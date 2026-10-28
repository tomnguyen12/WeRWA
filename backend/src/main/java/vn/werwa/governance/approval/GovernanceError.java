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
    ALERT_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có cảnh báo này"),
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
