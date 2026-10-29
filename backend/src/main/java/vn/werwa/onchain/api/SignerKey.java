package vn.werwa.onchain.api;

/**
 * Khóa ký giao dịch mà ứng dụng được dùng (ADR-05, ADR-06). Chỉ khóa của Holco; khóa của UBND, VSDC, BIDV do chính bên đó
 * giữ và ký qua Safe (B4).
 */
public enum SignerKey {
    /** Khóa vận hành Holco: mint, neo nhật ký, hủy khi đáo hạn. Nằm trong HSM, ký qua WeHSM (Q14). */
    HOLCO_OPERATOR,
    /**
     * Tài khoản chuyển tiếp gửi giao dịch Safe đã đủ chữ ký (D8, Q111): không có quyền gì trên hợp đồng. Chưa cấu hình
     * {@code werwa.signer.relayer-address} thì dùng khóa vận hành.
     */
    RELAYER
}
