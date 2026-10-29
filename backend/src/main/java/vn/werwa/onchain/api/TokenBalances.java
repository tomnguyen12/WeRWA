package vn.werwa.onchain.api;

/** Đọc số dư token qua O1 tại một số khối, cho đối soát bảng đọc với chuỗi (R5; Q89). */
public interface TokenBalances {

    /** {@code balanceOf(wallet)} của token tại khối {@code block}. */
    long balanceOf(String token, String wallet, long block);
}
