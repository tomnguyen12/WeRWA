package vn.werwa.platform.money;

/**
 * Tỷ lệ theo phần triệu (CLAUDE.md): 1 000 000 ppm = 100%. Dùng cho lãi suất, giá phát hành, thuế suất.
 *
 * <p>{@link #applyExact} theo quy tắc C5 của hợp đồng: số tiền nhân tỷ lệ phải chia hết cho 10⁶, nếu không là sai lệch
 * (khớp lỗi {@code AmountMismatch} khi mint).
 */
public record Ppm(long value) {

    public static final long ONE = 1_000_000L;

    public Ppm {
        if (value < 0) {
            throw new IllegalArgumentException("ppm âm: " + value);
        }
    }

    public static Ppm of(long value) {
        return new Ppm(value);
    }

    /** {@code amountMinor × value / 10⁶}, bắt buộc chia hết. */
    public Money applyExact(Money amount) {
        long product = Math.multiplyExact(amount.amountMinor(), value);
        if (product % ONE != 0) {
            throw new ArithmeticException("không chia hết cho 10^6: " + amount.amountMinor() + " × " + value);
        }
        return new Money(product / ONE, amount.currency());
    }

    /** {@code amountMinor × value / 10⁶}, làm tròn xuống (dùng khi nghiệp vụ quy định rõ làm tròn xuống). */
    public Money applyFloor(Money amount) {
        return new Money(Math.multiplyExact(amount.amountMinor(), value) / ONE, amount.currency());
    }
}
