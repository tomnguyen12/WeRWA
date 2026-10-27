package vn.werwa.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.IntegrationTest;
import vn.werwa.platform.OpenApiSchemas;
import vn.werwa.platform.events.EventSchemas;

/** Đề án, sổ hạn mức, series, quyết định UBND (US-2.01, 2.02, 2.04-AC2, 2.05, 2.06-AC1) với PostgreSQL và NATS thật. */
class IssuanceIT extends IntegrationTest {

    static final String UBND = "01a15bc1-f400-7c28-a7a8-d5d4cc19328b";
    static final String AGENCY = "01a15bc1-f401-70b2-9cfa-eb63c4f24ecc";
    static final String HOLCO = "01a15bc1-f409-7589-aef1-774ceddccf3f";
    static final String INVESTOR_A = "01a15bc1-f40a-7520-aeb5-19bb9d2faaa5";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    static RequestPostProcessor as(String subject, String org, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("org_id", org).claim("roles", List.of(role)));
    }

    static final RequestPostProcessor AGENCY_MAKER = as("agency-maker", AGENCY, "OPERATING_AGENCY");
    static final RequestPostProcessor AGENCY_CHECKER = as("agency-checker", AGENCY, "OPERATING_AGENCY");
    static final RequestPostProcessor HOLCO_MAKER = as("holco-maker", HOLCO, "OPERATOR");
    static final RequestPostProcessor HOLCO_CHECKER = as("holco-checker", HOLCO, "OPERATOR");
    static final RequestPostProcessor ISSUER = as("ubnd-user", UBND, "ISSUER");

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

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(MvcResult r) throws Exception {
        return JSON.readValue(r.getResponse().getContentAsString(), Map.class);
    }

    static void matches(String schema, MvcResult r) throws Exception {
        assertThat(OpenApiSchemas.validate(schema, r.getResponse().getContentAsString())).isEmpty();
    }

    static void problem(MvcResult r, int status, String code) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
        matches("Problem", r);
        assertThat(body(r).get("code")).isEqualTo(code);
    }

    static Map<String, Object> usd(long amountMinor) {
        return Map.of("amountMinor", Long.toString(amountMinor), "currency", "USD");
    }

    UUID doc(String type) throws Exception {
        return TestDocuments.uploadId(mvc, HOLCO_MAKER, type);
    }

    Map<String, Object> programBody(long maxPrincipalMinor) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("title", "Đề án thử " + UUID.randomUUID());
        b.put("hdndResolutionNo", "NQ-" + UUID.randomUUID());
        b.put("approvedOn", "2026-09-30");
        b.put("currency", "USD");
        b.put("maxPrincipal", usd(maxPrincipalMinor));
        b.put("rateFloorPpm", 30_000);
        b.put("rateCapPpm", 70_000);
        b.put("costCap", usd(1_000_000_00L));
        b.put("tenorRange", "P1Y/P10Y");
        b.put("eligibleInvestorClasses", List.of("FOREIGN_INSTITUTION", "VIFC_MEMBER"));
        b.put("issuanceMethods", List.of("BOOKBUILDING"));
        b.put("documentId", doc("HDND_RESOLUTION").toString());
        return b;
    }

    UUID approvalOf(String table, UUID id) {
        return jdbc.queryForObject("SELECT approval_request_id FROM issuance." + table + " WHERE id = ?", UUID.class, id);
    }

    MvcResult decide(UUID approval, RequestPostProcessor who, String decision) throws Exception {
        return post("/v1/approvals/" + approval + "/decision", who, Map.of("decision", decision));
    }

    UUID effectiveProgram(long maxPrincipalMinor) throws Exception {
        MvcResult r = post("/v1/programs", AGENCY_MAKER, programBody(maxPrincipalMinor));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        UUID id = UUID.fromString((String) body(r).get("id"));
        assertThat(decide(approvalOf("issuance_program", id), AGENCY_CHECKER, "APPROVE").getResponse().getStatus())
                .isEqualTo(200);
        return id;
    }

    String programStatus(UUID id) throws Exception {
        MvcResult r = mvc.perform(get("/v1/programs/" + id).with(ISSUER)).andReturn();
        matches("Program", r);
        return (String) body(r).get("status");
    }

    // ---------- US-2.01 ----------

    @Test
    @DisplayName("US-2.01-AC1 Đề án thiếu trường bắt buộc bị từ chối; đủ thì chờ duyệt")
    void US_2_01_AC1_programRequiresItsFrameworkFields() throws Exception {
        for (String field : List.of("hdndResolutionNo", "approvedOn", "documentId", "currency", "maxPrincipal",
                "rateCapPpm", "costCap", "eligibleInvestorClasses")) {
            Map<String, Object> b = programBody(1_000_000_00L);
            b.remove(field);
            problem(post("/v1/programs", AGENCY_MAKER, b), 400, "VALIDATION_FAILED");
        }
        Map<String, Object> unknownClass = programBody(1_000_000_00L);
        unknownClass.put("eligibleInvestorClasses", List.of("RETAIL"));
        problem(post("/v1/programs", AGENCY_MAKER, unknownClass), 422, "INVESTOR_CLASS_UNKNOWN");
        Map<String, Object> badTenor = programBody(1_000_000_00L);
        badTenor.put("tenorRange", "P10Y/P1Y");
        problem(post("/v1/programs", AGENCY_MAKER, badTenor), 422, "PROGRAM_INVALID");
        Map<String, Object> missingDoc = programBody(1_000_000_00L);
        missingDoc.put("documentId", UUID.randomUUID().toString());
        problem(post("/v1/programs", AGENCY_MAKER, missingDoc), 422, "DOCUMENT_NOT_FOUND");

        MvcResult ok = post("/v1/programs", AGENCY_MAKER, programBody(1_000_000_00L));
        assertThat(ok.getResponse().getStatus()).isEqualTo(201);
        matches("Program", ok);
        assertThat(body(ok).get("status")).isEqualTo("PENDING_REVIEW");
    }

    @Test
    @DisplayName("US-2.01-AC2 duyệt 4 mắt đạt thì Đề án hiệu lực; người lập không tự duyệt được")
    void US_2_01_AC2_fourEyesApprovalMakesProgramEffective() throws Exception {
        MvcResult r = post("/v1/programs", AGENCY_MAKER, programBody(1_000_000_00L));
        UUID id = UUID.fromString((String) body(r).get("id"));
        UUID approval = approvalOf("issuance_program", id);

        problem(decide(approval, AGENCY_MAKER, "APPROVE"), 403, "MAKER_CHECKER_SAME_USER");
        assertThat(programStatus(id)).isEqualTo("PENDING_REVIEW");

        assertThat(decide(approval, AGENCY_CHECKER, "APPROVE").getResponse().getStatus()).isEqualTo(200);
        assertThat(programStatus(id)).isEqualTo("EFFECTIVE");
    }

    @Test
    @DisplayName("US-2.01-AC3 đổi khung bằng Đề án điều chỉnh: bản mới trỏ bản cũ; bản cũ thành SUPERSEDED khi bản mới hiệu lực")
    void US_2_01_AC3_amendmentIsANewLinkedRecord() throws Exception {
        UUID original = effectiveProgram(1_000_000_00L);
        Map<String, Object> amendment = programBody(2_000_000_00L);
        amendment.put("amendsProgramId", original.toString());
        MvcResult r = post("/v1/programs", AGENCY_MAKER, amendment);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        UUID amended = UUID.fromString((String) body(r).get("id"));
        assertThat(amended).isNotEqualTo(original);
        assertThat(body(r).get("amendsProgramId")).isEqualTo(original.toString());
        assertThat(programStatus(original)).isEqualTo("EFFECTIVE");

        decide(approvalOf("issuance_program", amended), AGENCY_CHECKER, "APPROVE");
        assertThat(programStatus(amended)).isEqualTo("EFFECTIVE");
        assertThat(programStatus(original)).isEqualTo("SUPERSEDED");

        // Chỉ điều chỉnh được bản đang hiệu lực
        Map<String, Object> stale = programBody(3_000_000_00L);
        stale.put("amendsProgramId", original.toString());
        problem(post("/v1/programs", AGENCY_MAKER, stale), 409, "PROGRAM_NOT_EFFECTIVE");
    }

    // ---------- US-2.06 ----------

    @Test
    @DisplayName("US-2.06-AC1 quyết định UBND có số, ngày, tài liệu, đối tượng áp dụng và mã băm")
    void US_2_06_AC1_issuerDecisionIsRecordedWithHash() throws Exception {
        UUID offering = UUID.randomUUID();
        UUID document = doc("ISSUER_DECISION");
        String no = "QD-" + UUID.randomUUID();
        MvcResult r = post("/v1/issuer-decisions", ISSUER, Map.of("decisionNo", no, "decidedOn", "2026-10-20",
                "subject", "ALLOCATION", "offeringId", offering.toString(), "documentId", document.toString()));
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(r.getResponse().getHeader("Location")).startsWith("/v1/issuer-decisions/");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT subject, offering_id, document_id, decision_hash FROM issuance.issuer_decision WHERE decision_no = ?", no);
        assertThat(row.get("subject")).isEqualTo("ALLOCATION");
        assertThat(row.get("offering_id")).isEqualTo(offering);
        assertThat((String) row.get("decision_hash")).matches("^0x[0-9a-f]{64}$");

        problem(post("/v1/issuer-decisions", ISSUER, Map.of("decisionNo", no, "decidedOn", "2026-10-20",
                "subject", "ALLOCATION", "offeringId", offering.toString(), "documentId", document.toString())),
                409, "ISSUER_DECISION_EXISTS");
        problem(post("/v1/issuer-decisions", ISSUER, Map.of("decisionNo", "QD-" + UUID.randomUUID(),
                "decidedOn", "2026-10-20", "subject", "ALLOCATION", "documentId", document.toString())),
                422, "ISSUER_DECISION_INVALID");
        problem(post("/v1/issuer-decisions", HOLCO_MAKER, Map.of("decisionNo", "QD-" + UUID.randomUUID(),
                "decidedOn", "2026-10-20", "subject", "ALLOCATION", "offeringId", offering.toString(),
                "documentId", document.toString())), 403, "ROLE_NOT_ALLOWED");
    }

    // ---------- US-2.02 sổ chỉ ghi thêm ----------

    @Test
    @DisplayName("US-2.02-AC3 sổ hạn mức chỉ ghi thêm")
    void US_2_02_AC3_ledgerIsAppendOnly() {
        assertThatThrownBy(() -> jdbc.update("UPDATE issuance.limit_ledger_entry SET principal_minor = 1"))
                .hasMessageContaining("chỉ ghi thêm");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM issuance.limit_ledger_entry"))
                .hasMessageContaining("chỉ ghi thêm");
    }
}
