package vn.werwa.governance.api;

/** Kiểm chuỗi nhật ký (US-4.08-AC2) và khớp với mã băm đã neo (AC3). Đối soát hằng ngày (B11) gọi lại. */
public interface AuditVerification {

    Result verify();

    /**
     * @param intact chuỗi liền mạch và khớp mọi neo đã lên chuỗi
     * @param entries số dòng đã kiểm
     * @param brokenAtSeq dòng đầu tiên lệch; null nếu liền mạch
     * @param reason lý do lệch
     */
    record Result(boolean intact, long entries, Long brokenAtSeq, String reason) {}
}
