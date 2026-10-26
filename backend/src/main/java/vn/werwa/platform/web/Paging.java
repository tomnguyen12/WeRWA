package vn.werwa.platform.web;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Phân trang theo con trỏ (đặc tả API, 05): {@code limit} 1–200, mặc định 50; {@code cursor} là khóa UUIDv7 của dòng cuối
 * trang trước, mã hóa base64url. Khóa UUIDv7 tăng theo thời gian tạo nên thứ tự trang ổn định.
 */
public final class Paging {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private Paging() {}

    public static int limit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1 || requested > MAX_LIMIT) {
            throw new ApiException(PlatformError.PAGE_LIMIT_INVALID, "limit=" + requested);
        }
        return requested;
    }

    public static String encodeCursor(UUID lastId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(lastId.toString().getBytes(StandardCharsets.US_ASCII));
    }

    /** {@code null} khi không có con trỏ (trang đầu). */
    public static UUID decodeCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException e) {
            throw new ApiException(PlatformError.CURSOR_INVALID);
        }
    }

    /**
     * Trang kết quả. Truy vấn lấy {@code limit + 1} dòng; có dòng thừa thì còn trang sau và con trỏ là khóa của dòng
     * cuối trong trang.
     */
    public record Page<T>(List<T> items, String nextCursor) {

        public static <T> Page<T> of(List<T> fetched, int limit, java.util.function.Function<T, UUID> idOf) {
            if (fetched.size() <= limit) {
                return new Page<>(List.copyOf(fetched), null);
            }
            List<T> items = List.copyOf(fetched.subList(0, limit));
            return new Page<>(items, encodeCursor(idOf.apply(items.get(limit - 1))));
        }
    }
}
