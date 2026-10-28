package vn.werwa.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.IntegrationTest;
import vn.werwa.issuance.TestDocuments;
import vn.werwa.platform.OpenApiSchemas;
import vn.werwa.platform.hash.Hashes;

/** Tiếp nhận và duyệt lệnh phong tỏa (US-4.02-AC1, AC4; máy Phong tỏa cạnh 1–3; Q64–Q67). */
class EnforcementIT extends IntegrationTest {

    static final String HOLCO = "01a15bc1-f409-7589-aef1-774ceddccf3f";
    static final String AGENCY = "01a15bc1-f401-70b2-9cfa-eb63c4f24ecc";
    static final String VSDC = "01a15bc1-f403-7f0b-8fb9-a75cdc4b5b00";
    static final String SYNDICATE = "01a15bc1-f402-7188-aa1d-f504e6afe7e1";
    static final String INVESTOR_A = "01a15bc1-f40a-7520-aeb5-19bb9d2faaa5";
    static final String INVESTOR_B = "01a15bc1-f40b-7c54-aa64-c99f087b0103";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Random RANDOM = new Random();

    static RequestPostProcessor as(String subject, String org, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("org_id", org).claim("roles", List.of(role)));
    }

    static final RequestPostProcessor MAKER = as("ab1f0000-0000-4000-8000-000000000001", HOLCO, "OPERATOR");
    static final RequestPostProcessor CHECKER = as("ab1f0000-0000-4000-8000-000000000002", HOLCO, "OPERATOR");
    static final RequestPostProcessor REGISTRAR = as("ab1f0000-0000-4000-8000-000000000003", VSDC, "REGISTRAR");
    static final RequestPostProcessor AGENCY_MAKER = as("ab1f0000-0000-4000-8000-0000000000a1", AGENCY, "OPERATING_AGENCY");
    static final RequestPostProcessor AGENCY_CHECKER = as("ab1f0000-0000-4000-8000-0000000000a2", AGENCY, "OPERATING_AGENCY");
    static final RequestPostProcessor SYNDICATE_USER = as("ab1f0000-0000-4000-8000-0000000000e1", SYNDICATE, "SYNDICATE");
    static final RequestPostProcessor INVESTOR = as("ab1f0000-0000-4000-8000-0000000000d1", INVESTOR_A, "INVESTOR");

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    MvcResult post(String path, RequestPostProcessor who, Object body) throws Exception {
        var request = MockMvcRequestBuilders.post(path).with(who).header("Idempotency-Key", "k-" + UUID.randomUUID());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request).andReturn();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(MvcResult r) throws Exception {
        return JSON.readValue(r.getResponse().getContentAsString(), Map.class);
    }

    static void ok(MvcResult r, int status, String schema) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
        if (schema != null) {
            assertThat(OpenApiSchemas.validate(schema, r.getResponse().getContentAsString())).isEmpty();
        }
    }

    static void problem(MvcResult r, int status, String code) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(body(r).get("code")).isEqualTo(code);
    }

    record Wallet(UUID id, String address) {}

    Wallet wallet(String org) {
        UUID id = UUID.randomUUID();
        String address = "0x" + HexFormat.of().formatHex(ByteBuffer.allocate(20).putLong(RANDOM.nextLong())
                .putLong(RANDOM.nextLong()).putInt(RANDOM.nextInt()).array());
        jdbc.update("""
                INSERT INTO onboarding.wallet (id, organization_id, chain_id, address, wallet_type, status, created_at, updated_at)
                VALUES (?, ?, 20261, ?, 'SELF', 'REGISTERED', now(), now())
                """, id, UUID.fromString(org), address);
        return new Wallet(id, address);
    }

    /** Series đã triển khai (phần B6c nạp thẳng). */
    UUID deployedSeries() throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("title", "Đề án " + UUID.randomUUID());
        p.put("hdndResolutionNo", "NQ-" + UUID.randomUUID());
        p.put("approvedOn", "2026-09-30");
        p.put("currency", "USD");
        p.put("maxPrincipal", Map.of("amountMinor", "100000000000", "currency", "USD"));
        p.put("rateFloorPpm", 30_000);
        p.put("rateCapPpm", 70_000);
        p.put("costCap", Map.of("amountMinor", "100000000", "currency", "USD"));
        p.put("eligibleInvestorClasses", List.of("FOREIGN_INSTITUTION"));
        p.put("documentId", TestDocuments.uploadId(mvc, MAKER, "HDND_RESOLUTION").toString());
        MvcResult program = post("/v1/programs", AGENCY_MAKER, p);
        ok(program, 201, null);
        UUID programId = UUID.fromString((String) body(program).get("id"));
        UUID approval = jdbc.queryForObject("SELECT approval_request_id FROM issuance.issuance_program WHERE id = ?",
                UUID.class, programId);
        ok(post("/v1/approvals/" + approval + "/decision", AGENCY_CHECKER, Map.of("decision", "APPROVE")), 200, null);
        Map<String, Object> s = new HashMap<>();
        s.put("programId", programId.toString());
        s.put("seriesCode", "S-" + UUID.randomUUID());
        s.put("currency", "USD");
        s.put("faceValue", Map.of("amountMinor", "100000", "currency", "USD"));
        s.put("issuePricePpm", 1_000_000);
        s.put("maxQuantity", 1000);
        s.put("couponFrequency", "SEMI_ANNUAL");
        s.put("dayCount", "30_360");
        s.put("issueDate", "2027-03-02");
        s.put("maturityDate", "2030-03-02");
        s.put("termsDocumentId", TestDocuments.uploadId(mvc, MAKER, "TERM_SHEET").toString());
        MvcResult series = post("/v1/series", MAKER, s);
        ok(series, 201, null);
        UUID id = UUID.fromString((String) body(series).get("id"));
        jdbc.update("UPDATE issuance.series SET status = 'ACTIVE' WHERE id = ?", id);
        return id;
    }

    Map<String, Object> order(String action, UUID series, Wallet wallet, Wallet to, long qty, UUID doc) {
        Map<String, Object> b = new HashMap<>();
        b.put("authorityName", "Tòa án nhân dân TP.HCM (thử)");
        b.put("orderNo", "QĐ-PT-" + UUID.randomUUID());
        b.put("issuedOn", "2026-10-20");
        b.put("basisDocumentId", doc.toString());
        b.put("action", action);
        b.put("seriesId", series.toString());
        b.put("walletId", wallet.id().toString());
        if (to != null) {
            b.put("toWalletId", to.id().toString());
        }
        b.put("quantity", qty);
        return b;
    }

    @Test
    @DisplayName("US-4.02-AC1 tiếp nhận lệnh: ghi cơ quan, số lệnh, ví, số lượng, tài liệu căn cứ và mã băm")
    void US_4_02_AC1_orderIsRecordedWithItsBasisHash() throws Exception {
        UUID series = deployedSeries();
        Wallet w = wallet(INVESTOR_A);
        UUID doc = TestDocuments.uploadId(mvc, MAKER, "ENFORCEMENT_BASIS");
        Map<String, Object> b = order("FREEZE", series, w, null, 40, doc);
        MvcResult r = post("/v1/enforcement-orders", MAKER, b);
        ok(r, 201, "EnforcementOrder");
        Map<String, Object> o = body(r);
        assertThat(o.get("status")).isEqualTo("PENDING_APPROVAL");
        assertThat(o.get("approvalRequestId")).isNotNull();

        // Q65: mã băm tính lại được từ nội dung lệnh và SHA-256 văn bản căn cứ
        String docSha = jdbc.queryForObject("SELECT sha256 FROM issuance.document WHERE id = ?", String.class, doc);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("authorityName", b.get("authorityName"));
        m.put("orderNo", b.get("orderNo"));
        m.put("issuedOn", "2026-10-20");
        m.put("action", "FREEZE");
        m.put("seriesId", series.toString());
        m.put("wallet", w.address());
        m.put("toWallet", null);
        m.put("quantity", "40");
        m.put("basisDocumentSha256", docSha);
        assertThat(o.get("basisHash")).isEqualTo(Hashes.payloadHash(m));

        problem(post("/v1/enforcement-orders", MAKER, b), 409, "ENFORCEMENT_ORDER_EXISTS");
        MvcResult read = mvc.perform(get("/v1/enforcement-orders/" + o.get("id")).with(REGISTRAR)).andReturn();
        ok(read, 200, "EnforcementOrder");
        assertThat(mvc.perform(get("/v1/enforcement-orders").with(INVESTOR)).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        MvcResult list = mvc.perform(get("/v1/enforcement-orders").with(REGISTRAR)).andReturn();
        ok(list, 200, null);
        assertThat(list.getResponse().getContentAsString()).contains((String) o.get("id"));
    }

    @Test
    @DisplayName("Q66 kiểm khi tiếp nhận: ví, ví nhận, series, tài liệu căn cứ")
    void receivingChecksWalletsSeriesAndBasis() throws Exception {
        UUID series = deployedSeries();
        Wallet w = wallet(INVESTOR_A);
        UUID doc = TestDocuments.uploadId(mvc, MAKER, "ENFORCEMENT_BASIS");
        problem(post("/v1/enforcement-orders", MAKER, order("FREEZE", series, new Wallet(UUID.randomUUID(), "0x0"), null, 1,
                doc)), 422, "ENFORCEMENT_WALLET_INVALID");
        problem(post("/v1/enforcement-orders", MAKER, order("FREEZE", series, w, null, 1, UUID.randomUUID())), 422,
                "DOCUMENT_NOT_FOUND");
        problem(post("/v1/enforcement-orders", MAKER, order("FREEZE", UUID.randomUUID(), w, null, 1, doc)), 422,
                "ENFORCEMENT_SERIES_INVALID");
        problem(post("/v1/enforcement-orders", MAKER, order("FORCED_TRANSFER", series, w, null, 1, doc)), 422,
                "ENFORCEMENT_INVALID");
        problem(post("/v1/enforcement-orders", MAKER, order("FREEZE", series, w, wallet(INVESTOR_B), 1, doc)), 422,
                "ENFORCEMENT_INVALID");
        // Chủ ví nhận chưa có KYC đạt
        problem(post("/v1/enforcement-orders", MAKER, order("FORCED_TRANSFER", series, w, wallet(INVESTOR_B), 1, doc)), 422,
                "ENFORCEMENT_WALLET_INVALID");
        jdbc.update("UPDATE issuance.series SET status = 'CANCELLED' WHERE id = ?", series);
        problem(post("/v1/enforcement-orders", MAKER, order("FREEZE", series, w, null, 1, doc)), 422,
                "ENFORCEMENT_SERIES_INVALID");
    }

    @Test
    @DisplayName("US-4.02-AC4 giải tỏa hoặc chuyển cưỡng chế là lệnh mới, đi lại đủ các bước; trả lại có lý do")
    void US_4_02_AC4_releaseIsANewOrderThroughTheSameSteps() throws Exception {
        UUID series = deployedSeries();
        Wallet w = wallet(INVESTOR_A);
        UUID doc = TestDocuments.uploadId(mvc, MAKER, "ENFORCEMENT_BASIS");

        Map<String, Object> freeze = body(post("/v1/enforcement-orders", MAKER, order("FREEZE", series, w, null, 40, doc)));
        UUID freezeApproval = UUID.fromString((String) freeze.get("approvalRequestId"));
        problem(post("/v1/approvals/" + freezeApproval + "/decision", MAKER, Map.of("decision", "APPROVE")), 403,
                "MAKER_CHECKER_SAME_USER");
        ok(post("/v1/approvals/" + freezeApproval + "/decision", CHECKER, Map.of("decision", "APPROVE")), 200, null);
        assertThat(body(mvc.perform(get("/v1/enforcement-orders/" + freeze.get("id")).with(MAKER)).andReturn())
                .get("status")).isEqualTo("PENDING_SIGNATURES");

        // Giải tỏa: lệnh mới, có yêu cầu duyệt riêng; người duyệt trả lại vì thiếu căn cứ
        Map<String, Object> unfreeze = body(post("/v1/enforcement-orders", MAKER,
                order("UNFREEZE", series, w, null, 40, doc)));
        assertThat(unfreeze.get("id")).isNotEqualTo(freeze.get("id"));
        assertThat(unfreeze.get("approvalRequestId")).isNotEqualTo(freeze.get("approvalRequestId"));
        ok(post("/v1/approvals/" + unfreeze.get("approvalRequestId") + "/decision", CHECKER,
                Map.of("decision", "REJECT", "comment", "Thiếu quyết định giải tỏa có hiệu lực")), 200, null);
        assertThat(body(mvc.perform(get("/v1/enforcement-orders/" + unfreeze.get("id")).with(MAKER)).andReturn())
                .get("status")).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT reject_reason FROM governance.enforcement_order WHERE id = ?::uuid",
                String.class, unfreeze.get("id"))).isEqualTo("Thiếu quyết định giải tỏa có hiệu lực");

        // Chuyển cưỡng chế sang ví của nhà đầu tư có KYC đạt
        MvcResult kyc = post("/v1/kyc-cases", SYNDICATE_USER, Map.of("organizationId", INVESTOR_A));
        ok(kyc, 201, null);
        ok(post("/v1/kyc-cases/" + body(kyc).get("id") + "/decision", CHECKER,
                Map.of("decision", "APPROVE", "investorClass", "FOREIGN_INSTITUTION")), 200, null);
        MvcResult transfer = post("/v1/enforcement-orders", MAKER,
                order("FORCED_TRANSFER", series, w, wallet(INVESTOR_A), 10, doc));
        ok(transfer, 201, "EnforcementOrder");
        assertThat(body(transfer).get("status")).isEqualTo("PENDING_APPROVAL");
    }
}
