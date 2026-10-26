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

/**
 * Kiểm quyền mọi API {@code /v1/**} theo hợp đồng (US-4.07), chạy sau khi token đã xác thực:
 *
 * <ol>
 *   <li>API phải có trong {@code api/openapi.yaml}; không có thì 404, sai phương thức thì 405.
 *   <li>Webhook ngân hàng (mTLS) chưa bật tới B8: 403.
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

    public ContractAuthorizationFilter(ApiContract contract, OrganizationDirectory organizations) {
        this.contract = contract;
        this.organizations = organizations;
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
        if (op.bankMtls()) {
            Problems.write(request, response, PlatformError.INBOUND_CHANNEL_NOT_ENABLED, null);
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
