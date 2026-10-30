package vn.werwa.governance.approval;

import org.springframework.http.HttpStatus;
import vn.werwa.platform.web.ErrorCode;

/** Mã lỗi nghiệp vụ của mô-đun Quản trị. */
public enum GovernanceError implements ErrorCode {
    APPROVAL_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có yêu cầu duyệt này"),
    APPROVAL_NOT_PENDING(HttpStatus.CONFLICT, "Yêu cầu không còn chờ duyệt"),
    MAKER_CHECKER_SAME_USER(HttpStatus.FORBIDDEN, "Người lập không được tự duyệt"),
    PARAMETER_UNKNOWN(HttpStatus.UNPROCESSABLE_CONTENT, "Không có tham số này"),
    PARAMETER_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Giá trị tham số không hợp lệ"),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có thông báo này"),
    ENFORCEMENT_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Lệnh phong tỏa không hợp lệ"),
    ENFORCEMENT_WALLET_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Ví không đủ điều kiện cho lệnh"),
    ENFORCEMENT_SERIES_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Series không ở trạng thái áp lệnh được"),
    ENFORCEMENT_DOCUMENT_MISSING(HttpStatus.UNPROCESSABLE_CONTENT, "Văn bản căn cứ chưa có trong kho tài liệu",
            "DOCUMENT_NOT_FOUND"),
    ENFORCEMENT_ORDER_EXISTS(HttpStatus.CONFLICT, "Lệnh cùng cơ quan, số lệnh, hành động đã tiếp nhận"),
    ENFORCEMENT_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có lệnh phong tỏa này"),
    ENFORCEMENT_SIGNING_PENDING(HttpStatus.CONFLICT, "Lệnh đã có đề nghị ký đang mở"),
    ENFORCEMENT_NOT_SIGNABLE(HttpStatus.CONFLICT, "Lệnh không ở trạng thái chờ ký"),
    RECONCILIATION_BREAK_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có chênh lệch này"),
    RECONCILIATION_BREAK_RESOLVED(HttpStatus.CONFLICT, "Chênh lệch đã được đóng"),
    RECONCILIATION_REASON_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "Đóng chênh lệch phải ghi lý do"),
    ALERT_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có cảnh báo này"),
    BLOCK_HASH_ALREADY_ATTESTED(HttpStatus.CONFLICT, "Khối này đã có mã băm VSDC gửi"),
    SIGNING_PROPOSAL_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có đề nghị ký này"),
    SIGNING_PROPOSAL_NOT_COLLECTING(HttpStatus.CONFLICT, "Đề nghị ký không còn thu chữ ký"),
    SIGNING_PROPOSAL_NOT_CANCELLABLE(HttpStatus.CONFLICT, "Đề nghị ký đã gửi lên chuỗi, không hủy được"),
    SIGNATURE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Chữ ký không khớp người ký hoặc dữ liệu cần ký"),
    SIGNER_NOT_AUTHORIZED(HttpStatus.FORBIDDEN, "Người ký không phải owner Safe hoặc người ký được ủy quyền của tổ chức"),
    SIGNATURE_DUPLICATE(HttpStatus.CONFLICT, "Người ký đã nộp chữ ký cho đề nghị này"),
    SAFE_UNKNOWN(HttpStatus.UNPROCESSABLE_CONTENT, "Safe không có trong cấu hình hoặc owner không phải Safe tổ chức"),
    CHECKER_ROLE_REQUIRED(HttpStatus.FORBIDDEN, "Yêu cầu này phải do vai được chỉ định duyệt");

    private final HttpStatus status;
    private final String title;
    private final String code;

    GovernanceError(HttpStatus status, String title) {
        this(status, title, null);
    }

    GovernanceError(HttpStatus status, String title, String code) {
        this.status = status;
        this.title = title;
        this.code = code;
    }

    @Override
    public String code() {
        return code == null ? name() : code;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String group() {
        return "governance";
    }
}
