package vn.werwa.governance.audit;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.werwa.platform.hash.Hashes;
import vn.werwa.governance.api.AuditVerification;

/**
 * Kiểm chuỗi nhật ký (US-4.08-AC2): tính lại mã băm từng dòng theo thứ tự và so với mã băm đã lưu; so mỗi neo đã lên chuỗi
 * với dòng có cùng số thứ tự (AC3). Dòng bị sửa thẳng trong DB làm lệch mã băm của chính nó; sửa cả mã băm thì lệch dòng
 * kế tiếp hoặc lệch neo.
 */
public class AuditVerifier implements AuditVerification {

    private final JdbcTemplate jdbc;

    public AuditVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Result verify() {
        AtomicLong count = new AtomicLong();
        AtomicReference<String> prev = new AtomicReference<>(Hashes.ZERO);
        AtomicLong expectedSeq = new AtomicLong(1);
        AtomicReference<Result> broken = new AtomicReference<>();
        jdbc.query("""
                SELECT seq, occurred_at, actor_subject, actor_org_id, action, subject_ref, payload_hash, prev_hash, entry_hash
                FROM governance.audit_log ORDER BY seq
                """, rs -> {
            if (broken.get() != null) {
                return;
            }
            long seq = rs.getLong(1);
            count.incrementAndGet();
            if (seq != expectedSeq.getAndIncrement()) {
                broken.set(new Result(false, count.get(), seq, "thiếu dòng trước số " + seq));
                return;
            }
            String storedPrev = rs.getString(8);
            if (!storedPrev.equals(prev.get())) {
                broken.set(new Result(false, count.get(), seq, "prev_hash không khớp dòng trước"));
                return;
            }
            String expected = AuditLogService.entryHash(seq, rs.getTimestamp(2).toInstant(), rs.getString(3),
                    rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), rs.getString(7), storedPrev);
            if (!expected.equals(rs.getString(9))) {
                broken.set(new Result(false, count.get(), seq, "entry_hash không khớp nội dung dòng"));
                return;
            }
            prev.set(expected);
        });
        if (broken.get() != null) {
            return broken.get();
        }
        // Neo đã lên chuỗi phải khớp dòng cùng số thứ tự.
        Long badAnchor = jdbc.query("""
                SELECT a.last_seq FROM governance.audit_anchor a
                LEFT JOIN governance.audit_log l ON l.seq = a.last_seq
                WHERE a.status = 'ANCHORED' AND a.last_seq > 0 AND (l.seq IS NULL OR l.entry_hash <> a.last_entry_hash)
                ORDER BY a.last_seq LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null);
        if (badAnchor != null) {
            return new Result(false, count.get(), badAnchor, "dòng không khớp mã băm đã neo trên chuỗi");
        }
        return new Result(true, count.get(), null, null);
    }
}
