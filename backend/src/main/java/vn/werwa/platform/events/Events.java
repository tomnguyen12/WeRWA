package vn.werwa.platform.events;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.platform.id.Ids;

/**
 * Phát sự kiện giữa các mô-đun (D4). Gọi trong giao dịch của nghiệp vụ: sự kiện ghi vào outbox cùng giao dịch, commit xong
 * mới phát lên NATS; rollback thì không có sự kiện nào ra.
 *
 * <pre>{@code
 * events.publish("werwa.onboarding.organization.kyb_approved.v1", "organization/" + id,
 *         new KybApproved(id, roles));
 * }</pre>
 */
public class Events {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final ApplicationEventPublisher publisher;
    private final EventCatalog catalog;
    private final JsonMapper json;
    private final Clock clock;

    public Events(ApplicationEventPublisher publisher, EventCatalog catalog, JsonMapper json, Clock clock) {
        this.publisher = publisher;
        this.catalog = catalog;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param type tên sự kiện {@code werwa.<module>.<entity>.<event>.v<n>}, phải có trong catalog
     * @param subject đối tượng của sự kiện, ví dụ {@code allocation/<id>}
     * @param data dữ liệu theo lược đồ {@code api/events/<type>.schema.json}
     * @return id CloudEvent (UUIDv7)
     */
    public String publish(String type, String subject, Object data) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("phát sự kiện phải nằm trong giao dịch DB (outbox): " + type);
        }
        String producer = catalog.producerOf(type);
        String id = Ids.newId().toString();
        String time = DateTimeFormatter.ISO_INSTANT.format(clock.instant());
        Map<String, Object> payload = json.convertValue(data, MAP);
        publisher.publishEvent(
                new OutboundEvent(id, type, "/werwa/" + producer, subject, time, TraceContext.current(), payload));
        return id;
    }
}
