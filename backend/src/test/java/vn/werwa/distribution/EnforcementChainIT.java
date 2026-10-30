package vn.werwa.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import vn.werwa.distribution.payout.CouponCalculator;
import vn.werwa.distribution.payout.PayoutService;
import vn.werwa.governance.enforcement.EnforcementSigning;
import vn.werwa.onchain.api.contracts.SeriesController;
import vn.werwa.onchain.api.contracts.Token;
import vn.werwa.platform.OpenApiSchemas;

/**
 * Phong tỏa, giải tỏa, chuyển cưỡng chế với Besu, Safe 2/2 và nền tảng thật (US-4.02-AC2, AC3; Q148–Q154). Series Đang
 * lưu hành: A giữ 600, B giữ 300.
 */
class EnforcementChainIT extends AllocationChainTest {

    @Autowired
    PayoutService payouts;

    Map<String, Object> order(UUID id) throws Exception {
        MvcResult r = mvc.perform(get("/v1/enforcement-orders/" + id).with(as("vsdc"))).andReturn();
        status(r, 200);
        assertThat(OpenApiSchemas.validate("EnforcementOrder", r.getResponse().getContentAsString())).isEmpty();
        return body(r);
    }

    UUID proposalOf(UUID order) throws Exception {
        await().atMost(WAIT).until(() -> order(order).get("signingProposalId") != null);
        return UUID.fromString((String) order(order).get("signingProposalId"));
    }

    @Test
    @DisplayName("US-4.02-AC2, AC3 VSDC và Holco ký 2/2: phong tỏa đúng số lượng, ghi mã băm căn cứ; coupon phần phong tỏa bị giữ")
    void US_4_02_AC2_AC3_freezeUnfreezeForcedTransfer() throws Exception {
        Minted m = activeSeries();
        UUID series = m.prepared().series();
        String controller = (String) series(series).get("controller_address");
        Token token = calls.load(Token.class, calls.load(SeriesController.class, controller).token().send());

        // Phong tỏa A 200: VSDC ký một mình chưa đủ 2/2
        UUID freeze = approvedOrder("FREEZE", series, m.a(), null, 200);
        UUID proposal = proposalOf(freeze);
        assertThat(signing.get(proposal).safe()).isEqualTo(SAFES.get("enforcement2of2"));
        assertThat(signing.get(proposal).basisHash()).isEqualTo(order(freeze).get("basisHash"));
        assertThat(signing.get(proposal).functionName()).isEqualTo("freeze");
        MvcResult pending = post("/v1/enforcement-orders/" + freeze + "/signing-proposal", as("holco"), null);
        status(pending, 409);
        assertThat(body(pending).get("code")).isEqualTo("ENFORCEMENT_SIGNING_PENDING");
        signBy(proposal, "vsdc");
        assertThat(order(freeze).get("status")).isEqualTo("PENDING_SIGNATURES");
        assertThat(token.getFrozenTokens(m.a().address()).send()).isEqualTo(BigInteger.ZERO);
        signBy(proposal, "holco");
        await().atMost(WAIT).until(() -> "APPLIED".equals(order(freeze).get("status")));
        assertThat(order(freeze).get("appliedTxHash")).isNotNull();
        assertThat(token.getFrozenTokens(m.a().address()).send()).isEqualTo(BigInteger.valueOf(200));
        byte[] event = nats.jetStreamManagement().getLastMessage("WERWA", EnforcementSigning.APPLIED_EVENT).getData();
        assertThat(vn.werwa.platform.events.EventSchemas.validate(EnforcementSigning.APPLIED_EVENT, event)).isEmpty();

        // AC3: bảng đọc có phần phong tỏa; danh sách chốt lấy từ bảng đọc thì dòng phong tỏa bị giữ lại
        await().atMost(WAIT).until(() -> jdbc.queryForObject("""
                SELECT frozen FROM distribution.holding_view WHERE series_id = ? AND wallet_address = ?
                """, Long.class, series, m.a().address()) == 200L);
        UUID snapshot = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO distribution.record_snapshot (id, series_id, period_no, record_block, register_hash, holder_count,
                    total_quantity, created_at)
                SELECT ?, ?, 1, max(last_block), ?, count(*), sum(balance), now() FROM distribution.holding_view
                WHERE series_id = ? AND balance > 0
                """, snapshot, series, "0x" + "ef".repeat(32), series);
        jdbc.update("""
                INSERT INTO distribution.snapshot_holding (record_snapshot_id, wallet_id, investor_org_id, quantity, frozen_quantity)
                SELECT ?, wallet_id, wallet_org_id, balance, frozen FROM distribution.holding_view
                WHERE series_id = ? AND balance > 0
                """, snapshot, series);
        CouponCalculator.Result coupon = tx.execute(s -> payouts.calculate(series, 1));
        List<CouponCalculator.Line> withheld = coupon.lines().stream().filter(l -> "FROZEN".equals(l.withheldReason()))
                .toList();
        assertThat(withheld).hasSize(1);
        assertThat(withheld.getFirst().quantity()).isEqualTo(200);
        assertThat(withheld.getFirst().walletId()).isEqualTo(m.a().wallet());

        // Giải tỏa 500 vượt phần phong tỏa: FAILED, phải là lệnh mới
        UUID tooMuch = approvedOrder("UNFREEZE", series, m.a(), null, 500);
        await().atMost(WAIT).until(() -> "FAILED".equals(order(tooMuch).get("status")));
        assertThat(order(tooMuch)).containsEntry("failureReason", "EXCEEDS_FROZEN");
        MvcResult notSignable = post("/v1/enforcement-orders/" + tooMuch + "/signing-proposal", as("holco"), null);
        status(notSignable, 409);
        assertThat(body(notSignable).get("code")).isEqualTo("ENFORCEMENT_NOT_SIGNABLE");

        // Chuyển cưỡng chế A→B 100 (ví nhận đã xác minh định danh)
        UUID transfer = approvedOrder("FORCED_TRANSFER", series, m.a(), m.b(), 100);
        UUID transferProposal = proposalOf(transfer);
        assertThat(signing.get(transferProposal).functionName()).isEqualTo("forcedTransfer");
        signBy(transferProposal, "vsdc", "holco");
        await().atMost(WAIT).until(() -> "APPLIED".equals(order(transfer).get("status")));
        assertThat(token.balanceOf(m.a().address()).send()).isEqualTo(BigInteger.valueOf(500));
        assertThat(token.balanceOf(m.b().address()).send()).isEqualTo(BigInteger.valueOf(400));
    }
}
