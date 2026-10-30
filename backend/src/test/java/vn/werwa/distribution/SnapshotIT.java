package vn.werwa.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.web3j.utils.Numeric;
import vn.werwa.distribution.payout.DebtServiceRunner;
import vn.werwa.distribution.payout.SnapshotService;
import vn.werwa.integration.bank.SimulatedPayingAgent;
import vn.werwa.issuance.api.DebtServices;
import vn.werwa.onchain.api.contracts.SeriesController;
import vn.werwa.platform.OpenApiSchemas;

/**
 * Chụp danh sách chốt và VSDC xác nhận với Besu, Safe và nền tảng thật (US-3.09-AC1, AC2; Q155–Q162). Series Đang lưu
 * hành: A 600 (phong tỏa 200), B 300.
 */
class SnapshotIT extends AllocationChainTest {

    @Autowired
    DebtServiceRunner runner;

    @Autowired
    DebtServices debts;

    @Autowired
    SimulatedPayingAgent agent;

    Map<String, Object> snapshotRow(UUID series) {
        return jdbc.queryForMap("""
                SELECT id, status, record_block, register_hash, holder_count, total_quantity, signing_proposal_id
                FROM distribution.record_snapshot WHERE series_id = ? AND period_no = 1
                """, series);
    }

    @Test
    @DisplayName("US-3.09-AC1, AC2 chụp sổ ghi mã băm lên chuỗi; chỉ sau khi VSDC xác nhận mới gửi lệnh chi")
    @SuppressWarnings("unchecked")
    void US_3_09_AC1_AC2_snapshotThenAttestBeforePayout() throws Exception {
        Minted m = activeSeries();
        UUID series = m.prepared().series();
        String controller = (String) series(series).get("controller_address");
        frozen(series, m.a(), 200);
        UUID event = jdbc.queryForObject("""
                SELECT id FROM issuance.series_event WHERE series_id = ? AND event_type = 'COUPON' AND period_no = 1
                """, UUID.class, series);
        LocalDate t = debts.get(event).orElseThrow().paymentDate();
        agent.setBalance("USD", 1_000_000_000_000L);
        runner.run(t.minusDays(15));
        runner.run(t.minusDays(2));
        assertThat(debts.get(event).orElseThrow().status()).isEqualTo("FUNDS_CHECKED");

        // AC1: chụp tại khối cuối của hôm nay; sao kê có thể chậm hơn chuỗi vài giây nên thử lại khi lệch
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        await().atMost(WAIT).until(() -> post("/v1/series/" + series + "/snapshots/1", as("holco"),
                Map.of("asOf", today.toString())).getResponse().getStatus() == 202);
        MvcResult again = post("/v1/series/" + series + "/snapshots/1", as("holco"), Map.of("asOf", today.toString()));
        status(again, 409);
        assertThat(body(again).get("code")).isEqualTo("SNAPSHOT_EXISTS");
        await().atMost(WAIT).until(() -> "ATTESTING".equals(snapshotRow(series).get("status")));
        Map<String, Object> snap = snapshotRow(series);
        long block = ((Number) snap.get("record_block")).longValue();
        String hash = (String) snap.get("register_hash");
        assertThat(hash).isEqualTo(RegisterIT.castRegisterHash(controller, block,
                Map.of(m.a().address(), 600L, m.b().address(), 300L)));
        var onChain = calls.load(SeriesController.class, controller).snapshotOf(java.math.BigInteger.ONE).send();
        assertThat(Numeric.toHexString(onChain.registerHash)).isEqualTo(hash);
        assertThat(onChain.recordBlock.longValue()).isEqualTo(block);
        assertThat(jdbc.queryForObject("""
                SELECT frozen_quantity FROM distribution.snapshot_holding WHERE record_snapshot_id = ? AND wallet_id = ?
                """, Long.class, snap.get("id"), m.a().wallet())).isEqualTo(200L);
        UUID proposal = (UUID) snap.get("signing_proposal_id");
        assertThat(signing.get(proposal).safe()).isEqualTo(SAFES.get("vsdc"));
        assertThat(signing.get(proposal).basisHash()).isEqualTo(hash);

        // VSDC xem danh sách chốt (dòng chi đã tính, phần phong tỏa giữ lại)
        MvcResult view = mvc.perform(get("/v1/series/" + series + "/snapshots/1").with(as("vsdc"))).andReturn();
        status(view, 200);
        assertThat(OpenApiSchemas.validate("Snapshot", view.getResponse().getContentAsString())).isEmpty();
        assertThat(body(view)).containsEntry("status", "ATTESTING");
        assertThat(view.getResponse().getContentAsString()).contains("\"withheldReason\":\"FROZEN\"");

        // AC2: chưa xác nhận thì ngày T không gửi lệnh chi
        runner.run(t);
        assertThat(debts.get(event).orElseThrow().status()).isEqualTo("FUNDS_CHECKED");
        assertThat(agent.submitted(event.toString())).isEmpty();

        signBy(proposal, "vsdc");
        await().atMost(WAIT).until(() -> "ATTESTED".equals(snapshotRow(series).get("status")));
        byte[] attested = nats.jetStreamManagement().getLastMessage("WERWA", SnapshotService.ATTESTED_EVENT).getData();
        assertThat(vn.werwa.platform.events.EventSchemas.validate(SnapshotService.ATTESTED_EVENT, attested)).isEmpty();
        assertThat(calls.load(SeriesController.class, controller).snapshotOf(java.math.BigInteger.ONE).send().attested)
                .isTrue();
        runner.run(t);
        assertThat(debts.get(event).orElseThrow().status()).isEqualTo("INSTRUCTED");
        assertThat(agent.submitted(event.toString())).hasSize(1);
    }
}
