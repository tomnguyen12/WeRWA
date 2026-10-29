package vn.werwa.onchain.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Đọc sự kiện indexer đã ghi và thông tin khối trên O1, cho công cụ giám sát (US-4.04; Q92, Q93, Q95). Chỉ đọc. */
public interface IndexedEvents {

    long chainId();

    /**
     * Sự kiện mới nhất trước.
     *
     * @param afterBlock chỉ lấy khối lớn hơn; null là mọi khối
     * @param seriesId chỉ lấy hợp đồng của series này; null là mọi hợp đồng
     * @param beforePosition con trỏ trang: chỉ lấy vị trí nhỏ hơn; null là trang đầu
     */
    List<ChainEvent> list(Long afterBlock, UUID seriesId, Long beforePosition, int limit);

    /** Khối cuối indexer đã đọc xong. */
    long indexedBlock();

    /** Khối đầu chuỗi trên O1. */
    long headBlock();

    /** Mã băm và giờ của khối trên O1; rỗng nếu O1 chưa có khối này. */
    Optional<BlockHeader> block(long number);

    /** @param seriesId series của hợp đồng (Q43); null nếu là hợp đồng dùng chung */
    record ChainEvent(
            long block, int logIndex, String txHash, Instant blockTime, String contract, String contractKind,
            String eventName, UUID seriesId, Map<String, Object> args) {

        /** Thứ tự trong chuỗi: khối rồi log. */
        public long position() {
            return block * 1_000_000L + logIndex;
        }
    }

    record BlockHeader(long number, String hash, Instant time) {}
}
