package vn.werwa.governance.api;

import java.util.UUID;

/**
 * Kiểm và áp nghiệp vụ lúc duyệt (Q28). Mô-đun khai một bean cho mỗi loại thao tác; mọi đường ra quyết định (API chung
 * {@code /v1/approvals/{id}/decision} hay API riêng như {@code /kyb-decision}) đều gọi hook trong cùng giao dịch, sau khi
 * kiểm 4 mắt và trước khi ghi quyết định. Hook ném {@code ApiException} thì cả giao dịch hủy và yêu cầu vẫn PENDING.
 */
public interface ApprovalHook {

    /** Loại thao tác hook phụ trách, ví dụ {@code SERIES_SUBMIT}. */
    String actionType();

    /** Chạy trong giao dịch duyệt; áp kết quả vào đối tượng của mô-đun. */
    void onDecision(Decision decision);

    record Decision(UUID approvalId, String subjectRef, boolean approve, String makerSubject, String checkerSubject,
            String comment) {}
}
