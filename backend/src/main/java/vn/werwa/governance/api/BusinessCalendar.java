package vn.werwa.governance.api;

import java.time.LocalDate;
import java.util.Collection;

/**
 * Lịch ngày làm việc (4.11; Q32). Mã lịch: {@code VN}, {@code NY}. Một ngày là ngày làm việc của một tập lịch khi nó là ngày
 * làm việc ở mọi lịch trong tập. Ngày nghỉ tính theo luật cho mọi năm, cộng bảng điều chỉnh {@code governance.holiday_calendar}.
 */
public interface BusinessCalendar {

    boolean isBusinessDay(LocalDate day, Collection<String> calendars);

    /** Ngày làm việc kế tiếp, tính cả chính ngày đó. */
    default LocalDate following(LocalDate day, Collection<String> calendars) {
        LocalDate d = day;
        while (!isBusinessDay(d, calendars)) {
            d = d.plusDays(1);
        }
        return d;
    }

    /** Ngày làm việc liền trước, tính cả chính ngày đó. */
    default LocalDate preceding(LocalDate day, Collection<String> calendars) {
        LocalDate d = day;
        while (!isBusinessDay(d, calendars)) {
            d = d.minusDays(1);
        }
        return d;
    }
}
