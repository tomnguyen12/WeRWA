package vn.werwa.onchain.api;

import java.util.UUID;

/**
 * Gửi giao dịch lên chuỗi (B3). Gọi trong giao dịch DB của mô-đun gọi: giao dịch chuỗi được ký và ghi {@code chain_tx}
 * cùng giao dịch đó, commit xong mới gửi. Kết quả báo qua {@code werwa.onchain.tx.mined.v1} hoặc
 * {@code werwa.onchain.tx.failed.v1}, sau khi indexer đã đọc khối của giao dịch.
 *
 * <p>Hợp đồng từ chối ngay ở bước chạy thử (Q16) thì ném {@link ChainCallRejected}, không ký, không gửi.
 */
public interface ChainTransactions {

    /** @return id của {@code chain_tx} (UUIDv7) */
    UUID submit(TxRequest request);
}
