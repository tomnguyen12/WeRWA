package vn.werwa.distribution.allocation;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.distribution.DistributionError;
import vn.werwa.distribution.offering.OfferingService;
import vn.werwa.distribution.offering.OfferingService.Offering;
import vn.werwa.distribution.order.OrderService;
import vn.werwa.distribution.order.OrderService.Order;
import vn.werwa.governance.api.Approvals;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.issuance.api.IssuerDecisions;
import vn.werwa.issuance.api.SeriesCatalog;
import vn.werwa.issuance.api.SeriesCatalog.SeriesInfo;
import vn.werwa.onboarding.api.Wallets;
import vn.werwa.platform.hash.Hashes;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.security.OrgScope;
import vn.werwa.platform.security.Role;
import vn.werwa.platform.web.ApiException;

/**
 * Danh sách phân bổ (US-3.03-AC1, AC2; US-2.06-AC2; Q42, Q44, Q45). Tổ hợp đề xuất sau khi đóng sổ; mỗi lần đề xuất là một
 * phiên bản mới, bản nháp cũ chuyển SUPERSEDED. Gửi UBND thì kiểm tổng, khung lãi suất và quyết định UBND, rồi tạo yêu
 * cầu duyệt do vai ISSUER duyệt (Q40). Ký {@code setAllocation} trên chuỗi làm ở B7b.
 */
public class AllocationListService {

    public static final String SUBMIT_ACTION = "ALLOCATION_LIST_SUBMIT";
    private static final BigInteger PPM = BigInteger.valueOf(1_000_000);

    private final JdbcTemplate jdbc;
    private final OfferingService offerings;
    private final OrderService orders;
    private final SeriesCatalog series;
    private final IssuerDecisions decisions;
    private final Wallets wallets;
    private final Approvals approvals;
    private final AuditLog audit;
    private final Clock clock;

    public AllocationListService(JdbcTemplate jdbc, OfferingService offerings, OrderService orders, SeriesCatalog series,
            IssuerDecisions decisions, Wallets wallets, Approvals approvals, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.offerings = offerings;
        this.orders = orders;
        this.series = series;
        this.decisions = decisions;
        this.wallets = wallets;
        this.approvals = approvals;
        this.audit = audit;
        this.clock = clock;
    }

    public record ItemInput(UUID orderId, long quantity, String adjustmentReason) {}

    public record Item(
            UUID allocationId, UUID orderId, UUID investorOrgId, UUID walletId, String wallet, long quantity,
            long amountDueMinor, String currency, String adjustmentReason, String leaf) {}

    public record AllocationList(
            UUID id, UUID offeringId, int listVersion, int couponRatePpm, List<Integer> periodRatesPpm, long totalQuantity,
            String merkleRoot, String listHash, UUID approvalRequestId, String status, List<Item> items) {}

    public record Proof(UUID allocationId, String seriesController, String wallet, long quantity, String leaf,
            List<String> proof, String merkleRoot) {}

    /** US-3.03-AC1. Tổng và khung lãi suất kiểm khi gửi UBND (AC2). */
    @Transactional
    public AllocationList propose(UUID offeringId, int couponRatePpm, List<ItemInput> input) {
        Offering offering = lockOffering(offeringId);
        if (!"CLOSED".equals(offering.status())) {
            throw new ApiException(DistributionError.OFFERING_NOT_CLOSED, "đang " + offering.status());
        }
        Integer pending = jdbc.queryForObject("""
                SELECT count(*) FROM distribution.allocation_list WHERE offering_id = ? AND status IN ('SUBMITTED', 'SIGNED')
                """, Integer.class, offeringId);
        if (pending > 0) {
            throw new ApiException(DistributionError.LIST_ALREADY_SUBMITTED);
        }
        SeriesInfo s = series.find(offering.seriesId()).orElseThrow();
        if (s.controllerAddress() == null) {
            throw new ApiException(DistributionError.SERIES_NOT_DEPLOYED);
        }
        Map<UUID, Order> book = orders.activeOrders(offeringId);
        List<Item> items = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (ItemInput in : input) {
            if (in.quantity() == 0) {
                continue; // không phân bổ cho lệnh này
            }
            Order o = book.get(in.orderId());
            if (o == null || !seen.add(in.orderId())) {
                throw new ApiException(DistributionError.ALLOCATION_INVALID, "lệnh " + in.orderId()
                        + (o == null ? " không thuộc sổ lệnh hoặc đã hủy" : " lặp lại"));
            }
            if (in.quantity() < 0 || in.quantity() > o.quantity()) {
                throw new ApiException(DistributionError.ALLOCATION_EXCEEDS_ORDER, "lệnh " + o.id() + " đặt " + o.quantity());
            }
            boolean adjusted = in.quantity() != o.quantity() || o.ratePpm() > couponRatePpm;
            String reason = in.adjustmentReason() == null || in.adjustmentReason().isBlank() ? null : in.adjustmentReason();
            if (adjusted && reason == null) {
                throw new ApiException(DistributionError.ADJUSTMENT_REASON_REQUIRED, "lệnh " + o.id());
            }
            BigInteger gross = BigInteger.valueOf(in.quantity()).multiply(BigInteger.valueOf(s.faceValueMinor()))
                    .multiply(BigInteger.valueOf(s.issuePricePpm()));
            BigInteger[] qr = gross.divideAndRemainder(PPM);
            if (qr[1].signum() != 0) {
                throw new ApiException(DistributionError.AMOUNT_NOT_EXACT, "lệnh " + o.id() + ", số lượng " + in.quantity());
            }
            Wallets.Wallet w = wallets.registered(o.walletId())
                    .orElseThrow(() -> new ApiException(DistributionError.WALLET_NOT_REGISTERED, "lệnh " + o.id()));
            UUID allocationId = Ids.newId();
            String leaf = AllocationMerkle.hex(AllocationMerkle.leaf(s.controllerAddress(), allocationId, w.address(),
                    in.quantity()));
            items.add(new Item(allocationId, o.id(), o.investorOrgId(), o.walletId(), w.address(), in.quantity(),
                    qr[0].longValueExact(), s.currency(), reason, leaf));
        }
        if (items.isEmpty()) {
            throw new ApiException(DistributionError.ALLOCATION_INVALID, "danh sách rỗng");
        }
        // Thứ tự lá theo byte của allocationId, trùng ORDER BY id của PostgreSQL
        items.sort((a, b) -> Arrays.compareUnsigned(AllocationMerkle.uuidToBytes32(a.allocationId()),
                AllocationMerkle.uuidToBytes32(b.allocationId())));
        String root = AllocationMerkle.hex(AllocationMerkle.root(leaves(items)));
        long total = items.stream().mapToLong(Item::quantity).sum();
        List<Integer> periodRates = java.util.Collections.nCopies(s.couponPeriods(), couponRatePpm);

        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                UPDATE distribution.allocation_list SET status = 'SUPERSEDED', updated_at = ?
                WHERE offering_id = ? AND status = 'DRAFT'
                """, now, offeringId);
        int version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(list_version), 0) + 1 FROM distribution.allocation_list WHERE offering_id = ?",
                Integer.class, offeringId);
        UUID id = Ids.newId();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO distribution.allocation_list
                        (id, offering_id, list_version, coupon_rate_ppm, period_rates_ppm, total_quantity, merkle_root, status,
                         created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                    """);
            ps.setObject(1, id);
            ps.setObject(2, offeringId);
            ps.setInt(3, version);
            ps.setInt(4, couponRatePpm);
            ps.setArray(5, con.createArrayOf("integer", periodRates.toArray()));
            ps.setLong(6, total);
            ps.setString(7, root);
            ps.setString(8, CurrentUser.get().subject());
            ps.setTimestamp(9, now);
            ps.setTimestamp(10, now);
            return ps;
        });
        for (Item it : items) {
            jdbc.update("""
                    INSERT INTO distribution.allocation
                        (id, allocation_list_id, order_id, investor_org_id, wallet_id, wallet_address, quantity, amount_due_minor,
                         currency, adjustment_reason, leaf_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, it.allocationId(), id, it.orderId(), it.investorOrgId(), it.walletId(), it.wallet(), it.quantity(),
                    it.amountDueMinor(), it.currency(), it.adjustmentReason(), it.leaf());
        }
        audit.record("ALLOCATION_LIST_PROPOSED", "allocation-list/" + id, Map.of("offeringId", offeringId.toString(),
                "listVersion", version, "couponRatePpm", couponRatePpm, "totalQuantity", total, "merkleRoot", root,
                "adjusted", items.stream().filter(i -> i.adjustmentReason() != null).count()));
        return get(id);
    }

    /** US-3.03-AC2, US-2.06-AC2: gửi UBND; trả mã yêu cầu duyệt. */
    @Transactional
    public UUID submit(UUID id) {
        AllocationList list = lock(id);
        if (!"DRAFT".equals(list.status())) {
            throw new ApiException(DistributionError.LIST_NOT_DRAFT, "đang " + list.status());
        }
        Offering offering = offerings.get(list.offeringId());
        SeriesInfo s = series.find(offering.seriesId()).orElseThrow();
        if (list.totalQuantity() > s.maxQuantity()) {
            throw new ApiException(DistributionError.ALLOCATION_EXCEEDS_MAX,
                    list.totalQuantity() + " > " + s.maxQuantity());
        }
        if (list.couponRatePpm() < s.rateFloorPpm() || list.couponRatePpm() > s.rateCapPpm()) {
            throw new ApiException(DistributionError.RATE_OUT_OF_RANGE,
                    "khung Đề án " + s.rateFloorPpm() + "–" + s.rateCapPpm() + " ppm");
        }
        IssuerDecisions.Ref decision = decisions.latestCouponRate(s.id(), offering.id())
                .orElseThrow(() -> new ApiException(DistributionError.ISSUER_DECISION_REQUIRED));
        String listHash = listHash(list, decision.decisionHash());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("allocationListId", id.toString());
        payload.put("offeringId", list.offeringId().toString());
        payload.put("merkleRoot", list.merkleRoot());
        payload.put("listHash", listHash);
        payload.put("totalQuantity", Long.toString(list.totalQuantity()));
        payload.put("couponRatePpm", list.couponRatePpm());
        payload.put("issuerDecisionId", decision.id().toString());
        payload.put("issuerDecisionHash", decision.decisionHash());
        UUID approval = approvals.request(SUBMIT_ACTION, "allocation-list/" + id, payload, Role.ISSUER);
        jdbc.update("""
                UPDATE distribution.allocation_list SET status = 'SUBMITTED', list_hash = ?, issuer_decision_id = ?,
                    issuer_decision_hash = ?, approval_request_id = ?, updated_at = ?
                WHERE id = ?
                """, listHash, decision.id(), decision.decisionHash(), approval, Timestamp.from(clock.instant()), id);
        audit.record("ALLOCATION_LIST_SUBMITTED", "allocation-list/" + id, payload);
        return approval;
    }

    /** Q28, Q40: UBND duyệt thì chờ ký trên chuỗi (B7b); từ chối thì danh sách REJECTED, tổ hợp đề xuất lại. */
    @Transactional
    public void applyDecision(UUID approvalId, boolean approve, String comment) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM distribution.allocation_list WHERE approval_request_id = ? AND status = 'SUBMITTED' FOR UPDATE
                """, UUID.class, approvalId);
        if (ids.isEmpty()) {
            return;
        }
        UUID id = ids.getFirst();
        Timestamp now = Timestamp.from(clock.instant());
        if (approve) {
            jdbc.update("UPDATE distribution.allocation_list SET approved_at = ?, updated_at = ? WHERE id = ?", now, now, id);
            audit.record("ALLOCATION_LIST_APPROVED", "allocation-list/" + id, Map.of("approvalId", approvalId.toString()));
        } else {
            jdbc.update("UPDATE distribution.allocation_list SET status = 'REJECTED', updated_at = ? WHERE id = ?", now, id);
            audit.record("ALLOCATION_LIST_REJECTED", "allocation-list/" + id,
                    Map.of("approvalId", approvalId.toString(), "reason", String.valueOf(comment)));
        }
    }

    /** US-3.03-AC4: chỉ khi danh sách đã ký; nhà đầu tư chỉ lấy được phân bổ của mình. */
    public Proof proof(UUID listId, UUID allocationId) {
        AllocationList list = get(listId);
        Item item = list.items().stream().filter(i -> i.allocationId().equals(allocationId)).findFirst()
                .orElseThrow(() -> new ApiException(DistributionError.ALLOCATION_NOT_FOUND));
        OrgScope.requireOwnOrganization(item.investorOrgId());
        if (!"SIGNED".equals(list.status())) {
            throw new ApiException(DistributionError.LIST_NOT_SIGNED);
        }
        Offering offering = offerings.get(list.offeringId());
        String controller = series.find(offering.seriesId()).orElseThrow().controllerAddress();
        List<byte[]> leaves = leaves(list.items());
        int index = list.items().indexOf(item);
        return new Proof(item.allocationId(), controller, item.wallet(), item.quantity(), item.leaf(),
                AllocationMerkle.proof(leaves, index).stream().map(AllocationMerkle::hex).toList(), list.merkleRoot());
    }

    public AllocationList get(UUID id) {
        List<AllocationList> rows = jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> list(rs), id);
        if (rows.isEmpty()) {
            throw new ApiException(DistributionError.LIST_NOT_FOUND);
        }
        AllocationList l = rows.getFirst();
        List<Item> items = jdbc.query("""
                SELECT id, order_id, investor_org_id, wallet_id, wallet_address, quantity, amount_due_minor, currency,
                       adjustment_reason, leaf_hash
                FROM distribution.allocation WHERE allocation_list_id = ? ORDER BY id
                """, (rs, i) -> new Item(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getString(8),
                        rs.getString(9), rs.getString(10)), id);
        return new AllocationList(l.id(), l.offeringId(), l.listVersion(), l.couponRatePpm(), l.periodRatesPpm(),
                l.totalQuantity(), l.merkleRoot(), l.listHash(), l.approvalRequestId(), l.status(), items);
    }

    /** Q44: SHA-256 của JSON chuẩn hóa. */
    static String listHash(AllocationList list, String issuerDecisionHash) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("offeringId", list.offeringId().toString());
        m.put("listVersion", list.listVersion());
        m.put("periodRatesPpm", list.periodRatesPpm());
        m.put("issuerDecisionHash", issuerDecisionHash);
        m.put("items", list.items().stream().map(i -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("allocationId", i.allocationId().toString());
            e.put("orderId", i.orderId().toString());
            e.put("investorOrgId", i.investorOrgId().toString());
            e.put("wallet", i.wallet());
            e.put("quantity", Long.toString(i.quantity()));
            e.put("amountDueMinor", Long.toString(i.amountDueMinor()));
            e.put("leaf", i.leaf());
            e.put("adjustmentReason", i.adjustmentReason());
            return e;
        }).toList());
        return Hashes.payloadHash(m);
    }

    private static List<byte[]> leaves(List<Item> items) {
        return items.stream().map(i -> java.util.HexFormat.of().parseHex(i.leaf().substring(2))).toList();
    }

    private Offering lockOffering(UUID id) {
        jdbc.queryForList("SELECT id FROM distribution.offering WHERE id = ? FOR UPDATE", UUID.class, id);
        return offerings.get(id);
    }

    private AllocationList lock(UUID id) {
        jdbc.queryForList("SELECT id FROM distribution.allocation_list WHERE id = ? FOR UPDATE", UUID.class, id);
        return get(id);
    }

    private static final String SELECT = """
            SELECT id, offering_id, list_version, coupon_rate_ppm, period_rates_ppm, total_quantity, merkle_root, list_hash,
                   approval_request_id, status
            FROM distribution.allocation_list""";

    private static AllocationList list(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AllocationList(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getInt(4),
                Arrays.asList((Integer[]) rs.getArray(5).getArray()), rs.getLong(6), rs.getString(7), rs.getString(8),
                rs.getObject(9, UUID.class), rs.getString(10), List.of());
    }
}
