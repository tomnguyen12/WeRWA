package vn.werwa.distribution.transfer;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.distribution.DistributionError;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.issuance.api.SeriesCatalog;
import vn.werwa.onboarding.api.Custodians;
import vn.werwa.onboarding.api.Investors;
import vn.werwa.onboarding.api.Wallets;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.security.Role;
import vn.werwa.platform.web.ApiException;
import vn.werwa.platform.web.Paging;
import vn.werwa.platform.web.PlatformError;

/**
 * Chuyển OTC không kèm thanh toán, phần ngoài chuỗi (US-3.08; Q77–Q82). Bên chuyển tạo yêu cầu và xác nhận luôn; bên
 * nhận xác nhận. Ví omnibus thuộc lưu ký: lưu ký đứng thay chủ sở hữu thực (Q78). Đủ xác nhận thì phát sự kiện cho B9b
 * gửi lên chuỗi; trạng thái giữ {@code PENDING_CONFIRMATIONS} tới khi giao dịch được gửi (Q82). Số dư và phần phong
 * tỏa kiểm ở B9b (Q79).
 */
public class TransferService {

    static final String CONFIRMED_EVENT = "werwa.distribution.transfer.confirmed.v1";
    private static final Set<Role> SEE_ALL = EnumSet.of(Role.OPERATOR, Role.REGISTRAR, Role.SUPERVISOR);
    private static final String PENDING = "PENDING_CONFIRMATIONS";

    private final JdbcTemplate jdbc;
    private final SeriesCatalog series;
    private final Wallets wallets;
    private final Investors investors;
    private final Custodians custodians;
    private final AuditLog audit;
    private final Events events;
    private final Clock clock;

    public TransferService(JdbcTemplate jdbc, SeriesCatalog series, Wallets wallets, Investors investors,
            Custodians custodians, AuditLog audit, Events events, Clock clock) {
        this.jdbc = jdbc;
        this.series = series;
        this.wallets = wallets;
        this.investors = investors;
        this.custodians = custodians;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    public record Transfer(
            UUID id, UUID seriesId, UUID fromWalletId, UUID toWalletId, UUID fromOrgId, UUID toOrgId,
            String fromWalletType, String toWalletType, long quantity, String status, Instant createdAt,
            Instant fromConfirmedAt, Instant toConfirmedAt, Instant custodianConfirmedAt, Instant cancelledAt,
            String rejectReason, String txHash) {

        boolean fullyConfirmed() {
            return fromConfirmedAt != null && toConfirmedAt != null;
        }
    }

    /** US-3.08-AC1 (phần ngoài chuỗi), Q78, Q79. */
    @Transactional
    public Transfer create(UUID seriesId, UUID fromWalletId, UUID toWalletId, long quantity) {
        if (quantity < 1) {
            throw new ApiException(DistributionError.TRANSFER_INVALID, "số lượng phải ≥ 1");
        }
        if (fromWalletId.equals(toWalletId)) {
            throw new ApiException(DistributionError.TRANSFER_SAME_WALLET);
        }
        Wallets.Wallet from = registered(fromWalletId);
        Wallets.Wallet to = registered(toWalletId);
        if (from.chainId() != to.chainId()) {
            throw new ApiException(DistributionError.TRANSFER_INVALID, "hai ví khác chuỗi");
        }
        // Q78: chỉ tổ chức sở hữu ví chuyển, đúng vai theo loại ví
        requireActsFor(from);
        SeriesCatalog.SeriesInfo info = series.find(seriesId)
                .orElseThrow(() -> new ApiException(DistributionError.SERIES_NOT_FOUND));
        if (!"ACTIVE".equals(info.status())) {
            throw new ApiException(DistributionError.TRANSFER_SERIES_NOT_ACTIVE, info.status());
        }
        requireRecipientEligible(info, to);
        UUID id = Ids.newId();
        Timestamp now = Timestamp.from(clock.instant());
        Timestamp custodian = "OMNIBUS".equals(from.walletType()) ? now : null;
        CurrentUser user = CurrentUser.get();
        jdbc.update("""
                INSERT INTO distribution.transfer_request
                    (id, series_id, from_wallet_id, to_wallet_id, from_org_id, to_org_id, from_wallet_type, to_wallet_type,
                     quantity, status, from_confirmed_at, custodian_confirmed_at, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, seriesId, fromWalletId, toWalletId, from.organizationId(), to.organizationId(), from.walletType(),
                to.walletType(), quantity, PENDING, now, custodian, user.subject(), now, now);
        audit.record("TRANSFER_CREATED", "transfer/" + id, Map.of("seriesId", seriesId.toString(), "fromWalletId",
                fromWalletId.toString(), "toWalletId", toWalletId.toString(), "quantity", quantity));
        return get(id);
    }

    /** Bên nhận xác nhận; đủ xác nhận thì phát sự kiện cho B9b (Q78, Q81, Q82). */
    @Transactional
    public Transfer confirm(UUID id) {
        Transfer t = lock(id);
        Wallets.Wallet to = registered(t.toWalletId());
        if (!to.organizationId().equals(t.toOrgId())) {
            throw new ApiException(DistributionError.WALLET_NOT_REGISTERED);
        }
        requireActsFor(to);
        if (!PENDING.equals(t.status())) {
            throw new ApiException(DistributionError.TRANSFER_NOT_PENDING, t.status());
        }
        if (t.toConfirmedAt() != null) {
            throw new ApiException(DistributionError.TRANSFER_ALREADY_CONFIRMED);
        }
        // KYC bên nhận có thể đã hết hạn kể từ lúc tạo (Q79)
        SeriesCatalog.SeriesInfo info = series.find(t.seriesId())
                .orElseThrow(() -> new ApiException(DistributionError.SERIES_NOT_FOUND));
        if (!"ACTIVE".equals(info.status())) {
            throw new ApiException(DistributionError.TRANSFER_SERIES_NOT_ACTIVE, info.status());
        }
        requireRecipientEligible(info, to);
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("UPDATE distribution.transfer_request SET to_confirmed_at = ?, updated_at = ? WHERE id = ?", now, now,
                id);
        audit.record("TRANSFER_CONFIRMED", "transfer/" + id, Map.of("by", "TO", "toWalletId", t.toWalletId().toString()));
        Transfer out = get(id);
        if (out.fullyConfirmed()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("transferId", id.toString());
            data.put("seriesId", out.seriesId().toString());
            data.put("fromWalletId", out.fromWalletId().toString());
            data.put("toWalletId", out.toWalletId().toString());
            data.put("quantity", out.quantity());
            events.publish(CONFIRMED_EVENT, "transfer/" + id, data);
        }
        return out;
    }

    /** Bên chuyển hủy khi bên nhận chưa xác nhận (Q81); sau đó yêu cầu đã chờ gửi lên chuỗi. */
    @Transactional
    public Transfer cancel(UUID id) {
        Transfer t = lock(id);
        CurrentUser user = CurrentUser.get();
        if (!user.organizationId().equals(t.fromOrgId()) || !user.roles().contains(roleFor(t.fromWalletType()))) {
            throw new ApiException(PlatformError.ORG_SCOPE_VIOLATION);
        }
        if (!PENDING.equals(t.status()) || t.fullyConfirmed()) {
            throw new ApiException(DistributionError.TRANSFER_NOT_PENDING, t.status());
        }
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                UPDATE distribution.transfer_request SET status = 'CANCELLED', cancelled_at = ?, updated_at = ? WHERE id = ?
                """, now, now, id);
        audit.record("TRANSFER_CANCELLED", "transfer/" + id, Map.of("seriesId", t.seriesId().toString()));
        return get(id);
    }

    /** Q81: nhà đầu tư, lưu ký chỉ thấy yêu cầu có ví của tổ chức mình. */
    public Transfer view(UUID id) {
        Transfer t = get(id);
        CurrentUser user = CurrentUser.get();
        if (!user.hasAnyRole(SEE_ALL) && !user.organizationId().equals(t.fromOrgId())
                && !user.organizationId().equals(t.toOrgId())) {
            // Không lộ sự tồn tại của yêu cầu giữa hai tổ chức khác
            throw new ApiException(DistributionError.TRANSFER_NOT_FOUND);
        }
        return t;
    }

    /** Mới nhất trước (Q81). */
    public Paging.Page<Transfer> list(UUID seriesId, String status, String cursor, Integer limit) {
        int size = Paging.limit(limit);
        UUID before = Paging.decodeCursor(cursor);
        CurrentUser user = CurrentUser.get();
        StringBuilder sql = new StringBuilder(SELECT + " WHERE true");
        List<Object> args = new ArrayList<>();
        if (!user.hasAnyRole(SEE_ALL)) {
            sql.append(" AND (from_org_id = ? OR to_org_id = ?)");
            args.add(user.organizationId());
            args.add(user.organizationId());
        }
        if (seriesId != null) {
            sql.append(" AND series_id = ?");
            args.add(seriesId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (before != null) {
            sql.append(" AND id < ?");
            args.add(before);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(size + 1);
        return Paging.Page.of(jdbc.query(sql.toString(), TransferService::row, args.toArray()), size, Transfer::id);
    }

    public Transfer get(UUID id) {
        List<Transfer> rows = jdbc.query(SELECT + " WHERE id = ?", TransferService::row, id);
        if (rows.isEmpty()) {
            throw new ApiException(DistributionError.TRANSFER_NOT_FOUND);
        }
        return rows.getFirst();
    }

    private Transfer lock(UUID id) {
        List<Transfer> rows = jdbc.query(SELECT + " WHERE id = ? FOR UPDATE", TransferService::row, id);
        if (rows.isEmpty()) {
            throw new ApiException(DistributionError.TRANSFER_NOT_FOUND);
        }
        Transfer t = rows.getFirst();
        CurrentUser user = CurrentUser.get();
        if (!user.organizationId().equals(t.fromOrgId()) && !user.organizationId().equals(t.toOrgId())) {
            throw new ApiException(DistributionError.TRANSFER_NOT_FOUND);
        }
        return t;
    }

    private Wallets.Wallet registered(UUID walletId) {
        return wallets.registered(walletId).orElseThrow(() -> new ApiException(DistributionError.WALLET_NOT_REGISTERED));
    }

    /** Q78: người dùng thuộc tổ chức sở hữu ví và có vai ứng với loại ví. */
    private static void requireActsFor(Wallets.Wallet wallet) {
        CurrentUser user = CurrentUser.get();
        if (!user.organizationId().equals(wallet.organizationId()) || !user.roles().contains(roleFor(wallet.walletType()))) {
            throw new ApiException(PlatformError.ORG_SCOPE_VIOLATION);
        }
    }

    private static Role roleFor(String walletType) {
        return "OMNIBUS".equals(walletType) ? Role.CUSTODIAN : Role.INVESTOR;
    }

    /** Q79: ví SELF thì loại nhà đầu tư nằm trong danh sách của series; ví omnibus thì lưu ký đã qua KYB. */
    private void requireRecipientEligible(SeriesCatalog.SeriesInfo info, Wallets.Wallet to) {
        if ("OMNIBUS".equals(to.walletType())) {
            if (!custodians.active(to.organizationId())) {
                throw new ApiException(DistributionError.TRANSFER_RECIPIENT_NOT_ELIGIBLE, "lưu ký chưa qua KYB");
            }
            return;
        }
        String investorClass = investors.eligibleClass(to.organizationId(), clock.instant())
                .orElseThrow(() -> new ApiException(DistributionError.TRANSFER_RECIPIENT_NOT_ELIGIBLE, "KYC không còn hạn"));
        if (!info.investorClasses().contains(investorClass)) {
            throw new ApiException(DistributionError.TRANSFER_RECIPIENT_NOT_ELIGIBLE, investorClass);
        }
    }

    private static final String SELECT = """
            SELECT id, series_id, from_wallet_id, to_wallet_id, from_org_id, to_org_id, from_wallet_type, to_wallet_type,
                   quantity, status, created_at, from_confirmed_at, to_confirmed_at, custodian_confirmed_at, cancelled_at,
                   reject_reason, tx_hash
            FROM distribution.transfer_request""";

    private static Transfer row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Transfer(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getString(7),
                rs.getString(8), rs.getLong(9), rs.getString(10), instant(rs, 11), instant(rs, 12), instant(rs, 13),
                instant(rs, 14), instant(rs, 15), rs.getString(16), rs.getString(17));
    }

    private static Instant instant(java.sql.ResultSet rs, int col) throws java.sql.SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }
}
