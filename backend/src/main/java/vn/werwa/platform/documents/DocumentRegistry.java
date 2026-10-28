package vn.werwa.platform.documents;

import java.util.Collection;
import java.util.UUID;

/**
 * Kiểm mã tài liệu đã tải lên (US-2.04; Q26). Mô-đun Phát hành giữ kho tài liệu và cài interface này; mô-đun khác (ví dụ
 * Onboarding với hồ sơ KYB, văn bản ủy quyền) kiểm qua đây, nên không phụ thuộc trực tiếp vào mô-đun Phát hành.
 */
public interface DocumentRegistry {

    /** Mã nào trong danh sách chưa có trong kho; rỗng nếu đủ cả. */
    Collection<UUID> missing(Collection<UUID> documentIds);

    /** SHA-256 (hex, không tiền tố) của tài liệu; rỗng nếu không có (Q65). */
    default java.util.Optional<String> sha256(UUID documentId) {
        return java.util.Optional.empty();
    }
}
