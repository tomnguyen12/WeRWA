package vn.werwa.distribution;

import org.springframework.http.HttpStatus;
import vn.werwa.platform.web.ErrorCode;

/** Mã lỗi nghiệp vụ của mô-đun Phân phối. */
public enum DistributionError implements ErrorCode {
    SERIES_NOT_FOUND(HttpStatus.UNPROCESSABLE_CONTENT, "Không có series này"),
    SERIES_ISIN_REQUIRED(HttpStatus.CONFLICT, "Series chưa ở Sẵn sàng hoặc chưa có ISIN"),
    RATE_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_CONTENT, "Lãi suất ngoài khung Đề án"),
    OFFERING_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Đợt chào bán không hợp lệ"),
    OFFERING_EXISTS(HttpStatus.CONFLICT, "Series đã có đợt chào bán"),
    OFFERING_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có đợt chào bán này"),
    OFFERING_NOT_DRAFT(HttpStatus.CONFLICT, "Đợt chào bán không ở trạng thái Nháp"),
    BOOK_NOT_OPEN(HttpStatus.CONFLICT, "Sổ lệnh không mở"),
    OFFERING_NOT_CLOSED(HttpStatus.CONFLICT, "Sổ lệnh chưa đóng"),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có lệnh này"),
    ORDER_CANCELLED(HttpStatus.CONFLICT, "Lệnh đã hủy"),
    KYC_NOT_VALID(HttpStatus.UNPROCESSABLE_CONTENT, "Nhà đầu tư chưa có KYC đạt còn hạn"),
    INVESTOR_CLASS_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT, "Loại nhà đầu tư không được mua series này"),
    WALLET_NOT_REGISTERED(HttpStatus.UNPROCESSABLE_CONTENT, "Ví chưa đăng ký hoặc không thuộc nhà đầu tư"),
    RATE_OUTSIDE_GUIDANCE(HttpStatus.UNPROCESSABLE_CONTENT, "Lãi suất đặt ngoài khoảng chỉ dẫn"),
    QUANTITY_NOT_MULTIPLE(HttpStatus.UNPROCESSABLE_CONTENT, "Khối lượng không phải bội số cho phép"),
    LIST_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có danh sách phân bổ này"),
    LIST_ALREADY_SUBMITTED(HttpStatus.CONFLICT, "Đợt chào bán đã có danh sách gửi UBND"),
    LIST_NOT_DRAFT(HttpStatus.CONFLICT, "Danh sách không ở trạng thái Nháp"),
    LIST_NOT_SIGNED(HttpStatus.CONFLICT, "Danh sách chưa được UBND ký"),
    ALLOCATION_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Mục phân bổ không hợp lệ"),
    ALLOCATION_EXCEEDS_ORDER(HttpStatus.UNPROCESSABLE_CONTENT, "Phân bổ vượt số lượng đặt"),
    ADJUSTMENT_REASON_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "Điều chỉnh tay phải có lý do"),
    AMOUNT_NOT_EXACT(HttpStatus.UNPROCESSABLE_CONTENT, "Số tiền phải trả không chia hết theo giá phát hành"),
    ALLOCATION_EXCEEDS_MAX(HttpStatus.UNPROCESSABLE_CONTENT, "Tổng phân bổ vượt khối lượng tối đa của series"),
    ISSUER_DECISION_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "Chưa có quyết định của UBND về lãi suất"),
    SERIES_NOT_DEPLOYED(HttpStatus.CONFLICT, "Series chưa có địa chỉ hợp đồng"),
    SNAPSHOT_NOT_FOUND(HttpStatus.NOT_FOUND, "Chưa có danh sách chốt của kỳ này"),
    SNAPSHOT_NOT_CALCULATED(HttpStatus.CONFLICT, "Danh sách chốt chưa tính dòng chi trả"),
    PERIOD_RATE_MISSING(HttpStatus.CONFLICT, "Kỳ chưa có lãi suất"),
    PAYOUT_ALREADY_INSTRUCTED(HttpStatus.CONFLICT, "Kỳ đã có lệnh chi, không tính lại"),
    ALLOCATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có phân bổ này"),
    TRANSFER_NOT_FOUND(HttpStatus.NOT_FOUND, "Không có yêu cầu chuyển này"),
    TRANSFER_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Yêu cầu chuyển không hợp lệ"),
    TRANSFER_SAME_WALLET(HttpStatus.UNPROCESSABLE_CONTENT, "Ví chuyển và ví nhận trùng nhau"),
    TRANSFER_RECIPIENT_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT, "Bên nhận không đủ điều kiện nắm giữ series"),
    TRANSFER_SERIES_NOT_ACTIVE(HttpStatus.CONFLICT, "Series không ở Đang lưu hành"),
    TRANSFER_NOT_PENDING(HttpStatus.CONFLICT, "Yêu cầu chuyển không còn chờ xác nhận"),
    TRANSFER_ALREADY_CONFIRMED(HttpStatus.CONFLICT, "Bên nhận đã xác nhận");

    private final HttpStatus status;
    private final String title;

    DistributionError(HttpStatus status, String title) {
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
        return "distribution";
    }
}
