package vn.werwa.distribution;

import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import vn.werwa.distribution.allocation.AllocationListService;
import vn.werwa.distribution.offering.OfferingService;
import vn.werwa.distribution.order.OrderService;
import vn.werwa.governance.api.ApprovalHook;
import vn.werwa.governance.api.Approvals;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.issuance.api.IssuerDecisions;
import vn.werwa.issuance.api.SeriesCatalog;
import vn.werwa.onboarding.api.Investors;
import vn.werwa.onboarding.api.Wallets;
import vn.werwa.platform.events.Events;

/** Bean của mô-đun Phân phối (B7a). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DistributionProperties.class)
class DistributionConfig {

    @Bean
    OfferingService offerings(JdbcTemplate jdbc, SeriesCatalog series, AuditLog audit, Events events, Clock clock) {
        return new OfferingService(jdbc, series, audit, events, clock);
    }

    @Bean
    OrderService orders(JdbcTemplate jdbc, OfferingService offerings, SeriesCatalog series, Investors investors,
            Wallets wallets, AuditLog audit, DistributionProperties props, Clock clock) {
        return new OrderService(jdbc, offerings, series, investors, wallets, audit, props, clock);
    }

    @Bean
    AllocationListService allocationLists(JdbcTemplate jdbc, OfferingService offerings, OrderService orders,
            SeriesCatalog series, IssuerDecisions decisions, Wallets wallets, Approvals approvals, AuditLog audit,
            Clock clock) {
        return new AllocationListService(jdbc, offerings, orders, series, decisions, wallets, approvals, audit, clock);
    }

    /** Q40: UBND duyệt danh sách phân bổ. */
    @Bean
    ApprovalHook allocationListApprovalHook(AllocationListService lists) {
        return new ApprovalHook() {
            @Override
            public String actionType() {
                return AllocationListService.SUBMIT_ACTION;
            }

            @Override
            public void onDecision(Decision decision) {
                lists.applyDecision(decision.approvalId(), decision.approve(), decision.comment());
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "werwa.distribution.book-scheduler-enabled", havingValue = "true", matchIfMissing = true)
    BookScheduler bookScheduler(OfferingService offerings, Clock clock) {
        return new BookScheduler(offerings, clock);
    }

    /** Q46: mở, đóng sổ theo giờ; mỗi đợt một giao dịch, lỗi một đợt không chặn đợt khác. */
    static class BookScheduler {

        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BookScheduler.class);

        private final OfferingService offerings;
        private final Clock clock;

        BookScheduler(OfferingService offerings, Clock clock) {
            this.offerings = offerings;
            this.clock = clock;
        }

        @Scheduled(fixedDelayString = "${werwa.distribution.book-schedule-interval:PT1M}")
        void tick() {
            for (UUID id : offerings.dueToOpen(clock.instant())) {
                run("mở", id, () -> offerings.open(id));
            }
            for (UUID id : offerings.dueToClose(clock.instant())) {
                run("đóng", id, () -> offerings.close(id));
            }
        }

        private static void run(String what, UUID id, Runnable action) {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.warn("Không {} được sổ của đợt {}: {}", what, id, e.toString());
            }
        }
    }
}
