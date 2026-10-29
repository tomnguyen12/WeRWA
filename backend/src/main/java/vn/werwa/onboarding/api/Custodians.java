package vn.werwa.onboarding.api;

import java.util.UUID;

/** Lưu ký cho chuyển OTC qua ví omnibus (Q79). */
public interface Custodians {

    /** Tổ chức có vai CUSTODIAN và đã qua KYB. */
    boolean active(UUID organizationId);
}
