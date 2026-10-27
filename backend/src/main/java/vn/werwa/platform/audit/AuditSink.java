package vn.werwa.platform.audit;

/**
 * Nơi nhận dòng nhật ký của lệnh ghi API (Q22). Platform chỉ khai interface; mô-đun Quản trị cài, nên platform không phụ
 * thuộc mô-đun phân hệ nào.
 */
public interface AuditSink {

    void recordWrite(WriteAudit write);
}
