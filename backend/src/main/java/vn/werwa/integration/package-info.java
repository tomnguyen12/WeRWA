/**
 * Mô-đun Tích hợp · phân hệ 5 (ADR-10). Dữ liệu ở schema PostgreSQL {@code integration}.
 * Mô-đun khác chỉ gọi vào gói {@code api}; còn lại đi bằng sự kiện (D4).
 */
@ApplicationModule(displayName = "Integration")
package vn.werwa.integration;

import org.springframework.modulith.ApplicationModule;
