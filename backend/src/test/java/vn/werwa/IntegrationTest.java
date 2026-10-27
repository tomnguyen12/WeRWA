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
 * Lớp nền của kiểm thử tích hợp: PostgreSQL 18.6, NATS 2.15.1 (cùng phiên bản devnet) và kho S3 giả, khởi động một lần cho mọi
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

    public static final String S3_USER = "werwa-test";
    public static final String S3_PASSWORD = "werwa-test-password";
    public static final String BUCKET = "werwa-documents";

    /**
     * Kho S3 giả chỉ dùng trong kiểm thử (Adobe S3Mock, không kiểm chữ ký). MinIO đã ngừng phát hành ảnh Docker từ 09/2026
     * nên không dùng được nữa; kho S3 cho devnet do infra chọn (Q30).
     */
    @SuppressWarnings("resource")
    public static final GenericContainer<?> S3 = new GenericContainer<>("adobe/s3mock:3.12.0")
            .withExposedPorts(9090)
            .waitingFor(Wait.forListeningPort());

    static {
        POSTGRES.start();
        NATS.start();
        S3.start();
        createStream();
        createBucket();
    }

    public static String s3Url() {
        return "http://" + S3.getHost() + ":" + S3.getMappedPort(9090);
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
        registry.add("werwa.storage.endpoint", IntegrationTest::s3Url);
        registry.add("werwa.storage.bucket", () -> BUCKET);
        registry.add("werwa.storage.access-key", () -> S3_USER);
        registry.add("werwa.storage.secret-key", () -> S3_PASSWORD);
    }

    /** Bucket do infra tạo ở môi trường thật; kiểm thử tạo thay (Q30). */
    private static void createBucket() {
        try (var s3 = software.amazon.awssdk.services.s3.S3Client.builder()
                .endpointOverride(java.net.URI.create(s3Url()))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .forcePathStyle(true)
                .httpClient(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.create())
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(S3_USER, S3_PASSWORD)))
                .build()) {
            s3.createBucket(b -> b.bucket(BUCKET));
        }
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
