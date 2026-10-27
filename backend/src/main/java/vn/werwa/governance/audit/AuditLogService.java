package vn.werwa.governance.audit;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.werwa.governance.Hashes;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.platform.audit.AuditSink;
import vn.werwa.platform.audit.WriteAudit;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.CurrentUser;

/**
 * Ghi {@code governance.audit_log} (ADR-13). Mỗi dòng nối với dòng trước:
 * {@code entry_hash = SHA-256(prev_hash ‖ JSON chuẩn hóa của bản ghi)}. Khóa tư vấn giữ tới hết giao dịch, nên số thứ tự
 * liền mạch và không hai dòng nào cùng nối vào một dòng trước.
 */
public class AuditLogService implements AuditLog, AuditSink {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AuditLogService(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public long record(String action, String subjectRef, Object payload) {
        CurrentUser user = CurrentUser.find().orElse(null);
        String hash = Hashes.payloadHash(payload);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return append(user == null ? null : user.subject(), user == null ? null : user.organizationId(), action,
                    subjectRef, hash);
        }
        return tx.execute(s -> append(user == null ? null : user.subject(),
                user == null ? null : user.organizationId(), action, subjectRef, hash));
    }

    /** Lệnh ghi API thành công (Q22): ghi trong giao dịch riêng, sau khi nghiệp vụ đã commit. */
    @Override
    public void recordWrite(WriteAudit w) {
        tx.executeWithoutResult(s -> append(w.subject(), w.organizationId(), "API:" + w.operationId(),
                w.method() + " " + w.path(), w.requestHash()));
    }

    long append(String actor, UUID org, String action, String subjectRef, String payloadHash) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('governance.audit_log', 0))::text", String.class);
        List<Map<String, Object>> last = jdbc.queryForList(
                "SELECT seq, entry_hash FROM governance.audit_log ORDER BY seq DESC LIMIT 1");
        long seq = last.isEmpty() ? 1 : ((Number) last.getFirst().get("seq")).longValue() + 1;
        String prev = last.isEmpty() ? Hashes.ZERO : (String) last.getFirst().get("entry_hash");
        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String entry = entryHash(seq, at, actor, org, action, subjectRef, payloadHash, prev);
        jdbc.update("""
                INSERT INTO governance.audit_log
                    (seq, id, occurred_at, actor_subject, actor_org_id, action, subject_ref, payload_hash, prev_hash, entry_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, seq, Ids.newId(), Timestamp.from(at), actor, org, action, subjectRef, payloadHash, prev, entry);
        return seq;
    }

    /** Công thức mã băm của một dòng; bộ kiểm chuỗi dùng lại đúng công thức này. */
    public static String entryHash(
            long seq, Instant at, String actor, UUID org, String action, String subjectRef, String payloadHash, String prev) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("seq", seq);
        record.put("occurredAt", at.truncatedTo(ChronoUnit.MICROS).toString());
        record.put("actor", actor);
        record.put("actorOrgId", org == null ? null : org.toString());
        record.put("action", action);
        record.put("subjectRef", subjectRef);
        record.put("payloadHash", payloadHash);
        return Hashes.sha256(Hashes.bytes(prev), Hashes.canonicalJson(record).getBytes(StandardCharsets.UTF_8));
    }
}
