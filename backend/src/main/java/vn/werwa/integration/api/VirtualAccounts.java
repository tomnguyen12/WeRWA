package vn.werwa.integration.api;

import java.util.UUID;

/** Tài khoản ảo BIDV cho từng phân bổ (Q51). XD dùng giả lập; Open API thật của BIDV thay ở adapter. */
public interface VirtualAccounts {

    /** Mở (hoặc trả lại nếu đã mở) tài khoản ảo cho một phân bổ. Chạy lại an toàn. */
    String open(UUID allocationId, String currency);
}
