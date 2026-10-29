package vn.werwa.governance.api;

import java.util.Collection;
import java.util.UUID;
import vn.werwa.platform.security.Role;

/** Thông báo trong ứng dụng và email (US-4.11-AC3; Q62, Q63). */
public interface Notifications {

    /**
     * @param roles vai nhận; rỗng là mọi vai
     * @param organizationId tổ chức nhận; null là mọi tổ chức có vai trong {@code roles}
     */
    void notify(Notice notice);

    enum Kind {
        APPROVAL_PENDING, PAYMENT_MISMATCH, ALLOCATION_OVERDUE, INFRA_ALERT, RECONCILIATION_BREAK,
        // B11c (Q101, Q104)
        DEBT_SERVICE_REMINDER, FUNDING_SHORT, PAYOUT_FAILED, PAYMENT_GRACE_EXPIRED
    }

    enum Severity { INFO, WARNING, CRITICAL }

    record Notice(Kind kind, Severity severity, String title, String body, String subjectRef, Collection<Role> roles,
            UUID organizationId) {}
}
