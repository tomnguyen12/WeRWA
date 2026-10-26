package vn.werwa.platform.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.PublishOptions;
import io.nats.client.api.MessageInfo;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import vn.werwa.IntegrationTest;
import vn.werwa.platform.id.Ids;

/** Sự kiện giữa mô-đun qua outbox, NATS JetStream và inbox (B2; D4, I2, Q9–Q13), với PostgreSQL và NATS thật. */
@Import(EventsIT.Handlers.class)
@TestPropertySource(properties = {
    "werwa.events.retry-delays=PT0.2S,PT0.2S",
    "werwa.events.resubmit-interval=PT1S",
    "werwa.events.resubmit-older-than=PT1S",
    "werwa.events.publish-timeout=PT1S",
    "werwa.events.ack-wait=PT5S"
})
class EventsIT extends IntegrationTest {

    static final String KYB_APPROVED = "werwa.onboarding.organization.kyb_approved.v1";
    static final String WALLET_REGISTERED = "werwa.onboarding.wallet.registered.v1";
    static final String KYC_EXPIRED = "werwa.onboarding.investor.kyc_expired.v1";
    static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    Events events;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Connection connection;

    @Autowired
    Handlers handlers;

    String publish(String type, Map<String, Object> data) {
        return tx.execute(status -> events.publish(type, "organization/" + data.get("organizationId"), data));
    }

    static Map<String, Object> kyb(UUID org) {
        return Map.of("organizationId", org.toString(), "roles", List.of("INVESTOR"));
    }

    @Test
    void commitPublishesACloudEventThatMatchesItsSchema() throws Exception {
        UUID org = Ids.newId();
        String id = publish(KYB_APPROVED, kyb(org));

        await().atMost(WAIT).until(() -> handlers.kyb.containsKey(id));
        InboundEvent received = handlers.kyb.get(id);
        assertThat(received.type()).isEqualTo(KYB_APPROVED);
        assertThat(received.source()).isEqualTo("/werwa/onboarding");
        assertThat(received.data().get("organizationId")).isEqualTo(org.toString());

        JetStreamManagement jsm = connection.jetStreamManagement();
        MessageInfo stored = jsm.getLastMessage("WERWA", KYB_APPROVED);
        assertThat(stored.getHeaders().getFirst("Nats-Msg-Id")).isEqualTo(id);
        assertThat(EventSchemas.validate(KYB_APPROVED, stored.getData())).isEmpty();
        await().atMost(WAIT).until(() -> outstanding() == 0);
    }

    @Test
    void rollbackPublishesNothing() {
        String id = tx.execute(status -> {
            String published = events.publish(KYB_APPROVED, "organization/x", kyb(Ids.newId()));
            status.setRollbackOnly();
            return published;
        });

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                .until(() -> !handlers.kyb.containsKey(id));
    }

    @Test
    void sameEventDeliveredTwiceIsHandledOnce() throws Exception {
        // Giả lập phát lại ngoài cửa sổ chống trùng của NATS: cùng id CloudEvent, khác Nats-Msg-Id.
        String id = Ids.newId().toString();
        byte[] body = ("{\"specversion\":\"1.0\",\"id\":\"" + id + "\",\"type\":\"" + KYB_APPROVED
                        + "\",\"source\":\"/werwa/onboarding\",\"time\":\"2027-01-20T03:15:22Z\","
                        + "\"datacontenttype\":\"application/json\",\"data\":{\"organizationId\":\""
                        + Ids.newId() + "\",\"roles\":[\"INVESTOR\"]}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        JetStream js = connection.jetStream();
        js.publish(KYB_APPROVED, null, body, PublishOptions.builder().messageId("a-" + id).build());
        js.publish(KYB_APPROVED, null, body, PublishOptions.builder().messageId("b-" + id).build());

        await().atMost(WAIT).until(() -> handlers.kyb.containsKey(id));
        Thread.sleep(1500);
        assertThat(handlers.kybCount.get(id).get()).isEqualTo(1);
    }

    @Test
    void failingHandlerIsRetriedUntilItSucceeds() {
        String id = publish(WALLET_REGISTERED, Map.of(
                "walletId", Ids.newId().toString(),
                "address", "0x1000000000000000000000000000000000000001",
                "onchainId", "0x2000000000000000000000000000000000000002"));

        await().atMost(WAIT).until(() -> handlers.walletDone.contains(id));
        assertThat(handlers.walletAttempts.get(id).get()).isEqualTo(3);
        assertThat(deadLetters(id)).isZero();
    }

    @Test
    void exhaustedRetriesGoToDeadLetter() {
        String id = publish(KYC_EXPIRED, Map.of("organizationId", Ids.newId().toString()));

        await().atMost(WAIT).until(() -> deadLetters(id) == 1);
        Integer deliveries = jdbc.queryForObject(
                "SELECT deliveries FROM platform.event_dead_letter WHERE event_id = ?", Integer.class, UUID.fromString(id));
        assertThat(deliveries).isEqualTo(3);
        // Lỗi đã rollback, nên inbox không đánh dấu đã xử lý.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.inbox WHERE event_id = ?", Integer.class,
                UUID.fromString(id))).isZero();
    }

    @Test
    void eventWaitsInOutboxWhileNatsIsDownThenIsPublished() {
        var docker = NATS.getDockerClient();
        docker.pauseContainerCmd(NATS.getContainerId()).exec();
        String id;
        try {
            id = publish(KYB_APPROVED, kyb(Ids.newId()));
            await().atMost(Duration.ofSeconds(5)).until(() -> outstanding() > 0);
            assertThat(handlers.kyb).doesNotContainKey(id);
        } finally {
            docker.unpauseContainerCmd(NATS.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(40)).until(() -> handlers.kyb.containsKey(id));
        await().atMost(WAIT).until(() -> outstanding() == 0);
    }

    @Test
    void traceparentFlowsFromPublisherToHandler() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        String[] id = new String[1];
        TraceContext.with(traceparent, () -> id[0] = publish(KYB_APPROVED, kyb(Ids.newId())));

        await().atMost(WAIT).until(() -> handlers.kyb.containsKey(id[0]));
        assertThat(handlers.kyb.get(id[0]).traceparent()).isEqualTo(traceparent);
    }

    @Test
    void onlyCatalogTypesInsideATransactionCanBePublished() {
        assertThatThrownBy(() -> publish("werwa.onboarding.organization.renamed.v1", kyb(Ids.newId())))
                .hasMessageContaining("catalog.yaml");
        assertThatThrownBy(() -> events.publish(KYB_APPROVED, "x", kyb(Ids.newId())))
                .hasMessageContaining("giao dịch");
    }

    @Test
    void streamGuardRejectsAStreamThatDriftsFromI2() throws Exception {
        String drifted = "{\"name\":\"WERWA\",\"subjects\":[\"werwa.>\"],\"storage\":\"file\",\"retention\":\"limits\","
                + "\"max_age\":86400000000000,\"duplicate_window\":120000000000,\"deny_delete\":true,\"deny_purge\":true}";
        var guard = new NatsConfig.StreamGuard(connection.jetStreamManagement(), "WERWA", drifted);
        assertThatThrownBy(guard::verify).hasMessageContaining("max_age");
        assertThatThrownBy(new NatsConfig.StreamGuard(connection.jetStreamManagement(), "NOT_THERE", drifted)::verify)
                .hasMessageContaining("Q12");
    }

    int outstanding() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL", Integer.class);
    }

    int deadLetters(String id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM platform.event_dead_letter WHERE event_id = ?", Integer.class, UUID.fromString(id));
    }

    /** Bên nhận giả của kiểm thử, đúng bên nhận trong catalog. */
    @TestConfiguration
    static class Handlers {

        final Map<String, InboundEvent> kyb = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> kybCount = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> walletAttempts = new ConcurrentHashMap<>();
        final List<String> walletDone = new CopyOnWriteArrayList<>();

        @Bean
        EventHandler governanceOnKybApproved() {
            return handler("governance", KYB_APPROVED, e -> {
                kyb.put(e.id(), e);
                kybCount.computeIfAbsent(e.id(), k -> new AtomicInteger()).incrementAndGet();
            });
        }

        /** Lỗi hai lần đầu, lần thứ ba thành công. */
        @Bean
        EventHandler distributionOnWalletRegistered() {
            return handler("distribution", WALLET_REGISTERED, e -> {
                int attempt = walletAttempts.computeIfAbsent(e.id(), k -> new AtomicInteger()).incrementAndGet();
                if (attempt < 3) {
                    throw new IllegalStateException("lỗi giả lập lần " + attempt);
                }
                walletDone.add(e.id());
            });
        }

        /** Luôn lỗi: hết lần thử thì vào thư chết. */
        @Bean
        EventHandler distributionOnKycExpired() {
            return handler("distribution", KYC_EXPIRED, e -> {
                throw new IllegalStateException("luôn lỗi");
            });
        }

        private static EventHandler handler(String consumer, String type, java.util.function.Consumer<InboundEvent> body) {
            return new EventHandler() {
                @Override
                public String consumer() {
                    return consumer;
                }

                @Override
                public String eventType() {
                    return type;
                }

                @Override
                public void handle(InboundEvent event) {
                    body.accept(event);
                }
            };
        }
    }
}
