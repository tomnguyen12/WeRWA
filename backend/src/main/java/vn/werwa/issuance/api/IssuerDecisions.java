package vn.werwa.issuance.api;

import java.util.Optional;
import java.util.UUID;

/** Quyết định của UBND cho các mô-đun khác (US-2.06-AC2, AC3). */
public interface IssuerDecisions {

    /** Quyết định lãi suất mới nhất của đợt chào bán, hoặc của series nếu đợt chưa có quyết định riêng. */
    Optional<Ref> latestCouponRate(UUID seriesId, UUID offeringId);

    record Ref(UUID id, String decisionNo, String decisionHash) {}
}
