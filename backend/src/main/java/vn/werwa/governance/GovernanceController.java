package vn.werwa.governance;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RestController;
import vn.werwa.governance.approval.ApprovalService;
import vn.werwa.governance.approval.ApprovalService.Row;
import vn.werwa.platform.openapi.api.GovernanceApi;
import vn.werwa.platform.openapi.model.ApprovalRequest;
import vn.werwa.platform.openapi.model.AuditEntry;
import vn.werwa.platform.openapi.model.AuditEntryAnchor;
import vn.werwa.platform.openapi.model.Decision;
import vn.werwa.platform.openapi.model.ListApprovals200Response;
import vn.werwa.platform.openapi.model.ListAuditLog200Response;
import vn.werwa.platform.web.Paging;

/**
 * API của mô-đun Quản trị đã làm ở B4a: hàng chờ duyệt, quyết định duyệt, nhật ký kiểm toán. API khác của nhóm
 * {@code governance} trả 501 tới nhóm PR làm chúng (Q20).
 */
@RestController
class GovernanceController implements GovernanceApi {

    private final ApprovalService approvals;
    private final JdbcTemplate jdbc;

    GovernanceController(ApprovalService approvals, JdbcTemplate jdbc) {
        this.approvals = approvals;
        this.jdbc = jdbc;
    }

    @Override
    public ResponseEntity<ListApprovals200Response> listApprovals(String cursor, Integer limit) {
        Paging.Page<Row> page = approvals.queue(cursor, limit);
        return ResponseEntity.ok(new ListApprovals200Response(page.items().stream().map(GovernanceController::view).toList())
                .nextCursor(page.nextCursor()));
    }

    @Override
    public ResponseEntity<ApprovalRequest> decideApproval(UUID id, String idempotencyKey, Decision decision) {
        boolean approve = decision.getDecision() == Decision.DecisionEnum.APPROVE;
        return ResponseEntity.ok(view(approvals.decideRow(id, approve, decision.getComment())));
    }

    @Override
    public ResponseEntity<ListAuditLog200Response> listAuditLog(String cursor, Integer limit) {
        int size = Paging.limit(limit);
        Long after = Paging.decodeSeqCursor(cursor);
        List<AuditEntry> rows = jdbc.query("""
                SELECT l.seq, l.occurred_at, l.actor_subject, l.actor_org_id, l.action, l.subject_ref, l.payload_hash,
                       l.prev_hash, l.entry_hash, a.business_date, a.tx_hash
                FROM governance.audit_log l
                LEFT JOIN LATERAL (
                    SELECT business_date, tx_hash FROM governance.audit_anchor
                    WHERE status = 'ANCHORED' AND last_seq >= l.seq ORDER BY business_date LIMIT 1) a ON true
                WHERE l.seq > ? ORDER BY l.seq LIMIT ?
                """, (rs, i) -> {
            AuditEntry e = new AuditEntry(
                    (int) rs.getLong(1),
                    rs.getTimestamp(2).toInstant().atOffset(ZoneOffset.UTC),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9));
            e.setActorUserId(uuidOrNull(rs.getString(3)));
            e.setActorOrgId(rs.getObject(4, UUID.class));
            if (rs.getString(11) != null) {
                e.setAnchor(new AuditEntryAnchor().businessDate(rs.getObject(10, LocalDate.class)).txHash(rs.getString(11)));
            }
            return e;
        }, after == null ? 0L : after, size + 1);
        String next = null;
        if (rows.size() > size) {
            rows = rows.subList(0, size);
            next = Paging.encodeSeqCursor(rows.getLast().getSeq());
        }
        return ResponseEntity.ok(new ListAuditLog200Response(rows).nextCursor(next));
    }

    static ApprovalRequest view(Row r) {
        ApprovalRequest v = new ApprovalRequest(r.id(), r.actionType(), r.subjectRef(), r.payloadHash(),
                UUID.fromString(r.makerSubject()), ApprovalRequest.StatusEnum.fromValue(r.status()));
        v.setCheckerUserId(uuidOrNull(r.checkerSubject()));
        v.setComment(r.comment());
        v.setDecidedAt(r.decidedAt() == null ? null : r.decidedAt().toInstant().atOffset(ZoneOffset.UTC));
        return v;
    }

    /** Người dùng ghi theo subject OIDC (UUID của Keycloak) tới khi có bảng app_user ở B5. */
    private static UUID uuidOrNull(String subject) {
        if (subject == null) {
            return null;
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
