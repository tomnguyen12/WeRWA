package vn.werwa.issuance;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.werwa.governance.api.ApprovalHook;
import vn.werwa.governance.api.Approvals;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.api.BusinessCalendar;
import vn.werwa.issuance.decision.IssuerDecisionService;
import vn.werwa.issuance.document.DocumentService;
import vn.werwa.issuance.document.DocumentStore;
import vn.werwa.issuance.document.StorageProperties;
import vn.werwa.issuance.program.ProgramService;
import vn.werwa.issuance.series.ScheduleGenerator;
import vn.werwa.issuance.series.SeriesService;
import vn.werwa.onboarding.api.InvestorClasses;
import vn.werwa.platform.events.Events;

/** Bean của mô-đun Phát hành. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({StorageProperties.class, IssuanceProperties.class})
class IssuanceConfig {

    @Bean
    DocumentStore documentStore(StorageProperties props) {
        DocumentStore store = new DocumentStore(props);
        store.verifyBucket();
        return store;
    }

    @Bean
    DocumentService documents(JdbcTemplate jdbc, DocumentStore store, AuditLog audit, StorageProperties props, Clock clock) {
        return new DocumentService(jdbc, store, audit, props, clock);
    }

    @Bean
    ProgramService programs(JdbcTemplate jdbc, Approvals approvals, AuditLog audit, DocumentService documents,
            InvestorClasses investorClasses, Clock clock) {
        return new ProgramService(jdbc, approvals, audit, documents, investorClasses, clock);
    }

    @Bean
    SeriesService series(JdbcTemplate jdbc, ProgramService programs, DocumentService documents, Approvals approvals,
            AuditLog audit, Events events, BusinessCalendar calendar, IssuanceProperties props,
            vn.werwa.governance.api.Parameters parameters, Clock clock) {
        // Q60: tham số đọc mỗi lần dùng; chưa có trong bảng thì lấy cấu hình
        return new SeriesService(jdbc, programs, documents, approvals, audit, events,
                new ScheduleGenerator(calendar, () -> ScheduleGenerator.Convention.valueOf(parameters.enumValue(
                        "issuance.business-day-convention", props.businessDayConvention().name()))),
                () -> parameters.stringList("issuance.required-document-types", props.requiredDocumentTypes()), clock);
    }

    /** R1 (Q73): hạn mức đã giữ chỗ và đã dùng không vượt khối lượng Đề án; series đã qua duyệt phải có giữ chỗ. */
    @Bean
    vn.werwa.platform.reconciliation.ReconciliationCheck programLimitCheck(JdbcTemplate jdbc) {
        return new vn.werwa.platform.reconciliation.ReconciliationCheck() {
            @Override
            public String rule() {
                return "R1";
            }

            @Override
            public String scope() {
                return "LIMITS";
            }

            @Override
            public java.util.List<Finding> run(java.time.LocalDate runDate) {
                java.util.List<Finding> out = new java.util.ArrayList<>(jdbc.query("""
                        SELECT p.id, p.max_principal_minor,
                               COALESCE(SUM(CASE e.entry_type WHEN 'RESERVE' THEN e.principal_minor
                                                              WHEN 'RELEASE' THEN -e.principal_minor ELSE 0 END), 0)
                        FROM issuance.issuance_program p
                        LEFT JOIN issuance.issuance_program c ON c.root_program_id = p.root_program_id
                        LEFT JOIN issuance.limit_ledger_entry e ON e.program_id = c.id
                        WHERE p.status = 'EFFECTIVE'
                        GROUP BY p.id, p.max_principal_minor
                        HAVING COALESCE(SUM(CASE e.entry_type WHEN 'RESERVE' THEN e.principal_minor
                                                              WHEN 'RELEASE' THEN -e.principal_minor ELSE 0 END), 0)
                               > p.max_principal_minor
                        """, (rs, i) -> new Finding("PROGRAM_LIMIT_EXCEEDED", "program/" + rs.getObject(1),
                        "≤ " + rs.getLong(2), Long.toString(rs.getLong(3)))));
                out.addAll(jdbc.query("""
                        SELECT s.id, s.status FROM issuance.series s
                        WHERE s.status NOT IN ('DRAFT', 'PENDING_REVIEW', 'CANCELLED')
                          AND NOT EXISTS (SELECT 1 FROM issuance.limit_ledger_entry e
                                          WHERE e.series_id = s.id AND e.entry_type = 'RESERVE')
                        """, (rs, i) -> new Finding("RESERVE_MISSING", "series/" + rs.getObject(1),
                        "có bút toán RESERVE", "không có (series " + rs.getString(2) + ")")));
                return out;
            }
        };
    }

    /** Q61: lịch ngày làm việc đổi thì tính lại lịch các kỳ chưa tới ngày chốt. */
    @Bean
    vn.werwa.platform.events.EventHandler issuanceOnCalendarChanged(SeriesService series) {
        return new vn.werwa.platform.events.EventHandler() {
            @Override
            public String consumer() {
                return "issuance";
            }

            @Override
            public String eventType() {
                return "werwa.governance.calendar.changed.v1";
            }

            @Override
            public void handle(vn.werwa.platform.events.InboundEvent event) {
                series.recalculateSchedules((String) event.data().get("calendarCode"));
            }
        };
    }

    @Bean
    IssuerDecisionService issuerDecisions(JdbcTemplate jdbc, DocumentService documents, SeriesService series,
            AuditLog audit, Clock clock) {
        return new IssuerDecisionService(jdbc, documents, series, audit, clock);
    }

    /** US-2.01-AC2 (Q28). */
    @Bean
    ApprovalHook programApprovalHook(ProgramService programs) {
        return new ApprovalHook() {
            @Override
            public String actionType() {
                return ProgramService.ACTIVATE_ACTION;
            }

            @Override
            public void onDecision(Decision decision) {
                programs.applyDecision(decision.approvalId(), decision.approve());
            }
        };
    }

    /** Máy Series cạnh 2, 3; US-2.02-AC2 (Q28). */
    @Bean
    ApprovalHook seriesApprovalHook(SeriesService series) {
        return new ApprovalHook() {
            @Override
            public String actionType() {
                return SeriesService.SUBMIT_ACTION;
            }

            @Override
            public void onDecision(Decision decision) {
                series.applyDecision(decision.approvalId(), decision.approve(), decision.comment());
            }
        };
    }
}
