package vn.werwa.issuance.document;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Kho đối tượng chuẩn S3 (D7, Q30). Biến môi trường theo I11: {@code WERWA_STORAGE_ENDPOINT}, {@code _REGION},
 * {@code _BUCKET}, {@code _ACCESSKEY}, {@code _SECRETKEY}, {@code _PATHSTYLE}.
 *
 * @param endpoint địa chỉ S3 (kho S3 tương thích ở devnet); trống thì dùng địa chỉ mặc định của AWS theo vùng
 * @param bucket bucket do infra tạo; backend không tự tạo, thiếu thì không khởi động
 * @param downloadTtl hạn của URL tải (Q29)
 */
@ConfigurationProperties(prefix = "werwa.storage")
public record StorageProperties(
        URI endpoint,
        @DefaultValue("us-east-1") String region,
        String bucket,
        String accessKey,
        String secretKey,
        @DefaultValue("true") boolean pathStyle,
        @DefaultValue("PT5M") Duration downloadTtl) {

    @Override
    public String toString() {
        return "StorageProperties[endpoint=" + endpoint + ", bucket=" + bucket + ", accessKey=" + accessKey + "]";
    }
}
