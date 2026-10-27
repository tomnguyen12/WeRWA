package vn.werwa.onboarding;

import org.springframework.http.HttpStatus;
import vn.werwa.platform.web.ErrorCode;

/** Mã lỗi nghiệp vụ của mô-đun Onboarding. */
public enum OnboardingError implements ErrorCode {
    ORGANIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có tổ chức này"),
    ORGANIZATION_EXISTS(HttpStatus.CONFLICT, "Tổ chức cùng số đăng ký đã có"),
    KYB_NOT_PENDING(HttpStatus.CONFLICT, "Hồ sơ KYB không còn chờ duyệt"),
    ORGANIZATION_NOT_APPROVED(HttpStatus.CONFLICT, "Tổ chức chưa qua KYB"),
    SIGNER_ADDRESS_TAKEN(HttpStatus.CONFLICT, "Địa chỉ ký đã thuộc người ký khác"),
    ORGANIZATION_NOT_INVESTOR(HttpStatus.UNPROCESSABLE_CONTENT, "Tổ chức không có vai nhà đầu tư"),
    KYC_CASE_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có hồ sơ KYC này"),
    KYC_NOT_OPEN(HttpStatus.CONFLICT, "Hồ sơ KYC đã kết luận"),
    MAKER_CHECKER_SAME_USER(HttpStatus.FORBIDDEN, "Người lập không được tự duyệt"),
    INVESTOR_CLASS_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "Kết luận đạt phải có loại nhà đầu tư"),
    INVESTOR_CLASS_UNKNOWN(HttpStatus.UNPROCESSABLE_CONTENT, "Loại nhà đầu tư không có trong danh mục"),
    REASON_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "Kết quả sàng lọc trùng: kết luận đạt phải ghi lý do");

    private final HttpStatus status;
    private final String title;

    OnboardingError(HttpStatus status, String title) {
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
        return "onboarding";
    }
}
