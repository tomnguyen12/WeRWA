package vn.werwa.distribution.payout;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.distribution.DistributionError;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.issuance.api.SeriesCatalog;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.security.Role;
import vn.werwa.platform.web.ApiException;

/**
 * Dòng chi trả coupon của một kỳ từ danh sách chốt (US-2.09; Q68–Q72). Chụp sổ, VSDC xác nhận, lệnh chi làm ở B11b.
 */
public class PayoutService {

    private final JdbcTemplate jdbc;
    private final SeriesCatalog series;
    private final AuditLog audit;
    private final Clock clock;

    public PayoutService(JdbcTemplate jdbc, SeriesCatalog series, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.series = series;
        this.audit = audit;
        this.clock = clock;
    }

    public record Snapshot(
            UUID seriesId, int periodNo, long recordBlock, String registerHash, Instant registrarAttestedAt, int holderCount,
            long totalQuantity, Integer ratePpm, String currency, Long amountDueMinor, Long roundingResidualMinor,
            List<CouponCalculator.Line> lines) {}

    /** Tính (hoặc tính lại khi chưa có lệnh chi) các dòng chi trả của kỳ. */
    @Transactional
    public CouponCalculator.Result calculate(UUID seriesId, int periodNo) {
        List<Map<String, Object>> snaps = jdbc.queryForList(
                "SELECT id FROM distribution.record_snapshot WHERE series_id = ? AND period_no = ? FOR UPDATE", seriesId,
                periodNo);
        if (snaps.isEmpty()) {
            throw new ApiException(DistributionError.SNAPSHOT_NOT_FOUND, "kỳ " + periodNo);
        }
        UUID snapshot = (UUID) snaps.getFirst().get("id");
        SeriesCatalog.Period p = series.period(seriesId, periodNo)
                .orElseThrow(() -> new ApiException(DistributionError.SNAPSHOT_NOT_FOUND, "series không có kỳ " + periodNo));
        if (p.ratePpm() == null) {
            throw new ApiException(DistributionError.PERIOD_RATE_MISSING, "kỳ " + periodNo);
        }
        Integer instructed = jdbc.queryForObject("""
                SELECT count(*) FROM distribution.distribution_line
                WHERE record_snapshot_id = ? AND line_kind = 'COUPON' AND payment_instruction_ref IS NOT NULL
                """, Integer.class, snapshot);
        if (instructed > 0) {
            throw new ApiException(DistributionError.PAYOUT_ALREADY_INSTRUCTED);
        }
        List<CouponCalculator.Holding> holdings = jdbc.query("""
                SELECT wallet_id, investor_org_id, quantity, frozen_quantity FROM distribution.snapshot_holding
                WHERE record_snapshot_id = ? ORDER BY wallet_id
                """, (rs, i) -> new CouponCalculator.Holding(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getLong(3), rs.getLong(4)), snapshot);
        DayCount.Fraction fraction = DayCount.fraction(p.dayCount(), p.periodStart(), p.periodEnd(),
                periodsPerYear(p.couponFrequency()));
        CouponCalculator.Result result = CouponCalculator.calculate(holdings,
                new CouponCalculator.Terms(p.faceValueMinor(), p.ratePpm(), p.withholdingTaxPpm(), fraction));

        jdbc.update("DELETE FROM distribution.distribution_line WHERE record_snapshot_id = ? AND line_kind = 'COUPON'",
                snapshot);
        for (CouponCalculator.Line l : result.lines()) {
            jdbc.update("""
                    INSERT INTO distribution.distribution_line
                        (id, record_snapshot_id, wallet_id, investor_org_id, quantity, gross_minor, tax_minor, net_minor,
                         currency, withheld_reason)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), snapshot, l.walletId(), l.investorOrgId(), l.quantity(), l.grossMinor(), l.taxMinor(),
                    l.netMinor(), p.currency(), l.withheldReason());
        }
        jdbc.update("UPDATE distribution.record_snapshot SET calculated_at = ? WHERE id = ?",
                Timestamp.from(clock.instant()), snapshot);
        series.recordAmountDue(seriesId, periodNo, result.amountDueMinor(), result.roundingResidualMinor());
        audit.record("PAYOUT_CALCULATED", "series/" + seriesId, Map.of("periodNo", periodNo, "lines", result.lines().size(),
                "amountDueMinor", Long.toString(result.amountDueMinor()),
                "roundingResidualMinor", Long.toString(result.roundingResidualMinor())));
        return result;
    }

    /**
     * Kỳ gốc (Q103): mỗi ví một dòng mệnh giá × số lượng không phong tỏa, không khấu trừ thuế; phần phong tỏa thành dòng
     * {@code FROZEN} giữ lại tại IPA (ADR-0018). Dùng danh sách chốt của kỳ cuối. Trả tổng số phải trả.
     */
    @Transactional
    public long calculatePrincipal(UUID seriesId, int periodNo, long faceValueMinor, String currency) {
        List<Map<String, Object>> snaps = jdbc.queryForList(
                "SELECT id FROM distribution.record_snapshot WHERE series_id = ? AND period_no = ? FOR UPDATE", seriesId,
                periodNo);
        if (snaps.isEmpty()) {
            throw new ApiException(DistributionError.SNAPSHOT_NOT_FOUND, "kỳ " + periodNo);
        }
        UUID snapshot = (UUID) snaps.getFirst().get("id");
        Integer instructed = jdbc.queryForObject("""
                SELECT count(*) FROM distribution.distribution_line
                WHERE record_snapshot_id = ? AND line_kind = 'PRINCIPAL' AND payment_instruction_ref IS NOT NULL
                """, Integer.class, snapshot);
        if (instructed > 0) {
            throw new ApiException(DistributionError.PAYOUT_ALREADY_INSTRUCTED);
        }
        jdbc.update("DELETE FROM distribution.distribution_line WHERE record_snapshot_id = ? AND line_kind = 'PRINCIPAL'",
                snapshot);
        long total = 0;
        for (Map<String, Object> h : jdbc.queryForList("""
                SELECT wallet_id, investor_org_id, quantity, frozen_quantity FROM distribution.snapshot_holding
                WHERE record_snapshot_id = ? ORDER BY wallet_id
                """, snapshot)) {
            long quantity = ((Number) h.get("quantity")).longValue();
            long frozen = ((Number) h.get("frozen_quantity")).longValue();
            for (Object[] part : new Object[][] {{quantity - frozen, null}, {frozen, "FROZEN"}}) {
                long q = (Long) part[0];
                if (q == 0) {
                    continue;
                }
                long gross = Math.multiplyExact(q, faceValueMinor);
                total += gross;
                jdbc.update("""
                        INSERT INTO distribution.distribution_line
                            (id, record_snapshot_id, wallet_id, investor_org_id, quantity, gross_minor, tax_minor, net_minor,
                             currency, withheld_reason, line_kind)
                        VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 'PRINCIPAL')
                        """, Ids.newId(), snapshot, h.get("wallet_id"), h.get("investor_org_id"), q, gross, gross, currency,
                        part[1]);
            }
        }
        audit.record("PRINCIPAL_CALCULATED", "series/" + seriesId, Map.of("periodNo", periodNo, "amountDueMinor",
                Long.toString(total)));
        return total;
    }

    static int periodsPerYear(String couponFrequency) {
        return switch (couponFrequency) {
            case "ANNUAL" -> 1;
            case "SEMI_ANNUAL" -> 2;
            case "QUARTERLY" -> 4;
            default -> throw new IllegalArgumentException(couponFrequency);
        };
    }

    /** Danh sách chốt đã tính; lưu ký chỉ thấy dòng của khách mình (tổ chức của mình). */
    public Snapshot snapshot(UUID seriesId, int periodNo) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, record_block, register_hash, registrar_attested_at, holder_count, total_quantity, calculated_at
                FROM distribution.record_snapshot WHERE series_id = ? AND period_no = ?
                """, seriesId, periodNo);
        if (rows.isEmpty()) {
            throw new ApiException(DistributionError.SNAPSHOT_NOT_FOUND, "kỳ " + periodNo);
        }
        Map<String, Object> s = rows.getFirst();
        if (s.get("calculated_at") == null) {
            throw new ApiException(DistributionError.SNAPSHOT_NOT_CALCULATED);
        }
        CurrentUser user = CurrentUser.get();
        boolean all = user.roles().contains(Role.REGISTRAR) || user.roles().contains(Role.PAYING_AGENT);
        List<CouponCalculator.Line> lines = jdbc.query("""
                SELECT wallet_id, investor_org_id, quantity, gross_minor, tax_minor, net_minor, withheld_reason
                FROM distribution.distribution_line
                WHERE record_snapshot_id = ? AND line_kind = 'COUPON' AND (? OR investor_org_id = ?)
                ORDER BY wallet_id, withheld_reason NULLS FIRST
                """, (rs, i) -> new CouponCalculator.Line(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getString(7)),
                s.get("id"), all, user.organizationId());
        SeriesCatalog.Period p = series.period(seriesId, periodNo).orElseThrow();
        SeriesCatalog.DebtService due = series.debtService(seriesId, periodNo).orElse(null);
        Timestamp attested = (Timestamp) s.get("registrar_attested_at");
        return new Snapshot(seriesId, periodNo, ((Number) s.get("record_block")).longValue(), (String) s.get("register_hash"),
                attested == null ? null : attested.toInstant(), ((Number) s.get("holder_count")).intValue(),
                ((Number) s.get("total_quantity")).longValue(), p.ratePpm(), p.currency(),
                due == null ? null : due.amountDueMinor(), due == null ? null : due.roundingResidualMinor(), lines);
    }
}
