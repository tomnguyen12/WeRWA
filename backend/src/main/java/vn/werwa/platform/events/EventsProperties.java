package vn.werwa.platform.events;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tham số sự kiện (B2; Q9–Q11). Giá trị mặc định là giá trị đã duyệt; kiểm thử rút ngắn thời gian chờ.
 *
 * @param publishTimeout thời gian chờ JetStream xác nhận một lần phát
 * @param resubmitInterval chu kỳ phát lại sự kiện còn nằm trong outbox
 * @param resubmitOlderThan chỉ phát lại sự kiện đã nằm trong outbox lâu hơn mức này
 * @param retryDelays khoảng chờ trước mỗi lần thử lại khi xử lý lỗi (Q10); hết danh sách thì vào thư chết
 * @param ackWait JetStream gửi lại nếu không ack trong thời gian này
 * @param fetchBatch số sự kiện lấy mỗi lần của một consumer
 * @param archiveRetention giữ sự kiện đã phát trong bảng lưu trữ (Q11), khớp thời gian stream giữ (I2)
 * @param inboxRetention giữ dấu đã xử lý trong inbox, dài hơn thời gian stream giữ để không xử lý lại
 */
@ConfigurationProperties(prefix = "werwa.events")
public record EventsProperties(
        @DefaultValue("PT2S") Duration publishTimeout,
        @DefaultValue("PT30S") Duration resubmitInterval,
        @DefaultValue("PT1M") Duration resubmitOlderThan,
        @DefaultValue({"PT1S", "PT5S", "PT30S", "PT2M", "PT10M"}) List<Duration> retryDelays,
        @DefaultValue("PT30S") Duration ackWait,
        @DefaultValue("10") int fetchBatch,
        @DefaultValue("P30D") Duration archiveRetention,
        @DefaultValue("P35D") Duration inboxRetention) {

    /** Số lần JetStream giao tối đa: lần đầu cộng số lần thử lại. */
    public int maxDeliver() {
        return retryDelays.size() + 1;
    }
}
