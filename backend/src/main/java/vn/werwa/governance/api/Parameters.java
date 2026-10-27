package vn.werwa.governance.api;

import java.util.List;

/**
 * Tham số hệ thống có duyệt và ngày hiệu lực (US-4.11-AC2; Q60). Chưa có giá trị đã duyệt và tới ngày hiệu lực thì trả
 * giá trị mặc định do mô-đun đưa vào (thường lấy từ cấu hình).
 */
public interface Parameters {

    long integer(String key, long fallback);

    List<String> stringList(String key, List<String> fallback);

    String enumValue(String key, String fallback);
}
