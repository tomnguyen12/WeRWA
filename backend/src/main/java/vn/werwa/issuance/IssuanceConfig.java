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

    /** B11c: trạng thái trả nợ theo kỳ (Q99, Q101, Q105). */
    @Bean
    vn.werwa.issuance.series.DebtServiceLedger debtServices(JdbcTemplate jdbc, AuditLog audit,
            vn.werwa.platform.events.Events events, Clock clock) {
        return new vn.werwa.issuance.series.DebtServiceLedger(jdbc, audit, events, clock);
    }

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
    /** B6c: triển khai 3/3, ISIN, đồng bộ trạng thái từ chuỗi (Q114–Q119). */
    @Bean
    vn.werwa.issuance.series.SeriesDeployment seriesDeployment(JdbcTemplate jdbc, SeriesService series,
            ProgramService programs, vn.werwa.governance.api.SigningProposals proposals, InvestorClasses investorClasses,
            vn.werwa.onboarding.api.Wallets wallets, vn.werwa.onchain.api.Accounts accounts,
            vn.werwa.platform.config.WerwaProperties werwa, Events events, AuditLog audit, Clock clock) {
        return new vn.werwa.issuance.series.SeriesDeployment(jdbc, series, programs, proposals, investorClasses, wallets,
                accounts, werwa, events, audit, clock);
    }

    /**
     * Q115: duyệt series đạt thì tạo đề nghị ký triển khai, trong giao dịch riêng. Không tạo được (chuỗi hay cấu hình Safe
     * chưa sẵn) thì ghi nhật ký; Holco tạo lại bằng {@code POST /v1/series/{id}/deploy}.
     */
    @Bean
    vn.werwa.platform.events.EventHandler issuanceOnSeriesApproved(vn.werwa.issuance.series.SeriesDeployment deployment,
            org.springframework.transaction.PlatformTransactionManager txManager, AuditLog audit) {
        org.springframework.transaction.support.TransactionTemplate separate =
                new org.springframework.transaction.support.TransactionTemplate(txManager);
        separate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new vn.werwa.platform.events.EventHandler() {
            @Override
            public String consumer() {
                return "issuance";
            }

            @Override
            public String eventType() {
                return "werwa.issuance.series.approved.v1";
            }

            @Override
            public void handle(vn.werwa.platform.events.InboundEvent event) {
                java.util.UUID seriesId = java.util.UUID.fromString(String.valueOf(event.data().get("seriesId")));
                try {
                    separate.executeWithoutResult(s -> deployment.proposeDeploy(seriesId));
                } catch (RuntimeException e) {
                    audit.record("SERIES_DEPLOY_PROPOSAL_FAILED", "series/" + seriesId,
                            java.util.Map.of("reason", String.valueOf(e.getMessage())));
                }
            }
        };
    }

    /** B14: tạm dừng và mở lại series (Q171–Q175). */
    @Bean
    vn.werwa.issuance.series.SeriesPause seriesPause(JdbcTemplate jdbc, vn.werwa.onchain.api.ContractCalls calls,
            vn.werwa.governance.api.SigningProposals proposals, Approvals approvals,
            vn.werwa.governance.api.Notifications notifications, vn.werwa.platform.events.Events events, AuditLog audit,
            java.time.Clock clock) {
        return new vn.werwa.issuance.series.SeriesPause(jdbc, calls, proposals, approvals, notifications, events, audit,
                clock);
    }

    @Bean
    ApprovalHook seriesUnpauseApprovalHook(vn.werwa.issuance.series.SeriesPause pauses) {
        return new ApprovalHook() {
            @Override
            public String actionType() {
                return vn.werwa.issuance.series.SeriesPause.UNPAUSE_ACTION;
            }

            @Override
            public void onDecision(Decision decision) {
                pauses.applyDecision(decision.approvalId(), decision.approve());
            }
        };
    }

    /** Q173: duyệt mở lại đạt thì tạo đề nghị Safe 3/3 trong giao dịch riêng. */
    @Bean
    vn.werwa.platform.events.EventHandler issuanceOnApprovalDecided(vn.werwa.issuance.series.SeriesPause pauses,
            org.springframework.transaction.PlatformTransactionManager txManager, AuditLog audit) {
        org.springframework.transaction.support.TransactionTemplate separate =
                new org.springframework.transaction.support.TransactionTemplate(txManager);
        separate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new vn.werwa.platform.events.EventHandler() {
            @Override
            public String consumer() {
                return "issuance";
            }

            @Override
            public String eventType() {
                return "werwa.governance.approval.decided.v1";
            }

            @Override
            public void handle(vn.werwa.platform.events.InboundEvent event) {
                if (!vn.werwa.issuance.series.SeriesPause.UNPAUSE_ACTION.equals(event.data().get("actionType"))
                        || !"APPROVE".equals(event.data().get("decision"))) {
                    return;
                }
                pauses.unpauseOfApproval(java.util.UUID.fromString(String.valueOf(event.data().get("approvalId"))))
                        .ifPresent(request -> {
                            try {
                                separate.executeWithoutResult(s -> pauses.proposeUnpause(request));
                            } catch (RuntimeException e) {
                                audit.record("SERIES_UNPAUSE_PROPOSAL_FAILED", "series-pause-request/" + request,
                                        java.util.Map.of("reason", String.valueOf(e.getMessage())));
                            }
                        });
            }
        };
    }

    /** B13b: đáo hạn (Q164, Q165). */
    @Bean
    vn.werwa.issuance.series.SeriesMaturity seriesMaturity(JdbcTemplate jdbc, vn.werwa.onchain.api.ContractCalls calls,
            vn.werwa.onchain.api.ChainTransactions chain, vn.werwa.governance.api.OperationalAlerts alerts, AuditLog audit,
            java.time.Clock clock) {
        return new vn.werwa.issuance.series.SeriesMaturity(jdbc, calls, chain, alerts, audit, clock);
    }

    /** Q165: kỳ gốc đã đối soát thì gửi mature trong giao dịch riêng. */
    @Bean
    vn.werwa.platform.events.EventHandler issuanceOnDebtServiceReconciled(
            vn.werwa.issuance.series.SeriesMaturity maturity,
            org.springframework.transaction.PlatformTransactionManager txManager, AuditLog audit) {
        org.springframework.transaction.support.TransactionTemplate separate =
                new org.springframework.transaction.support.TransactionTemplate(txManager);
        separate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new vn.werwa.platform.events.EventHandler() {
            @Override
            public String consumer() {
                return "issuance";
            }

            @Override
            public String eventType() {
                return "werwa.issuance.debt_service.reconciled.v1";
            }

            @Override
            public void handle(vn.werwa.platform.events.InboundEvent event) {
                if (!"MATURITY".equals(event.data().get("eventType"))) {
                    return;
                }
                java.util.UUID seriesId = java.util.UUID.fromString(String.valueOf(event.data().get("seriesId")));
                try {
                    separate.executeWithoutResult(s -> maturity.submit(seriesId, false));
                } catch (RuntimeException e) {
                    audit.record("SERIES_MATURE_SUBMIT_FAILED", "series/" + seriesId,
                            java.util.Map.of("reason", String.valueOf(e.getMessage())));
                }
            }
        };
    }

    /** Q165: giao dịch mature revert. */
    @Bean
    vn.werwa.platform.events.EventHandler issuanceOnTxFailed(vn.werwa.issuance.series.SeriesMaturity maturity) {
        return new vn.werwa.platform.events.EventHandler() {
            @Override
            public String consumer() {
                return "issuance";
            }

            @Override
            public String eventType() {
                return "werwa.onchain.tx.failed.v1";
            }

            @Override
            public void handle(vn.werwa.platform.events.InboundEvent event) {
                maturity.onTxFailed(java.util.UUID.fromString(String.valueOf(event.data().get("txId"))),
                        String.valueOf(event.data().get("reason")));
            }
        };
    }

    /** Q117: địa chỉ hợp đồng, ISIN, trạng thái series từ sự kiện indexer. */
    @Bean
    vn.werwa.platform.events.EventHandler issuanceOnEventIndexed(vn.werwa.issuance.series.SeriesDeployment deployment,
            vn.werwa.issuance.series.SeriesPause pauses, SeriesService seriesService) {
        return new vn.werwa.platform.events.EventHandler() {
            @Override
            public String consumer() {
                return "issuance";
            }

            @Override
            public String eventType() {
                return "werwa.onchain.event.indexed.v1";
            }

            @Override
            public void handle(vn.werwa.platform.events.InboundEvent event) {
                Object kind = event.data().get("contractKind");
                if ("seriesfactory".equals(kind) || "series.controller".equals(kind)) {
                    deployment.onIndexed(event.data());
                }
                Object name = event.data().get("eventName");
                if ("series.controller".equals(kind) && ("Paused".equals(name) || "Unpaused".equals(name))) {
                    // B14 (Q174)
                    seriesService.findByController(String.valueOf(event.data().get("contract"))).ifPresent(id ->
                            pauses.onIndexed(id, String.valueOf(name), String.valueOf(event.data().get("txHash"))));
                }
            }
        };
    }

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
