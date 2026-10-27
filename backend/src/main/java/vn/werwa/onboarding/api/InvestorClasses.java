package vn.werwa.onboarding.api;

import java.util.Collection;
import java.util.List;

/** Danh mục loại nhà đầu tư (Q25). Đề án và series chọn tập con của danh mục này. */
public interface InvestorClasses {

    /** Các mã không có trong danh mục, giữ thứ tự đầu vào. */
    List<String> unknown(Collection<String> keys);
}
