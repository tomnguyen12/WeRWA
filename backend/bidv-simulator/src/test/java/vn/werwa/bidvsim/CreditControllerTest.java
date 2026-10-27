package vn.werwa.bidvsim;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** Giả lập ký đúng lược đồ Q49 và gửi đúng thân đã ký; gửi lại dùng nguyên thông điệp cũ. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CreditControllerTest {

    record Received(byte[] body, String signature, String idempotencyKey) {}

    static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    static final KeyPair KEYS;
    static final HttpServer PLATFORM;

    static {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            KEYS = g.generateKeyPair();
            PLATFORM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PLATFORM.createContext("/v1/inbound/bidv/credit-notifications", ex -> {
                RECEIVED.add(new Received(ex.getRequestBody().readAllBytes(), ex.getRequestHeaders().getFirst("X-Bank-Signature"),
                        ex.getRequestHeaders().getFirst("Idempotency-Key")));
                byte[] ack = "{\"received\":true}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, ack.length);
                ex.getResponseBody().write(ack);
                ex.close();
            });
            PLATFORM.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        PLATFORM.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("bidv-sim.platform-url", () -> "http://127.0.0.1:" + PLATFORM.getAddress().getPort());
        r.add("bidv-sim.private-key", () -> Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()));
        r.add("server.address", () -> "127.0.0.1");
    }

    @LocalServerPort
    int port;

    @Autowired
    RestClient.Builder builder;

    @Test
    void createsSignsAndResendsACreditNotification() throws Exception {
        RestClient sim = builder.baseUrl("http://127.0.0.1:" + port).build();
        String response = sim.post().uri("/sim/credits").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{\"virtualAccount\":\"9630000000001\",\"amountMinor\":\"10000000\",\"currency\":\"USD\","
                        + "\"payerName\":\"Investor A\",\"bankRef\":\"BIDVTEST0001\"}")
                .retrieve().body(String.class);
        assertThat(response).contains("\"platformStatus\":200");

        Received first = RECEIVED.getLast();
        assertThat(first.idempotencyKey()).isEqualTo("BIDVTEST0001");
        String body = new String(first.body(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"bankRef\":\"BIDVTEST0001\"").contains("\"virtualAccount\":\"9630000000001\"")
                .contains("\"amountMinor\":\"10000000\"");
        Signature v = Signature.getInstance("SHA256withECDSA");
        v.initVerify(KEYS.getPublic());
        v.update(first.body());
        assertThat(v.verify(Base64.getDecoder().decode(first.signature()))).isTrue();

        sim.post().uri("/sim/credits/BIDVTEST0001/resend").retrieve().body(String.class);
        assertThat(RECEIVED.getLast().body()).isEqualTo(first.body());
    }
}
