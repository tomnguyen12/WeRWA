package vn.werwa.distribution.payment;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.utils.Numeric;
import vn.werwa.distribution.DistributionError;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.api.OperationalAlerts;
import vn.werwa.governance.api.SigningProposals;
import vn.werwa.issuance.api.SeriesCatalog;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.web.ApiException;
import vn.werwa.platform.web.Paging;

/**
 * BIDV ký gốc lô xác nhận tiền trên chuỗi (US-3.05-AC3; Q129–Q132): lô vừa gom thì tạo đề nghị ký Safe BIDV gọi
 * {@code addPaymentBatch(root, batchHash)}; indexer đọc {@code PaymentBatchAdded} thì lô SIGNED và phát
 * {@code payment_batch.signed.v1} cho bộ điều phối mint.
 */
public class PaymentBatchSigning {

    public static final String PURPOSE = "PAYMENT_BATCH_SIGN";
    public static final String SIGNED_EVENT = "werwa.distribution.payment_batch.signed.v1";
    private static final Set<String> OPEN = Set.of("QUEUED", "COLLECTING", "THRESHOLD_REACHED", "SENT");

    private final JdbcTemplate jdbc;
    private final SeriesCatalog series;
    private final SigningProposals proposals;
    private final OperationalAlerts alerts;
    private final Events events;
    private final AuditLog audit;
    private final Clock clock;

    public PaymentBatchSigning(JdbcTemplate jdbc, SeriesCatalog series, SigningProposals proposals,
            OperationalAlerts alerts, Events events, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.series = series;
        this.proposals = proposals;
        this.alerts = alerts;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    public record Item(UUID allocationId, long amountMinor, String currency, String bankRefHash, String leaf,
            String allocationStatus, String mintTxHash, String mintFailureReason) {}

    public record Batch(UUID id, UUID offeringId, UUID seriesId, int batchNo, String status, int itemCount,
            long totalAmountMinor, String currency, String merkleRoot, String batchHash, UUID signingProposalId,
            String signedTxHash, Instant createdAt, List<Item> items) {}

    private static final String SELECT = """
            SELECT b.id, b.offering_id, o.series_id, b.batch_no, b.status, b.item_count, b.total_amount_minor, b.currency,
                   b.merkle_root, b.batch_hash, b.signing_proposal_id, b.signed_tx_hash, b.created_at
            FROM distribution.payment_batch b JOIN distribution.offering o ON o.id = b.offering_id
            """;

    private static Batch row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Batch(rs.getObject("id", UUID.class), rs.getObject("offering_id", UUID.class),
                rs.getObject("series_id", UUID.class), rs.getInt("batch_no"), rs.getString("status"),
                rs.getInt("item_count"), rs.getLong("total_amount_minor"), rs.getString("currency").trim(),
                rs.getString("merkle_root"), rs.getString("batch_hash"), rs.getObject("signing_proposal_id", UUID.class),
                rs.getString("signed_tx_hash"), rs.getTimestamp("created_at").toInstant(), List.of());
    }

    public Batch get(UUID id) {
        Batch b = jdbc.query(SELECT + " WHERE b.id = ?", PaymentBatchSigning::row, id).stream().findFirst()
                .orElseThrow(() -> new ApiException(DistributionError.PAYMENT_BATCH_NOT_FOUND));
        return withItems(b);
    }

    public Paging.Page<Batch> list(UUID offeringId, String status, String cursor, Integer limit) {
        int size = Paging.limit(limit);
        UUID before = Paging.decodeCursor(cursor);
        StringBuilder sql = new StringBuilder(SELECT + " WHERE b.offering_id = ?");
        List<Object> args = new ArrayList<>(List.of(offeringId));
        if (status != null) {
            sql.append(" AND b.status = ?");
            args.add(status);
        }
        if (before != null) {
            sql.append(" AND b.id < ?");
            args.add(before);
        }
        sql.append(" ORDER BY b.id DESC LIMIT ?");
        args.add(size + 1);
        Paging.Page<Batch> page = Paging.Page.of(jdbc.query(sql.toString(), PaymentBatchSigning::row, args.toArray()),
                size, Batch::id);
        return new Paging.Page<>(page.items().stream().map(this::withItems).toList(), page.nextCursor());
    }

    private Batch withItems(Batch b) {
        List<Item> items = jdbc.query("""
                SELECT p.allocation_id, p.amount_minor, p.currency, p.bank_ref, p.payment_leaf_hash, a.status,
                       a.minted_tx_hash, a.mint_failure_reason
                FROM distribution.payment_confirmation p JOIN distribution.allocation a ON a.id = p.allocation_id
                WHERE p.payment_batch_id = ? ORDER BY p.allocation_id
                """, (rs, i) -> new Item(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3).trim(),
                vn.werwa.distribution.allocation.AllocationMerkle.hex(
                        vn.werwa.distribution.allocation.AllocationMerkle.bankRefHash(rs.getString(4))),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)), b.id());
        return new Batch(b.id(), b.offeringId(), b.seriesId(), b.batchNo(), b.status(), b.itemCount(),
                b.totalAmountMinor(), b.currency(), b.merkleRoot(), b.batchHash(), b.signingProposalId(),
                b.signedTxHash(), b.createdAt(), items);
    }

    /** Q130, Q131: tạo (hoặc tạo lại) đề nghị ký Safe BIDV cho lô chưa ký và không có đề nghị đang mở. */
    @Transactional
    public SigningProposals.Ref propose(UUID batchId) {
        Map<String, Object> row = jdbc.queryForList("""
                SELECT b.status, b.signing_proposal_id, b.merkle_root, b.batch_hash, b.batch_no, b.item_count,
                       b.total_amount_minor, b.currency, o.series_id
                FROM distribution.payment_batch b JOIN distribution.offering o ON o.id = b.offering_id
                WHERE b.id = ? FOR UPDATE OF b
                """, batchId).stream().findFirst()
                .orElseThrow(() -> new ApiException(DistributionError.PAYMENT_BATCH_NOT_FOUND));
        if ("SIGNED".equals(row.get("status"))) {
            throw new ApiException(DistributionError.PAYMENT_BATCH_NOT_SIGNABLE);
        }
        UUID previous = (UUID) row.get("signing_proposal_id");
        if (previous != null && proposals.status(previous).filter(OPEN::contains).isPresent()) {
            throw new ApiException(DistributionError.PAYMENT_BATCH_SIGNING_PENDING, previous.toString());
        }
        SeriesCatalog.SeriesInfo s = series.find((UUID) row.get("series_id")).orElseThrow();
        if (s.controllerAddress() == null) {
            throw new ApiException(DistributionError.SERIES_NOT_DEPLOYED);
        }
        String controller = s.controllerAddress().toLowerCase(Locale.ROOT);
        String batchHash = (String) row.get("batch_hash");
        if (batchHash == null) {
            // Lô gom trước V24 chưa có batchHash
            batchHash = PaymentService.batchHash(controller, ((Number) row.get("batch_no")).intValue(),
                    ((Number) row.get("item_count")).intValue(), ((Number) row.get("total_amount_minor")).longValue(),
                    String.valueOf(row.get("currency")).trim());
            jdbc.update("UPDATE distribution.payment_batch SET batch_hash = ? WHERE id = ?", batchHash, batchId);
        }
        String calldata = FunctionEncoder.encode(new Function("addPaymentBatch", List.of(
                new Bytes32(Numeric.hexStringToByteArray((String) row.get("merkle_root"))),
                new Bytes32(Numeric.hexStringToByteArray(batchHash))), List.of()));
        SigningProposals.Ref ref = proposals.create(new SigningProposals.Request(PURPOSE, "payment-batch/" + batchId, null,
                "bidv", controller, "addPaymentBatch", calldata, batchHash));
        jdbc.update("UPDATE distribution.payment_batch SET status = 'SIGNING', signing_proposal_id = ? WHERE id = ?",
                ref.id(), batchId);
        audit.record("PAYMENT_BATCH_SIGNING_PROPOSED", "payment-batch/" + batchId,
                Map.of("signingProposalId", ref.id().toString(), "batchHash", batchHash));
        return ref;
    }

    /**
     * Q132: {@code PaymentBatchAdded(root, batchHash)} của controller. Lô chưa ký có đúng gốc trong series đó thì SIGNED;
     * đã SIGNED thì bỏ qua; không khớp thì cảnh báo.
     */
    @Transactional
    public void onPaymentBatchAdded(String controller, Map<String, Object> args, String txHash) {
        String root = String.valueOf(args.get("root")).toLowerCase(Locale.ROOT);
        String batchHash = String.valueOf(args.get("batchHash")).toLowerCase(Locale.ROOT);
        UUID seriesId = series.findByController(controller)
                .orElseThrow(() -> new IllegalStateException("chưa biết series của controller " + controller));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.id, b.status, b.item_count, b.batch_hash, b.offering_id FROM distribution.payment_batch b
                JOIN distribution.offering o ON o.id = b.offering_id
                WHERE o.series_id = ? AND b.merkle_root = ? FOR UPDATE OF b
                """, seriesId, root);
        if (rows.isEmpty() || (rows.getFirst().get("batch_hash") != null
                && !batchHash.equals(rows.getFirst().get("batch_hash")))) {
            alerts.raise("payment-batch-added:" + controller + ":" + root, "PaymentBatchUnmatched", "critical",
                    "Controller " + controller + " ghi lô gốc " + root + " (tx " + txHash
                            + ") không khớp lô xác nhận nào của nền tảng");
            return;
        }
        Map<String, Object> row = rows.getFirst();
        if ("SIGNED".equals(row.get("status"))) {
            return;
        }
        UUID batchId = (UUID) row.get("id");
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                UPDATE distribution.payment_batch SET status = 'SIGNED', signed_tx_hash = ?, signed_at = ?, batch_hash = ?
                WHERE id = ?
                """, txHash.toLowerCase(Locale.ROOT), now, batchHash, batchId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchId", batchId.toString());
        data.put("merkleRoot", root);
        data.put("itemCount", ((Number) row.get("item_count")).intValue());
        data.put("batchHash", batchHash);
        data.put("offeringId", row.get("offering_id").toString());
        data.put("seriesId", seriesId.toString());
        data.put("txHash", txHash);
        events.publish(SIGNED_EVENT, "payment-batch/" + batchId, data);
        audit.record("PAYMENT_BATCH_SIGNED", "payment-batch/" + batchId, data);
    }
}
