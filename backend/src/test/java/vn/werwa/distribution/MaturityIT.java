package vn.werwa.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import vn.werwa.distribution.payment.SupplyCheck;
import vn.werwa.distribution.payout.DebtServiceRunner;
import vn.werwa.integration.bank.SimulatedPayingAgent;
import vn.werwa.issuance.api.DebtServices;
import vn.werwa.onchain.api.contracts.ModularCompliance;
import vn.werwa.onchain.api.contracts.SeriesController;
import vn.werwa.onchain.api.contracts.Token;

/**
 * Đáo hạn, hủy trái phiếu, đóng series với Besu, Safe và nền tảng thật (US-2.10-AC2, AC3; Q163–Q170). Devnet không đẩy
 * được thời gian khối nên series có ngày phát hành và đáo hạn đã qua. A 600, B 300 trong đó 100 bị phong tỏa.
 */
class MaturityIT extends AllocationChainTest {

    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    DebtServiceRunner runner;

    @Autowired
    DebtServices debts;

    @Autowired
    SimulatedPayingAgent agent;

    @Autowired
    SupplyCheck supply;

    /** Xác nhận chi có chữ ký BIDV cho mọi dòng của lệnh chi, như giả lập gửi. */
    @SuppressWarnings("unchecked")
    MvcResult confirmAll(UUID event) throws Exception {
        String payload = (String) agent.submitted(event.toString()).getFirst().get("payload");
        List<Map<String, Object>> lines = (List<Map<String, Object>>) JSON.readValue(payload, Map.class).get("lines");
        List<Map<String, Object>> out = lines.stream().map(l -> Map.<String, Object>of("lineRef", l.get("lineRef"),
                "amount", Map.of("amountMinor", l.get("amountMinor"), "currency", "USD"), "status", "PAID",
                "bankRef", "BIDVP" + l.get("lineRef").toString().substring(0, 8))).toList();
        byte[] raw = JSON.writeValueAsString(Map.of("instructionRef", event.toString(), "lines", out))
                .getBytes(StandardCharsets.UTF_8);
        return mvc.perform(MockMvcRequestBuilders.post("/v1/inbound/bidv/payout-confirmations")
                .header("X-Bank-Signature", bidvSign(raw)).header("Idempotency-Key", "p-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(raw)).andReturn();
    }

    String debtStatus(UUID event) {
        return debts.get(event).orElseThrow().status();
    }

    @Test
    @DisplayName("US-2.10-AC2, AC3 IPA xác nhận chi gốc thì mature; hủy số dư không phong tỏa; giải tỏa thì hủy nốt, tổng cung 0 thì đóng series")
    void US_2_10_AC2_AC3_matureRedeemAndClose() throws Exception {
        LocalDate today = LocalDate.now(VN);
        issueDate = today.minusMonths(13).toString();
        maturityDate = today.minusDays(20).toString();
        Minted m = activeSeries();
        UUID series = m.prepared().series();
        String controller = (String) series(series).get("controller_address");
        SeriesController ctl = calls.load(SeriesController.class, controller);
        Token token = calls.load(Token.class, ctl.token().send());
        frozen(series, m.b(), 100);

        // AC1 (B11b, B11c): chụp kỳ cuối, VSDC xác nhận, lệnh chi gốc, BIDV xác nhận
        UUID event = jdbc.queryForObject("""
                SELECT id FROM issuance.series_event WHERE series_id = ? AND event_type = 'MATURITY'
                """, UUID.class, series);
        DebtServices.Due due = debts.get(event).orElseThrow();
        LocalDate t = due.paymentDate();
        int period = due.periodNo();
        agent.setBalance("USD", 1_000_000_000_000L);
        runner.run(t.minusDays(15));
        runner.run(t.minusDays(2));
        await().atMost(WAIT).until(() -> post("/v1/series/" + series + "/snapshots/" + period, as("holco"),
                Map.of("asOf", today.toString())).getResponse().getStatus() == 202);
        await().atMost(WAIT).until(() -> "ATTESTING".equals(jdbc.queryForObject("""
                SELECT status FROM distribution.record_snapshot WHERE series_id = ? AND period_no = ?
                """, String.class, series, period)));
        signBy(jdbc.queryForObject("""
                SELECT signing_proposal_id FROM distribution.record_snapshot WHERE series_id = ? AND period_no = ?
                """, UUID.class, series, period), "vsdc");
        await().atMost(WAIT).until(() -> "ATTESTED".equals(jdbc.queryForObject("""
                SELECT status FROM distribution.record_snapshot WHERE series_id = ? AND period_no = ?
                """, String.class, series, period)));
        runner.run(t);
        assertThat(debtStatus(event)).isEqualTo("INSTRUCTED");
        MvcResult ack = confirmAll(event);
        assertThat(ack.getResponse().getStatus()).as(ack.getResponse().getContentAsString()).isEqualTo(200);
        await().atMost(WAIT).until(() -> "RECONCILED".equals(debtStatus(event)));
        String payoutHash = jdbc.queryForObject("SELECT payout_hash FROM issuance.debt_service WHERE series_event_id = ?",
                String.class, event);
        assertThat(payoutHash).startsWith("0x").hasSize(66);

        // AC2: mature tự gửi; series Đã đáo hạn, chặn chuyển nhượng
        await().atMost(WAIT).until(() -> "MATURED".equals(series(series).get("status")));
        assertThat(org.web3j.utils.Numeric.toHexString(ctl.payoutHash().send())).isEqualTo(payoutHash);
        ModularCompliance compliance = calls.load(ModularCompliance.class, token.compliance().send());
        assertThat(compliance.canTransfer(m.a().address(), m.b().address(), BigInteger.ONE).send()).isFalse();
        MvcResult again = post("/v1/series/" + series + "/mature", as("holco"), null);
        status(again, 409);
        assertThat(body(again).get("code")).isEqualTo("SERIES_NOT_MATURABLE");

        // AC3: hủy số dư không phong tỏa; B còn 100 phong tỏa nên series giữ Đã đáo hạn
        await().atMost(WAIT).until(() -> BigInteger.ZERO.equals(token.balanceOf(m.a().address()).send()));
        await().atMost(WAIT).until(() -> BigInteger.valueOf(100).equals(token.balanceOf(m.b().address()).send()));
        await().atMost(WAIT).until(() -> "FROZEN_REMAINING".equals(jdbc.queryForList(
                "SELECT status FROM distribution.series_closure WHERE series_id = ?", String.class, series).stream()
                .findFirst().orElse(null)));
        assertThat(series(series).get("status")).isEqualTo("MATURED");
        assertThat(jdbc.queryForObject("SELECT COALESCE(sum(burned), 0) FROM distribution.redemption WHERE series_id = ?",
                Long.class, series)).isEqualTo(800L);
        MvcResult nothing = post("/v1/series/" + series + "/redemption", as("holco"), null);
        status(nothing, 409);
        assertThat(body(nothing).get("code")).isEqualTo("NOTHING_TO_REDEEM");

        // Giải tỏa B 100 qua 2/2 thì tự hủy nốt; tổng cung 0 thì đóng series
        UUID unfreeze = approvedOrder("UNFREEZE", series, m.b(), null, 100);
        await().atMost(WAIT).until(() -> jdbc.queryForObject(
                "SELECT signing_proposal_id FROM governance.enforcement_order WHERE id = ?", UUID.class, unfreeze) != null);
        signBy(jdbc.queryForObject("SELECT signing_proposal_id FROM governance.enforcement_order WHERE id = ?", UUID.class,
                unfreeze), "vsdc", "holco");
        await().atMost(WAIT).until(() -> BigInteger.ZERO.equals(token.totalSupply().send()));
        await().atMost(WAIT).until(() -> "CLOSED".equals(series(series).get("status")));

        // Q168: R4 không lệch sau khi hủy
        await().atMost(WAIT).until(() -> supply.run(LocalDate.now()).stream()
                .noneMatch(f -> f.subjectRef().startsWith("series/" + series) || "CHAIN_UNAVAILABLE".equals(f.kind())));
    }
}
