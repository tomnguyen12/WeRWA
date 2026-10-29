package vn.werwa.onchain;

import java.math.BigInteger;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.utils.Numeric;
import vn.werwa.onchain.api.TokenBalances;

/** {@code balanceOf(address)} gọi thẳng bằng {@code eth_call} tại số khối, qua node chỉ đọc (ADR-12). */
class Web3jTokenBalances implements TokenBalances {

    private static final String BALANCE_OF = "0x70a08231";
    private static final String ZERO = "0x0000000000000000000000000000000000000000";

    private final ChainNodes nodes;

    Web3jTokenBalances(ChainNodes nodes) {
        this.nodes = nodes;
    }

    @Override
    public long balanceOf(String token, String wallet, long block) {
        String data = BALANCE_OF + Numeric.toHexStringNoPrefixZeroPadded(Numeric.toBigInt(wallet), 64);
        EthCall result = nodes.read("eth_call", w -> w.ethCall(Transaction.createEthCallTransaction(ZERO, token, data),
                DefaultBlockParameter.valueOf(BigInteger.valueOf(block))).send());
        if (result.hasError() || result.isReverted()) {
            throw new IllegalStateException("balanceOf lỗi tại khối " + block + ": "
                    + (result.hasError() ? result.getError().getMessage() : result.getRevertReason()));
        }
        String value = result.getValue();
        return value == null || "0x".equals(value) ? 0 : Numeric.toBigInt(value).longValueExact();
    }
}
