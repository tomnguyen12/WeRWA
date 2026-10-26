/**
 * Mô-đun Onboarding · phân hệ 1 (ADR-10). Dữ liệu ở schema PostgreSQL {@code onboarding}.
 * Mô-đun khác chỉ gọi vào gói {@code api}; còn lại đi bằng sự kiện (D4).
 */
@ApplicationModule(displayName = "Onboarding")
package vn.werwa.onboarding;

import org.springframework.modulith.ApplicationModule;
