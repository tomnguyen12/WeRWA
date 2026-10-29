package vn.werwa.distribution.allocation;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.utils.Numeric;
import vn.werwa.distribution.DistributionError;
import vn.werwa.distribution.offering.OfferingService;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.api.OperationalAlerts;
import vn.werwa.governance.api.SigningProposals;
import vn.werwa.issuance.api.SeriesCatalog;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.web.ApiException;

/**
 * UBND ký danh sách phân bổ trên chuỗi (US-3.03-AC3, US-2.06-AC3; Q121–Q127): sau duyệt 4 mắt, đề nghị ký Safe UBND gọi
 * {@code setAllocation}; indexer đọc {@code AllocationSet} thì danh sách SIGNED, phân bổ chờ tiền, lệnh có kết quả.
 */
public class AllocationSigning {

    public static final String PURPOSE = "ALLOCATION_SIGN";
    static final String SIGNED_EVENT = "werwa.distribution.allocation_list.signed.v1";
    private static final Set<String> OPEN = Set.of("QUEUED", "COLLECTING", "THRESHOLD_REACHED", "SENT");

    private final JdbcTemplate jdbc;
    private final AllocationListService lists;
    private final OfferingService offerings;
    private final SeriesCatalog series;
    private final SigningProposals proposals;
    private final OperationalAlerts alerts;
    private final Events events;
    private final AuditLog audit;
    private final Clock clock;

    public AllocationSigning(JdbcTemplate jdbc, AllocationListService lists, OfferingService offerings, SeriesCatalog series,
            SigningProposals proposals, OperationalAlerts alerts, Events events, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.lists = lists;
        this.offerings = offerings;
        this.series = series;
        this.proposals = proposals;
        this.alerts = alerts;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    /** Q122, Q127: danh sách đã được UBND duyệt và chưa có đề nghị ký đang mở. */
    @Transactional
    public SigningProposals.Ref propose(UUID listId) {
        Map<String, Object> row = jdbc.queryForList("""
                SELECT status, approved_at, signing_proposal_id, issuer_decision_hash FROM distribution.allocation_list
                WHERE id = ? FOR UPDATE
                """, listId).stream().findFirst().orElseThrow(() -> new ApiException(DistributionError.LIST_NOT_FOUND));
        if (!"SUBMITTED".equals(row.get("status")) || row.get("approved_at") == null) {
            throw new ApiException(DistributionError.LIST_NOT_APPROVED, String.valueOf(row.get("status")));
        }
        UUID previous = (UUID) row.get("signing_proposal_id");
        if (previous != null && proposals.status(previous).filter(OPEN::contains).isPresent()) {
            throw new ApiException(DistributionError.ALLOCATION_SIGNING_PENDING, previous.toString());
        }
        AllocationListService.AllocationList list = lists.get(listId);
        SeriesCatalog.SeriesInfo s = series.find(offerings.get(list.offeringId()).seriesId()).orElseThrow();
        if (s.controllerAddress() == null) {
            throw new ApiException(DistributionError.SERIES_NOT_DEPLOYED);
        }
        // Q123: lãi suất chốt cho mọi kỳ coupon, đủ periodCount
        List<Uint32> rates = list.periodRatesPpm().size() == s.couponPeriods()
                ? list.periodRatesPpm().stream().map(r -> new Uint32(BigInteger.valueOf(r))).toList()
                : java.util.Collections.nCopies(s.couponPeriods(), new Uint32(BigInteger.valueOf(list.couponRatePpm())));
        String calldata = FunctionEncoder.encode(new Function("setAllocation", List.of(
                new Bytes32(Numeric.hexStringToByteArray(list.merkleRoot())),
                new Bytes32(Numeric.hexStringToByteArray(list.listHash())),
                new Uint64(BigInteger.valueOf(list.totalQuantity())),
                new DynamicArray<>(Uint32.class, rates)), List.of()));
        SigningProposals.Ref ref = proposals.create(new SigningProposals.Request(PURPOSE, "allocation-list/" + listId,
                list.approvalRequestId(), "ubnd", s.controllerAddress().toLowerCase(Locale.ROOT), "setAllocation", calldata,
                (String) row.get("issuer_decision_hash")));
        jdbc.update("UPDATE distribution.allocation_list SET signing_proposal_id = ?, updated_at = ? WHERE id = ?", ref.id(),
                Timestamp.from(clock.instant()), listId);
        audit.record("ALLOCATION_LIST_SIGNING_PROPOSED", "allocation-list/" + listId,
                Map.of("signingProposalId", ref.id().toString()));
        return ref;
    }

    /** Danh sách gắn với một yêu cầu duyệt (để nhận {@code approval.decided}). */
    public java.util.Optional<UUID> listOfApproval(UUID approvalId) {
        return jdbc.queryForList("SELECT id FROM distribution.allocation_list WHERE approval_request_id = ?", UUID.class,
                approvalId).stream().findFirst();
    }

    /**
     * Q124: {@code AllocationSet} của controller. Tìm danh sách đã gửi có đúng gốc và listHash; đánh dấu đã ký, phân bổ chờ
     * tiền, kết quả lệnh, đợt chào bán đã phân bổ. Đã ký rồi thì không làm gì.
     */
    @Transactional
    public void onAllocationSet(String controller, Map<String, Object> args, String txHash) {
        String root = String.valueOf(args.get("root")).toLowerCase(Locale.ROOT);
        String listHash = String.valueOf(args.get("listHash")).toLowerCase(Locale.ROOT);
        UUID seriesId = series.findByController(controller)
                .orElseThrow(() -> new IllegalStateException("chưa biết series của controller " + controller));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT l.id, l.status, l.offering_id, l.total_quantity FROM distribution.allocation_list l
                JOIN distribution.offering o ON o.id = l.offering_id
                WHERE o.series_id = ? AND l.merkle_root = ? AND l.list_hash = ? AND l.status IN ('SUBMITTED', 'SIGNED')
                FOR UPDATE OF l
                """, seriesId, root, listHash);
        if (rows.isEmpty()) {
            alerts.raise("allocation-set:" + controller + ":" + root, "AllocationSetUnmatched", "critical",
                    "Controller " + controller + " ghi gốc " + root + " (tx " + txHash
                            + ") không khớp danh sách phân bổ nào đã gửi UBND");
            return;
        }
        Map<String, Object> row = rows.getFirst();
        if ("SIGNED".equals(row.get("status"))) {
            return;
        }
        UUID listId = (UUID) row.get("id");
        UUID offeringId = (UUID) row.get("offering_id");
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                UPDATE distribution.allocation_list SET status = 'SIGNED', signed_tx_hash = ?, signed_at = ?, updated_at = ?
                WHERE id = ?
                """, txHash.toLowerCase(Locale.ROOT), now, now, listId);
        jdbc.update("UPDATE distribution.allocation SET status = 'AWAITING_PAYMENT' WHERE allocation_list_id = ? "
                + "AND status = 'PROPOSED'", listId);
        // Kết quả lệnh: so số được phân bổ với số đặt; lệnh không có trong danh sách thì không được phân bổ
        jdbc.update("""
                UPDATE distribution."order" o SET status = CASE
                        WHEN a.quantity IS NULL THEN 'NOT_ALLOCATED'
                        WHEN a.quantity < o.quantity THEN 'PARTIALLY_ALLOCATED'
                        ELSE 'ALLOCATED' END,
                    version = o.version + 1, updated_at = ?
                FROM (SELECT x.id AS order_id, (SELECT al.quantity FROM distribution.allocation al
                                                 WHERE al.order_id = x.id AND al.allocation_list_id = ?) AS quantity
                      FROM distribution."order" x WHERE x.offering_id = ? AND x.status <> 'CANCELLED') a
                WHERE o.id = a.order_id
                """, now, listId, offeringId);
        jdbc.update("UPDATE distribution.offering SET status = 'ALLOCATED', updated_at = ? WHERE id = ? AND status = 'CLOSED'",
                now, offeringId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("listId", listId.toString());
        data.put("merkleRoot", root);
        data.put("totalQuantity", ((Number) row.get("total_quantity")).longValue());
        data.put("listHash", listHash);
        data.put("txHash", txHash);
        events.publish(SIGNED_EVENT, "allocation-list/" + listId, data);
        audit.record("ALLOCATION_LIST_SIGNED", "allocation-list/" + listId, data);
    }
}
