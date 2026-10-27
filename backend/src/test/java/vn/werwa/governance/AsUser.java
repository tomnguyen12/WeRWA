package vn.werwa.governance;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Chạy mã trong ngữ cảnh một người dùng có token OIDC (subject, org_id, roles), ngoài request HTTP. */
final class AsUser {

    private AsUser() {}

    static <T> T run(String subject, String orgId, List<String> roles, Supplier<T> body) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none").subject(subject)
                .claim("org_id", orgId).claim("roles", roles).build();
        var previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            return body.get();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }
}
