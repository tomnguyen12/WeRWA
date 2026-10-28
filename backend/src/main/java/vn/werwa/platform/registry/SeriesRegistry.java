package vn.werwa.platform.registry;

import java.util.Optional;
import java.util.UUID;

/** Trạng thái series cho Quản trị (B10a; Q66). Phát hành cài đặt; Quản trị không phụ thuộc ngược vào Phát hành. */
public interface SeriesRegistry {

    /** Trạng thái series; rỗng nếu không có. */
    Optional<String> seriesStatus(UUID seriesId);
}
