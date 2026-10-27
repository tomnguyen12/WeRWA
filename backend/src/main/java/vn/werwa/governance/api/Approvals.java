package vn.werwa.governance.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Duyệt 4 mắt (ADR-13, US-4.08; Q18, Q19). Mô-đun gọi trong giao dịch của mình khi người lập gửi một thao tác trọng yếu.
 * Người duyệt cùng tổ chức, khác người lập, ra quyết định qua {@code POST /v1/approvals/{id}/decision}; kết quả báo qua
 * {@code werwa.governance.approval.decided.v1}.
 */
public interface Approvals {

    /**
     * Gửi duyệt. Người lập là người gọi hiện tại. Đối tượng đã có yêu cầu đang chờ: cùng nội dung thì trả lại yêu cầu
     * đó; khác nội dung thì yêu cầu cũ chuyển {@code CANCELLED} và tạo yêu cầu mới (US-4.08-AC1).
     *
     * @param actionType loại thao tác, ví dụ {@code SERIES_SUBMIT}
     * @param subjectRef đối tượng, ví dụ {@code series/<id>}
     * @param payload nội dung được duyệt; nền tảng lưu SHA-256 của dạng JSON chuẩn hóa
     * @return id yêu cầu duyệt
     */
    UUID request(String actionType, String subjectRef, Object payload);

    /**
     * Hủy yêu cầu đang chờ vì đối tượng đã đổi (ADR-13: nội dung đổi thì yêu cầu bị hủy). Yêu cầu không còn chờ thì bỏ qua.
     */
    void cancel(UUID approvalId, String reason);

    /** Trạng thái hiện tại: PENDING, APPROVED, REJECTED, CANCELLED. */
    String status(UUID approvalId);

    /** Yêu cầu đang chờ của một đối tượng, nếu có. */
    Optional<UUID> pendingFor(String actionType, String subjectRef);

    /**
     * Ra quyết định thay cho người gọi hiện tại, cùng luật với {@code POST /v1/approvals/{id}/decision}: người duyệt cùng
     * tổ chức người lập, khác người lập (Q18). Dùng cho API quyết định riêng của nghiệp vụ, ví dụ {@code /kyb-decision}.
     */
    Outcome decide(UUID approvalId, boolean approve, String comment);

    /** Kết quả quyết định. {@code status}: APPROVED hoặc REJECTED. */
    record Outcome(UUID approvalId, String actionType, String subjectRef, String status, String makerSubject,
            String checkerSubject) {}
}
