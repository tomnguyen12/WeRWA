package vn.werwa.distribution.payout;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.distribution.DistributionError;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.api.OperationalAlerts;
import vn.werwa.issuance.api.SeriesCatalog;
import vn.werwa.onchain.api.ChainCallRejected;
import vn.werwa.onchain.api.ChainTransactions;
import vn.werwa.onchain.api.ContractCalls;
import vn.werwa.onchain.api.IndexedEvents;
import vn.werwa.onchain.api.SignerKey;
import vn.werwa.onchain.api.TokenBalances;
import vn.werwa.onchain.api.TxRequest;
import vn.werwa.onchain.api.contracts.SeriesController;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.web.ApiException;

/**
 * Hủy trái phiếu sau đáo hạn và đóng series (US-2.10-AC3; máy Series cạnh 15; Q166–Q168).
 *
 * <p>Series MATURED thì gửi {@code burnRedeemed} theo lô 50 ví bằng khóa vận hành, cho các ví có số dư lớn hơn phần
 * phong tỏa (đọc qua O1 tại khối indexer đã đọc; chính sách hủy phần phong tỏa thì lấy mọi ví có số dư). Sau mỗi lô lên
 * khối: tổng cung bằng 0 thì gửi {@code close()}; còn phong tỏa thì giữ MATURED và cảnh báo thông tin.
 */
public class RedemptionService {

    public static final String PURPOSE_REDEEM = "SERIES_REDEEM";
    public static final String PURPOSE_CLOSE = "SERIES_CLOSE";
    static final int BATCH = 50;
    /** Thứ tự {@code FrozenRedemptionPolicy} trong hợp đồng (ADR-0018). */
    static final int BURN_AFTER_PAYOUT = 1;

    private final JdbcTemplate jdbc;
    private final SeriesCatalog series;
    private final ContractCalls calls;
    private final TokenBalances balances;
    private final IndexedEvents indexed;
    private final ChainTransactions chain;
    private final OperationalAlerts alerts;
    private final AuditLog audit;
    private final Clock clock;

    public RedemptionService(JdbcTemplate jdbc, SeriesCatalog series, ContractCalls calls, TokenBalances balances,
            IndexedEvents indexed, ChainTransactions chain, OperationalAlerts alerts, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.series = series;
        this.calls = calls;
        this.balances = balances;
        this.indexed = indexed;
        this.chain = chain;
        this.alerts = alerts;
        this.audit = audit;
        this.clock = clock;
    }

    /** Gửi các lô {@code burnRedeemed}. {@code manual} = Holco gửi lại qua API: không có gì để làm thì ném lỗi. */
    @Transactional
    public List<UUID> redeem(UUID seriesId, boolean manual) {
        SeriesCatalog.SeriesInfo s = series.find(seriesId)
                .orElseThrow(() -> new ApiException(DistributionError.SERIES_NOT_FOUND));
        if (!"MATURED".equals(s.status()) || s.controllerAddress() == null) {
            if (manual) {
                throw new ApiException(DistributionError.SERIES_NOT_MATURED, s.status());
            }
            return List.of();
        }
        if (series.isPaused(seriesId)) {
            // Q175: chờ mở lại
            if (manual) {
                throw new ApiException(DistributionError.SERIES_PAUSED);
            }
            return List.of();
        }
        String controller = s.controllerAddress().toLowerCase(Locale.ROOT);
        jdbc.queryForList("SELECT series_id FROM distribution.series_closure WHERE series_id = ? FOR UPDATE", UUID.class,
                seriesId);
        Set<String> pending = new HashSet<>(jdbc.queryForList("""
                SELECT unnest(wallets) FROM distribution.redemption_batch WHERE series_id = ? AND status = 'SUBMITTED'
                """, String.class, seriesId));
        SeriesController c = calls.load(SeriesController.class, controller);
        String token;
        boolean burnFrozen;
        try {
            token = c.token().send();
            burnFrozen = c.frozenRedemptionPolicy().send().intValue() == BURN_AFTER_PAYOUT;
        } catch (Exception e) {
            throw new IllegalStateException("không đọc được controller " + controller + ": " + e.getMessage(), e);
        }
        long block = indexed.indexedBlock();
        List<String> wallets = new ArrayList<>();
        for (String w : jdbc.queryForList("""
                SELECT wallet_address FROM distribution.holding_view WHERE series_id = ? AND balance > 0 ORDER BY wallet_address
                """, String.class, seriesId)) {
            if (pending.contains(w)) {
                continue;
            }
            long balance = balances.balanceOf(token, w, block);
            long frozen = balances.frozenOf(token, w, block);
            if (balance > frozen || (burnFrozen && balance > 0)) {
                wallets.add(w);
            }
        }
        if (wallets.isEmpty()) {
            if (manual) {
                throw new ApiException(DistributionError.NOTHING_TO_REDEEM);
            }
            return List.of();
        }
        List<UUID> txs = new ArrayList<>();
        Timestamp now = Timestamp.from(clock.instant());
        for (int i = 0; i < wallets.size(); i += BATCH) {
            List<String> batch = wallets.subList(i, Math.min(wallets.size(), i + BATCH));
            String data = c.burnRedeemed(batch).encodeFunctionCall();
            UUID id = Ids.newId();
            UUID tx;
            try {
                tx = chain.submit(new TxRequest(PURPOSE_REDEEM, "redemption-batch/" + id, SignerKey.HOLCO_OPERATOR,
                        controller, data));
            } catch (ChainCallRejected e) {
                alerts.raise("redeem-failed:" + seriesId, "RedemptionFailed", "critical",
                        "Hợp đồng từ chối burnRedeemed series " + seriesId + ": " + e.contractError());
                if (manual) {
                    throw e;
                }
                break;
            }
            jdbc.update("""
                    INSERT INTO distribution.redemption_batch (id, series_id, chain_tx_id, wallets, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'SUBMITTED', ?, ?)
                    """, id, seriesId, tx, (Object) batch.toArray(new String[0]), now, now);
            txs.add(tx);
        }
        audit.record("SERIES_REDEMPTION_SUBMITTED", "series/" + seriesId, Map.of("wallets", wallets.size(),
                "batches", txs.size()));
        return txs;
    }

    /** Q167: {@code Redeemed(holder, burned, frozenRemaining)}. */
    @Transactional
    public void onRedeemed(String controller, Map<String, Object> args, String txHash, long block) {
        UUID seriesId = series.findByController(controller).orElse(null);
        if (seriesId == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO distribution.redemption (id, series_id, wallet_address, burned, frozen_remaining, block_number,
                    tx_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tx_hash, wallet_address) DO NOTHING
                """, Ids.newId(), seriesId, String.valueOf(args.get("holder")).toLowerCase(Locale.ROOT),
                Long.parseLong(String.valueOf(args.get("burned"))),
                Long.parseLong(String.valueOf(args.get("frozenRemaining"))), block, txHash.toLowerCase(Locale.ROOT),
                Timestamp.from(clock.instant()));
    }

    /** {@code FrozenPositionRedeemed(holder, quantity, payoutHash)}: phần phong tỏa bị hủy (ADR-0018). */
    @Transactional
    public void onFrozenPositionRedeemed(String controller, Map<String, Object> args, String txHash) {
        jdbc.update("""
                UPDATE distribution.redemption SET frozen_burned = ? WHERE tx_hash = ? AND wallet_address = ?
                """, Long.parseLong(String.valueOf(args.get("quantity"))), txHash.toLowerCase(Locale.ROOT),
                String.valueOf(args.get("holder")).toLowerCase(Locale.ROOT));
        audit.record("FROZEN_POSITION_REDEEMED", "series-controller/" + controller, args);
    }

    /** Lô đã lên khối ({@code tx.mined}): đánh dấu, rồi xét đóng series. Trả series cần xét. */
    @Transactional
    public Optional<UUID> onBatchMined(UUID chainTxId) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT series_id FROM distribution.redemption_batch WHERE chain_tx_id = ? AND status = 'SUBMITTED'
                """, UUID.class, chainTxId);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        jdbc.update("UPDATE distribution.redemption_batch SET status = 'MINED', updated_at = ? WHERE chain_tx_id = ?",
                Timestamp.from(clock.instant()), chainTxId);
        return Optional.of(ids.getFirst());
    }

    @Transactional
    public void onTxFailed(UUID chainTxId, String reason) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT series_id FROM distribution.redemption_batch WHERE chain_tx_id = ? AND status = 'SUBMITTED'
                """, UUID.class, chainTxId);
        if (!ids.isEmpty()) {
            jdbc.update("UPDATE distribution.redemption_batch SET status = 'FAILED', updated_at = ? WHERE chain_tx_id = ?",
                    Timestamp.from(clock.instant()), chainTxId);
            alerts.raise("redeem-failed:" + ids.getFirst(), "RedemptionFailed", "critical",
                    "Giao dịch burnRedeemed series " + ids.getFirst() + " bị từ chối: " + reason);
        }
        jdbc.update("""
                UPDATE distribution.series_closure SET status = 'FAILED', updated_at = ? WHERE chain_tx_id = ?
                """, Timestamp.from(clock.instant()), chainTxId);
    }

    /**
     * Q167: không còn lô đang chờ thì đọc tổng cung qua O1; bằng 0 thì gửi {@code close()} (một lần), còn phong tỏa thì
     * cảnh báo thông tin và giữ MATURED.
     */
    @Transactional
    public void considerClose(UUID seriesId) {
        Integer open = jdbc.queryForObject("""
                SELECT count(*) FROM distribution.redemption_batch WHERE series_id = ? AND status = 'SUBMITTED'
                """, Integer.class, seriesId);
        if (open != null && open > 0) {
            return;
        }
        SeriesCatalog.SeriesInfo s = series.find(seriesId).orElseThrow();
        if (!"MATURED".equals(s.status()) || s.controllerAddress() == null) {
            return;
        }
        String controller = s.controllerAddress().toLowerCase(Locale.ROOT);
        SeriesController c = calls.load(SeriesController.class, controller);
        long supply;
        try {
            supply = balances.totalSupply(c.token().send(), indexed.indexedBlock());
        } catch (Exception e) {
            throw new IllegalStateException("không đọc được tổng cung của " + controller + ": " + e.getMessage(), e);
        }
        Map<String, Object> closure = jdbc.queryForList("""
                SELECT status FROM distribution.series_closure WHERE series_id = ? FOR UPDATE
                """, seriesId).stream().findFirst().orElse(null);
        Timestamp now = Timestamp.from(clock.instant());
        if (supply > 0) {
            if (closure == null) {
                jdbc.update("""
                        INSERT INTO distribution.series_closure (series_id, status, updated_at) VALUES (?, 'FROZEN_REMAINING', ?)
                        """, seriesId, now);
                alerts.raise("matured-with-frozen:" + seriesId, "MaturedWithFrozen", "info",
                        "Series " + seriesId + " đã đáo hạn, còn " + supply + " trái phiếu bị phong tỏa; chờ giải tỏa hoặc"
                                + " chuyển cưỡng chế");
            }
            return;
        }
        if (closure != null && "SUBMITTED".equals(closure.get("status"))) {
            return;
        }
        UUID tx;
        try {
            tx = chain.submit(new TxRequest(PURPOSE_CLOSE, "series/" + seriesId, SignerKey.HOLCO_OPERATOR, controller,
                    c.close().encodeFunctionCall()));
        } catch (ChainCallRejected e) {
            alerts.raise("close-failed:" + seriesId, "SeriesCloseFailed", "critical",
                    "Hợp đồng từ chối close series " + seriesId + ": " + e.contractError());
            return;
        }
        jdbc.update("""
                INSERT INTO distribution.series_closure (series_id, chain_tx_id, status, updated_at) VALUES (?, ?, 'SUBMITTED', ?)
                ON CONFLICT (series_id) DO UPDATE SET chain_tx_id = excluded.chain_tx_id, status = 'SUBMITTED',
                    updated_at = excluded.updated_at
                """, seriesId, tx, now);
        audit.record("SERIES_CLOSE_SUBMITTED", "series/" + seriesId, Map.of("chainTxId", tx.toString()));
    }

    /** Q168: tổng đã hủy khi đáo hạn tới khối {@code block}. */
    public long redeemedUpTo(UUID seriesId, long block) {
        Long sum = jdbc.queryForObject("""
                SELECT COALESCE(sum(burned), 0) FROM distribution.redemption WHERE series_id = ? AND block_number <= ?
                """, Long.class, seriesId, block);
        return sum == null ? 0 : sum;
    }
}
