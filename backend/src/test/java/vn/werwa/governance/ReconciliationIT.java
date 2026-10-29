package vn.werwa.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import vn.werwa.governance.reconciliation.ReconciliationService;
import vn.werwa.issuance.TestDocuments;
import vn.werwa.platform.OpenApiSchemas;

/** Đối soát hằng ngày (US-4.09; Q73–Q76). DB dùng chung với kiểm thử khác nên chỉ kiểm chênh lệch do chính ca này tạo. */
class ReconciliationIT extends IntegrationTest {

    static final String HOLCO = "01a15bc1-f409-7589-aef1-774ceddccf3f";
    static final String AGENCY = "01a15bc1-f401-70b2-9cfa-eb63c4f24ecc";
    static final String VSDC = "01a15bc1-f403-7f0b-8fb9-a75cdc4b5b00";
    static final String BIDV = "01a15bc1-f404-72f3-86c3-4f3f7c24f440";
    static final String SUPERVISOR = "01a15bc1-f408-7d65-ace1-f4c87b5c1cdc";
    static final JsonMapper JSON = JsonMapper.builder().build();

    static RequestPostProcessor as(String subject, String org, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("org_id", org).claim("roles", List.of(role)));
    }

    static final String OPERATOR_ID = "cd1f0000-0000-4000-8000-000000000001";
    static final RequestPostProcessor OPERATOR = as(OPERATOR_ID, HOLCO, "OPERATOR");
    static final RequestPostProcessor REGISTRAR = as("cd1f0000-0000-4000-8000-000000000002", VSDC, "REGISTRAR");
    static final RequestPostProcessor PAYING_AGENT = as("cd1f0000-0000-4000-8000-000000000003", BIDV, "PAYING_AGENT");
    static final RequestPostProcessor SUPERVISOR_USER = as("cd1f0000-0000-4000-8000-000000000004", SUPERVISOR, "SUPERVISOR");
    static final RequestPostProcessor AGENCY_MAKER = as("cd1f0000-0000-4000-8000-0000000000a1", AGENCY, "OPERATING_AGENCY");
    static final RequestPostProcessor AGENCY_CHECKER = as("cd1f0000-0000-4000-8000-0000000000a2", AGENCY, "OPERATING_AGENCY");

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReconciliationService reconciliation;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    MvcResult post(String path, RequestPostProcessor who, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path).with(who).header("Idempotency-Key", "k-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body))).andReturn();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(MvcResult r) throws Exception {
        return JSON.readValue(r.getResponse().getContentAsString(), Map.class);
    }

    /** Đề án hiệu lực 1.000 USD và một series nháp của nó. */
    UUID[] programAndSeries() throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("title", "Đề án " + UUID.randomUUID());
        p.put("hdndResolutionNo", "NQ-" + UUID.randomUUID());
        p.put("approvedOn", "2026-09-30");
        p.put("currency", "USD");
        p.put("maxPrincipal", Map.of("amountMinor", "100000", "currency", "USD"));
        p.put("rateFloorPpm", 30_000);
        p.put("rateCapPpm", 70_000);
        p.put("costCap", Map.of("amountMinor", "100000", "currency", "USD"));
        p.put("eligibleInvestorClasses", List.of("FOREIGN_INSTITUTION"));
        p.put("documentId", TestDocuments.uploadId(mvc, OPERATOR, "HDND_RESOLUTION").toString());
        UUID program = UUID.fromString((String) body(post("/v1/programs", AGENCY_MAKER, p)).get("id"));
        UUID approval = jdbc.queryForObject("SELECT approval_request_id FROM issuance.issuance_program WHERE id = ?",
                UUID.class, program);
        post("/v1/approvals/" + approval + "/decision", AGENCY_CHECKER, Map.of("decision", "APPROVE"));
        Map<String, Object> s = new HashMap<>();
        s.put("programId", program.toString());
        s.put("seriesCode", "S-" + UUID.randomUUID());
        s.put("currency", "USD");
        s.put("faceValue", Map.of("amountMinor", "100000", "currency", "USD"));
        s.put("issuePricePpm", 1_000_000);
        s.put("maxQuantity", 1);
        s.put("couponFrequency", "SEMI_ANNUAL");
        s.put("dayCount", "30_360");
        s.put("issueDate", "2027-03-02");
        s.put("maturityDate", "2030-03-02");
        s.put("termsDocumentId", TestDocuments.uploadId(mvc, OPERATOR, "TERM_SHEET").toString());
        MvcResult series = post("/v1/series", OPERATOR, s);
        assertThat(series.getResponse().getStatus()).as(series.getResponse().getContentAsString()).isEqualTo(201);
        return new UUID[] {program, UUID.fromString((String) body(series).get("id"))};
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> runs(RequestPostProcessor who) throws Exception {
        MvcResult r = mvc.perform(get("/v1/reconciliation/runs").param("limit", "200").with(who)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        List<Map<String, Object>> items = (List<Map<String, Object>>) body(r).get("items");
        for (Map<String, Object> run : items) {
            assertThat(OpenApiSchemas.validate("ReconciliationRun", JSON.writeValueAsString(run))).isEmpty();
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> breakFor(List<Map<String, Object>> runs, List<UUID> runIds, String subjectRef) {
        return runs.stream().filter(r -> runIds.contains(UUID.fromString((String) r.get("id"))))
                .flatMap(r -> ((List<Map<String, Object>>) r.get("breaks")).stream())
                .filter(b -> subjectRef.equals(b.get("subjectRef"))).findFirst().orElse(null);
    }

    @Test
    @DisplayName("US-4.09-AC1, AC2 đối soát kiểm R1: mỗi lệch thành một chênh lệch cần người đóng; còn lệch thì có chênh lệch mới")
    void US_4_09_AC1_AC2_breaksAreRecordedAndMustBeResolved() throws Exception {
        UUID[] ps = programAndSeries();
        // Series đã qua duyệt nhưng không có giữ chỗ, và bút toán vượt khối lượng Đề án (1.000 USD)
        jdbc.update("UPDATE issuance.series SET status = 'PENDING_SIGNATURES' WHERE id = ?", ps[1]);
        List<UUID> first = reconciliation.runAll(LocalDate.of(2026, 10, 28));
        List<Map<String, Object>> all = runs(OPERATOR);
        Map<String, Object> missing = breakFor(all, first, "series/" + ps[1]);
        assertThat(missing).isNotNull();
        assertThat(missing.get("rule")).isEqualTo("R1");
        assertThat(missing.get("kind")).isEqualTo("RESERVE_MISSING");

        jdbc.update("""
                INSERT INTO issuance.limit_ledger_entry (id, program_id, series_id, entry_type, principal_minor, created_by, created_at)
                VALUES (?, ?, ?, 'RESERVE', 200000, 'test', now())
                """, UUID.randomUUID(), ps[0], ps[1]);
        List<UUID> second = reconciliation.runAll(LocalDate.of(2026, 10, 29));
        Map<String, Object> exceeded = breakFor(runs(OPERATOR), second, "program/" + ps[0]);
        assertThat(exceeded).isNotNull();
        assertThat(exceeded.get("kind")).isEqualTo("PROGRAM_LIMIT_EXCEEDED");
        assertThat(exceeded.get("actual")).isEqualTo("200000");
        assertThat(breakFor(runs(OPERATOR), second, "series/" + ps[1])).isNull();

        // AC2: người xử lý đóng, bắt buộc lý do
        String id = (String) exceeded.get("id");
        MvcResult noReason = post("/v1/reconciliation/breaks/" + id + "/resolve", OPERATOR, Map.of("reason", ""));
        assertThat(noReason.getResponse().getStatus()).isIn(400, 422);
        MvcResult resolved = post("/v1/reconciliation/breaks/" + id + "/resolve", OPERATOR,
                Map.of("reason", "Đã đối chiếu: bút toán nhập thử, sẽ ghi RELEASE"));
        assertThat(resolved.getResponse().getStatus()).as(resolved.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(OpenApiSchemas.validate("ReconciliationBreak", resolved.getResponse().getContentAsString())).isEmpty();
        assertThat(body(resolved).get("resolvedBy")).isEqualTo(OPERATOR_ID);
        MvcResult again = post("/v1/reconciliation/breaks/" + id + "/resolve", OPERATOR, Map.of("reason", "lần hai"));
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(again).get("code")).isEqualTo("RECONCILIATION_BREAK_RESOLVED");
        assertThat(post("/v1/reconciliation/breaks/" + id + "/resolve", REGISTRAR, Map.of("reason", "x")).getResponse()
                .getStatus()).isEqualTo(403);

        // Q75: lần chạy sau còn lệch thì có chênh lệch mới, chưa đóng
        List<UUID> third = reconciliation.runAll(LocalDate.of(2026, 10, 30));
        Map<String, Object> reopened = breakFor(runs(OPERATOR), third, "program/" + ps[0]);
        assertThat(reopened).isNotNull();
        assertThat(reopened.get("id")).isNotEqualTo(id);
        assertThat(reopened.get("resolvedAt")).isNull();

        // Có thông báo cho Holco
        assertThat(mvc.perform(get("/v1/notifications").param("limit", "200").with(OPERATOR)).andReturn().getResponse()
                .getContentAsString()).contains("RECONCILIATION_BREAK");
    }

    @Test
    @DisplayName("US-4.09-AC3 BIDV và VSDC chỉ thấy phần liên quan tới mình")
    void US_4_09_AC3_partiesSeeTheirScopesOnly() throws Exception {
        reconciliation.runAll(LocalDate.of(2026, 10, 27));
        assertThat(runs(OPERATOR)).extracting(r -> r.get("scope")).contains("LIMITS", "AUDIT_CHAIN", "CHAIN_VS_BANK");
        assertThat(runs(SUPERVISOR_USER)).extracting(r -> r.get("scope")).contains("LIMITS", "AUDIT_CHAIN");
        assertThat(runs(PAYING_AGENT)).isNotEmpty().allSatisfy(r -> assertThat(r.get("scope")).isEqualTo("CHAIN_VS_BANK"));
        assertThat(runs(REGISTRAR)).allSatisfy(r -> assertThat(r.get("scope")).isIn("CHAIN_VS_VIEW", "BLOCK_HASH_VSDC"));
    }
}
