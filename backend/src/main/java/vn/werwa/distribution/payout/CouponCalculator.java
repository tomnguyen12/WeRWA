package vn.werwa.distribution.payout;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tính coupon và thuế khấu trừ cho từng dòng (US-2.09; Q70, Q71). Mọi phép tính trên số nguyên: gross của một dòng là
 * {@code floor(số lượng × mệnh giá × lãi suất ppm × phần năm / 10⁶)}; thuế {@code floor(gross × thuế suất ppm / 10⁶)};
 * net = gross − thuế. Phần làm tròn = phần nguyên của tổng tính đúng − tổng gross các dòng.
 */
public final class CouponCalculator {

    private static final BigInteger PPM = BigInteger.valueOf(1_000_000);

    private CouponCalculator() {}

    public record Holding(UUID walletId, UUID investorOrgId, long quantity, long frozenQuantity) {}

    public record Terms(long faceValueMinor, int ratePpm, int withholdingTaxPpm, DayCount.Fraction yearFraction) {}

    public record Line(UUID walletId, UUID investorOrgId, long quantity, long grossMinor, long taxMinor, long netMinor,
            String withheldReason) {}

    public record Result(List<Line> lines, long amountDueMinor, long roundingResidualMinor, long totalQuantity) {}

    public static Result calculate(List<Holding> holdings, Terms t) {
        List<Line> lines = new ArrayList<>();
        long total = 0;
        long quantity = 0;
        for (Holding h : holdings) {
            long free = h.quantity() - h.frozenQuantity();
            if (free > 0) {
                lines.add(line(h, free, null, t));
            }
            if (h.frozenQuantity() > 0) {
                // Q71: phần phong tỏa vẫn tính, nhưng giữ lại tại IPA
                lines.add(line(h, h.frozenQuantity(), "FROZEN", t));
            }
            quantity += h.quantity();
        }
        for (Line l : lines) {
            total = Math.addExact(total, l.grossMinor());
        }
        long exact = exactGross(quantity, t).longValueExact();
        return new Result(lines, total, exact - total, quantity);
    }

    private static Line line(Holding h, long quantity, String withheld, Terms t) {
        long gross = exactGross(quantity, t).longValueExact();
        long tax = BigInteger.valueOf(gross).multiply(BigInteger.valueOf(t.withholdingTaxPpm())).divide(PPM).longValueExact();
        return new Line(h.walletId(), h.investorOrgId(), quantity, gross, tax, gross - tax, withheld);
    }

    /** Phần nguyên của số lượng × mệnh giá × lãi suất × phần năm. */
    static BigInteger exactGross(long quantity, Terms t) {
        BigInteger numerator = BigInteger.valueOf(quantity).multiply(BigInteger.valueOf(t.faceValueMinor()))
                .multiply(BigInteger.valueOf(t.ratePpm())).multiply(t.yearFraction().numerator());
        return numerator.divide(PPM.multiply(t.yearFraction().denominator()));
    }
}
