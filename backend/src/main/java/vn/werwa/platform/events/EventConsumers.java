package vn.werwa.platform.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.platform.id.Ids;

/**
 * Nhận sự kiện từ NATS cho mọi {@link EventHandler} (D4; Q9, Q10). Mỗi bên nhận một consumer bền kiểu pull, ack thủ công:
 *
 * <ol>
 *   <li>Trong một giao dịch DB: ghi {@code platform.inbox} (consumer, id sự kiện); đã có thì bỏ qua; chưa có thì xử lý.
 *   <li>Commit xong mới ack. Lỗi thì rollback và báo JetStream gửi lại sau khoảng chờ của lần đó.
 *   <li>Hết số lần thử, hoặc sự kiện không đọc được: ghi {@code platform.event_dead_letter} và dừng gửi lại.
 * </ol>
 */
public class EventConsumers implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EventConsumers.class);

    private final Connection connection;
    private final JetStream jetStream;
    private final JetStreamManagement jsm;
    private final List<EventHandler> handlers;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final String stream;
    private final EventsProperties props;
    private final Clock clock;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;

    public EventConsumers(
            Connection connection,
            JetStream jetStream,
            JetStreamManagement jsm,
            List<EventHandler> handlers,
            EventCatalog catalog,
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            JsonMapper json,
            String stream,
            EventsProperties props,
            Clock clock) {
        this.connection = connection;
        this.jetStream = jetStream;
        this.jsm = jsm;
        this.handlers = List.copyOf(handlers);
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.stream = stream;
        this.props = props;
        this.clock = clock;
        Set<String> names = new HashSet<>();
        for (EventHandler h : handlers) {
            catalog.producerOf(h.eventType());
            if (!names.add(durableName(h))) {
                throw new IllegalStateException("hai bên nhận trùng consumer " + durableName(h));
            }
        }
    }

    /** {@code <consumer>__<loại sự kiện, dấu chấm thành gạch dưới>}; NATS không cho dấu chấm trong tên consumer. */
    static String durableName(EventHandler h) {
        return h.consumer() + "__" + h.eventType().replace('.', '_');
    }

    @Override
    public void start() {
        running = true;
        for (EventHandler handler : handlers) {
            JetStreamSubscription subscription = subscribe(handler);
            workers.add(Thread.ofVirtual().name("event-" + durableName(handler)).start(() -> poll(handler, subscription)));
        }
        log.info("Nhận sự kiện: {} consumer", handlers.size());
    }

    @Override
    public void stop() {
        running = false;
        for (Thread worker : workers) {
            worker.interrupt();
        }
        for (Thread worker : workers) {
            try {
                worker.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        workers.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private JetStreamSubscription subscribe(EventHandler handler) {
        String durable = durableName(handler);
        try {
            ConsumerConfiguration config = ConsumerConfiguration.builder()
                    .durable(durable)
                    .filterSubject(handler.eventType())
                    .ackPolicy(AckPolicy.Explicit)
                    .deliverPolicy(DeliverPolicy.All)
                    .ackWait(props.ackWait())
                    .maxDeliver(props.maxDeliver())
                    .build();
            jsm.addOrUpdateConsumer(stream, config);
            return jetStream.subscribe(
                    handler.eventType(), PullSubscribeOptions.builder().stream(stream).bind(true).durable(durable).build());
        } catch (Exception e) {
            throw new IllegalStateException("không tạo được consumer NATS " + durable, e);
        }
    }

    private void poll(EventHandler handler, JetStreamSubscription subscription) {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                for (Message message : subscription.fetch(props.fetchBatch(), Duration.ofSeconds(1))) {
                    process(handler, message);
                }
            } catch (IllegalStateException e) {
                if (running && connection.getStatus() == Connection.Status.CONNECTED) {
                    log.warn("Lỗi đọc sự kiện của {}", durableName(handler), e);
                }
                sleepQuietly();
            } catch (RuntimeException e) {
                log.warn("Lỗi đọc sự kiện của {}", durableName(handler), e);
                sleepQuietly();
            }
        }
    }

    void process(EventHandler handler, Message message) {
        long delivered = message.metaData().deliveredCount();
        Envelope envelope;
        try {
            envelope = json.readValue(message.getData(), Envelope.class);
            UUID.fromString(envelope.id());
        } catch (RuntimeException e) {
            deadLetter(handler, null, message, "không đọc được vỏ CloudEvents: " + e.getMessage(), delivered);
            message.term();
            return;
        }
        InboundEvent event = new InboundEvent(envelope.id(), envelope.type(), envelope.source(), envelope.subject(),
                envelope.time(), envelope.traceparent(), envelope.data() == null ? Map.of() : envelope.data(), json);
        try {
            TraceContext.with(event.traceparent(), () -> tx.executeWithoutResult(status -> {
                int inserted = jdbc.update("""
                        INSERT INTO platform.inbox (consumer, event_id, event_type, processed_at)
                        VALUES (?, ?, ?, ?) ON CONFLICT (consumer, event_id) DO NOTHING
                        """, handler.consumer(), UUID.fromString(event.id()), event.type(), Timestamp.from(clock.instant()));
                if (inserted == 1) {
                    handler.handle(event);
                } else {
                    log.debug("Bỏ qua {} đã xử lý bởi {}", event, handler.consumer());
                }
            }));
            message.ack();
        } catch (RuntimeException e) {
            if (delivered >= props.maxDeliver()) {
                log.error("{} lỗi sau {} lần, chuyển thư chết", event, delivered, e);
                deadLetter(handler, event.id(), message, String.valueOf(e), delivered);
                message.term();
            } else {
                Duration delay = props.retryDelays().get((int) delivered - 1);
                log.warn("{} lỗi lần {}, thử lại sau {}", event, delivered, delay, e);
                message.nakWithDelay(delay);
            }
        }
    }

    private void deadLetter(EventHandler handler, String eventId, Message message, String error, long delivered) {
        jdbc.update("""
                INSERT INTO platform.event_dead_letter
                    (id, consumer, event_id, event_type, payload, error, deliveries, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, Ids.newId(), handler.consumer(), eventId == null ? null : UUID.fromString(eventId),
                handler.eventType(), new String(message.getData(), java.nio.charset.StandardCharsets.UTF_8),
                error.length() > 4000 ? error.substring(0, 4000) : error, (int) delivered, Timestamp.from(clock.instant()));
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(Duration.ofSeconds(1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(
            String specversion,
            String id,
            String type,
            String source,
            String subject,
            String time,
            String traceparent,
            String datacontenttype,
            Map<String, Object> data) {}
}
