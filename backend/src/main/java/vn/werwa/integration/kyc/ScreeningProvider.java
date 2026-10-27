package vn.werwa.integration.kyc;

import java.util.Map;
import vn.werwa.integration.api.Screening.Subject;

/** Adapter của một nhà cung cấp KYC (US-5.04-AC1): chỉ gọi nhà cung cấp và trả kết quả nguyên văn. */
public interface ScreeningProvider {

    String name();

    /** Kết quả nguyên văn; khóa {@code sanctionsHit}, {@code pepHit}, {@code reference} bắt buộc có. */
    Map<String, Object> screen(Subject subject);
}
