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
