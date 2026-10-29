package vn.werwa.issuance.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Nghĩa vụ trả nợ theo kỳ (máy Kỳ trả nợ; B11c, Q98–Q105). Phân phối điều phối các bước; Phát hành giữ trạng thái và
 * phát sự kiện {@code funding_short}, {@code reconciled}. Khóa của một kỳ là id sự kiện trong lịch series.
 */
public interface DebtServices {

    /**
     * Một kỳ coupon hoặc kỳ gốc.
     *
     * @param status null khi chưa có dòng nghĩa vụ trả nợ
     */
    record Due(
            UUID eventId, UUID seriesId, String eventType, int periodNo, LocalDate recordDate, LocalDate paymentDate,
            String currency, int graceBusinessDays, List<String> calendars, String status, Long amountDueMinor,
            Long estimatedMinor, Long fundedMinor, Long shortfallMinor, LocalDate graceUntil, String instructionRef,
            Instant calculatedAt) {

        /** Số dùng để kiểm tiền: số đã tính, chưa tính thì số dự kiến. */
        public long dueMinor() {
            return amountDueMinor != null ? amountDueMinor : estimatedMinor == null ? 0 : estimatedMinor;
        }
    }

    /**
     * Kỳ cần xử lý: series đang lưu hành hoặc vi phạm, ngày chi tới {@code today + horizonDays}, chưa xong (chưa có dòng,
     * hoặc ở CALCULATED, REMINDED, FUNDS_CHECKED, LATE).
     */
    List<Due> open(LocalDate today, int horizonDays);

    Optional<Due> get(UUID eventId);

    List<Due> withStatus(String status);

    /** Kỳ gốc: ghi số phải trả sau khi tính dòng gốc (Q103). Kỳ coupon dùng {@code SeriesCatalog.recordAmountDue}. */
    void recordAmountDue(UUID eventId, long amountDueMinor);

    /** Cạnh 1: đã nhắc với số dự kiến. */
    void reminded(UUID eventId, long estimatedMinor);

    /** Cạnh 2: đã kiểm tiền; thiếu thì phát {@code werwa.issuance.debt_service.funding_short.v1}. */
    void fundsChecked(UUID eventId, long fundedMinor, long shortfallMinor);

    /** Cạnh 5, 9: đã gửi lệnh chi. */
    void instructed(UUID eventId, String instructionRef);

    /** Cạnh 8: ngày T thiếu tiền; ân hạn tới hết ngày {@code graceUntil}. */
    void late(UUID eventId, LocalDate graceUntil);

    /** Quá ân hạn; chuyển series sang Vi phạm là Đ1. */
    void graceExpired(UUID eventId);

    /** Cạnh 6: IPA xác nhận đã chi mọi dòng. */
    void paid(UUID eventId);

    /** Cạnh 7: đã chi khớp số phải trả trừ phần giữ lại; phát {@code werwa.issuance.debt_service.reconciled.v1}. */
    void reconciled(UUID eventId, long paidMinor, long withheldMinor);
}
