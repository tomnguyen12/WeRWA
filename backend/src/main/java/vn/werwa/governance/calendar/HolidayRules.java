package vn.werwa.governance.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ngày nghỉ tính theo luật (Q32), chưa gồm bảng điều chỉnh.
 *
 * <ul>
 *   <li>{@code VN}: 1/1; Tết (ngày cuối năm âm lịch tới mùng 4); Giỗ Tổ 10/3 âm lịch; 30/4; 1/5; 2/9 và một ngày liền kề
 *       (1/9 nếu 2/9 là thứ Ba, còn lại 3/9). Lễ rơi vào thứ Bảy, Chủ nhật thì nghỉ bù ngày làm việc kế tiếp. Ngày Chính phủ
 *       chốt khác (hoán đổi, làm bù) nhập qua bảng điều chỉnh.
 *   <li>{@code NY}: ngày lễ liên bang Hoa Kỳ; thứ Bảy thì nghỉ thứ Sáu trước, Chủ nhật thì nghỉ thứ Hai sau.
 * </ul>
 */
final class HolidayRules {

    private HolidayRules() {}

    static boolean weekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    static Map<LocalDate, String> holidays(String calendar, int year) {
        return switch (calendar) {
            case "VN" -> vietnam(year);
            case "NY" -> newYork(year);
            default -> throw new IllegalArgumentException("lịch không hỗ trợ: " + calendar);
        };
    }

    static Map<LocalDate, String> vietnam(int year) {
        Map<LocalDate, String> days = new TreeMap<>();
        days.put(LocalDate.of(year, 1, 1), "Tết Dương lịch");
        // Tết của năm âm lịch bắt đầu trong năm dương lịch này
        LocalDate tet = LunarCalendar.toSolar(1, 1, year);
        days.put(tet.minusDays(1), "Tết Nguyên đán (cuối năm)");
        for (int i = 0; i <= 3; i++) {
            days.put(tet.plusDays(i), "Tết Nguyên đán");
        }
        days.put(LunarCalendar.toSolar(10, 3, year), "Giỗ Tổ Hùng Vương");
        days.put(LocalDate.of(year, 4, 30), "Ngày Chiến thắng");
        days.put(LocalDate.of(year, 5, 1), "Quốc tế Lao động");
        LocalDate national = LocalDate.of(year, 9, 2);
        days.put(national, "Quốc khánh");
        days.put(national.getDayOfWeek() == DayOfWeek.TUESDAY ? national.minusDays(1) : national.plusDays(1), "Quốc khánh");
        Map<LocalDate, String> out = new TreeMap<>(days);
        for (Map.Entry<LocalDate, String> e : days.entrySet()) {
            if (weekend(e.getKey())) {
                LocalDate d = e.getKey().plusDays(1);
                while (weekend(d) || out.containsKey(d)) {
                    d = d.plusDays(1);
                }
                out.put(d, "Nghỉ bù " + e.getValue());
            }
        }
        return out;
    }

    static Map<LocalDate, String> newYork(int year) {
        Map<LocalDate, String> days = new TreeMap<>();
        observed(days, LocalDate.of(year, 1, 1), "New Year's Day");
        days.put(nth(year, Month.JANUARY, DayOfWeek.MONDAY, 3), "Martin Luther King Jr. Day");
        days.put(nth(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3), "Washington's Birthday");
        days.put(LocalDate.of(year, 5, 31).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), "Memorial Day");
        observed(days, LocalDate.of(year, 6, 19), "Juneteenth");
        observed(days, LocalDate.of(year, 7, 4), "Independence Day");
        days.put(nth(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1), "Labor Day");
        days.put(nth(year, Month.OCTOBER, DayOfWeek.MONDAY, 2), "Columbus Day");
        observed(days, LocalDate.of(year, 11, 11), "Veterans Day");
        days.put(nth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4), "Thanksgiving Day");
        observed(days, LocalDate.of(year, 12, 25), "Christmas Day");
        // 1/1 năm sau rơi vào thứ Bảy thì nghỉ 31/12 năm nay
        if (LocalDate.of(year + 1, 1, 1).getDayOfWeek() == DayOfWeek.SATURDAY) {
            days.put(LocalDate.of(year, 12, 31), "New Year's Day (observed)");
        }
        return days;
    }

    private static void observed(Map<LocalDate, String> days, LocalDate d, String name) {
        if (d.getDayOfWeek() == DayOfWeek.SATURDAY) {
            if (d.getMonthValue() == 1 && d.getDayOfMonth() == 1) {
                return; // nghỉ 31/12 năm trước
            }
            days.put(d.minusDays(1), name + " (observed)");
        } else if (d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            days.put(d.plusDays(1), name + " (observed)");
        } else {
            days.put(d, name);
        }
    }

    private static LocalDate nth(int year, Month month, DayOfWeek dow, int n) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow));
    }
}
