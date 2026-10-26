package vn.werwa.platform.security;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tra vai của tổ chức để kiểm "vai người dùng nằm trong vai tổ chức" (I7). Keycloak không biết vai của tổ chức.
 *
 * <p>Q5: tới B5 dùng {@link SeedOrganizationDirectory} đọc tệp seed của devnet; B5 thay bằng bảng {@code organization}
 * của mô-đun onboarding (khai báo bean cài interface này).
 */
public interface OrganizationDirectory {

    /** Vai của tổ chức; rỗng nếu tổ chức không có trên nền tảng. */
    Optional<Set<Role>> rolesOf(UUID organizationId);
}
