package vn.werwa;

import io.nats.client.Connection;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.api.StreamConfiguration;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Lớp nền của kiểm thử tích hợp: PostgreSQL 18.6 và NATS 2.15.1 thật (cùng phiên bản devnet), khởi động một lần cho mọi
 * lớp kiểm thử. Stream {@code WERWA} tạo từ đúng tệp {@code infra/services/nats/stream-werwa.json} (I2), như infra làm.
 */
@SpringBootTest
@ActiveProfiles("it")
public abstract class IntegrationTest {

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @SuppressWarnings("resource")
    public static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.15.1")
            .withCommand("-js")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    static {
        POSTGRES.start();
        NATS.start();
        createStream();
    }

    public static String natsUrl() {
        return "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("werwa.nats.url", IntegrationTest::natsUrl);
    }

    private static void createStream() {
        try (InputStream in = IntegrationTest.class.getResourceAsStream("/contract/nats/stream-werwa.json");
                Connection connection = Nats.connect(natsUrl())) {
            JetStreamManagement jsm = connection.jetStreamManagement();
            jsm.addStream(StreamConfiguration.instance(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("không tạo được stream WERWA cho kiểm thử", e);
        }
    }
}
