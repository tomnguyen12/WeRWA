package vn.werwa.platform.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Cấu hình riêng của WeRWA, prefix {@code werwa}. Tên biến môi trường theo I11 (relaxed binding của Spring), khớp tệp
 * env gộp do {@code infra/werwa.sh} sinh; phần ký qua WeHSM theo I13.
 *
 * <p>Khóa của {@code contracts} và {@code safes} là tên trong {@code devnet.json}/{@code safes.json}. Biến môi trường
 * làm mất chữ hoa và dấu gạch, nên tra bằng {@link #contract(String)} và {@link #safe(String)}, không đọc map trực tiếp.
 */
@Validated
@ConfigurationProperties(prefix = "werwa")
public record WerwaProperties(
        @Valid @NotNull Nats nats,
        @Valid @NotNull Oidc oidc,
        @Valid @NotNull Chain chain,
        Map<String, String> contracts,
        Map<String, String> safes,
        @Valid Signer signer,
        @Valid Monitoring monitoring) {

    public WerwaProperties {
        contracts = normalizeKeys(contracts);
        safes = normalizeKeys(safes);
    }

    /** Địa chỉ hợp đồng dùng chung theo tên, ví dụ {@code seriesFactory} hay {@code series-factory}. */
    public String contract(String name) {
        return lookup(contracts, name, "werwa.contracts");
    }

    /** Địa chỉ Safe theo tên, ví dụ {@code governance3of3}. */
    public String safe(String name) {
        return lookup(safes, name, "werwa.safes");
    }

    /** Chế độ pkcs11 ký giao dịch qua WeHSM (I12, I13), nên phải có {@code werwa.signer.*}. */
    @AssertTrue(message = "werwa.signer.url và werwa.signer.token bắt buộc khi werwa.chain.signing-mode=pkcs11")
    public boolean isSignerPresentForPkcs11() {
        return chain == null || chain.signingMode() != SigningMode.PKCS11 || (signer != null && signer.isComplete());
    }

    public record Nats(@NotNull URI url, String username, String password, @NotBlank String stream) {

        @Override
        public String toString() {
            return "Nats[url=" + url + ", username=" + username + ", stream=" + stream + "]";
        }
    }

    public record Oidc(@NotBlank String clientId) {}

    /**
     * Kết nối chuỗi. Đọc chỉ qua node chỉ đọc O1 (ADR-12); gửi giao dịch qua validator V1, V2 của Holco (ADR-02).
     * Danh sách theo thứ tự ưu tiên.
     */
    public record Chain(
            @Positive long id,
            @NotEmpty List<URI> readUrls,
            @NotEmpty List<URI> submitUrls,
            @PositiveOrZero long deployedAtBlock,
            @NotNull SigningMode signingMode) {

        /** Ứng dụng không bao giờ đọc từ validator: không địa chỉ nào được vừa đọc vừa gửi. */
        @AssertTrue(message = "werwa.chain.read-urls không được trùng werwa.chain.submit-urls: chỉ đọc qua O1 (ADR-12)")
        public boolean isReadSeparatedFromSubmit() {
            if (readUrls == null || submitUrls == null) {
                return true;
            }
            Set<URI> overlap = new HashSet<>(readUrls);
            overlap.retainAll(submitUrls);
            return overlap.isEmpty();
        }
    }

    /** Chế độ ký của nền tảng (I12): devnet dùng tài khoản thử; pkcs11 ký qua WeHSM. */
    public enum SigningMode {
        DEVNET,
        PKCS11
    }

    /** @param relayerAddress địa chỉ khóa {@code relayer} trong WeHSM (D8); trống thì gửi giao dịch Safe bằng khóa vận hành */
    public record Signer(URI url, String token, String relayerAddress) {

        boolean isComplete() {
            return url != null && token != null && !token.isBlank();
        }

        @Override
        public String toString() {
            return "Signer[url=" + url + ", token=" + (token == null ? null : "***") + ", relayerAddress=" + relayerAddress
                    + "]";
        }
    }

    public record Monitoring(URI alertmanagerUrl) {}

    private static Map<String, String> normalizeKeys(Map<String, String> source) {
        if (source == null) {
            return Map.of();
        }
        return source.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(e -> normalize(e.getKey()), Map.Entry::getValue));
    }

    private static String normalize(String key) {
        return key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static String lookup(Map<String, String> map, String name, String prefix) {
        String value = map.get(normalize(name));
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("thiếu cấu hình " + prefix + "." + name + "; có: " + map.keySet());
        }
        return value;
    }
}
