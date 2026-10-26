package vn.werwa.onchain.api;

import java.util.Objects;

/**
 * Yêu cầu gửi một giao dịch lên chuỗi.
 *
 * @param purpose mục đích, ví dụ {@code AUDIT_ANCHOR}, {@code MINT}; ghi vào {@code chain_tx.purpose}
 * @param subjectRef đối tượng nghiệp vụ, ví dụ {@code allocation/<id>}; trả lại trong sự kiện kết quả
 * @param signer khóa ký
 * @param to địa chỉ hợp đồng
 * @param data calldata, mã hóa từ lớp hợp đồng sinh từ ABI ({@link ContractCalls})
 */
public record TxRequest(String purpose, String subjectRef, SignerKey signer, String to, String data) {

    public TxRequest {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(subjectRef, "subjectRef");
        Objects.requireNonNull(signer, "signer");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(data, "data");
    }
}
