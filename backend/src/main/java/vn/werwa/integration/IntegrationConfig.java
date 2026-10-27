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
@org.springframework.boot.context.properties.EnableConfigurationProperties(vn.werwa.integration.bank.BidvProperties.class)
class IntegrationConfig {

    /** Q62: chỉ gửi email khi có máy chủ SMTP ({@code spring.mail.host}). */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty("spring.mail.host")
    vn.werwa.platform.mail.Mailer mailer(org.springframework.mail.javamail.JavaMailSender sender,
            @org.springframework.beans.factory.annotation.Value("${werwa.integration.mail.from:no-reply@werwa.local}")
            String from) {
        return new vn.werwa.integration.mail.SmtpMailer(sender, from);
    }

    /** Q49: kênh webhook BIDV chỉ bật khi có khóa công khai của BIDV. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty("werwa.integration.bidv.public-key")
    vn.werwa.integration.bank.BidvBankChannel bidvBankChannel(vn.werwa.integration.bank.BidvProperties props,
            JdbcTemplate jdbc, Clock clock) {
        return new vn.werwa.integration.bank.BidvBankChannel(props.publicKey(), jdbc, clock);
    }

    @Bean
    vn.werwa.integration.bank.InboundBankService inboundBank(JdbcTemplate jdbc,
            vn.werwa.platform.events.Events events, vn.werwa.governance.api.AuditLog audit, Clock clock) {
        return new vn.werwa.integration.bank.InboundBankService(jdbc, events, audit, clock);
    }

    @Bean
    vn.werwa.integration.bank.SimulatedVirtualAccounts virtualAccounts(JdbcTemplate jdbc,
            vn.werwa.integration.bank.BidvProperties props, Clock clock) {
        return new vn.werwa.integration.bank.SimulatedVirtualAccounts(jdbc, props.virtualAccountPrefix(), clock);
    }

    @Bean
    ScreeningProvider screeningProvider(Clock clock) {
        return new SimulatedScreeningProvider(clock);
    }

    @Bean
    ScreeningService screening(ScreeningProvider provider, JdbcTemplate jdbc, JsonMapper json, Clock clock) {
        return new ScreeningService(provider, jdbc, json, clock);
    }
}
