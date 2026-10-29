package vn.werwa.distribution.payment;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.utils.Numeric;
import vn.werwa.distribution.DistributionError;
import vn.werwa.distribution.allocation.AllocationListService;
import vn.werwa.distribution.allocation.AllocationMerkle;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.api.OperationalAlerts;
import vn.werwa.onchain.api.ChainCallRejected;
import vn.werwa.onchain.api.ChainTransactions;
import vn.werwa.onchain.api.ContractCalls;
import vn.werwa.onchain.api.SignerKey;
import vn.werwa.onchain.api.TxRequest;
import vn.werwa.onchain.api.contracts.IdentityRegistry;
import vn.werwa.onchain.api.contracts.SeriesController;
import vn.werwa.onchain.api.contracts.Token;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.web.ApiException;

/**
 * Bộ điều phối mint (US-3.05-AC3; Q133–Q135): mỗi phân bổ PAID trong lô BIDV đã ký được mint một lần bằng khóa vận hành
 * Holco, kèm đường chứng minh danh sách UBND và đường chứng minh lô BIDV. Hợp đồng là chốt chặn cuối. Trạng thái ngoài
 * chuỗi chỉ đổi khi indexer đọc {@code Minted}.
 *
 * <p>Phân bổ đang chờ mint: {@code mint_chain_tx_id} khác null. Mint bị từ chối: {@code mint_chain_tx_id} về null,
 * {@code mint_failure_reason} ghi lý do; bộ điều phối không tự thử lại, Holco gọi {@code POST /v1/allocations/{id}/mint}.
 */
public class MintOrchestrator {

    public static final String PURPOSE = "MINT";
    public static final String COMPLETED_EVENT = "werwa.distribution.mint.completed.v1";
    static final String WALLET_NOT_VERIFIED = "WALLET_NOT_VERIFIED";

    private final JdbcTemplate jdbc;
    private final AllocationListService lists;
    private final ChainTransactions chain;
    private final ContractCalls calls;
    private final OperationalAlerts alerts;
    private final Events events;
    private final AuditLog audit;
    private final Clock clock;

    public MintOrchestrator(JdbcTemplate jdbc, AllocationListService lists, ChainTransactions chain, ContractCalls calls,
            OperationalAlerts alerts, Events events, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.lists = lists;
        this.chain = chain;
        this.calls = calls;
        this.alerts = alerts;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    /** Kết quả một lần thử: id {@code chain_tx}, hoặc null kèm lý do không gửi. */
    public record Attempt(UUID chainTxId, String skippedReason) {}

    /** Phân bổ của lô đã ký đang chờ bộ điều phối gửi mint (chưa gửi, chưa từng bị từ chối). */
    public List<UUID> pending(UUID batchId) {
        return jdbc.queryForList("""
                SELECT a.id FROM distribution.payment_confirmation p
                JOIN distribution.allocation a ON a.id = p.allocation_id
                JOIN distribution.payment_batch b ON b.id = p.payment_batch_id
                WHERE b.id = ? AND b.status = 'SIGNED' AND a.status = 'PAID' AND a.mint_chain_tx_id IS NULL
                  AND a.mint_failure_reason IS NULL
                ORDER BY a.id
                """, UUID.class, batchId);
    }

    /** Q133: lịch quét bù, mọi lô đã ký. */
    public List<UUID> pendingAll() {
        return jdbc.queryForList("""
                SELECT a.id FROM distribution.payment_confirmation p
                JOIN distribution.allocation a ON a.id = p.allocation_id
                JOIN distribution.payment_batch b ON b.id = p.payment_batch_id
                WHERE b.status = 'SIGNED' AND a.status = 'PAID' AND a.mint_chain_tx_id IS NULL
                  AND a.mint_failure_reason IS NULL
                ORDER BY b.id, a.id
                """, UUID.class);
    }

    /**
     * Gửi mint một phân bổ. {@code retry} = Holco gọi lại qua API (Q135): bỏ lý do lỗi cũ; không đủ điều kiện thì ném lỗi
     * thay vì bỏ qua.
     */
    @Transactional
    public Attempt submit(UUID allocationId, boolean retry) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.status, a.allocation_list_id, a.wallet_address, a.quantity, a.mint_chain_tx_id,
                       a.mint_failure_reason, p.amount_minor, p.bank_ref, p.payment_batch_id, b.status AS batch_status,
                       b.merkle_root AS batch_root
                FROM distribution.allocation a
                LEFT JOIN distribution.payment_confirmation p ON p.allocation_id = a.id AND p.match_result = 'MATCHED'
                LEFT JOIN distribution.payment_batch b ON b.id = p.payment_batch_id
                WHERE a.id = ? FOR UPDATE OF a
                """, allocationId);
        if (rows.isEmpty()) {
            throw new ApiException(DistributionError.ALLOCATION_NOT_FOUND);
        }
        Map<String, Object> a = rows.getFirst();
        if (a.get("mint_chain_tx_id") != null) {
            if (retry) {
                throw new ApiException(DistributionError.MINT_PENDING, a.get("mint_chain_tx_id").toString());
            }
            return new Attempt(null, "MINT_PENDING");
        }
        if (!"PAID".equals(a.get("status")) || !"SIGNED".equals(a.get("batch_status"))) {
            return notMintable(retry, "phân bổ " + a.get("status") + ", lô " + a.get("batch_status"));
        }
        if (!retry && a.get("mint_failure_reason") != null) {
            return new Attempt(null, "PREVIOUSLY_FAILED");
        }
        UUID listId = (UUID) a.get("allocation_list_id");
        AllocationListService.Proof allocProof = lists.proof(listId, allocationId);
        String controller = allocProof.seriesController().toLowerCase(Locale.ROOT);
        String wallet = ((String) a.get("wallet_address")).toLowerCase(Locale.ROOT);
        byte[] allocationKey = AllocationMerkle.uuidToBytes32(allocationId);
        SeriesController c = calls.load(SeriesController.class, controller);
        try {
            if (Boolean.TRUE.equals(c.isMinted(allocationKey).send())) {
                // Đã mint trên chuỗi; chờ indexer đọc Minted (Q134)
                return new Attempt(null, "ALREADY_MINTED");
            }
            String registry = calls.load(Token.class, c.token().send()).identityRegistry().send();
            if (!Boolean.TRUE.equals(calls.load(IdentityRegistry.class, registry).isVerified(wallet).send())) {
                failed(allocationId, WALLET_NOT_VERIFIED, "WalletNotVerified",
                        "Ví " + wallet + " của phân bổ " + allocationId + " chưa xác minh định danh; chưa mint");
                return notMintable(retry, WALLET_NOT_VERIFIED);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("không đọc được controller " + controller + " qua O1: " + e.getMessage(), e);
        }
        UUID batchId = (UUID) a.get("payment_batch_id");
        List<byte[]> batchLeaves = jdbc.queryForList("""
                SELECT payment_leaf_hash FROM distribution.payment_confirmation WHERE payment_batch_id = ?
                ORDER BY allocation_id
                """, String.class, batchId).stream().map(Numeric::hexStringToByteArray).toList();
        List<UUID> batchOrder = jdbc.queryForList(
                "SELECT allocation_id FROM distribution.payment_confirmation WHERE payment_batch_id = ? ORDER BY allocation_id",
                UUID.class, batchId);
        List<byte[]> paymentProof = AllocationMerkle.proof(batchLeaves, batchOrder.indexOf(allocationId));
        long amount = ((Number) a.get("amount_minor")).longValue();
        String bankRef = (String) a.get("bank_ref");
        String calldata = c.mint(
                new SeriesController.MintItem(allocationKey, wallet, BigInteger.valueOf(((Number) a.get("quantity")).longValue()),
                        BigInteger.valueOf(amount), AllocationMerkle.bankRefHash(bankRef)),
                allocProof.proof().stream().map(Numeric::hexStringToByteArray).toList(),
                Numeric.hexStringToByteArray((String) a.get("batch_root")), paymentProof).encodeFunctionCall();
        UUID txId;
        try {
            txId = chain.submit(new TxRequest(PURPOSE, "allocation/" + allocationId, SignerKey.HOLCO_OPERATOR, controller,
                    calldata));
        } catch (ChainCallRejected e) {
            // Q135: hợp đồng từ chối ở bước chạy thử
            failed(allocationId, e.contractError(), "MintFailed",
                    "Hợp đồng từ chối mint phân bổ " + allocationId + ": " + e.contractError());
            if (retry) {
                throw e;
            }
            return new Attempt(null, e.contractError());
        }
        jdbc.update("""
                UPDATE distribution.allocation SET mint_chain_tx_id = ?, mint_failure_reason = NULL WHERE id = ?
                """, txId, allocationId);
        audit.record("MINT_SUBMITTED", "allocation/" + allocationId,
                Map.of("chainTxId", txId.toString(), "paymentBatchId", batchId.toString(), "retry", retry));
        return new Attempt(txId, null);
    }

    private static Attempt notMintable(boolean retry, String reason) {
        if (retry) {
            throw new ApiException(DistributionError.ALLOCATION_NOT_MINTABLE, reason);
        }
        return new Attempt(null, reason);
    }

    private void failed(UUID allocationId, String reason, String alertName, String summary) {
        jdbc.update("UPDATE distribution.allocation SET mint_chain_tx_id = NULL, mint_failure_reason = ? WHERE id = ?",
                reason, allocationId);
        alerts.raise(alertName.toLowerCase(Locale.ROOT) + ":" + allocationId, alertName, "critical", summary);
        audit.record("MINT_FAILED", "allocation/" + allocationId, Map.of("reason", reason));
    }

    /** Q135: giao dịch mint đã gửi nhưng revert trên chuỗi ({@code werwa.onchain.tx.failed.v1}). */
    @Transactional
    public void onTxFailed(UUID chainTxId, String reason) {
        jdbc.queryForList("""
                SELECT id FROM distribution.allocation WHERE mint_chain_tx_id = ? AND status = 'PAID' FOR UPDATE
                """, UUID.class, chainTxId).forEach(id -> failed(id, reason, "MintFailed",
                "Giao dịch mint phân bổ " + id + " bị hợp đồng từ chối: " + reason));
    }

    /** Q134: {@code Minted(allocationId, wallet, quantity, amountMinor, paymentRoot)} của controller. */
    @Transactional
    public void onMinted(String controller, Map<String, Object> args, String txHash, long block) {
        UUID allocationId = uuidOf(String.valueOf(args.get("allocationId")));
        String wallet = String.valueOf(args.get("wallet")).toLowerCase(Locale.ROOT);
        long quantity = Long.parseLong(String.valueOf(args.get("quantity")));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.status, a.wallet_address, a.quantity, o.series_id FROM distribution.allocation a
                JOIN distribution.allocation_list l ON l.id = a.allocation_list_id
                JOIN distribution.offering o ON o.id = l.offering_id
                WHERE a.id = ? FOR UPDATE OF a
                """, allocationId);
        if (rows.isEmpty() || !wallet.equals(rows.getFirst().get("wallet_address"))
                || quantity != ((Number) rows.getFirst().get("quantity")).longValue()) {
            alerts.raise("minted:" + controller + ":" + allocationId, "MintUnmatched", "critical",
                    "Controller " + controller + " mint " + quantity + " cho " + wallet + " (tx " + txHash
                            + ") không khớp phân bổ " + allocationId);
            return;
        }
        Map<String, Object> a = rows.getFirst();
        if (!"PAID".equals(a.get("status"))) {
            return;
        }
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                UPDATE distribution.allocation SET status = 'MINTED', minted_tx_hash = ?, minted_block = ?, minted_at = ?,
                    mint_failure_reason = NULL
                WHERE id = ?
                """, txHash.toLowerCase(Locale.ROOT), block, now, allocationId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("allocationId", allocationId.toString());
        data.put("wallet", wallet);
        data.put("quantity", quantity);
        data.put("txHash", txHash);
        data.put("seriesId", a.get("series_id").toString());
        data.put("amountMinor", String.valueOf(args.get("amountMinor")));
        data.put("paymentRoot", String.valueOf(args.get("paymentRoot")));
        events.publish(COMPLETED_EVENT, "allocation/" + allocationId, data);
        audit.record("ALLOCATION_MINTED", "allocation/" + allocationId, data);
    }

    /** Ngược của {@link AllocationMerkle#uuidToBytes32} (Q43). */
    static UUID uuidOf(String bytes32) {
        ByteBuffer b = ByteBuffer.wrap(Numeric.hexStringToByteArray(bytes32));
        b.position(16);
        return new UUID(b.getLong(), b.getLong());
    }

    /** R4 (Q138): tổng số lượng đã mint lên tới khối {@code block} của các phân bổ thuộc series. */
    public long mintedUpTo(UUID seriesId, long block) {
        Long sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(a.quantity), 0) FROM distribution.allocation a
                JOIN distribution.allocation_list l ON l.id = a.allocation_list_id
                JOIN distribution.offering o ON o.id = l.offering_id
                WHERE o.series_id = ? AND a.status IN ('MINTED', 'CONFIRMED') AND a.minted_block <= ?
                """, Long.class, seriesId, block);
        return sum == null ? 0 : sum;
    }

    /** Series đã có mục mint (đối tượng của R4). */
    public List<UUID> seriesWithMints() {
        return jdbc.queryForList("""
                SELECT DISTINCT o.series_id FROM distribution.allocation a
                JOIN distribution.allocation_list l ON l.id = a.allocation_list_id
                JOIN distribution.offering o ON o.id = l.offering_id
                WHERE a.status IN ('MINTED', 'CONFIRMED')
                """, UUID.class);
    }
}
