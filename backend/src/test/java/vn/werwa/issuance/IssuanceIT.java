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

    @Autowired
    io.nats.client.Connection nats;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    static RequestPostProcessor as(String subject, String org, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("org_id", org).claim("roles", List.of(role)));
    }

    static final RequestPostProcessor AGENCY_MAKER = as("6c1f0000-0000-4000-8000-0000000000a1", AGENCY, "OPERATING_AGENCY");
    static final RequestPostProcessor AGENCY_CHECKER = as("6c1f0000-0000-4000-8000-0000000000a2", AGENCY, "OPERATING_AGENCY");
    static final RequestPostProcessor HOLCO_MAKER = as("6c1f0000-0000-4000-8000-0000000000b1", HOLCO, "OPERATOR");
    static final RequestPostProcessor HOLCO_CHECKER = as("6c1f0000-0000-4000-8000-0000000000b2", HOLCO, "OPERATOR");
    static final RequestPostProcessor ISSUER = as("6c1f0000-0000-4000-8000-0000000000c1", UBND, "ISSUER");

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

    // ---------- Series ----------

    Map<String, Object> seriesBody(UUID program, long maxQuantity) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("programId", program.toString());
        b.put("seriesCode", "S-" + UUID.randomUUID());
        b.put("currency", "USD");
        b.put("faceValue", usd(1_000_00L));
        b.put("issuePricePpm", 1_000_000);
        b.put("maxQuantity", maxQuantity);
        b.put("couponFrequency", "SEMI_ANNUAL");
        b.put("dayCount", "30_360");
        b.put("issueDate", "2027-03-02");
        b.put("maturityDate", "2030-03-02");
        b.put("calendars", List.of("VN", "NY"));
        b.put("termsDocumentId", doc("TERM_SHEET").toString());
        b.put("documentIds", List.of(doc("OFFERING_CIRCULAR").toString(), doc("LEGAL_OPINION").toString()));
        return b;
    }

    UUID createSeries(Map<String, Object> body) throws Exception {
        MvcResult r = post("/v1/series", HOLCO_MAKER, body);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        matches("Series", r);
        return UUID.fromString((String) body(r).get("id"));
    }

    MvcResult getSeries(UUID id) throws Exception {
        MvcResult r = mvc.perform(get("/v1/series/" + id).with(INVESTOR)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        matches("Series", r);
        return r;
    }

    String seriesStatus(UUID id) throws Exception {
        return (String) body(getSeries(id)).get("status");
    }

    MvcResult submit(UUID id) throws Exception {
        return post("/v1/series/" + id + "/submit", HOLCO_MAKER, null);
    }

    UUID submitted(UUID id) throws Exception {
        MvcResult r = submit(id);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(202);
        matches("SigningProposalRef", r);
        assertThat(body(r).get("status")).isEqualTo("PENDING_APPROVAL");
        return UUID.fromString((String) body(r).get("approvalRequestId"));
    }

    static final RequestPostProcessor INVESTOR = as("6c1f0000-0000-4000-8000-0000000000d1", INVESTOR_A, "INVESTOR");

    String approvalStatus(UUID approval) {
        return jdbc.queryForObject("SELECT status FROM governance.approval_request WHERE id = ?", String.class, approval);
    }

    Map<String, Object> limits(UUID program) throws Exception {
        MvcResult r = mvc.perform(get("/v1/programs/" + program + "/limits").with(ISSUER)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        matches("ProgramLimits", r);
        return body(r);
    }

    @SuppressWarnings("unchecked")
    static String amount(Map<String, Object> limits, String field) {
        return (String) ((Map<String, Object>) limits.get(field)).get("amountMinor");
    }

    @Test
    @DisplayName("US-2.05-AC1 term sheet được kiểm đồng tiền, kỳ hạn, đối tượng theo khung Đề án")
    void US_2_05_AC1_termSheetIsCheckedAgainstTheProgram() throws Exception {
        UUID program = effectiveProgram(100_000_000_00L);

        Map<String, Object> vnd = seriesBody(program, 1000);
        vnd.put("currency", "VND");
        vnd.put("faceValue", Map.of("amountMinor", "100000000", "currency", "VND"));
        problem(post("/v1/series", HOLCO_MAKER, vnd), 422, "SERIES_OUTSIDE_PROGRAM");

        Map<String, Object> tooLong = seriesBody(program, 1000);
        tooLong.put("maturityDate", "2042-03-02");
        problem(post("/v1/series", HOLCO_MAKER, tooLong), 422, "SERIES_OUTSIDE_PROGRAM");

        Map<String, Object> retail = seriesBody(program, 1000);
        retail.put("investorClasses", List.of("VIFC_MEMBER", "RETAIL"));
        problem(post("/v1/series", HOLCO_MAKER, retail), 422, "INVESTOR_CLASS_NOT_ELIGIBLE");

        MvcResult pending = post("/v1/programs", AGENCY_MAKER, programBody(100_000_000_00L));
        UUID pendingProgram = UUID.fromString((String) body(pending).get("id"));
        problem(post("/v1/series", HOLCO_MAKER, seriesBody(pendingProgram, 1000)), 409, "PROGRAM_NOT_EFFECTIVE");

        Map<String, Object> ok = seriesBody(program, 1000);
        MvcResult r = post("/v1/series", HOLCO_MAKER, ok);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        matches("Series", r);
        assertThat(body(r).get("status")).isEqualTo("DRAFT");
        assertThat(body(r).get("investorClasses")).isEqualTo(List.of("FOREIGN_INSTITUTION", "VIFC_MEMBER"));
        assertThat((String) body(r).get("termsHash")).matches("^0x[0-9a-f]{64}$");
        assertThat(r.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
        problem(post("/v1/series", HOLCO_MAKER, ok), 409, "SERIES_CODE_TAKEN");
    }

    @Test
    @DisplayName("US-2.05-AC2 series USD trả 6 tháng: lịch theo VN và NY lưu cùng series")
    @SuppressWarnings("unchecked")
    void US_2_05_AC2_scheduleIsStoredWithTheSeries() throws Exception {
        UUID id = createSeries(seriesBody(effectiveProgram(100_000_000_00L), 1000));
        List<Map<String, Object>> events = (List<Map<String, Object>>) body(getSeries(id)).get("events");
        assertThat(events).hasSize(7);
        assertThat(events).filteredOn(e -> "MATURITY".equals(e.get("eventType"))).hasSize(1);
        Map<String, Object> first = events.getFirst();
        assertThat(first.get("periodEnd")).isEqualTo("2027-09-02");
        assertThat(first.get("paymentDate")).isEqualTo("2027-09-07");
        assertThat(first.get("recordDate")).isEqualTo("2027-09-01");
    }

    @Test
    @DisplayName("US-2.04-AC2 gửi duyệt khi còn thiếu tài liệu bắt buộc thì từ chối, liệt kê tài liệu thiếu")
    void US_2_04_AC2_submitRequiresTheConfiguredDocuments() throws Exception {
        Map<String, Object> b = seriesBody(effectiveProgram(100_000_000_00L), 1000);
        b.put("documentIds", List.of(doc("OFFERING_CIRCULAR").toString()));
        UUID id = createSeries(b);
        MvcResult r = submit(id);
        problem(r, 422, "REQUIRED_DOCUMENTS_MISSING");
        assertThat((String) body(r).get("detail")).contains("LEGAL_OPINION").doesNotContain("TERM_SHEET");
        assertThat(seriesStatus(id)).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("US-2.05-AC3 sửa term sheet của series đã gửi duyệt: về Nháp, yêu cầu duyệt cũ bị hủy, termsHash tính lại")
    void US_2_05_AC3_editingASubmittedSeriesSendsItBackToDraft() throws Exception {
        Map<String, Object> b = seriesBody(effectiveProgram(100_000_000_00L), 1000);
        UUID id = createSeries(b);
        UUID approval = submitted(id);
        MvcResult before = getSeries(id);
        assertThat(body(before).get("status")).isEqualTo("PENDING_REVIEW");
        String etag = before.getResponse().getHeader("ETag");
        String oldHash = (String) body(before).get("termsHash");

        Map<String, Object> update = new HashMap<>(b);
        update.remove("programId");
        update.remove("seriesCode");
        update.put("maturityDate", "2031-03-02");
        problem(send(MockMvcRequestBuilders.patch("/v1/series/" + id), HOLCO_MAKER, update), 428, "PRECONDITION_REQUIRED");
        problem(send(MockMvcRequestBuilders.patch("/v1/series/" + id).header("If-Match", "\"99\""), HOLCO_MAKER, update),
                412, "PRECONDITION_FAILED");

        MvcResult r = send(MockMvcRequestBuilders.patch("/v1/series/" + id).header("If-Match", etag), HOLCO_MAKER, update);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        matches("Series", r);
        assertThat(body(r).get("status")).isEqualTo("DRAFT");
        assertThat(body(r).get("termsHash")).isNotEqualTo(oldHash);
        assertThat(body(r).get("maturityDate")).isEqualTo("2031-03-02");
        assertThat((List<?>) body(r).get("events")).hasSize(9);
        assertThat(approvalStatus(approval)).isEqualTo("CANCELLED");
        problem(decide(approval, HOLCO_CHECKER, "APPROVE"), 409, "APPROVAL_NOT_PENDING");

        // Gửi lại thì có yêu cầu duyệt mới
        assertThat(submitted(id)).isNotEqualTo(approval);
    }

    @Test
    @DisplayName("US-2.02-AC1 series qua duyệt thì giữ chỗ khối lượng tối đa nhân mệnh giá; chờ ký 3 bên; phát series.approved")
    void US_2_02_AC1_approvalReservesTheLimit() throws Exception {
        UUID program = effectiveProgram(100_000_000_00L);
        UUID id = createSeries(seriesBody(program, 50_000));
        UUID approval = submitted(id);

        MvcResult r = decide(approval, HOLCO_CHECKER, "APPROVE");
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(seriesStatus(id)).isEqualTo("PENDING_SIGNATURES");

        Map<String, Object> entry = jdbc.queryForMap(
                "SELECT program_id, entry_type, principal_minor FROM issuance.limit_ledger_entry WHERE series_id = ?", id);
        assertThat(entry.get("entry_type")).isEqualTo("RESERVE");
        assertThat(entry.get("principal_minor")).isEqualTo(5_000_000_000L);
        Map<String, Object> limits = limits(program);
        assertThat(amount(limits, "reserved")).isEqualTo("5000000000");
        assertThat(amount(limits, "used")).isEqualTo("0");
        assertThat(amount(limits, "available")).isEqualTo("5000000000");

        byte[] event = nats.jetStreamManagement().getLastMessage("WERWA", "werwa.issuance.series.approved.v1").getData();
        assertThat(EventSchemas.validate("werwa.issuance.series.approved.v1", event)).isEmpty();
        assertThat(new String(event)).contains(id.toString());
    }

    @Test
    @DisplayName("US-2.02-AC2 giữ chỗ vượt khối lượng Đề án thì từ chối PROGRAM_LIMIT_EXCEEDED; yêu cầu vẫn chờ duyệt")
    void US_2_02_AC2_overTheProgramLimitIsRejected() throws Exception {
        UUID program = effectiveProgram(100_000_000_00L);
        UUID first = createSeries(seriesBody(program, 60_000));
        decide(submitted(first), HOLCO_CHECKER, "APPROVE");

        UUID second = createSeries(seriesBody(program, 50_000));
        UUID approval = submitted(second);
        problem(decide(approval, HOLCO_CHECKER, "APPROVE"), 422, "PROGRAM_LIMIT_EXCEEDED");
        assertThat(approvalStatus(approval)).isEqualTo("PENDING");
        assertThat(seriesStatus(second)).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issuance.limit_ledger_entry WHERE series_id = ?",
                Integer.class, second)).isZero();

        // Từ chối thì series về Nháp
        decide(approval, HOLCO_CHECKER, "REJECT");
        assertThat(seriesStatus(second)).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("Q37 Đề án điều chỉnh tính hạn mức trên cả chuỗi, theo khối lượng của bản đang hiệu lực")
    void limitsSpanTheAmendmentChain() throws Exception {
        UUID original = effectiveProgram(100_000_000_00L);
        decide(submitted(createSeries(seriesBody(original, 60_000))), HOLCO_CHECKER, "APPROVE");

        Map<String, Object> amendment = programBody(150_000_000_00L);
        amendment.put("amendsProgramId", original.toString());
        UUID amended = UUID.fromString((String) body(post("/v1/programs", AGENCY_MAKER, amendment)).get("id"));
        decide(approvalOf("issuance_program", amended), AGENCY_CHECKER, "APPROVE");

        Map<String, Object> limits = limits(amended);
        assertThat(amount(limits, "maxPrincipal")).isEqualTo("15000000000");
        assertThat(amount(limits, "reserved")).isEqualTo("6000000000");
        assertThat(amount(limits, "available")).isEqualTo("9000000000");
        // Series mới không gắn vào bản đã bị thay
        problem(post("/v1/series", HOLCO_MAKER, seriesBody(original, 1000)), 409, "PROGRAM_NOT_EFFECTIVE");
        decide(submitted(createSeries(seriesBody(amended, 80_000))), HOLCO_CHECKER, "APPROVE");
        assertThat(amount(limits(amended), "available")).isEqualTo("1000000000");
    }

    @Test
    void limitsAreForIssuerAndOperatingAgencyOnly() throws Exception {
        UUID program = effectiveProgram(100_000_000_00L);
        problem(mvc.perform(get("/v1/programs/" + program + "/limits").with(INVESTOR)).andReturn(), 403, "ROLE_NOT_ALLOWED");
        assertThat(mvc.perform(get("/v1/programs/" + program + "/limits").with(AGENCY_CHECKER)).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
    }
}
