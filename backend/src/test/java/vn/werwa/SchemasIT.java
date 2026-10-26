package vn.werwa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Ứng dụng khởi động với PostgreSQL thật (cùng phiên bản devnet) và Flyway tạo đủ bảy schema (ADR-10, Q1). */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers
class SchemasIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

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
