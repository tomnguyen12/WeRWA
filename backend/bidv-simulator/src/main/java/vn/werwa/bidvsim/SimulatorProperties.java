package vn.werwa.bidvsim;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param platformUrl địa chỉ nền tảng WeRWA (ingress)
 * @param privateKey khóa riêng ECDSA P-256 của BIDV giả lập, PKCS#8 dạng base64 hoặc PEM
 */
@ConfigurationProperties("bidv-sim")
public record SimulatorProperties(String platformUrl, String privateKey) {}
