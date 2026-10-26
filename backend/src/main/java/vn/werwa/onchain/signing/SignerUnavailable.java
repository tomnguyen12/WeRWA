package vn.werwa.onchain.signing;

/** Không ký được: chưa bật chế độ ký, hoặc WeHSM không trả lời hay từ chối theo chính sách. */
public class SignerUnavailable extends RuntimeException {

    public SignerUnavailable(String message) {
        super(message);
    }

    public SignerUnavailable(String message, Throwable cause) {
        super(message, cause);
    }

    static SignerUnavailable devnetMode() {
        return new SignerUnavailable(
                "chế độ ký devnet: chưa ký được; dựng devnet với --signer wehsm (I12) để ký qua WeHSM");
    }
}
