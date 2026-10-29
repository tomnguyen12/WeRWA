package vn.werwa.issuance;

import org.springframework.http.HttpStatus;
import vn.werwa.platform.web.ErrorCode;

/** Mã lỗi nghiệp vụ của mô-đun Phát hành. */
public enum IssuanceError implements ErrorCode {
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có tài liệu này"),
    DOCUMENT_ALREADY_SUPERSEDED(HttpStatus.CONFLICT, "Tài liệu đã có phiên bản mới hơn"),
    DOCUMENT_EMPTY(HttpStatus.BAD_REQUEST, "Tệp rỗng"),
    /** Mã tài liệu được tham chiếu chưa có trong kho; cùng mã lỗi với Onboarding (Q26). */
    DOCUMENT_REFERENCE_MISSING(HttpStatus.UNPROCESSABLE_CONTENT, "Mã tài liệu chưa có trong kho tài liệu",
            "DOCUMENT_NOT_FOUND"),
    INVESTOR_CLASS_UNKNOWN(HttpStatus.UNPROCESSABLE_CONTENT, "Loại nhà đầu tư không có trong danh mục"),
    PROGRAM_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Khung Đề án không hợp lệ"),
    PROGRAM_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có Đề án này"),
    PROGRAM_NOT_EFFECTIVE(HttpStatus.CONFLICT, "Đề án không ở trạng thái hiệu lực"),
    PROGRAM_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Vượt khối lượng tối đa của Đề án"),
    SERIES_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có series này"),
    SERIES_CODE_TAKEN(HttpStatus.CONFLICT, "Mã series đã có"),
    SERIES_OUTSIDE_PROGRAM(HttpStatus.UNPROCESSABLE_CONTENT, "Term sheet nằm ngoài khung Đề án"),
    INVESTOR_CLASS_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT, "Loại nhà đầu tư không thuộc đối tượng của Đề án"),
    SERIES_NOT_EDITABLE(HttpStatus.CONFLICT, "Series không còn ở trạng thái sửa được"),
    SERIES_NOT_DRAFT(HttpStatus.CONFLICT, "Chỉ gửi duyệt được series ở trạng thái Nháp"),
    REQUIRED_DOCUMENTS_MISSING(HttpStatus.UNPROCESSABLE_CONTENT, "Thiếu tài liệu bắt buộc"),
    SERIES_NOT_READY(HttpStatus.CONFLICT, "Series không ở trạng thái cho bước này"),
    SERIES_DEPLOY_PENDING(HttpStatus.CONFLICT, "Series đã có đề nghị ký triển khai đang mở"),
    ISIN_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "ISIN không đúng ISO 6166"),
    ISIN_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "Series đã có ISIN hoặc đề nghị gắn ISIN đang mở"),
    ISSUER_DECISION_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Quyết định thiếu đối tượng áp dụng"),
    ISSUER_DECISION_EXISTS(HttpStatus.CONFLICT, "Số quyết định đã được ghi");

    private final HttpStatus status;
    private final String title;
    private final String code;

    IssuanceError(HttpStatus status, String title) {
        this(status, title, null);
    }

    IssuanceError(HttpStatus status, String title, String code) {
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
        return "issuance";
    }
}
