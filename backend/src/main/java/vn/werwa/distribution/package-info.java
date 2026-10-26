/**
 * Mô-đun Phân phối · phân hệ 3 (ADR-10). Dữ liệu ở schema PostgreSQL {@code distribution}.
 * Mô-đun khác chỉ gọi vào gói {@code api}; còn lại đi bằng sự kiện (D4).
 */
@ApplicationModule(displayName = "Distribution")
package vn.werwa.distribution;

import org.springframework.modulith.ApplicationModule;
