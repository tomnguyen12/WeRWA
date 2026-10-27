package vn.werwa.onboarding.organization;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Nạp tổ chức thử của devnet ({@code infra/keycloak/seed/orgs.json}, I6) ở trạng thái đã qua KYB, đúng UUID cố định để khớp
 * claim {@code org_id} của Keycloak. Chỉ chạy khi cấu hình {@code werwa.onboarding.seed-organizations}; tổ chức đã có thì
 * giữ nguyên.
 */
public class OrganizationSeeder {

    private static final Logger log = LoggerFactory.getLogger(OrganizationSeeder.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OrganizationSeeder(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @SuppressWarnings("unchecked")
    public int seed(Resource file) {
        Map<String, Object> root;
        try (InputStream in = file.getInputStream()) {
            root = JsonParserFactory.getJsonParser().parseMap(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("không đọc được tệp tổ chức thử " + file, e);
        }
        int added = 0;
        Timestamp now = Timestamp.from(clock.instant());
        for (Object o : (List<Object>) root.get("organizations")) {
            Map<String, Object> org = (Map<String, Object>) o;
            List<String> roles = (List<String>) org.get("roles");
            added += jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO onboarding.organization
                            (id, legal_name, registration_no, country, roles, kyb_status, created_at, updated_at)
                        VALUES (?, ?, ?, 'VN', ?, 'KYB_APPROVED', ?, ?)
                        ON CONFLICT DO NOTHING
                        """);
                ps.setObject(1, UUID.fromString((String) org.get("id")));
                ps.setString(2, (String) org.get("legalName"));
                ps.setString(3, "DEVNET-" + org.get("code"));
                ps.setArray(4, con.createArrayOf("text", roles.toArray()));
                ps.setTimestamp(5, now);
                ps.setTimestamp(6, now);
                return ps;
            });
        }
        log.warn("Nạp {} tổ chức thử từ {} (chỉ dùng ở devnet và kiểm thử)", added, file);
        return added;
    }
}
