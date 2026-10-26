/**
 * Mô-đun dùng chung (D3): mã kỹ thuật cho mọi phân hệ — cấu hình, lỗi problem+json, Idempotency-Key, If-Match,
 * UUIDv7, kiểu tiền và ppm, bảo mật JWT. Không chứa nghiệp vụ. Bảng kỹ thuật nằm ở schema {@code platform} (Q1).
 */
@ApplicationModule(displayName = "Platform", type = ApplicationModule.Type.OPEN)
package vn.werwa.platform;

import org.springframework.modulith.ApplicationModule;
