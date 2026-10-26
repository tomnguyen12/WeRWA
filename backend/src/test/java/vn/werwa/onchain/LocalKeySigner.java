package vn.werwa.onchain;

import java.util.concurrent.atomic.AtomicInteger;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;
import vn.werwa.onchain.api.SignerKey;
import vn.werwa.onchain.signing.TransactionSigner;

/** Bộ ký giả chỉ có trong kiểm thử (Q15): khóa thử sinh ngẫu nhiên lúc chạy, thay cho WeHSM. */
class LocalKeySigner implements TransactionSigner {

    final AtomicInteger signed = new AtomicInteger();
    private final Credentials credentials;

    LocalKeySigner(Credentials credentials) {
        this.credentials = credentials;
    }

    @Override
    public String address(SignerKey key) {
        return credentials.getAddress().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public String sign(SignerKey key, RawTransaction transaction, long chainId) {
        signed.incrementAndGet();
        return Numeric.toHexString(TransactionEncoder.signMessage(transaction, chainId, credentials));
    }
}
