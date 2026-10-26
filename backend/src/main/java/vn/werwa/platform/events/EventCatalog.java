package vn.werwa.platform.events;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Danh mục {@code api/events/catalog.yaml}: chỉ phát được loại sự kiện có ở đây, đúng mô-đun phát (Q13). */
public final class EventCatalog {

    private final Map<String, String> producers;

    private EventCatalog(Map<String, String> producers) {
        this.producers = Map.copyOf(producers);
    }

    @SuppressWarnings("unchecked")
    public static EventCatalog load(Resource catalog) {
        try (InputStream in = catalog.getInputStream()) {
            Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            Map<String, String> producers = new HashMap<>();
            for (Object e : (List<Object>) root.get("events")) {
                Map<String, Object> event = (Map<String, Object>) e;
                producers.put((String) event.get("type"), (String) event.get("producer"));
            }
            return new EventCatalog(producers);
        } catch (IOException e) {
            throw new IllegalStateException("không đọc được danh mục sự kiện " + catalog, e);
        }
    }

    public Set<String> types() {
        return producers.keySet();
    }

    /** Mô-đun phát của loại sự kiện; lỗi nếu loại không có trong danh mục. */
    public String producerOf(String type) {
        String producer = producers.get(type);
        if (producer == null) {
            throw new IllegalArgumentException("loại sự kiện không có trong api/events/catalog.yaml: " + type);
        }
        return producer;
    }
}
