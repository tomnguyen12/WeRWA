package vn.werwa.platform.web.idempotency;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Bảng {@code platform.idempotency_key} (Q1, Q6): khóa theo người dùng ({@code sub}) và {@code Idempotency-Key}, giữ
 * 24 giờ. Mỗi lệnh ghi tự commit ngay (không nằm trong giao dịch của nghiệp vụ) để request song song thấy nhau.
 */
public class IdempotencyStore {

    public static final Duration RETENTION = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IdempotencyStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Giữ chỗ cho request mới; {@code false} nếu khóa đã có (còn hạn). */
    public boolean reserve(String subject, String key, String operationId, String requestHash) {
        Instant now = clock.instant();
        jdbc.update(
                "DELETE FROM platform.idempotency_key WHERE subject = ? AND idem_key = ? AND created_at < ?",
                subject, key, Timestamp.from(now.minus(RETENTION)));
        return jdbc.update("""
                INSERT INTO platform.idempotency_key (subject, idem_key, operation_id, request_hash, state, created_at)
                VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?)
                ON CONFLICT (subject, idem_key) DO NOTHING
                """, subject, key, operationId, requestHash, Timestamp.from(now)) == 1;
    }

    public Optional<Entry> find(String subject, String key) {
        List<Entry> rows = jdbc.query("""
                SELECT request_hash, state, response_status, response_content_type, response_location, response_etag,
                       response_body
                FROM platform.idempotency_key WHERE subject = ? AND idem_key = ?
                """, (rs, i) -> new Entry(
                        rs.getString(1), rs.getString(2), (Integer) rs.getObject(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getBytes(7)), subject, key);
        return rows.stream().findFirst();
    }

    public void complete(String subject, String key, StoredResponse response) {
        jdbc.update("""
                UPDATE platform.idempotency_key
                SET state = 'COMPLETED', response_status = ?, response_content_type = ?, response_location = ?,
                    response_etag = ?, response_body = ?, completed_at = ?
                WHERE subject = ? AND idem_key = ?
                """, response.status(), response.contentType(), response.location(), response.etag(), response.body(),
                Timestamp.from(clock.instant()), subject, key);
    }

    /** Bỏ giữ chỗ khi xử lý lỗi hệ thống (5xx), để bên gọi thử lại được với cùng khóa. */
    public void release(String subject, String key) {
        jdbc.update("DELETE FROM platform.idempotency_key WHERE subject = ? AND idem_key = ?", subject, key);
    }

    /** Dọn khóa quá hạn; chạy định kỳ. */
    public int purgeExpired() {
        return jdbc.update(
                "DELETE FROM platform.idempotency_key WHERE created_at < ?",
                Timestamp.from(clock.instant().minus(RETENTION)));
    }

    public record Entry(
            String requestHash,
            String state,
            Integer status,
            String contentType,
            String location,
            String etag,
            byte[] body) {

        boolean completed() {
            return "COMPLETED".equals(state);
        }
    }

    public record StoredResponse(int status, String contentType, String location, String etag, byte[] body) {}
}
