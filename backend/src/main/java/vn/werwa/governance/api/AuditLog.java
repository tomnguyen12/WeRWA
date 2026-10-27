package vn.werwa.governance.api;

/**
 * Nhật ký kiểm toán nối mã băm (ADR-13, US-4.08). Mô-đun ghi thao tác trọng yếu trong giao dịch nghiệp vụ của mình;
 * mọi lệnh ghi API thành công đã được ghi tự động (Q22). Chỉ lưu mã băm nội dung.
 */
public interface AuditLog {

    /**
     * @param action việc đã làm, ví dụ {@code SERIES_DEPLOY_REQUESTED}
     * @param subjectRef đối tượng
     * @param payload nội dung; lưu SHA-256 của dạng JSON chuẩn hóa
     * @return số thứ tự của dòng nhật ký
     */
    long record(String action, String subjectRef, Object payload);
}
