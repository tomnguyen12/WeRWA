package vn.werwa.onboarding;

import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.governance.api.Approvals;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.integration.api.Screening;
import vn.werwa.onboarding.kyc.KycService;
import vn.werwa.onboarding.organization.OrganizationSeeder;
import vn.werwa.onboarding.organization.OrganizationService;
import vn.werwa.platform.documents.DocumentRegistry;
import vn.werwa.platform.events.EventHandler;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.events.InboundEvent;

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

    /** Quyết định KYB ra qua {@code /v1/approvals/{id}/decision} cũng phải áp vào tổ chức. */
    @Bean
    EventHandler onboardingOnApprovalDecided(OrganizationService organizations) {
        return new EventHandler() {
            @Override
            public String consumer() {
                return "onboarding";
            }

            @Override
            public String eventType() {
                return "werwa.governance.approval.decided.v1";
            }

            @Override
            public void handle(InboundEvent event) {
                if (OrganizationService.KYB_ACTION.equals(event.data().get("actionType"))) {
                    organizations.applyDecision(UUID.fromString(String.valueOf(event.data().get("approvalId"))),
                            "APPROVE".equals(event.data().get("decision")));
                }
            }
        };
    }
}
