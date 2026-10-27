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
class GovernanceConfig {

    static final String TX_MINED = "werwa.onchain.tx.mined.v1";
    static final String TX_FAILED = "werwa.onchain.tx.failed.v1";

    @Bean
    vn.werwa.governance.calendar.CalendarService businessCalendar(JdbcTemplate jdbc) {
        return new vn.werwa.governance.calendar.CalendarService(jdbc);
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
            org.springframework.beans.factory.ObjectProvider<vn.werwa.governance.api.ApprovalHook> hooks) {
        return new ApprovalService(jdbc, audit, events, clock, hooks);
    }

    @Bean
    AuditAnchorService auditAnchors(
            JdbcTemplate jdbc, TransactionTemplate tx, ChainTransactions chain, ContractCalls calls, Clock clock) {
        return new AuditAnchorService(jdbc, tx, chain, calls, clock, 7);
    }

    /** Kết quả giao dịch neo nhật ký, nhận qua NATS như mọi sự kiện giữa mô-đun (D4). */
    @Bean
    EventHandler governanceOnTxMined(AuditAnchorService anchors) {
        return handler(TX_MINED, e -> {
            if (purposeIsAnchor(e)) {
                anchors.anchored(UUID.fromString(String.valueOf(e.data().get("txId"))),
                        String.valueOf(e.data().get("txHash")), ((Number) e.data().get("block")).longValue());
            }
        });
    }

    @Bean
    EventHandler governanceOnTxFailed(AuditAnchorService anchors) {
        return handler(TX_FAILED, e -> {
            if (purposeIsAnchor(e)) {
                anchors.failed(UUID.fromString(String.valueOf(e.data().get("txId"))), String.valueOf(e.data().get("reason")));
            }
        });
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
