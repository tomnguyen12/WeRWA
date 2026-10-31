package vn.werwa.platform;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import vn.werwa.platform.web.ApiContract;
import vn.werwa.platform.web.idempotency.IdempotencyStore;

/** Bean dùng chung của mô-đun platform. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class PlatformConfig {

    private static final Logger log = LoggerFactory.getLogger(PlatformConfig.class);

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Hợp đồng {@code api/openapi.yaml}, chép vào classpath {@code contract/} khi build. */
    @Bean
    ApiContract apiContract(@Value("classpath:contract/openapi.yaml") Resource spec,
            @Value("${werwa.api.probe-contract:#{null}}") Resource probe) {
        ApiContract contract = ApiContract.load(spec);
        if (probe != null) {
            // Chỉ kiểm thử đặt: API thăm dò để kiểm lớp nền mà không đụng đường dẫn thật
            contract = contract.with(ApiContract.load(probe));
            log.warn("Nạp thêm hợp đồng thăm dò {}", probe);
        }
        log.info("Nạp hợp đồng API: {} API", contract.operations().size());
        return contract;
    }

    @Bean
    IdempotencyStore idempotencyStore(JdbcTemplate jdbc, Clock clock) {
        return new IdempotencyStore(jdbc, clock);
    }

    @Bean
    IdempotencyPurge idempotencyPurge(IdempotencyStore store) {
        return new IdempotencyPurge(store);
    }

    /** Dọn {@code Idempotency-Key} quá 24 giờ, mỗi giờ một lần. */
    static class IdempotencyPurge {

        private final IdempotencyStore store;

        IdempotencyPurge(IdempotencyStore store) {
            this.store = store;
        }

        @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
        void purge() {
            int removed = store.purgeExpired();
            if (removed > 0) {
                log.info("Dọn {} Idempotency-Key quá hạn", removed);
            }
        }
    }
}
