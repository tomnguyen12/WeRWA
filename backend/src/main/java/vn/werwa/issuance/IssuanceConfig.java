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
            AuditLog audit, Events events, BusinessCalendar calendar, IssuanceProperties props, Clock clock) {
        return new SeriesService(jdbc, programs, documents, approvals, audit, events,
                new ScheduleGenerator(calendar, props.businessDayConvention()), props.requiredDocumentTypes(), clock);
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
