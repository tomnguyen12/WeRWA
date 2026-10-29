package vn.werwa.issuance.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Series cho mô-đun Phân phối (B7a; Q46): đọc tham số và đổi trạng thái ngoài chuỗi của đợt chào bán. */
public interface SeriesCatalog {

    Optional<SeriesInfo> find(UUID seriesId);

    /** Máy Series cạnh 6: READY → OFFERING khi mở sổ. */
    void markOffering(UUID seriesId);

    /** Máy Series cạnh 7: OFFERING → BOOK_CLOSED khi đóng sổ. */
    void markBookClosed(UUID seriesId);

    /** Một kỳ coupon của series, kèm tham số để tính coupon (B11a). Rỗng nếu không có kỳ đó. */
    Optional<Period> period(UUID seriesId, int periodNo);

    /** Ghi nghĩa vụ trả nợ của kỳ sau khi tính các dòng chi trả (Q70). Tính lại thì ghi đè. */
    void recordAmountDue(UUID seriesId, int periodNo, long amountDueMinor, long roundingResidualMinor);

    /** Nghĩa vụ trả nợ đã ghi của kỳ. */
    Optional<DebtService> debtService(UUID seriesId, int periodNo);

    record DebtService(long amountDueMinor, long roundingResidualMinor) {}

    /**
     * @param ratePpm lãi suất của kỳ; null nếu chưa ghi (Q72)
     */
    record Period(
            UUID eventId, int periodNo, java.time.LocalDate periodStart, java.time.LocalDate periodEnd, Integer ratePpm,
            String dayCount, String couponFrequency, long faceValueMinor, String currency, int withholdingTaxPpm) {}

    /**
     * @param rateFloorPpm khung lãi suất của Đề án đang hiệu lực
     * @param couponPeriods số kỳ trả lãi (số phần tử {@code periodRatePpm} của {@code setAllocation})
     */
    record SeriesInfo(
            UUID id, String status, String isin, String currency, long faceValueMinor, int issuePricePpm,
            long maxQuantity, List<String> investorClasses, int rateFloorPpm, int rateCapPpm, String controllerAddress,
            int couponPeriods, java.time.LocalDate issueDate) {}
}
