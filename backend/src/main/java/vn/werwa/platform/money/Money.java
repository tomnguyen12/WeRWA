package vn.werwa.platform.money;

import java.util.Objects;
import java.util.regex.Pattern;
import vn.werwa.platform.web.ApiException;
import vn.werwa.platform.web.PlatformError;

/**
 * Tiền theo đơn vị nhỏ nhất (CLAUDE.md): {@code amountMinor} nguyên, không âm, kèm mã tiền ISO 4217. Trong JSON là
 * {@code {"amountMinor":"100000000","currency":"USD"}}. Phép tính kiểm tràn số; không bao giờ dùng số thực.
 */
public record Money(long amountMinor, String currency) {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (amountMinor < 0) {
            throw new IllegalArgumentException("amountMinor âm: " + amountMinor);
        }
        if (!CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency không hợp lệ: " + currency);
        }
    }

    /** Đọc từ dạng JSON của API; sai dạng hoặc vượt giới hạn trả 400 {@code VALIDATION_FAILED}. */
    public static Money parse(String amountMinor, String currency) {
        if (amountMinor == null || !DIGITS.matcher(amountMinor).matches()) {
            throw new ApiException(PlatformError.VALIDATION_FAILED, "amountMinor phải là chuỗi chữ số");
        }
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new ApiException(PlatformError.VALIDATION_FAILED, "currency phải là mã ISO 4217");
        }
        try {
            return new Money(Long.parseLong(amountMinor), currency);
        } catch (NumberFormatException e) {
            throw new ApiException(PlatformError.VALIDATION_FAILED, "amountMinor vượt giới hạn");
        }
    }

    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    /** Dạng chuỗi cho trường {@code amountMinor} của JSON. */
    public String amountMinorString() {
        return Long.toString(amountMinor);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    public Money times(long quantity) {
        return new Money(Math.multiplyExact(amountMinor, quantity), currency);
    }

    public boolean isZero() {
        return amountMinor == 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("khác loại tiền: " + currency + " và " + other.currency);
        }
    }
}
