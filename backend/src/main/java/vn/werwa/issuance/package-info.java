/**
 * Mô-đun Phát hành · phân hệ 2 (ADR-10). Dữ liệu ở schema PostgreSQL {@code issuance}.
 * Mô-đun khác chỉ gọi vào gói {@code api}; còn lại đi bằng sự kiện (D4).
 */
@ApplicationModule(displayName = "Issuance")
package vn.werwa.issuance;

import org.springframework.modulith.ApplicationModule;
