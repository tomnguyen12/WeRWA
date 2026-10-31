package vn.werwa.bidvsim;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** Ký thông điệp như BIDV (Q49): ECDSA P-256, SHA-256 trên thân thô, chữ ký DER dạng base64. */
public class MessageSigner {

    private final PrivateKey key;

    public MessageSigner(String privateKey) {
        if (privateKey == null || privateKey.isBlank()) {
            throw new IllegalStateException("thiếu bidv-sim.private-key (BIDV_SIM_PRIVATE_KEY)");
        }
        String b64 = privateKey.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        try {
            this.key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("bidv-sim.private-key không phải khóa EC PKCS#8", e);
        }
    }

    public String sign(byte[] body) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(key);
            s.update(body);
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
