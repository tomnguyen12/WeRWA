package vn.werwa.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
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
import vn.werwa.governance.api.Parameters;
import vn.werwa.governance.notification.NotificationService;
import vn.werwa.issuance.TestDocuments;
import vn.werwa.platform.OpenApiSchemas;
import vn.werwa.platform.events.EventSchemas;

/** Tham số, lịch ngày nghỉ, thông báo (US-4.11; Q60–Q63). */
class OperationsIT extends IntegrationTest {

    static final String HOLCO = "01a15bc1-f409-7589-aef1-774ceddccf3f";
    static final String AGENCY = "01a15bc1-f401-70b2-9cfa-eb63c4f24ecc";
    static final String INVESTOR_A = "01a15bc1-f40a-7520-aeb5-19bb9d2faaa5";
    static final JsonMapper JSON = JsonMapper.builder().build();

    static RequestPostProcessor as(String subject, String org, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("org_id", org).claim("roles", List.of(role)));
    }

    static final String MAKER_ID = "9a1f0000-0000-4000-8000-000000000001";
    static final String CHECKER_ID = "9a1f0000-0000-4000-8000-000000000002";
    static final RequestPostProcessor MAKER = as(MAKER_ID, HOLCO, "OPERATOR");
    static final RequestPostProcessor CHECKER = as(CHECKER_ID, HOLCO, "OPERATOR");
    static final RequestPostProcessor AGENCY_MAKER = as("9a1f0000-0000-4000-8000-0000000000a1", AGENCY, "OPERATING_AGENCY");
    static final RequestPostProcessor AGENCY_CHECKER = as("9a1f0000-0000-4000-8000-0000000000a2", AGENCY, "OPERATING_AGENCY");
    static final RequestPostProcessor INVESTOR = as("9a1f0000-0000-4000-8000-0000000000d1", INVESTOR_A, "INVESTOR");

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Parameters parameters;

    @Autowired
    NotificationService notifications;

    @Autowired
    io.nats.client.Connection nats;

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

    MvcResult read(String path, RequestPostProcessor who) throws Exception {
        return mvc.perform(get(path).with(who)).andReturn();
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

    static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    UUID approve(UUID approval) throws Exception {
        ok(post("/v1/approvals/" + approval + "/decision", CHECKER, Map.of("decision", "APPROVE")), 200, null);
        return approval;
    }

    // ---------- Tham số ----------

    @Test
    @DisplayName("US-4.11-AC2 đổi tham số phải qua duyệt 4 mắt và có ngày hiệu lực")
    @SuppressWarnings("unchecked")
    void US_4_11_AC2_parameterChangesNeedFourEyesAndAnEffectiveDate() throws Exception {
        MvcResult list = read("/v1/parameters", MAKER);
        ok(list, 200, null);
        assertThat(((List<Map<String, Object>>) body(list).get("items"))).extracting(m -> m.get("key"))
                .contains("distribution.order-quantity-step", "issuance.required-document-types");

        problem(post("/v1/parameters/no.such.key/changes", MAKER, Map.of("value", 1, "effectiveFrom", today().toString(),
                "reason", "thử")), 422, "PARAMETER_UNKNOWN");
        problem(post("/v1/parameters/distribution.order-quantity-step/changes", MAKER, Map.of("value", "mười",
                "effectiveFrom", today().toString(), "reason", "thử")), 422, "PARAMETER_INVALID");
        problem(post("/v1/parameters/distribution.order-quantity-step/changes", MAKER, Map.of("value", 0,
                "effectiveFrom", today().toString(), "reason", "thử")), 422, "PARAMETER_INVALID");
        problem(post("/v1/parameters/issuance.business-day-convention/changes", MAKER, Map.of("value", "PRECEDING",
                "effectiveFrom", today().toString(), "reason", "thử")), 422, "PARAMETER_INVALID");

        // Giữ đúng danh sách mặc định để không ảnh hưởng kiểm thử khác dùng chung DB
        List<String> docs = List.of("TERM_SHEET", "OFFERING_CIRCULAR", "LEGAL_OPINION");
        MvcResult change = post("/v1/parameters/issuance.required-document-types/changes", MAKER,
                Map.of("value", docs, "effectiveFrom", today().toString(), "reason", "chuyển vào bảng tham số"));
        ok(change, 201, "ParameterChange");
        assertThat(body(change).get("status")).isEqualTo("PENDING_APPROVAL");
        assertThat(parameters.stringList("issuance.required-document-types", List.of("FALLBACK"))).containsExactly("FALLBACK");

        UUID approval = UUID.fromString((String) body(change).get("approvalRequestId"));
        problem(post("/v1/approvals/" + approval + "/decision", MAKER, Map.of("decision", "APPROVE")), 403,
                "MAKER_CHECKER_SAME_USER");
        approve(approval);
        assertThat(parameters.stringList("issuance.required-document-types", List.of("FALLBACK"))).isEqualTo(docs);

        // Thay đổi có ngày hiệu lực tương lai: đã duyệt nhưng chưa áp
        MvcResult future = post("/v1/parameters/issuance.business-day-convention/changes", MAKER,
                Map.of("value", "MODIFIED_FOLLOWING", "effectiveFrom", today().plusYears(50).toString(), "reason", "sau"));
        ok(future, 201, "ParameterChange");
        approve(UUID.fromString((String) body(future).get("approvalRequestId")));
        assertThat(parameters.enumValue("issuance.business-day-convention", "FOLLOWING")).isEqualTo("FOLLOWING");
        Map<String, Object> convention = ((List<Map<String, Object>>) body(read("/v1/parameters", MAKER)).get("items"))
                .stream().filter(m -> "issuance.business-day-convention".equals(m.get("key"))).findFirst().orElseThrow();
        assertThat(convention.get("source")).isEqualTo("DEFAULT");
        assertThat((List<?>) convention.get("scheduled")).isNotEmpty();
        assertThat(OpenApiSchemas.validate("Parameter", JSON.writeValueAsString(convention))).isEmpty();
    }

    // ---------- Lịch ngày nghỉ ----------

    UUID vnSeries() throws Exception {
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
        s.put("calendars", List.of("VN"));
        s.put("termsDocumentId", TestDocuments.uploadId(mvc, MAKER, "TERM_SHEET").toString());
        MvcResult series = post("/v1/series", MAKER, s);
        ok(series, 201, null);
        return UUID.fromString((String) body(series).get("id"));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> firstEvent(UUID series) throws Exception {
        return ((List<Map<String, Object>>) body(read("/v1/series/" + series, MAKER)).get("events")).getFirst();
    }

    @Test
    @DisplayName("US-4.11-AC1 nhập ngày nghỉ bù: lịch có hiệu lực cho kỳ chưa chốt, có nhật ký")
    @SuppressWarnings("unchecked")
    void US_4_11_AC1_holidayAdjustmentRecalculatesOpenPeriods() throws Exception {
        MvcResult days = read("/v1/calendars/VN/days?year=2027", INVESTOR);
        ok(days, 200, "CalendarYear");
        assertThat(((List<Map<String, Object>>) body(days).get("holidays"))).anySatisfy(d -> {
            assertThat(d.get("day")).isEqualTo("2027-02-06");
            assertThat(d.get("source")).isEqualTo("RULE");
        });

        UUID series = vnSeries();
        // Kỳ 1 hết 2/9/2027 (thứ Năm, Quốc khánh; nghỉ cả 3/9): trả thứ Hai 6/9
        assertThat(firstEvent(series).get("paymentDate")).isEqualTo("2027-09-06");

        MvcResult request = post("/v1/calendars/VN/adjustments", MAKER,
                Map.of("day", "2027-09-06", "kind", "HOLIDAY", "name", "Nghỉ bù Quốc khánh (thử)"));
        ok(request, 201, "CalendarAdjustment");
        assertThat(firstEvent(series).get("paymentDate")).isEqualTo("2027-09-06");
        approve(UUID.fromString((String) body(request).get("approvalRequestId")));

        Awaitility.await().atMost(Duration.ofSeconds(15))
                .until(() -> "2027-09-07".equals(firstEvent(series).get("paymentDate")));
        assertThat(firstEvent(series).get("recordDate")).isEqualTo("2027-09-01");
        byte[] event = nats.jetStreamManagement().getLastMessage("WERWA", "werwa.governance.calendar.changed.v1").getData();
        assertThat(EventSchemas.validate("werwa.governance.calendar.changed.v1", event)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM governance.audit_log WHERE action = 'SERIES_SCHEDULE_RECALCULATED' "
                + "AND subject_ref = ?", Integer.class, "series/" + series)).isEqualTo(1);
        assertThat(((List<Map<String, Object>>) body(read("/v1/calendars/VN/days?year=2027", INVESTOR)).get("holidays")))
                .anySatisfy(d -> {
                    assertThat(d.get("day")).isEqualTo("2027-09-06");
                    assertThat(d.get("source")).isEqualTo("ADJUSTMENT");
                });
    }

    // ---------- Thông báo ----------

    @Test
    @DisplayName("US-4.11-AC3 sự kiện cảnh báo: người nhận thấy thông báo trong ứng dụng và nhận email")
    @SuppressWarnings("unchecked")
    void US_4_11_AC3_notificationsInAppAndByEmail() throws Exception {
        jdbc.update("UPDATE onboarding.organization SET notification_email = 'ops@holco.test' WHERE id = ?::uuid", HOLCO);
        MvcResult change = post("/v1/parameters/distribution.order-quantity-step/changes", MAKER,
                Map.of("value", 1, "effectiveFrom", today().plusYears(60).toString(), "reason", "thông báo thử"));
        ok(change, 201, null);
        String approvalRef = "approval/" + body(change).get("approvalRequestId");

        MvcResult inbox = read("/v1/notifications?limit=200", CHECKER);
        ok(inbox, 200, null);
        Map<String, Object> n = ((List<Map<String, Object>>) body(inbox).get("items")).stream()
                .filter(i -> approvalRef.equals(i.get("subjectRef"))).findFirst().orElseThrow();
        assertThat(OpenApiSchemas.validate("Notification", JSON.writeValueAsString(n))).isEmpty();
        assertThat(n.get("kind")).isEqualTo("APPROVAL_PENDING");
        assertThat(n.get("read")).isEqualTo(false);
        assertThat(read("/v1/notifications?limit=200", INVESTOR).getResponse().getContentAsString()).doesNotContain(approvalRef);

        MvcResult read = post("/v1/notifications/" + n.get("id") + "/read", CHECKER, null);
        ok(read, 200, "Notification");
        assertThat(body(read).get("read")).isEqualTo(true);
        Map<String, Object> forMaker = ((List<Map<String, Object>>) body(read("/v1/notifications?limit=200", MAKER))
                .get("items")).stream().filter(i -> approvalRef.equals(i.get("subjectRef"))).findFirst().orElseThrow();
        assertThat(forMaker.get("read")).isEqualTo(false);
        problem(post("/v1/notifications/" + n.get("id") + "/read", INVESTOR, null), 404, "NOTIFICATION_NOT_FOUND");

        assertThat(notifications.sendPendingEmails()).isPositive();
        MimeMessage[] mails = SMTP.getReceivedMessages();
        assertThat(mails).anySatisfy(m -> {
            assertThat(m.getAllRecipients()[0].toString()).isEqualTo("ops@holco.test");
            assertThat(m.getSubject()).contains("Có yêu cầu chờ duyệt: PARAMETER_CHANGE");
        });
        assertThat(jdbc.queryForObject("SELECT email_status FROM governance.notification WHERE subject_ref = ?",
                String.class, approvalRef)).isEqualTo("SENT");
    }
}
