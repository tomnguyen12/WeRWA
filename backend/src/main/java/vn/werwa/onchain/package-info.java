/**
 * Mô-đun Onchain · phân hệ 6 (ADR-10). Dữ liệu ở schema PostgreSQL {@code onchain}.
 * Mô-đun khác chỉ gọi vào gói {@code api}; còn lại đi bằng sự kiện (D4).
 */
@ApplicationModule(displayName = "Onchain")
package vn.werwa.onchain;

import org.springframework.modulith.ApplicationModule;
