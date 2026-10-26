package vn.werwa.platform.events;

import java.util.Map;

/**
 * Sự kiện đã ghi vào outbox, chờ phát lên NATS. Mọi trường cố định từ lúc phát trong giao dịch nghiệp vụ, nên các lần
 * phát lại có cùng {@code id} (NATS chống trùng theo {@code Nats-Msg-Id}, bên nhận chống trùng bằng inbox).
 */
public record OutboundEvent(
        String id,
        String type,
        String source,
        String subject,
        String time,
        String traceparent,
        Map<String, Object> data) {}
