package vn.werwa.platform.security;

import java.util.Set;

/**
 * Thao tác ghi Cơ quan giám sát được phép làm (US-4.04-AC2; Q97). Quản trị cài đặt theo tham số hệ thống; không có
 * bean thì chặn mọi thao tác ghi.
 */
public interface SupervisorWritePolicy {

    /** operationId trong {@code api/openapi.yaml} được miễn chặn. */
    Set<String> allowedOperations();
}
