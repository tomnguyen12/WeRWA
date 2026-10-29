package vn.werwa.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.web3j.crypto.Hash;
import vn.werwa.issuance.TestDocuments;
import vn.werwa.onchain.api.contracts.SeriesController;
import vn.werwa.platform.OpenApiSchemas;

/**
 * UBND ký danh sách phân bổ trên chuỗi với Besu, Safe và nền tảng thật (US-3.03-AC3, AC4, US-2.06-AC3; Q121–Q127): series
 * Sẵn sàng, mở sổ, đặt lệnh, lập và duyệt danh sách, Safe UBND ký 2/3, indexer đọc AllocationSet.
 */
class AllocationSignIT extends AllocationChainTest {

    /** OZ MerkleProof: băm cặp theo thứ tự tăng. */
    static byte[] fold(byte[] leaf, List<String> proof) {
        byte[] h = leaf;
        for (String p : proof) {
            byte[] q = HexFormat.of().parseHex(p.substring(2));
            h = Arrays.compareUnsigned(h, q) <= 0 ? Hash.sha3(concat(h, q)) : Hash.sha3(concat(q, h));
        }
        return h;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    @DisplayName("US-3.03-AC3, AC4, US-2.06-AC3 UBND duyệt rồi ký: series Đã chốt phân bổ, lệnh có kết quả, đường chứng minh khớp gốc trên chuỗi")
    @SuppressWarnings("unchecked")
    void US_3_03_AC3_AC4_issuerSignsAllocationOnChain() throws Exception {
        UUID series = readySeries();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        MvcResult created = post("/v1/offerings", SYNDICATE_USER, Map.of("seriesId", series.toString(), "method",
                "BOOKBUILDING", "rateGuidanceLowPpm", 40_000, "rateGuidanceHighPpm", 60_000, "targetQuantity", 1000,
                "bookOpenAt", now.minus(1, ChronoUnit.HOURS).toString(), "bookCloseAt", now.plus(1, ChronoUnit.DAYS).toString()));
        status(created, 201);
        UUID offering = id(created);
        status(post("/v1/offerings/" + offering + "/open", SYNDICATE_USER, null), 200);

        Investor a = investor();
        Investor b = investor();
        Investor c = investor();
        UUID orderA = order(offering, a, 600);
        UUID orderB = order(offering, b, 500);
        UUID orderC = order(offering, c, 100);
        status(post("/v1/offerings/" + offering + "/close", SYNDICATE_USER, null), 200);

        MvcResult proposed = post("/v1/offerings/" + offering + "/allocation-lists", SYNDICATE_USER, Map.of("couponRatePpm",
                50_000, "items", List.of(item(orderA, 600, null), item(orderB, 300, "giới hạn khối lượng"))));
        status(proposed, 201);
        UUID list = id(proposed);
        RequestPostProcessor issuer = as("ubnd");
        status(post("/v1/issuer-decisions", issuer, Map.of("decisionNo", "QD-LS-" + UUID.randomUUID(), "decidedOn",
                "2026-10-27", "subject", "COUPON_RATE", "offeringId", offering.toString(), "documentId",
                TestDocuments.uploadId(mvc, issuer, "ISSUER_DECISION").toString())), 201);
        MvcResult submitted = post("/v1/allocation-lists/" + list + "/submit", SYNDICATE_USER, null);
        status(submitted, 202);
        approve(UUID.fromString((String) body(submitted).get("approvalRequestId")), as("ubnd"));

        // Q122: đề nghị ký Safe UBND tự tạo sau duyệt, kèm mã băm quyết định (US-2.06-AC3)
        await().atMost(WAIT).until(() -> jdbc.queryForObject(
                "SELECT signing_proposal_id FROM distribution.allocation_list WHERE id = ?", UUID.class, list) != null);
        UUID proposal = jdbc.queryForObject("SELECT signing_proposal_id FROM distribution.allocation_list WHERE id = ?",
                UUID.class, list);
        String decisionHash = jdbc.queryForObject("SELECT issuer_decision_hash FROM distribution.allocation_list WHERE id = ?",
                String.class, list);
        MvcResult view = mvc.perform(get("/v1/signing-proposals/" + proposal).with(as("ubnd"))).andReturn();
        status(view, 200);
        assertThat(OpenApiSchemas.validate("SigningProposal", view.getResponse().getContentAsString())).isEmpty();
        assertThat(body(view)).containsEntry("basisHash", decisionHash).containsEntry("functionName", "setAllocation")
                .containsEntry("safe", SAFES.get("ubnd"));
        // Q127: tổ chức UBND được báo có việc cần ký
        assertThat(mvc.perform(get("/v1/notifications?limit=200").with(as("ubnd"))).andReturn().getResponse()
                .getContentAsString()).contains("SIGNATURE_REQUESTED").contains(proposal.toString());
        // Q126: đề nghị đang mở thì Holco không tạo lại được
        MvcResult again = post("/v1/allocation-lists/" + list + "/signing-proposal", as("holco"), null);
        status(again, 409);
        assertThat(body(again).get("code")).isEqualTo("ALLOCATION_SIGNING_PENDING");

        signBy(proposal, "ubnd");
        await().atMost(WAIT).until(() -> "SIGNED".equals(jdbc.queryForObject(
                "SELECT status FROM distribution.allocation_list WHERE id = ?", String.class, list)));

        // AC3: series Đã chốt phân bổ; lệnh có kết quả; đợt chào bán đã phân bổ; phân bổ chờ tiền
        await().atMost(WAIT).until(() -> "ALLOCATED".equals(series(series).get("status")));
        assertThat(orderStatus(orderA)).isEqualTo("ALLOCATED");
        assertThat(orderStatus(orderB)).isEqualTo("PARTIALLY_ALLOCATED");
        assertThat(orderStatus(orderC)).isEqualTo("NOT_ALLOCATED");
        assertThat(jdbc.queryForObject("SELECT status FROM distribution.offering WHERE id = ?", String.class, offering))
                .isEqualTo("ALLOCATED");
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM distribution.allocation WHERE allocation_list_id = ?",
                String.class, list)).containsExactly("AWAITING_PAYMENT");
        // Q72, Q124: lãi suất từng kỳ ghi từ AllocationSet
        await().atMost(WAIT).until(() -> jdbc.queryForObject("""
                SELECT count(*) FROM issuance.series_event WHERE series_id = ? AND event_type = 'COUPON' AND rate_ppm = 50000
                """, Integer.class, series) == 6);
        byte[] event = nats.jetStreamManagement().getLastMessage("WERWA", "werwa.distribution.allocation_list.signed.v1")
                .getData();
        assertThat(vn.werwa.platform.events.EventSchemas.validate("werwa.distribution.allocation_list.signed.v1", event))
                .isEmpty();

        // AC4: nhà đầu tư A lấy đường chứng minh và tự kiểm với gốc đọc từ controller trên chuỗi
        Map<String, Object> items = body(mvc.perform(get("/v1/allocation-lists/" + list).with(SYNDICATE_USER)).andReturn());
        String allocationA = ((List<Map<String, Object>>) items.get("items")).stream()
                .filter(i -> orderA.toString().equals(i.get("orderId"))).findFirst().orElseThrow().get("allocationId")
                .toString();
        MvcResult proof = mvc.perform(get("/v1/allocation-lists/" + list + "/proofs/" + allocationA).with(a.user()))
                .andReturn();
        status(proof, 200);
        Map<String, Object> p = body(proof);
        String controller = (String) series(series).get("controller_address");
        byte[] onChainRoot = calls.load(SeriesController.class, controller).allocationRoot().send();
        byte[] folded = fold(HexFormat.of().parseHex(((String) p.get("leaf")).substring(2)), (List<String>) p.get("proof"));
        assertThat(folded).isEqualTo(onChainRoot);
        assertThat(p.get("merkleRoot")).isEqualTo("0x" + HexFormat.of().formatHex(onChainRoot));

        // Đã ký thì không đề nghị ký lại
        MvcResult after = post("/v1/allocation-lists/" + list + "/signing-proposal", as("holco"), null);
        status(after, 409);
        assertThat(body(after).get("code")).isEqualTo("LIST_NOT_APPROVED");
    }
}
