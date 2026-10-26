package vn.werwa.platform.events;

/**
 * {@code traceparent} (W3C) của việc đang chạy, để nối vết qua các mô-đun: sự kiện phát ra trong lúc xử lý một sự kiện
 * hoặc một request mang tiếp {@code traceparent} của nó.
 */
public final class TraceContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TraceContext() {}

    public static String current() {
        return CURRENT.get();
    }

    /** Đặt {@code traceparent} cho đoạn mã {@code body}, trả lại giá trị cũ sau khi chạy. */
    public static void with(String traceparent, Runnable body) {
        String previous = CURRENT.get();
        CURRENT.set(traceparent);
        try {
            body.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
