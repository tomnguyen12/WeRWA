package vn.werwa.platform.events;

import io.nats.client.JetStream;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.Headers;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Đẩy sự kiện từ outbox lên NATS sau khi giao dịch commit. Spring Modulith ghi mỗi sự kiện vào
 * {@code platform.event_publication} cùng giao dịch nghiệp vụ, rồi chỉ đánh dấu xong khi phương thức này chạy hết không
 * lỗi, tức là khi JetStream đã xác nhận lưu. Lỗi thì sự kiện nằm lại và {@link EventMaintenance} phát lại.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JetStream jetStream;
    private final JsonMapper json;
    private final String stream;
    private final EventsProperties props;

    public OutboxRelay(JetStream jetStream, JsonMapper json, String stream, EventsProperties props) {
        this.jetStream = jetStream;
        this.json = json;
        this.stream = stream;
        this.props = props;
    }

    @TransactionalEventListener
    public void relay(OutboundEvent event) throws Exception {
        Headers headers = new Headers();
        headers.put("ce-type", event.type());
        if (event.traceparent() != null) {
            headers.put("traceparent", event.traceparent());
        }
        // messageId đặt header Nats-Msg-Id: NATS chống trùng 2 phút (I2)
        PublishOptions options = PublishOptions.builder().expectedStream(stream).messageId(event.id()).build();
        PublishAck ack = jetStream.publishAsync(event.type(), headers, cloudEvent(event), options)
                .get(props.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        if (ack.isDuplicate()) {
            log.debug("Sự kiện {} đã có trên stream (phát lại)", event.id());
        }
    }

    /** Vỏ CloudEvents 1.0 theo {@code api/events/cloudevent.schema.json}. */
    byte[] cloudEvent(OutboundEvent event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", event.id());
        envelope.put("type", event.type());
        envelope.put("source", event.source());
        if (event.subject() != null) {
            envelope.put("subject", event.subject());
        }
        envelope.put("time", event.time());
        if (event.traceparent() != null) {
            envelope.put("traceparent", event.traceparent());
        }
        envelope.put("datacontenttype", "application/json");
        envelope.put("data", event.data());
        return json.writeValueAsBytes(envelope);
    }
}
