package vn.werwa.platform.web;

import org.springframework.http.HttpStatus;

/** Mã lỗi kỹ thuật dùng chung cho mọi API (B1). Mã nghiệp vụ nằm ở mô-đun phân hệ. */
public enum PlatformError implements ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Chưa đăng nhập hoặc token không hợp lệ"),
    TOKEN_CLAIMS_INVALID(HttpStatus.UNAUTHORIZED, "Token thiếu org_id hoặc roles hợp lệ"),
    ROLE_NOT_ALLOWED(HttpStatus.FORBIDDEN, "Vai của người dùng không được gọi API này"),
    ROLE_NOT_IN_ORGANIZATION(HttpStatus.FORBIDDEN, "Vai của người dùng không thuộc vai của tổ chức"),
    ORGANIZATION_UNKNOWN(HttpStatus.FORBIDDEN, "Tổ chức của người dùng không có trên nền tảng"),
    ORG_SCOPE_VIOLATION(HttpStatus.FORBIDDEN, "Đối tượng thuộc tổ chức khác"),
    INBOUND_CHANNEL_NOT_ENABLED(HttpStatus.FORBIDDEN, "Kênh nhận từ ngân hàng chưa bật"),
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có API này"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Phương thức không được hỗ trợ"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Request không đọc được"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Kiểu nội dung không được hỗ trợ"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Không trả được kiểu nội dung yêu cầu"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Lệnh ghi bắt buộc header Idempotency-Key"),
    IDEMPOTENCY_KEY_INVALID(HttpStatus.BAD_REQUEST, "Idempotency-Key không hợp lệ"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency-Key đã dùng cho request khác"),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "Request cùng Idempotency-Key đang xử lý"),
    PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "Ghi đè bắt buộc header If-Match"),
    PRECONDITION_FAILED(HttpStatus.PRECONDITION_FAILED, "Phiên bản đã thay đổi, tải lại rồi thử lại"),
    PAGE_LIMIT_INVALID(HttpStatus.BAD_REQUEST, "limit phải từ 1 tới 200"),
    CURSOR_INVALID(HttpStatus.BAD_REQUEST, "cursor không hợp lệ"),
    NOT_IMPLEMENTED(HttpStatus.NOT_IMPLEMENTED, "API có trong đặc tả nhưng chưa làm"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi hệ thống");

    private final HttpStatus status;
    private final String title;

    PlatformError(HttpStatus status, String title) {
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
        return "platform";
    }
}
