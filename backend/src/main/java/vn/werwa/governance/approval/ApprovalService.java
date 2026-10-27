package vn.werwa.governance.approval;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.governance.Hashes;
import vn.werwa.governance.api.Approvals;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.security.OrgScope;
import vn.werwa.platform.web.ApiException;
import vn.werwa.platform.web.Paging;

/** Duyệt 4 mắt (ADR-13, US-4.08-AC1; Q18, Q19). */
public class ApprovalService implements Approvals {

    static final String DECIDED = "werwa.governance.approval.decided.v1";

    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    private final Events events;
    private final Clock clock;

    public ApprovalService(JdbcTemplate jdbc, AuditLog audit, Events events, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    public record Row(
            UUID id,
            String actionType,
            String subjectRef,
            String payloadHash,
            String makerSubject,
            UUID makerOrgId,
            String checkerSubject,
            String status,
            String comment,
            Timestamp decidedAt) {}

    @Override
    @Transactional
    public UUID request(String actionType, String subjectRef, Object payload) {
        CurrentUser maker = CurrentUser.get();
        String hash = Hashes.payloadHash(payload);
        List<Row> pending = jdbc.query(SELECT + " WHERE action_type = ? AND subject_ref = ? AND status = 'PENDING' FOR UPDATE",
                ApprovalService::row, actionType, subjectRef);
        if (!pending.isEmpty()) {
            Row current = pending.getFirst();
            if (current.payloadHash().equals(hash)) {
                return current.id();
            }
            jdbc.update("UPDATE governance.approval_request SET status = 'CANCELLED', decided_at = ? WHERE id = ?",
                    Timestamp.from(clock.instant()), current.id());
            audit.record("APPROVAL_CANCELLED", "approval/" + current.id(),
                    Map.of("replacedBy", "new request", "subjectRef", subjectRef, "oldPayloadHash", current.payloadHash()));
        }
        UUID id = Ids.newId();
        jdbc.update("""
                INSERT INTO governance.approval_request
                    (id, action_type, subject_ref, payload_hash, maker_subject, maker_org_id, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, id, actionType, subjectRef, hash, maker.subject(), maker.organizationId(),
                Timestamp.from(clock.instant()));
        audit.record("APPROVAL_REQUESTED", "approval/" + id,
                Map.of("actionType", actionType, "subjectRef", subjectRef, "payloadHash", hash));
        return id;
    }

    @Override
    public String status(UUID approvalId) {
        return find(approvalId).status();
    }

    /** Người duyệt cùng tổ chức người lập (Q18), khác người lập. */
    @Transactional
    public Row decide(UUID id, boolean approve, String comment) {
        CurrentUser checker = CurrentUser.get();
        List<Row> rows = jdbc.query(SELECT + " WHERE id = ? FOR UPDATE", ApprovalService::row, id);
        if (rows.isEmpty()) {
            throw new ApiException(GovernanceError.APPROVAL_NOT_FOUND);
        }
        Row current = rows.getFirst();
        OrgScope.requireOwnOrganization(current.makerOrgId());
        if (!"PENDING".equals(current.status())) {
            throw new ApiException(GovernanceError.APPROVAL_NOT_PENDING, "đang " + current.status());
        }
        if (current.makerSubject().equals(checker.subject())) {
            throw new ApiException(GovernanceError.MAKER_CANNOT_APPROVE);
        }
        String status = approve ? "APPROVED" : "REJECTED";
        jdbc.update("""
                UPDATE governance.approval_request SET status = ?, checker_subject = ?, comment = ?, decided_at = ?
                WHERE id = ?
                """, status, checker.subject(), comment, Timestamp.from(clock.instant()), id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("approvalId", id.toString());
        data.put("subjectRef", current.subjectRef());
        data.put("decision", approve ? "APPROVE" : "REJECT");
        data.put("actionType", current.actionType());
        data.put("payloadHash", current.payloadHash());
        events.publish(DECIDED, "approval/" + id, data);
        audit.record(approve ? "APPROVAL_APPROVED" : "APPROVAL_REJECTED", "approval/" + id, data);
        return find(id);
    }

    /** Hàng chờ duyệt của tổ chức người gọi (Q18), theo thứ tự tạo. */
    public Paging.Page<Row> queue(String cursor, Integer limit) {
        int size = Paging.limit(limit);
        UUID after = Paging.decodeCursor(cursor);
        UUID org = CurrentUser.get().organizationId();
        List<Row> rows = after == null
                ? jdbc.query(SELECT + " WHERE maker_org_id = ? AND status = 'PENDING' ORDER BY id LIMIT ?",
                        ApprovalService::row, org, size + 1)
                : jdbc.query(SELECT + " WHERE maker_org_id = ? AND status = 'PENDING' AND id > ? ORDER BY id LIMIT ?",
                        ApprovalService::row, org, after, size + 1);
        return Paging.Page.of(rows, size, Row::id);
    }

    Row find(UUID id) {
        List<Row> rows = jdbc.query(SELECT + " WHERE id = ?", ApprovalService::row, id);
        if (rows.isEmpty()) {
            throw new ApiException(GovernanceError.APPROVAL_NOT_FOUND);
        }
        return rows.getFirst();
    }

    private static final String SELECT = """
            SELECT id, action_type, subject_ref, payload_hash, maker_subject, maker_org_id, checker_subject, status, comment,
                   decided_at
            FROM governance.approval_request""";

    private static Row row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8), rs.getString(9), rs.getTimestamp(10));
    }
}
