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
}
