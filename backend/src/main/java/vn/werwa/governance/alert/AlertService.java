package vn.werwa.governance.alert;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.approval.GovernanceError;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.web.ApiException;
import vn.werwa.platform.web.Paging;

/**
 * Hàng chờ cảnh báo vận hành (I5, US-6.01-AC3; Q57–Q59). Cảnh báo hạ tầng vào từ Alertmanager; cảnh báo nghiệp vụ dùng
 * chung hàng chờ ở các nhóm sau.
 */
public class AlertService {

    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    private final JsonMapper json;
    private final Clock clock;
    private final vn.werwa.governance.api.Notifications notifications;

    public AlertService(JdbcTemplate jdbc, AuditLog audit, JsonMapper json, Clock clock,
            vn.werwa.governance.api.Notifications notifications) {
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /** Một cảnh báo trong webhook. {@code firing = false} là cảnh báo đã hết. */
    public record Incoming(
            String fingerprint, boolean firing, Map<String, String> labels, Map<String, String> annotations,
            Instant startsAt, Instant endsAt) {}

    public record Alert(
            UUID id, String fingerprint, String name, String severity, String summary, Map<String, String> labels,
            String status, Instant startsAt, Instant endsAt, String acknowledgedBy, Instant acknowledgedAt) {}

    /** Q57: chống trùng theo (fingerprint, startsAt); cảnh báo hết thì RESOLVED, giữ thông tin người xác nhận. */
    @Transactional
    public int receive(String source, List<Incoming> alerts) {
        int changed = 0;
        Timestamp now = Timestamp.from(clock.instant());
        for (Incoming a : alerts) {
            String name = a.labels().getOrDefault("alertname", "unknown");
            String summary = a.annotations() == null ? null
                    : a.annotations().getOrDefault("summary", a.annotations().get("description"));
            String status = a.firing() ? "FIRING" : "RESOLVED";
            // Alertmanager gửi endsAt = 0001-01-01 khi cảnh báo còn đang cháy
            Timestamp endsAt = a.firing() || a.endsAt() == null || a.endsAt().getEpochSecond() < 0 ? null
                    : Timestamp.from(a.endsAt());
            UUID id = Ids.newId();
            int n = jdbc.update("""
                    INSERT INTO governance.operational_alert
                        (id, fingerprint, name, severity, summary, labels, annotations, status, source, starts_at, ends_at,
                         received_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (fingerprint, starts_at) DO UPDATE SET
                        status = CASE WHEN excluded.status = 'RESOLVED' THEN 'RESOLVED' ELSE operational_alert.status END,
                        ends_at = COALESCE(excluded.ends_at, operational_alert.ends_at),
                        updated_at = excluded.updated_at
                    WHERE operational_alert.status <> 'RESOLVED' AND excluded.status = 'RESOLVED'
                    """, id, a.fingerprint(), name, a.labels().get("severity"), summary,
                    json.writeValueAsString(a.labels()), json.writeValueAsString(
                            a.annotations() == null ? Map.of() : a.annotations()),
                    status, source, Timestamp.from(a.startsAt()), endsAt, now, now);
            if (n > 0) {
                changed++;
                if (a.firing() && "critical".equalsIgnoreCase(a.labels().get("severity"))) {
                    // Q63: cảnh báo critical mới báo Holco vận hành
                    notifications.notify(new vn.werwa.governance.api.Notifications.Notice(
                            vn.werwa.governance.api.Notifications.Kind.INFRA_ALERT,
                            vn.werwa.governance.api.Notifications.Severity.CRITICAL, "Cảnh báo hạ tầng: " + name, summary,
                            "alert/" + a.fingerprint(), java.util.List.of(vn.werwa.platform.security.Role.OPERATOR), null));
                }
                audit.record("ALERT_" + status, "alert/" + a.fingerprint(),
                        Map.of("name", name, "source", source, "startsAt", a.startsAt().toString()));
            }
        }
        return changed;
    }

    public Paging.Page<Alert> list(String status, String cursor, Integer limit) {
        int size = Paging.limit(limit);
        UUID after = Paging.decodeCursor(cursor);
        StringBuilder sql = new StringBuilder(SELECT + " WHERE true");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (after != null) {
            sql.append(" AND id > ?");
            args.add(after);
        }
        sql.append(" ORDER BY id LIMIT ?");
        args.add(size + 1);
        return Paging.Page.of(jdbc.query(sql.toString(), (rs, i) -> row(rs), args.toArray()), size, Alert::id);
    }

    /** Q58: người trực xác nhận; cảnh báo đã hết vẫn ghi người xác nhận nhưng giữ RESOLVED. */
    @Transactional
    public Alert acknowledge(UUID id) {
        CurrentUser user = CurrentUser.get();
        int n = jdbc.update("""
                UPDATE governance.operational_alert SET
                    status = CASE WHEN status = 'FIRING' THEN 'ACKNOWLEDGED' ELSE status END,
                    acknowledged_by = COALESCE(acknowledged_by, ?), acknowledged_at = COALESCE(acknowledged_at, ?),
                    updated_at = ?
                WHERE id = ?
                """, user.subject(), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), id);
        if (n == 0) {
            throw new ApiException(GovernanceError.ALERT_NOT_FOUND);
        }
        audit.record("ALERT_ACKNOWLEDGED", "alert/" + id, Map.of("by", user.subject()));
        return jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> row(rs), id).getFirst();
    }

    private static final String SELECT = """
            SELECT id, fingerprint, name, severity, summary, labels::text, status, starts_at, ends_at, acknowledged_by,
                   acknowledged_at
            FROM governance.operational_alert""";

    @SuppressWarnings("unchecked")
    private Alert row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Alert(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                json.readValue(rs.getString(6), Map.class), rs.getString(7), rs.getTimestamp(8).toInstant(),
                rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant(), rs.getString(10),
                rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant());
    }
}
