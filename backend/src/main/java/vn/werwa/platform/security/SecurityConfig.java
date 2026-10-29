package vn.werwa.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import vn.werwa.platform.audit.AuditSink;
import vn.werwa.platform.web.ApiContract;
import vn.werwa.platform.web.PlatformError;
import vn.werwa.platform.web.Problems;
import vn.werwa.platform.web.idempotency.IdempotencyFilter;
import vn.werwa.platform.web.idempotency.IdempotencyStore;

/**
 * Resource server OIDC (ADR-15; I3, I7, I8): token của Keycloak realm {@code werwa}, kiểm issuer và audience theo cấu
 * hình {@code spring.security.oauth2.resourceserver.jwt.*}. Không có phiên phía máy chủ; MFA do Keycloak bắt buộc khi
 * đăng nhập (I4). Sau khi xác thực, {@link ContractAuthorizationFilter} kiểm quyền theo hợp đồng rồi
 * {@link IdempotencyFilter} xử lý {@code Idempotency-Key}.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            ApiContract contract,
            OrganizationDirectory organizations,
            IdempotencyStore idempotency,
            org.springframework.beans.factory.ObjectProvider<AuditSink> audit,
            org.springframework.beans.factory.ObjectProvider<vn.werwa.platform.inbound.BankChannel> bankChannel,
            org.springframework.beans.factory.ObjectProvider<vn.werwa.platform.inbound.InboundToken> inboundToken,
            org.springframework.beans.factory.ObjectProvider<SupervisorWritePolicy> supervisorWrites)
            throws Exception {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        AuthenticationEntryPoint unauthenticated = (request, response, ex) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            Problems.write(request, response, PlatformError.UNAUTHENTICATED, null);
        };
        AccessDeniedHandler denied =
                (request, response, ex) -> Problems.write(request, response, PlatformError.ROLE_NOT_ALLOWED, null);

        ContractAuthorizationFilter contractFilter = new ContractAuthorizationFilter(contract, organizations, bankChannel,
                inboundToken, supervisorWrites);
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Webhook ngân hàng: mTLS ở ingress, chữ ký thông điệp kiểm ở ContractAuthorizationFilter (Q49).
                        .requestMatchers("/v1/inbound/**").permitAll()
                        .requestMatchers("/v1/**").authenticated()
                        .anyRequest().hasRole(Role.OPERATOR.name()))
                .oauth2ResourceServer(o -> o
                        // Kênh /v1/inbound/** không dùng JWT: token Bearer cố định (Q56) do ContractAuthorizationFilter kiểm
                        .bearerTokenResolver(request -> request.getRequestURI().startsWith("/v1/inbound/") ? null
                                : new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver()
                                        .resolve(request))
                        .jwt(j -> j.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(denied))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied))
                .addFilterAfter(contractFilter, AuthorizationFilter.class)
                .addFilterAfter(new IdempotencyFilter(idempotency, audit.getIfAvailable(() -> write -> {})),
                        ContractAuthorizationFilter.class)
                .build();
    }
}
