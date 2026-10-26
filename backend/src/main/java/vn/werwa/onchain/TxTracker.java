package vn.werwa.onchain;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import vn.werwa.platform.events.Events;

/**
 * Chốt kết quả giao dịch đã gửi. Giao dịch chỉ sang MINED (hoặc FAILED nếu receipt báo revert) khi indexer đã đọc tới khối
 * của nó, đúng quy ước "trạng thái ngoài chuỗi chỉ đổi sau khi giao dịch đã lên khối và indexer đã đọc sự kiện"
 * (CLAUDE.md). Kết quả phát {@code werwa.onchain.tx.mined.v1} hoặc {@code werwa.onchain.tx.failed.v1} cùng giao dịch DB.
 */
public class TxTracker {

    static final String MINED = "werwa.onchain.tx.mined.v1";
    static final String FAILED = "werwa.onchain.tx.failed.v1";

    private final ChainNodes nodes;
    private final Indexer indexer;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Events events;
    private final Clock clock;

    public TxTracker(
            ChainNodes nodes, Indexer indexer, JdbcTemplate jdbc, TransactionTemplate tx, Events events, Clock clock) {
        this.nodes = nodes;
        this.indexer = indexer;
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.clock = clock;
    }

    record Sent(UUID id, String hash, String subjectRef, String purpose) {}

    public void track() {
        List<Sent> sent = jdbc.query(
                "SELECT id, tx_hash, subject_ref, purpose FROM onchain.chain_tx WHERE chain_id = ? AND status = 'SENT' ORDER BY created_at",
                (rs, i) -> new Sent(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)),
                nodes.chainId());
        if (sent.isEmpty()) {
            return;
        }
        long indexed = indexer.lastIndexedBlock();
        for (Sent s : sent) {
            Optional<TransactionReceipt> receipt = nodes.read("eth_getTransactionReceipt",
                    w -> w.ethGetTransactionReceipt(s.hash()).send().getTransactionReceipt());
            if (receipt.isEmpty() || receipt.get().getBlockNumber().longValueExact() > indexed) {
                continue;
            }
            finish(s, receipt.get());
        }
    }

    private void finish(Sent s, TransactionReceipt receipt) {
        long block = receipt.getBlockNumber().longValueExact();
        boolean ok = receipt.isStatusOK();
        tx.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE onchain.chain_tx SET status = ?, block_number = ?, finished_at = ?, failure_reason = ?
                    WHERE id = ? AND status = 'SENT'
                    """, ok ? "MINED" : "FAILED", block, Timestamp.from(clock.instant()), ok ? null : "REVERTED", s.id());
            if (changed == 0) {
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("txId", s.id().toString());
            if (ok) {
                data.put("txHash", s.hash());
                data.put("block", block);
                data.put("subjectRef", s.subjectRef());
                data.put("purpose", s.purpose());
                events.publish(MINED, "chain-tx/" + s.id(), data);
            } else {
                data.put("reason", "REVERTED");
                data.put("txHash", s.hash());
                data.put("block", block);
                data.put("subjectRef", s.subjectRef());
                data.put("purpose", s.purpose());
                events.publish(FAILED, "chain-tx/" + s.id(), data);
            }
        });
    }
}
