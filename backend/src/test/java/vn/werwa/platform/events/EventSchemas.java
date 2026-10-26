package vn.werwa.platform.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.util.Set;

/** Kiểm sự kiện CloudEvents khớp {@code api/events/<type>.schema.json} (kèm vỏ {@code cloudevent.schema.json}). */
public final class EventSchemas {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PREFIX = "https://schemas.werwa/events/";
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaMappers(m -> m.mapPrefix(PREFIX, "classpath:contract/events/")));

    private EventSchemas() {}

    public static Set<ValidationMessage> validate(String type, byte[] cloudEvent) throws IOException {
        JsonSchema schema = FACTORY.getSchema(SchemaLocation.of(PREFIX + type + ".schema.json"));
        return schema.validate(JSON.readTree(cloudEvent));
    }
}
