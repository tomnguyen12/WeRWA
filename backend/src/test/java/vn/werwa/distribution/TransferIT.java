package vn.werwa.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
import vn.werwa.platform.events.EventSchemas;

/**
 * Chuyển OTC không kèm thanh toán, phần ngoài chuỗi (US-3.08; Q77–Q82). Mỗi ca tự tạo tổ chức, KYC và ví riêng (nạp thẳng
 * DB) để không phụ thuộc kiểm thử khác dùng chung cơ sở dữ liệu.
 */
class TransferIT extends IntegrationTest {

    static final String HOLCO = "01a15bc1-f409-7589-aef1-774ceddccf3f";
    static final String AGENCY = "01a15bc1-f401-70b2-9cfa-eb63c4f24ecc";
    static final String VSDC = "01a15bc1-f403-7f0b-8fb9-a75cdc4b5b00";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Random RANDOM = new Random();

    static RequestPostProcessor as(String org, String role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", org).claim("roles", List.of(role)));
    }

    static final RequestPostProcessor OPERATOR = as(HOLCO, "OPERATOR");
    static final RequestPostProcessor REGISTRAR = as(VSDC, "REGISTRAR");
    static final RequestPostProcessor AGENCY_MAKER = as(AGENCY, "OPERATING_AGENCY");
    static final RequestPostProcessor AGENCY_CHECKER = as(AGENCY, "OPERATING_AGENCY");

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    io.nats.client.Connection nats;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ---------- dữ liệu thử ----------

    /** Tổ chức đã qua KYB (hoặc chưa, nếu {@code kyb} sai). */
    UUID organization(String role, boolean kyb) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO onboarding.organization (id, legal_name, registration_no, country, roles, kyb_status, created_at, updated_at)
                VALUES (?, ?, ?, 'SG', ARRAY[?], ?, now(), now())
                """, id, "Tổ chức thử " + id, "T-" + id, role, kyb ? "KYB_APPROVED" : "PENDING_REVIEW");
        return id;
    }

    UUID investor(String investorClass) {
        UUID org = organization("INVESTOR", true);
        if (investorClass != null) {
            jdbc.update("""
                    INSERT INTO onboarding.kyc_case (id, organization_id, status, provider, provider_ref, screening_result,
                        sanctions_hit, pep_hit, opened_by, opened_by_org_id, investor_class, decided_by, decided_at,
                        expires_at, created_at)
                    VALUES (?, ?, 'APPROVED', 'test', ?, '{}'::jsonb, false, false, 'maker', ?, ?, 'checker', now(),
                        now() + interval '1 year', now())
                    """, UUID.randomUUID(), org, "ref-" + org, UUID.fromString(HOLCO), investorClass);
        }
        return org;
    }

    UUID wallet(UUID org, String type) {
        UUID id = UUID.randomUUID();
        String address = "0x" + HexFormat.of().formatHex(java.nio.ByteBuffer.allocate(20).putLong(RANDOM.nextLong())
                .putLong(RANDOM.nextLong()).putInt(RANDOM.nextInt()).array());
        jdbc.update("""
                INSERT INTO onboarding.wallet (id, organization_id, chain_id, address, wallet_type, status, created_at, updated_at)
                VALUES (?, ?, 20261, ?, ?, 'REGISTERED', now(), now())
                """, id, org, address, type);
        return id;
    }

    /** Series Đang lưu hành, cho loại FOREIGN_INSTITUTION (trạng thái đồng bộ từ chuỗi là phần B6c, nạp thẳng DB). */
    UUID activeSeries() throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("title", "Đề án " + UUID.randomUUID());
        p.put("hdndResolutionNo", "NQ-" + UUID.randomUUID());
        p.put("approvedOn", "2026-09-30");
        p.put("currency", "USD");
        p.put("maxPrincipal", Map.of("amountMinor", "100000000", "currency", "USD"));
        p.put("rateFloorPpm", 30_000);
        p.put("rateCapPpm", 70_000);
        p.put("costCap", Map.of("amountMinor", "100000000", "currency", "USD"));
        p.put("eligibleInvestorClasses", List.of("FOREIGN_INSTITUTION"));
        p.put("documentId", TestDocuments.uploadId(mvc, OPERATOR, "HDND_RESOLUTION").toString());
        MvcResult program = post("/v1/programs", AGENCY_MAKER, p);
        ok(program, 201, null);
        UUID approval = jdbc.queryForObject("SELECT approval_request_id FROM issuance.issuance_program WHERE id = ?",
                UUID.class, id(program));
        ok(post("/v1/approvals/" + approval + "/decision", AGENCY_CHECKER, Map.of("decision", "APPROVE")), 200, null);
        Map<String, Object> s = new HashMap<>();
        s.put("programId", id(program).toString());
        s.put("seriesCode", "S-" + UUID.randomUUID());
        s.put("currency", "USD");
        s.put("faceValue", Map.of("amountMinor", "100000", "currency", "USD"));
        s.put("issuePricePpm", 1_000_000);
        s.put("maxQuantity", 100);
        s.put("couponFrequency", "SEMI_ANNUAL");
        s.put("dayCount", "30_360");
        s.put("issueDate", "2027-03-02");
        s.put("maturityDate", "2030-03-02");
        s.put("termsDocumentId", TestDocuments.uploadId(mvc, OPERATOR, "TERM_SHEET").toString());
        MvcResult series = post("/v1/series", OPERATOR, s);
        ok(series, 201, null);
        jdbc.update("UPDATE issuance.series SET status = 'ACTIVE' WHERE id = ?", id(series));
        return id(series);
    }

    MvcResult post(String path, RequestPostProcessor who, Object body) throws Exception {
        var request = MockMvcRequestBuilders.post(path).with(who).header("Idempotency-Key", "k-" + UUID.randomUUID());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request).andReturn();
    }

    MvcResult read(String path, RequestPostProcessor who) throws Exception {
        return mvc.perform(get(path).with(who)).andReturn();
    }

    MvcResult create(RequestPostProcessor who, UUID series, UUID from, UUID to, long qty) throws Exception {
        return post("/v1/transfers", who, Map.of("seriesId", series.toString(), "fromWalletId", from.toString(),
                "toWalletId", to.toString(), "quantity", qty));
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

    // ---------- US-3.08 ----------

    @Test
    @DisplayName("US-3.08-AC1 bên chuyển tạo, bên nhận xác nhận; đủ xác nhận thì phát sự kiện chờ gửi lên chuỗi")
    void US_3_08_AC1_selfWalletsBothConfirm() throws Exception {
        UUID series = activeSeries();
        UUID a = investor("FOREIGN_INSTITUTION");
        UUID b = investor("FOREIGN_INSTITUTION");
        RequestPostProcessor userA = as(a.toString(), "INVESTOR");
        RequestPostProcessor userB = as(b.toString(), "INVESTOR");
        UUID walletA = wallet(a, "SELF");
        UUID walletB = wallet(b, "SELF");

        MvcResult created = create(userA, series, walletA, walletB, 10);
        ok(created, 201, "Transfer");
        Map<String, Object> t = body(created);
        assertThat(t.get("status")).isEqualTo("PENDING_CONFIRMATIONS");
        assertThat(t.get("fromConfirmedAt")).isNotNull();
        assertThat(t.get("toConfirmedAt")).isNull();
        assertThat(t.get("custodianConfirmedAt")).isNull();
        UUID transfer = id(created);

        // Chỉ bên nhận xác nhận được
        problem(post("/v1/transfers/" + transfer + "/confirm", userA, null), 403, "ORG_SCOPE_VIOLATION");
        MvcResult confirmed = post("/v1/transfers/" + transfer + "/confirm", userB, null);
        ok(confirmed, 202, "Transfer");
        assertThat(body(confirmed).get("toConfirmedAt")).isNotNull();
        assertThat(body(confirmed).get("status")).isEqualTo("PENDING_CONFIRMATIONS");
        problem(post("/v1/transfers/" + transfer + "/confirm", userB, null), 409, "TRANSFER_ALREADY_CONFIRMED");

        byte[] event = nats.jetStreamManagement().getLastMessage("WERWA", "werwa.distribution.transfer.confirmed.v1")
                .getData();
        assertThat(EventSchemas.validate("werwa.distribution.transfer.confirmed.v1", event)).isEmpty();
        assertThat(new String(event)).contains(transfer.toString()).contains("\"quantity\":10");

        // Đã đủ xác nhận thì bên chuyển không hủy được nữa
        problem(post("/v1/transfers/" + transfer + "/cancel", userA, null), 409, "TRANSFER_NOT_PENDING");
    }

    @Test
    @DisplayName("US-3.08-AC1 ví omnibus: lưu ký tạo thay chủ sở hữu thực và ghi xác nhận của lưu ký (Q78)")
    void US_3_08_AC1_omnibusCustodianConfirms() throws Exception {
        UUID series = activeSeries();
        UUID custodian = organization("CUSTODIAN", true);
        UUID b = investor("FOREIGN_INSTITUTION");
        UUID omnibus = wallet(custodian, "OMNIBUS");
        UUID walletB = wallet(b, "SELF");

        // Nhà đầu tư không tạo được từ ví omnibus của lưu ký
        problem(create(as(custodian.toString(), "INVESTOR"), series, omnibus, walletB, 5), 403, "ORG_SCOPE_VIOLATION");
        MvcResult created = create(as(custodian.toString(), "CUSTODIAN"), series, omnibus, walletB, 5);
        ok(created, 201, "Transfer");
        assertThat(body(created).get("fromConfirmedAt")).isNotNull();
        assertThat(body(created).get("custodianConfirmedAt")).isNotNull();
        ok(post("/v1/transfers/" + id(created) + "/confirm", as(b.toString(), "INVESTOR"), null), 202, "Transfer");

        // Chuyển vào ví omnibus: lưu ký của ví nhận xác nhận; lưu ký chưa qua KYB thì không nhận được
        UUID pending = organization("CUSTODIAN", false);
        problem(create(as(b.toString(), "INVESTOR"), series, walletB, wallet(pending, "OMNIBUS"), 1), 422,
                "TRANSFER_RECIPIENT_NOT_ELIGIBLE");
        UUID other = organization("CUSTODIAN", true);
        MvcResult toOmnibus = create(as(b.toString(), "INVESTOR"), series, walletB, wallet(other, "OMNIBUS"), 1);
        ok(toOmnibus, 201, "Transfer");
        problem(post("/v1/transfers/" + id(toOmnibus) + "/confirm", as(other.toString(), "INVESTOR"), null), 403,
                "ORG_SCOPE_VIOLATION");
        ok(post("/v1/transfers/" + id(toOmnibus) + "/confirm", as(other.toString(), "CUSTODIAN"), null), 202,
                "Transfer");
    }

    @Test
    @DisplayName("US-3.08 kiểm khi tạo: series, ví, số lượng, điều kiện bên nhận (Q79)")
    void US_3_08_createChecks() throws Exception {
        UUID series = activeSeries();
        UUID a = investor("FOREIGN_INSTITUTION");
        RequestPostProcessor userA = as(a.toString(), "INVESTOR");
        UUID walletA = wallet(a, "SELF");
        UUID eligible = wallet(investor("FOREIGN_INSTITUTION"), "SELF");

        problem(create(userA, series, walletA, walletA, 1), 422, "TRANSFER_SAME_WALLET");
        problem(create(userA, series, walletA, eligible, 0), 422, "TRANSFER_INVALID");
        problem(create(userA, series, walletA, UUID.randomUUID(), 1), 422, "WALLET_NOT_REGISTERED");
        // Bên nhận không có KYC, hoặc loại nhà đầu tư không nằm trong danh sách của series
        problem(create(userA, series, walletA, wallet(investor(null), "SELF"), 1), 422,
                "TRANSFER_RECIPIENT_NOT_ELIGIBLE");
        problem(create(userA, series, walletA, wallet(investor("VIFC_MEMBER"), "SELF"), 1), 422,
                "TRANSFER_RECIPIENT_NOT_ELIGIBLE");
        // Người của tổ chức khác không chuyển được từ ví của A
        problem(create(as(UUID.randomUUID().toString(), "INVESTOR"), series, walletA, eligible, 1), 403,
                "ORG_SCOPE_VIOLATION");
        // Series chưa Đang lưu hành
        jdbc.update("UPDATE issuance.series SET status = 'ISSUING' WHERE id = ?", series);
        problem(create(userA, series, walletA, eligible, 1), 409, "TRANSFER_SERIES_NOT_ACTIVE");
        problem(create(userA, UUID.randomUUID(), walletA, eligible, 1), 422, "SERIES_NOT_FOUND");
    }

    @Test
    @DisplayName("US-3.08 KYC bên nhận hết hạn sau khi tạo thì không xác nhận được (Q79)")
    void US_3_08_recipientRecheckedOnConfirm() throws Exception {
        UUID series = activeSeries();
        UUID a = investor("FOREIGN_INSTITUTION");
        UUID b = investor("FOREIGN_INSTITUTION");
        MvcResult created = create(as(a.toString(), "INVESTOR"), series, wallet(a, "SELF"), wallet(b, "SELF"), 3);
        ok(created, 201, "Transfer");
        jdbc.update("UPDATE onboarding.kyc_case SET expires_at = now() - interval '1 day' WHERE organization_id = ?", b);
        problem(post("/v1/transfers/" + id(created) + "/confirm", as(b.toString(), "INVESTOR"), null), 422,
                "TRANSFER_RECIPIENT_NOT_ELIGIBLE");
    }

    @Test
    @DisplayName("US-3.08 bên chuyển hủy khi còn chờ; mỗi bên chỉ thấy yêu cầu có ví của mình (Q81)")
    void US_3_08_cancelAndVisibility() throws Exception {
        UUID series = activeSeries();
        UUID a = investor("FOREIGN_INSTITUTION");
        UUID b = investor("FOREIGN_INSTITUTION");
        UUID c = investor("FOREIGN_INSTITUTION");
        RequestPostProcessor userA = as(a.toString(), "INVESTOR");
        RequestPostProcessor userB = as(b.toString(), "INVESTOR");
        RequestPostProcessor userC = as(c.toString(), "INVESTOR");
        UUID walletB = wallet(b, "SELF");
        MvcResult first = create(userA, series, wallet(a, "SELF"), walletB, 2);
        ok(first, 201, "Transfer");
        UUID transfer = id(first);

        problem(post("/v1/transfers/" + transfer + "/cancel", userB, null), 403, "ORG_SCOPE_VIOLATION");
        MvcResult cancelled = post("/v1/transfers/" + transfer + "/cancel", userA, null);
        ok(cancelled, 200, "Transfer");
        assertThat(body(cancelled).get("status")).isEqualTo("CANCELLED");
        assertThat(body(cancelled).get("cancelledAt")).isNotNull();
        problem(post("/v1/transfers/" + transfer + "/confirm", userB, null), 409, "TRANSFER_NOT_PENDING");
        problem(post("/v1/transfers/" + transfer + "/cancel", userA, null), 409, "TRANSFER_NOT_PENDING");

        ok(read("/v1/transfers/" + transfer, userB), 200, "Transfer");
        ok(read("/v1/transfers/" + transfer, REGISTRAR), 200, "Transfer");
        problem(read("/v1/transfers/" + transfer, userC), 404, "TRANSFER_NOT_FOUND");

        MvcResult second = create(userA, series, wallet(a, "SELF"), walletB, 1);
        ok(second, 201, "Transfer");
        MvcResult mine = read("/v1/transfers?seriesId=" + series + "&limit=1", userA);
        ok(mine, 200, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> page = (List<Map<String, Object>>) body(mine).get("items");
        assertThat(page).extracting(i -> i.get("id")).containsExactly(id(second).toString());
        String cursor = (String) body(mine).get("nextCursor");
        assertThat(cursor).isNotNull();
        MvcResult next = mvc.perform(get("/v1/transfers").param("seriesId", series.toString()).param("limit", "1")
                .param("cursor", cursor).with(userA)).andReturn();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rest = (List<Map<String, Object>>) body(next).get("items");
        assertThat(rest).extracting(i -> i.get("id")).containsExactly(transfer.toString());
        assertThat((List<?>) body(read("/v1/transfers?seriesId=" + series + "&status=CANCELLED", userB)).get("items"))
                .hasSize(1);
        assertThat((List<?>) body(read("/v1/transfers?seriesId=" + series, userC)).get("items")).isEmpty();
        assertThat((List<?>) body(read("/v1/transfers?seriesId=" + series, OPERATOR)).get("items")).hasSize(2);
    }
}
