package vn.werwa.onchain;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import vn.werwa.onchain.api.Safes;

/** Gọi hàm view của Safe bằng {@code eth_call} qua node chỉ đọc (ADR-12). */
class Web3jSafes implements Safes {

    private static final String ZERO = "0x0000000000000000000000000000000000000000";

    private final ChainNodes nodes;

    Web3jSafes(ChainNodes nodes) {
        this.nodes = nodes;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<String> owners(String safe) {
        List<Type> out = call(safe, new Function("getOwners", List.of(),
                List.of(new TypeReference<DynamicArray<Address>>() {})));
        return ((List<Address>) out.getFirst().getValue()).stream()
                .map(a -> a.getValue().toLowerCase(Locale.ROOT)).toList();
    }

    @Override
    public int threshold(String safe) {
        return uint(safe, "getThreshold").intValueExact();
    }

    @Override
    public long nonce(String safe) {
        return uint(safe, "nonce").longValueExact();
    }

    @SuppressWarnings("rawtypes")
    private BigInteger uint(String safe, String name) {
        List<Type> out = call(safe, new Function(name, List.of(), List.of(new TypeReference<Uint256>() {})));
        return (BigInteger) out.getFirst().getValue();
    }

    @SuppressWarnings("rawtypes")
    private List<Type> call(String safe, Function f) {
        String data = FunctionEncoder.encode(f);
        EthCall r = nodes.read("eth_call", w -> w.ethCall(Transaction.createEthCallTransaction(ZERO, safe, data),
                DefaultBlockParameterName.LATEST).send());
        if (r.hasError() || r.getValue() == null || "0x".equals(r.getValue())) {
            throw new IllegalStateException("Không đọc được " + f.getName() + " của Safe " + safe
                    + (r.hasError() ? ": " + r.getError().getMessage() : ""));
        }
        return FunctionReturnDecoder.decode(r.getValue(), f.getOutputParameters());
    }
}
