package vn.werwa.platform.registry;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Ví và điều kiện nhà đầu tư cho các mô-đun mà Onboarding đã phụ thuộc (Quản trị: lệnh phong tỏa, B10a; Q66). Onboarding
 * cài đặt; nhờ đó Quản trị không phụ thuộc ngược vào Onboarding.
 */
public interface WalletRegistry {

    /** Ví đã đăng ký: tổ chức sở hữu và địa chỉ; rỗng nếu không có hoặc chưa đăng ký. */
    Optional<Wallet> registeredWallet(UUID walletId);

    /** Tổ chức có KYC nhà đầu tư đạt còn hạn tại {@code at}. */
    boolean investorEligible(UUID organizationId, Instant at);

    record Wallet(UUID id, UUID organizationId, String address) {}
}
