package vn.werwa.governance.approval;

import org.springframework.http.HttpStatus;
import vn.werwa.platform.web.ErrorCode;

/** Mã lỗi nghiệp vụ của mô-đun Quản trị. */
public enum GovernanceError implements ErrorCode {
    APPROVAL_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có yêu cầu duyệt này"),
    APPROVAL_NOT_PENDING(HttpStatus.CONFLICT, "Yêu cầu không còn chờ duyệt"),
    MAKER_CANNOT_APPROVE(HttpStatus.FORBIDDEN, "Người lập không được tự duyệt");

    private final HttpStatus status;
    private final String title;

    GovernanceError(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
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
