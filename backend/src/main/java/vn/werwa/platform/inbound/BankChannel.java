package vn.werwa.platform.inbound;

/**
 * Kênh nhận webhook ngân hàng (US-5.02-AC2; Q49). mTLS kết thúc ở ingress; ứng dụng kiểm chữ ký thông điệp trên thân thô.
 * Mô-đun Tích hợp cài đặt; không có bean nào thì kênh đóng (403 {@code INBOUND_CHANNEL_NOT_ENABLED}).
 */
public interface BankChannel {

    /** Thuộc tính request chứa thân thô đã kiểm chữ ký, để lưu nguyên văn. */
    String RAW_BODY_ATTRIBUTE = BankChannel.class.getName() + ".rawBody";

    /** Chữ ký {@code X-Bank-Signature} có hợp lệ với thân thô không. */
    boolean verify(byte[] body, String signature);

    /** Ghi lại thông điệp bị từ chối vì sai chữ ký (US-5.02-AC2). */
    void rejected(String operationId, byte[] body, String signature);
}
