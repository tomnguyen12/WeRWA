package vn.werwa.distribution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tham số Phân phối. B12 chuyển vào bảng tham số có duyệt.
 *
 * @param orderQuantityStep khối lượng lệnh phải là bội số của số này (Q42)
 * @param bookSchedulerEnabled bộ lập lịch tự mở, đóng sổ theo giờ (Q46); cũng bật việc quá hạn, gom lô (Q53, Q54)
 * @param paymentGraceBusinessDays ân hạn thanh toán, số ngày làm việc sau hạn trả (Q54)
 */
@ConfigurationProperties("werwa.distribution")
public record DistributionProperties(
        @DefaultValue("1") long orderQuantityStep, @DefaultValue("true") boolean bookSchedulerEnabled,
        @DefaultValue("0") int paymentGraceBusinessDays) {}
