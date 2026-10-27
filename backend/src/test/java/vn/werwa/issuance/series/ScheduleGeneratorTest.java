package vn.werwa.issuance.series;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.werwa.governance.calendar.CalendarService;
import vn.werwa.issuance.series.ScheduleGenerator.Convention;
import vn.werwa.issuance.series.ScheduleGenerator.Event;

class ScheduleGeneratorTest {

    static final List<String> VN_NY = List.of("VN", "NY");
    final CalendarService calendar = CalendarService.rulesOnly();

    @Test
    @DisplayName("US-2.05-AC2 USD trả 6 tháng: đủ kỳ, ngày trả là ngày làm việc ở cả VN và NY, ngày chốt là ngày làm việc liền trước")
    void US_2_05_AC2_semiAnnualUsdScheduleOnVnAndNyCalendars() {
        List<Event> events = new ScheduleGenerator(calendar, Convention.FOLLOWING)
                .generate(LocalDate.of(2027, 3, 2), LocalDate.of(2030, 3, 2), "SEMI_ANNUAL", VN_NY);

        assertThat(events).filteredOn(e -> e.eventType().equals("COUPON")).hasSize(6);
        assertThat(events).filteredOn(e -> e.eventType().equals("MATURITY")).singleElement()
                .satisfies(e -> assertThat(e.periodNo()).isEqualTo(6));
        for (Event e : events) {
            assertThat(calendar.isBusinessDay(e.paymentDate(), VN_NY)).as("ngày trả %s", e).isTrue();
            assertThat(calendar.isBusinessDay(e.recordDate(), VN_NY)).as("ngày chốt %s", e).isTrue();
            assertThat(e.recordDate()).isEqualTo(calendar.preceding(e.paymentDate().minusDays(1), VN_NY));
            assertThat(e.paymentDate()).isAfterOrEqualTo(e.periodEnd());
        }
        // Kỳ 1 kết thúc 2/9/2027 (Quốc khánh, thứ Năm; nghỉ cả 3/9); 6/9 là Labor Day ở NY nên ngày trả là 7/9
        Event first = events.getFirst();
        assertThat(first.periodStart()).isEqualTo(LocalDate.of(2027, 3, 2));
        assertThat(first.periodEnd()).isEqualTo(LocalDate.of(2027, 9, 2));
        assertThat(first.paymentDate()).isEqualTo(LocalDate.of(2027, 9, 7));
        assertThat(first.recordDate()).isEqualTo(LocalDate.of(2027, 9, 1));
        // Kỳ sau vẫn tính từ ngày chưa dời
        assertThat(events.get(1).periodStart()).isEqualTo(LocalDate.of(2027, 9, 2));
        assertThat(events.get(1).periodEnd()).isEqualTo(LocalDate.of(2028, 3, 2));
    }

    @Test
    void lastPeriodIsAShortStubWhenMaturityIsNotOnTheGrid() {
        List<Event> events = new ScheduleGenerator(calendar, Convention.FOLLOWING)
                .generate(LocalDate.of(2027, 1, 15), LocalDate.of(2028, 3, 15), "ANNUAL", List.of("VN"));
        assertThat(events).extracting(Event::periodEnd)
                .containsExactly(LocalDate.of(2028, 1, 15), LocalDate.of(2028, 3, 15), LocalDate.of(2028, 3, 15));
    }

    @Test
    void modifiedFollowingStaysInTheSameMonth() {
        // 31/10/2027 là Chủ nhật: FOLLOWING sang 1/11, MODIFIED_FOLLOWING lùi về 29/10
        ScheduleGenerator modified = new ScheduleGenerator(calendar, Convention.MODIFIED_FOLLOWING);
        assertThat(modified.adjust(LocalDate.of(2027, 10, 31), List.of("VN"))).isEqualTo(LocalDate.of(2027, 10, 29));
        ScheduleGenerator following = new ScheduleGenerator(calendar, Convention.FOLLOWING);
        assertThat(following.adjust(LocalDate.of(2027, 10, 31), List.of("VN"))).isEqualTo(LocalDate.of(2027, 11, 1));
    }
}
