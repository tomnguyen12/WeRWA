package vn.werwa.onchain;

import org.springframework.http.HttpStatus;
import vn.werwa.platform.web.ErrorCode;

/** Mã lỗi kỹ thuật của mô-đun Onchain. Lỗi do hợp đồng từ chối dùng {@link vn.werwa.onchain.api.ChainCallRejected}. */
enum OnchainError implements ErrorCode {
    CHAIN_SIGNER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Chưa ký được giao dịch: chế độ ký chưa bật"),
    CHAIN_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Không kết nối được node chuỗi");

    private final HttpStatus status;
    private final String title;

    OnchainError(HttpStatus status, String title) {
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
        return "onchain";
    }
}
