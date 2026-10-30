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
    private static final String TOTAL_SUPPLY = "0x18160ddd";
    private static final String GET_FROZEN = org.web3j.abi.FunctionEncoder.buildMethodId("getFrozenTokens(address)");
    private static final String ZERO = "0x0000000000000000000000000000000000000000";

    private final ChainNodes nodes;

    Web3jTokenBalances(ChainNodes nodes) {
        this.nodes = nodes;
    }

    @Override
    public long balanceOf(String token, String wallet, long block) {
        return call(token, BALANCE_OF + Numeric.toHexStringNoPrefixZeroPadded(Numeric.toBigInt(wallet), 64), block,
                "balanceOf");
    }

    @Override
    public long frozenOf(String token, String wallet, long block) {
        return call(token, GET_FROZEN + Numeric.toHexStringNoPrefixZeroPadded(Numeric.toBigInt(wallet), 64), block,
                "getFrozenTokens");
    }

    @Override
    public long totalSupply(String token, long block) {
        return call(token, TOTAL_SUPPLY, block, "totalSupply");
    }

    private long call(String token, String data, long block, String what) {
        EthCall result = nodes.read("eth_call", w -> w.ethCall(Transaction.createEthCallTransaction(ZERO, token, data),
                DefaultBlockParameter.valueOf(BigInteger.valueOf(block))).send());
        if (result.hasError() || result.isReverted()) {
            throw new IllegalStateException(what + " lỗi tại khối " + block + ": "
                    + (result.hasError() ? result.getError().getMessage() : result.getRevertReason()));
        }
        String value = result.getValue();
        return value == null || "0x".equals(value) ? 0 : Numeric.toBigInt(value).longValueExact();
    }
}
