package vn.werwa.onchain;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tham số của mô-đun Onchain.
 *
 * @param workersEnabled bật gửi lại, theo dõi receipt và indexer; tắt trong kiểm thử không có chuỗi
 * @param pollInterval chu kỳ indexer và theo dõi receipt (khối 2 giây, ADR-01)
 * @param logBatchBlocks số khối tối đa mỗi lần {@code eth_getLogs}
 * @param resendAfter gửi lại raw tx nếu sau khoảng này chưa thấy trên chuỗi
 * @param gasMarginPercent cộng thêm vào ước lượng gas
 * @param rpcTimeout thời gian chờ mỗi lời gọi JSON-RPC
 */
@ConfigurationProperties(prefix = "werwa.onchain")
public record OnchainProperties(
        @DefaultValue("true") boolean workersEnabled,
        @DefaultValue("PT1S") Duration pollInterval,
        @DefaultValue("1000") int logBatchBlocks,
        @DefaultValue("PT20S") Duration resendAfter,
        @DefaultValue("20") int gasMarginPercent,
        @DefaultValue("PT5S") Duration rpcTimeout) {}
