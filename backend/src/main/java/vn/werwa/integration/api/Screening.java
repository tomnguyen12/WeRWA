package vn.werwa.integration.api;

import java.util.Map;
import java.util.UUID;

/**
 * Sàng lọc cấm vận và PEP qua nhà cung cấp KYC (US-5.04, ADR-14). Nghiệp vụ chỉ dùng interface này; đổi nhà cung cấp là
 * đổi adapter trong mô-đun Tích hợp, không đổi mã nghiệp vụ (AC1). Mọi kết quả được lưu nguyên văn (AC3).
 */
public interface Screening {

    Result screen(Subject subject);

    /** Tổ chức cần sàng lọc. */
    record Subject(UUID organizationId, String legalName, String registrationNo, String country) {}

    /**
     * @param provider tên nhà cung cấp, ví dụ {@code SIMULATOR}
     * @param providerRef mã tham chiếu của nhà cung cấp
     * @param raw kết quả nguyên văn
     */
    record Result(String provider, String providerRef, boolean sanctionsHit, boolean pepHit, Map<String, Object> raw) {}
}
