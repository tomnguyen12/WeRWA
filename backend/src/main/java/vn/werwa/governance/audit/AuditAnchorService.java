package vn.werwa.governance.audit;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import vn.werwa.governance.Hashes;
import vn.werwa.onchain.api.ChainCallRejected;
import vn.werwa.onchain.api.ChainTransactions;
import vn.werwa.onchain.api.ContractCalls;
import vn.werwa.onchain.api.SignerKey;
import vn.werwa.onchain.api.TxRequest;
import vn.werwa.onchain.api.contracts.AuditAnchor;

/**
 * Neo nhật ký hằng ngày lên {@code AuditAnchor} (ADR-13, US-4.08-AC3; Q21): mỗi ngày nghiệp vụ theo giờ Việt Nam một lần,
 * ghi số thứ tự và mã băm của dòng cuối cùng trong ngày (ngày không có dòng mới thì giữ dòng cuối trước đó). Gửi bằng khóa
 * vận hành Holco qua mô-đun Onchain; {@code tx.mined} chốt {@code audit_anchor} ở ANCHORED.
 */
public class AuditAnchorService {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final String PURPOSE = "AUDIT_ANCHOR";
    private static final Logger log = LoggerFactory.getLogger(AuditAnchorService.class);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ChainTransactions chain;
    private final ContractCalls calls;
    private final Clock clock;
    private final int catchUpDays;

    public AuditAnchorService(
            JdbcTemplate jdbc, TransactionTemplate tx, ChainTransactions chain, ContractCalls calls, Clock clock, int catchUpDays) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.chain = chain;
        this.calls = calls;
        this.clock = clock;
        this.catchUpDays = catchUpDays;
    }

    /** Neo mọi ngày đã qua trong cửa sổ bù mà chưa neo hoặc neo lỗi. */
    public void catchUp() {
        LocalDate yesterday = LocalDate.now(clock.withZone(BUSINESS_ZONE)).minusDays(1);
        for (LocalDate day = yesterday.minusDays(catchUpDays - 1L); !day.isAfter(yesterday); day = day.plusDays(1)) {
            LocalDate d = day;
            try {
                tx.executeWithoutResult(s -> anchor(d));
            } catch (RuntimeException e) {
                log.warn("Chưa neo được nhật ký ngày {}: {}", d, e.getMessage());
            }
        }
    }

    /** Neo một ngày; ngày đã gửi hoặc đã neo thì bỏ qua. Gọi trong giao dịch. */
    public void anchor(LocalDate day) {
        List<String> state = jdbc.queryForList(
                "SELECT status FROM governance.audit_anchor WHERE business_date = ? FOR UPDATE", String.class, day);
        if (!state.isEmpty() && !"FAILED".equals(state.getFirst())) {
            return;
        }
        Timestamp end = Timestamp.from(day.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant());
        List<Map<String, Object>> last = jdbc.queryForList(
                "SELECT seq, entry_hash FROM governance.audit_log WHERE occurred_at < ? ORDER BY seq DESC LIMIT 1", end);
        long lastSeq = last.isEmpty() ? 0 : ((Number) last.getFirst().get("seq")).longValue();
        String lastHash = last.isEmpty() ? Hashes.ZERO : (String) last.getFirst().get("entry_hash");

        String address = calls.address("auditAnchor");
        String data = calls.load(AuditAnchor.class, address)
                .anchor(new BigInteger(day.format(YYYYMMDD)), BigInteger.valueOf(lastSeq), Hashes.bytes(lastHash))
                .encodeFunctionCall();
        String status;
        UUID txId = null;
        String reason = null;
        try {
            txId = chain.submit(new TxRequest(PURPOSE, "audit-anchor/" + day, SignerKey.HOLCO_OPERATOR, address, data));
            status = "SUBMITTED";
        } catch (ChainCallRejected e) {
            // Ngày đã neo trên chuỗi mà DB không biết (ví dụ khôi phục DB): để bộ kiểm và vận hành đối chiếu.
            status = "CHAIN_ALREADY_ANCHORED".equals(e.error().code()) ? "CONFLICT" : "FAILED";
            reason = e.error().code();
        }
        jdbc.update("""
                INSERT INTO governance.audit_anchor
                    (business_date, last_seq, last_entry_hash, status, chain_tx_id, failure_reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (business_date) DO UPDATE SET last_seq = excluded.last_seq,
                    last_entry_hash = excluded.last_entry_hash, status = excluded.status,
                    chain_tx_id = excluded.chain_tx_id, failure_reason = excluded.failure_reason
                """, day, lastSeq, lastHash, status, txId, reason, Timestamp.from(clock.instant()));
    }

    /** {@code tx.mined} của giao dịch neo. */
    public void anchored(UUID chainTxId, String txHash, long block) {
        jdbc.update("""
                UPDATE governance.audit_anchor SET status = 'ANCHORED', tx_hash = ?, block_number = ?, anchored_at = ?
                WHERE chain_tx_id = ? AND status = 'SUBMITTED'
                """, txHash, block, Timestamp.from(clock.instant()), chainTxId);
    }

    /** {@code tx.failed} của giao dịch neo: lượt bù sau gửi lại. */
    public void failed(UUID chainTxId, String reason) {
        jdbc.update("""
                UPDATE governance.audit_anchor SET status = 'FAILED', failure_reason = ? WHERE chain_tx_id = ? AND status = 'SUBMITTED'
                """, reason, chainTxId);
    }
}
