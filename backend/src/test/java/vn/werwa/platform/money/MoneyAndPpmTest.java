package vn.werwa.platform.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyAndPpmTest {

    @Test
    void parsesTheApiStringForm() {
        Money m = Money.parse("100000000", "USD");
        assertThat(m.amountMinor()).isEqualTo(100_000_000L);
        assertThat(m.amountMinorString()).isEqualTo("100000000");
    }

    @Test
    void rejectsNonDigitsBadCurrencyAndOverflow() {
        assertThatThrownBy(() -> Money.parse("-1", "USD")).hasMessageContaining("VALIDATION_FAILED");
        assertThatThrownBy(() -> Money.parse("1.5", "USD")).hasMessageContaining("VALIDATION_FAILED");
        assertThatThrownBy(() -> Money.parse("1", "usd")).hasMessageContaining("VALIDATION_FAILED");
        assertThatThrownBy(() -> Money.parse("99999999999999999999", "USD")).hasMessageContaining("VALIDATION_FAILED");
    }

    @Test
    void arithmeticChecksCurrencyAndOverflow() {
        Money a = new Money(5, "USD");
        assertThat(a.plus(new Money(7, "USD")).amountMinor()).isEqualTo(12);
        assertThat(a.times(3).amountMinor()).isEqualTo(15);
        assertThatThrownBy(() -> a.plus(new Money(1, "VND"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(Long.MAX_VALUE, "USD").plus(a)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> a.minus(new Money(6, "USD"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ppmApplyExactFollowsC5Divisibility() {
        // 500 trái phiếu × mệnh giá 1 000 000 xu × giá phát hành 99,5% = 497 500 000 xu
        Money principal = new Money(1_000_000L, "USD").times(500);
        assertThat(Ppm.of(995_000).applyExact(principal).amountMinor()).isEqualTo(497_500_000L);
        assertThatThrownBy(() -> Ppm.of(333_333).applyExact(new Money(1, "USD")))
                .isInstanceOf(ArithmeticException.class);
        assertThat(Ppm.of(333_333).applyFloor(new Money(10, "USD")).amountMinor()).isEqualTo(3);
    }
}
