package vn.werwa.platform.registry;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Người ký được ủy quyền cho Quản trị kiểm chữ ký multisig (B4b; Q109). Onboarding cài đặt; Quản trị không phụ thuộc
 * ngược vào Onboarding.
 */
public interface SignerRegistry {

    /** Tổ chức của người ký có địa chỉ này nếu còn hiệu lực vào ngày {@code on}. */
    Optional<UUID> activeSignerOrganization(String signingAddress, LocalDate on);
}
