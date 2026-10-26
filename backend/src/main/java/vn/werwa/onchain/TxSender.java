package vn.werwa.onchain;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.werwa.onchain.ChainNodes.ChainUnavailable;
import vn.werwa.onchain.ChainNodes.RejectedByNode;

/**
 * Gửi raw tx đã ký qua V1 rồi V2 (Q14). Gửi ngay sau commit; giao dịch còn BUILT, hoặc đã SENT mà quá
 * {@code werwa.onchain.resend-after} chưa có receipt, được gửi lại đúng raw tx đó, nên không bao giờ sinh giao dịch thứ hai.
 */
public class TxSender {

    private static final Logger log = LoggerFactory.getLogger(TxSender.class);

    private final ChainNodes nodes;
    private final JdbcTemplate jdbc;
    private final OnchainProperties props;
    private final Clock clock;

    public TxSender(ChainNodes nodes, JdbcTemplate jdbc, OnchainProperties props, Clock clock) {
        this.nodes = nodes;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    /** Gửi một giao dịch; lỗi thì để việc định kỳ gửi lại. */
    public void sendQuietly(UUID id) {
        try {
            send(id);
        } catch (RuntimeException e) {
            log.warn("Chưa gửi được giao dịch chuỗi {}: {}; sẽ gửi lại", id, e.getMessage());
        }
    }

    /** Gửi lại giao dịch còn BUILT, hoặc SENT quá lâu chưa lên khối. */
    public void resendPending() {
        List<UUID> due = jdbc.queryForList("""
                SELECT id FROM onchain.chain_tx
                WHERE chain_id = ? AND (status = 'BUILT' OR (status = 'SENT' AND sent_at < ?))
                ORDER BY from_address, nonce
                """, UUID.class, nodes.chainId(), Timestamp.from(clock.instant().minus(props.resendAfter())));
        due.forEach(this::sendQuietly);
    }

    void send(UUID id) {
        String raw = jdbc.queryForObject(
                "SELECT raw_tx FROM onchain.chain_tx WHERE id = ? AND status IN ('BUILT', 'SENT')", String.class, id);
        try {
            nodes.sendRaw(raw);
        } catch (RejectedByNode e) {
            // Ví dụ nonce đã dùng: không gửi lại vô hạn; theo dõi receipt quyết định kết quả, quá hạn thì báo lỗi.
            log.warn("Node từ chối giao dịch chuỗi {}: {}", id, e.getMessage());
            jdbc.update("UPDATE onchain.chain_tx SET failure_reason = ?, send_attempts = send_attempts + 1, "
                    + "sent_at = coalesce(sent_at, ?), status = 'SENT' WHERE id = ? AND status IN ('BUILT', 'SENT')",
                    e.getMessage(), Timestamp.from(clock.instant()), id);
            return;
        } catch (ChainUnavailable e) {
            throw e;
        }
        jdbc.update("UPDATE onchain.chain_tx SET status = 'SENT', sent_at = ?, send_attempts = send_attempts + 1 "
                + "WHERE id = ? AND status IN ('BUILT', 'SENT')", Timestamp.from(clock.instant()), id);
    }
}
