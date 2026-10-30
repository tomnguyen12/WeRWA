package vn.werwa.issuance.series;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.issuance.IssuanceError;
import vn.werwa.issuance.api.DebtServices;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.web.ApiException;

/** Trạng thái nghiệp vụ trả nợ theo kỳ (B11c; Q99, Q101, Q105). Mỗi bước ghi nhật ký; bước lặp lại không đổi gì. */
public class DebtServiceLedger implements DebtServices {

    static final String FUNDING_SHORT = "werwa.issuance.debt_service.funding_short.v1";
    static final String RECONCILED = "werwa.issuance.debt_service.reconciled.v1";

    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    private final Events events;
    private final Clock clock;

    public DebtServiceLedger(JdbcTemplate jdbc, AuditLog audit, Events events, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public List<Due> open(LocalDate today, int horizonDays) {
        return jdbc.query(SELECT + """
                 WHERE s.status IN ('ACTIVE', 'DEFAULTED') AND e.payment_date <= ?
                   AND (d.id IS NULL OR d.status IN ('CALCULATED', 'REMINDED', 'FUNDS_CHECKED', 'LATE'))
                 ORDER BY e.payment_date, e.series_id, e.event_type
                """, DebtServiceLedger::row, today.plusDays(horizonDays));
    }

    @Override
    public Optional<Due> get(UUID eventId) {
        return jdbc.query(SELECT + " WHERE e.id = ?", DebtServiceLedger::row, eventId).stream().findFirst();
    }

    @Override
    public List<Due> withStatus(String status) {
        return jdbc.query(SELECT + " WHERE d.status = ? ORDER BY e.payment_date", DebtServiceLedger::row, status);
    }

    @Override
    @Transactional
    public void recordAmountDue(UUID eventId, long amountDueMinor) {
        Due d = require(eventId);
        upsert(eventId, d.currency(), "CALCULATED");
        jdbc.update("""
                UPDATE issuance.debt_service SET amount_due_minor = ?, rounding_residual_minor = 0, calculated_at = ?,
                    updated_at = ? WHERE series_event_id = ?
                """, amountDueMinor, now(), now(), eventId);
        audit.record("DEBT_SERVICE_CALCULATED", ref(d), Map.of("amountDueMinor", Long.toString(amountDueMinor)));
    }

    @Override
    @Transactional
    public void reminded(UUID eventId, long estimatedMinor) {
        Due d = require(eventId);
        upsert(eventId, d.currency(), "REMINDED");
        jdbc.update("""
                UPDATE issuance.debt_service SET estimated_minor = ?, reminded_at = ?, updated_at = ?,
                    status = CASE WHEN status = 'CALCULATED' THEN 'REMINDED' ELSE status END
                WHERE series_event_id = ?
                """, estimatedMinor, now(), now(), eventId);
        audit.record("DEBT_SERVICE_REMINDED", ref(d), Map.of("estimatedMinor", Long.toString(estimatedMinor)));
    }

    @Override
    @Transactional
    public void fundsChecked(UUID eventId, long fundedMinor, long shortfallMinor) {
        Due d = require(eventId);
        upsert(eventId, d.currency(), "FUNDS_CHECKED");
        jdbc.update("""
                UPDATE issuance.debt_service SET funded_minor = ?, shortfall_minor = ?, funding_checked_at = ?, updated_at = ?,
                    status = CASE WHEN status IN ('CALCULATED', 'REMINDED') THEN 'FUNDS_CHECKED' ELSE status END
                WHERE series_event_id = ?
                """, fundedMinor, shortfallMinor, now(), now(), eventId);
        audit.record("DEBT_SERVICE_FUNDS_CHECKED", ref(d), Map.of("fundedMinor", Long.toString(fundedMinor),
                "shortfallMinor", Long.toString(shortfallMinor)));
        if (shortfallMinor > 0) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("seriesId", d.seriesId().toString());
            data.put("period", d.periodNo());
            data.put("shortfallMinor", Long.toString(shortfallMinor));
            data.put("currency", d.currency());
            data.put("eventType", d.eventType());
            data.put("paymentDate", d.paymentDate().toString());
            events.publish(FUNDING_SHORT, ref(d), data);
        }
    }

    @Override
    @Transactional
    public void instructed(UUID eventId, String instructionRef) {
        Due d = require(eventId);
        jdbc.update("""
                UPDATE issuance.debt_service SET status = 'INSTRUCTED', instruction_ref = ?, instructed_at = ?, updated_at = ?
                WHERE series_event_id = ? AND status <> 'INSTRUCTED'
                """, instructionRef, now(), now(), eventId);
        audit.record("DEBT_SERVICE_INSTRUCTED", ref(d), Map.of("instructionRef", instructionRef));
    }

    @Override
    @Transactional
    public void late(UUID eventId, LocalDate graceUntil) {
        Due d = require(eventId);
        jdbc.update("""
                UPDATE issuance.debt_service SET status = 'LATE', grace_until = ?, updated_at = ?
                WHERE series_event_id = ? AND status <> 'LATE'
                """, graceUntil, now(), eventId);
        audit.record("DEBT_SERVICE_LATE", ref(d), Map.of("graceUntil", graceUntil.toString()));
    }

    @Override
    @Transactional
    public void graceExpired(UUID eventId) {
        Due d = require(eventId);
        jdbc.update("""
                UPDATE issuance.debt_service SET status = 'GRACE_EXPIRED', updated_at = ?
                WHERE series_event_id = ? AND status = 'LATE'
                """, now(), eventId);
        audit.record("DEBT_SERVICE_GRACE_EXPIRED", ref(d), Map.of("graceUntil", String.valueOf(d.graceUntil())));
    }

    @Override
    @Transactional
    public void paid(UUID eventId) {
        Due d = require(eventId);
        int n = jdbc.update("""
                UPDATE issuance.debt_service SET status = 'PAID', paid_at = ?, updated_at = ?
                WHERE series_event_id = ? AND status = 'INSTRUCTED'
                """, now(), now(), eventId);
        if (n > 0) {
            audit.record("DEBT_SERVICE_PAID", ref(d), Map.of("instructionRef", String.valueOf(d.instructionRef())));
        }
    }

    @Override
    @Transactional
    public void reconciled(UUID eventId, long paidMinor, long withheldMinor, String payoutHash) {
        Due d = require(eventId);
        int n = jdbc.update("""
                UPDATE issuance.debt_service SET status = 'RECONCILED', paid_minor = ?, withheld_minor = ?, reconciled_at = ?,
                    updated_at = ?, payout_hash = ?
                WHERE series_event_id = ? AND status = 'PAID'
                """, paidMinor, withheldMinor, now(), now(), payoutHash, eventId);
        if (n == 0) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seriesId", d.seriesId().toString());
        data.put("period", d.periodNo());
        data.put("eventType", d.eventType());
        data.put("paidMinor", Long.toString(paidMinor));
        data.put("withheldMinor", Long.toString(withheldMinor));
        data.put("currency", d.currency());
        if (payoutHash != null) {
            data.put("payoutHash", payoutHash);
        }
        events.publish(RECONCILED, ref(d), data);
        audit.record("DEBT_SERVICE_RECONCILED", ref(d), data);
    }

    private Due require(UUID eventId) {
        return get(eventId).orElseThrow(() -> new ApiException(IssuanceError.SERIES_NOT_FOUND, "kỳ " + eventId));
    }

    private void upsert(UUID eventId, String currency, String status) {
        jdbc.update("""
                INSERT INTO issuance.debt_service (id, series_event_id, currency, status, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (series_event_id) DO NOTHING
                """, Ids.newId(), eventId, currency, status, now());
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static String ref(Due d) {
        return "series/" + d.seriesId() + "/" + d.eventType().toLowerCase() + "/" + d.periodNo();
    }

    private static final String SELECT = """
            SELECT e.id, e.series_id, e.event_type, e.period_no, e.record_date, e.payment_date, s.currency,
                   s.grace_business_days, s.calendars, d.status, d.amount_due_minor, d.estimated_minor, d.funded_minor,
                   d.shortfall_minor, d.grace_until, d.instruction_ref, d.calculated_at
            FROM issuance.series_event e
            JOIN issuance.series s ON s.id = e.series_id
            LEFT JOIN issuance.debt_service d ON d.series_event_id = e.id""";

    private static Due row(ResultSet rs, int i) throws SQLException {
        Array cal = rs.getArray(9);
        Timestamp calculated = rs.getTimestamp(17);
        return new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4),
                rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class), rs.getString(7), rs.getInt(8),
                cal == null ? List.of() : Arrays.asList((String[]) cal.getArray()), rs.getString(10),
                rs.getObject(11, Long.class), rs.getObject(12, Long.class), rs.getObject(13, Long.class),
                rs.getObject(14, Long.class), rs.getObject(15, LocalDate.class), rs.getString(16),
                calculated == null ? null : calculated.toInstant());
    }
}
