package vn.werwa.platform.security;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tra vai của tổ chức để kiểm "vai người dùng nằm trong vai tổ chức" (I7). Keycloak không biết vai của tổ chức.
 *
 * <p>Mô-đun Onboarding cài interface này từ bảng {@code organization} (B5a, thay tệp seed theo Q5): chỉ tổ chức đã qua
 * KYB mới có vai.
 */
public interface OrganizationDirectory {

    /** Vai của tổ chức; rỗng nếu tổ chức không có trên nền tảng. */
    Optional<Set<Role>> rolesOf(UUID organizationId);
}
