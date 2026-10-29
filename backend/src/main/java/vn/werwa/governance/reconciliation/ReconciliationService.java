package vn.werwa.governance.reconciliation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.api.Notifications;
import vn.werwa.governance.approval.GovernanceError;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.reconciliation.ReconciliationCheck;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.security.Role;
import vn.werwa.platform.web.ApiException;
import vn.werwa.platform.web.Paging;

/**
 * Đối soát hằng ngày (US-4.09; Q73–Q76): chạy mọi {@link ReconciliationCheck}, mỗi phạm vi một lần chạy, mỗi chênh lệch một
 * dòng cần người xử lý đóng. Lần chạy sau còn lệch thì có chênh lệch mới.
 */
public class ReconciliationService {

    private final JdbcTemplate jdbc;
    private final ObjectProvider<ReconciliationCheck> checks;
    private final AuditLog audit;
    private final Notifications notifications;
    private final Clock clock;

    public ReconciliationService(JdbcTemplate jdbc, ObjectProvider<ReconciliationCheck> checks, AuditLog audit,
            Notifications notifications, Clock clock) {
        this.jdbc = jdbc;
        this.checks = checks;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    public record Break(UUID id, String rule, String kind, String subjectRef, String expected, String actual,
            String resolvedBy, Instant resolvedAt, String resolution) {}

    public record Run(UUID id, LocalDate runDate, String scope, int breakCount, List<Break> breaks) {}

    /** US-4.09-AC1, AC2. Trả các lần chạy vừa ghi. */
    @Transactional
    public List<UUID> runAll(LocalDate runDate) {
        Map<String, List<ReconciliationCheck>> byScope = checks.orderedStream()
                .collect(Collectors.groupingBy(ReconciliationCheck::scope, LinkedHashMap::new, Collectors.toList()));
        List<UUID> runs = new ArrayList<>();
        for (Map.Entry<String, List<ReconciliationCheck>> e : byScope.entrySet()) {
            Instant started = clock.instant();
            UUID run = Ids.newId();
            List<Object[]> breaks = new ArrayList<>();
            for (ReconciliationCheck c : e.getValue()) {
                for (ReconciliationCheck.Finding f : c.run(runDate)) {
                    breaks.add(new Object[] {Ids.newId(), run, c.rule(), f.kind(), f.subjectRef(), f.expected(), f.actual()});
                }
            }
            jdbc.update("""
                    INSERT INTO governance.reconciliation_run (id, run_date, scope, break_count, started_at, finished_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, run, runDate, e.getKey(), breaks.size(), Timestamp.from(started), Timestamp.from(clock.instant()));
            for (Object[] b : breaks) {
                jdbc.update("""
                        INSERT INTO governance.reconciliation_break (id, run_id, rule, kind, subject_ref, expected, actual)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, b);
            }
            audit.record("RECONCILIATION_RUN", "reconciliation-run/" + run,
                    Map.of("runDate", runDate.toString(), "scope", e.getKey(), "breakCount", breaks.size()));
            if (!breaks.isEmpty()) {
                // Q96: lệch mã băm khối với VSDC là cảnh báo đỏ cho cả Cơ quan giám sát
                boolean blockHash = "BLOCK_HASH_VSDC".equals(e.getKey());
                notifications.notify(new Notifications.Notice(Notifications.Kind.RECONCILIATION_BREAK,
                        blockHash ? Notifications.Severity.CRITICAL : Notifications.Severity.WARNING,
                        "Đối soát " + e.getKey() + " ngày " + runDate + ": " + breaks.size() + " chênh lệch", null,
                        "reconciliation-run/" + run,
                        blockHash ? List.of(Role.OPERATOR, Role.SUPERVISOR) : List.of(Role.OPERATOR), null));
            }
            runs.add(run);
        }
        return runs;
    }

    /** Q76: mỗi bên thấy phạm vi liên quan tới mình. */
    static Set<String> visibleScopes(Set<Role> roles) {
        if (roles.contains(Role.OPERATOR) || roles.contains(Role.SUPERVISOR)) {
            return Set.of("CHAIN_VS_VIEW", "CHAIN_VS_BANK", "BLOCK_HASH_VSDC", "LIMITS", "AUDIT_CHAIN");
        }
        Set<String> out = new java.util.HashSet<>();
        if (roles.contains(Role.PAYING_AGENT)) {
            out.add("CHAIN_VS_BANK");
        }
        if (roles.contains(Role.REGISTRAR)) {
            out.addAll(List.of("CHAIN_VS_VIEW", "BLOCK_HASH_VSDC"));
        }
        return out;
    }

    /** Mới nhất trước, kèm chênh lệch. */
    public Paging.Page<Run> list(String cursor, Integer limit) {
        return list(null, cursor, limit);
    }

    /** Chỉ một phạm vi, ví dụ {@code BLOCK_HASH_VSDC} cho công cụ giám sát (Q96); null là mọi phạm vi được xem. */
    public Paging.Page<Run> list(String scope, String cursor, Integer limit) {
        int size = Paging.limit(limit);
        UUID before = Paging.decodeCursor(cursor);
        String[] scopes = visibleScopes(CurrentUser.get().roles()).stream().filter(s -> scope == null || s.equals(scope))
                .toArray(String[]::new);
        List<Object> args = new ArrayList<>(List.of((Object) scopes));
        String sql = "SELECT id, run_date, scope, break_count FROM governance.reconciliation_run WHERE scope = ANY (?)"
                + (before == null ? "" : " AND id < ?") + " ORDER BY id DESC LIMIT ?";
        if (before != null) {
            args.add(before);
        }
        args.add(size + 1);
        List<Run> runs = jdbc.query(sql, (rs, i) -> new Run(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                rs.getString(3), rs.getInt(4), breaks(rs.getObject(1, UUID.class))), args.toArray());
        return Paging.Page.of(runs, size, Run::id);
    }

    /** US-4.09-AC2 (Q75): đóng chênh lệch, bắt buộc lý do. */
    @Transactional
    public Break resolve(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(GovernanceError.RECONCILIATION_REASON_REQUIRED);
        }
        CurrentUser user = CurrentUser.get();
        List<Break> rows = jdbc.query(SELECT_BREAK + " WHERE id = ? FOR UPDATE", (rs, i) -> row(rs), id);
        if (rows.isEmpty()) {
            throw new ApiException(GovernanceError.RECONCILIATION_BREAK_NOT_FOUND);
        }
        if (rows.getFirst().resolvedAt() != null) {
            throw new ApiException(GovernanceError.RECONCILIATION_BREAK_RESOLVED);
        }
        jdbc.update("""
                UPDATE governance.reconciliation_break SET resolved_by = ?, resolved_at = ?, resolution = ? WHERE id = ?
                """, user.subject(), Timestamp.from(clock.instant()), reason, id);
        audit.record("RECONCILIATION_BREAK_RESOLVED", "reconciliation-break/" + id, Map.of("reason", reason));
        return jdbc.query(SELECT_BREAK + " WHERE id = ?", (rs, i) -> row(rs), id).getFirst();
    }

    private List<Break> breaks(UUID run) {
        return jdbc.query(SELECT_BREAK + " WHERE run_id = ? ORDER BY id", (rs, i) -> row(rs), run);
    }

    private static final String SELECT_BREAK = """
            SELECT id, rule, kind, subject_ref, expected, actual, resolved_by, resolved_at, resolution
            FROM governance.reconciliation_break""";

    private static Break row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp at = rs.getTimestamp(8);
        return new Break(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), at == null ? null : at.toInstant(), rs.getString(9));
    }
}
