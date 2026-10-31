package vn.werwa.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.werwa.issuance.series.SeriesPause;
import vn.werwa.onchain.TestIdentities;
import vn.werwa.onchain.api.contracts.SeriesController;
import vn.werwa.onchain.api.contracts.Token;
import vn.werwa.platform.OpenApiSchemas;

/**
 * Tạm dừng và mở lại series với Besu, Safe và nền tảng thật (Q171–Q177). Dừng lúc lô BIDV chưa ký: mục có tiền không được
 * mint khi đang dừng, mở lại thì mint.
 */
class PauseIT extends AllocationChainTest {

    boolean paused(UUID series) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT paused FROM issuance.series WHERE id = ?", Boolean.class,
                series));
    }

    String allocationStatus(Object id) {
        return jdbc.queryForObject("SELECT status FROM distribution.allocation WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("Q172–Q177 VSDC và Holco ký 2/2 thì series tạm dừng, mint chờ; duyệt 4 mắt và UBND, VSDC, Holco ký 3/3 thì mở lại, mint chạy tiếp")
    void pauseHoldsMintUntilUnpaused() throws Exception {
        Investor a = investor();
        Prepared p = approvedList(List.of(new Want(a, 600, 600)));
        signList(p.list());
        UUID series = p.series();
        String controller = (String) series(series).get("controller_address");
        Token token = calls.load(Token.class, calls.load(SeriesController.class, controller).token().send());
        TestIdentities.onboard(chainTransactions, calls, tx, a.address(), 1);
        Map<String, Object> alloc = allocationOf(p.list(), p.orders().get(a));
        payFor(alloc);
        List<UUID> batches = paymentService.buildBatches();
        DistributionConfig.proposeBatches(batches, paymentBatchSigning, DistributionConfig.separate(transactionManager),
                auditLog);

        // Q172: VSDC lập yêu cầu tạm dừng; VSDC ký một mình chưa đủ 2/2
        MvcResult pause = post("/v1/series/" + series + "/pause", as("vsdc"), Map.of("reason", "Sự cố an ninh (thử)"));
        status(pause, 202);
        UUID pauseProposal = UUID.fromString((String) body(pause).get("signingProposalId"));
        assertThat(signing.get(pauseProposal).safe()).isEqualTo(SAFES.get("enforcement2of2"));
        assertThat(signing.get(pauseProposal).functionName()).isEqualTo("pause");
        MvcResult twice = post("/v1/series/" + series + "/pause", as("holco"), Map.of("reason", "x"));
        status(twice, 409);
        assertThat(body(twice).get("code")).isEqualTo("PAUSE_REQUEST_PENDING");
        signBy(pauseProposal, "vsdc");
        assertThat(paused(series)).isFalse();
        signBy(pauseProposal, "holco");
        await().atMost(WAIT).until(() -> paused(series));
        assertThat(token.paused().send()).isTrue();
        byte[] event = nats.jetStreamManagement().getLastMessage("WERWA", SeriesPause.PAUSED_EVENT).getData();
        assertThat(vn.werwa.platform.events.EventSchemas.validate(SeriesPause.PAUSED_EVENT, event)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM governance.notification WHERE kind = 'SERIES_PAUSED' AND subject_ref = ?
                """, Integer.class, "series/" + series)).isPositive();
        MvcResult view = mvc.perform(get("/v1/series/" + series).with(as("holco"))).andReturn();
        status(view, 200);
        assertThat(OpenApiSchemas.validate("Series", view.getResponse().getContentAsString())).isEmpty();
        assertThat(body(view)).containsEntry("paused", true);

        // Q175: BIDV ký lô khi đang dừng; bộ điều phối giữ mục lại, không gửi mint
        signBy(jdbc.queryForObject("SELECT signing_proposal_id FROM distribution.payment_batch WHERE id = ?", UUID.class,
                batches.getFirst()), "bidv");
        await().atMost(WAIT).until(() -> "SIGNED".equals(jdbc.queryForObject(
                "SELECT status FROM distribution.payment_batch WHERE id = ?", String.class, batches.getFirst())));
        MvcResult retry = post("/v1/allocations/" + alloc.get("id") + "/mint", as("holco"), null);
        status(retry, 409);
        assertThat(body(retry).get("code")).isEqualTo("SERIES_PAUSED");
        assertThat(allocationStatus(alloc.get("id"))).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT mint_chain_tx_id FROM distribution.allocation WHERE id = ?", UUID.class,
                alloc.get("id"))).isNull();

        // Q173: mở lại; người lập không tự duyệt
        String maker = UUID.randomUUID().toString();
        RequestPostProcessor makerUser = jwt().jwt(j -> j.subject(maker).claim("org_id", SAFE_ORGS.get("holco"))
                .claim("roles", List.of("OPERATOR")));
        MvcResult unpause = post("/v1/series/" + series + "/unpause", makerUser, Map.of("reason", "Đã khắc phục sự cố"));
        status(unpause, 202);
        UUID approval = UUID.fromString((String) body(unpause).get("approvalRequestId"));
        MvcResult self = post("/v1/approvals/" + approval + "/decision", makerUser, Map.of("decision", "APPROVE"));
        status(self, 403);
        approve(approval, as("holco"));
        UUID requestId = jdbc.queryForObject("""
                SELECT id FROM issuance.series_pause_request WHERE approval_request_id = ?
                """, UUID.class, approval);
        await().atMost(WAIT).until(() -> jdbc.queryForObject(
                "SELECT signing_proposal_id FROM issuance.series_pause_request WHERE id = ?", UUID.class, requestId) != null);
        UUID unpauseProposal = jdbc.queryForObject(
                "SELECT signing_proposal_id FROM issuance.series_pause_request WHERE id = ?", UUID.class, requestId);
        assertThat(signing.get(unpauseProposal).safe()).isEqualTo(SAFES.get("governance3of3"));
        signBy(unpauseProposal, "ubnd", "vsdc", "holco");
        await().atMost(WAIT).until(() -> !paused(series));
        assertThat(token.paused().send()).isFalse();

        // Q175: mở lại thì mint chạy tiếp
        await().atMost(WAIT).until(() -> "MINTED".equals(allocationStatus(alloc.get("id"))));
    }
}
