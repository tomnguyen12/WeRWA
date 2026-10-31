package vn.werwa.issuance;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import vn.werwa.issuance.series.ScheduleGenerator;

/**
 * Tham số của mô-đun Phát hành. B12 chuyển vào bảng tham số có duyệt.
 *
 * @param requiredDocumentTypes loại tài liệu bắt buộc khi gửi series đi duyệt (Q31)
 * @param businessDayConvention quy tắc dời ngày trả rơi vào ngày nghỉ (US-2.05-AC2)
 */
@ConfigurationProperties("werwa.issuance")
public record IssuanceProperties(
        @DefaultValue({"TERM_SHEET", "OFFERING_CIRCULAR", "LEGAL_OPINION"}) List<String> requiredDocumentTypes,
        @DefaultValue("FOLLOWING") ScheduleGenerator.Convention businessDayConvention) {}
