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

    /**
     * @param rateFloorPpm khung lãi suất của Đề án đang hiệu lực
     * @param couponPeriods số kỳ trả lãi (số phần tử {@code periodRatePpm} của {@code setAllocation})
     */
    record SeriesInfo(
            UUID id, String status, String isin, String currency, long faceValueMinor, int issuePricePpm,
            long maxQuantity, List<String> investorClasses, int rateFloorPpm, int rateCapPpm, String controllerAddress,
            int couponPeriods) {}
}
