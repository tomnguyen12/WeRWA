package vn.werwa.platform.inbound;

/**
 * Kênh nhận dùng token cố định thay cho OIDC (kiểu {@code alertToken} trong hợp đồng; Q56), ví dụ webhook Alertmanager.
 * Mô-đun sở hữu kênh cài đặt; không có bean nào thì kênh đóng (403 {@code INBOUND_CHANNEL_NOT_ENABLED}).
 */
public interface InboundToken {

    /** Token Bearer có hợp lệ cho thao tác này không; so sánh thời gian hằng. */
    boolean accepts(String operationId, String bearerToken);
}
