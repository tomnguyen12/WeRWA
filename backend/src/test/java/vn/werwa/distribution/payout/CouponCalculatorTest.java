package vn.werwa.distribution.payout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.werwa.distribution.payout.CouponCalculator.Holding;
import vn.werwa.distribution.payout.CouponCalculator.Line;
import vn.werwa.distribution.payout.CouponCalculator.Result;
import vn.werwa.distribution.payout.CouponCalculator.Terms;

/** Bộ tính coupon (US-2.09; Q69–Q71). */
class CouponCalculatorTest {

    static final UUID ORG = UUID.randomUUID();
    static final DayCount.Fraction HALF_YEAR_30_360 =
            DayCount.fraction("30_360", LocalDate.of(2027, 3, 2), LocalDate.of(2027, 9, 2), 2);

    @Test
    @DisplayName("US-2.09-AC1 500 trái phiếu, mệnh giá 1.000 USD, 5,30%/năm, 30/360, 6 tháng: gross kỳ 1 = 13.250,00 USD")
    void US_2_09_AC1_grossForTheReferenceCase() {
        Result r = CouponCalculator.calculate(List.of(new Holding(UUID.randomUUID(), ORG, 500, 0)),
                new Terms(1_000_00, 53_000, 0, HALF_YEAR_30_360));
        assertThat(r.lines()).singleElement().satisfies(l -> {
            assertThat(l.grossMinor()).isEqualTo(13_250_00);
            assertThat(l.taxMinor()).isZero();
            assertThat(l.netMinor()).isEqualTo(13_250_00);
        });
        assertThat(r.amountDueMinor()).isEqualTo(13_250_00);
        assertThat(r.roundingResidualMinor()).isZero();
    }

    @Test
    @DisplayName("US-2.09-AC2 mỗi dòng có gross, thuế, net; làm tròn xuống tới cent, phần làm tròn báo riêng")
    void US_2_09_AC2_taxAndRoundingDown() {
        // 5,125%/năm, 1 cent mệnh giá để lộ phần lẻ; thuế 5%
        Terms t = new Terms(1, 51_250, 50_000, HALF_YEAR_30_360);
        List<Holding> h = List.of(new Holding(UUID.randomUUID(), ORG, 1_000, 0),
                new Holding(UUID.randomUUID(), ORG, 333, 0), new Holding(UUID.randomUUID(), ORG, 1_667, 0));
        Result r = CouponCalculator.calculate(h, t);
        // 1000 × 0,025625 = 25,625 → 25; 333 × 0,025625 = 8,53 → 8; 1667 × 0,025625 = 42,71 → 42; tổng đúng 3000 × 0,025625 = 76,875 → 76
        assertThat(r.lines()).extracting(Line::grossMinor).containsExactly(25L, 8L, 42L);
        assertThat(r.lines()).extracting(Line::taxMinor).containsExactly(1L, 0L, 2L);
        assertThat(r.lines()).allSatisfy(l -> assertThat(l.netMinor()).isEqualTo(l.grossMinor() - l.taxMinor()));
        assertThat(r.amountDueMinor()).isEqualTo(75);
        assertThat(r.roundingResidualMinor()).isEqualTo(1);
    }

    @Test
    @DisplayName("US-2.09-AC3 vị thế đang bị phong tỏa: dòng chi trả của phần phong tỏa bị giữ lại với lý do FROZEN")
    void US_2_09_AC3_frozenPartIsWithheld() {
        UUID wallet = UUID.randomUUID();
        Result r = CouponCalculator.calculate(List.of(new Holding(wallet, ORG, 500, 200)),
                new Terms(1_000_00, 53_000, 0, HALF_YEAR_30_360));
        assertThat(r.lines()).hasSize(2);
        assertThat(r.lines().get(0)).satisfies(l -> {
            assertThat(l.quantity()).isEqualTo(300);
            assertThat(l.withheldReason()).isNull();
            assertThat(l.grossMinor()).isEqualTo(7_950_00);
        });
        assertThat(r.lines().get(1)).satisfies(l -> {
            assertThat(l.quantity()).isEqualTo(200);
            assertThat(l.withheldReason()).isEqualTo("FROZEN");
            assertThat(l.grossMinor()).isEqualTo(5_300_00);
        });
    }

    @Test
    @DisplayName("US-2.09-AC4 tổng các dòng bằng số phải trả của kỳ")
    void US_2_09_AC4_linesAddUpToTheAmountDue() {
        java.util.Random random = new java.util.Random(7);
        List<Holding> h = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long q = 1 + random.nextInt(5_000);
            h.add(new Holding(UUID.randomUUID(), ORG, q, random.nextInt(4) == 0 ? random.nextLong(q + 1) : 0));
        }
        Result r = CouponCalculator.calculate(h, new Terms(1_000_00, 51_250, 50_000,
                DayCount.fraction("ACT_ACT", LocalDate.of(2027, 3, 2), LocalDate.of(2027, 9, 2), 2)));
        assertThat(r.lines().stream().mapToLong(Line::grossMinor).sum()).isEqualTo(r.amountDueMinor());
        assertThat(r.roundingResidualMinor()).isBetween(0L, (long) r.lines().size());
        assertThat(r.totalQuantity()).isEqualTo(h.stream().mapToLong(Holding::quantity).sum());
    }

    @Test
    void dayCountConventions() {
        assertThat(DayCount.days30360(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 7, 31))).isEqualTo(180);
        assertThat(DayCount.days30360(LocalDate.of(2027, 2, 28), LocalDate.of(2027, 8, 31))).isEqualTo(183);
        assertThat(DayCount.fraction("ACT_ACT", LocalDate.of(2027, 3, 2), LocalDate.of(2027, 9, 2), 2))
                .isEqualTo(DayCount.Fraction.of(1, 2));
        // Kỳ lẻ cuối 2/3/2030 → 2/5/2030: 61 ngày / (2 × 184 ngày của kỳ chuẩn)
        assertThat(DayCount.fraction("ACT_ACT", LocalDate.of(2030, 3, 2), LocalDate.of(2030, 5, 2), 2))
                .isEqualTo(DayCount.Fraction.of(61, 368));
    }
}
