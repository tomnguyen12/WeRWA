package vn.werwa.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.governance.api.SigningProposals;
import vn.werwa.governance.signing.SafeTypedData;
import vn.werwa.governance.signing.SigningService;
import vn.werwa.issuance.series.SeriesDeployment;
import vn.werwa.onchain.ChainIntegrationTest;
import vn.werwa.onchain.signing.TransactionSigner;
import vn.werwa.platform.OpenApiSchemas;

/**
 * Vòng đời series trên chuỗi với Besu, Safe 1.4.1 và nền tảng thật (US-2.07; Q114–Q120): duyệt xong tự có đề nghị ký
 * 3/3, ký đủ thì factory triển khai, indexer đọc thì series sang Đã triển khai; VSDC gắn ISIN qua Safe VSDC thì sang Sẵn
 * sàng.
 */
class SeriesDeployIT extends ChainIntegrationTest {

    static final String AGENCY = "01a15bc1-f401-70b2-9cfa-eb63c4f24ecc";
    static final Map<String, String> ROLES = Map.of("ubnd", "ISSUER", "vsdc", "REGISTRAR", "bidv", "PAYING_AGENT",
            "holco", "OPERATOR");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Duration WAIT = Duration.ofSeconds(90);
    static final Random RANDOM = new Random();

    static RequestPostProcessor as(String org) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", SAFE_ORGS.get(org))
                .claim("roles", List.of(ROLES.get(org))));
    }

    static RequestPostProcessor agency() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", AGENCY)
                .claim("roles", List.of("OPERATING_AGENCY")));
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SigningService signing;

    @Autowired
    SigningProposals proposals;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    io.nats.client.Connection nats;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        authorizeSafeSigners(jdbc);
    }

    // ---------- tiện ích ----------

    MvcResult post(String path, RequestPostProcessor who, Object body) throws Exception {
        var r = MockMvcRequestBuilders.post(path).with(who).header("Idempotency-Key", "k-" + UUID.randomUUID());
        if (body != null) {
            r.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(r).andReturn();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(MvcResult r) throws Exception {
        return JSON.readValue(r.getResponse().getContentAsString(), Map.class);
    }

    static UUID id(MvcResult r) throws Exception {
        return UUID.fromString((String) body(r).get("id"));
    }

    static void status(MvcResult r, int code) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(code);
    }

    UUID approve(UUID approval, RequestPostProcessor checker) throws Exception {
        status(post("/v1/approvals/" + approval + "/decision", checker, Map.of("decision", "APPROVE")), 200);
        return approval;
    }

    /** Series đã qua duyệt 4 mắt của Holco (cạnh 1, 2). */
    UUID approvedSeries() throws Exception {
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
        p.put("documentId", TestDocuments.uploadId(mvc, as("holco"), "HDND_RESOLUTION").toString());
        MvcResult program = post("/v1/programs", agency(), p);
        status(program, 201);
        approve(jdbc.queryForObject("SELECT approval_request_id FROM issuance.issuance_program WHERE id = ?", UUID.class,
                id(program)), agency());

        Map<String, Object> s = new HashMap<>();
        s.put("programId", id(program).toString());
        s.put("seriesCode", "S" + (100000 + RANDOM.nextInt(900000)));
        s.put("currency", "USD");
        s.put("faceValue", Map.of("amountMinor", "100000", "currency", "USD"));
        s.put("issuePricePpm", 1_000_000);
        s.put("maxQuantity", 10_000);
        s.put("couponFrequency", "SEMI_ANNUAL");
        s.put("dayCount", "30_360");
        s.put("issueDate", "2027-03-02");
        s.put("maturityDate", "2030-03-02");
        s.put("calendars", List.of("VN", "NY"));
        s.put("investorClasses", List.of("FOREIGN_INSTITUTION"));
        s.put("termsDocumentId", TestDocuments.uploadId(mvc, as("holco"), "TERM_SHEET").toString());
        s.put("documentIds", List.of(TestDocuments.uploadId(mvc, as("holco"), "OFFERING_CIRCULAR").toString(),
                TestDocuments.uploadId(mvc, as("holco"), "LEGAL_OPINION").toString()));
        MvcResult series = post("/v1/series", as("holco"), s);
        status(series, 201);
        UUID id = id(series);
        MvcResult submitted = post("/v1/series/" + id + "/submit", as("holco"), null);
        status(submitted, 202);
        approve(UUID.fromString((String) body(submitted).get("approvalRequestId")), as("holco"));
        return id;
    }

    Map<String, Object> series(UUID id) {
        return jdbc.queryForMap("""
                SELECT status, isin, controller_address, token_address, signing_proposal_id, isin_proposal_id
                FROM issuance.series WHERE id = ?
                """, id);
    }

    String sign(byte[] hash, Credentials who) {
        Sign.SignatureData d = Sign.signMessage(hash, who.getEcKeyPair(), false);
        byte[] out = new byte[65];
        System.arraycopy(d.getR(), 0, out, 0, 32);
        System.arraycopy(d.getS(), 0, out, 32, 32);
        out[64] = d.getV()[0];
        return Numeric.toHexString(out);
    }

    /** Hai người ký được ủy quyền của từng tổ chức ký đề nghị (Q108). */
    void signBy(UUID proposal, String... orgs) throws Exception {
        for (String org : orgs) {
            for (int i = 1; i <= 2; i++) {
                SigningService.Proposal p = signing.get(proposal);
                String orgSafe = SAFES.get(org);
                byte[] hash = p.safe().equals(orgSafe)
                        ? SafeTypedData.safeTxHash(CHAIN_ID, p.safe(), p.targetContract(), p.calldata(), p.nonce())
                        : SafeTypedData.safeMessageHash(CHAIN_ID, orgSafe,
                                SafeTypedData.safeTxData(CHAIN_ID, p.safe(), p.targetContract(), p.calldata(), p.nonce()));
                Credentials who = safeSigner(org, i);
                status(post("/v1/signing-proposals/" + proposal + "/signatures", as(org),
                        Map.of("signer", who.getAddress(), "signature", sign(hash, who))), 202);
            }
        }
    }

    /** ISIN hợp lệ ngẫu nhiên: chữ số kiểm tra tính bằng chính bộ kiểm ISO 6166. */
    static String isin() {
        String alnum = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder b = new StringBuilder("VN");
        for (int i = 0; i < 9; i++) {
            b.append(alnum.charAt(RANDOM.nextInt(alnum.length())));
        }
        for (int d = 0; d <= 9; d++) {
            if (SeriesDeployment.isValidIsin(b + Integer.toString(d))) {
                return b + Integer.toString(d);
            }
        }
        throw new IllegalStateException();
    }

    /** Series đã triển khai: duyệt, ký 3/3, chờ indexer. */
    UUID deployedSeries() throws Exception {
        UUID id = approvedSeries();
        await().atMost(WAIT).until(() -> series(id).get("signing_proposal_id") != null);
        UUID proposal = (UUID) series(id).get("signing_proposal_id");
        signBy(proposal, "ubnd", "vsdc", "holco");
        await().atMost(WAIT).until(() -> "DEPLOYED".equals(series(id).get("status")));
        return id;
    }

    // ---------- US-2.07 ----------

    @Test
    @DisplayName("US-2.07-AC1 UBND, VSDC, Holco ký đủ 3/3 thì series sang Đã triển khai, lưu địa chỉ hợp đồng")
    void US_2_07_AC1_threePartySignaturesDeploy() throws Exception {
        UUID id = approvedSeries();
        await().atMost(WAIT).until(() -> series(id).get("signing_proposal_id") != null);
        UUID proposal = (UUID) series(id).get("signing_proposal_id");
        SigningService.Proposal p = signing.get(proposal);
        assertThat(p.safe()).isEqualTo(SAFES.get("governance3of3"));
        assertThat(p.functionName()).isEqualTo("deploySeries");

        // Hai bên chưa đủ: chưa triển khai (US-6.04-AC3)
        signBy(proposal, "ubnd", "vsdc");
        assertThat(series(id).get("status")).isEqualTo("PENDING_SIGNATURES");
        // Đề nghị còn mở thì không tạo lại được
        MvcResult again = post("/v1/series/" + id + "/deploy", as("holco"), null);
        status(again, 409);
        assertThat(body(again).get("code")).isEqualTo("SERIES_DEPLOY_PENDING");

        signBy(proposal, "holco");
        await().atMost(WAIT).until(() -> "DEPLOYED".equals(series(id).get("status")));
        Map<String, Object> s = series(id);
        assertThat((String) s.get("controller_address")).startsWith("0x");
        assertThat((String) s.get("token_address")).startsWith("0x");
        MvcResult view = mvc.perform(get("/v1/series/" + id).with(as("vsdc"))).andReturn();
        assertThat(body(view).get("controllerAddress")).isEqualTo(s.get("controller_address"));
    }

    @Test
    @DisplayName("US-2.07-AC2 VSDC nhập ISIN đúng ISO 6166 thì series sang Sẵn sàng; nhập lần hai bị từ chối")
    void US_2_07_AC2_isinMakesSeriesReady() throws Exception {
        UUID id = deployedSeries();
        MvcResult bad = post("/v1/series/" + id + "/isin", as("vsdc"), Map.of("isin", "US0378331006".substring(0, 11) + "9"));
        status(bad, 422);
        assertThat(body(bad).get("code")).isEqualTo("ISIN_INVALID");
        assertThat(post("/v1/series/" + id + "/isin", as("holco"), Map.of("isin", isin())).getResponse().getStatus())
                .isEqualTo(403);

        String isin = isin();
        MvcResult ok = post("/v1/series/" + id + "/isin", as("vsdc"), Map.of("isin", isin));
        status(ok, 202);
        assertThat(OpenApiSchemas.validate("SigningProposalRef", ok.getResponse().getContentAsString())).isEmpty();
        UUID proposal = UUID.fromString((String) body(ok).get("signingProposalId"));
        assertThat(signing.get(proposal).safe()).isEqualTo(SAFES.get("vsdc"));
        // Đề nghị gắn ISIN đang mở: không tạo đề nghị thứ hai
        status(post("/v1/series/" + id + "/isin", as("vsdc"), Map.of("isin", isin())), 409);

        signBy(proposal, "vsdc");
        await().atMost(WAIT).until(() -> "READY".equals(series(id).get("status")));
        assertThat(series(id).get("isin")).isEqualTo(isin);
        MvcResult second = post("/v1/series/" + id + "/isin", as("vsdc"), Map.of("isin", isin()));
        status(second, 409);
        assertThat(body(second).get("code")).isEqualTo("ISIN_ALREADY_ASSIGNED");
    }

    @Test
    @DisplayName("US-2.07-AC3 chuyển không có trong sơ đồ bị từ chối ở API và trên chuỗi")
    void US_2_07_AC3_illegalTransitionsRejectedBothSides() throws Exception {
        UUID id = deployedSeries();
        // API: series đã triển khai không tạo lại đề nghị triển khai
        MvcResult deployAgain = post("/v1/series/" + id + "/deploy", as("holco"), null);
        status(deployAgain, 409);
        assertThat(body(deployAgain).get("code")).isEqualTo("SERIES_NOT_READY");

        // Trên chuỗi: gọi thẳng setIsin với ISIN sai qua Safe VSDC; hợp đồng từ chối khi chạy thử nên đề nghị FAILED
        String controller = (String) series(id).get("controller_address");
        String valid = isin();
        String bad = valid.substring(0, 11) + (char) ('0' + (valid.charAt(11) - '0' + 1) % 10);
        String calldata = org.web3j.abi.FunctionEncoder.encode(new org.web3j.abi.datatypes.Function("setIsin",
                List.of(new org.web3j.abi.datatypes.generated.Bytes12(bad.getBytes(java.nio.charset.StandardCharsets.US_ASCII))),
                List.of()));
        UUID proposal = tx.execute(s -> proposals.create(new SigningProposals.Request("SERIES_ISIN", "series/" + id, null,
                "vsdc", controller, "setIsin", calldata))).id();
        signBy(proposal, "vsdc");
        await().atMost(WAIT).until(() -> "FAILED".equals(signing.get(proposal).status()));
        assertThat(signing.get(proposal).failureReason()).isNotBlank();
        assertThat(series(id).get("status")).isEqualTo("DEPLOYED");
        assertThat(series(id).get("isin")).isNull();
    }

    @Test
    @DisplayName("US-2.07-AC4 mỗi lần chuyển trạng thái có dòng nhật ký và sự kiện series.state_changed")
    void US_2_07_AC4_everyTransitionIsAuditedAndPublished() throws Exception {
        UUID id = deployedSeries();
        await().atMost(WAIT).until(() -> jdbc.queryForObject("""
                SELECT count(*) FROM governance.audit_log WHERE action = 'SERIES_STATE_CHANGED' AND subject_ref = ?
                """, Integer.class, "series/" + id) >= 1);
        byte[] event = nats.jetStreamManagement().getLastMessage("WERWA", "werwa.issuance.series.state_changed.v1").getData();
        assertThat(vn.werwa.platform.events.EventSchemas.validate("werwa.issuance.series.state_changed.v1", event)).isEmpty();
        assertThat(new String(event)).contains("\"to\":\"DEPLOYED\"").contains("\"from\":\"PENDING_SIGNATURES\"");
    }

    /** Giao dịch Safe gửi bằng khóa thử (Q15). */
    @TestConfiguration
    static class Signer {
        @Bean
        @Primary
        TransactionSigner testSigner() {
            return vn.werwa.onchain.TestSigners.operator();
        }
    }
}
