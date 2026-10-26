package vn.werwa.onchain.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.onchain.api.SignerKey;

/** Lớp gọi WeHSM theo {@code eth_signTransaction} (Q14), với một WeHSM giả bằng HTTP. */
class WeHsmSignerTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final long CHAIN = 1337;

    HttpServer server;
    final AtomicReference<String> authorization = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    WeHsmSigner signer(Credentials key, Function<Map<String, Object>, Object> reply) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            @SuppressWarnings("unchecked")
            Map<String, Object> request = JSON.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
            byte[] body = JSON.writeValueAsBytes(reply.apply(request));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new WeHsmSigner(url, "bi-mat-thu", JSON, Duration.ofSeconds(2), key::getAddress);
    }

    static RawTransaction tx(long nonce) {
        return RawTransaction.createTransaction(BigInteger.valueOf(nonce), BigInteger.ZERO, BigInteger.valueOf(100_000),
                "0x1000000000000000000000000000000000000001", BigInteger.ZERO, "0xabcdef01");
    }

    static String signed(Credentials key, RawTransaction tx) {
        return Numeric.toHexString(TransactionEncoder.signMessage(tx, CHAIN, key));
    }

    @Test
    void returnsTheRawTransactionSignedByWeHsm() throws Exception {
        Credentials key = Credentials.create(Keys.createEcKeyPair());
        RawTransaction tx = tx(5);
        WeHsmSigner signer = signer(key, req -> Map.of("jsonrpc", "2.0", "id", 1, "result", signed(key, tx)));

        assertThat(signer.sign(SignerKey.HOLCO_OPERATOR, tx, CHAIN)).isEqualTo(signed(key, tx));
        assertThat(authorization.get())
                .isEqualTo("Basic " + java.util.Base64.getEncoder()
                        .encodeToString("wehsm:bi-mat-thu".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsGethStyleResultObject() throws Exception {
        Credentials key = Credentials.create(Keys.createEcKeyPair());
        RawTransaction tx = tx(6);
        WeHsmSigner signer = signer(key, req -> Map.of("jsonrpc", "2.0", "id", 1, "result", Map.of("raw", signed(key, tx))));
        assertThat(signer.sign(SignerKey.HOLCO_OPERATOR, tx, CHAIN)).isEqualTo(signed(key, tx));
    }

    @Test
    void rejectsATransactionDifferentFromTheRequest() throws Exception {
        Credentials key = Credentials.create(Keys.createEcKeyPair());
        WeHsmSigner signer = signer(key, req -> Map.of("jsonrpc", "2.0", "id", 1, "result", signed(key, tx(99))));
        assertThatThrownBy(() -> signer.sign(SignerKey.HOLCO_OPERATOR, tx(7), CHAIN)).hasMessageContaining("khác");
    }

    @Test
    void rejectsASignatureFromAnotherKey() throws Exception {
        Credentials key = Credentials.create(Keys.createEcKeyPair());
        Credentials other = Credentials.create(Keys.createEcKeyPair());
        RawTransaction tx = tx(8);
        WeHsmSigner signer = signer(key, req -> Map.of("jsonrpc", "2.0", "id", 1, "result", signed(other, tx)));
        assertThatThrownBy(() -> signer.sign(SignerKey.HOLCO_OPERATOR, tx, CHAIN)).hasMessageContaining("khác");
    }

    @Test
    void surfacesPolicyRefusal() throws Exception {
        Credentials key = Credentials.create(Keys.createEcKeyPair());
        WeHsmSigner signer = signer(key,
                req -> Map.of("jsonrpc", "2.0", "id", 1, "error", Map.of("code", -32000, "message", "policy denied")));
        assertThatThrownBy(() -> signer.sign(SignerKey.HOLCO_OPERATOR, tx(9), CHAIN)).hasMessageContaining("policy denied");
    }
}
