package vn.werwa.platform.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import vn.werwa.platform.web.ApiException;
import vn.werwa.platform.web.PlatformError;

/**
 * Người gọi API, đọc từ token OIDC của Keycloak: {@code sub}, {@code org_id} (UUID tổ chức) và {@code roles} (I3, I7).
 */
public record CurrentUser(String subject, UUID organizationId, Set<Role> roles) {

    public CurrentUser {
        roles = Set.copyOf(roles);
    }

    /** Người gọi của request hiện tại; không có token hợp lệ thì 401. */
    public static CurrentUser get() {
        return find().orElseThrow(() -> new ApiException(PlatformError.UNAUTHENTICATED));
    }

    public static Optional<CurrentUser> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return Optional.of(fromJwt(token.getToken()));
        }
        return Optional.empty();
    }

    /** Claim thiếu hoặc sai dạng trả 401 {@code TOKEN_CLAIMS_INVALID}. */
    public static CurrentUser fromJwt(Jwt jwt) {
        String subject = jwt.getSubject();
        Object org = jwt.getClaims().get("org_id");
        Object roles = jwt.getClaims().get("roles");
        if (subject == null || subject.isBlank() || !(org instanceof String orgId) || !(roles instanceof Collection<?> list)) {
            throw new ApiException(PlatformError.TOKEN_CLAIMS_INVALID);
        }
        try {
            Set<Role> parsed = EnumSet.noneOf(Role.class);
            for (Object r : list) {
                parsed.add(Role.valueOf(String.valueOf(r)));
            }
            return new CurrentUser(subject, UUID.fromString(orgId), parsed);
        } catch (IllegalArgumentException e) {
            throw new ApiException(PlatformError.TOKEN_CLAIMS_INVALID);
        }
    }

    public boolean hasAnyRole(Collection<Role> candidates) {
        return candidates.stream().anyMatch(roles::contains);
    }
}
