package vn.werwa.governance.enforcement;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.governance.api.Approvals;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.approval.GovernanceError;
import vn.werwa.platform.documents.DocumentRegistry;
import vn.werwa.platform.hash.Hashes;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.registry.SeriesRegistry;
import vn.werwa.platform.registry.WalletRegistry;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.web.ApiException;
import vn.werwa.platform.web.Paging;

/**
 * Lệnh phong tỏa, giải tỏa, chuyển cưỡng chế (US-4.02-AC1, AC4; máy Phong tỏa cạnh 1–3; Q64–Q67). Lệnh áp cho số lượng
 * của một vị thế. Duyệt đạt thì chờ VSDC và Holco ký 2/2 ({@link EnforcementSigning}, B10b); trả lại thì REJECTED kèm lý do. Giải tỏa, chuyển cưỡng
 * chế là lệnh mới, đi lại đủ các bước.
 */
public class EnforcementService {

    public static final String APPROVE_ACTION = "ENFORCEMENT_ORDER";
    /** Series có thể đã có vị thế: đã triển khai, chưa hủy hoặc đóng (Q66). */
    static final Set<String> SERIES_OPEN = Set.of("DEPLOYED", "READY", "OFFERING", "BOOK_CLOSED", "ALLOCATED", "ISSUING",
            "ACTIVE", "DEFAULTED", "MATURED");

    private final JdbcTemplate jdbc;
    private final WalletRegistry wallets;
    private final SeriesRegistry series;
    private final DocumentRegistry documents;
    private final Approvals approvals;
    private final AuditLog audit;
    private final Clock clock;

    public EnforcementService(JdbcTemplate jdbc, WalletRegistry wallets, SeriesRegistry series, DocumentRegistry documents,
            Approvals approvals, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.wallets = wallets;
        this.series = series;
        this.documents = documents;
        this.approvals = approvals;
        this.audit = audit;
        this.clock = clock;
    }

    public record Input(String authorityName, String orderNo, LocalDate issuedOn, UUID basisDocumentId, String action,
            UUID seriesId, UUID walletId, UUID toWalletId, long quantity) {}

    public record Order(UUID id, String authorityName, String orderNo, LocalDate issuedOn, UUID basisDocumentId,
            String action, UUID seriesId, UUID walletId, UUID toWalletId, long quantity, String basisHash, String status,
            UUID approvalRequestId, String rejectReason, String failureReason, UUID signingProposalId,
            String appliedTxHash, java.time.Instant appliedAt) {}

    /** Máy Phong tỏa cạnh 1: tiếp nhận, tính mã băm căn cứ, gửi duyệt 4 mắt. */
    @Transactional
    public Order receive(Input in) {
        if (in.quantity() <= 0) {
            throw new ApiException(GovernanceError.ENFORCEMENT_INVALID, "quantity phải lớn hơn 0");
        }
        boolean transfer = "FORCED_TRANSFER".equals(in.action());
        if (transfer != (in.toWalletId() != null)) {
            throw new ApiException(GovernanceError.ENFORCEMENT_INVALID,
                    transfer ? "chuyển cưỡng chế cần toWalletId" : "toWalletId chỉ dùng cho FORCED_TRANSFER");
        }
        String seriesStatus = series.seriesStatus(in.seriesId())
                .orElseThrow(() -> new ApiException(GovernanceError.ENFORCEMENT_SERIES_INVALID, "không có series"));
        if (!SERIES_OPEN.contains(seriesStatus)) {
            throw new ApiException(GovernanceError.ENFORCEMENT_SERIES_INVALID, "series đang " + seriesStatus);
        }
        WalletRegistry.Wallet wallet = wallets.registeredWallet(in.walletId())
                .orElseThrow(() -> new ApiException(GovernanceError.ENFORCEMENT_WALLET_INVALID, "ví " + in.walletId()));
        WalletRegistry.Wallet to = null;
        if (transfer) {
            to = wallets.registeredWallet(in.toWalletId())
                    .orElseThrow(() -> new ApiException(GovernanceError.ENFORCEMENT_WALLET_INVALID, "ví nhận " + in.toWalletId()));
            if (to.id().equals(wallet.id())) {
                throw new ApiException(GovernanceError.ENFORCEMENT_INVALID, "ví nhận trùng ví bị chuyển");
            }
            if (!wallets.investorEligible(to.organizationId(), clock.instant())) {
                throw new ApiException(GovernanceError.ENFORCEMENT_WALLET_INVALID, "chủ ví nhận chưa có KYC đạt còn hạn");
            }
        }
        String docSha = documents.sha256(in.basisDocumentId())
                .orElseThrow(() -> new ApiException(GovernanceError.ENFORCEMENT_DOCUMENT_MISSING, in.basisDocumentId().toString()));
        String hash = orderHash(in, wallet.address(), to == null ? null : to.address(), docSha);

        UUID id = Ids.newId();
        CurrentUser user = CurrentUser.get();
        Timestamp now = Timestamp.from(clock.instant());
        try {
            jdbc.update("""
                    INSERT INTO governance.enforcement_order
                        (id, authority_name, order_no, issued_on, basis_document_id, basis_document_sha256, action, series_id,
                         wallet_id, wallet_address, to_wallet_id, to_wallet_address, quantity, basis_hash, status, owner_org_id,
                         created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED', ?, ?, ?, ?)
                    """, id, in.authorityName(), in.orderNo(), in.issuedOn(), in.basisDocumentId(), docSha, in.action(),
                    in.seriesId(), in.walletId(), wallet.address(), in.toWalletId(), to == null ? null : to.address(),
                    in.quantity(), hash, user.organizationId(), user.subject(), now, now);
        } catch (DuplicateKeyException e) {
            throw new ApiException(GovernanceError.ENFORCEMENT_ORDER_EXISTS, in.authorityName() + " " + in.orderNo());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enforcementOrderId", id.toString());
        payload.put("action", in.action());
        payload.put("orderNo", in.orderNo());
        payload.put("basisHash", hash);
        payload.put("quantity", Long.toString(in.quantity()));
        UUID approval = approvals.request(APPROVE_ACTION, "enforcement-order/" + id, payload);
        jdbc.update("""
                UPDATE governance.enforcement_order SET status = 'PENDING_APPROVAL', approval_request_id = ?, updated_at = ?
                WHERE id = ?
                """, approval, now, id);
        audit.record("ENFORCEMENT_ORDER_RECEIVED", "enforcement-order/" + id, payload);
        return get(id);
    }

    /** Máy Phong tỏa cạnh 2, 3 (Q28, Q67). */
    @Transactional
    public void applyDecision(UUID approvalId, boolean approve, String comment) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM governance.enforcement_order WHERE approval_request_id = ? AND status = 'PENDING_APPROVAL'
                FOR UPDATE
                """, UUID.class, approvalId);
        if (ids.isEmpty()) {
            return;
        }
        UUID id = ids.getFirst();
        Timestamp now = Timestamp.from(clock.instant());
        if (approve) {
            jdbc.update("UPDATE governance.enforcement_order SET status = 'PENDING_SIGNATURES', updated_at = ? WHERE id = ?",
                    now, id);
            audit.record("ENFORCEMENT_ORDER_APPROVED", "enforcement-order/" + id, Map.of("approvalId", approvalId.toString()));
        } else {
            jdbc.update("""
                    UPDATE governance.enforcement_order SET status = 'REJECTED', reject_reason = ?, updated_at = ? WHERE id = ?
                    """, comment, now, id);
            audit.record("ENFORCEMENT_ORDER_RETURNED", "enforcement-order/" + id,
                    Map.of("approvalId", approvalId.toString(), "reason", String.valueOf(comment)));
        }
    }

    public Order get(UUID id) {
        List<Order> rows = jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> row(rs), id);
        if (rows.isEmpty()) {
            throw new ApiException(GovernanceError.ENFORCEMENT_ORDER_NOT_FOUND);
        }
        return rows.getFirst();
    }

    /** Mới nhất trước. */
    public Paging.Page<Order> list(String cursor, Integer limit) {
        int size = Paging.limit(limit);
        UUID before = Paging.decodeCursor(cursor);
        List<Order> rows = before == null
                ? jdbc.query(SELECT + " ORDER BY id DESC LIMIT ?", (rs, i) -> row(rs), size + 1)
                : jdbc.query(SELECT + " WHERE id < ? ORDER BY id DESC LIMIT ?", (rs, i) -> row(rs), before, size + 1);
        return Paging.Page.of(rows, size, Order::id);
    }

    /** Q65: SHA-256 của JSON chuẩn hóa; là {@code orderHash} gửi lên hợp đồng ở B10b. */
    static String orderHash(Input in, String wallet, String toWallet, String basisDocumentSha256) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("authorityName", in.authorityName());
        m.put("orderNo", in.orderNo());
        m.put("issuedOn", in.issuedOn().toString());
        m.put("action", in.action());
        m.put("seriesId", in.seriesId().toString());
        m.put("wallet", wallet);
        m.put("toWallet", toWallet);
        m.put("quantity", Long.toString(in.quantity()));
        m.put("basisDocumentSha256", basisDocumentSha256);
        return Hashes.payloadHash(m);
    }

    private static final String SELECT = """
            SELECT id, authority_name, order_no, issued_on, basis_document_id, action, series_id, wallet_id, to_wallet_id,
                   quantity, basis_hash, status, approval_request_id, reject_reason, failure_reason, signing_proposal_id,
                   applied_tx_hash, applied_at
            FROM governance.enforcement_order""";

    private static Order row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Order(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class),
                rs.getObject(5, UUID.class), rs.getString(6), rs.getObject(7, UUID.class), rs.getObject(8, UUID.class),
                rs.getObject(9, UUID.class), rs.getLong(10), rs.getString(11), rs.getString(12), rs.getObject(13, UUID.class),
                rs.getString(14), rs.getString(15), rs.getObject(16, UUID.class), rs.getString(17),
                rs.getTimestamp(18) == null ? null : rs.getTimestamp(18).toInstant());
    }
}
