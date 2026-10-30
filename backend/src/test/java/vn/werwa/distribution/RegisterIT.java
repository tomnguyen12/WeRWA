package vn.werwa.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import vn.werwa.distribution.register.RegisterAttestationService;
import vn.werwa.onchain.api.contracts.ModularCompliance;
import vn.werwa.onchain.api.contracts.SeriesController;
import vn.werwa.onchain.api.contracts.Token;
import vn.werwa.platform.OpenApiSchemas;

/**
 * VSDC xác nhận sổ sau phát hành với Besu, Safe và nền tảng thật (US-3.05-AC5; Q139–Q147): VSDC từ chối một mục, Holco
 * hủy mục đó qua duyệt 4 mắt, sổ được lập lại, VSDC ký thì series Đang lưu hành và mở chuyển nhượng.
 */
class RegisterIT extends AllocationChainTest {

    /** Mã băm sổ tính độc lập bằng {@code cast} (Foundry), không qua mã của nền tảng. */
    static String castRegisterHash(String controller, long atBlock, Map<String, Long> holdings) throws Exception {
        TreeMap<String, Long> sorted = new TreeMap<>(holdings);
        String wallets = "[" + String.join(",", sorted.keySet()) + "]";
        String quantities = "[" + String.join(",", sorted.values().stream().map(String::valueOf).toList()) + "]";
        String encoded = cast("abi-encode", "f(address,uint64,address[],uint64[])", controller, Long.toString(atBlock),
                wallets, quantities);
        return cast("keccak", encoded);
    }

    static String cast(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "cast";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(new File(".")).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("cast lỗi: " + out);
        }
        return out.toLowerCase();
    }

    Map<String, Object> attestation(UUID series) throws Exception {
        MvcResult r = mvc.perform(get("/v1/series/" + series + "/register-attestation").with(as("vsdc"))).andReturn();
        status(r, 200);
        assertThat(OpenApiSchemas.validate("RegisterAttestation", r.getResponse().getContentAsString())).isEmpty();
        return body(r);
    }

    String allocationStatus(Object id) {
        return jdbc.queryForObject("SELECT status FROM distribution.allocation WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("US-3.05-AC5 VSDC từ chối một mục, Holco hủy qua duyệt 4 mắt, sổ lập lại; VSDC ký thì series Đang lưu hành, mở chuyển nhượng")
    @SuppressWarnings("unchecked")
    void US_3_05_AC5_registrarAttestsAfterCorrection() throws Exception {
        Minted m = mintedSeries();
        UUID series = m.prepared().series();
        String controller = (String) series(series).get("controller_address");
        String walletA = m.a().address();
        String walletB = m.b().address();

        // Q141: mục cuối mint xong thì sổ tự lập, đề nghị ký Safe VSDC
        await().atMost(WAIT).until(() -> jdbc.queryForObject("""
                SELECT count(*) FROM distribution.register_attestation WHERE series_id = ? AND status = 'SIGNING'
                """, Integer.class, series) == 1);
        Map<String, Object> first = attestation(series);
        assertThat(first).containsEntry("holderCount", 2).containsEntry("status", "SIGNING");
        assertThat(((Number) first.get("totalQuantity")).longValue()).isEqualTo(900);
        long atBlock = ((Number) first.get("atBlock")).longValue();
        assertThat(first.get("registerHash")).isEqualTo(castRegisterHash(controller, atBlock,
                Map.of(walletA, 600L, walletB, 300L)));
        assertThat(first.get("registerHash")).isEqualTo(RegisterAttestationService.registerHash(controller, atBlock,
                Map.of(walletA, 600L, walletB, 300L)));
        UUID firstProposal = UUID.fromString((String) first.get("signingProposalId"));
        assertThat(signing.get(firstProposal).safe()).isEqualTo(SAFES.get("vsdc"));
        assertThat(signing.get(firstProposal).basisHash()).isEqualTo(first.get("registerHash"));
        // Đang có lần lập mở thì Holco không lập thêm
        MvcResult pending = post("/v1/series/" + series + "/register-attestation", as("holco"), null);
        status(pending, 409);
        assertThat(body(pending).get("code")).isEqualTo("REGISTER_ATTESTATION_PENDING");

        // Q144: VSDC từ chối, chỉ ra mục B; mục lạ bị từ chối 422
        UUID firstId = UUID.fromString((String) first.get("id"));
        MvcResult bad = post("/v1/register-attestations/" + firstId + "/rejection", as("vsdc"), Map.of("reason", "lệch sổ",
                "items", List.of(Map.of("allocationId", UUID.randomUUID().toString(), "reason", "x"))));
        status(bad, 422);
        assertThat(body(bad).get("code")).isEqualTo("REGISTER_REJECTION_INVALID");
        status(post("/v1/register-attestations/" + firstId + "/rejection", as("holco"), Map.of("reason", "x",
                "items", List.of(Map.of("allocationId", m.allocB().get("id").toString(), "reason", "x")))), 403);
        MvcResult rejected = post("/v1/register-attestations/" + firstId + "/rejection", as("vsdc"), Map.of("reason",
                "Sổ VSDC chưa có hồ sơ chuyển tiền của B", "items", List.of(Map.of("allocationId",
                        m.allocB().get("id").toString(), "reason", "chưa có hồ sơ"))));
        status(rejected, 200);
        assertThat(OpenApiSchemas.validate("RegisterAttestation", rejected.getResponse().getContentAsString())).isEmpty();
        assertThat(body(rejected)).containsEntry("status", "REJECTED");
        assertThat(signing.get(firstProposal).status()).isEqualTo("CANCELLED");
        assertThat(allocationStatus(m.allocB().get("id"))).isEqualTo("NEEDS_CORRECTION");
        assertThat(allocationStatus(m.allocA().get("id"))).isEqualTo("MINTED");

        // Q145: Holco hủy mục B qua duyệt 4 mắt; mục A không cần sửa
        MvcResult notInCorrection = post("/v1/allocations/" + m.allocA().get("id") + "/correction", as("holco"),
                Map.of("action", "BURN", "reason", "x"));
        status(notInCorrection, 409);
        assertThat(body(notInCorrection).get("code")).isEqualTo("ALLOCATION_NOT_IN_CORRECTION");
        MvcResult correction = post("/v1/allocations/" + m.allocB().get("id") + "/correction", as("holco"),
                Map.of("action", "BURN", "reason", "VSDC chỉ ra chưa có hồ sơ chuyển tiền"));
        status(correction, 202);
        assertThat(OpenApiSchemas.validate("ApprovalRef", correction.getResponse().getContentAsString())).isEmpty();
        approve(UUID.fromString((String) body(correction).get("approvalRequestId")), as("holco"));
        await().atMost(WAIT).until(() -> "PAID".equals(allocationStatus(m.allocB().get("id"))));
        assertThat(jdbc.queryForObject("SELECT mint_failure_reason FROM distribution.allocation WHERE id = ?", String.class,
                m.allocB().get("id"))).isEqualTo("CORRECTION_BURNED");
        Token token = calls.load(Token.class, calls.load(SeriesController.class, controller).token().send());
        assertThat(token.balanceOf(walletB).send()).isEqualTo(BigInteger.ZERO);

        // Sổ lập lại chỉ còn A; VSDC ký 2/3
        await().atMost(WAIT).until(() -> "SIGNING".equals(attestation(series).get("status"))
                && !firstId.toString().equals(attestation(series).get("id")));
        Map<String, Object> second = attestation(series);
        assertThat(second).containsEntry("holderCount", 1);
        assertThat(((List<Map<String, Object>>) second.get("holders")).getFirst()).containsEntry("wallet", walletA);
        signBy(UUID.fromString((String) second.get("signingProposalId")), "vsdc");

        // AC5: series Đang lưu hành, mục CONFIRMED, mở chuyển nhượng
        await().atMost(WAIT).until(() -> "ATTESTED".equals(attestation(series).get("status")));
        await().atMost(WAIT).until(() -> "ACTIVE".equals(series(series).get("status")));
        assertThat(allocationStatus(m.allocA().get("id"))).isEqualTo("CONFIRMED");
        ModularCompliance compliance = calls.load(ModularCompliance.class, token.compliance().send());
        assertThat(compliance.canTransfer(walletA, walletB, BigInteger.ONE).send()).isTrue();
        byte[] event = nats.jetStreamManagement().getLastMessage("WERWA", RegisterAttestationService.ATTESTED_EVENT)
                .getData();
        assertThat(vn.werwa.platform.events.EventSchemas.validate(RegisterAttestationService.ATTESTED_EVENT, event))
                .isEmpty();
        assertThat(paymentService.progress(series).step())
                .isEqualTo(vn.werwa.distribution.payment.PaymentService.Step.DONE);
        MvcResult again = post("/v1/series/" + series + "/register-attestation", as("holco"), null);
        status(again, 409);
        assertThat(body(again).get("code")).isEqualTo("REGISTER_NOT_READY");
    }
}
