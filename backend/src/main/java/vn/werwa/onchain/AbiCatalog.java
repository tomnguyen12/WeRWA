package vn.werwa.onchain;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.Resource;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Array;
import org.web3j.abi.datatypes.Type;
import org.web3j.crypto.Hash;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.utils.Numeric;
import tools.jackson.databind.json.JsonMapper;

/**
 * ABI của mọi hợp đồng trong {@code api/abi} (chép vào classpath {@code contract/abi/}), đọc lúc chạy để giải mã log cho
 * indexer (Q17) và custom error khi chạy thử (Q16). Không cần biết trước từng sự kiện: ABI đổi thì giải mã đổi theo.
 */
public final class AbiCatalog {

    private static final String ERROR_STRING = "0x08c379a0";
    private static final String PANIC = "0x4e487b71";

    private final Map<String, Map<String, EventDef>> events;
    private final Map<String, ErrorDef> errors;

    private AbiCatalog(Map<String, Map<String, EventDef>> events, Map<String, ErrorDef> errors) {
        this.events = events;
        this.errors = errors;
    }

    public record Param(String name, String type, boolean indexed, List<Param> components) {

        String canonical() {
            if (!type.startsWith("tuple")) {
                return type;
            }
            StringBuilder s = new StringBuilder("(");
            for (int i = 0; i < components.size(); i++) {
                s.append(i == 0 ? "" : ",").append(components.get(i).canonical());
            }
            return s.append(')').append(type.substring("tuple".length())).toString();
        }

        boolean dynamic() {
            return type.equals("string") || type.equals("bytes") || type.endsWith("]") || type.startsWith("tuple");
        }
    }

    public record EventDef(String contract, String name, String topic0, List<Param> inputs) {}

    public record ErrorDef(String contract, String name, String selector, List<Param> inputs) {}

    /** Lỗi hợp đồng giải mã được: tên custom error và đối số. */
    public record Revert(String name, Map<String, Object> args) {}

    @SuppressWarnings("unchecked")
    public static AbiCatalog load(Resource[] files, JsonMapper json) {
        Map<String, Map<String, EventDef>> events = new HashMap<>();
        Map<String, ErrorDef> errors = new HashMap<>();
        for (Resource file : files) {
            String name = file.getFilename();
            if (name == null || name.equals("manifest.json") || !name.endsWith(".json")) {
                continue;
            }
            String contract = name.substring(0, name.length() - ".json".length());
            List<Map<String, Object>> abi;
            try (InputStream in = file.getInputStream()) {
                abi = json.readValue(in, List.class);
            } catch (IOException e) {
                throw new IllegalStateException("không đọc được ABI " + name, e);
            }
            Map<String, EventDef> byTopic = new HashMap<>();
            for (Map<String, Object> entry : abi) {
                String kind = (String) entry.get("type");
                if (!"event".equals(kind) && !"error".equals(kind)) {
                    continue;
                }
                List<Param> inputs = params((List<Object>) entry.getOrDefault("inputs", List.of()));
                String signature = entry.get("name") + "(" + String.join(",", inputs.stream().map(Param::canonical).toList()) + ")";
                String hash = Hash.sha3String(signature);
                if ("event".equals(kind)) {
                    byTopic.put(hash, new EventDef(contract, (String) entry.get("name"), hash, inputs));
                } else {
                    String selector = hash.substring(0, 10);
                    errors.putIfAbsent(selector, new ErrorDef(contract, (String) entry.get("name"), selector, inputs));
                }
            }
            events.put(contract, Map.copyOf(byTopic));
        }
        return new AbiCatalog(Map.copyOf(events), Map.copyOf(errors));
    }

    public boolean hasContract(String contract) {
        return events.containsKey(contract);
    }

    public Optional<EventDef> event(String contract, String topic0) {
        return Optional.ofNullable(events.getOrDefault(contract, Map.of()).get(topic0));
    }

    /** Đối số của log theo tên tham số. Tham số tuple không giải mã được thì ghi dữ liệu thô vào {@code _data}. */
    public Map<String, Object> decode(EventDef event, Log log) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Param> plain = new ArrayList<>();
        int topic = 1;
        for (Param p : event.inputs()) {
            if (p.indexed()) {
                String raw = log.getTopics().get(topic++);
                out.put(p.name(), p.dynamic() ? raw : json(decodeIndexed(raw, p)));
            } else {
                plain.add(p);
            }
        }
        if (plain.stream().anyMatch(p -> p.type().startsWith("tuple"))) {
            out.put("_data", log.getData());
            return out;
        }
        List<Type> values = decodeData(log.getData(), plain);
        for (int i = 0; i < plain.size(); i++) {
            out.put(plain.get(i).name(), json(values.get(i)));
        }
        return out;
    }

    /** Giải mã dữ liệu revert của {@code eth_call}: {@code Error(string)}, {@code Panic(uint256)} hoặc custom error. */
    public Revert decodeRevert(String data) {
        if (data == null || data.length() < 10) {
            return new Revert("UNKNOWN", Map.of());
        }
        String selector = data.substring(0, 10).toLowerCase(java.util.Locale.ROOT);
        String body = "0x" + data.substring(10);
        if (selector.equals(ERROR_STRING)) {
            List<Type> v = decodeData(body, List.of(new Param("reason", "string", false, List.of())));
            return new Revert("Error", Map.of("reason", json(v.getFirst())));
        }
        if (selector.equals(PANIC)) {
            List<Type> v = decodeData(body, List.of(new Param("code", "uint256", false, List.of())));
            return new Revert("Panic", Map.of("code", json(v.getFirst())));
        }
        ErrorDef error = errors.get(selector);
        if (error == null) {
            return new Revert("UNKNOWN", Map.of("data", data));
        }
        Map<String, Object> args = new LinkedHashMap<>();
        if (error.inputs().stream().noneMatch(p -> p.type().startsWith("tuple"))) {
            List<Type> values = decodeData(body, error.inputs());
            for (int i = 0; i < error.inputs().size(); i++) {
                args.put(error.inputs().get(i).name(), json(values.get(i)));
            }
        }
        return new Revert(error.name(), args);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Type> decodeData(String data, List<Param> params) {
        if (params.isEmpty()) {
            return List.of();
        }
        List<TypeReference<Type>> refs = new ArrayList<>();
        for (Param p : params) {
            refs.add((TypeReference) typeRef(p, false));
        }
        return FunctionReturnDecoder.decode(data, refs);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Type decodeIndexed(String topic, Param p) {
        return FunctionReturnDecoder.decodeIndexedValue(topic, (TypeReference) typeRef(p, true));
    }

    private static TypeReference<?> typeRef(Param p, boolean indexed) {
        try {
            return TypeReference.makeTypeReference(p.type(), indexed, false);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("kiểu ABI không hỗ trợ: " + p.type(), e);
        }
    }

    /** Giá trị ABI sang dạng JSON: số nguyên thành chuỗi (không mất chính xác), bytes thành hex. */
    static Object json(Type<?> value) {
        Object v = value.getValue();
        if (value instanceof Array<?> array) {
            List<Object> out = new ArrayList<>();
            for (Type<?> item : array.getValue()) {
                out.add(json(item));
            }
            return out;
        }
        if (v instanceof BigInteger n) {
            return n.toString();
        }
        if (v instanceof byte[] bytes) {
            return Numeric.toHexString(bytes);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private static List<Param> params(List<Object> raw) {
        List<Param> out = new ArrayList<>();
        for (Object o : raw) {
            Map<String, Object> p = (Map<String, Object>) o;
            out.add(new Param(
                    (String) p.get("name"),
                    (String) p.get("type"),
                    Boolean.TRUE.equals(p.get("indexed")),
                    params((List<Object>) p.getOrDefault("components", List.of()))));
        }
        return List.copyOf(out);
    }
}
