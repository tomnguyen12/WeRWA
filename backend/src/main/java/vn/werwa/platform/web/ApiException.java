package vn.werwa.platform.web;

/** Lỗi trả cho bên gọi dưới dạng {@code application/problem+json} có {@code code}. */
public class ApiException extends RuntimeException {

    private final ErrorCode error;
    private final String detail;

    public ApiException(ErrorCode error) {
        this(error, null);
    }

    public ApiException(ErrorCode error, String detail) {
        super(error.code() + (detail == null ? "" : ": " + detail));
        this.error = error;
        this.detail = detail;
    }

    public ErrorCode error() {
        return error;
    }

    public String detail() {
        return detail;
    }
}
