package vn.werwa.governance.api;

import java.util.UUID;

/**
 * Đề nghị ký multisig (US-6.04; Q106–Q111). Mô-đun nghiệp vụ tạo đề nghị sau khi duyệt 4 mắt; Quản trị thu chữ ký EIP-712
 * của người ký các tổ chức, đủ ngưỡng thì gửi {@code execTransaction} của Safe. Kết quả trên chuỗi về mô-đun gọi qua
 * {@code werwa.onchain.tx.mined.v1} hoặc {@code tx.failed.v1} với đúng {@code purpose} và {@code subjectRef}.
 */
public interface SigningProposals {

    /**
     * @param safe tên Safe trong cấu hình ({@code governance3of3}, {@code vsdc}...) hoặc địa chỉ
     * @param functionName tên hàm của hợp đồng đích, để người ký đọc
     * @param calldata calldata hex của lời gọi hợp đồng đích
     */
    record Request(
            String purpose, String subjectRef, UUID approvalRequestId, String safe, String targetContract,
            String functionName, String calldata) {}

    record Ref(UUID id, String status) {}

    /** Gọi trong giao dịch DB của mô-đun gọi. Safe đang có đề nghị khác thì đề nghị mới ở {@code QUEUED} (Q110). */
    Ref create(Request request);
}
