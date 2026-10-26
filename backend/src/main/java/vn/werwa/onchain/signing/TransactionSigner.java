package vn.werwa.onchain.signing;

import org.web3j.crypto.RawTransaction;
import vn.werwa.onchain.api.SignerKey;

/**
 * Ký giao dịch, không gửi (Q14): trả raw tx đã ký để mô-đun Onchain tự giữ nonce, tính hash và gửi qua V1/V2. Mã ứng
 * dụng chỉ có {@link WeHsmSigner} (khóa trong HSM, US-6.04-AC4); bộ ký bằng khóa thử chỉ nằm trong {@code src/test} (Q15).
 */
public interface TransactionSigner {

    /** Địa chỉ của khóa. */
    String address(SignerKey key);

    /** Raw tx đã ký, dạng hex {@code 0x…}. */
    String sign(SignerKey key, RawTransaction transaction, long chainId);
}
