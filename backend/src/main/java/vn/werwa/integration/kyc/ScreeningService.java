package vn.werwa.integration.kyc;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.integration.api.Screening;
import vn.werwa.platform.id.Ids;

/** Gọi nhà cung cấp đang cấu hình và lưu yêu cầu, kết quả nguyên văn vào {@code integration.outbound_message}. */
public class ScreeningService implements Screening {

    private final ScreeningProvider provider;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public ScreeningService(ScreeningProvider provider, JdbcTemplate jdbc, JsonMapper json, Clock clock) {
        this.provider = provider;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Result screen(Subject subject) {
        UUID id = Ids.newId();
        jdbc.update("""
                INSERT INTO integration.outbound_message
                    (id, counterparty, message_type, idempotency_key, raw_payload, status, created_at)
                VALUES (?, 'KYC', 'SCREENING', ?, ?::jsonb, 'SENT', ?)
                """, id, id.toString(), json.writeValueAsString(subject), Timestamp.from(clock.instant()));
        Map<String, Object> raw = provider.screen(subject);
        jdbc.update("""
                UPDATE integration.outbound_message SET raw_response = ?::jsonb, status = 'SUCCEEDED', completed_at = ?
                WHERE id = ?
                """, json.writeValueAsString(raw), Timestamp.from(clock.instant()), id);
        return new Result(provider.name(), String.valueOf(raw.get("reference")),
                Boolean.TRUE.equals(raw.get("sanctionsHit")), Boolean.TRUE.equals(raw.get("pepHit")), raw);
    }
}
