package vn.werwa.distribution.payout;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import vn.werwa.governance.api.BusinessCalendar;
import vn.werwa.governance.api.Notifications;
import vn.werwa.issuance.api.DebtServices;
import vn.werwa.issuance.api.DebtServices.Due;
import vn.werwa.issuance.api.SeriesCatalog;
import vn.werwa.integration.api.PayingAgent;
import vn.werwa.platform.registry.HoldingsSummaries;
import vn.werwa.platform.security.Role;

/**
 * Điều phối phần ngoài chuỗi của máy Kỳ trả nợ (B11c; Q98–Q105): nhắc T−15, kiểm tiền T−2, ngày T gửi lệnh chi hoặc
 * chậm trả, ân hạn, nhận xác nhận chi, đối soát. Chạy lại an toàn: mỗi bước chỉ chạy khi kỳ đang ở trạng thái trước nó.
 * Chụp sổ và VSDC xác nhận (cạnh 3, 4) là B11b; chưa có xác nhận thì chờ.
 */
public class DebtServiceRunner {

    private static final Logger log = LoggerFactory.getLogger(DebtServiceRunner.class);
    static final int REMIND_DAYS = 15;
    static final int FUNDS_CHECK_DAYS = 2;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DebtServices debts;
    private final SeriesCatalog series;
    private final PayoutService payouts;
    private final PayingAgent agent;
    private final Notifications notifications;
    private final BusinessCalendar calendar;
    private final HoldingsSummaries holdings;
    private final Clock clock;

    public DebtServiceRunner(JdbcTemplate jdbc, TransactionTemplate tx, DebtServices debts, SeriesCatalog series,
            PayoutService payouts, PayingAgent agent, Notifications notifications, BusinessCalendar calendar,
            HoldingsSummaries holdings, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.debts = debts;
        this.series = series;
        this.payouts = payouts;
        this.agent = agent;
        this.notifications = notifications;
        this.calendar = calendar;
        this.holdings = holdings;
        this.clock = clock;
    }

    /** Một lượt cho ngày {@code today} (giờ VN). Lỗi ở một kỳ không chặn kỳ khác. */
    public void run(LocalDate today) {
        for (Due d : debts.open(today, REMIND_DAYS)) {
            try {
                tx.executeWithoutResult(s -> process(d.eventId(), today));
            } catch (RuntimeException e) {
                log.warn("Kỳ trả nợ {} kỳ {} của series {} lỗi: {}", d.eventType(), d.periodNo(), d.seriesId(), e.toString());
            }
        }
    }

    void process(UUID eventId, LocalDate today) {
        Due d = refresh(eventId);
        LocalDate t = d.paymentDate();
        // Cạnh 1: T−15 báo số dự kiến cho UBND, Kho bạc
        if ((d.status() == null || "CALCULATED".equals(d.status())) && !today.isBefore(t.minusDays(REMIND_DAYS))) {
            long estimate = estimate(d);
            debts.reminded(eventId, estimate);
            notify(Notifications.Kind.DEBT_SERVICE_REMINDER, Notifications.Severity.INFO, d,
                    title(d) + ": dự kiến " + estimate + " " + d.currency() + " ngày " + t,
                    List.of(Role.ISSUER, Role.TREASURY, Role.OPERATOR));
            d = refresh(eventId);
        }
        // Cạnh 2: T−2 đọc số dư IPA
        if ("REMINDED".equals(d.status()) && !today.isBefore(t.minusDays(FUNDS_CHECK_DAYS))) {
            long balance = agent.ipaBalance(d.currency());
            long shortfall = Math.max(0, d.dueMinor() - balance);
            debts.fundsChecked(eventId, balance, shortfall);
            if (shortfall > 0) {
                notify(Notifications.Kind.FUNDING_SHORT, Notifications.Severity.WARNING, d,
                        title(d) + ": tài khoản IPA thiếu " + shortfall + " " + d.currency(),
                        List.of(Role.ISSUER, Role.OPERATOR, Role.SUPERVISOR, Role.TRUSTEE));
            }
            d = refresh(eventId);
        }
        if (!("FUNDS_CHECKED".equals(d.status()) || "LATE".equals(d.status())) || today.isBefore(t)) {
            return;
        }
        // Quá ân hạn: dừng, chuyển series sang Vi phạm là Đ1
        if ("LATE".equals(d.status()) && today.isAfter(d.graceUntil())) {
            debts.graceExpired(eventId);
            notify(Notifications.Kind.PAYMENT_GRACE_EXPIRED, Notifications.Severity.CRITICAL, d,
                    title(d) + ": quá ân hạn " + d.graceUntil() + " chưa đủ tiền",
                    List.of(Role.ISSUER, Role.OPERATOR, Role.SUPERVISOR, Role.TRUSTEE));
            return;
        }
        if (!registrarAttested(d)) {
            // Chụp sổ và VSDC xác nhận ở B11b; chưa có thì chưa tạo được lệnh chi (US-3.09-AC2)
            return;
        }
        d = ensureCalculated(d);
        long balance = agent.ipaBalance(d.currency());
        if (balance >= d.dueMinor()) {
            instruct(d); // cạnh 5, 9
        } else if ("FUNDS_CHECKED".equals(d.status())) {
            LocalDate graceUntil = addBusinessDays(t, d.graceBusinessDays(), d.calendars());
            debts.late(eventId, graceUntil); // cạnh 8, US-3.09-AC4
            notify(Notifications.Kind.FUNDING_SHORT, Notifications.Severity.WARNING, d,
                    title(d) + ": chậm trả, thiếu " + (d.dueMinor() - balance) + " " + d.currency() + "; ân hạn tới "
                            + graceUntil, List.of(Role.ISSUER, Role.OPERATOR, Role.SUPERVISOR, Role.TRUSTEE));
        }
    }

    /** Q102: một lệnh chi cho kỳ, mã lệnh = id sự kiện lịch; dòng FROZEN giữ lại tại IPA. */
    private void instruct(Due d) {
        String ref = d.eventId().toString();
        List<PayingAgent.Line> lines = jdbc.query("""
                SELECT l.id, l.investor_org_id, l.wallet_id, l.net_minor
                FROM distribution.distribution_line l
                JOIN distribution.record_snapshot s ON s.id = l.record_snapshot_id
                WHERE s.series_id = ? AND s.period_no = ? AND l.line_kind = ? AND l.withheld_reason IS NULL
                ORDER BY l.wallet_id
                """, (rs, i) -> new PayingAgent.Line(rs.getObject(1, UUID.class).toString(), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getLong(4)), d.seriesId(), d.periodNo(), lineKind(d));
        agent.submitPayout(new PayingAgent.Instruction(ref, d.currency(), lines));
        if (lines.isEmpty()) {
            // Mọi vị thế đang phong tỏa: không có gì để chi, cả kỳ giữ lại tại IPA
            debts.instructed(d.eventId(), ref);
            confirmed(ref, List.of());
            return;
        }
        for (PayingAgent.Line l : lines) {
            jdbc.update("""
                    UPDATE distribution.distribution_line SET payment_instruction_ref = ?, payout_status = 'INSTRUCTED'
                    WHERE id = ? AND payment_instruction_ref IS NULL
                    """, ref, UUID.fromString(l.lineRef()));
        }
        debts.instructed(d.eventId(), ref);
    }

    /**
     * Q104, Q105: xác nhận chi từ BIDV. Ghi từng dòng; dòng FAILED báo Holco; mọi dòng PAID thì kỳ PAID và đối soát: tổng
     * đã chi bằng tổng net các dòng đã gửi thì RECONCILED.
     */
    public void confirmed(String instructionRef, List<Map<String, Object>> lines) {
        UUID eventId = UUID.fromString(instructionRef);
        Due d = refresh(eventId);
        for (Map<String, Object> l : lines) {
            String status = String.valueOf(l.get("status"));
            jdbc.update("""
                    UPDATE distribution.distribution_line SET payout_status = ?, bank_ref = ?, paid_minor = ?, settled_at = ?
                    WHERE id = ? AND payment_instruction_ref = ?
                    """, status, l.get("bankRef"), "PAID".equals(status) ? Long.parseLong(String.valueOf(l.get("amountMinor")))
                    : null, Timestamp.from(clock.instant()), UUID.fromString(String.valueOf(l.get("lineRef"))),
                    instructionRef);
            if ("FAILED".equals(status)) {
                notify(Notifications.Kind.PAYOUT_FAILED, Notifications.Severity.WARNING, d,
                        title(d) + ": BIDV báo chi lỗi dòng " + l.get("lineRef"), List.of(Role.OPERATOR));
            }
        }
        Map<String, Object> sums = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE payout_status IS DISTINCT FROM 'PAID') AS open_lines,
                       COALESCE(sum(net_minor), 0) AS expected, COALESCE(sum(paid_minor), 0) AS paid
                FROM distribution.distribution_line WHERE payment_instruction_ref = ?
                """, instructionRef);
        if (((Number) sums.get("open_lines")).longValue() > 0) {
            return;
        }
        debts.paid(eventId);
        long expected = ((Number) sums.get("expected")).longValue();
        long paid = ((Number) sums.get("paid")).longValue();
        if (paid == expected) {
            Long withheld = jdbc.queryForObject("""
                    SELECT COALESCE(sum(l.net_minor), 0) FROM distribution.distribution_line l
                    JOIN distribution.record_snapshot s ON s.id = l.record_snapshot_id
                    WHERE s.series_id = ? AND s.period_no = ? AND l.line_kind = ? AND l.withheld_reason IS NOT NULL
                    """, Long.class, d.seriesId(), d.periodNo(), lineKind(d));
            debts.reconciled(eventId, paid, withheld == null ? 0 : withheld);
        }
    }

    private Due ensureCalculated(Due d) {
        if ("MATURITY".equals(d.eventType())) {
            if (d.amountDueMinor() == null) {
                SeriesCatalog.SeriesInfo info = series.find(d.seriesId()).orElseThrow();
                long total = payouts.calculatePrincipal(d.seriesId(), d.periodNo(), info.faceValueMinor(), d.currency());
                debts.recordAmountDue(d.eventId(), total);
            }
        } else if (d.calculatedAt() == null) {
            payouts.calculate(d.seriesId(), d.periodNo());
        }
        return refresh(d.eventId());
    }

    /** Số dự kiến khi chưa có danh sách chốt: theo danh sách chốt nếu đã có, không thì theo bảng đọc số dư. */
    private long estimate(Due d) {
        List<Long> snap = jdbc.queryForList("""
                SELECT total_quantity FROM distribution.record_snapshot WHERE series_id = ? AND period_no = ?
                """, Long.class, d.seriesId(), d.periodNo());
        long quantity = snap.isEmpty() ? holdings.summary(d.seriesId()).totalBalance() : snap.getFirst();
        if (quantity == 0) {
            return 0;
        }
        if ("MATURITY".equals(d.eventType())) {
            return Math.multiplyExact(quantity, series.find(d.seriesId()).orElseThrow().faceValueMinor());
        }
        SeriesCatalog.Period p = series.period(d.seriesId(), d.periodNo()).orElse(null);
        if (p == null || p.ratePpm() == null) {
            return 0;
        }
        DayCount.Fraction fraction = DayCount.fraction(p.dayCount(), p.periodStart(), p.periodEnd(),
                PayoutService.periodsPerYear(p.couponFrequency()));
        return CouponCalculator.calculate(List.of(new CouponCalculator.Holding(new UUID(0, 0), new UUID(0, 0), quantity, 0)),
                new CouponCalculator.Terms(p.faceValueMinor(), p.ratePpm(), p.withholdingTaxPpm(), fraction))
                .amountDueMinor();
    }

    private boolean registrarAttested(Due d) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM distribution.record_snapshot
                               WHERE series_id = ? AND period_no = ? AND registrar_attested_at IS NOT NULL)
                """, Boolean.class, d.seriesId(), d.periodNo()));
    }

    /** Ngày làm việc thứ {@code n} sau {@code day} theo lịch của series; n = 0 là chính ngày đó. */
    LocalDate addBusinessDays(LocalDate day, int n, List<String> calendars) {
        LocalDate d = day;
        int left = n;
        while (left > 0) {
            d = d.plusDays(1);
            if (calendar.isBusinessDay(d, calendars)) {
                left--;
            }
        }
        return d;
    }

    private Due refresh(UUID eventId) {
        return debts.get(eventId).orElseThrow();
    }

    private void notify(Notifications.Kind kind, Notifications.Severity severity, Due d, String title, List<Role> roles) {
        notifications.notify(new Notifications.Notice(kind, severity, title, null,
                "series/" + d.seriesId() + "/" + d.eventType().toLowerCase() + "/" + d.periodNo(), roles, null));
    }

    private static String title(Due d) {
        return ("MATURITY".equals(d.eventType()) ? "Kỳ gốc" : "Kỳ coupon " + d.periodNo()) + " series " + d.seriesId();
    }

    private static String lineKind(Due d) {
        return "MATURITY".equals(d.eventType()) ? "PRINCIPAL" : "COUPON";
    }
}
