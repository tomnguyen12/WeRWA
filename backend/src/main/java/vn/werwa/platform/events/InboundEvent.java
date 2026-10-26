package vn.werwa.platform.events;

import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Sự kiện nhận từ NATS, vỏ CloudEvents đã đọc. */
public record InboundEvent(
        String id,
        String type,
        String source,
        String subject,
        String time,
        String traceparent,
        Map<String, Object> data,
        JsonMapper json) {

    /** Đọc {@code data} thành kiểu của bên nhận. */
    public <T> T data(Class<T> type) {
        return json.convertValue(data, type);
    }

    @Override
    public String toString() {
        return "InboundEvent[" + type + " " + id + "]";
    }
}
