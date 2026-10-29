package vn.werwa.governance;

import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import vn.werwa.governance.approval.ApprovalService;
import vn.werwa.governance.audit.AuditAnchorService;
import vn.werwa.governance.audit.AuditLogService;
import vn.werwa.governance.audit.AuditVerifier;
import vn.werwa.onchain.api.ChainTransactions;
import vn.werwa.onchain.api.ContractCalls;
import vn.werwa.platform.events.EventHandler;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.events.InboundEvent;

/** Bean của mô-đun Quản trị (B4a). */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.context.properties.EnableConfigurationProperties(
        vn.werwa.governance.signing.SigningProperties.class)
class GovernanceConfig {

    static final String TX_MINED = "werwa.onchain.tx.mined.v1";
    static final String TX_FAILED = "werwa.onchain.tx.failed.v1";

    @Bean
    vn.werwa.governance.calendar.CalendarService businessCalendar(JdbcTemplate jdbc) {
        return new vn.werwa.governance.calendar.CalendarService(jdbc);
    }

    @Bean
    vn.werwa.governance.alert.AlertService alerts(JdbcTemplate jdbc, AuditLogService audit,
            tools.jackson.databind.json.JsonMapper json, Clock clock,
            vn.werwa.governance.notification.NotificationService notifications) {
        return new vn.werwa.governance.alert.AlertService(jdbc, audit, json, clock, notifications);
    }

    @Bean
    vn.werwa.governance.notification.NotificationService notifications(JdbcTemplate jdbc,
            org.springframework.beans.factory.ObjectProvider<vn.werwa.platform.security.OrganizationDirectory> organizations,
            org.springframework.beans.factory.ObjectProvider<vn.werwa.platform.mail.Mailer> mailer, Clock clock) {
        return new vn.werwa.governance.notification.NotificationService(jdbc, organizations, mailer, clock);
    }

    @Bean
    vn.werwa.governance.parameter.ParameterService parameters(JdbcTemplate jdbc, ApprovalService approvals,
            AuditLogService audit, tools.jackson.databind.json.JsonMapper json, Clock clock) {
        return new vn.werwa.governance.parameter.ParameterService(jdbc, approvals, audit, json, clock);
    }

    @Bean
    vn.werwa.governance.calendar.CalendarAdjustmentService calendarAdjustments(JdbcTemplate jdbc,
            vn.werwa.governance.calendar.CalendarService calendar, ApprovalService approvals, AuditLogService audit,
            Events events, Clock clock) {
        return new vn.werwa.governance.calendar.CalendarAdjustmentService(jdbc, calendar, approvals, audit, events, clock);
    }

    /** B10a: lệnh phong tỏa (Q64–Q67). Ví, series, tài liệu qua SPI của platform để không phụ thuộc ngược. */
    @Bean
    vn.werwa.governance.enforcement.EnforcementService enforcement(JdbcTemplate jdbc,
            vn.werwa.platform.registry.WalletRegistry wallets, vn.werwa.platform.registry.SeriesRegistry series,
            vn.werwa.platform.documents.DocumentRegistry documents, ApprovalService approvals, AuditLogService audit,
            Clock clock) {
        return new vn.werwa.governance.enforcement.EnforcementService(jdbc, wallets, series, documents, approvals, audit,
                clock);
    }

    @Bean
    vn.werwa.governance.api.ApprovalHook enforcementApprovalHook(
            vn.werwa.governance.enforcement.EnforcementService enforcement) {
        return hook(vn.werwa.governance.enforcement.EnforcementService.APPROVE_ACTION,
                d -> enforcement.applyDecision(d.approvalId(), d.approve(), d.comment()));
    }

    /** B4c: đối soát hằng ngày (Q73–Q76). */
    @Bean
    vn.werwa.governance.reconciliation.ReconciliationService reconciliation(JdbcTemplate jdbc,
            org.springframework.beans.factory.ObjectProvider<vn.werwa.platform.reconciliation.ReconciliationCheck> checks,
            AuditLogService audit, vn.werwa.governance.notification.NotificationService notifications, Clock clock) {
        return new vn.werwa.governance.reconciliation.ReconciliationService(jdbc, checks, audit, notifications, clock);
    }

    /** R8: chuỗi nhật ký liền mạch và khớp lần neo đã lên chuỗi. */
    @Bean
    vn.werwa.platform.reconciliation.ReconciliationCheck auditChainCheck(AuditVerifier verifier) {
        return new vn.werwa.platform.reconciliation.ReconciliationCheck() {
            @Override
            public String rule() {
                return "R8";
            }

            @Override
            public String scope() {
                return "AUDIT_CHAIN";
            }

            @Override
            public java.util.List<Finding> run(java.time.LocalDate runDate) {
                vn.werwa.governance.api.AuditVerification.Result r = verifier.verify();
                return r.intact() ? java.util.List.of()
                        : java.util.List.of(new Finding("AUDIT_CHAIN_BROKEN", "audit-log/" + r.brokenAtSeq(),
                                "liền mạch, khớp neo", String.valueOf(r.reason())));
            }
        };
    }

    /** B12b: công cụ giám sát (Q90–Q96). */
    @Bean
    vn.werwa.governance.supervision.SupervisionService supervision(JdbcTemplate jdbc,
            vn.werwa.onchain.api.IndexedEvents events, vn.werwa.governance.parameter.ParameterService parameters,
            vn.werwa.governance.alert.AlertService alerts,
            vn.werwa.governance.notification.NotificationService notifications, AuditLogService audit, Clock clock) {
        return new vn.werwa.governance.supervision.SupervisionService(jdbc, events, parameters, alerts, notifications,
                audit, clock);
    }

    /** Q97: thao tác ghi Cơ quan giám sát được làm, theo tham số hệ thống. */
    @Bean
    vn.werwa.governance.supervision.SupervisorWrites supervisorWrites(
            vn.werwa.governance.parameter.ParameterService parameters) {
        return new vn.werwa.governance.supervision.SupervisorWrites(parameters);
    }

    /** R7 (Q95, Q96). */
    @Bean
    vn.werwa.governance.supervision.BlockHashCheck blockHashCheck(
            vn.werwa.governance.supervision.SupervisionService supervision,
            vn.werwa.governance.calendar.CalendarService calendar) {
        return new vn.werwa.governance.supervision.BlockHashCheck(supervision, calendar);
    }

    /** Q93: theo dõi độ trễ indexer mỗi 10 giây. */
    @Bean
    @ConditionalOnProperty(name = "werwa.governance.supervision-monitor-enabled", havingValue = "true",
            matchIfMissing = true)
    SupervisionMonitor supervisionMonitor(vn.werwa.governance.supervision.SupervisionService supervision) {
        return new SupervisionMonitor(supervision);
    }

    static class SupervisionMonitor {
        private final vn.werwa.governance.supervision.SupervisionService supervision;

        SupervisionMonitor(vn.werwa.governance.supervision.SupervisionService supervision) {
            this.supervision = supervision;
        }

        @Scheduled(fixedDelayString = "${werwa.governance.supervision-monitor-interval:PT10S}", initialDelayString = "PT30S")
        void check() {
            try {
                supervision.monitor();
            } catch (RuntimeException e) {
                // O1 không trả lời: vẫn là sự cố cần biết
                org.slf4j.LoggerFactory.getLogger(SupervisionMonitor.class).warn("Không đo được độ trễ indexer: {}",
                        e.toString());
            }
        }
    }

    @Bean
    @ConditionalOnProperty(name = "werwa.governance.reconciliation-enabled", havingValue = "true", matchIfMissing = true)
    ReconciliationJob reconciliationJob(vn.werwa.governance.reconciliation.ReconciliationService reconciliation,
            Clock clock) {
        return new ReconciliationJob(reconciliation, clock);
    }

    /** Q76: 00:30 giờ VN, sau neo nhật ký 00:15; đối soát cho ngày hôm trước. */
    static class ReconciliationJob {
        private final vn.werwa.governance.reconciliation.ReconciliationService reconciliation;
        private final Clock clock;

        ReconciliationJob(vn.werwa.governance.reconciliation.ReconciliationService reconciliation, Clock clock) {
            this.reconciliation = reconciliation;
            this.clock = clock;
        }

        @Scheduled(cron = "0 30 0 * * *", zone = "Asia/Ho_Chi_Minh")
        void daily() {
            try {
                reconciliation.runAll(clock.instant().atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate()
                        .minusDays(1));
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(ReconciliationJob.class).warn("Đối soát hằng ngày lỗi: {}", e.toString());
            }
        }
    }

    /** Q60. */
    @Bean
    vn.werwa.governance.api.ApprovalHook parameterApprovalHook(vn.werwa.governance.parameter.ParameterService parameters) {
        return hook(vn.werwa.governance.parameter.ParameterService.CHANGE_ACTION,
                d -> parameters.applyDecision(d.approvalId(), d.approve()));
    }

    /** Q61. */
    @Bean
    vn.werwa.governance.api.ApprovalHook calendarApprovalHook(
            vn.werwa.governance.calendar.CalendarAdjustmentService adjustments) {
        return hook(vn.werwa.governance.calendar.CalendarAdjustmentService.ADJUST_ACTION,
                d -> adjustments.applyDecision(d.approvalId(), d.approve()));
    }

    private static vn.werwa.governance.api.ApprovalHook hook(String action,
            java.util.function.Consumer<vn.werwa.governance.api.ApprovalHook.Decision> apply) {
        return new vn.werwa.governance.api.ApprovalHook() {
            @Override
            public String actionType() {
                return action;
            }

            @Override
            public void onDecision(Decision decision) {
                apply.accept(decision);
            }
        };
    }

    /** Q62: gửi email thông báo định kỳ. */
    @Bean
    @ConditionalOnProperty(name = "werwa.governance.notification-mail-enabled", havingValue = "true", matchIfMissing = true)
    MailJob notificationMailJob(vn.werwa.governance.notification.NotificationService notifications) {
        return new MailJob(notifications);
    }

    static class MailJob {
        private final vn.werwa.governance.notification.NotificationService notifications;

        MailJob(vn.werwa.governance.notification.NotificationService notifications) {
            this.notifications = notifications;
        }

        @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${werwa.governance.notification-mail-interval:PT1M}")
        void tick() {
            try {
                notifications.sendPendingEmails();
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(MailJob.class).warn("Lượt gửi email lỗi: {}", e.toString());
            }
        }
    }

    /** Q56: kênh webhook Alertmanager chỉ mở khi có token. */
    @Bean
    @ConditionalOnProperty("werwa.governance.alert-token")
    vn.werwa.platform.inbound.InboundToken alertInboundToken(
            @org.springframework.beans.factory.annotation.Value("${werwa.governance.alert-token}") String token) {
        byte[] expected = token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return (operationId, bearer) -> "receiveAlertmanagerWebhook".equals(operationId)
                && java.security.MessageDigest.isEqual(expected, bearer.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Bean
    AuditLogService auditLog(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        return new AuditLogService(jdbc, tx, clock);
    }

    @Bean
    AuditVerifier auditVerifier(JdbcTemplate jdbc) {
        return new AuditVerifier(jdbc);
    }

    @Bean
    ApprovalService approvals(JdbcTemplate jdbc, AuditLogService audit, Events events, Clock clock,
            org.springframework.beans.factory.ObjectProvider<vn.werwa.governance.api.ApprovalHook> hooks,
            vn.werwa.governance.notification.NotificationService notifications) {
        return new ApprovalService(jdbc, audit, events, clock, hooks, notifications);
    }

    @Bean
    AuditAnchorService auditAnchors(
            JdbcTemplate jdbc, TransactionTemplate tx, ChainTransactions chain, ContractCalls calls, Clock clock) {
        return new AuditAnchorService(jdbc, tx, chain, calls, clock, 7);
    }

    /** Kết quả giao dịch neo nhật ký, nhận qua NATS như mọi sự kiện giữa mô-đun (D4). */
    @Bean
    EventHandler governanceOnTxMined(AuditAnchorService anchors,
            vn.werwa.governance.signing.SigningService signing) {
        return handler(TX_MINED, e -> {
            UUID txId = UUID.fromString(String.valueOf(e.data().get("txId")));
            if (purposeIsAnchor(e)) {
                anchors.anchored(txId, String.valueOf(e.data().get("txHash")), ((Number) e.data().get("block")).longValue());
            } else {
                // Q111: giao dịch Safe của đề nghị ký (mục đích là của đề nghị)
                signing.mined(txId, String.valueOf(e.data().get("txHash")));
            }
        });
    }

    @Bean
    EventHandler governanceOnTxFailed(AuditAnchorService anchors,
            vn.werwa.governance.signing.SigningService signing) {
        return handler(TX_FAILED, e -> {
            UUID txId = UUID.fromString(String.valueOf(e.data().get("txId")));
            if (purposeIsAnchor(e)) {
                anchors.failed(txId, String.valueOf(e.data().get("reason")));
            } else {
                signing.failed(txId, String.valueOf(e.data().get("reason")));
            }
        });
    }

    /** B4b: thu chữ ký multisig, gửi giao dịch Safe (Q106–Q111). */
    @Bean
    vn.werwa.governance.signing.SigningService signing(JdbcTemplate jdbc, vn.werwa.onchain.api.Safes safes,
            vn.werwa.onchain.api.ChainTransactions chain, vn.werwa.platform.registry.SignerRegistry signers,
            vn.werwa.platform.config.WerwaProperties werwa, vn.werwa.governance.signing.SigningProperties props,
            Events events, AuditLogService audit, tools.jackson.databind.json.JsonMapper json, Clock clock,
            vn.werwa.governance.notification.NotificationService notifications) {
        var service = new vn.werwa.governance.signing.SigningService(jdbc, safes, chain, signers, werwa, props, events,
                audit, json, clock);
        service.setNotifications(notifications);
        return service;
    }

    /** Neo hằng ngày (Q21): 00:15 giờ Việt Nam neo ngày hôm trước; mỗi 10 phút bù ngày còn thiếu hoặc lỗi. */
    @Bean
    @ConditionalOnProperty(name = "werwa.governance.anchor-enabled", havingValue = "true", matchIfMissing = true)
    AnchorSchedule anchorSchedule(AuditAnchorService anchors) {
        return new AnchorSchedule(anchors);
    }

    static class AnchorSchedule {

        private final AuditAnchorService anchors;

        AnchorSchedule(AuditAnchorService anchors) {
            this.anchors = anchors;
        }

        @Scheduled(cron = "0 15 0 * * *", zone = "Asia/Ho_Chi_Minh")
        void daily() {
            anchors.catchUp();
        }

        @Scheduled(fixedDelayString = "${werwa.governance.anchor-catch-up:PT10M}", initialDelayString = "PT1M")
        void catchUp() {
            anchors.catchUp();
        }
    }

    private static boolean purposeIsAnchor(InboundEvent e) {
        return "AUDIT_ANCHOR".equals(e.data().get("purpose"));
    }

    private static EventHandler handler(String type, java.util.function.Consumer<InboundEvent> body) {
        return new EventHandler() {
            @Override
            public String consumer() {
                return "governance";
            }

            @Override
            public String eventType() {
                return type;
            }

            @Override
            public void handle(InboundEvent event) {
                body.accept(event);
            }
        };
    }
}
