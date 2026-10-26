package vn.werwa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Ứng dụng khởi động với PostgreSQL thật (cùng phiên bản devnet) và Flyway tạo đủ bảy schema (ADR-10, Q1). */
class SchemasIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayCreatesSevenSchemas() {
        List<String> schemas = jdbc.queryForList(
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name = ANY (?)",
                String.class,
                (Object) new String[] {
                    "platform", "onboarding", "issuance", "distribution", "governance", "integration", "onchain"
                });

        assertThat(schemas)
                .containsExactlyInAnyOrder(
                        "platform", "onboarding", "issuance", "distribution", "governance", "integration", "onchain");
    }

    @Test
    void flywayHistoryLivesInPlatformSchema() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM platform.flyway_schema_history WHERE success", Integer.class);

        assertThat(applied).isPositive();
    }
}
