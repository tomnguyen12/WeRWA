package vn.werwa.platform.registry;

import java.util.UUID;

/** Tóm tắt sổ của một series cho công cụ giám sát (Q91). Phân phối cài đặt; Phát hành không phụ thuộc ngược. */
public interface HoldingsSummaries {

    Summary summary(UUID seriesId);

    /** @param holderCount số ví có số dư lớn hơn 0 */
    record Summary(int holderCount, long totalBalance, long frozen, long asOfBlock) {}
}
