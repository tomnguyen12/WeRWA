package vn.werwa.onchain.signing;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.SignedRawTransaction;
import org.web3j.crypto.TransactionDecoder;
import org.web3j.utils.Numeric;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.onchain.api.SignerKey;

/**
 * Ký qua WeHSM (ADR-06; I13, Q14): gọi {@code eth_signTransaction} với Basic auth {@code wehsm:<token>}. WeHSM kiểm chính
 * sách ký rồi nhờ HSM ký qua PKCS#11; khóa không rời HSM. Kết quả được giải mã lại để chắc WeHSM ký đúng giao dịch đã
 * yêu cầu, từ đúng địa chỉ.
 *
 * <p>Địa chỉ khóa vận hành Holco đọc trên chuỗi (vai OPERATOR của {@code AuditAnchor}), không cấu hình tay.
 */
public class WeHsmSigner implements TransactionSigner {

    private final URI url;
    private final String authorization;
    private final HttpClient http;
    private final JsonMapper json;
    private final Duration timeout;
    private final Supplier<String> operatorAddress;
    private final AtomicReference<String> operator = new AtomicReference<>();

    private final String relayerAddress;

    public WeHsmSigner(URI url, String token, JsonMapper json, Duration timeout, Supplier<String> operatorAddress,
            String relayerAddress) {
        this.relayerAddress = relayerAddress == null || relayerAddress.isBlank() ? null
                : relayerAddress.toLowerCase(Locale.ROOT);
        this.url = url;
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(("wehsm:" + token).getBytes(StandardCharsets.UTF_8));
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.json = json;
        this.timeout = timeout;
        this.operatorAddress = operatorAddress;
    }

    @Override
    public String address(SignerKey key) {
        if (key == SignerKey.RELAYER && relayerAddress != null) {
            return relayerAddress;
        }
        return operator.updateAndGet(current -> current != null ? current : operatorAddress.get().toLowerCase(Locale.ROOT));
    }

    @Override
    public String sign(SignerKey key, RawTransaction tx, long chainId) {
        String from = address(key);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", from);
        params.put("to", tx.getTo());
        params.put("nonce", Numeric.encodeQuantity(tx.getNonce()));
        params.put("gas", Numeric.encodeQuantity(tx.getGasLimit()));
        params.put("gasPrice", Numeric.encodeQuantity(tx.getGasPrice()));
        params.put("value", Numeric.encodeQuantity(BigInteger.ZERO));
        params.put("data", tx.getData());
        params.put("chainId", Numeric.encodeQuantity(BigInteger.valueOf(chainId)));
        Object result = call("eth_signTransaction", List.of(params));
        String raw = result instanceof Map<?, ?> m ? String.valueOf(m.get("raw")) : String.valueOf(result);
        verify(raw, tx, from, chainId);
        return raw;
    }

    /** WeHSM phải ký đúng giao dịch được yêu cầu, từ đúng khóa, đúng chain. */
    private static void verify(String raw, RawTransaction expected, String from, long chainId) {
        if (!(TransactionDecoder.decode(raw) instanceof SignedRawTransaction signed)) {
            throw new SignerUnavailable("WeHSM trả giao dịch chưa ký");
        }
        try {
            boolean same = signed.getFrom().equalsIgnoreCase(from)
                    && signed.getNonce().equals(expected.getNonce())
                    && signed.getTo().equalsIgnoreCase(expected.getTo())
                    && Numeric.cleanHexPrefix(signed.getData()).equalsIgnoreCase(Numeric.cleanHexPrefix(expected.getData()))
                    && signed.getGasLimit().equals(expected.getGasLimit())
                    && (signed.getChainId() == null || signed.getChainId() == chainId);
            if (!same) {
                throw new SignerUnavailable("WeHSM ký giao dịch khác với yêu cầu");
            }
        } catch (java.security.SignatureException e) {
            throw new SignerUnavailable("chữ ký của WeHSM không hợp lệ", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Object call(String method, List<Object> params) {
        Map<String, Object> body = Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params);
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new SignerUnavailable("WeHSM không trả lời: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SignerUnavailable("bị ngắt khi chờ WeHSM", e);
        }
        if (response.statusCode() != 200) {
            throw new SignerUnavailable("WeHSM trả HTTP " + response.statusCode());
        }
        Map<String, Object> reply = json.readValue(response.body(), Map.class);
        if (reply.get("error") instanceof Map<?, ?> error) {
            throw new SignerUnavailable("WeHSM từ chối " + method + ": " + error.get("message"));
        }
        return reply.get("result");
    }
}
