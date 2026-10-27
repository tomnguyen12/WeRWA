package vn.werwa.issuance;

import org.springframework.http.HttpStatus;
import vn.werwa.platform.web.ErrorCode;

/** Mã lỗi nghiệp vụ của mô-đun Phát hành. */
public enum IssuanceError implements ErrorCode {
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có tài liệu này"),
    DOCUMENT_ALREADY_SUPERSEDED(HttpStatus.CONFLICT, "Tài liệu đã có phiên bản mới hơn"),
    DOCUMENT_EMPTY(HttpStatus.BAD_REQUEST, "Tệp rỗng");

    private final HttpStatus status;
    private final String title;

    IssuanceError(HttpStatus status, String title) {
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
        return "issuance";
    }
}
