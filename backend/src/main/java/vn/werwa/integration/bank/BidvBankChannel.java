package vn.werwa.integration.bank;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Base64;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.inbound.BankChannel;

/**
 * Kiểm chữ ký thông điệp BIDV (US-5.02-AC2; Q49): {@code X-Bank-Signature} là chữ ký ECDSA P-256 SHA-256 (DER, base64)
 * trên thân thô. Lược đồ thật của BIDV khác thì thay lớp này, nghiệp vụ không đổi.
 */
public class BidvBankChannel implements BankChannel {

    private final PublicKey publicKey;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BidvBankChannel(String publicKey, JdbcTemplate jdbc, Clock clock) {
        this.publicKey = parse(publicKey);
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean verify(byte[] body, String signature) {
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(body);
            return verifier.verify(Base64.getDecoder().decode(signature.strip()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Ghi nhật ký thông điệp bị từ chối, ở giao dịch riêng để không phụ thuộc request. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rejected(String operationId, byte[] body, String signature) {
        jdbc.update("""
                INSERT INTO integration.inbound_message
                    (id, counterparty, message_type, idempotency_key, raw_payload, signature, signature_valid, received_at,
                     processing_status)
                VALUES (?, 'BIDV', ?, NULL, ?, ?, false, ?, 'REJECTED')
                """, Ids.newId(), operationId, new String(body, StandardCharsets.UTF_8), signature,
                Timestamp.from(clock.instant()));
        org.slf4j.LoggerFactory.getLogger(BidvBankChannel.class)
                .warn("Từ chối thông điệp BIDV {}: chữ ký không hợp lệ", operationId);
    }

    static PublicKey parse(String key) {
        String b64 = key.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("werwa.integration.bidv.public-key không phải khóa EC hợp lệ", e);
        }
    }
}
