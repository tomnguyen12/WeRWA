package vn.werwa.onchain.api;

/** Đọc số dư token qua O1 tại một số khối, cho đối soát bảng đọc với chuỗi (R4, R5; Q89, Q138). */
public interface TokenBalances {

    /** {@code balanceOf(wallet)} của token tại khối {@code block}. */
    long balanceOf(String token, String wallet, long block);

    /** {@code totalSupply()} của token tại khối {@code block} (R4; Q138). */
    long totalSupply(String token, long block);

    /** {@code getFrozenTokens(wallet)} của token tại khối {@code block} (danh sách chốt; Q156). */
    long frozenOf(String token, String wallet, long block);
}
