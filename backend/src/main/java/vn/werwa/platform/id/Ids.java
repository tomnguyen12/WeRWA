package vn.werwa.platform.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Khóa chính UUIDv7 (RFC 9562): 48 bit thời gian mili giây, rồi bit ngẫu nhiên. Khóa sinh sau có thứ tự lớn hơn khóa sinh
 * ở mili giây trước, nên chỉ mục B-tree không bị phân mảnh và phân trang theo con trỏ đi đúng thứ tự tạo.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    public static UUID newId() {
        return newId(Clock.systemUTC());
    }

    static UUID newId(Clock clock) {
        long millis = clock.millis();
        long msb = (millis & 0xFFFF_FFFF_FFFFL) << 16;
        msb |= 0x7000L; // phiên bản 7
        msb |= RANDOM.nextInt(1 << 12); // rand_a, 12 bit
        long lsb = RANDOM.nextLong();
        lsb = (lsb & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L; // biến thể RFC 9562
        return new UUID(msb, lsb);
    }

    /** Thời điểm (mili giây UTC) ghi trong một UUIDv7. */
    public static long timestampMillis(UUID id) {
        if (id.version() != 7) {
            throw new IllegalArgumentException("không phải UUIDv7: " + id);
        }
        return id.getMostSignificantBits() >>> 16;
    }
}
