package vn.werwa.issuance.series;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import vn.werwa.governance.api.BusinessCalendar;

/**
 * Sinh lịch sự kiện của series (US-2.05-AC2). Kỳ tính từ ngày phát hành theo tần suất trả lãi; kỳ cuối kết thúc ở ngày
 * đáo hạn (kỳ lẻ cuối nếu không chia hết). Ngày trả là ngày cuối kỳ dời theo quy tắc ngày làm việc (tham số); ngày chốt là
 * ngày làm việc liền trước ngày trả. Ngày làm việc là ngày làm việc ở mọi lịch của series (VN, NY).
 */
public final class ScheduleGenerator {

    public enum Convention { FOLLOWING, MODIFIED_FOLLOWING }

    public record Event(
            String eventType, int periodNo, LocalDate periodStart, LocalDate periodEnd, LocalDate recordDate,
            LocalDate paymentDate) {}

    private final BusinessCalendar calendar;
    private final java.util.function.Supplier<Convention> convention;

    public ScheduleGenerator(BusinessCalendar calendar, Convention convention) {
        this(calendar, () -> convention);
    }

    /** Quy tắc dời ngày đọc mỗi lần sinh lịch, vì là tham số đổi được (Q60). */
    public ScheduleGenerator(BusinessCalendar calendar, java.util.function.Supplier<Convention> convention) {
        this.calendar = calendar;
        this.convention = convention;
    }

    public static int months(String couponFrequency) {
        return switch (couponFrequency) {
            case "ANNUAL" -> 12;
            case "SEMI_ANNUAL" -> 6;
            case "QUARTERLY" -> 3;
            default -> throw new IllegalArgumentException("tần suất không hỗ trợ: " + couponFrequency);
        };
    }

    public List<Event> generate(LocalDate issueDate, LocalDate maturityDate, String couponFrequency, List<String> calendars) {
        int step = months(couponFrequency);
        List<Event> events = new ArrayList<>();
        LocalDate start = issueDate;
        for (int n = 1; start.isBefore(maturityDate); n++) {
            // Tính từ ngày phát hành mỗi kỳ để không trôi ngày cuối tháng
            LocalDate end = issueDate.plusMonths((long) step * n);
            if (end.isAfter(maturityDate)) {
                end = maturityDate;
            }
            LocalDate payment = adjust(end, calendars);
            LocalDate record = calendar.preceding(payment.minusDays(1), calendars);
            events.add(new Event("COUPON", n, start, end, record, payment));
            if (end.equals(maturityDate)) {
                events.add(new Event("MATURITY", n, start, end, record, payment));
            }
            start = end;
        }
        return events;
    }

    LocalDate adjust(LocalDate day, List<String> calendars) {
        LocalDate next = calendar.following(day, calendars);
        if (convention.get() == Convention.MODIFIED_FOLLOWING && next.getMonthValue() != day.getMonthValue()) {
            return calendar.preceding(day, calendars);
        }
        return next;
    }

}
