package vn.werwa.platform.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import vn.werwa.platform.security.Role;

/**
 * Hợp đồng {@code api/openapi.yaml} đọc lúc chạy (B1.0): mỗi API có vai được gọi ({@code x-werwa-roles}), kênh xác thực
 * và header bắt buộc. Backend kiểm quyền và {@code Idempotency-Key} theo bảng này, nên API mới chỉ cần khai báo ở đặc tả.
 */
public final class ApiContract {

    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

    private final List<Operation> operations;

    private ApiContract(List<Operation> operations) {
        this.operations = List.copyOf(operations);
    }

    public static ApiContract load(Resource spec) {
        try (InputStream in = spec.getInputStream()) {
            LoaderOptions options = new LoaderOptions();
            options.setMaxAliasesForCollections(500);
            Map<String, Object> root = new Yaml(new SafeConstructor(options)).load(in);
            return new ApiContract(parse(root));
        } catch (IOException e) {
            throw new IllegalStateException("không đọc được hợp đồng API " + spec, e);
        }
    }

    public List<Operation> operations() {
        return operations;
    }

    /** API khớp đường dẫn và phương thức; nhiều mẫu cùng khớp thì lấy mẫu cụ thể nhất. */
    public Optional<Operation> match(HttpMethod method, String path) {
        PathContainer container = PathContainer.parsePath(path);
        return operations.stream()
                .filter(op -> op.method().equals(method) && op.pattern().matches(container))
                .min(Comparator.comparing(Operation::pattern, PathPattern.SPECIFICITY_COMPARATOR));
    }

    /** Có API nào ở đường dẫn này với phương thức khác không (để trả 405 thay vì 404). */
    public boolean hasPath(String path) {
        PathContainer container = PathContainer.parsePath(path);
        return operations.stream().anyMatch(op -> op.pattern().matches(container));
    }

    /**
     * Một API của hợp đồng. {@code anyRole} ứng với {@code x-werwa-roles: [ANY]}: mọi vai, quyền kiểm theo đối tượng ở
     * tầng nghiệp vụ. {@code bankMtls} là webhook ngân hàng, không dùng token OIDC.
     */
    public record Operation(
            String operationId,
            HttpMethod method,
            String path,
            PathPattern pattern,
            boolean anyRole,
            Set<Role> roles,
            boolean bankMtls,
            boolean idempotencyKeyRequired,
            boolean ifMatchRequired) {}

    @SuppressWarnings("unchecked")
    private static List<Operation> parse(Map<String, Object> root) {
        Map<String, Object> paths = (Map<String, Object>) root.get("paths");
        Map<String, Object> components = (Map<String, Object>) root.getOrDefault("components", Map.of());
        Map<String, Object> sharedParams = (Map<String, Object>) components.getOrDefault("parameters", Map.of());
        PathPatternParser parser = new PathPatternParser();
        List<Operation> out = new ArrayList<>();
        for (Map.Entry<String, Object> p : paths.entrySet()) {
            Map<String, Object> item = (Map<String, Object>) p.getValue();
            List<Object> pathParams = (List<Object>) item.getOrDefault("parameters", List.of());
            for (Map.Entry<String, Object> m : item.entrySet()) {
                if (!METHODS.contains(m.getKey())) {
                    continue;
                }
                Map<String, Object> op = (Map<String, Object>) m.getValue();
                String id = (String) op.get("operationId");
                List<Object> rolesRaw = (List<Object>) op.get("x-werwa-roles");
                if (rolesRaw == null || rolesRaw.isEmpty()) {
                    throw new IllegalStateException("API " + id + " thiếu x-werwa-roles");
                }
                boolean any = rolesRaw.equals(List.of("ANY"));
                Set<Role> roles = EnumSet.noneOf(Role.class);
                if (!any) {
                    rolesRaw.forEach(r -> roles.add(Role.valueOf((String) r)));
                }
                List<Object> security = (List<Object>) op.getOrDefault("security", List.of());
                boolean mtls = security.stream().anyMatch(s -> ((Map<String, Object>) s).containsKey("bankMtls"));
                Map<String, Map<String, Object>> headers = requiredHeaders(pathParams, op, sharedParams);
                out.add(new Operation(
                        id,
                        HttpMethod.valueOf(m.getKey().toUpperCase(java.util.Locale.ROOT)),
                        p.getKey(),
                        parser.parse(p.getKey()),
                        any,
                        Set.copyOf(roles),
                        mtls,
                        headers.containsKey("idempotency-key"),
                        headers.containsKey("if-match")));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> requiredHeaders(
            List<Object> pathParams, Map<String, Object> op, Map<String, Object> shared) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        List<Object> all = new ArrayList<>(pathParams);
        all.addAll((List<Object>) op.getOrDefault("parameters", List.of()));
        for (Object raw : all) {
            Map<String, Object> param = (Map<String, Object>) raw;
            if (param.containsKey("$ref")) {
                String ref = (String) param.get("$ref");
                param = (Map<String, Object>) shared.get(ref.substring(ref.lastIndexOf('/') + 1));
            }
            if ("header".equals(param.get("in")) && Boolean.TRUE.equals(param.get("required"))) {
                out.put(((String) param.get("name")).toLowerCase(java.util.Locale.ROOT), param);
            }
        }
        return out;
    }
}
