package vn.werwa.distribution.payout;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.utils.Numeric;
import vn.werwa.distribution.DistributionError;
import vn.werwa.distribution.register.RegisterAttestationService;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.api.OperationalAlerts;
import vn.werwa.governance.api.SigningProposals;
import vn.werwa.issuance.api.DebtServices;
import vn.werwa.issuance.api.SeriesCatalog;
import vn.werwa.onchain.api.ChainCallRejected;
import vn.werwa.onchain.api.ChainTransactions;
import vn.werwa.onchain.api.ContractCalls;
import vn.werwa.onchain.api.IndexedEvents;
import vn.werwa.onchain.api.SignerKey;
import vn.werwa.onchain.api.TokenBalances;
import vn.werwa.onchain.api.TxRequest;
import vn.werwa.onchain.api.contracts.SeriesController;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.web.ApiException;

/**
 * Chụp danh sách chốt của kỳ lên chuỗi và VSDC xác nhận (US-3.09-AC1, AC2; máy Kỳ trả nợ cạnh 3, 4; Q155–Q161).
 *
 * <p>Khối chốt là khối cuối trước 00:00 giờ VN của ngày sau ngày chốt. Số dư và phần phong tỏa từng ví cộng dồn từ sao
 * kê (B9c) tới khối chốt, kiểm với chuỗi qua O1; khớp thì ghi {@code record_snapshot} và gửi {@code takeSnapshot} bằng
 * khóa vận hành. Indexer đọc {@code SnapshotTaken} thì tính dòng chi và đề nghị Safe VSDC ký {@code attestSnapshot};
 * đọc {@code SnapshotAttested} thì danh sách đã xác nhận, bộ chạy kỳ trả nợ (B11c) gửi lệnh chi.
 */
public class SnapshotService {

    public static final String PURPOSE_TAKE = "SNAPSHOT";
    public static final String PURPOSE_ATTEST = "SNAPSHOT_ATTEST";
    public static final String ATTESTED_EVENT = "werwa.distribution.snapshot.attested.v1";
    static final Set<String> ACTIVE = Set.of("ACTIVE", "DEFAULTED");
    private static final Set<String> OPEN = Set.of("QUEUED", "COLLECTING", "THRESHOLD_REACHED", "SENT");
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JdbcTemplate jdbc;
    private final SeriesCatalog series;
    private final DebtServices debts;
    private final IndexedEvents indexed;
    private final TokenBalances balances;
    private final ContractCalls calls;
    private final ChainTransactions chain;
    private final SigningProposals proposals;
    private final OperationalAlerts alerts;
    private final vn.werwa.governance.api.Parameters parameters;
    private final Events events;
    private final AuditLog audit;
    private final Clock clock;

    public SnapshotService(JdbcTemplate jdbc, SeriesCatalog series, DebtServices debts, IndexedEvents indexed,
            TokenBalances balances, ContractCalls calls, ChainTransactions chain, SigningProposals proposals,
            OperationalAlerts alerts, vn.werwa.governance.api.Parameters parameters, Events events, AuditLog audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.series = series;
        this.debts = debts;
        this.indexed = indexed;
        this.balances = balances;
        this.calls = calls;
        this.chain = chain;
        this.proposals = proposals;
        this.alerts = alerts;
        this.parameters = parameters;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    public record Status(String status, UUID signingProposalId, String takenTxHash, String attestedTxHash) {}

    public Optional<Status> status(UUID seriesId, int period) {
        return jdbc.query("""
                SELECT status, signing_proposal_id, taken_tx_hash, attest_tx_hash FROM distribution.record_snapshot
                WHERE series_id = ? AND period_no = ?
                """, (rs, i) -> new Status(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4)), seriesId, period).stream().findFirst();
    }

    /** Kỳ (series, số kỳ) có ngày trả là {@code today}; mỗi kỳ một lần dù có cả coupon cuối và gốc (C10). */
    public List<Map.Entry<UUID, Integer>> dueToday(LocalDate today) {
        return debts.open(today, 0).stream().filter(d -> today.equals(d.paymentDate()))
                .map(d -> Map.entry(d.seriesId(), d.periodNo())).distinct().toList();
    }

    // ---------- khối chốt ----------

    /** Khối cuối có thời gian trước 00:00 giờ VN của ngày sau {@code asOf}, trong phạm vi indexer đã đọc. */
    long recordBlock(LocalDate asOf) {
        Instant cutoff = asOf.plusDays(1).atStartOfDay(VN).toInstant();
        long hi = indexed.indexedBlock();
        if (blockTime(hi).isBefore(cutoff)) {
            return hi;
        }
        long lo = 0;
        while (lo < hi) {
            long mid = (lo + hi + 1) >>> 1;
            if (blockTime(mid).isBefore(cutoff)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private Instant blockTime(long number) {
        return indexed.block(number).map(IndexedEvents.BlockHeader::time)
                .orElseThrow(() -> new IllegalStateException("O1 không trả khối " + number));
    }

    // ---------- chụp (Q156, Q157) ----------

    /**
     * Chụp danh sách chốt kỳ {@code period} theo ngày chốt {@code asOf}. {@code manual} = Holco gọi API: không đủ điều
     * kiện thì ném lỗi; tự động thì bỏ qua (lệch chuỗi vẫn cảnh báo).
     */
    @Transactional
    public Optional<UUID> take(UUID seriesId, int period, LocalDate asOf, boolean manual) {
        SeriesCatalog.SeriesInfo s = series.find(seriesId)
                .orElseThrow(() -> new ApiException(DistributionError.SERIES_NOT_FOUND));
        if (!ACTIVE.contains(s.status()) || s.controllerAddress() == null) {
            return refuse(manual, DistributionError.SERIES_NOT_ACTIVE, s.status());
        }
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT id, status FROM distribution.record_snapshot WHERE series_id = ? AND period_no = ? FOR UPDATE
                """, seriesId, period);
        if (!existing.isEmpty()) {
            if (!"FAILED".equals(existing.getFirst().get("status"))) {
                return refuse(manual, DistributionError.SNAPSHOT_EXISTS, "kỳ " + period);
            }
            // Giao dịch takeSnapshot trước bị từ chối: chụp lại
            UUID old = (UUID) existing.getFirst().get("id");
            jdbc.update("DELETE FROM distribution.distribution_line WHERE record_snapshot_id = ?", old);
            jdbc.update("DELETE FROM distribution.snapshot_holding WHERE record_snapshot_id = ?", old);
            jdbc.update("DELETE FROM distribution.record_snapshot WHERE id = ?", old);
        }
        String controller = s.controllerAddress().toLowerCase(Locale.ROOT);
        long block = recordBlock(asOf);

        // Số dư, phần phong tỏa từng ví tới khối chốt, từ sao kê
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT wallet_address, max(wallet_id::text) AS wallet_id, max(wallet_org_id::text) AS org_id,
                       sum(CASE kind WHEN 'MINT' THEN quantity WHEN 'TRANSFER_IN' THEN quantity
                                     WHEN 'TRANSFER_OUT' THEN -quantity WHEN 'BURN' THEN -quantity ELSE 0 END) AS balance,
                       sum(CASE kind WHEN 'FREEZE' THEN quantity WHEN 'UNFREEZE' THEN -quantity ELSE 0 END) AS frozen,
                       max(token_address) AS token
                FROM distribution.holding_movement
                WHERE series_id = ? AND block_number <= ?
                GROUP BY wallet_address
                """, seriesId, block);
        String token;
        try {
            token = calls.load(SeriesController.class, controller).token().send();
        } catch (Exception e) {
            throw new IllegalStateException("không đọc được token của " + controller + ": " + e.getMessage(), e);
        }
        Map<String, Long> quantities = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String wallet = (String) r.get("wallet_address");
            long balance = ((Number) r.get("balance")).longValue();
            long frozen = ((Number) r.get("frozen")).longValue();
            long chainBalance = balances.balanceOf(token, wallet, block);
            long chainFrozen = balances.frozenOf(token, wallet, block);
            String problem = null;
            if (chainBalance != balance || chainFrozen != frozen) {
                problem = "ví " + wallet + " sao kê " + balance + "/" + frozen + ", chuỗi " + chainBalance + "/" + chainFrozen;
            } else if (balance > 0 && r.get("wallet_id") == null) {
                problem = "ví " + wallet + " chưa có trong onboarding";
            }
            if (problem != null) {
                alerts.raise("snapshot-view:" + seriesId + ":" + period, "SnapshotViewMismatch", "critical",
                        "Danh sách chốt kỳ " + period + " series " + seriesId + " tại khối " + block + ": " + problem);
                return refuse(manual, DistributionError.SNAPSHOT_VIEW_MISMATCH, problem);
            }
            if (balance > 0) {
                quantities.put(wallet, balance);
            }
        }
        String hash = RegisterAttestationService.registerHash(controller, block, quantities);
        long total = quantities.values().stream().mapToLong(Long::longValue).sum();
        UUID id = Ids.newId();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO distribution.record_snapshot (id, series_id, period_no, record_block, register_hash, holder_count,
                    total_quantity, created_at, status, series_controller)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'BUILT', ?)
                """, id, seriesId, period, block, hash, quantities.size(), total, now, controller);
        for (Map<String, Object> r : rows) {
            long balance = ((Number) r.get("balance")).longValue();
            if (balance > 0) {
                jdbc.update("""
                        INSERT INTO distribution.snapshot_holding (record_snapshot_id, wallet_id, investor_org_id, quantity,
                            frozen_quantity)
                        VALUES (?, ?::uuid, ?::uuid, ?, ?)
                        """, id, r.get("wallet_id"), r.get("org_id"), balance, ((Number) r.get("frozen")).longValue());
            }
        }
        String data = calls.load(SeriesController.class, controller).takeSnapshot(BigInteger.valueOf(period),
                BigInteger.valueOf(block), Numeric.hexStringToByteArray(hash)).encodeFunctionCall();
        UUID tx;
        try {
            tx = chain.submit(new TxRequest(PURPOSE_TAKE, "record-snapshot/" + id, SignerKey.HOLCO_OPERATOR, controller,
                    data));
        } catch (ChainCallRejected e) {
            if (manual) {
                throw e;
            }
            failed(id, e.contractError());
            return Optional.empty();
        }
        jdbc.update("UPDATE distribution.record_snapshot SET status = 'SUBMITTED', take_chain_tx_id = ? WHERE id = ?", tx,
                id);
        audit.record("SNAPSHOT_SUBMITTED", "record-snapshot/" + id, Map.of("seriesId", seriesId.toString(),
                "period", period, "recordBlock", block, "registerHash", hash, "holderCount", quantities.size(),
                "totalQuantity", total, "chainTxId", tx.toString()));
        return Optional.of(tx);
    }

    private static <T> Optional<T> refuse(boolean manual, DistributionError error, String detail) {
        if (manual) {
            throw new ApiException(error, detail);
        }
        return Optional.empty();
    }

    private void failed(UUID id, String reason) {
        jdbc.update("UPDATE distribution.record_snapshot SET status = 'FAILED', failure_reason = ? WHERE id = ?", reason,
                id);
        alerts.raise("snapshot-failed:" + id, "SnapshotFailed", "critical",
                "Giao dịch takeSnapshot của danh sách chốt " + id + " bị từ chối: " + reason);
        audit.record("SNAPSHOT_FAILED", "record-snapshot/" + id, Map.of("reason", reason));
    }

    @Transactional
    public void onTxFailed(UUID chainTxId, String reason) {
        jdbc.queryForList("""
                SELECT id FROM distribution.record_snapshot WHERE take_chain_tx_id = ? AND status = 'SUBMITTED' FOR UPDATE
                """, UUID.class, chainTxId).forEach(id -> failed(id, reason));
    }

    // ---------- lên chuỗi, VSDC xác nhận (Q158) ----------

    /** {@code SnapshotTaken(period, recordBlock, registerHash)}. Trả (series, kỳ) để tính dòng và đề nghị ký. */
    @Transactional
    public Optional<Map.Entry<UUID, Integer>> onSnapshotTaken(String controller, Map<String, Object> args, String txHash) {
        int period = Integer.parseInt(String.valueOf(args.get("period")));
        long block = Long.parseLong(String.valueOf(args.get("recordBlock")));
        String hash = String.valueOf(args.get("registerHash")).toLowerCase(Locale.ROOT);
        UUID seriesId = series.findByController(controller).orElse(null);
        int updated = seriesId == null ? 0 : jdbc.update("""
                UPDATE distribution.record_snapshot SET status = 'TAKEN', taken_tx_hash = ?
                WHERE series_id = ? AND period_no = ? AND record_block = ? AND register_hash = ? AND status IN ('BUILT', 'SUBMITTED')
                """, txHash.toLowerCase(Locale.ROOT), seriesId, period, block, hash);
        if (updated == 0) {
            Integer known = seriesId == null ? 0 : jdbc.queryForObject("""
                    SELECT count(*) FROM distribution.record_snapshot WHERE series_id = ? AND period_no = ? AND register_hash = ?
                    """, Integer.class, seriesId, period, hash);
            if (known == null || known == 0) {
                alerts.raise("snapshot-taken:" + controller + ":" + period, "SnapshotUnmatched", "critical",
                        "Controller " + controller + " ghi danh sách chốt kỳ " + period + " (" + hash + ", tx " + txHash
                                + ") không khớp bản chụp nào");
            }
            return Optional.empty();
        }
        audit.record("SNAPSHOT_TAKEN", "series/" + seriesId + "/snapshot/" + period, Map.of("txHash", txHash));
        return Optional.of(Map.entry(seriesId, period));
    }

    /** Đề nghị Safe VSDC ký {@code attestSnapshot}. {@code manual} = Holco tạo lại qua API. */
    @Transactional
    public Optional<SigningProposals.Ref> proposeAttestation(UUID seriesId, int period, boolean manual) {
        Map<String, Object> r = jdbc.queryForList("""
                SELECT id, status, register_hash, signing_proposal_id, series_controller FROM distribution.record_snapshot
                WHERE series_id = ? AND period_no = ? FOR UPDATE
                """, seriesId, period).stream().findFirst()
                .orElseThrow(() -> new ApiException(DistributionError.SNAPSHOT_NOT_FOUND, "kỳ " + period));
        String status = (String) r.get("status");
        if (!"TAKEN".equals(status) && !"ATTESTING".equals(status) || r.get("series_controller") == null) {
            return refuse(manual, DistributionError.SNAPSHOT_NOT_ATTESTABLE, status);
        }
        UUID previous = (UUID) r.get("signing_proposal_id");
        if (previous != null && proposals.status(previous).filter(OPEN::contains).isPresent()) {
            return refuse(manual, DistributionError.SNAPSHOT_ATTESTATION_PENDING, previous.toString());
        }
        String controller = (String) r.get("series_controller");
        String hash = (String) r.get("register_hash");
        String calldata = calls.load(SeriesController.class, controller).attestSnapshot(BigInteger.valueOf(period),
                Numeric.hexStringToByteArray(hash)).encodeFunctionCall();
        SigningProposals.Ref ref = proposals.create(new SigningProposals.Request(PURPOSE_ATTEST,
                "record-snapshot/" + r.get("id"), null, "vsdc", controller, "attestSnapshot", calldata, hash));
        jdbc.update("""
                UPDATE distribution.record_snapshot SET status = 'ATTESTING', signing_proposal_id = ?, overdue_alerted_at = NULL
                WHERE id = ?
                """, ref.id(), r.get("id"));
        audit.record("SNAPSHOT_ATTESTATION_PROPOSED", "record-snapshot/" + r.get("id"),
                Map.of("signingProposalId", ref.id().toString()));
        return Optional.of(ref);
    }

    /** {@code SnapshotAttested(period, registerHash, registrar)}. */
    @Transactional
    public void onSnapshotAttested(String controller, Map<String, Object> args, String txHash) {
        int period = Integer.parseInt(String.valueOf(args.get("period")));
        String hash = String.valueOf(args.get("registerHash")).toLowerCase(Locale.ROOT);
        UUID seriesId = series.findByController(controller).orElse(null);
        List<UUID> ids = seriesId == null ? List.of() : jdbc.queryForList("""
                SELECT id FROM distribution.record_snapshot WHERE series_id = ? AND period_no = ? AND register_hash = ?
                  AND status <> 'ATTESTED' FOR UPDATE
                """, UUID.class, seriesId, period, hash);
        if (ids.isEmpty()) {
            Integer done = seriesId == null ? 0 : jdbc.queryForObject("""
                    SELECT count(*) FROM distribution.record_snapshot WHERE series_id = ? AND period_no = ? AND register_hash = ?
                    """, Integer.class, seriesId, period, hash);
            if (done == null || done == 0) {
                alerts.raise("snapshot-attested:" + controller + ":" + period, "SnapshotUnmatched", "critical",
                        "Controller " + controller + " ghi xác nhận danh sách chốt kỳ " + period + " (tx " + txHash
                                + ") không khớp bản chụp nào");
            }
            return;
        }
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                UPDATE distribution.record_snapshot SET status = 'ATTESTED', registrar_attested_at = ?, attest_tx_hash = ?
                WHERE id = ?
                """, now, txHash.toLowerCase(Locale.ROOT), ids.getFirst());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seriesId", seriesId.toString());
        data.put("period", period);
        data.put("registerHash", hash);
        data.put("txHash", txHash);
        events.publish(ATTESTED_EVENT, "series/" + seriesId + "/snapshot/" + period, data);
        audit.record("SNAPSHOT_ATTESTED", "record-snapshot/" + ids.getFirst(), data);
    }

    // ---------- nhắc VSDC (Q159) ----------

    @Transactional
    public int alertOverdue(Instant now) {
        long hours = parameters.integer("distribution.snapshot-attestation-hours", 4);
        int count = 0;
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT id, series_id, period_no, created_at FROM distribution.record_snapshot
                WHERE status IN ('TAKEN', 'ATTESTING') AND overdue_alerted_at IS NULL AND series_controller IS NOT NULL
                """)) {
            Instant created = ((Timestamp) r.get("created_at")).toInstant();
            if (now.isAfter(created.plusSeconds(hours * 3600))) {
                alerts.raise("snapshot-overdue:" + r.get("id"), "SnapshotAttestationOverdue", "warning",
                        "VSDC chưa xác nhận danh sách chốt kỳ " + r.get("period_no") + " series " + r.get("series_id")
                                + " sau " + hours + " giờ");
                jdbc.update("UPDATE distribution.record_snapshot SET overdue_alerted_at = ? WHERE id = ?",
                        Timestamp.from(now), r.get("id"));
                count++;
            }
        }
        return count;
    }
}
