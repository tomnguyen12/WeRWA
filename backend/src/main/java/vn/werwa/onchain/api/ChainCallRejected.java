package vn.werwa.onchain.api;

import java.util.Locale;
import org.springframework.http.HttpStatus;
import vn.werwa.platform.web.ApiException;
import vn.werwa.platform.web.ErrorCode;

/**
 * Hợp đồng từ chối lời gọi ở bước chạy thử {@code eth_call} (Q16). Mã lỗi là tên custom error của hợp đồng đổi sang chữ
 * hoa gạch dưới, thêm tiền tố {@code CHAIN_}: {@code AlreadyAnchored} thành {@code CHAIN_ALREADY_ANCHORED}.
 */
public class ChainCallRejected extends ApiException {

    private final String contractError;

    public ChainCallRejected(String contractError, String detail) {
        super(new Code(contractError), detail);
        this.contractError = contractError;
    }

    /** Tên custom error trong ABI, ví dụ {@code AlreadyAnchored}; {@code Error(string)}, {@code Panic} hoặc {@code UNKNOWN}. */
    public String contractError() {
        return contractError;
    }

    record Code(String contractError) implements ErrorCode {

        @Override
        public String code() {
            return "CHAIN_" + contractError.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.CONFLICT;
        }

        @Override
        public String title() {
            return "Hợp đồng từ chối thao tác";
        }

        @Override
        public String group() {
            return "onchain";
        }
    }
}
