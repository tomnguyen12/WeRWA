package vn.werwa.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.werwa.platform.web.ApiContract;
import vn.werwa.platform.web.ApiContract.Operation;
import vn.werwa.platform.web.ApiException;
import vn.werwa.platform.web.PlatformError;
import vn.werwa.platform.web.Problems;
import vn.werwa.platform.inbound.BankChannel;
import vn.werwa.platform.web.idempotency.CachedBodyRequest;

/**
 * Kiểm quyền mọi API {@code /v1/**} theo hợp đồng (US-4.07), chạy sau khi token đã xác thực:
 *
 * <ol>
 *   <li>API phải có trong {@code api/openapi.yaml}; không có thì 404, sai phương thức thì 405.
 *   <li>Webhook ngân hàng: không dùng token; kiểm chữ ký thông điệp qua {@link BankChannel} (Q49). Không có kênh thì 403;
 *       sai chữ ký thì 401 {@code BANK_SIGNATURE_INVALID}.
 *   <li>Tổ chức trong {@code org_id} phải có trên nền tảng; vai người dùng phải nằm trong vai tổ chức (I7).
 *   <li>Người dùng phải có ít nhất một vai trong {@code x-werwa-roles} của API, trừ API {@code ANY}.
 * </ol>
 *
 * Kiểm theo đối tượng (US-4.07-AC2) do mô-đun nghiệp vụ làm bằng {@link OrgScope}.
 */
public class ContractAuthorizationFilter extends OncePerRequestFilter {

    /** Thuộc tính request chứa {@link Operation} đã khớp, cho các filter sau dùng. */
    public static final String OPERATION_ATTRIBUTE = ContractAuthorizationFilter.class.getName() + ".operation";

    private final ApiContract contract;
    private final OrganizationDirectory organizations;
    private final org.springframework.beans.factory.ObjectProvider<BankChannel> bankChannel;
    private final org.springframework.beans.factory.ObjectProvider<vn.werwa.platform.inbound.InboundToken> inboundToken;

    public ContractAuthorizationFilter(ApiContract contract, OrganizationDirectory organizations,
            org.springframework.beans.factory.ObjectProvider<BankChannel> bankChannel,
            org.springframework.beans.factory.ObjectProvider<vn.werwa.platform.inbound.InboundToken> inboundToken) {
        this.contract = contract;
        this.organizations = organizations;
        this.bankChannel = bankChannel;
        this.inboundToken = inboundToken;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !path(request).startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = path(request);
        Optional<Operation> found = contract.match(HttpMethod.valueOf(request.getMethod()), path);
        if (found.isEmpty()) {
            Problems.write(request, response,
                    contract.hasPath(path) ? PlatformError.METHOD_NOT_ALLOWED : PlatformError.ROUTE_NOT_FOUND, null);
            return;
        }
        Operation op = found.get();
        if (op.inboundToken()) {
            vn.werwa.platform.inbound.InboundToken tokens = inboundToken.getIfAvailable();
            if (tokens == null) {
                Problems.write(request, response, PlatformError.INBOUND_CHANNEL_NOT_ENABLED, null);
                return;
            }
            String auth = request.getHeader("Authorization");
            String bearer = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7).strip() : null;
            if (bearer == null || !tokens.accepts(op.operationId(), bearer)) {
                Problems.write(request, response, PlatformError.INBOUND_TOKEN_INVALID, null);
                return;
            }
            request.setAttribute(OPERATION_ATTRIBUTE, op);
            chain.doFilter(request, response);
            return;
        }
        if (op.bankMtls()) {
            BankChannel channel = bankChannel.getIfAvailable();
            if (channel == null) {
                Problems.write(request, response, PlatformError.INBOUND_CHANNEL_NOT_ENABLED, null);
                return;
            }
            CachedBodyRequest cached = new CachedBodyRequest(request);
            String signature = request.getHeader("X-Bank-Signature");
            if (signature == null || !channel.verify(cached.body(), signature)) {
                channel.rejected(op.operationId(), cached.body(), signature);
                Problems.write(request, response, PlatformError.BANK_SIGNATURE_INVALID, null);
                return;
            }
            cached.setAttribute(BankChannel.RAW_BODY_ATTRIBUTE, cached.body());
            cached.setAttribute(OPERATION_ATTRIBUTE, op);
            chain.doFilter(cached, response);
            return;
        }
        try {
            authorize(op);
        } catch (ApiException e) {
            Problems.write(request, response, e.error(), e.detail());
            return;
        }
        request.setAttribute(OPERATION_ATTRIBUTE, op);
        chain.doFilter(request, response);
    }

    private void authorize(Operation op) {
        CurrentUser user = CurrentUser.get();
        Set<Role> orgRoles = organizations.rolesOf(user.organizationId())
                .orElseThrow(() -> new ApiException(PlatformError.ORGANIZATION_UNKNOWN));
        if (!orgRoles.containsAll(user.roles())) {
            throw new ApiException(PlatformError.ROLE_NOT_IN_ORGANIZATION);
        }
        // US-4.04-AC2 (Q94): Cơ quan giám sát chỉ đọc, kể cả thao tác mở cho mọi vai
        if (user.roles().contains(Role.SUPERVISOR) && !org.springframework.http.HttpMethod.GET.equals(op.method())) {
            throw new ApiException(PlatformError.ROLE_NOT_ALLOWED, "Cơ quan giám sát chỉ được đọc");
        }
        if (!op.anyRole() && !user.hasAnyRole(op.roles())) {
            throw new ApiException(PlatformError.ROLE_NOT_ALLOWED, op.operationId() + " cần một trong " + op.roles());
        }
    }

    static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }
}
