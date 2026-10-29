package vn.werwa.integration.bank;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.integration.api.PayingAgent;

/**
 * Đại lý thanh toán giả lập (Q100). Mọi lệnh chi được lưu nguyên văn ở {@code integration.payout_instruction}.
 * Có {@code werwa.integration.bidv.simulator-url} thì đọc số dư và gửi lệnh chi sang giả lập BIDV (giả lập tự gọi
 * webhook xác nhận chi); không có thì số dư lấy từ cấu hình hoặc {@link #setBalance}, xác nhận chi do người demo hoặc
 * kiểm thử gửi.
 */
public class SimulatedPayingAgent implements PayingAgent {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final AuditLog audit;
    private final Clock clock;
    private final RestClient simulator;
    private final long defaultBalance;
    private final Map<String, Long> balances = new ConcurrentHashMap<>();

    public SimulatedPayingAgent(JdbcTemplate jdbc, JsonMapper json, AuditLog audit, Clock clock, String simulatorUrl,
            long defaultBalance) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
        this.simulator = simulatorUrl == null || simulatorUrl.isBlank() ? null
                : RestClient.builder().baseUrl(simulatorUrl).build();
        this.defaultBalance = defaultBalance;
    }

    /** Đặt số dư IPA giả lập (người demo, kiểm thử). */
    public void setBalance(String currency, long minor) {
        balances.put(currency, minor);
    }

    @Override
    public long ipaBalance(String currency) {
        if (simulator != null) {
            Map<?, ?> r = simulator.get().uri("/sim/ipa-balance?currency={c}", currency).retrieve().body(Map.class);
            return Long.parseLong(String.valueOf(r.get("amountMinor")));
        }
        return balances.getOrDefault(currency, defaultBalance);
    }

    @Override
    public void submitPayout(Instruction i) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instructionRef", i.instructionRef());
        payload.put("currency", i.currency());
        payload.put("lines", i.lines().stream().map(l -> Map.of("lineRef", l.lineRef(), "investorOrgId",
                l.investorOrgId().toString(), "walletId", l.walletId().toString(), "amountMinor",
                Long.toString(l.amountMinor()))).toList());
        long total = i.lines().stream().mapToLong(Line::amountMinor).sum();
        String body = json.writeValueAsString(payload);
        int inserted = jdbc.update("""
                INSERT INTO integration.payout_instruction
                    (instruction_ref, counterparty, currency, line_count, total_minor, payload, submitted_at)
                VALUES (?, 'BIDV', ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (instruction_ref) DO NOTHING
                """, i.instructionRef(), i.currency(), i.lines().size(), total, body, Timestamp.from(clock.instant()));
        if (inserted == 0) {
            return;
        }
        audit.record("BANK_PAYOUT_SUBMITTED", "payout-instruction/" + i.instructionRef(),
                Map.of("lines", i.lines().size(), "totalMinor", Long.toString(total), "currency", i.currency()));
        if (simulator != null) {
            simulator.post().uri("/sim/payouts").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
        }
    }

    /** Lệnh chi đã gửi (kiểm thử, màn hình giả lập). */
    public List<Map<String, Object>> submitted(String instructionRef) {
        return jdbc.queryForList("SELECT payload::text AS payload, total_minor, line_count FROM integration.payout_instruction"
                + " WHERE instruction_ref = ?", instructionRef);
    }
}
