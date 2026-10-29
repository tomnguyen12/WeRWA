package vn.werwa.onboarding;

import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.governance.api.ApprovalHook;
import vn.werwa.governance.api.Approvals;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.integration.api.Screening;
import vn.werwa.onboarding.kyc.KycService;
import vn.werwa.onboarding.organization.OrganizationSeeder;
import vn.werwa.onboarding.organization.OrganizationService;
import vn.werwa.platform.documents.DocumentRegistry;
import vn.werwa.platform.events.Events;

/** Bean của mô-đun Onboarding (B5a). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OnboardingProperties.class)
class OnboardingConfig {

    /** Cũng là nguồn vai tổ chức cho kiểm quyền (OrganizationDirectory, Q5). */
    @Bean
    @Primary
    OrganizationService organizations(JdbcTemplate jdbc, Approvals approvals, AuditLog audit, Events events, Clock clock,
            DocumentRegistry documents) {
        return new OrganizationService(jdbc, approvals, audit, events, clock, documents);
    }

    @Bean
    vn.werwa.onboarding.api.InvestorClasses investorClasses(JdbcTemplate jdbc) {
        return keys -> {
            java.util.List<String> known = jdbc.queryForList("SELECT key FROM onboarding.investor_class", String.class);
            return keys.stream().filter(k -> !known.contains(k)).toList();
        };
    }

    @Bean
    vn.werwa.onboarding.api.Wallets wallets(JdbcTemplate jdbc) {
        org.springframework.jdbc.core.RowMapper<vn.werwa.onboarding.api.Wallets.Wallet> row = (rs, i) ->
                new vn.werwa.onboarding.api.Wallets.Wallet(rs.getObject(1, java.util.UUID.class),
                        rs.getObject(2, java.util.UUID.class), rs.getLong(3), rs.getString(4), rs.getString(5));
        return new vn.werwa.onboarding.api.Wallets() {
            @Override
            public java.util.Optional<Wallet> registered(java.util.UUID walletId) {
                return jdbc.query("""
                        SELECT id, organization_id, chain_id, address, wallet_type FROM onboarding.wallet
                        WHERE id = ? AND status = 'REGISTERED'
                        """, row, walletId).stream().findFirst();
            }

            @Override
            public java.util.Optional<Wallet> byAddress(long chainId, String address) {
                return jdbc.query("""
                        SELECT id, organization_id, chain_id, address, wallet_type FROM onboarding.wallet
                        WHERE chain_id = ? AND address = ?
                        """, row, chainId, address.toLowerCase(java.util.Locale.ROOT)).stream().findFirst();
            }
        };
    }

    /** B4b (Q109): người ký được ủy quyền cho kiểm chữ ký multisig. */
    @Bean
    vn.werwa.platform.registry.SignerRegistry signerRegistry(vn.werwa.onboarding.api.Signers signers) {
        return (address, on) -> signers.active(address, on).map(vn.werwa.onboarding.api.Signers.Active::organizationId);
    }

    /** Q79: lưu ký của ví omnibus phải đã qua KYB. */
    @Bean
    vn.werwa.onboarding.api.Custodians custodians(JdbcTemplate jdbc) {
        return org -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM onboarding.organization
                               WHERE id = ? AND kyb_status = 'KYB_APPROVED' AND 'CUSTODIAN' = ANY (roles))
                """, Boolean.class, org));
    }

    /** US-3.02-AC1: KYC đạt và còn hạn; lấy loại nhà đầu tư của hồ sơ đạt mới nhất. */
    @Bean
    vn.werwa.onboarding.api.Investors investors(JdbcTemplate jdbc) {
        return (org, at) -> jdbc.queryForList("""
                SELECT k.investor_class FROM onboarding.kyc_case k
                JOIN onboarding.organization o ON o.id = k.organization_id
                WHERE k.organization_id = ? AND k.status = 'APPROVED' AND k.expires_at > ?
                  AND o.kyb_status = 'KYB_APPROVED' AND 'INVESTOR' = ANY (o.roles)
                ORDER BY k.decided_at DESC LIMIT 1
                """, String.class, org, java.sql.Timestamp.from(at)).stream().findFirst();
    }

    /** Q66: ví và điều kiện nhà đầu tư cho Quản trị, qua SPI của platform. */
    @Bean
    vn.werwa.platform.registry.WalletRegistry walletRegistry(vn.werwa.onboarding.api.Wallets wallets,
            vn.werwa.onboarding.api.Investors investors) {
        return new vn.werwa.platform.registry.WalletRegistry() {
            @Override
            public java.util.Optional<Wallet> registeredWallet(java.util.UUID walletId) {
                return wallets.registered(walletId).map(w -> new Wallet(w.id(), w.organizationId(), w.address()));
            }

            @Override
            public boolean investorEligible(java.util.UUID organizationId, java.time.Instant at) {
                return investors.eligibleClass(organizationId, at).isPresent();
            }
        };
    }

    @Bean
    KycService kyc(JdbcTemplate jdbc, OrganizationService organizations, Screening screening, AuditLog audit,
            Events events, JsonMapper json, OnboardingProperties props, Clock clock) {
        return new KycService(jdbc, organizations, screening, audit, events, json, props, clock);
    }

    @Bean
    ApplicationRunner seedOrganizations(OnboardingProperties props, JdbcTemplate jdbc, Clock clock) {
        return args -> {
            if (props.seedOrganizations() != null) {
                new OrganizationSeeder(jdbc, clock).seed(props.seedOrganizations());
            }
        };
    }

    /** Q28: mọi đường ra quyết định KYB (API riêng hay {@code /v1/approvals/{id}/decision}) áp vào tổ chức trong cùng giao dịch. */
    @Bean
    ApprovalHook kybApprovalHook(OrganizationService organizations) {
        return new ApprovalHook() {
            @Override
            public String actionType() {
                return OrganizationService.KYB_ACTION;
            }

            @Override
            public void onDecision(Decision decision) {
                organizations.applyDecision(decision.approvalId(), decision.approve());
            }
        };
    }
}
