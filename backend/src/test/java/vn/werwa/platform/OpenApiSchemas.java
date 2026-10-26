package vn.werwa.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Kiểm thân response khớp lược đồ trong {@code api/openapi.yaml} (CLAUDE.md: request/response trong kiểm thử phải khớp
 * đặc tả). OpenAPI 3.1 dùng JSON Schema 2020-12, nên kiểm thẳng bằng bộ kiểm JSON Schema.
 */
public final class OpenApiSchemas {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> COMPONENTS = loadComponents();

    private OpenApiSchemas() {}

    /** Danh sách lỗi; rỗng nếu {@code json} khớp {@code #/components/schemas/<name>}. */
    public static Set<ValidationMessage> validate(String schemaName, String json) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("$ref", "#/components/schemas/" + schemaName);
        root.put("components", COMPONENTS);
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(JSON.valueToTree(root));
        JsonNode node = JSON.readTree(json);
        return schema.validate(node);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadComponents() {
        try (InputStream in = OpenApiSchemas.class.getResourceAsStream("/contract/openapi.yaml")) {
            LoaderOptions options = new LoaderOptions();
            options.setMaxAliasesForCollections(500);
            Map<String, Object> root = new Yaml(new SafeConstructor(options)).load(in);
            return (Map<String, Object>) root.get("components");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
