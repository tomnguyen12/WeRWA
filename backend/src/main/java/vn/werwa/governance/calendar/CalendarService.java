package vn.werwa.governance.calendar;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.werwa.governance.api.BusinessCalendar;

/**
 * Lịch ngày làm việc (Q32): luật trong {@link HolidayRules} cộng bảng điều chỉnh {@code governance.holiday_calendar}.
 * Bảng điều chỉnh có hai loại: {@code HOLIDAY} thêm ngày nghỉ (nghỉ bù, nghỉ đột xuất), {@code WORKING_DAY} biến ngày nghỉ
 * thành ngày làm việc (làm bù, hoán đổi). Giao diện sửa bảng điều chỉnh làm ở B12 (tham số).
 */
public class CalendarService implements BusinessCalendar {

    private final JdbcTemplate jdbc;
    private final Map<String, Map<LocalDate, String>> rules = new ConcurrentHashMap<>();

    public CalendarService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Lịch chỉ theo luật, không đọc bảng điều chỉnh (kiểm thử, công cụ). */
    public static CalendarService rulesOnly() {
        return new CalendarService(null);
    }

    @Override
    public boolean isBusinessDay(LocalDate day, Collection<String> calendars) {
        for (String calendar : calendars) {
            if (!businessDay(day, calendar)) {
                return false;
            }
        }
        return true;
    }

    /** Ngày nghỉ theo luật của một lịch trong một năm, chưa gồm điều chỉnh. */
    public Map<LocalDate, String> ruleHolidays(String calendar, int year) {
        return rules.computeIfAbsent(calendar + ":" + year, k -> Map.copyOf(HolidayRules.holidays(calendar, year)));
    }

    boolean businessDay(LocalDate day, String calendar) {
        List<String> adjustment = jdbc == null ? List.of() : jdbc.queryForList(
                "SELECT kind FROM governance.holiday_calendar WHERE calendar_code = ? AND day = ?", String.class, calendar, day);
        if (!adjustment.isEmpty()) {
            return "WORKING_DAY".equals(adjustment.getFirst());
        }
        return !HolidayRules.weekend(day) && !ruleHolidays(calendar, day.getYear()).containsKey(day);
    }
}
