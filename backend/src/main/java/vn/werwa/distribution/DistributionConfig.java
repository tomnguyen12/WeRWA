package vn.werwa.distribution;

import java.time.Clock;
import java.util.Map;
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
            Wallets wallets, AuditLog audit, DistributionProperties props, vn.werwa.governance.api.Parameters parameters,
            Clock clock) {
        return new OrderService(jdbc, offerings, series, investors, wallets, audit, props, parameters, clock);
    }

    @Bean
    AllocationListService allocationLists(JdbcTemplate jdbc, OfferingService offerings, OrderService orders,
            SeriesCatalog series, IssuerDecisions decisions, Wallets wallets,
            vn.werwa.integration.api.VirtualAccounts virtualAccounts, Approvals approvals, AuditLog audit, Clock clock) {
        return new AllocationListService(jdbc, offerings, orders, series, decisions, wallets, virtualAccounts, approvals,
                audit, clock);
    }

    @Bean
    vn.werwa.distribution.payment.PaymentService payments(JdbcTemplate jdbc, SeriesCatalog series,
            vn.werwa.governance.api.BusinessCalendar calendar, AuditLog audit, Events events, DistributionProperties props,
            vn.werwa.governance.api.Parameters parameters, vn.werwa.governance.api.Notifications notifications,
            Clock clock) {
        return new vn.werwa.distribution.payment.PaymentService(jdbc, series, calendar, audit, events,
                props.paymentGraceBusinessDays(), parameters, notifications, clock);
    }

    @Bean
    vn.werwa.distribution.payout.PayoutService payouts(JdbcTemplate jdbc, SeriesCatalog series, AuditLog audit,
            Clock clock) {
        return new vn.werwa.distribution.payout.PayoutService(jdbc, series, audit, clock);
    }

    /** US-3.05-AC2: đối chiếu báo có từ mô-đun Tích hợp. */
    @Bean
    vn.werwa.platform.events.EventHandler distributionOnCreditReceived(vn.werwa.distribution.payment.PaymentService payments) {
        return new vn.werwa.platform.events.EventHandler() {
            @Override
            public String consumer() {
                return "distribution";
            }

            @Override
            public String eventType() {
                return "werwa.integration.bank.credit_received.v1";
            }

            @Override
            public void handle(vn.werwa.platform.events.InboundEvent event) {
                Map<String, Object> d = event.data();
                payments.match(new vn.werwa.distribution.payment.PaymentService.Credit((String) d.get("bankRef"),
                        (String) d.get("virtualAccount"), Long.parseLong((String) d.get("amountMinor")),
                        (String) d.get("currency"), java.time.LocalDate.parse((String) d.get("valueDate")),
                        (String) d.get("payerName"), d.get("messageId") == null ? null
                                : UUID.fromString((String) d.get("messageId"))));
            }
        };
    }

    /** Q53, Q54: việc định kỳ của bộ điều phối ghi sổ. */
    @Bean
    @ConditionalOnProperty(name = "werwa.distribution.book-scheduler-enabled", havingValue = "true", matchIfMissing = true)
    PaymentJobs paymentJobs(vn.werwa.distribution.payment.PaymentService payments, Clock clock) {
        return new PaymentJobs(payments, clock);
    }

    static class PaymentJobs {

        private final vn.werwa.distribution.payment.PaymentService payments;
        private final Clock clock;

        PaymentJobs(vn.werwa.distribution.payment.PaymentService payments, Clock clock) {
            this.payments = payments;
            this.clock = clock;
        }

        @Scheduled(fixedDelayString = "${werwa.distribution.payment-batch-interval:PT15M}")
        void tick() {
            try {
                payments.markOverdue(clock.instant());
                payments.buildBatches();
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(PaymentJobs.class).warn("Lượt theo dõi tiền lỗi: {}", e.toString());
            }
        }
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
