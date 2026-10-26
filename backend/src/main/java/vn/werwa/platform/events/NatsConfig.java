package vn.werwa.platform.events;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.platform.config.WerwaProperties;

/** Kết nối NATS JetStream (I2) và các bean sự kiện của B2. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EventsProperties.class)
class NatsConfig {

    private static final Logger log = LoggerFactory.getLogger(NatsConfig.class);

    @Bean(destroyMethod = "close")
    Connection natsConnection(WerwaProperties werwa) throws IOException, InterruptedException {
        WerwaProperties.Nats nats = werwa.nats();
        Options.Builder options = new Options.Builder()
                .server(nats.url().toString())
                .connectionName("werwa-backend")
                .maxReconnects(-1)
                .reconnectWait(Duration.ofSeconds(1));
        if (nats.username() != null && !nats.username().isBlank()) {
            options.userInfo(nats.username(), nats.password());
        }
        return Nats.connect(options.build());
    }

    @Bean
    JetStream jetStream(Connection connection) throws IOException {
        return connection.jetStream();
    }

    @Bean
    JetStreamManagement jetStreamManagement(Connection connection) throws IOException {
        return connection.jetStreamManagement();
    }

    /** Q12: stream do infra tạo; backend chỉ kiểm có tồn tại và đúng cấu hình I2, sai thì không khởi động. */
    @Bean
    StreamGuard streamGuard(
            JetStreamManagement jsm,
            WerwaProperties werwa,
            @Value("classpath:contract/nats/stream-werwa.json") Resource expected)
            throws IOException, JetStreamApiException {
        StreamGuard guard = new StreamGuard(jsm, werwa.nats().stream(), readJson(expected));
        guard.verify();
        return guard;
    }

    @Bean
    EventCatalog eventCatalog(@Value("classpath:contract/events/catalog.yaml") Resource catalog) {
        EventCatalog c = EventCatalog.load(catalog);
        log.info("Nạp danh mục sự kiện: {} loại", c.types().size());
        return c;
    }

    @Bean
    Events events(ApplicationEventPublisher publisher, EventCatalog catalog, JsonMapper json, Clock clock) {
        return new Events(publisher, catalog, json, clock);
    }

    @Bean
    OutboxRelay outboxRelay(JetStream jetStream, JsonMapper json, WerwaProperties werwa, EventsProperties props) {
        return new OutboxRelay(jetStream, json, werwa.nats().stream(), props);
    }

    @Bean
    EventMaintenance eventMaintenance(
            IncompleteEventPublications incomplete, JdbcTemplate jdbc, EventsProperties props, Clock clock) {
        return new EventMaintenance(incomplete, jdbc, props, clock);
    }

    @Bean
    EventConsumers eventConsumers(
            Connection connection,
            JetStream jetStream,
            JetStreamManagement jsm,
            ObjectProvider<EventHandler> handlers,
            EventCatalog catalog,
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            JsonMapper json,
            WerwaProperties werwa,
            EventsProperties props,
            Clock clock) {
        return new EventConsumers(
                connection, jetStream, jsm, handlers.orderedStream().toList(), catalog, jdbc, tx, json, werwa.nats().stream(), props, clock);
    }

    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<TraceparentFilter> traceparentFilter() {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new TraceparentFilter());
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    private static String readJson(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** So stream đang chạy với tệp {@code infra/services/nats/stream-werwa.json}. */
    static class StreamGuard {

        private final JetStreamManagement jsm;
        private final String name;
        private final StreamConfiguration expected;

        StreamGuard(JetStreamManagement jsm, String name, String expectedJson) {
            this.jsm = jsm;
            this.name = name;
            try {
                this.expected = StreamConfiguration.instance(expectedJson);
            } catch (io.nats.client.support.JsonParseException e) {
                throw new IllegalStateException("tệp cấu hình stream không đọc được", e);
            }
        }

        void verify() throws IOException, JetStreamApiException {
            StreamInfo info;
            try {
                info = jsm.getStreamInfo(name);
            } catch (JetStreamApiException e) {
                throw new IllegalStateException(
                        "không có stream NATS " + name + "; infra tạo stream (infra/services/services.sh up), backend không tự tạo (Q12)", e);
            }
            StreamConfiguration actual = info.getConfiguration();
            List<String> diffs = new ArrayList<>();
            check(diffs, "subjects", expected.getSubjects(), actual.getSubjects());
            check(diffs, "storage", expected.getStorageType(), actual.getStorageType());
            check(diffs, "retention", expected.getRetentionPolicy(), actual.getRetentionPolicy());
            check(diffs, "max_age", expected.getMaxAge(), actual.getMaxAge());
            check(diffs, "duplicate_window", expected.getDuplicateWindow(), actual.getDuplicateWindow());
            check(diffs, "deny_delete", expected.getDenyDelete(), actual.getDenyDelete());
            check(diffs, "deny_purge", expected.getDenyPurge(), actual.getDenyPurge());
            if (!diffs.isEmpty()) {
                throw new IllegalStateException("stream NATS " + name + " lệch cấu hình I2: " + diffs);
            }
            log.info("Stream NATS {} đúng cấu hình I2", name);
        }

        private static void check(List<String> diffs, String field, Object expected, Object actual) {
            if (!Objects.equals(expected, actual)) {
                diffs.add(field + " mong đợi " + expected + ", đang là " + actual);
            }
        }
    }
}
