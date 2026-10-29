package vn.werwa.distribution.payout;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Phần năm của một kỳ coupon (Q69), dạng phân số đúng để không dùng số thực cho tiền. */
public final class DayCount {

    private DayCount() {}

    /** Phân số {@code numerator / denominator}. */
    public record Fraction(BigInteger numerator, BigInteger denominator) {
        static Fraction of(long n, long d) {
            return new Fraction(BigInteger.valueOf(n), BigInteger.valueOf(d));
        }
    }

    /**
     * @param dayCount {@code 30_360} (30/360 bond basis) hoặc {@code ACT_ACT} (ICMA)
     * @param periodsPerYear số kỳ trong năm (1, 2, 4)
     */
    public static Fraction fraction(String dayCount, LocalDate start, LocalDate end, int periodsPerYear) {
        return switch (dayCount) {
            case "30_360" -> Fraction.of(days30360(start, end), 360);
            case "ACT_ACT" -> actActIcma(start, end, periodsPerYear);
            default -> throw new IllegalArgumentException("quy ước đếm ngày không hỗ trợ: " + dayCount);
        };
    }

    /** 30/360 bond basis: D1 = 31 thì 30; D2 = 31 và D1 ≥ 30 thì 30. */
    static long days30360(LocalDate start, LocalDate end) {
        int d1 = Math.min(start.getDayOfMonth(), 30);
        int d2 = end.getDayOfMonth() == 31 && d1 >= 30 ? 30 : end.getDayOfMonth();
        return 360L * (end.getYear() - start.getYear()) + 30L * (end.getMonthValue() - start.getMonthValue()) + (d2 - d1);
    }

    /** ICMA: kỳ đều = 1/tần suất; kỳ lẻ = ngày thực / (tần suất × số ngày của kỳ chuẩn bắt đầu cùng ngày). */
    static Fraction actActIcma(LocalDate start, LocalDate end, int periodsPerYear) {
        LocalDate notionalEnd = start.plusMonths(12 / periodsPerYear);
        if (end.equals(notionalEnd)) {
            return Fraction.of(1, periodsPerYear);
        }
        long actual = ChronoUnit.DAYS.between(start, end);
        long notional = ChronoUnit.DAYS.between(start, notionalEnd);
        return Fraction.of(actual, (long) periodsPerYear * notional);
    }
}
