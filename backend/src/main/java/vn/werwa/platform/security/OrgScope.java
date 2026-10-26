package vn.werwa.platform.security;

import java.util.Set;
import java.util.UUID;
import vn.werwa.platform.web.ApiException;
import vn.werwa.platform.web.PlatformError;

/**
 * Kiểm đối tượng thuộc tổ chức của người gọi (US-4.07-AC2): người dùng tổ chức A gọi vào đối tượng của tổ chức B thì 403.
 * Vai được xem chéo tổ chức (ví dụ Holco vận hành, Cơ quan giám sát) do mô-đun nghiệp vụ truyền vào theo từng API.
 */
public final class OrgScope {

    private OrgScope() {}

    public static void requireOwnOrganization(UUID ownerOrganizationId) {
        requireOwnOrganizationOr(ownerOrganizationId, Set.of());
    }

    public static void requireOwnOrganizationOr(UUID ownerOrganizationId, Set<Role> crossOrganizationRoles) {
        CurrentUser user = CurrentUser.get();
        if (user.organizationId().equals(ownerOrganizationId) || user.hasAnyRole(crossOrganizationRoles)) {
            return;
        }
        throw new ApiException(PlatformError.ORG_SCOPE_VIOLATION);
    }
}
