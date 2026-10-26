package vn.werwa.onchain.signing;

import org.web3j.crypto.RawTransaction;
import vn.werwa.onchain.api.SignerKey;

/**
 * Chế độ ký {@code devnet} (I12): khóa vận hành Holco chỉ là địa chỉ giữ chỗ, nên không ký được. Gửi giao dịch trả lỗi rõ
 * ràng thay vì treo.
 */
public class UnavailableSigner implements TransactionSigner {

    @Override
    public String address(SignerKey key) {
        throw SignerUnavailable.devnetMode();
    }

    @Override
    public String sign(SignerKey key, RawTransaction transaction, long chainId) {
        throw SignerUnavailable.devnetMode();
    }
}
