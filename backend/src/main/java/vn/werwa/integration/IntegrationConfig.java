package vn.werwa.integration;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.integration.kyc.ScreeningProvider;
import vn.werwa.integration.kyc.ScreeningService;
import vn.werwa.integration.kyc.SimulatedScreeningProvider;

/** Bean của mô-đun Tích hợp. XD dùng nhà cung cấp KYC giả lập (ADR-14). */
@Configuration(proxyBeanMethods = false)
class IntegrationConfig {

    @Bean
    ScreeningProvider screeningProvider(Clock clock) {
        return new SimulatedScreeningProvider(clock);
    }

    @Bean
    ScreeningService screening(ScreeningProvider provider, JdbcTemplate jdbc, JsonMapper json, Clock clock) {
        return new ScreeningService(provider, jdbc, json, clock);
    }
}
