package vn.werwa.governance.signing;

import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Q107: Safe tổ chức thuộc tổ chức nào, theo tên Safe trong {@code werwa.safes} (ví dụ {@code ubnd}, {@code vsdc},
 * {@code bidv}, {@code holco}). Biến môi trường {@code WERWA_SIGNING_SAFEORGANIZATIONS_<TÊN>}.
 */
@ConfigurationProperties("werwa.signing")
public record SigningProperties(Map<String, UUID> safeOrganizations) {

    public SigningProperties {
        safeOrganizations = safeOrganizations == null ? Map.of() : Map.copyOf(safeOrganizations);
    }
}
