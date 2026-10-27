package vn.werwa.onchain;

import java.lang.reflect.Method;
import java.math.BigInteger;
import org.web3j.protocol.Web3j;
import org.web3j.tx.Contract;
import org.web3j.tx.ReadonlyTransactionManager;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.StaticGasProvider;
import vn.werwa.onchain.api.ContractCalls;

/** Nạp lớp hợp đồng sinh từ ABI, chỉ đọc qua O1; dùng để mã hóa calldata và gọi hàm view. */
class Web3jContractCalls implements ContractCalls {

    private static final String ZERO = "0x0000000000000000000000000000000000000000";

    private final ChainNodes nodes;
    private final ContractRegistry registry;

    Web3jContractCalls(ChainNodes nodes, ContractRegistry registry) {
        this.nodes = nodes;
        this.registry = registry;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Contract> T load(Class<T> type, String address) {
        try {
            Method load = type.getMethod("load", String.class, Web3j.class, TransactionManager.class,
                    org.web3j.tx.gas.ContractGasProvider.class);
            // Node chỉ đọc đầu tiên đang trả lời (O1-a rồi O1-b, ADR-12).
            Web3j web3j = nodes.read("eth_blockNumber", w -> {
                w.ethBlockNumber().send();
                return w;
            });
            return (T) load.invoke(null, address, web3j, new ReadonlyTransactionManager(web3j, ZERO),
                    new StaticGasProvider(BigInteger.ZERO, BigInteger.ZERO));
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("không nạp được lớp hợp đồng " + type.getName(), e);
        }
    }

    @Override
    public String address(String contractName) {
        return registry.addressOf(contractName)
                .orElseThrow(() -> new IllegalStateException("chưa có hợp đồng " + contractName + " trong registry"));
    }
}
