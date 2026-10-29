package vn.werwa.governance.supervision;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import vn.werwa.governance.api.Parameters;
import vn.werwa.platform.security.SupervisorWritePolicy;

/**
 * Lựa chọn thao tác ghi cho Cơ quan giám sát (US-4.04-AC2; Q97), đặt bằng tham số hệ thống
 * {@value #KEY}: đổi qua duyệt 4 mắt, có nhật ký, có ngày hiệu lực. Chưa đặt thì {@code READ_ONLY}.
 */
public class SupervisorWrites implements SupervisorWritePolicy {

    public static final String KEY = "governance.supervisor-write-policy";
    public static final String DEFAULT = "READ_ONLY";

    /** Giá trị của tham số sang các operationId được miễn chặn. */
    public static final Map<String, Set<String>> OPTIONS;

    static {
        Map<String, Set<String>> o = new LinkedHashMap<>();
        // Chặn mọi thao tác ghi, đúng nguyên văn AC2
        o.put("READ_ONLY", Set.of());
        // Cho đánh dấu đã đọc thông báo của chính mình (trạng thái hiển thị, không phải dữ liệu nghiệp vụ)
        o.put("ALLOW_NOTIFICATION_READ", Set.of("markNotificationRead"));
        // Thêm tải tài liệu lên kho (ví dụ văn bản yêu cầu giải trình); tài liệu không đổi được dữ liệu nghiệp vụ
        o.put("ALLOW_NOTIFICATION_READ_AND_DOCUMENTS", Set.of("markNotificationRead", "uploadDocument"));
        OPTIONS = java.util.Collections.unmodifiableMap(o);
    }

    private final Parameters parameters;

    public SupervisorWrites(Parameters parameters) {
        this.parameters = parameters;
    }

    @Override
    public Set<String> allowedOperations() {
        return OPTIONS.getOrDefault(parameters.enumValue(KEY, DEFAULT), Set.of());
    }
}
