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

    /** B11c: vòng đời ngoài chuỗi của kỳ trả nợ (Q98–Q105). */
    @Bean
    vn.werwa.distribution.payout.DebtServiceRunner debtServiceRunner(JdbcTemplate jdbc,
            org.springframework.transaction.support.TransactionTemplate tx, vn.werwa.issuance.api.DebtServices debts,
            SeriesCatalog series, vn.werwa.distribution.payout.PayoutService payouts,
            vn.werwa.integration.api.PayingAgent agent, vn.werwa.governance.api.Notifications notifications,
            vn.werwa.governance.api.BusinessCalendar calendar, vn.werwa.platform.registry.HoldingsSummaries holdings,
            Clock clock) {
        return new vn.werwa.distribution.payout.DebtServiceRunner(jdbc, tx, debts, series, payouts, agent, notifications,
                calendar, holdings, clock);
    }

    /** Q104: xác nhận chi của BIDV, nhận qua NATS từ mô-đun Tích hợp. */
    @Bean
    vn.werwa.platform.events.EventHandler distributionOnPayoutConfirmed(
            vn.werwa.distribution.payout.DebtServiceRunner runner) {
        return new vn.werwa.platform.events.EventHandler() {
            @Override
            public String consumer() {
                return "distribution";
            }

            @Override
            public String eventType() {
                return "werwa.integration.bank.payout_confirmed.v1";
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handle(vn.werwa.platform.events.InboundEvent event) {
                runner.confirmed(String.valueOf(event.data().get("instructionRef")),
                        (java.util.List<Map<String, Object>>) event.data().get("lines"));
            }
        };
    }

    /** Q101: 08:00 giờ VN mỗi ngày. */
    @Bean
    @ConditionalOnProperty(name = "werwa.distribution.debt-service-enabled", havingValue = "true", matchIfMissing = true)
    DebtServiceJob debtServiceJob(vn.werwa.distribution.payout.DebtServiceRunner runner,
            vn.werwa.distribution.payout.SnapshotService snapshots, Clock clock) {
        return new DebtServiceJob(runner, snapshots, clock);
    }

    static class DebtServiceJob {
        private static final java.time.ZoneId VN = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        private final vn.werwa.distribution.payout.DebtServiceRunner runner;
        private final vn.werwa.distribution.payout.SnapshotService snapshots;
        private final Clock clock;

        DebtServiceJob(vn.werwa.distribution.payout.DebtServiceRunner runner,
                vn.werwa.distribution.payout.SnapshotService snapshots, Clock clock) {
            this.runner = runner;
            this.snapshots = snapshots;
            this.clock = clock;
        }

        /** Q101 08:00; Q161 lặp mỗi giờ tới 16:00 để gửi lệnh chi khi VSDC ký muộn. */
        @Scheduled(cron = "0 0 8-16 * * *", zone = "Asia/Ho_Chi_Minh")
        void daily() {
            runner.run(clock.instant().atZone(VN).toLocalDate());
        }

        /** Q156: 00:10 giờ VN ngày T chụp danh sách chốt tại khối cuối ngày T−1. */
        @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Ho_Chi_Minh")
        void snapshot() {
            java.time.LocalDate today = clock.instant().atZone(VN).toLocalDate();
            for (Map.Entry<UUID, Integer> due : snapshots.dueToday(today)) {
                try {
                    snapshots.take(due.getKey(), due.getValue(), today.minusDays(1), false);
                } catch (RuntimeException e) {
                    org.slf4j.LoggerFactory.getLogger(DebtServiceJob.class).warn("Chưa chụp được kỳ {} series {}: {}",
                            due.getValue(), due.getKey(), e.toString());
                }
            }
        }

        /** Q159: nhắc VSDC xác nhận danh sách chốt. */
        @Scheduled(fixedDelayString = "${werwa.distribution.snapshot-overdue-interval:PT15M}")
        void snapshotOverdue() {
            try {
                snapshots.alertOverdue(clock.instant());
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(DebtServiceJob.class).warn("Lượt nhắc VSDC lỗi: {}", e.toString());
            }
        }
    }

    /** B11b: chụp danh sách chốt, VSDC xác nhận (Q155–Q161). */
    @Bean
    vn.werwa.distribution.payout.SnapshotService snapshotService(JdbcTemplate jdbc, SeriesCatalog series,
            vn.werwa.issuance.api.DebtServices debts, vn.werwa.onchain.api.IndexedEvents indexed,
            vn.werwa.onchain.api.TokenBalances balances, vn.werwa.onchain.api.ContractCalls calls,
            vn.werwa.onchain.api.ChainTransactions chain, vn.werwa.governance.api.SigningProposals proposals,
            vn.werwa.governance.api.OperationalAlerts alerts, vn.werwa.governance.api.Parameters parameters, Events events,
            AuditLog audit, Clock clock) {
        return new vn.werwa.distribution.payout.SnapshotService(jdbc, series, debts, indexed, balances, calls, chain,
                proposals, alerts, parameters, events, audit, clock);
    }

    /** Q158: danh sách đã lên chuỗi thì tính dòng chi (để VSDC đối chiếu) và đề nghị VSDC ký, mỗi việc giao dịch riêng. */
    static void afterSnapshotTaken(UUID seriesId, int period, vn.werwa.distribution.payout.SnapshotService snapshots,
            vn.werwa.distribution.payout.PayoutService payouts,
            org.springframework.transaction.support.TransactionTemplate separate) {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DistributionConfig.class);
        try {
            separate.executeWithoutResult(s -> payouts.calculate(seriesId, period));
        } catch (RuntimeException e) {
            log.warn("Chưa tính được dòng chi kỳ {} series {}: {}", period, seriesId, e.toString());
        }
        try {
            separate.executeWithoutResult(s -> snapshots.proposeAttestation(seriesId, period, false));
        } catch (RuntimeException e) {
            log.warn("Chưa tạo được đề nghị VSDC ký danh sách chốt kỳ {} series {}: {}", period, seriesId, e.toString());
        }
    }

    /** R6 (Q105): kỳ đã chi hết nhưng tổng đã chi lệch tổng các dòng đã gửi. */
    @Bean
    vn.werwa.platform.reconciliation.ReconciliationCheck payoutCheck(JdbcTemplate jdbc,
            vn.werwa.issuance.api.DebtServices debts) {
        return new vn.werwa.platform.reconciliation.ReconciliationCheck() {
            @Override
            public String rule() {
                return "R6";
            }

            @Override
            public String scope() {
                return "CHAIN_VS_BANK";
            }

            @Override
            public java.util.List<Finding> run(java.time.LocalDate runDate) {
                java.util.List<Finding> out = new java.util.ArrayList<>();
                for (vn.werwa.issuance.api.DebtServices.Due d : debts.withStatus("PAID")) {
                    Map<String, Object> sums = jdbc.queryForMap("""
                            SELECT COALESCE(sum(net_minor), 0) AS expected, COALESCE(sum(paid_minor), 0) AS paid
                            FROM distribution.distribution_line WHERE payment_instruction_ref = ?
                            """, d.instructionRef());
                    out.add(new Finding("PAYOUT_MISMATCH", "debt-service/" + d.eventId(),
                            String.valueOf(sums.get("expected")), String.valueOf(sums.get("paid"))));
                }
                return out;
            }
        };
    }

    /** B9c: sổ người sở hữu, sao kê (Q83–Q89). */
    @Bean
    vn.werwa.distribution.holding.HoldingsService holdings(JdbcTemplate jdbc, SeriesCatalog series, Wallets wallets,
            vn.werwa.governance.api.OperationalAlerts alerts, Clock clock) {
        return new vn.werwa.distribution.holding.HoldingsService(jdbc, series, wallets, alerts, clock);
    }

    /** Q84, Q85: bộ chiếu nhận sự kiện indexer; xử lý lặp lại an toàn nhờ khóa (chuỗi, khối, log, ví). */
    @Bean
    vn.werwa.platform.events.EventHandler distributionOnEventIndexed(vn.werwa.distribution.holding.HoldingsService holdings,
            vn.werwa.distribution.allocation.AllocationSigning allocationSigning,
            vn.werwa.distribution.payment.PaymentBatchSigning batchSigning,
            vn.werwa.distribution.payment.MintOrchestrator mints,
            vn.werwa.distribution.register.RegisterAttestationService register,
            vn.werwa.distribution.payout.SnapshotService snapshots, vn.werwa.distribution.payout.PayoutService payouts,
            org.springframework.transaction.PlatformTransactionManager txManager) {
        org.springframework.transaction.support.TransactionTemplate separate = separate(txManager);
        return new vn.werwa.platform.events.EventHandler() {
            @Override
            public String consumer() {
                return "distribution";
            }

            @Override
            public String eventType() {
                return "werwa.onchain.event.indexed.v1";
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handle(vn.werwa.platform.events.InboundEvent event) {
                Map<String, Object> d = event.data();
                if ("series.controller".equals(d.get("contractKind")) && "AllocationSet".equals(d.get("eventName"))) {
                    // B7b (Q124): UBND đã ký danh sách phân bổ
                    allocationSigning.onAllocationSet(String.valueOf(d.get("contract")),
                            (Map<String, Object>) d.get("args"), String.valueOf(d.get("txHash")));
                    return;
                }
                if ("series.controller".equals(d.get("contractKind")) && "PaymentBatchAdded".equals(d.get("eventName"))) {
                    // B8b (Q132): BIDV đã ký gốc lô
                    batchSigning.onPaymentBatchAdded(String.valueOf(d.get("contract")),
                            (Map<String, Object>) d.get("args"), String.valueOf(d.get("txHash")));
                    return;
                }
                if ("series.controller".equals(d.get("contractKind")) && "Minted".equals(d.get("eventName"))) {
                    // B8b (Q134): phân bổ đã mint
                    mints.onMinted(String.valueOf(d.get("contract")), (Map<String, Object>) d.get("args"),
                            String.valueOf(d.get("txHash")), ((Number) d.get("block")).longValue());
                    // B9d (Q141): mục cuối đã mint thì lập sổ cho VSDC
                    register.seriesOf(String.valueOf(d.get("contract")))
                            .ifPresent(id -> proposeRegister(id, register, separate));
                    return;
                }
                if ("series.controller".equals(d.get("contractKind")) && "SnapshotTaken".equals(d.get("eventName"))) {
                    // B11b (Q158): danh sách chốt đã lên chuỗi
                    snapshots.onSnapshotTaken(String.valueOf(d.get("contract")), (Map<String, Object>) d.get("args"),
                            String.valueOf(d.get("txHash")))
                            .ifPresent(e -> afterSnapshotTaken(e.getKey(), e.getValue(), snapshots, payouts, separate));
                    return;
                }
                if ("series.controller".equals(d.get("contractKind")) && "SnapshotAttested".equals(d.get("eventName"))) {
                    // B11b (Q158): VSDC đã xác nhận danh sách chốt
                    snapshots.onSnapshotAttested(String.valueOf(d.get("contract")), (Map<String, Object>) d.get("args"),
                            String.valueOf(d.get("txHash")));
                    return;
                }
                if ("series.controller".equals(d.get("contractKind")) && "RegisterAttested".equals(d.get("eventName"))) {
                    // B9d (Q143): VSDC đã ký xác nhận sổ
                    register.onRegisterAttested(String.valueOf(d.get("contract")), (Map<String, Object>) d.get("args"),
                            String.valueOf(d.get("txHash")));
                    return;
                }
                if ("series.controller".equals(d.get("contractKind")) && "CorrectionBurned".equals(d.get("eventName"))) {
                    // B9d (Q145): Holco đã hủy mục VSDC chỉ ra; lập sổ lại
                    register.onCorrectionBurned(String.valueOf(d.get("contract")), (Map<String, Object>) d.get("args"),
                            String.valueOf(d.get("txHash")), ((Number) d.get("block")).longValue())
                            .ifPresent(id -> proposeRegister(id, register, separate));
                    return;
                }
                if (!vn.werwa.distribution.holding.HoldingsService.TOKEN_KIND.equals(d.get("contractKind"))) {
                    return;
                }
                Object blockTime = d.get("blockTime");
                Object args = d.get("args");
                if (blockTime == null || !(args instanceof Map)) {
                    throw new IllegalStateException("sự kiện indexer thiếu blockTime hoặc args (Q84): " + event.id());
                }
                holdings.project(new vn.werwa.distribution.holding.HoldingsService.IndexedLog(
                        ((Number) d.get("chainId")).longValue(), String.valueOf(d.get("contract")),
                        String.valueOf(d.get("contractKind")), String.valueOf(d.get("eventName")),
                        ((Number) d.get("block")).longValue(), ((Number) d.get("logIndex")).intValue(),
                        String.valueOf(d.get("txHash")), java.time.Instant.parse(String.valueOf(blockTime)),
                        (Map<String, Object>) args));
            }
        };
    }

    /** R5 (Q89): số dư trong bảng đọc bằng số dư trên chuỗi tại cùng số khối (US-3.07-AC1). */
    @Bean
    vn.werwa.distribution.holding.HoldingViewCheck holdingViewCheck(vn.werwa.distribution.holding.HoldingsService holdings,
            vn.werwa.onchain.api.TokenBalances balances) {
        return new vn.werwa.distribution.holding.HoldingViewCheck(holdings, balances);
    }

    /** B9a: chuyển OTC, phần ngoài chuỗi (Q77–Q82). */
    @Bean
    vn.werwa.distribution.transfer.TransferService transfers(JdbcTemplate jdbc, SeriesCatalog series, Wallets wallets,
            Investors investors, vn.werwa.onboarding.api.Custodians custodians, AuditLog audit, Events events,
            Clock clock) {
        return new vn.werwa.distribution.transfer.TransferService(jdbc, series, wallets, investors, custodians, audit,
                events, clock);
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
    PaymentJobs paymentJobs(vn.werwa.distribution.payment.PaymentService payments,
            vn.werwa.distribution.payment.PaymentBatchSigning batchSigning,
            vn.werwa.distribution.payment.MintOrchestrator mints,
            vn.werwa.distribution.register.RegisterAttestationService register,
            org.springframework.transaction.PlatformTransactionManager txManager, AuditLog audit, Clock clock) {
        return new PaymentJobs(payments, batchSigning, mints, register, separate(txManager), audit, clock);
    }

    static org.springframework.transaction.support.TransactionTemplate separate(
            org.springframework.transaction.PlatformTransactionManager txManager) {
        org.springframework.transaction.support.TransactionTemplate t =
                new org.springframework.transaction.support.TransactionTemplate(txManager);
        t.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    /** Q130: mỗi lô một đề nghị ký, giao dịch riêng; không tạo được thì ghi nhật ký (Holco tạo lại qua API). */
    static void proposeBatches(java.util.List<UUID> batches,
            vn.werwa.distribution.payment.PaymentBatchSigning batchSigning,
            org.springframework.transaction.support.TransactionTemplate separate, AuditLog audit) {
        for (UUID batch : batches) {
            try {
                separate.executeWithoutResult(s -> batchSigning.propose(batch));
            } catch (RuntimeException e) {
                audit.record("PAYMENT_BATCH_SIGNING_PROPOSAL_FAILED", "payment-batch/" + batch,
                        Map.of("reason", String.valueOf(e.getMessage())));
            }
        }
    }

    /** Q133: mỗi phân bổ một giao dịch riêng; lỗi một mục không chặn mục khác. */
    static void mintEach(java.util.List<UUID> allocations, vn.werwa.distribution.payment.MintOrchestrator mints,
            org.springframework.transaction.support.TransactionTemplate separate) {
        for (UUID allocation : allocations) {
            try {
                separate.executeWithoutResult(s -> mints.submit(allocation, false));
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(DistributionConfig.class)
                        .warn("Chưa gửi được mint phân bổ {}: {}", allocation, e.toString());
            }
        }
    }

    /** Q141: lập sổ trong giao dịch riêng; chưa đủ điều kiện thì thôi, lỗi thì ghi log (lịch quét bù). */
    static void proposeRegister(UUID seriesId, vn.werwa.distribution.register.RegisterAttestationService register,
            org.springframework.transaction.support.TransactionTemplate separate) {
        try {
            separate.executeWithoutResult(s -> register.propose(seriesId, false));
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(DistributionConfig.class)
                    .warn("Chưa lập được sổ cho VSDC của series {}: {}", seriesId, e.toString());
        }
    }

    /** B9d: VSDC xác nhận sổ, sửa sổ (Q139–Q146). */
    @Bean
    vn.werwa.distribution.register.RegisterAttestationService registerAttestations(JdbcTemplate jdbc,
            SeriesCatalog series, vn.werwa.governance.api.SigningProposals proposals, Approvals approvals,
            vn.werwa.onchain.api.ChainTransactions chain, vn.werwa.onchain.api.ContractCalls calls,
            vn.werwa.onchain.api.TokenBalances balances, vn.werwa.governance.api.OperationalAlerts alerts,
            vn.werwa.governance.api.Notifications notifications, vn.werwa.governance.api.BusinessCalendar calendar,
            vn.werwa.governance.api.Parameters parameters, Events events, AuditLog audit, Clock clock) {
        return new vn.werwa.distribution.register.RegisterAttestationService(jdbc, series, proposals, approvals, chain,
                calls, balances, alerts, notifications, calendar, parameters, events, audit, clock);
    }

    /** Q145: duyệt 4 mắt yêu cầu sửa mục. */
    @Bean
    ApprovalHook allocationCorrectionApprovalHook(vn.werwa.distribution.register.RegisterAttestationService register) {
        return new ApprovalHook() {
            @Override
            public String actionType() {
                return vn.werwa.distribution.register.RegisterAttestationService.CORRECTION_ACTION;
            }

            @Override
            public void onDecision(Decision decision) {
                register.applyCorrectionDecision(decision.approvalId(), decision.approve());
            }
        };
    }

    static class PaymentJobs {

        private final vn.werwa.distribution.payment.PaymentService payments;
        private final vn.werwa.distribution.payment.PaymentBatchSigning batchSigning;
        private final vn.werwa.distribution.payment.MintOrchestrator mints;
        private final vn.werwa.distribution.register.RegisterAttestationService register;
        private final org.springframework.transaction.support.TransactionTemplate separate;
        private final AuditLog audit;
        private final Clock clock;

        PaymentJobs(vn.werwa.distribution.payment.PaymentService payments,
                vn.werwa.distribution.payment.PaymentBatchSigning batchSigning,
                vn.werwa.distribution.payment.MintOrchestrator mints,
                vn.werwa.distribution.register.RegisterAttestationService register,
                org.springframework.transaction.support.TransactionTemplate separate, AuditLog audit, Clock clock) {
            this.payments = payments;
            this.batchSigning = batchSigning;
            this.mints = mints;
            this.register = register;
            this.separate = separate;
            this.audit = audit;
            this.clock = clock;
        }

        @Scheduled(fixedDelayString = "${werwa.distribution.payment-batch-interval:PT15M}")
        void tick() {
            try {
                payments.markOverdue(clock.instant());
                proposeBatches(payments.buildBatches(), batchSigning, separate, audit);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(PaymentJobs.class).warn("Lượt theo dõi tiền lỗi: {}", e.toString());
            }
        }

        /** Q133: quét bù các phân bổ có tiền trong lô đã ký mà chưa gửi mint. */
        @Scheduled(fixedDelayString = "${werwa.distribution.mint-sweep-interval:PT5M}")
        void mintSweep() {
            try {
                mintEach(mints.pendingAll(), mints, separate);
                // Q141, Q146: lập sổ bù và nhắc VSDC
                for (UUID series : register.candidates()) {
                    proposeRegister(series, register, separate);
                }
                register.alertOverdue(clock.instant());
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(PaymentJobs.class).warn("Lượt quét mint lỗi: {}", e.toString());
            }
        }
    }

    /** B8b: BIDV ký gốc lô (Q129–Q132). */
    @Bean
    vn.werwa.distribution.payment.PaymentBatchSigning paymentBatchSigning(JdbcTemplate jdbc, SeriesCatalog series,
            vn.werwa.governance.api.SigningProposals proposals, vn.werwa.governance.api.OperationalAlerts alerts,
            Events events, AuditLog audit, Clock clock) {
        return new vn.werwa.distribution.payment.PaymentBatchSigning(jdbc, series, proposals, alerts, events, audit,
                clock);
    }

    /** B8b: bộ điều phối mint (Q133–Q135). */
    @Bean
    vn.werwa.distribution.payment.MintOrchestrator mintOrchestrator(JdbcTemplate jdbc, AllocationListService lists,
            vn.werwa.onchain.api.ChainTransactions chain, vn.werwa.onchain.api.ContractCalls calls,
            vn.werwa.governance.api.OperationalAlerts alerts, Events events, AuditLog audit, Clock clock) {
        return new vn.werwa.distribution.payment.MintOrchestrator(jdbc, lists, chain, calls, alerts, events, audit,
                clock);
    }

    /** R4 (Q138): tổng cung token bằng tổng đã mint. */
    @Bean
    vn.werwa.distribution.payment.SupplyCheck supplyCheck(vn.werwa.distribution.payment.MintOrchestrator mints,
            SeriesCatalog series, vn.werwa.onchain.api.ContractCalls calls, vn.werwa.onchain.api.TokenBalances balances,
            vn.werwa.onchain.api.IndexedEvents indexed) {
        return new vn.werwa.distribution.payment.SupplyCheck(mints, series, calls, balances, indexed);
    }

    /** Q133: lô vừa được BIDV ký thì gửi mint các phân bổ trong lô. */
    @Bean
    vn.werwa.platform.events.EventHandler distributionOnPaymentBatchSigned(
            vn.werwa.distribution.payment.MintOrchestrator mints,
            org.springframework.transaction.PlatformTransactionManager txManager) {
        org.springframework.transaction.support.TransactionTemplate separate = separate(txManager);
        return new vn.werwa.platform.events.EventHandler() {
            @Override
            public String consumer() {
                return "distribution";
            }

            @Override
            public String eventType() {
                return vn.werwa.distribution.payment.PaymentBatchSigning.SIGNED_EVENT;
            }

            @Override
            public void handle(vn.werwa.platform.events.InboundEvent event) {
                mintEach(mints.pending(UUID.fromString(String.valueOf(event.data().get("batchId")))), mints, separate);
            }
        };
    }

    /** Q135: giao dịch mint revert trên chuỗi. */
    @Bean
    vn.werwa.platform.events.EventHandler distributionOnTxFailed(vn.werwa.distribution.payment.MintOrchestrator mints,
            vn.werwa.distribution.register.RegisterAttestationService register,
            vn.werwa.distribution.payout.SnapshotService snapshots) {
        return new vn.werwa.platform.events.EventHandler() {
            @Override
            public String consumer() {
                return "distribution";
            }

            @Override
            public String eventType() {
                return "werwa.onchain.tx.failed.v1";
            }

            @Override
            public void handle(vn.werwa.platform.events.InboundEvent event) {
                UUID txId = UUID.fromString(String.valueOf(event.data().get("txId")));
                String reason = String.valueOf(event.data().get("reason"));
                mints.onTxFailed(txId, reason);
                register.onTxFailed(txId, reason);
                snapshots.onTxFailed(txId, reason);
            }
        };
    }

    /** R2 (Q73): tổng danh sách đã ký không vượt khối lượng tối đa series và bằng tổng các mục. */
    @Bean
    vn.werwa.platform.reconciliation.ReconciliationCheck allocationTotalCheck(JdbcTemplate jdbc, SeriesCatalog series) {
        return new vn.werwa.platform.reconciliation.ReconciliationCheck() {
            @Override
            public String rule() {
                return "R2";
            }

            @Override
            public String scope() {
                return "LIMITS";
            }

            @Override
            public java.util.List<Finding> run(java.time.LocalDate runDate) {
                java.util.List<Finding> out = new java.util.ArrayList<>();
                jdbc.query("""
                        SELECT l.id, l.total_quantity, o.series_id, COALESCE(SUM(a.quantity), 0)
                        FROM distribution.allocation_list l JOIN distribution.offering o ON o.id = l.offering_id
                        LEFT JOIN distribution.allocation a ON a.allocation_list_id = l.id
                        WHERE l.status = 'SIGNED' GROUP BY l.id, l.total_quantity, o.series_id
                        """, rs -> {
                    UUID list = rs.getObject(1, UUID.class);
                    long total = rs.getLong(2);
                    long items = rs.getLong(4);
                    long max = series.find(rs.getObject(3, UUID.class)).map(SeriesCatalog.SeriesInfo::maxQuantity)
                            .orElse(0L);
                    if (total > max) {
                        out.add(new Finding("ALLOCATION_EXCEEDS_MAX", "allocation-list/" + list, "≤ " + max,
                                Long.toString(total)));
                    }
                    if (items != total) {
                        out.add(new Finding("LIST_TOTAL_MISMATCH", "allocation-list/" + list, Long.toString(total),
                                Long.toString(items)));
                    }
                });
                return out;
            }
        };
    }

    /** R6 (Q73): mỗi phân bổ đã có tiền có đúng một xác nhận MATCHED, đúng số tiền phải trả; và ngược lại. */
    @Bean
    vn.werwa.platform.reconciliation.ReconciliationCheck paymentCheck(JdbcTemplate jdbc) {
        return new vn.werwa.platform.reconciliation.ReconciliationCheck() {
            @Override
            public String rule() {
                return "R6";
            }

            @Override
            public String scope() {
                return "CHAIN_VS_BANK";
            }

            @Override
            public java.util.List<Finding> run(java.time.LocalDate runDate) {
                java.util.List<Finding> out = new java.util.ArrayList<>(jdbc.query("""
                        SELECT a.id, a.amount_due_minor, count(p.id), COALESCE(SUM(p.amount_minor), 0)
                        FROM distribution.allocation a
                        LEFT JOIN distribution.payment_confirmation p ON p.allocation_id = a.id AND p.match_result = 'MATCHED'
                        WHERE a.status IN ('PAID', 'MINTED', 'CONFIRMED')
                        GROUP BY a.id, a.amount_due_minor
                        HAVING count(p.id) <> 1 OR COALESCE(SUM(p.amount_minor), 0) <> a.amount_due_minor
                        """, (rs, i) -> new Finding("PAYMENT_MISMATCH", "allocation/" + rs.getObject(1),
                        "1 xác nhận, " + rs.getLong(2), rs.getLong(3) + " xác nhận, " + rs.getLong(4))));
                out.addAll(jdbc.query("""
                        SELECT p.bank_ref, a.id, a.status FROM distribution.payment_confirmation p
                        JOIN distribution.allocation a ON a.id = p.allocation_id
                        WHERE p.match_result = 'MATCHED' AND a.status NOT IN ('PAID', 'MINTED', 'CONFIRMED')
                        """, (rs, i) -> new Finding("ALLOCATION_STATUS_MISMATCH", "allocation/" + rs.getObject(2),
                        "PAID (báo có " + rs.getString(1) + ")", rs.getString(3))));
                return out;
            }
        };
    }

    /** Q40: UBND duyệt danh sách phân bổ. */
    /** B7b: UBND ký danh sách phân bổ trên chuỗi (Q121–Q127). */
    @Bean
    vn.werwa.distribution.allocation.AllocationSigning allocationSigning(JdbcTemplate jdbc, AllocationListService lists,
            OfferingService offerings, SeriesCatalog series, vn.werwa.governance.api.SigningProposals proposals,
            vn.werwa.governance.api.OperationalAlerts alerts, Events events, AuditLog audit, Clock clock) {
        return new vn.werwa.distribution.allocation.AllocationSigning(jdbc, lists, offerings, series, proposals, alerts,
                events, audit, clock);
    }

    /** Q122: UBND duyệt đạt thì tạo đề nghị ký trong giao dịch riêng; không tạo được thì ghi nhật ký (Holco tạo lại). */
    @Bean
    vn.werwa.platform.events.EventHandler distributionOnApprovalDecided(
            vn.werwa.distribution.allocation.AllocationSigning allocationSigning,
            vn.werwa.distribution.register.RegisterAttestationService register,
            org.springframework.transaction.PlatformTransactionManager txManager, AuditLog audit) {
        org.springframework.transaction.support.TransactionTemplate separate =
                new org.springframework.transaction.support.TransactionTemplate(txManager);
        separate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new vn.werwa.platform.events.EventHandler() {
            @Override
            public String consumer() {
                return "distribution";
            }

            @Override
            public String eventType() {
                return "werwa.governance.approval.decided.v1";
            }

            @Override
            public void handle(vn.werwa.platform.events.InboundEvent event) {
                Map<String, Object> d = event.data();
                if (!"APPROVE".equals(d.get("decision"))) {
                    return;
                }
                if (vn.werwa.distribution.register.RegisterAttestationService.CORRECTION_ACTION.equals(d.get("actionType"))) {
                    // Q145: BURN gửi giao dịch hủy; DISMISS đã áp trong hook, lập sổ lại
                    UUID approval = UUID.fromString(String.valueOf(d.get("approvalId")));
                    register.burnOfApproval(approval).ifPresentOrElse(allocation -> {
                        try {
                            separate.executeWithoutResult(s -> register.submitBurn(allocation));
                        } catch (RuntimeException e) {
                            audit.record("ALLOCATION_CORRECTION_BURN_SUBMIT_FAILED", "allocation/" + allocation,
                                    Map.of("reason", String.valueOf(e.getMessage())));
                        }
                    }, () -> register.candidates().forEach(series -> proposeRegister(series, register, separate)));
                    return;
                }
                if (!AllocationListService.SUBMIT_ACTION.equals(d.get("actionType"))) {
                    return;
                }
                allocationSigning.listOfApproval(UUID.fromString(String.valueOf(d.get("approvalId")))).ifPresent(list -> {
                    try {
                        separate.executeWithoutResult(s -> allocationSigning.propose(list));
                    } catch (RuntimeException e) {
                        audit.record("ALLOCATION_LIST_SIGNING_PROPOSAL_FAILED", "allocation-list/" + list,
                                Map.of("reason", String.valueOf(e.getMessage())));
                    }
                });
            }
        };
    }

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
