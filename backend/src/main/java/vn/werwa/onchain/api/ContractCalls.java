package vn.werwa.onchain.api;

import org.web3j.tx.Contract;

/**
 * Mã hóa calldata bằng lớp hợp đồng sinh từ ABI, và tra địa chỉ hợp đồng.
 *
 * <pre>{@code
 * String data = calls.load(AuditAnchor.class, calls.address("auditAnchor"))
 *         .anchor(BigInteger.valueOf(20270120), BigInteger.valueOf(seq), hash)
 *         .encodeFunctionCall();
 * chain.submit(new TxRequest("AUDIT_ANCHOR", "audit-anchor/2027-01-20", SignerKey.HOLCO_OPERATOR, address, data));
 * }</pre>
 *
 * Lớp nạp ra chỉ đọc qua O1 (gọi hàm {@code view}); không gửi giao dịch được.
 */
public interface ContractCalls {

    <T extends Contract> T load(Class<T> type, String address);

    /** Địa chỉ hợp đồng dùng chung theo tên trong {@code devnet.json}, ví dụ {@code auditAnchor}. */
    String address(String contractName);
}
