package vn.werwa.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.web3j.utils.Numeric;
import vn.werwa.distribution.allocation.AllocationMerkle;
import vn.werwa.distribution.payment.MintOrchestrator;
import vn.werwa.distribution.payment.PaymentBatchSigning;
import vn.werwa.distribution.payment.PaymentService;
import vn.werwa.distribution.payment.SupplyCheck;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.onchain.TestIdentities;
import vn.werwa.onchain.api.ChainCallRejected;
import vn.werwa.onchain.api.ChainTransactions;
import vn.werwa.onchain.api.SignerKey;
import vn.werwa.onchain.api.TxRequest;
import vn.werwa.onchain.api.contracts.ModularCompliance;
import vn.werwa.onchain.api.contracts.SeriesController;
import vn.werwa.onchain.api.contracts.Token;
import vn.werwa.platform.OpenApiSchemas;

/**
 * BIDV ký lô và bộ điều phối mint với Besu, Safe và nền tảng thật (US-3.05-AC3, AC4; Q128–Q138). A, B, C có tiền; D
 * chưa trả. A, B có định danh trước; C có định danh sau khi mint bị chặn (Q135).
 */
class MintIT extends AllocationChainTest {

    @Autowired
    PaymentService payments;

    @Autowired
    PaymentBatchSigning batchSigning;

    @Autowired
    MintOrchestrator mints;

    @Autowired
    SupplyCheck supply;

    @Autowired
    ChainTransactions chain;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    AuditLog audit;

    void pay(Map<String, Object> allocation) {
        String result = tx.execute(s -> payments.match(new PaymentService.Credit("FT" + UUID.randomUUID(),
                (String) allocation.get("virtual_account"), ((Number) allocation.get("amount_due_minor")).longValue(),
                String.valueOf(allocation.get("currency")).trim(), LocalDate.of(2027, 2, 25), "Payer",
                UUID.randomUUID())));
        assertThat(result).isEqualTo("MATCHED");
    }

    String allocationStatus(Object id) {
        return jdbc.queryForObject("SELECT status FROM distribution.allocation WHERE id = ?", String.class, id);
    }

    /** Lệnh mint gửi thẳng bằng khóa vận hành, không qua bộ điều phối (cảnh demo AC4). */
    String rawMint(String controller, UUID allocationId, String wallet, long quantity, long amountMinor,
            List<String> allocProof, byte[] paymentRoot) {
        return calls.load(SeriesController.class, controller).mint(
                new SeriesController.MintItem(AllocationMerkle.uuidToBytes32(allocationId), wallet,
                        BigInteger.valueOf(quantity), BigInteger.valueOf(amountMinor), AllocationMerkle.bankRefHash("FT-X")),
                allocProof.stream().map(Numeric::hexStringToByteArray).toList(), paymentRoot, List.of())
                .encodeFunctionCall();
    }

    ChainCallRejected rejected(String controller, String data) {
        return catchThrowableOfType(ChainCallRejected.class, () -> tx.execute(s -> chain.submit(new TxRequest(
                "TEST_MINT", "test/" + UUID.randomUUID(), SignerKey.HOLCO_OPERATOR, controller, data))));
    }

    @Test
    @DisplayName("US-3.05-AC3, AC4 BIDV ký lô; mỗi phân bổ mint đúng một lần với hai đường chứng minh; mint sai bị hợp đồng từ chối")
    @SuppressWarnings("unchecked")
    void US_3_05_AC3_AC4_bidvSignsBatchThenMintOnce() throws Exception {
        Investor a = investor();
        Investor b = investor();
        Investor c = investor();
        Investor d = investor();
        Prepared p = approvedList(List.of(new Want(a, 600, 600), new Want(b, 500, 300), new Want(c, 100, 100),
                new Want(d, 100, 100)));
        signList(p.list());
        String controller = (String) series(p.series()).get("controller_address");
        TestIdentities.onboard(chain, calls, tx, a.address(), 1);
        TestIdentities.onboard(chain, calls, tx, b.address(), 1);

        Map<String, Object> allocA = allocationOf(p.list(), p.orders().get(a));
        Map<String, Object> allocB = allocationOf(p.list(), p.orders().get(b));
        Map<String, Object> allocC = allocationOf(p.list(), p.orders().get(c));
        Map<String, Object> allocD = allocationOf(p.list(), p.orders().get(d));
        pay(allocA);
        pay(allocB);
        pay(allocC);

        // Q130: gom lô và tự tạo đề nghị ký Safe BIDV
        List<UUID> batches = payments.buildBatches();
        assertThat(batches).hasSize(1);
        UUID batch = batches.getFirst();
        DistributionConfig.proposeBatches(batches, batchSigning, DistributionConfig.separate(txManager), audit);
        MvcResult view = mvc.perform(get("/v1/payment-batches/" + batch).with(as("bidv"))).andReturn();
        status(view, 200);
        assertThat(OpenApiSchemas.validate("PaymentBatch", view.getResponse().getContentAsString())).isEmpty();
        Map<String, Object> bv = body(view);
        assertThat(bv).containsEntry("status", "SIGNING").containsEntry("itemCount", 3);
        assertThat(bv.get("batchHash")).isEqualTo(PaymentService.batchHash(controller, 1, 3, 100_000L * 1000, "USD"));
        UUID proposal = UUID.fromString((String) bv.get("signingProposalId"));
        assertThat(signing.get(proposal).safe()).isEqualTo(SAFES.get("bidv"));
        assertThat(signing.get(proposal).basisHash()).isEqualTo(bv.get("batchHash"));
        MvcResult listed = mvc.perform(get("/v1/payment-batches?offeringId=" + p.offering()).with(as("bidv"))).andReturn();
        status(listed, 200);
        assertThat((List<?>) body(listed).get("items")).hasSize(1);
        // Đề nghị đang mở thì không tạo lại
        MvcResult again = post("/v1/payment-batches/" + batch + "/signing-proposal", as("holco"), null);
        status(again, 409);
        assertThat(body(again).get("code")).isEqualTo("PAYMENT_BATCH_SIGNING_PENDING");

        // AC4 (b): trước khi BIDV ký, mint D với gốc lô chưa ký bị từ chối
        Map<String, Object> proofD = body(mvc.perform(get("/v1/allocation-lists/" + p.list() + "/proofs/"
                + allocD.get("id")).with(d.user())).andReturn());
        ChainCallRejected unsignedBatch = rejected(controller, rawMint(controller, (UUID) allocD.get("id"), d.address(),
                100, 100_000L * 100, (List<String>) proofD.get("proof"), Numeric.hexStringToByteArray((String) bv.get("merkleRoot"))));
        assertThat(unsignedBatch).isNotNull();
        assertThat(unsignedBatch.contractError()).isEqualTo("InvalidPaymentProof");

        // BIDV ký 2/3; indexer đọc PaymentBatchAdded → lô SIGNED → bộ điều phối mint
        signBy(proposal, "bidv");
        await().atMost(WAIT).until(() -> "SIGNED".equals(jdbc.queryForObject(
                "SELECT status FROM distribution.payment_batch WHERE id = ?", String.class, batch)));
        await().atMost(WAIT).until(() -> "MINTED".equals(allocationStatus(allocA.get("id")))
                && "MINTED".equals(allocationStatus(allocB.get("id"))));
        byte[] event = nats.jetStreamManagement().getLastMessage("WERWA", MintOrchestrator.COMPLETED_EVENT).getData();
        assertThat(vn.werwa.platform.events.EventSchemas.validate(MintOrchestrator.COMPLETED_EVENT, event)).isEmpty();
        byte[] signedEvent = nats.jetStreamManagement().getLastMessage("WERWA", PaymentBatchSigning.SIGNED_EVENT).getData();
        assertThat(vn.werwa.platform.events.EventSchemas.validate(PaymentBatchSigning.SIGNED_EVENT, signedEvent)).isEmpty();

        // C chưa có định danh: không gửi mint, giữ PAID kèm lý do (Q135)
        await().atMost(WAIT).until(() -> "WALLET_NOT_VERIFIED".equals(allocationOf(p.list(), p.orders().get(c))
                .get("mint_failure_reason")));
        assertThat(allocationStatus(allocC.get("id"))).isEqualTo("PAID");

        // Trên chuỗi: số dư đúng, series Đang ghi sổ, trái phiếu khóa chuyển nhượng (AC3)
        SeriesController ctl = calls.load(SeriesController.class, controller);
        Token token = calls.load(Token.class, ctl.token().send());
        assertThat(token.balanceOf(a.address()).send()).isEqualTo(BigInteger.valueOf(600));
        assertThat(token.balanceOf(b.address()).send()).isEqualTo(BigInteger.valueOf(300));
        await().atMost(WAIT).until(() -> "ISSUING".equals(series(p.series()).get("status")));
        ModularCompliance compliance = calls.load(ModularCompliance.class, token.compliance().send());
        assertThat(compliance.canTransfer(a.address(), b.address(), BigInteger.ONE).send()).isFalse();

        // Mint đúng một lần: API từ chối, hợp đồng từ chối
        MvcResult retryA = post("/v1/allocations/" + allocA.get("id") + "/mint", as("holco"), null);
        status(retryA, 409);
        assertThat(body(retryA).get("code")).isEqualTo("ALLOCATION_NOT_MINTABLE");
        Map<String, Object> proofA = body(mvc.perform(get("/v1/allocation-lists/" + p.list() + "/proofs/"
                + allocA.get("id")).with(a.user())).andReturn());
        ChainCallRejected twice = rejected(controller, rawMint(controller, (UUID) allocA.get("id"), a.address(), 600,
                100_000L * 600, (List<String>) proofA.get("proof"), Numeric.hexStringToByteArray((String) bv.get("merkleRoot"))));
        assertThat(twice.contractError()).isEqualTo("AlreadyMinted");

        // AC4 (a): mục không có trong danh sách UBND
        ChainCallRejected notListed = rejected(controller, rawMint(controller, UUID.randomUUID(), a.address(), 1,
                100_000L, (List<String>) proofA.get("proof"), Numeric.hexStringToByteArray((String) bv.get("merkleRoot"))));
        assertThat(notListed.contractError()).isEqualTo("InvalidAllocationProof");

        // Q135: C có định danh rồi, Holco gửi lại qua API
        TestIdentities.onboard(chain, calls, tx, c.address(), 1);
        MvcResult retryC = post("/v1/allocations/" + allocC.get("id") + "/mint", as("holco"), null);
        status(retryC, 202);
        assertThat(OpenApiSchemas.validate("ChainTxRef", retryC.getResponse().getContentAsString())).isEmpty();
        status(post("/v1/allocations/" + allocC.get("id") + "/mint", as("holco"), null), 409);
        await().atMost(WAIT).until(() -> "MINTED".equals(allocationStatus(allocC.get("id"))));

        // Tiến độ: mọi mục có tiền đã mint, D chưa trả nên vẫn theo dõi tiền
        assertThat(payments.progress(p.series()).minted()).isEqualTo(1000);
        MvcResult after = post("/v1/payment-batches/" + batch + "/signing-proposal", as("holco"), null);
        status(after, 409);
        assertThat(body(after).get("code")).isEqualTo("PAYMENT_BATCH_NOT_SIGNABLE");

        // R4 (Q138): tổng cung bằng tổng đã mint
        await().atMost(WAIT).until(() -> supply.run(LocalDate.now()).stream()
                .noneMatch(f -> f.subjectRef().startsWith("series/" + p.series()) || "CHAIN_UNAVAILABLE".equals(f.kind())));
    }
}
