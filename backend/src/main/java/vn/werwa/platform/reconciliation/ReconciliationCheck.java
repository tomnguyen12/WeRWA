package vn.werwa.platform.reconciliation;

import java.time.LocalDate;
import java.util.List;

/**
 * Một luật đối soát (US-4.09; R1–R8 của mô hình dữ liệu; Q73). Mô-đun giữ dữ liệu cài luật của mình; Quản trị chạy mọi
 * luật mỗi ngày và ghi chênh lệch, nên không cần đọc dữ liệu của mô-đun khác.
 */
public interface ReconciliationCheck {

    /** Luật, ví dụ {@code R1}. */
    String rule();

    /** Phạm vi đối soát: {@code LIMITS}, {@code CHAIN_VS_BANK}, {@code AUDIT_CHAIN}, … (Q74). */
    String scope();

    /** Các chênh lệch tìm thấy; rỗng nếu khớp. */
    List<Finding> run(LocalDate runDate);

    record Finding(String kind, String subjectRef, String expected, String actual) {}
}
