package vn.werwa.platform.audit;

import java.util.UUID;

/**
 * Một lệnh ghi API thành công (Q22). {@code requestHash} là SHA-256 của phương thức, đường dẫn và thân request, đã tính
 * ở bước Idempotency-Key; nhật ký chỉ giữ mã băm, không giữ nội dung.
 */
public record WriteAudit(
        String subject, UUID organizationId, String operationId, String method, String path, String requestHash, int status) {}
