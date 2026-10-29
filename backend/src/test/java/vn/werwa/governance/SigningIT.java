package vn.werwa.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.governance.api.SigningProposals;
import vn.werwa.governance.signing.SafeTypedData;
import vn.werwa.governance.signing.SigningService;
import vn.werwa.onchain.ChainIntegrationTest;
import vn.werwa.onchain.api.Safes;
import vn.werwa.onchain.signing.TransactionSigner;
import vn.werwa.platform.OpenApiSchemas;

/**
 * Thu chữ ký multisig và gửi giao dịch Safe với Besu và Safe 1.4.1 thật (US-6.04 AC2, AC3; Q106–Q113). Safe dựng bằng
 * chính {@code infra/safe/safe.sh} như devnet: Safe UBND, VSDC, BIDV, Holco 2/3; {@code governance3of3} có owner là Safe
 * UBND, VSDC, Holco. Khóa người ký thử do script sinh, chỉ sống trong thư mục tạm của kiểm thử.
 */
class SigningIT extends ChainIntegrationTest {

    static final Map<String, String> ORGS = Map.of(
            "ubnd", "01a15bc1-f400-7c28-a7a8-d5d4cc19328b",
            "vsdc", "01a15bc1-f403-7f0b-8fb9-a75cdc4b5b00",
            "bidv", "01a15bc1-f404-72f3-86c3-4f3f7c24f440",
            "holco", "01a15bc1-f409-7589-aef1-774ceddccf3f");
    static final Map<String, String> ROLES = Map.of("ubnd", "ISSUER", "vsdc", "REGISTRAR", "bidv", "PAYING_AGENT",
            "holco", "OPERATOR");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Duration WAIT = Duration.ofSeconds(60);
    static final Path OUT;
    static final Map<String, String> SAFES = new HashMap<>();

    static {
        try {
            OUT = Files.createTempDirectory("werwa-safe-");
            Path bootstrap = OUT.resolve("accounts/bootstrap/key");
            Files.createDirectories(bootstrap.getParent());
            Files.writeString(bootstrap, Numeric.toHexStringWithPrefixZeroPadded(Keys.createEcKeyPair().getPrivateKey(), 64));
            String rpc = "http://" + BESU.getHost() + ":" + BESU.getMappedPort(8545);
            ProcessBuilder pb = new ProcessBuilder("bash", "../infra/safe/safe.sh", "up").redirectErrorStream(true);
            pb.environment().putAll(Map.of("DEVNET_OUT", OUT.toString(), "O1A_RPC", rpc, "V1_RPC", rpc));
            Process p = pb.start();
            String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
                throw new IllegalStateException("safe.sh up lỗi:\n" + log);
            }
            Map<?, ?> json = JSON.readValue(Files.readString(OUT.resolve("safe/safes.json")), Map.class);
            ((Map<?, ?>) json.get("safes")).forEach((name, v) ->
                    SAFES.put((String) name, ((String) ((Map<?, ?>) v).get("address")).toLowerCase()));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void safeProperties(DynamicPropertyRegistry registry) {
        SAFES.forEach((name, address) -> registry.add("werwa.safes." + name, () -> address));
        ORGS.forEach((name, org) -> registry.add("werwa.signing.safe-organizations." + name, () -> org));
    }

    static Credentials signer(String org, int i) {
        try {
            return Credentials.create(Files.readString(OUT.resolve("safe/signers/" + org + "-" + i + "/key")).trim());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static RequestPostProcessor as(String org) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", ORGS.get(org))
                .claim("roles", List.of(ROLES.get(org))));
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    SigningProposals proposals;

    @Autowired
    SigningService signing;

    @Autowired
    Safes safes;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // Người ký 1, 2 của mỗi tổ chức được ủy quyền (B5a); người ký 3 là owner Safe nhưng chưa ủy quyền
        for (String org : ORGS.keySet()) {
            for (int i = 1; i <= 2; i++) {
                jdbc.update("""
                        INSERT INTO onboarding.authorized_signer (id, organization_id, user_id, signing_address, scopes,
                            valid_from, authorization_doc_id, created_at)
                        VALUES (?, ?::uuid, ?, ?, ARRAY['ALL'], current_date - 1, ?, now())
                        ON CONFLICT (signing_address) DO NOTHING
                        """, UUID.randomUUID(), ORGS.get(org), UUID.randomUUID(),
                        signer(org, i).getAddress().toLowerCase(), UUID.randomUUID());
            }
        }
    }

    // ---------- tiện ích ----------

    /** Giao dịch Safe vô hại: CALL giá trị 0 tới một địa chỉ ngẫu nhiên, không dữ liệu. */
    SigningProposals.Ref propose(String safe) {
        byte[] random = new byte[20];
        new java.security.SecureRandom().nextBytes(random);
        String target = Numeric.toHexString(random);
        return tx.execute(s -> proposals.create(new SigningProposals.Request("TEST_SAFE_CALL", "test/" + UUID.randomUUID(),
                null, safe, target, "noop", "0x")));
    }

    String sign(byte[] hash, Credentials who) {
        Sign.SignatureData d = Sign.signMessage(hash, who.getEcKeyPair(), false);
        return Numeric.toHexString(concat(d.getR(), d.getS(), d.getV()));
    }

    static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    /** Mã băm người ký của {@code org} cần ký cho đề nghị (Q108). */
    byte[] hashFor(SigningService.Proposal p, String org) {
        if (p.safe().equals(SAFES.get(org))) {
            return SafeTypedData.safeTxHash(CHAIN_ID, p.safe(), p.targetContract(), p.calldata(), p.nonce());
        }
        return SafeTypedData.safeMessageHash(CHAIN_ID, SAFES.get(org),
                SafeTypedData.safeTxData(CHAIN_ID, p.safe(), p.targetContract(), p.calldata(), p.nonce()));
    }

    MvcResult submit(UUID id, String org, Credentials who, String signature) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/v1/signing-proposals/" + id + "/signatures").with(as(org))
                .header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("signer", who.getAddress(), "signature", signature)))).andReturn();
    }

    MvcResult sign(UUID id, String org, int i) throws Exception {
        Credentials who = signer(org, i);
        return submit(id, org, who, sign(hashFor(signing.get(id), org), who));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(MvcResult r) throws Exception {
        return JSON.readValue(r.getResponse().getContentAsString(), Map.class);
    }

    static void ok(MvcResult r, int status) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(OpenApiSchemas.validate("SigningProposal", r.getResponse().getContentAsString())).isEmpty();
    }

    static void problem(MvcResult r, int status, String code) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(body(r).get("code")).isEqualTo(code);
    }

    // ---------- US-6.04 ----------

    @Test
    @DisplayName("US-6.04-AC2 Safe tổ chức 2/3: người ký nộp chữ ký EIP-712, đủ ngưỡng thì gửi và lên khối")
    @SuppressWarnings("unchecked")
    void US_6_04_AC2_organizationSafeExecutesAtThreshold() throws Exception {
        String vsdc = SAFES.get("vsdc");
        long nonce = safes.nonce(vsdc);
        UUID id = propose("vsdc").id();
        SigningService.Proposal p = signing.get(id);
        assertThat(p.status()).isEqualTo("COLLECTING");
        assertThat(p.nonce()).isEqualTo(nonce);

        MvcResult view = mvc.perform(get("/v1/signing-proposals/" + id).with(as("vsdc"))).andReturn();
        ok(view, 200);
        assertThat(((Map<String, Object>) body(view).get("typedData")).get("primaryType")).isEqualTo("SafeTx");

        MvcResult first = sign(id, "vsdc", 1);
        ok(first, 202);
        assertThat(body(first).get("status")).isEqualTo("COLLECTING");
        ok(sign(id, "vsdc", 2), 202);
        await().atMost(WAIT).until(() -> "MINED".equals(signing.get(id).status()));
        assertThat(safes.nonce(vsdc)).isEqualTo(nonce + 1);
        assertThat(signing.get(id).txHash()).startsWith("0x");
    }

    @Test
    @DisplayName("US-6.04-AC2, AC3 Safe liên bên 3/3: đủ 2/3 từng tổ chức thì ghép EIP-1271; chưa đủ ba bên thì không gửi")
    @SuppressWarnings("unchecked")
    void US_6_04_AC3_interPartySafeWaitsForAllParties() throws Exception {
        String gov = SAFES.get("governance3of3");
        long nonce = safes.nonce(gov);
        UUID id = propose("governance3of3").id();
        assertThat(signing.get(id).parties()).extracting(SigningService.Party::safe)
                .containsExactlyInAnyOrder(SAFES.get("ubnd"), SAFES.get("vsdc"), SAFES.get("holco"));

        // typedData theo tổ chức người hỏi: SafeMessage của Safe VSDC
        Map<String, Object> typed = (Map<String, Object>) body(mvc.perform(get("/v1/signing-proposals/" + id)
                .with(as("vsdc"))).andReturn()).get("typedData");
        assertThat(typed.get("primaryType")).isEqualTo("SafeMessage");
        assertThat(((Map<String, Object>) typed.get("domain")).get("verifyingContract")).isEqualTo(SAFES.get("vsdc"));

        for (String org : List.of("ubnd", "vsdc")) {
            ok(sign(id, org, 1), 202);
            ok(sign(id, org, 2), 202);
        }
        // AC3: hai bên đủ, bên thứ ba chưa ký thì không có giao dịch
        assertThat(signing.get(id).status()).isEqualTo("COLLECTING");
        assertThat(jdbc.queryForObject("SELECT chain_tx_id FROM governance.signing_proposal WHERE id = ?", UUID.class, id))
                .isNull();
        MvcResult partial = mvc.perform(get("/v1/signing-proposals/" + id).with(as("holco"))).andReturn();
        assertThat((List<Map<String, Object>>) body(partial).get("parties"))
                .anySatisfy(x -> assertThat(x).containsEntry("safe", SAFES.get("ubnd")).containsEntry("signed", 2));

        ok(sign(id, "holco", 1), 202);
        ok(sign(id, "holco", 2), 202);
        await().atMost(WAIT).until(() -> "MINED".equals(signing.get(id).status()));
        assertThat(safes.nonce(gov)).isEqualTo(nonce + 1);
    }

    @Test
    @DisplayName("US-6.04-AC2 nền tảng kiểm đúng chủ Safe: chữ ký sai, người ký khác tổ chức, chưa ủy quyền, nộp trùng")
    void US_6_04_AC2_signerIsChecked() throws Exception {
        UUID id = propose("ubnd").id();
        SigningService.Proposal p = signing.get(id);
        Credentials ubnd1 = signer("ubnd", 1);
        // Chữ ký trên dữ liệu khác
        problem(submit(id, "ubnd", ubnd1, sign(new byte[32], ubnd1)), 422, "SIGNATURE_INVALID");
        // Người của VSDC không phải một bên của Safe UBND
        problem(submit(id, "vsdc", ubnd1, sign(hashFor(p, "ubnd"), ubnd1)), 403, "SIGNER_NOT_AUTHORIZED");
        // Owner Safe nhưng chưa là người ký được ủy quyền
        Credentials ubnd3 = signer("ubnd", 3);
        problem(submit(id, "ubnd", ubnd3, sign(hashFor(p, "ubnd"), ubnd3)), 403, "SIGNER_NOT_AUTHORIZED");
        // Người ký được ủy quyền của BIDV ký thay UBND: không phải owner Safe UBND
        Credentials bidv1 = signer("bidv", 1);
        problem(submit(id, "ubnd", bidv1, sign(hashFor(p, "ubnd"), bidv1)), 403, "SIGNER_NOT_AUTHORIZED");
        ok(sign(id, "ubnd", 1), 202);
        problem(sign(id, "ubnd", 1), 409, "SIGNATURE_DUPLICATE");
        tx.execute(s -> signing.cancel(id));
    }

    @Test
    @DisplayName("Q110 mỗi Safe một đề nghị mở; đề nghị sau xếp hàng, hủy đề nghị trước thì đề nghị sau nhận nonce")
    void Q110_proposalsQueuePerSafe() throws Exception {
        String bidv = SAFES.get("bidv");
        long nonce = safes.nonce(bidv);
        UUID first = propose("bidv").id();
        UUID second = propose("bidv").id();
        assertThat(signing.get(first).status()).isEqualTo("COLLECTING");
        assertThat(signing.get(second).status()).isEqualTo("QUEUED");
        assertThat(signing.get(second).nonce()).isNull();
        problem(sign(second, "bidv", 1), 409, "SIGNING_PROPOSAL_NOT_COLLECTING");

        MvcResult cancelled = mvc.perform(MockMvcRequestBuilders.post("/v1/signing-proposals/" + first + "/cancel")
                .with(as("holco")).header("Idempotency-Key", "k-" + UUID.randomUUID())).andReturn();
        ok(cancelled, 200);
        assertThat(body(cancelled).get("status")).isEqualTo("CANCELLED");
        assertThat(signing.get(second).status()).isEqualTo("COLLECTING");
        assertThat(signing.get(second).nonce()).isEqualTo(nonce);
        problem(mvc.perform(MockMvcRequestBuilders.post("/v1/signing-proposals/" + second + "/cancel").with(as("bidv"))
                .header("Idempotency-Key", "k-" + UUID.randomUUID())).andReturn(), 403, "ROLE_NOT_ALLOWED");

        // BIDV thấy đề nghị đang chờ mình; UBND không thấy
        assertThat(mvc.perform(get("/v1/signing-proposals").with(as("bidv"))).andReturn().getResponse()
                .getContentAsString()).contains(second.toString());
        assertThat(mvc.perform(get("/v1/signing-proposals").with(as("ubnd"))).andReturn().getResponse()
                .getContentAsString()).doesNotContain(second.toString());
        problem(mvc.perform(get("/v1/signing-proposals/" + second).with(as("ubnd"))).andReturn(), 404,
                "SIGNING_PROPOSAL_NOT_FOUND");
        tx.execute(s -> signing.cancel(second));
    }

    /** Giao dịch Safe gửi bằng khóa thử thay cho khóa relayer (Q15, Q111). */
    @TestConfiguration
    static class Signer {
        @Bean
        @Primary
        TransactionSigner testSigner() {
            return vn.werwa.onchain.TestSigners.operator();
        }
    }
}
