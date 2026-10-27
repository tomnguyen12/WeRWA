package vn.werwa.onboarding.api;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Người ký được ủy quyền của tổ chức (US-1.01-AC3, AC4). Đề nghị ký multisig (B4b) kiểm người ký còn hiệu lực. */
public interface Signers {

    /** Người ký có địa chỉ này và còn hiệu lực vào ngày {@code on}. */
    Optional<Active> active(String signingAddress, LocalDate on);

    record Active(UUID signerId, UUID organizationId, UUID userId, java.util.Set<String> scopes) {}
}
