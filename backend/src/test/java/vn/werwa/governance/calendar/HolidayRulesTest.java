package vn.werwa.governance.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Lịch ngày làm việc theo luật (Q32). */
class HolidayRulesTest {

    @Test
    void tetFollowsTheLunarCalendar() {
        assertThat(LunarCalendar.toSolar(1, 1, 2024)).isEqualTo(LocalDate.of(2024, 2, 10));
        assertThat(LunarCalendar.toSolar(1, 1, 2025)).isEqualTo(LocalDate.of(2025, 1, 29));
        assertThat(LunarCalendar.toSolar(1, 1, 2026)).isEqualTo(LocalDate.of(2026, 2, 17));
        assertThat(LunarCalendar.toSolar(1, 1, 2027)).isEqualTo(LocalDate.of(2027, 2, 6));
        assertThat(LunarCalendar.toSolar(1, 1, 2028)).isEqualTo(LocalDate.of(2028, 1, 26));
        // Sóc 23h07 ngày 2/2/2030 giờ Hà Nội: Tết Việt Nam 2/2, sớm hơn Trung Quốc (3/2) một ngày
        assertThat(LunarCalendar.toSolar(1, 1, 2030)).isEqualTo(LocalDate.of(2030, 2, 2));
    }

    @Test
    void hungKingsDayHandlesLeapMonths() {
        // 2023 có tháng 2 nhuận: 10/3 âm lịch là 29/4
        assertThat(LunarCalendar.toSolar(10, 3, 2023)).isEqualTo(LocalDate.of(2023, 4, 29));
        assertThat(LunarCalendar.toSolar(10, 3, 2024)).isEqualTo(LocalDate.of(2024, 4, 18));
        assertThat(LunarCalendar.toSolar(10, 3, 2025)).isEqualTo(LocalDate.of(2025, 4, 7));
        assertThat(LunarCalendar.toSolar(10, 3, 2026)).isEqualTo(LocalDate.of(2026, 4, 26));
    }

    @Test
    void vietnamHolidaysWithCompensatoryDays() {
        var days = HolidayRules.vietnam(2026);
        // Tết 2026: 16/2 (cuối năm) tới 20/2
        assertThat(days).containsKeys(LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 17), LocalDate.of(2026, 2, 20));
        // Giỗ Tổ 26/4/2026 là Chủ nhật: nghỉ bù thứ Hai 27/4
        assertThat(days).containsKeys(LocalDate.of(2026, 4, 26), LocalDate.of(2026, 4, 27));
        assertThat(days).containsKeys(LocalDate.of(2026, 4, 30), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 9, 2));
    }

    @Test
    void newYorkFederalHolidaysWithObservedDays() {
        var days = HolidayRules.newYork(2026);
        assertThat(days).containsKeys(LocalDate.of(2026, 1, 19), LocalDate.of(2026, 2, 16), LocalDate.of(2026, 5, 25),
                LocalDate.of(2026, 6, 19), LocalDate.of(2026, 9, 7), LocalDate.of(2026, 10, 12),
                LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 25));
        // 4/7/2026 là thứ Bảy: nghỉ thứ Sáu 3/7
        assertThat(days).containsKey(LocalDate.of(2026, 7, 3)).doesNotContainKey(LocalDate.of(2026, 7, 4));
        // 25/12/2027 là thứ Bảy: nghỉ 24/12; 1/1/2022 thứ Bảy: nghỉ 31/12/2021
        assertThat(HolidayRules.newYork(2027)).containsKey(LocalDate.of(2027, 12, 24));
        assertThat(HolidayRules.newYork(2021)).containsKey(LocalDate.of(2021, 12, 31));
    }

    @Test
    void businessDayMustHoldInEveryCalendar() {
        CalendarService cal = CalendarService.rulesOnly();
        LocalDate laborDay = LocalDate.of(2027, 9, 6);
        assertThat(cal.isBusinessDay(laborDay, List.of("VN"))).isTrue();
        assertThat(cal.isBusinessDay(laborDay, List.of("VN", "NY"))).isFalse();
        assertThat(cal.following(LocalDate.of(2027, 9, 2), List.of("VN", "NY"))).isEqualTo(LocalDate.of(2027, 9, 7));
        assertThat(cal.preceding(LocalDate.of(2027, 9, 6), List.of("VN", "NY"))).isEqualTo(LocalDate.of(2027, 9, 1));
    }
}
