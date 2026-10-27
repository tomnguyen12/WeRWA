package vn.werwa.governance.calendar;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.governance.api.Approvals;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.CurrentUser;

/**
 * Điều chỉnh lịch ngày làm việc có duyệt 4 mắt (US-4.11-AC1; Q32, Q61). Duyệt xong thì ghi bảng điều chỉnh và phát
 * {@code werwa.governance.calendar.changed.v1} để Phát hành tính lại lịch các kỳ chưa tới ngày chốt.
 */
public class CalendarAdjustmentService {

    public static final String ADJUST_ACTION = "CALENDAR_ADJUSTMENT";
    static final String CHANGED_EVENT = "werwa.governance.calendar.changed.v1";

    private final JdbcTemplate jdbc;
    private final CalendarService calendar;
    private final Approvals approvals;
    private final AuditLog audit;
    private final Events events;
    private final Clock clock;

    public CalendarAdjustmentService(JdbcTemplate jdbc, CalendarService calendar, Approvals approvals, AuditLog audit,
            Events events, Clock clock) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.approvals = approvals;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    public record Day(LocalDate day, String name, String source) {}

    public record Year(String calendarCode, int year, List<Day> holidays, List<Day> workingDays) {}

    public record Request(UUID id, String calendarCode, LocalDate day, String kind, String name, String status,
            UUID approvalRequestId) {}

    /** Ngày nghỉ theo luật trừ ngày làm bù, cộng ngày nghỉ điều chỉnh; và các ngày làm bù. */
    public Year year(String code, int year) {
        Map<LocalDate, String> rules = calendar.ruleHolidays(code, year);
        Map<LocalDate, String[]> adjustments = new LinkedHashMap<>();
        jdbc.query("""
                SELECT day, kind, name FROM governance.holiday_calendar
                WHERE calendar_code = ? AND day BETWEEN ? AND ? ORDER BY day
                """, rs -> {
            adjustments.put(rs.getObject(1, LocalDate.class), new String[] {rs.getString(2), rs.getString(3)});
        }, code, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        List<Day> holidays = new ArrayList<>();
        List<Day> working = new ArrayList<>();
        rules.forEach((d, name) -> {
            if (!adjustments.containsKey(d)) {
                holidays.add(new Day(d, name, "RULE"));
            }
        });
        adjustments.forEach((d, a) -> (a[0].equals("HOLIDAY") ? holidays : working).add(new Day(d, a[1], "ADJUSTMENT")));
        holidays.sort(java.util.Comparator.comparing(Day::day));
        return new Year(code, year, holidays, working);
    }

    @Transactional
    public Request request(String code, LocalDate day, String kind, String name) {
        UUID id = Ids.newId();
        jdbc.update("""
                INSERT INTO governance.calendar_adjustment_request
                    (id, calendar_code, day, kind, name, status, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING_APPROVAL', ?, ?)
                """, id, code, day, kind, name, CurrentUser.get().subject(), Timestamp.from(clock.instant()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", id.toString());
        payload.put("calendarCode", code);
        payload.put("day", day.toString());
        payload.put("kind", kind);
        payload.put("name", name);
        UUID approval = approvals.request(ADJUST_ACTION, "calendar/" + code + "/" + day, payload);
        jdbc.update("UPDATE governance.calendar_adjustment_request SET approval_request_id = ? WHERE id = ?", approval, id);
        audit.record("CALENDAR_ADJUSTMENT_REQUESTED", "calendar/" + code, payload);
        return get(id);
    }

    @Transactional
    public void applyDecision(UUID approvalId, boolean approve) {
        List<Request> rows = jdbc.query(SELECT + " WHERE approval_request_id = ? AND status = 'PENDING_APPROVAL' FOR UPDATE",
                (rs, i) -> row(rs), approvalId);
        if (rows.isEmpty()) {
            return;
        }
        Request r = rows.getFirst();
        jdbc.update("UPDATE governance.calendar_adjustment_request SET status = ? WHERE id = ?",
                approve ? "APPROVED" : "REJECTED", r.id());
        if (!approve) {
            return;
        }
        jdbc.update("""
                INSERT INTO governance.holiday_calendar (calendar_code, day, kind, name, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (calendar_code, day) DO UPDATE SET kind = excluded.kind, name = excluded.name,
                    created_by = excluded.created_by, created_at = excluded.created_at
                """, r.calendarCode(), r.day(), r.kind(), r.name(), CurrentUser.find().map(u -> u.subject()).orElse("system"),
                Timestamp.from(clock.instant()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("calendarCode", r.calendarCode());
        data.put("day", r.day().toString());
        data.put("kind", r.kind());
        data.put("name", r.name());
        events.publish(CHANGED_EVENT, "calendar/" + r.calendarCode(), data);
        audit.record("CALENDAR_ADJUSTED", "calendar/" + r.calendarCode(), data);
    }

    Request get(UUID id) {
        return jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> row(rs), id).getFirst();
    }

    private static final String SELECT = """
            SELECT id, calendar_code, day, kind, name, status, approval_request_id
            FROM governance.calendar_adjustment_request""";

    private static Request row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Request(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, LocalDate.class), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class));
    }
}
