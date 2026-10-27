package vn.werwa.onboarding.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Điều kiện nhà đầu tư cho sổ lệnh (US-3.02-AC1). */
public interface Investors {

    /**
     * Loại nhà đầu tư nếu tổ chức có vai INVESTOR, đã qua KYB và có hồ sơ KYC đạt còn hạn tại {@code at}; rỗng nếu không.
     */
    Optional<String> eligibleClass(UUID organizationId, Instant at);
}
