package vn.werwa.distribution;

import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.werwa.issuance.SeriesChainTest;
import vn.werwa.issuance.TestDocuments;
import vn.werwa.onchain.api.ContractCalls;

/**
 * Nền cho kiểm thử phân phối trên chuỗi thật: nhà đầu tư có KYC và ví, đặt lệnh, lập danh sách, UBND duyệt và ký (B7b).
 */
abstract class AllocationChainTest extends SeriesChainTest {

    static final String SYNDICATE = "01a15bc1-f402-7188-aa1d-f504e6afe7e1";

    @Autowired
    ContractCalls calls;

    @Autowired
    vn.werwa.distribution.payment.PaymentService paymentService;

    @Autowired
    vn.werwa.distribution.payment.PaymentBatchSigning paymentBatchSigning;

    @Autowired
    vn.werwa.onchain.api.ChainTransactions chainTransactions;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    vn.werwa.governance.api.AuditLog auditLog;

    static RequestPostProcessor user(String org, String role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", org).claim("roles", List.of(role)));
    }

    static final RequestPostProcessor SYNDICATE_USER = user(SYNDICATE, "SYNDICATE");

    /** Nhà đầu tư đã qua KYC (loại FOREIGN_INSTITUTION) và có ví đã đăng ký (B5b nạp thẳng). */
    record Investor(UUID org, UUID wallet, String address, RequestPostProcessor user) {}

    Investor investor() throws Exception {
        UUID org = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO onboarding.organization (id, legal_name, registration_no, country, roles, kyb_status, created_at, updated_at)
                VALUES (?, ?, ?, 'SG', ARRAY['INVESTOR'], 'KYB_APPROVED', now(), now())
                """, org, "Nhà đầu tư thử " + org, "A-" + org);
        MvcResult kyc = post("/v1/kyc-cases", SYNDICATE_USER, Map.of("organizationId", org.toString()));
        status(kyc, 201);
        status(post("/v1/kyc-cases/" + body(kyc).get("id") + "/decision", as("holco"),
                Map.of("decision", "APPROVE", "investorClass", "FOREIGN_INSTITUTION")), 200);
        UUID wallet = UUID.randomUUID();
        byte[] raw = new byte[20];
        RANDOM.nextBytes(raw);
        String address = "0x" + HexFormat.of().formatHex(raw);
        jdbc.update("""
                INSERT INTO onboarding.wallet (id, organization_id, chain_id, address, wallet_type, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'SELF', 'REGISTERED', now(), now())
                """, wallet, org, CHAIN_ID, address);
        return new Investor(org, wallet, address, user(org.toString(), "INVESTOR"));
    }

    UUID order(UUID offering, Investor who, long quantity) throws Exception {
        MvcResult r = post("/v1/offerings/" + offering + "/orders", who.user(), Map.of("investorOrgId", who.org().toString(),
                "walletId", who.wallet().toString(), "ratePpm", 50_000, "quantity", quantity));
        status(r, 201);
        return id(r);
    }

    static Map<String, Object> item(UUID order, long quantity, String reason) {
        Map<String, Object> m = new HashMap<>();
        m.put("orderId", order.toString());
        m.put("quantity", quantity);
        if (reason != null) {
            m.put("adjustmentReason", reason);
        }
        return m;
    }

    String orderStatus(UUID order) {
        return jdbc.queryForObject("SELECT status FROM distribution.\"order\" WHERE id = ?", String.class, order);
    }

    /** Mỗi nhà đầu tư: số đặt và số được phân bổ (0 = không vào danh sách). */
    record Want(Investor investor, long ordered, long allocated) {}

    record Prepared(UUID series, UUID offering, UUID list, Map<Investor, UUID> orders) {}

    /** Series Sẵn sàng, đợt dựng sổ, lệnh, danh sách gửi UBND và UBND duyệt đạt (chưa ký trên chuỗi). */
    Prepared approvedList(List<Want> wants) throws Exception {
        UUID series = readySeries();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        MvcResult created = post("/v1/offerings", SYNDICATE_USER, Map.of("seriesId", series.toString(), "method",
                "BOOKBUILDING", "rateGuidanceLowPpm", 40_000, "rateGuidanceHighPpm", 60_000, "targetQuantity", 1000,
                "bookOpenAt", now.minus(1, ChronoUnit.HOURS).toString(), "bookCloseAt", now.plus(1, ChronoUnit.DAYS).toString()));
        status(created, 201);
        UUID offering = id(created);
        status(post("/v1/offerings/" + offering + "/open", SYNDICATE_USER, null), 200);
        Map<Investor, UUID> orders = new LinkedHashMap<>();
        for (Want w : wants) {
            orders.put(w.investor(), order(offering, w.investor(), w.ordered()));
        }
        status(post("/v1/offerings/" + offering + "/close", SYNDICATE_USER, null), 200);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Want w : wants) {
            if (w.allocated() > 0) {
                items.add(item(orders.get(w.investor()), w.allocated(),
                        w.allocated() < w.ordered() ? "giới hạn khối lượng" : null));
            }
        }
        MvcResult proposed = post("/v1/offerings/" + offering + "/allocation-lists", SYNDICATE_USER,
                Map.of("couponRatePpm", 50_000, "items", items));
        status(proposed, 201);
        UUID list = id(proposed);
        RequestPostProcessor issuer = as("ubnd");
        status(post("/v1/issuer-decisions", issuer, Map.of("decisionNo", "QD-LS-" + UUID.randomUUID(), "decidedOn",
                "2026-10-27", "subject", "COUPON_RATE", "offeringId", offering.toString(), "documentId",
                TestDocuments.uploadId(mvc, issuer, "ISSUER_DECISION").toString())), 201);
        MvcResult submitted = post("/v1/allocation-lists/" + list + "/submit", SYNDICATE_USER, null);
        status(submitted, 202);
        approve(UUID.fromString((String) body(submitted).get("approvalRequestId")), as("ubnd"));
        return new Prepared(series, offering, list, orders);
    }

    /** Safe UBND ký đề nghị tự tạo; chờ indexer đưa danh sách sang SIGNED. */
    void signList(UUID list) throws Exception {
        await().atMost(WAIT).until(() -> jdbc.queryForObject(
                "SELECT signing_proposal_id FROM distribution.allocation_list WHERE id = ?", UUID.class, list) != null);
        signBy(jdbc.queryForObject("SELECT signing_proposal_id FROM distribution.allocation_list WHERE id = ?",
                UUID.class, list), "ubnd");
        await().atMost(WAIT).until(() -> "SIGNED".equals(jdbc.queryForObject(
                "SELECT status FROM distribution.allocation_list WHERE id = ?", String.class, list)));
    }

    /** Phân bổ của lệnh trong danh sách. */
    Map<String, Object> allocationOf(UUID list, UUID order) {
        return jdbc.queryForMap("""
                SELECT id, status, quantity, amount_due_minor, currency, virtual_account, wallet_address,
                       mint_failure_reason, minted_tx_hash
                FROM distribution.allocation WHERE allocation_list_id = ? AND order_id = ?
                """, list, order);
    }

    /** Báo có khớp đủ tiền cho phân bổ (đường đối chiếu của B8a, bỏ qua webhook). */
    void payFor(Map<String, Object> allocation) {
        String result = tx.execute(st -> paymentService.match(new vn.werwa.distribution.payment.PaymentService.Credit(
                "FT" + UUID.randomUUID(), (String) allocation.get("virtual_account"),
                ((Number) allocation.get("amount_due_minor")).longValue(), String.valueOf(allocation.get("currency")).trim(),
                java.time.LocalDate.of(2027, 2, 25), "Payer", UUID.randomUUID())));
        if (!"MATCHED".equals(result)) {
            throw new IllegalStateException("báo có không khớp: " + result);
        }
    }

    record Minted(Prepared prepared, Investor a, Investor b, Map<String, Object> allocA, Map<String, Object> allocB) {}

    /**
     * Series Đang ghi sổ: A được 600, B được 300, đều có định danh và đã trả đủ; BIDV ký lô; cả hai mục đã mint (B8b). Không
     * còn mục chờ tiền.
     */
    Minted mintedSeries() throws Exception {
        Investor a = investor();
        Investor b = investor();
        Prepared p = approvedList(List.of(new Want(a, 600, 600), new Want(b, 500, 300)));
        signList(p.list());
        vn.werwa.onchain.TestIdentities.onboard(chainTransactions, calls, tx, a.address(), 1);
        vn.werwa.onchain.TestIdentities.onboard(chainTransactions, calls, tx, b.address(), 1);
        Map<String, Object> allocA = allocationOf(p.list(), p.orders().get(a));
        Map<String, Object> allocB = allocationOf(p.list(), p.orders().get(b));
        payFor(allocA);
        payFor(allocB);
        List<UUID> batches = paymentService.buildBatches();
        DistributionConfig.proposeBatches(batches, paymentBatchSigning, DistributionConfig.separate(transactionManager),
                auditLog);
        UUID proposal = jdbc.queryForObject("SELECT signing_proposal_id FROM distribution.payment_batch WHERE id = ?",
                UUID.class, batches.getFirst());
        signBy(proposal, "bidv");
        await().atMost(WAIT).until(() -> jdbc.queryForObject("""
                SELECT count(*) FROM distribution.allocation WHERE allocation_list_id = ? AND status = 'MINTED'
                """, Integer.class, p.list()) == 2);
        return new Minted(p, a, b, allocA, allocB);
    }

    /** Series Đang lưu hành: {@link #mintedSeries()} rồi VSDC ký xác nhận sổ (B9d). */
    Minted activeSeries() throws Exception {
        Minted m = mintedSeries();
        UUID series = m.prepared().series();
        await().atMost(WAIT).until(() -> jdbc.queryForList("""
                SELECT signing_proposal_id FROM distribution.register_attestation WHERE series_id = ? AND status = 'SIGNING'
                """, UUID.class, series).size() == 1);
        signBy(jdbc.queryForObject("""
                SELECT signing_proposal_id FROM distribution.register_attestation WHERE series_id = ? AND status = 'SIGNING'
                """, UUID.class, series), "vsdc");
        await().atMost(WAIT).until(() -> "ACTIVE".equals(series(series).get("status")));
        return m;
    }

    /** Holco tiếp nhận lệnh và một người duyệt khác của Holco duyệt đạt. Trả id lệnh. */
    UUID approvedOrder(String action, UUID series, Investor from, Investor to, long qty) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("authorityName", "Tòa án nhân dân TP.HCM (thử)");
        b.put("orderNo", "QĐ-PT-" + UUID.randomUUID());
        b.put("issuedOn", "2026-10-20");
        b.put("basisDocumentId", TestDocuments.uploadId(mvc, as("holco"), "ENFORCEMENT_BASIS").toString());
        b.put("action", action);
        b.put("seriesId", series.toString());
        b.put("walletId", from.wallet().toString());
        if (to != null) {
            b.put("toWalletId", to.wallet().toString());
        }
        b.put("quantity", qty);
        MvcResult r = post("/v1/enforcement-orders", as("holco"), b);
        status(r, 201);
        approve(UUID.fromString((String) body(r).get("approvalRequestId")), as("holco"));
        return id(r);
    }

    /** Phong tỏa {@code qty} của ví {@code who} qua lệnh đã duyệt và Safe 2/2 (B10b); chờ lệnh APPLIED. */
    UUID frozen(UUID series, Investor who, long qty) throws Exception {
        UUID order = approvedOrder("FREEZE", series, who, null, qty);
        await().atMost(WAIT).until(() -> jdbc.queryForObject(
                "SELECT signing_proposal_id FROM governance.enforcement_order WHERE id = ?", UUID.class, order) != null);
        signBy(jdbc.queryForObject("SELECT signing_proposal_id FROM governance.enforcement_order WHERE id = ?", UUID.class,
                order), "vsdc", "holco");
        await().atMost(WAIT).until(() -> "APPLIED".equals(jdbc.queryForObject(
                "SELECT status FROM governance.enforcement_order WHERE id = ?", String.class, order)));
        return order;
    }
}
