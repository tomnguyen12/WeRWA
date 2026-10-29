package vn.werwa.governance.api;

/** Cảnh báo vận hành do chính ứng dụng phát (cùng bảng với cảnh báo từ Alertmanager, I5). */
public interface OperationalAlerts {

    /**
     * Tạo cảnh báo đang cháy; cùng {@code fingerprint} thì không tạo lại.
     *
     * @param severity {@code warning} hoặc {@code critical} (critical báo Holco vận hành, Q63)
     */
    void raise(String fingerprint, String name, String severity, String summary);
}
