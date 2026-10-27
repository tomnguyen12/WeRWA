package vn.werwa.integration.bank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Kênh BIDV (Q49, Q51).
 *
 * @param publicKey khóa công khai ECDSA P-256 của BIDV, X.509 SubjectPublicKeyInfo dạng base64 hoặc PEM; trống thì kênh
 *     webhook đóng
 * @param virtualAccountPrefix tiền tố tài khoản ảo của giả lập
 */
@ConfigurationProperties("werwa.integration.bidv")
public record BidvProperties(String publicKey, @DefaultValue("963") String virtualAccountPrefix) {}
