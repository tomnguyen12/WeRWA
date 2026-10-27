package vn.werwa.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.nats.client.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.IntegrationTest;
import vn.werwa.platform.OpenApiSchemas;
import vn.werwa.platform.events.EventSchemas;

/** Onboarding ngoài chuỗi (B5a): KYB, người ký, KYC, sàng lọc, loại nhà đầu tư; PostgreSQL và NATS thật. */
class OnboardingIT extends IntegrationTest {

    static final String HOLCO = "01a15bc1-f409-7589-aef1-774ceddccf3f";
    static final String SYNDICATE = "01a15bc1-f402-7188-aa1d-f504e6afe7e1";
    static final String MAKER = "6c1f0000-0000-4000-8000-000000000001";
    static final String CHECKER = "6c1f0000-0000-4000-8000-000000000002";
    static final String SYNDICATE_USER = "6c1f0000-0000-4000-8000-000000000003";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    WebApplicationContext context;

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

    MvcResult post(String path, RequestPostProcessor who, Object body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .with(who)
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andReturn();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(MvcResult r) throws Exception {
        return JSON.readValue(r.getResponse().getContentAsString(), Map.class);
    }

    static void matches(String schema, MvcResult r) throws Exception {
        assertThat(OpenApiSchemas.validate(schema, r.getResponse().getContentAsString())).isEmpty();
    }

    UUID createOrg(String legalName, String role) throws Exception {
        MvcResult r = post("/v1/organizations", as(MAKER, HOLCO, "OPERATOR"), Map.of(
                "legalName", legalName, "registrationNo", "REG-" + UUID.randomUUID(), "country", "SG",
                "roles", List.of(role)));
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        matches("Organization", r);
        assertThat(body(r).get("kybStatus")).isEqualTo("PENDING_REVIEW");
        return UUID.fromString((String) body(r).get("id"));
    }

    UUID approvedInvestor(String legalName) throws Exception {
        UUID org = createOrg(legalName, "INVESTOR");
        assertThat(kyb(org, as(CHECKER, HOLCO, "OPERATOR"), "APPROVE").getResponse().getStatus()).isEqualTo(200);
        return org;
    }

    MvcResult kyb(UUID org, RequestPostProcessor who, String decision) throws Exception {
        return post("/v1/organizations/" + org + "/kyb-decision", who, Map.of("decision", decision));
    }

    MvcResult openKyc(UUID org) throws Exception {
        return post("/v1/kyc-cases", as(SYNDICATE_USER, SYNDICATE, "SYNDICATE"), Map.of("organizationId", org.toString()));
    }

    MvcResult decideKyc(UUID kycCase, Map<String, Object> decision) throws Exception {
        return post("/v1/kyc-cases/" + kycCase + "/decision", as(CHECKER, HOLCO, "OPERATOR"), decision);
    }

    @Test
    @DisplayName("US-1.01-AC1 người lập nhập hồ sơ, người duyệt khác duyệt: KYB_APPROVED, phát sự kiện, nhật ký ghi cả hai người")
    void US_1_01_AC1_kybApprovedByAnotherUser() throws Exception {
        UUID org = createOrg("UBND thử " + UUID.randomUUID(), "ISSUER");

        MvcResult r = kyb(org, as(CHECKER, HOLCO, "OPERATOR"), "APPROVE");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        matches("Organization", r);
        assertThat(body(r).get("kybStatus")).isEqualTo("KYB_APPROVED");

        byte[] event = nats.jetStreamManagement()
                .getLastMessage("WERWA", "werwa.onboarding.organization.kyb_approved.v1").getData();
        assertThat(EventSchemas.validate("werwa.onboarding.organization.kyb_approved.v1", event)).isEmpty();
        assertThat(new String(event)).contains(org.toString());

        List<String> actors = jdbc.queryForList("""
                SELECT l.actor_subject FROM governance.audit_log l
                JOIN governance.approval_request a ON l.subject_ref = 'approval/' || a.id
                WHERE a.subject_ref = ? AND l.action IN ('APPROVAL_REQUESTED', 'APPROVAL_APPROVED') ORDER BY l.seq
                """, String.class, "organization/" + org);
        assertThat(actors).containsExactly(MAKER, CHECKER);
    }

    @Test
    @DisplayName("US-1.01-AC2 người lập tự duyệt hồ sơ của mình thì 403 MAKER_CHECKER_SAME_USER")
    void US_1_01_AC2_makerCannotDecideOwnKyb() throws Exception {
        UUID org = createOrg("Tổ chức thử " + UUID.randomUUID(), "SYNDICATE");
        MvcResult r = kyb(org, as(MAKER, HOLCO, "OPERATOR"), "APPROVE");
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        matches("Problem", r);
        assertThat(body(r).get("code")).isEqualTo("MAKER_CHECKER_SAME_USER");
    }

    @Test
    void kybDecidedThroughTheGenericApprovalEndpointIsAppliedToo() throws Exception {
        UUID org = createOrg("Tổ chức thử " + UUID.randomUUID(), "CUSTODIAN");
        UUID approval = jdbc.queryForObject(
                "SELECT kyb_approval_id FROM onboarding.organization WHERE id = ?", UUID.class, org);
        assertThat(post("/v1/approvals/" + approval + "/decision", as(CHECKER, HOLCO, "OPERATOR"),
                Map.of("decision", "APPROVE")).getResponse().getStatus()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(20)).until(() -> "KYB_APPROVED".equals(jdbc.queryForObject(
                "SELECT kyb_status FROM onboarding.organization WHERE id = ?", String.class, org)));
    }

    @Test
    void usersOfAnOrganizationGetRolesOnlyAfterKyb() throws Exception {
        UUID org = createOrg("Tổ chức thử " + UUID.randomUUID(), "CUSTODIAN");
        String user = UUID.randomUUID().toString();
        mvc.perform(get("/v1/organizations/" + org).with(as(user, org.toString(), "CUSTODIAN")))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("ORGANIZATION_UNKNOWN"));
        kyb(org, as(CHECKER, HOLCO, "OPERATOR"), "APPROVE");
        MvcResult own = mvc.perform(get("/v1/organizations/" + org).with(as(user, org.toString(), "CUSTODIAN")))
                .andReturn();
        assertThat(own.getResponse().getStatus()).isEqualTo(200);
        matches("Organization", own);
    }

    @Test
    @DisplayName("US-1.01-AC3 người ký có hiệu lực từ valid_from; địa chỉ trùng người ký khác bị 409")
    void US_1_01_AC3_signersHaveUniqueAddresses() throws Exception {
        UUID approved = approvedInvestor("Tổ chức ký " + UUID.randomUUID());
        String address = "0x" + UUID.randomUUID().toString().replace("-", "") + "abcdefab";
        Map<String, Object> signer = Map.of("userId", UUID.randomUUID().toString(), "signingAddress", address,
                "scopes", List.of("GOVERNANCE"), "validFrom", "2026-11-01",
                "authorizationDocId", UUID.randomUUID().toString());

        MvcResult r = post("/v1/organizations/" + approved + "/signers", as(MAKER, HOLCO, "OPERATOR"), signer);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        matches("AuthorizedSigner", r);

        UUID other = approvedInvestor("Tổ chức ký khác " + UUID.randomUUID());
        MvcResult dup = post("/v1/organizations/" + other + "/signers", as(MAKER, HOLCO, "OPERATOR"), signer);
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(dup).get("code")).isEqualTo("SIGNER_ADDRESS_TAKEN");

        UUID pending = createOrg("Chưa KYB " + UUID.randomUUID(), "INVESTOR");
        MvcResult notApproved = post("/v1/organizations/" + pending + "/signers", as(MAKER, HOLCO, "OPERATOR"),
                Map.of("userId", UUID.randomUUID().toString(), "signingAddress", "0x" + "b".repeat(40),
                        "scopes", List.of("GOVERNANCE"), "validFrom", "2026-11-01",
                        "authorizationDocId", UUID.randomUUID().toString()));
        assertThat(body(notApproved).get("code")).isEqualTo("ORGANIZATION_NOT_APPROVED");
    }

    @Test
    @DisplayName("US-1.02-AC1, US-5.04-AC3 mở hồ sơ KYC gọi sàng lọc và lưu kết quả nguyên văn")
    void US_1_02_AC1_screeningIsCalledAndStoredVerbatim() throws Exception {
        UUID investor = approvedInvestor("Quỹ đầu tư sạch " + UUID.randomUUID());
        MvcResult r = openKyc(investor);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        matches("KycCase", r);
        assertThat(body(r).get("status")).isEqualTo("OPEN");

        UUID kycCase = UUID.fromString((String) body(r).get("id"));
        String stored = jdbc.queryForObject("SELECT screening_result::text FROM onboarding.kyc_case WHERE id = ?",
                String.class, kycCase);
        String ref = jdbc.queryForObject("SELECT provider_ref FROM onboarding.kyc_case WHERE id = ?", String.class, kycCase);
        String logged = jdbc.queryForObject("""
                SELECT raw_response::text FROM integration.outbound_message
                WHERE counterparty = 'KYC' AND raw_response->>'reference' = ?
                """, String.class, ref);
        assertThat(JSON.readValue(stored, Map.class)).isEqualTo(JSON.readValue(logged, Map.class));
    }

    @Test
    @DisplayName("US-1.02-AC2, US-5.04-AC2 trùng cấm vận: hồ sơ cần xem xét, kết luận đạt phải ghi lý do")
    void US_1_02_AC2_sanctionsHitNeedsReviewAndReason() throws Exception {
        UUID investor = approvedInvestor("SANCTIONED Holdings " + UUID.randomUUID());
        MvcResult r = openKyc(investor);
        assertThat(body(r).get("status")).isEqualTo("NEEDS_REVIEW");
        @SuppressWarnings("unchecked")
        Map<String, Object> screening = (Map<String, Object>) body(r).get("screeningResult");
        assertThat(screening.get("sanctionsHit")).isEqualTo(true);

        UUID kycCase = UUID.fromString((String) body(r).get("id"));
        MvcResult noReason = decideKyc(kycCase, Map.of("decision", "APPROVE", "investorClass", "FOREIGN_INSTITUTION"));
        assertThat(noReason.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(noReason).get("code")).isEqualTo("REASON_REQUIRED");

        MvcResult withReason = decideKyc(kycCase, Map.of("decision", "APPROVE", "investorClass", "FOREIGN_INSTITUTION",
                "reason", "Trùng tên, đã đối chiếu số đăng ký: không phải cùng tổ chức"));
        assertThat(withReason.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(withReason).get("status")).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("US-1.02-AC3 kết luận đạt: hạn hiệu lực theo tham số, phát investor.kyc_approved")
    void US_1_02_AC3_approvalSetsExpiryAndPublishes() throws Exception {
        UUID investor = approvedInvestor("Quỹ VIFC " + UUID.randomUUID());
        UUID kycCase = UUID.fromString((String) body(openKyc(investor)).get("id"));
        Instant before = Instant.now();

        MvcResult r = decideKyc(kycCase, Map.of("decision", "APPROVE", "investorClass", "VIFC_MEMBER"));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        matches("KycCase", r);
        Instant expires = Instant.parse((String) body(r).get("expiresAt"));
        assertThat(expires).isBetween(before.plus(365, ChronoUnit.DAYS).minusSeconds(5),
                Instant.now().plus(365, ChronoUnit.DAYS).plusSeconds(5));

        byte[] event = nats.jetStreamManagement()
                .getLastMessage("WERWA", "werwa.onboarding.investor.kyc_approved.v1").getData();
        assertThat(EventSchemas.validate("werwa.onboarding.investor.kyc_approved.v1", event)).isEmpty();
        assertThat(new String(event)).contains(investor.toString()).contains("VIFC_MEMBER");
        assertThat(jdbc.queryForObject("SELECT investor_class FROM onboarding.investor_profile WHERE organization_id = ?",
                String.class, investor)).isEqualTo("VIFC_MEMBER");
    }

    @Test
    @DisplayName("US-1.03-AC1 chỉ chọn được loại nhà đầu tư có trong danh mục (phần ngoài chuỗi)")
    void US_1_03_AC1_unknownInvestorClassIsRejected() throws Exception {
        UUID investor = approvedInvestor("Quỹ thử " + UUID.randomUUID());
        UUID kycCase = UUID.fromString((String) body(openKyc(investor)).get("id"));
        MvcResult r = decideKyc(kycCase, Map.of("decision", "APPROVE", "investorClass", "RETAIL"));
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(r).get("code")).isEqualTo("INVESTOR_CLASS_UNKNOWN");
    }

    @Test
    void openerCannotDecideOwnKycCase() throws Exception {
        UUID investor = approvedInvestor("Quỹ tự duyệt " + UUID.randomUUID());
        MvcResult opened = post("/v1/kyc-cases", as(CHECKER, HOLCO, "OPERATOR"), Map.of("organizationId", investor.toString()));
        UUID kycCase = UUID.fromString((String) body(opened).get("id"));
        MvcResult r = decideKyc(kycCase, Map.of("decision", "APPROVE", "investorClass", "VIFC_MEMBER"));
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(r).get("code")).isEqualTo("MAKER_CHECKER_SAME_USER");
    }

    @Test
    void kycOnlyForInvestorOrganizations() throws Exception {
        MvcResult r = openKyc(UUID.fromString(SYNDICATE));
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(r).get("code")).isEqualTo("ORGANIZATION_NOT_INVESTOR");
    }

    @Test
    void walletApisAnswer501UntilB5b() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/wallet-challenges")
                        .with(as(UUID.randomUUID().toString(), "01a15bc1-f40a-7520-aeb5-19bb9d2faaa5", "INVESTOR"))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"0x" + "c".repeat(40) + "\"}"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(501));
    }
}
