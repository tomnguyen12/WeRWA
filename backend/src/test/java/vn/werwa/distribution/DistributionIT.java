package vn.werwa.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.IntegrationTest;
import vn.werwa.distribution.allocation.AllocationMerkle;
import vn.werwa.distribution.offering.OfferingService;
import vn.werwa.issuance.TestDocuments;
import vn.werwa.platform.OpenApiSchemas;
import vn.werwa.platform.events.EventSchemas;

/** Đợt chào bán, sổ lệnh, danh sách phân bổ (US-3.01, 3.02, 3.03-AC1/AC2/AC4, 2.06-AC2) với PostgreSQL và NATS thật. */
class DistributionIT extends IntegrationTest {

    static final String UBND = "01a15bc1-f400-7c28-a7a8-d5d4cc19328b";
    static final String AGENCY = "01a15bc1-f401-70b2-9cfa-eb63c4f24ecc";
    static final String SYNDICATE = "01a15bc1-f402-7188-aa1d-f504e6afe7e1";
    static final String HOLCO = "01a15bc1-f409-7589-aef1-774ceddccf3f";
    static final String INVESTOR_A = "01a15bc1-f40a-7520-aeb5-19bb9d2faaa5";
    static final String INVESTOR_B = "01a15bc1-f40b-7c54-aa64-c99f087b0103";
    static final String CONTROLLER = "0x00000000000000000000000000000000000c0de1";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Random RANDOM = new Random();

    static RequestPostProcessor as(String subject, String org, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("org_id", org).claim("roles", List.of(role)));
    }

    static final RequestPostProcessor AGENCY_MAKER = as("7d1f0000-0000-4000-8000-0000000000a1", AGENCY, "OPERATING_AGENCY");
    static final RequestPostProcessor AGENCY_CHECKER = as("7d1f0000-0000-4000-8000-0000000000a2", AGENCY, "OPERATING_AGENCY");
    static final RequestPostProcessor HOLCO_MAKER = as("7d1f0000-0000-4000-8000-0000000000b1", HOLCO, "OPERATOR");
    static final RequestPostProcessor HOLCO_CHECKER = as("7d1f0000-0000-4000-8000-0000000000b2", HOLCO, "OPERATOR");
    static final RequestPostProcessor ISSUER_CHECKER = as("7d1f0000-0000-4000-8000-0000000000c1", UBND, "ISSUER");
    static final RequestPostProcessor SYNDICATE_USER = as("7d1f0000-0000-4000-8000-0000000000e1", SYNDICATE, "SYNDICATE");
    static final RequestPostProcessor SYNDICATE_OTHER = as("7d1f0000-0000-4000-8000-0000000000e2", SYNDICATE, "SYNDICATE");
    static final RequestPostProcessor INV_A = as("7d1f0000-0000-4000-8000-0000000000d1", INVESTOR_A, "INVESTOR");
    static final RequestPostProcessor INV_B = as("7d1f0000-0000-4000-8000-0000000000d2", INVESTOR_B, "INVESTOR");

    static volatile boolean investorAReady;
    static UUID walletA;
    static UUID walletB;

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OfferingService offeringService;

    @Autowired
    io.nats.client.Connection nats;

    MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        synchronized (DistributionIT.class) {
            if (!investorAReady) {
                // KYC đạt cho nhà đầu tư A (FOREIGN_INSTITUTION); B không có KYC. Ví nạp sẵn (Q41: đăng ký ví ở B5b).
                MvcResult kyc = post("/v1/kyc-cases", SYNDICATE_USER, Map.of("organizationId", INVESTOR_A));
                assertThat(kyc.getResponse().getStatus()).as(kyc.getResponse().getContentAsString()).isEqualTo(201);
                MvcResult decided = post("/v1/kyc-cases/" + body(kyc).get("id") + "/decision", HOLCO_CHECKER,
                        Map.of("decision", "APPROVE", "investorClass", "FOREIGN_INSTITUTION"));
                assertThat(decided.getResponse().getStatus()).as(decided.getResponse().getContentAsString()).isEqualTo(200);
                walletA = wallet(INVESTOR_A);
                walletB = wallet(INVESTOR_B);
                investorAReady = true;
            }
        }
    }

    UUID wallet(String org) {
        UUID id = UUID.randomUUID();
        String address = "0x" + HexFormat.of().formatHex(java.nio.ByteBuffer.allocate(20).putLong(RANDOM.nextLong())
                .putLong(RANDOM.nextLong()).putInt(RANDOM.nextInt()).array());
        jdbc.update("""
                INSERT INTO onboarding.wallet (id, organization_id, chain_id, address, wallet_type, status, created_at, updated_at)
                VALUES (?, ?, 20261, ?, 'SELF', 'REGISTERED', now(), now())
                """, id, UUID.fromString(org), address);
        return id;
    }

    // ---------- tiện ích ----------

    MvcResult send(MockHttpServletRequestBuilder request, RequestPostProcessor who, Object body) throws Exception {
        request.with(who).header("Idempotency-Key", "k-" + UUID.randomUUID());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request).andReturn();
    }

    MvcResult post(String path, RequestPostProcessor who, Object body) throws Exception {
        return send(MockMvcRequestBuilders.post(path), who, body);
    }

    MvcResult read(String path, RequestPostProcessor who) throws Exception {
        return mvc.perform(get(path).with(who)).andReturn();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(MvcResult r) throws Exception {
        return JSON.readValue(r.getResponse().getContentAsString(), Map.class);
    }

    static UUID id(MvcResult r) throws Exception {
        return UUID.fromString((String) body(r).get("id"));
    }

    static void ok(MvcResult r, int status, String schema) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
        if (schema != null) {
            assertThat(OpenApiSchemas.validate(schema, r.getResponse().getContentAsString())).isEmpty();
        }
    }

    static void problem(MvcResult r, int status, String code) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(OpenApiSchemas.validate("Problem", r.getResponse().getContentAsString())).isEmpty();
        assertThat(body(r).get("code")).isEqualTo(code);
    }

    UUID doc(String type) throws Exception {
        return TestDocuments.uploadId(mvc, HOLCO_MAKER, type);
    }

    UUID approve(String table, UUID id, RequestPostProcessor checker) throws Exception {
        UUID approval = jdbc.queryForObject("SELECT approval_request_id FROM " + table + " WHERE id = ?", UUID.class, id);
        ok(post("/v1/approvals/" + approval + "/decision", checker, Map.of("decision", "APPROVE")), 200, null);
        return approval;
    }

    /** Series đã triển khai và có ISIN (phần của B6c, nạp thẳng vào DB). */
    UUID readySeries(Map<String, Object> overrides) throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("title", "Đề án " + UUID.randomUUID());
        p.put("hdndResolutionNo", "NQ-" + UUID.randomUUID());
        p.put("approvedOn", "2026-09-30");
        p.put("currency", "USD");
        p.put("maxPrincipal", Map.of("amountMinor", "100000000000", "currency", "USD"));
        p.put("rateFloorPpm", 30_000);
        p.put("rateCapPpm", 70_000);
        p.put("costCap", Map.of("amountMinor", "100000000", "currency", "USD"));
        p.put("eligibleInvestorClasses", List.of("FOREIGN_INSTITUTION", "VIFC_MEMBER"));
        p.put("documentId", doc("HDND_RESOLUTION").toString());
        MvcResult program = post("/v1/programs", AGENCY_MAKER, p);
        ok(program, 201, null);
        approve("issuance.issuance_program", id(program), AGENCY_CHECKER);

        Map<String, Object> s = new HashMap<>();
        s.put("programId", id(program).toString());
        s.put("seriesCode", "S-" + UUID.randomUUID());
        s.put("currency", "USD");
        s.put("faceValue", Map.of("amountMinor", "100000", "currency", "USD"));
        s.put("issuePricePpm", 1_000_000);
        s.put("maxQuantity", 1000);
        s.put("couponFrequency", "SEMI_ANNUAL");
        s.put("dayCount", "30_360");
        s.put("issueDate", "2027-03-02");
        s.put("maturityDate", "2030-03-02");
        s.put("termsDocumentId", doc("TERM_SHEET").toString());
        s.putAll(overrides);
        MvcResult series = post("/v1/series", HOLCO_MAKER, s);
        ok(series, 201, null);
        UUID id = id(series);
        jdbc.update("UPDATE issuance.series SET status = 'READY', isin = ?, controller_address = ? WHERE id = ?", isin(),
                CONTROLLER, id);
        return id;
    }

    static String isin() {
        StringBuilder b = new StringBuilder("VN");
        String alnum = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < 9; i++) {
            b.append(alnum.charAt(RANDOM.nextInt(alnum.length())));
        }
        return b.append(RANDOM.nextInt(10)).toString();
    }

    Map<String, Object> offeringBody(UUID series) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> b = new HashMap<>();
        b.put("seriesId", series.toString());
        b.put("method", "BOOKBUILDING");
        b.put("rateGuidanceLowPpm", 40_000);
        b.put("rateGuidanceHighPpm", 60_000);
        b.put("targetQuantity", 1000);
        b.put("bookOpenAt", now.minus(1, ChronoUnit.HOURS).toString());
        b.put("bookCloseAt", now.plus(1, ChronoUnit.DAYS).toString());
        return b;
    }

    UUID openOffering(UUID series) throws Exception {
        MvcResult created = post("/v1/offerings", SYNDICATE_USER, offeringBody(series));
        ok(created, 201, "Offering");
        UUID offering = id(created);
        ok(post("/v1/offerings/" + offering + "/open", SYNDICATE_USER, null), 200, "Offering");
        return offering;
    }

    MvcResult place(UUID offering, RequestPostProcessor who, String investor, UUID wallet, int rate, long qty)
            throws Exception {
        return post("/v1/offerings/" + offering + "/orders", who, Map.of("investorOrgId", investor,
                "walletId", wallet.toString(), "ratePpm", rate, "quantity", qty));
    }

    UUID order(UUID offering, int rate, long qty) throws Exception {
        MvcResult r = place(offering, INV_A, INVESTOR_A, walletA, rate, qty);
        ok(r, 201, "Order");
        return id(r);
    }

    String seriesStatus(UUID series) throws Exception {
        return (String) body(read("/v1/series/" + series, INV_A)).get("status");
    }

    static Map<String, Object> item(UUID order, long qty, String reason) {
        Map<String, Object> m = new HashMap<>();
        m.put("orderId", order.toString());
        m.put("quantity", qty);
        if (reason != null) {
            m.put("adjustmentReason", reason);
        }
        return m;
    }

    MvcResult propose(UUID offering, int rate, List<Map<String, Object>> items) throws Exception {
        return post("/v1/offerings/" + offering + "/allocation-lists", SYNDICATE_USER,
                Map.of("couponRatePpm", rate, "items", items));
    }

    void couponDecision(UUID offering) throws Exception {
        ok(post("/v1/issuer-decisions", ISSUER_CHECKER, Map.of("decisionNo", "QD-LS-" + UUID.randomUUID(),
                "decidedOn", "2026-10-27", "subject", "COUPON_RATE", "offeringId", offering.toString(),
                "documentId", doc("ISSUER_DECISION").toString())), 201, null);
    }

    // ---------- US-3.01 ----------

    @Test
    @DisplayName("US-3.01-AC3 series chưa có ISIN thì không tạo được đợt chào bán")
    void US_3_01_AC3_seriesWithoutIsinIsRejected() throws Exception {
        UUID series = readySeries(Map.of());
        jdbc.update("UPDATE issuance.series SET status = 'DEPLOYED', isin = NULL WHERE id = ?", series);
        problem(post("/v1/offerings", SYNDICATE_USER, offeringBody(series)), 409, "SERIES_ISIN_REQUIRED");
    }

    @Test
    @DisplayName("US-3.01-AC1 khoảng lãi suất chỉ dẫn phải nằm trong khung Đề án")
    void US_3_01_AC1_guidanceMustBeInsideTheProgramFrame() throws Exception {
        UUID series = readySeries(Map.of());
        Map<String, Object> wide = offeringBody(series);
        wide.put("rateGuidanceHighPpm", 80_000);
        problem(post("/v1/offerings", SYNDICATE_USER, wide), 422, "RATE_OUT_OF_RANGE");

        MvcResult r = post("/v1/offerings", ISSUER_CHECKER, offeringBody(series));
        ok(r, 201, "Offering");
        assertThat(body(r).get("status")).isEqualTo("DRAFT");
        problem(post("/v1/offerings", SYNDICATE_USER, offeringBody(series)), 409, "OFFERING_EXISTS");
    }

    @Test
    @DisplayName("US-3.01-AC2 tới giờ mở sổ thì sổ mở và series ngoài chuỗi sang Đang chào bán")
    void US_3_01_AC2_openingTheBookMovesTheSeriesToOffering() throws Exception {
        UUID series = readySeries(Map.of());
        MvcResult created = post("/v1/offerings", SYNDICATE_USER, offeringBody(series));
        UUID offering = id(created);
        // Bộ lập lịch (Q46) thấy đợt đã tới giờ mở
        assertThat(offeringService.dueToOpen(Instant.now())).contains(offering);
        offeringService.open(offering);
        assertThat(body(read("/v1/offerings/" + offering, INV_A)).get("status")).isEqualTo("OPEN");
        assertThat(seriesStatus(series)).isEqualTo("OFFERING");
        assertThat(offeringService.dueToClose(Instant.now().plus(2, ChronoUnit.DAYS))).contains(offering);
    }

    // ---------- US-3.02 ----------

    @Test
    @DisplayName("US-3.02-AC1 đặt lệnh kiểm KYC, loại nhà đầu tư, ví, lãi suất chỉ dẫn, khối lượng")
    void US_3_02_AC1_placingAnOrderChecksTheInvestor() throws Exception {
        UUID offering = openOffering(readySeries(Map.of()));
        problem(place(offering, INV_A, INVESTOR_A, walletA, 65_000, 10), 422, "RATE_OUTSIDE_GUIDANCE");
        problem(place(offering, INV_A, INVESTOR_A, walletA, 50_000, 0), 422, "QUANTITY_NOT_MULTIPLE");
        problem(place(offering, INV_A, INVESTOR_A, walletB, 50_000, 10), 422, "WALLET_NOT_REGISTERED");
        problem(place(offering, INV_B, INVESTOR_B, walletB, 50_000, 10), 422, "KYC_NOT_VALID");
        problem(place(offering, INV_A, INVESTOR_B, walletB, 50_000, 10), 403, "ORG_SCOPE_VIOLATION");

        UUID vifcOnly = openOffering(readySeries(Map.of("investorClasses", List.of("VIFC_MEMBER"))));
        problem(place(vifcOnly, INV_A, INVESTOR_A, walletA, 50_000, 10), 422, "INVESTOR_CLASS_NOT_ELIGIBLE");

        MvcResult r = place(offering, INV_A, INVESTOR_A, walletA, 50_000, 10);
        ok(r, 201, "Order");
        assertThat(body(r).get("status")).isEqualTo("PLACED");
        assertThat(body(r).get("enteredByOrgId")).isEqualTo(INVESTOR_A);
    }

    @Test
    @DisplayName("US-3.02-AC2 tổ hợp nhập thay khách: lệnh ghi người và tổ chức nhập")
    void US_3_02_AC2_syndicateEntersOnBehalfOfAClient() throws Exception {
        UUID offering = openOffering(readySeries(Map.of()));
        MvcResult r = place(offering, SYNDICATE_USER, INVESTOR_A, walletA, 45_000, 20);
        ok(r, 201, "Order");
        UUID order = id(r);
        assertThat(body(r).get("enteredByOrgId")).isEqualTo(SYNDICATE);
        assertThat(body(r).get("investorOrgId")).isEqualTo(INVESTOR_A);
        assertThat(jdbc.queryForObject("SELECT entered_by_user_id FROM distribution.\"order\" WHERE id = ?", String.class,
                order)).isEqualTo("7d1f0000-0000-4000-8000-0000000000e1");

        // Nhà đầu tư thấy lệnh của mình; nhà đầu tư khác không
        MvcResult mine = read("/v1/offerings/" + offering + "/orders", INV_A);
        ok(mine, 200, null);
        assertThat((List<?>) body(mine).get("items")).hasSize(1);
        assertThat((List<?>) body(read("/v1/offerings/" + offering + "/orders", INV_B)).get("items")).isEmpty();
        problem(read("/v1/orders/" + order, INV_B), 403, "ORG_SCOPE_VIOLATION");
        ok(read("/v1/orders/" + order, INV_A), 200, "Order");
    }

    @Test
    @DisplayName("US-3.02-AC3 sửa, hủy trước giờ đóng sổ có revision; sau đóng sổ mọi lệnh khóa")
    void US_3_02_AC3_amendAndCancelBeforeCloseThenLocked() throws Exception {
        UUID offering = openOffering(readySeries(Map.of()));
        UUID kept = order(offering, 50_000, 30);
        UUID dropped = order(offering, 55_000, 40);

        MvcResult amended = send(MockMvcRequestBuilders.patch("/v1/orders/" + kept).header("If-Match", "\"1\""), INV_A,
                Map.of("quantity", 35));
        ok(amended, 200, "Order");
        assertThat(body(amended).get("status")).isEqualTo("AMENDED");
        assertThat(body(amended).get("version")).isEqualTo(2);
        problem(send(MockMvcRequestBuilders.patch("/v1/orders/" + kept).header("If-Match", "\"1\""), INV_A,
                Map.of("quantity", 36)), 412, "PRECONDITION_FAILED");
        problem(send(MockMvcRequestBuilders.patch("/v1/orders/" + kept).header("If-Match", "\"2\""), INV_A,
                Map.of("ratePpm", 90_000)), 422, "RATE_OUTSIDE_GUIDANCE");

        MvcResult cancelled = send(MockMvcRequestBuilders.delete("/v1/orders/" + dropped).header("If-Match", "\"1\""),
                INV_A, null);
        ok(cancelled, 200, "Order");
        assertThat(body(cancelled).get("status")).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForList("SELECT action FROM distribution.order_revision WHERE order_id = ? ORDER BY revision_no",
                String.class, kept)).containsExactly("PLACE", "AMEND");
        assertThat(jdbc.queryForList("SELECT action FROM distribution.order_revision WHERE order_id = ? ORDER BY revision_no",
                String.class, dropped)).containsExactly("PLACE", "CANCEL");

        ok(post("/v1/offerings/" + offering + "/close", SYNDICATE_USER, null), 200, "Offering");
        byte[] event = nats.jetStreamManagement().getLastMessage("WERWA", "werwa.distribution.offering.closed.v1").getData();
        assertThat(EventSchemas.validate("werwa.distribution.offering.closed.v1", event)).isEmpty();
        assertThat(new String(event)).contains(offering.toString()).contains("\"orderCount\":1");

        problem(send(MockMvcRequestBuilders.patch("/v1/orders/" + kept).header("If-Match", "\"2\""), INV_A,
                Map.of("quantity", 40)), 409, "BOOK_NOT_OPEN");
        problem(place(offering, INV_A, INVESTOR_A, walletA, 50_000, 10), 409, "BOOK_NOT_OPEN");
    }

    // ---------- US-3.03 ----------

    @Test
    @DisplayName("US-3.03-AC1 điều chỉnh tay phải có lý do; mỗi lần đề xuất là phiên bản mới")
    @SuppressWarnings("unchecked")
    void US_3_03_AC1_manualAdjustmentsNeedAReasonAndCreateAVersion() throws Exception {
        UUID series = readySeries(Map.of());
        UUID offering = openOffering(series);
        UUID low = order(offering, 45_000, 300);
        UUID high = order(offering, 58_000, 200);
        problem(propose(offering, 50_000, List.of(item(low, 300, null))), 409, "OFFERING_NOT_CLOSED");
        ok(post("/v1/offerings/" + offering + "/close", SYNDICATE_USER, null), 200, null);
        assertThat(seriesStatus(series)).isEqualTo("BOOK_CLOSED");

        // Lãi suất đặt 58 000 > chốt 50 000 mà vẫn phân bổ, hoặc phân bổ thiếu: cần lý do
        problem(propose(offering, 50_000, List.of(item(low, 300, null), item(high, 200, null))), 422,
                "ADJUSTMENT_REASON_REQUIRED");
        problem(propose(offering, 50_000, List.of(item(low, 250, null))), 422, "ADJUSTMENT_REASON_REQUIRED");
        problem(propose(offering, 50_000, List.of(item(low, 400, "thêm"))), 422, "ALLOCATION_EXCEEDS_ORDER");

        MvcResult v1 = propose(offering, 50_000, List.of(item(low, 300, null)));
        ok(v1, 201, "AllocationList");
        assertThat(body(v1).get("listVersion")).isEqualTo(1);
        MvcResult v2 = propose(offering, 50_000, List.of(item(low, 250, "Giảm theo chỉ đạo UBND"),
                item(high, 100, "Phân bổ một phần cho lệnh trên mức chốt")));
        ok(v2, 201, "AllocationList");
        Map<String, Object> list = body(v2);
        assertThat(list.get("listVersion")).isEqualTo(2);
        assertThat(list.get("status")).isEqualTo("DRAFT");
        assertThat(list.get("totalQuantity")).isEqualTo(350);
        assertThat(body(read("/v1/allocation-lists/" + id(v1), SYNDICATE_USER)).get("status")).isEqualTo("SUPERSEDED");

        // Lá và gốc khớp quy tắc hợp đồng; số tiền = số lượng × mệnh giá × giá phát hành
        List<Map<String, Object>> items = (List<Map<String, Object>>) list.get("items");
        List<byte[]> leaves = new java.util.ArrayList<>();
        for (Map<String, Object> it : items) {
            byte[] leaf = AllocationMerkle.leaf(CONTROLLER, UUID.fromString((String) it.get("allocationId")),
                    (String) it.get("wallet"), ((Number) it.get("quantity")).longValue());
            assertThat(it.get("leaf")).isEqualTo(AllocationMerkle.hex(leaf));
            assertThat(((Map<String, Object>) it.get("amountDue")).get("amountMinor"))
                    .isEqualTo(Long.toString(((Number) it.get("quantity")).longValue() * 100_000));
            leaves.add(leaf);
        }
        assertThat(list.get("merkleRoot")).isEqualTo(AllocationMerkle.hex(AllocationMerkle.root(leaves)));
    }

    @Test
    @DisplayName("Q42 phân bổ có số tiền không chia hết theo giá phát hành bị từ chối")
    void amountsMustBeExact() throws Exception {
        UUID offering = openOffering(readySeries(Map.of("faceValue", Map.of("amountMinor", "1", "currency", "USD"),
                "issuePricePpm", 995_000)));
        UUID o = order(offering, 50_000, 300);
        ok(post("/v1/offerings/" + offering + "/close", SYNDICATE_USER, null), 200, null);
        problem(propose(offering, 50_000, List.of(item(o, 150, "lẻ"))), 422, "AMOUNT_NOT_EXACT");
        ok(propose(offering, 50_000, List.of(item(o, 200, "tròn"))), 201, "AllocationList");
    }

    @Test
    @DisplayName("US-3.03-AC2, US-2.06-AC2 gửi UBND: vượt khối lượng, lãi suất ngoài khung, thiếu quyết định thì từ chối; hợp lệ thì UBND duyệt")
    void US_3_03_AC2_submitChecksTotalsRateAndIssuerDecision() throws Exception {
        UUID offering = openOffering(readySeries(Map.of()));
        UUID a = order(offering, 50_000, 700);
        UUID b = order(offering, 50_000, 500);
        ok(post("/v1/offerings/" + offering + "/close", SYNDICATE_USER, null), 200, null);

        UUID tooMany = id(propose(offering, 50_000, List.of(item(a, 700, null), item(b, 500, null))));
        problem(post("/v1/allocation-lists/" + tooMany + "/submit", SYNDICATE_USER, null), 422, "ALLOCATION_EXCEEDS_MAX");
        UUID offFrame = id(propose(offering, 20_000, List.of(item(a, 700, "chốt thấp"))));
        problem(post("/v1/allocation-lists/" + offFrame + "/submit", SYNDICATE_USER, null), 422, "RATE_OUT_OF_RANGE");
        UUID good = id(propose(offering, 50_000, List.of(item(a, 600, "giới hạn 1000"), item(b, 400, "giới hạn 1000"))));
        problem(post("/v1/allocation-lists/" + good + "/submit", SYNDICATE_USER, null), 422, "ISSUER_DECISION_REQUIRED");

        couponDecision(offering);
        MvcResult submitted = post("/v1/allocation-lists/" + good + "/submit", SYNDICATE_USER, null);
        ok(submitted, 202, "SigningProposalRef");
        assertThat(body(submitted).get("status")).isEqualTo("PENDING_APPROVAL");
        UUID approval = UUID.fromString((String) body(submitted).get("approvalRequestId"));
        Map<String, Object> list = body(read("/v1/allocation-lists/" + good, ISSUER_CHECKER));
        assertThat(list.get("status")).isEqualTo("SUBMITTED");
        assertThat((String) list.get("listHash")).matches("^0x[0-9a-f]{64}$");

        // Q40: UBND thấy yêu cầu trong hàng chờ; tổ hợp không tự duyệt được
        assertThat(read("/v1/approvals", ISSUER_CHECKER).getResponse().getContentAsString()).contains(approval.toString());
        problem(post("/v1/approvals/" + approval + "/decision", SYNDICATE_OTHER, Map.of("decision", "APPROVE")), 403,
                "CHECKER_ROLE_REQUIRED");
        ok(post("/v1/approvals/" + approval + "/decision", ISSUER_CHECKER, Map.of("decision", "APPROVE")), 200, null);
        assertThat(jdbc.queryForObject("SELECT approved_at IS NOT NULL FROM distribution.allocation_list WHERE id = ?",
                Boolean.class, good)).isTrue();
        problem(propose(offering, 50_000, List.of(item(a, 600, "x"))), 409, "LIST_ALREADY_SUBMITTED");
    }

    @Test
    @DisplayName("US-3.03-AC4 nhà đầu tư lấy đường chứng minh và tự kiểm khớp gốc danh sách")
    @SuppressWarnings("unchecked")
    void US_3_03_AC4_investorVerifiesTheProofAgainstTheRoot() throws Exception {
        UUID offering = openOffering(readySeries(Map.of()));
        UUID mine = order(offering, 50_000, 300);
        UUID other = order(offering, 50_000, 200);
        UUID third = order(offering, 50_000, 100);
        ok(post("/v1/offerings/" + offering + "/close", SYNDICATE_USER, null), 200, null);
        Map<String, Object> list = body(propose(offering, 50_000, List.of(item(mine, 300, null), item(other, 200, null),
                item(third, 100, null))));
        UUID listId = UUID.fromString((String) list.get("id"));
        Map<String, Object> first = ((List<Map<String, Object>>) list.get("items")).getFirst();
        String path = "/v1/allocation-lists/" + listId + "/proofs/" + first.get("allocationId");

        problem(read(path, INV_A), 409, "LIST_NOT_SIGNED");
        // Ký trên chuỗi là B7b: đánh dấu đã ký để kiểm đường chứng minh
        jdbc.update("UPDATE distribution.allocation_list SET status = 'SIGNED' WHERE id = ?", listId);
        problem(read(path, INV_B), 403, "ORG_SCOPE_VIOLATION");
        MvcResult r = read(path, INV_A);
        ok(r, 200, "AllocationProof");
        Map<String, Object> proof = body(r);
        List<byte[]> siblings = ((List<String>) proof.get("proof")).stream()
                .map(h -> HexFormat.of().parseHex(h.substring(2))).toList();
        byte[] leaf = AllocationMerkle.leaf((String) proof.get("seriesController"),
                UUID.fromString((String) proof.get("allocationId")), (String) proof.get("wallet"),
                ((Number) proof.get("quantity")).longValue());
        assertThat(AllocationMerkle.verify(siblings, HexFormat.of().parseHex(((String) proof.get("merkleRoot")).substring(2)),
                leaf)).isTrue();
        assertThat(proof.get("merkleRoot")).isEqualTo(list.get("merkleRoot"));
    }
}
