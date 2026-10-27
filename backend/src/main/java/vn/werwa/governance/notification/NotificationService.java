package vn.werwa.governance.notification;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.governance.api.Notifications;
import vn.werwa.governance.approval.GovernanceError;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.mail.Mailer;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.security.OrganizationDirectory;
import vn.werwa.platform.security.Role;
import vn.werwa.platform.web.ApiException;
import vn.werwa.platform.web.Paging;

/**
 * Thông báo trong ứng dụng và email (US-4.11-AC3; Q62, Q63). Thông báo ghi trong giao dịch của sự việc; email gửi sau
 * bằng việc định kỳ ({@link #sendPendingEmails}) tới hộp thư của tổ chức nhận, thử lại tối đa 5 lần.
 */
public class NotificationService implements Notifications {

    private static final int MAX_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;
    private final ObjectProvider<OrganizationDirectory> organizations;
    private final ObjectProvider<Mailer> mailer;
    private final Clock clock;

    public NotificationService(JdbcTemplate jdbc, ObjectProvider<OrganizationDirectory> organizations,
            ObjectProvider<Mailer> mailer,
            Clock clock) {
        this.jdbc = jdbc;
        this.organizations = organizations;
        this.mailer = mailer;
        this.clock = clock;
    }

    public record Item(UUID id, String kind, String severity, String title, String body, String subjectRef,
            Instant createdAt, boolean read) {}

    @Override
    public void notify(Notice n) {
        String[] roles = n.roles() == null ? new String[0] : n.roles().stream().map(Role::name).toArray(String[]::new);
        jdbc.update("""
                INSERT INTO governance.notification
                    (id, kind, severity, title, body, subject_ref, recipient_roles, recipient_org_id, email_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, Ids.newId(), n.kind().name(), n.severity().name(), n.title(), n.body(), n.subjectRef(), roles,
                n.organizationId(), Timestamp.from(clock.instant()));
    }

    /** Thông báo gửi tới tổ chức và vai của người dùng, mới nhất trước. */
    public Paging.Page<Item> list(String cursor, Integer limit) {
        CurrentUser user = CurrentUser.get();
        int size = Paging.limit(limit);
        UUID before = Paging.decodeCursor(cursor);
        String[] roles = user.roles().stream().map(Role::name).toArray(String[]::new);
        List<Object> args = new ArrayList<>(List.of(user.subject(), user.organizationId(), roles));
        String sql = SELECT + VISIBLE + (before == null ? "" : " AND n.id < ?") + " ORDER BY n.id DESC LIMIT ?";
        if (before != null) {
            args.add(before);
        }
        args.add(size + 1);
        return Paging.Page.of(jdbc.query(sql, (rs, i) -> row(rs), args.toArray()), size, Item::id);
    }

    @Transactional
    public Item markRead(UUID id) {
        CurrentUser user = CurrentUser.get();
        String[] roles = user.roles().stream().map(Role::name).toArray(String[]::new);
        List<Item> rows = jdbc.query(SELECT + VISIBLE + " AND n.id = ?", (rs, i) -> row(rs), user.subject(),
                user.organizationId(), roles, id);
        if (rows.isEmpty()) {
            throw new ApiException(GovernanceError.NOTIFICATION_NOT_FOUND);
        }
        jdbc.update("""
                INSERT INTO governance.notification_read (notification_id, user_subject, read_at) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """, id, user.subject(), Timestamp.from(clock.instant()));
        Item it = rows.getFirst();
        return new Item(it.id(), it.kind(), it.severity(), it.title(), it.body(), it.subjectRef(), it.createdAt(), true);
    }

    /** Gửi email chờ gửi. Trả số email đã gửi. */
    public int sendPendingEmails() {
        Mailer m = mailer.getIfAvailable();
        if (m == null) {
            return 0;
        }
        int sent = 0;
        List<Map<String, Object>> pending = jdbc.queryForList("""
                SELECT id, title, body, recipient_roles, recipient_org_id FROM governance.notification
                WHERE email_status = 'PENDING' ORDER BY created_at LIMIT 100
                """);
        for (Map<String, Object> p : pending) {
            UUID id = (UUID) p.get("id");
            List<Role> roles = new ArrayList<>();
            try {
                for (String r : (String[]) ((java.sql.Array) p.get("recipient_roles")).getArray()) {
                    roles.add(Role.valueOf(r));
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            List<String> to = organizations.getObject().notificationEmails(roles, (UUID) p.get("recipient_org_id"));
            if (to.isEmpty()) {
                jdbc.update("UPDATE governance.notification SET email_status = 'NO_RECIPIENT' WHERE id = ?", id);
                continue;
            }
            try {
                m.send(to, "[WeRWA] " + p.get("title"), p.get("body") == null ? (String) p.get("title") : (String) p.get("body"));
                jdbc.update("""
                        UPDATE governance.notification SET email_status = 'SENT', email_attempts = email_attempts + 1,
                            email_sent_at = ? WHERE id = ?
                        """, Timestamp.from(clock.instant()), id);
                sent++;
            } catch (RuntimeException e) {
                jdbc.update("""
                        UPDATE governance.notification SET email_attempts = email_attempts + 1,
                            email_status = CASE WHEN email_attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END
                        WHERE id = ?
                        """, MAX_ATTEMPTS, id);
                org.slf4j.LoggerFactory.getLogger(NotificationService.class)
                        .warn("Gửi email thông báo {} lỗi: {}", id, e.toString());
            }
        }
        return sent;
    }

    private static final String SELECT = """
            SELECT n.id, n.kind, n.severity, n.title, n.body, n.subject_ref, n.created_at,
                   EXISTS (SELECT 1 FROM governance.notification_read r WHERE r.notification_id = n.id AND r.user_subject = ?)
            FROM governance.notification n""";

    private static final String VISIBLE = """
             WHERE (n.recipient_org_id IS NULL OR n.recipient_org_id = ?)
               AND (cardinality(n.recipient_roles) = 0 OR n.recipient_roles && ?::text[])""";

    private static Item row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Item(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getTimestamp(7).toInstant(), rs.getBoolean(8));
    }
}
