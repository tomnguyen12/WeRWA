/**
 * Mô-đun Quản trị · phân hệ 4 (ADR-10). Dữ liệu ở schema PostgreSQL {@code governance}.
 * Mô-đun khác chỉ gọi vào gói {@code api}; còn lại đi bằng sự kiện (D4).
 */
@ApplicationModule(displayName = "Governance")
package vn.werwa.governance;

import org.springframework.modulith.ApplicationModule;
