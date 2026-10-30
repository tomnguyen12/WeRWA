package vn.werwa.issuance.series;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.utils.Numeric;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.api.OperationalAlerts;
import vn.werwa.issuance.IssuanceError;
import vn.werwa.onchain.api.ChainCallRejected;
import vn.werwa.onchain.api.ChainTransactions;
import vn.werwa.onchain.api.ContractCalls;
import vn.werwa.onchain.api.SignerKey;
import vn.werwa.onchain.api.TxRequest;
import vn.werwa.onchain.api.contracts.SeriesController;
import vn.werwa.platform.web.ApiException;

/**
 * Đáo hạn (US-2.10-AC2; máy Series cạnh 14; Q164, Q165): kỳ gốc đã đối soát thì gửi {@code mature(payoutHash)} bằng khóa
 * vận hành. Series sang MATURED khi indexer đọc {@code StateChanged} ({@link SeriesDeployment#applyState}).
 */
public class SeriesMaturity {

    public static final String PURPOSE = "SERIES_MATURE";
    static final Set<String> MATURABLE = Set.of("ACTIVE", "DEFAULTED");

    private final JdbcTemplate jdbc;
    private final ContractCalls calls;
    private final ChainTransactions chain;
    private final OperationalAlerts alerts;
    private final AuditLog audit;
    private final Clock clock;

    public SeriesMaturity(JdbcTemplate jdbc, ContractCalls calls, ChainTransactions chain, OperationalAlerts alerts,
            AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.calls = calls;
        this.chain = chain;
        this.alerts = alerts;
        this.audit = audit;
        this.clock = clock;
    }

    /** Gửi {@code mature}. {@code manual} = Holco gửi lại qua API: không đủ điều kiện hay bị từ chối thì ném lỗi. */
    @Transactional
    public Optional<UUID> submit(UUID seriesId, boolean manual) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.status, s.controller_address, d.status AS debt_status, d.payout_hash
                FROM issuance.series s
                LEFT JOIN issuance.series_event e ON e.series_id = s.id AND e.event_type = 'MATURITY'
                LEFT JOIN issuance.debt_service d ON d.series_event_id = e.id
                WHERE s.id = ? FOR UPDATE OF s
                """, seriesId);
        if (rows.isEmpty()) {
            throw new ApiException(IssuanceError.SERIES_NOT_FOUND);
        }
        Map<String, Object> r = rows.getFirst();
        if (!MATURABLE.contains(r.get("status")) || r.get("controller_address") == null
                || !"RECONCILED".equals(r.get("debt_status")) || r.get("payout_hash") == null) {
            if (manual) {
                throw new ApiException(IssuanceError.SERIES_NOT_MATURABLE,
                        "series " + r.get("status") + ", kỳ gốc " + r.get("debt_status"));
            }
            return Optional.empty();
        }
        String controller = ((String) r.get("controller_address")).toLowerCase(Locale.ROOT);
        String payoutHash = (String) r.get("payout_hash");
        String data = calls.load(SeriesController.class, controller).mature(Numeric.hexStringToByteArray(payoutHash))
                .encodeFunctionCall();
        UUID tx;
        try {
            tx = chain.submit(new TxRequest(PURPOSE, "series/" + seriesId, SignerKey.HOLCO_OPERATOR, controller, data));
        } catch (ChainCallRejected e) {
            alerts.raise("mature-failed:" + seriesId, "MatureFailed", "critical",
                    "Hợp đồng từ chối mature series " + seriesId + ": " + e.contractError());
            audit.record("SERIES_MATURE_REJECTED", "series/" + seriesId, Map.of("reason", e.contractError()));
            if (manual) {
                throw e;
            }
            return Optional.empty();
        }
        jdbc.update("UPDATE issuance.series SET mature_chain_tx_id = ?, updated_at = ? WHERE id = ?", tx,
                Timestamp.from(clock.instant()), seriesId);
        audit.record("SERIES_MATURE_SUBMITTED", "series/" + seriesId, Map.of("chainTxId", tx.toString(),
                "payoutHash", payoutHash));
        return Optional.of(tx);
    }

    /** Giao dịch mature revert trên chuỗi. */
    @Transactional
    public void onTxFailed(UUID chainTxId, String reason) {
        jdbc.queryForList("SELECT id FROM issuance.series WHERE mature_chain_tx_id = ?", UUID.class, chainTxId)
                .forEach(id -> {
                    jdbc.update("UPDATE issuance.series SET mature_chain_tx_id = NULL WHERE id = ?", id);
                    alerts.raise("mature-failed:" + id, "MatureFailed", "critical",
                            "Giao dịch mature series " + id + " bị từ chối: " + reason);
                });
    }
}
