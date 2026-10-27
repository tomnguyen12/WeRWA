package vn.werwa.integration.bank;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.integration.api.VirtualAccounts;

/** Tài khoản ảo giả lập (Q51): tiền tố + 10 chữ số theo dãy số; mỗi phân bổ một tài khoản. */
public class SimulatedVirtualAccounts implements VirtualAccounts {

    private final JdbcTemplate jdbc;
    private final String prefix;
    private final Clock clock;

    public SimulatedVirtualAccounts(JdbcTemplate jdbc, String prefix, Clock clock) {
        this.jdbc = jdbc;
        this.prefix = prefix;
        this.clock = clock;
    }

    @Override
    @Transactional
    public String open(UUID allocationId, String currency) {
        List<String> existing = jdbc.queryForList(
                "SELECT account_no FROM integration.virtual_account WHERE reference_id = ?", String.class, allocationId);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        long seq = jdbc.queryForObject("SELECT nextval('integration.virtual_account_seq')", Long.class);
        String account = prefix + String.format("%010d", seq);
        jdbc.update("""
                INSERT INTO integration.virtual_account (account_no, counterparty, reference_id, currency, created_at)
                VALUES (?, 'BIDV', ?, ?, ?)
                """, account, allocationId, currency, Timestamp.from(clock.instant()));
        return account;
    }
}
