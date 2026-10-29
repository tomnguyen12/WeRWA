package vn.werwa.onboarding.api;

import java.util.Collection;
import java.util.List;

/** Danh mục loại nhà đầu tư (Q25). Đề án và series chọn tập con của danh mục này. */
public interface InvestorClasses {

    /** Các mã không có trong danh mục, giữ thứ tự đầu vào. */
    List<String> unknown(Collection<String> keys);

    /** Mã {@code uint16} trên chuỗi của từng loại (Q25), giữ thứ tự đầu vào; loại không có thì bỏ qua. */
    java.util.Map<String, Integer> codes(Collection<String> keys);
}
