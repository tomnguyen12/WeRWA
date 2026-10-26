package vn.werwa.platform.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.io.Resource;

/**
 * Nguồn vai tổ chức tạm (Q5): tệp {@code infra/keycloak/seed/orgs.json} (I6), chép vào classpath {@code seed/orgs.json}
 * khi build. Chỉ dùng tới B5.
 */
public class SeedOrganizationDirectory implements OrganizationDirectory {

    private final Map<UUID, Set<Role>> roles;

    public SeedOrganizationDirectory(Resource seed) {
        this.roles = load(seed);
    }

    @Override
    public Optional<Set<Role>> rolesOf(UUID organizationId) {
        return Optional.ofNullable(roles.get(organizationId));
    }

    public int size() {
        return roles.size();
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Set<Role>> load(Resource seed) {
        try (InputStream in = seed.getInputStream()) {
            Map<String, Object> root = JsonParserFactory.getJsonParser()
                    .parseMap(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            Map<UUID, Set<Role>> out = new HashMap<>();
            for (Object o : (List<Object>) root.get("organizations")) {
                Map<String, Object> org = (Map<String, Object>) o;
                Set<Role> r = EnumSet.noneOf(Role.class);
                for (Object role : (List<Object>) org.get("roles")) {
                    r.add(Role.valueOf((String) role));
                }
                out.put(UUID.fromString((String) org.get("id")), Set.copyOf(r));
            }
            return Map.copyOf(out);
        } catch (IOException e) {
            throw new IllegalStateException("không đọc được tệp seed tổ chức " + seed, e);
        }
    }
}
