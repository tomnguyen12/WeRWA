package vn.werwa.platform.events;

/**
 * Bên nhận một loại sự kiện (D4, Q9). Mô-đun khai một bean cho mỗi loại sự kiện nó nhận; platform tạo consumer bền
 * {@code <consumer>__<loại>} trên stream {@code WERWA}.
 *
 * <p>{@link #handle} chạy trong giao dịch DB cùng với dòng inbox: lỗi thì rollback cả hai và JetStream gửi lại (Q10).
 * Phải xử lý lặp lại an toàn (CLAUDE.md), dù inbox đã chặn phần lớn trường hợp trùng.
 */
public interface EventHandler {

    /** Tên mô-đun nhận, ví dụ {@code governance}. */
    String consumer();

    /** Loại sự kiện, có trong {@code api/events/catalog.yaml}. */
    String eventType();

    void handle(InboundEvent event);
}
