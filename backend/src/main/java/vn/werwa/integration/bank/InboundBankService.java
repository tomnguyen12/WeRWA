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
    static final String PAYOUT_CONFIRMED = "werwa.integration.bank.payout_confirmed.v1";

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

    /** Một dòng trong xác nhận chi của BIDV. */
    public record PayoutLine(String lineRef, long amountMinor, String currency, String status, String bankRef) {}

    /**
     * Xác nhận chi coupon, gốc (US-5.02; Q104): lưu nguyên văn, chặn trùng theo ({@code instructionRef},
     * {@code lineRef}), phát {@code werwa.integration.bank.payout_confirmed.v1} với các dòng mới.
     */
    @Transactional
    public Received payoutConfirmation(String instructionRef, List<PayoutLine> lines, String idempotencyKey, byte[] raw,
            String signature) {
        UUID id = Ids.newId();
        String key = "payout:" + instructionRef + ":" + idempotencyKey;
        int stored = jdbc.update("""
                INSERT INTO integration.inbound_message
                    (id, counterparty, message_type, idempotency_key, raw_payload, signature, signature_valid, received_at,
                     processing_status)
                VALUES (?, 'BIDV', 'bidvPayoutConfirmation', ?, ?, ?, true, ?, 'PROCESSED')
                ON CONFLICT (counterparty, idempotency_key) WHERE signature_valid DO NOTHING
                """, id, key, new String(raw, StandardCharsets.UTF_8), signature, Timestamp.from(clock.instant()));
        if (stored == 0) {
            UUID existing = jdbc.queryForList("""
                    SELECT id FROM integration.inbound_message
                    WHERE counterparty = 'BIDV' AND idempotency_key = ? AND signature_valid
                    """, UUID.class, key).getFirst();
            jdbc.update("UPDATE integration.inbound_message SET attempts = attempts + 1 WHERE id = ?", existing);
            return new Received(existing, true);
        }
        List<Map<String, Object>> fresh = new java.util.ArrayList<>();
        for (PayoutLine l : lines) {
            int n = jdbc.update("""
                    INSERT INTO integration.payout_confirmation_line
                        (instruction_ref, line_ref, status, amount_minor, currency, bank_ref, message_id, received_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (instruction_ref, line_ref) DO NOTHING
                    """, instructionRef, l.lineRef(), l.status(), l.amountMinor(), l.currency(), l.bankRef(), id,
                    Timestamp.from(clock.instant()));
            if (n > 0) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("lineRef", l.lineRef());
                line.put("amountMinor", Long.toString(l.amountMinor()));
                line.put("currency", l.currency());
                line.put("status", l.status());
                if (l.bankRef() != null) {
                    line.put("bankRef", l.bankRef());
                }
                fresh.add(line);
            }
        }
        if (!fresh.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("instructionRef", instructionRef);
            data.put("lines", fresh);
            data.put("messageId", id.toString());
            events.publish(PAYOUT_CONFIRMED, "inbound-message/" + id, data);
        }
        audit.record("BANK_PAYOUT_CONFIRMED", "payout-instruction/" + instructionRef,
                Map.of("lines", lines.size(), "newLines", fresh.size(), "messageId", id.toString()));
        return new Received(id, fresh.isEmpty());
    }

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
