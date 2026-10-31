package vn.werwa.integration.bank;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.id.Ids;

/**
 * Báo có của BIDV (US-3.05-AC1, US-5.02-AC3; Q50): lưu nguyên văn, chặn trùng theo {@code bankRef}, phát
 * {@code werwa.integration.bank.credit_received.v1} trong cùng giao dịch (outbox).
 */
public class InboundBankService {

    static final String CREDIT_RECEIVED = "werwa.integration.bank.credit_received.v1";

    private final JdbcTemplate jdbc;
    private final Events events;
    private final AuditLog audit;
    private final Clock clock;

    public InboundBankService(JdbcTemplate jdbc, Events events, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    public record Credit(
            String bankRef, String virtualAccount, long amountMinor, String currency, LocalDate valueDate, String payerName,
            String remittanceInfo) {}

    /** Kết quả nhận: mã thông điệp và có phải lần nhận trùng không. */
    public record Received(UUID messageId, boolean duplicate) {}

    @Transactional
    public Received credit(Credit c, byte[] raw, String signature) {
        UUID id = Ids.newId();
        int inserted = jdbc.update("""
                INSERT INTO integration.inbound_message
                    (id, counterparty, message_type, idempotency_key, raw_payload, signature, signature_valid, received_at,
                     processing_status)
                VALUES (?, 'BIDV', 'bidvCreditNotification', ?, ?, ?, true, ?, 'PROCESSED')
                ON CONFLICT (counterparty, idempotency_key) WHERE signature_valid DO NOTHING
                """, id, c.bankRef(), new String(raw, StandardCharsets.UTF_8), signature, Timestamp.from(clock.instant()));
        if (inserted == 0) {
            List<UUID> existing = jdbc.queryForList("""
                    SELECT id FROM integration.inbound_message
                    WHERE counterparty = 'BIDV' AND idempotency_key = ? AND signature_valid
                    """, UUID.class, c.bankRef());
            jdbc.update("UPDATE integration.inbound_message SET attempts = attempts + 1 WHERE id = ?", existing.getFirst());
            return new Received(existing.getFirst(), true);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bankRef", c.bankRef());
        data.put("virtualAccount", c.virtualAccount());
        data.put("amountMinor", Long.toString(c.amountMinor()));
        data.put("currency", c.currency());
        data.put("valueDate", c.valueDate().toString());
        data.put("payerName", c.payerName());
        data.put("messageId", id.toString());
        events.publish(CREDIT_RECEIVED, "inbound-message/" + id, data);
        audit.record("BANK_CREDIT_RECEIVED", "inbound-message/" + id, data);
        return new Received(id, false);
    }
}
