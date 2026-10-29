package vn.werwa.onboarding.api;

import java.util.Optional;
import java.util.UUID;

/** Ví của tổ chức (Q41). Đăng ký ví vào sổ định danh trên chuỗi làm ở B5b. */
public interface Wallets {

    /** Ví đã đăng ký ({@code REGISTERED}); rỗng nếu không có hoặc chưa đăng ký. */
    Optional<Wallet> registered(UUID walletId);

    /** @param walletType {@code SELF} hoặc {@code OMNIBUS} (ví omnibus thuộc lưu ký) */
    record Wallet(UUID id, UUID organizationId, long chainId, String address, String walletType) {}
}
