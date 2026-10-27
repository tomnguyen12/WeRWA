package vn.werwa.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nats.client.Connection;
import java.time.Duration;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.IntegrationTest;
import vn.werwa.governance.api.Approvals;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.api.AuditVerification;
import vn.werwa.platform.OpenApiSchemas;
import vn.werwa.platform.events.EventSchemas;

/** Duyệt 4 mắt và nhật ký kiểm toán (B4a, US-4.08; Q18–Q20, Q22) với PostgreSQL và NATS thật. */
class GovernanceIT extends IntegrationTest {

    static final String HOLCO = "01a15bc1-f409-7589-aef1-774ceddccf3f";
    static final String UBND = "01a15bc1-f400-7c28-a7a8-d5d4cc19328b";
    static final String MAKER = "5b0e7d1c-0000-4000-8000-000000000001";
    static final String CHECKER = "5b0e7d1c-0000-4000-8000-000000000002";
    static final String UBND_USER = "5b0e7d1c-0000-4000-8000-000000000003";

    @Autowired
    WebApplicationContext context;

    @Autowired
    Approvals approvals;

    @Autowired
    AuditLog audit;

    @Autowired
    AuditVerification verification;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Connection nats;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    static RequestPostProcessor as(String subject, String org, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("org_id", org).claim("roles", List.of(role)));
    }

    UUID request(String subjectRef, Map<String, Object> payload) {
        return AsUser.run(MAKER, HOLCO, List.of("OPERATOR"),
                () -> tx.execute(s -> approvals.request("SERIES_SUBMIT", subjectRef, payload)));
    }

    MvcResult decide(UUID id, RequestPostProcessor who, String decision) throws Exception {
        return mvc.perform(post("/v1/approvals/" + id + "/decision")
                        .with(who)
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"" + decision + "\",\"comment\":\"ok\"}"))
                .andReturn();
    }

    @Test
    @DisplayName("US-4.08-AC1 nội dung đổi sau khi gửi duyệt thì yêu cầu cũ bị hủy, phải gửi lại")
    void US_4_08_AC1_changedContentCancelsThePendingRequest() {
        String subject = "series/" + UUID.randomUUID();
        UUID first = request(subject, Map.of("maxQuantity", 100_000));
        assertThat(request(subject, Map.of("maxQuantity", 100_000))).isEqualTo(first);

        UUID second = request(subject, Map.of("maxQuantity", 120_000));
        assertThat(second).isNotEqualTo(first);
        assertThat(approvals.status(first)).isEqualTo("CANCELLED");
        assertThat(approvals.status(second)).isEqualTo("PENDING");
    }

    @Test
    void checkerOfTheSameOrganizationApprovesAndTheDecisionIsPublished() throws Exception {
        UUID id = request("series/" + UUID.randomUUID(), Map.of("x", 1));

        MvcResult result = decide(id, as(CHECKER, HOLCO, "OPERATOR"), "APPROVE");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(OpenApiSchemas.validate("ApprovalRequest", result.getResponse().getContentAsString())).isEmpty();
        assertThat(approvals.status(id)).isEqualTo("APPROVED");

        byte[] event = nats.jetStreamManagement()
                .getLastMessage("WERWA", "werwa.governance.approval.decided.v1").getData();
        assertThat(EventSchemas.validate("werwa.governance.approval.decided.v1", event)).isEmpty();
        assertThat(new String(event)).contains(id.toString()).contains("APPROVE");
    }

    @Test
    void makerCannotApproveOwnRequest() throws Exception {
        UUID id = request("series/" + UUID.randomUUID(), Map.of("x", 2));
        MvcResult r = decide(id, as(MAKER, HOLCO, "OPERATOR"), "APPROVE");
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(r.getResponse().getContentAsString()).contains("MAKER_CANNOT_APPROVE");
        assertThat(approvals.status(id)).isEqualTo("PENDING");
    }

    @Test
    void anotherOrganizationCannotDecideNorSeeTheQueue() throws Exception {
        UUID id = request("series/" + UUID.randomUUID(), Map.of("x", 3));
        MvcResult r = decide(id, as(UBND_USER, UBND, "ISSUER"), "APPROVE");
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(r.getResponse().getContentAsString()).contains("ORG_SCOPE_VIOLATION");

        String ubndQueue = mvc.perform(get("/v1/approvals").with(as(UBND_USER, UBND, "ISSUER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ubndQueue).doesNotContain(id.toString());

        String holcoQueue = mvc.perform(get("/v1/approvals?limit=200").with(as(CHECKER, HOLCO, "OPERATOR")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(holcoQueue).contains(id.toString());
        for (Object item : (List<?>) JsonMapper.builder().build().readValue(holcoQueue, Map.class).get("items")) {
            assertThat(OpenApiSchemas.validate("ApprovalRequest", JsonMapper.builder().build().writeValueAsString(item)))
                    .isEmpty();
        }
    }

    @Test
    void decidedRequestCannotBeDecidedAgain() throws Exception {
        UUID id = request("series/" + UUID.randomUUID(), Map.of("x", 4));
        assertThat(decide(id, as(CHECKER, HOLCO, "OPERATOR"), "REJECT").getResponse().getStatus()).isEqualTo(200);
        MvcResult again = decide(id, as(CHECKER, HOLCO, "OPERATOR"), "APPROVE");
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(again.getResponse().getContentAsString()).contains("APPROVAL_NOT_PENDING");
    }

    @Test
    @DisplayName("US-4.08-AC2 sửa một dòng nhật ký trực tiếp trong DB thì công cụ kiểm phát hiện chuỗi đứt")
    void US_4_08_AC2_tamperingIsDetected() {
        long seq = tx.execute(s -> {
            audit.record("TEST_A", "test/1", Map.of("v", 1));
            return audit.record("TEST_B", "test/2", Map.of("v", 2));
        });
        assertThat(verification.verify().intact()).isTrue();

        // Ứng dụng không sửa được nhật ký.
        assertThatThrownBy(() -> jdbc.update("UPDATE governance.audit_log SET action = 'X' WHERE seq = ?", seq))
                .hasMessageContaining("chỉ ghi thêm");

        // Người có quyền DB tắt trigger rồi sửa: bộ kiểm vẫn bắt được.
        String original = jdbc.queryForObject("SELECT action FROM governance.audit_log WHERE seq = ?", String.class, seq);
        jdbc.execute("ALTER TABLE governance.audit_log DISABLE TRIGGER audit_log_append_only");
        try {
            jdbc.update("UPDATE governance.audit_log SET action = 'TEST_SUA' WHERE seq = ?", seq);
            AuditVerification.Result result = verification.verify();
            assertThat(result.intact()).isFalse();
            assertThat(result.brokenAtSeq()).isEqualTo(seq);
        } finally {
            jdbc.update("UPDATE governance.audit_log SET action = ? WHERE seq = ?", original, seq);
            jdbc.execute("ALTER TABLE governance.audit_log ENABLE TRIGGER audit_log_append_only");
        }
        assertThat(verification.verify().intact()).isTrue();
    }

    @Test
    void everySuccessfulWriteApiIsAudited() throws Exception {
        UUID id = request("series/" + UUID.randomUUID(), Map.of("x", 5));
        decide(id, as(CHECKER, HOLCO, "OPERATOR"), "APPROVE");
        await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM governance.audit_log WHERE action = 'API:decideApproval' AND subject_ref = ?",
                Integer.class, "POST /v1/approvals/" + id + "/decision") == 1);
    }

    @Test
    void auditLogIsReadableByOperatorAndMatchesTheSpec() throws Exception {
        tx.executeWithoutResult(s -> audit.record("TEST_READ", "test/read", Map.of("v", 1)));
        String body = mvc.perform(get("/v1/audit-log?limit=200").with(as(CHECKER, HOLCO, "OPERATOR")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (Object item : (List<?>) JsonMapper.builder().build().readValue(body, Map.class).get("items")) {
            assertThat(OpenApiSchemas.validate("AuditEntry", JsonMapper.builder().build().writeValueAsString(item)))
                    .isEmpty();
        }
        mvc.perform(get("/v1/audit-log").with(as(UBND_USER, UBND, "ISSUER"))).andExpect(status().isForbidden());
    }

    @Test
    void apiNotYetBuiltAnswers501ProblemJson() throws Exception {
        MvcResult r = mvc.perform(get("/v1/signing-proposals/" + UUID.randomUUID()).with(as(CHECKER, HOLCO, "OPERATOR")))
                .andExpect(status().is(501))
                .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"))
                .andReturn();
        assertThat(OpenApiSchemas.validate("Problem", r.getResponse().getContentAsString())).isEmpty();
    }
}
