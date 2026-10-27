package vn.werwa.onboarding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

/**
 * Tham số của mô-đun Onboarding.
 *
 * @param kycValidity hạn hiệu lực của kết luận KYC (US-1.02-AC3); chuyển vào bảng tham số ở B12
 * @param seedOrganizations tệp tổ chức thử nạp lúc khởi động (devnet, kiểm thử: {@code classpath:seed/orgs.json}, I6);
 *     để trống ở môi trường thật
 */
@ConfigurationProperties(prefix = "werwa.onboarding")
public record OnboardingProperties(@DefaultValue("P365D") Duration kycValidity, Resource seedOrganizations) {}
