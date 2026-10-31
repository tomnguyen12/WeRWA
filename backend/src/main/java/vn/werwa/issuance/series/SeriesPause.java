package vn.werwa.issuance.series;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.governance.api.Approvals;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.api.Notifications;
import vn.werwa.governance.api.SigningProposals;
import vn.werwa.issuance.IssuanceError;
import vn.werwa.onchain.api.ContractCalls;
import vn.werwa.onchain.api.contracts.SeriesController;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.hash.Hashes;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.security.Role;
import vn.werwa.platform.web.ApiException;

/**
 * Tạm dừng và mở lại series (hợp đồng {@code pause()}/{@code unpause()}; ADR-05; C6; Q171–Q175). Tạm dừng là việc khẩn
 * cấp: đề nghị Safe {@code enforcement2of2} (VSDC, Holco) ký ngay. Mở lại qua duyệt 4 mắt Holco rồi Safe
 * {@code governance3of3} (UBND, VSDC, Holco) ký. Trạng thái ngoài chuỗi đổi khi indexer đọc {@code Paused}/{@code Unpaused}.
 */
public class SeriesPause {

    public static final String UNPAUSE_ACTION = "SERIES_UNPAUSE";
    public static final String PAUSED_EVENT = "werwa.issuance.series.paused.v1";
    public static final String UNPAUSED_EVENT = "werwa.issuance.series.unpaused.v1";
    static final Set<String> PAUSABLE = Set.of("DEPLOYED", "READY", "OFFERING", "BOOK_CLOSED", "ALLOCATED", "ISSUING",
            "ACTIVE", "DEFAULTED", "MATURED");
    private static final Set<String> CLOSED_PROPOSAL = Set.of("FAILED", "CANCELLED");
    private static final List<Role> NOTIFY = List.of(Role.ISSUER, Role.REGISTRAR, Role.PAYING_AGENT, Role.OPERATOR,
            Role.SUPERVISOR, Role.SYNDICATE);

    private final JdbcTemplate jdbc;
    private final ContractCalls calls;
    private final SigningProposals proposals;
    private final Approvals approvals;
    private final Notifications notifications;
    private final Events events;
    private final AuditLog audit;
    private final Clock clock;

    public SeriesPause(JdbcTemplate jdbc, ContractCalls calls, SigningProposals proposals, Approvals approvals,
            Notifications notifications, Events events, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.calls = calls;
        this.proposals = proposals;
        this.approvals = approvals;
        this.notifications = notifications;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    /** Yêu cầu đang mở của series (cho {@code Series.pauseRequestId}). */
    public Optional<UUID> openRequest(UUID seriesId) {
        return jdbc.queryForList("""
                SELECT id FROM issuance.series_pause_request WHERE series_id = ? AND status IN ('PENDING_APPROVAL', 'SIGNING')
                """, UUID.class, seriesId).stream().findFirst();
    }

    private Map<String, Object> lockSeries(UUID seriesId) {
        return jdbc.queryForList("""
                SELECT status, paused, controller_address FROM issuance.series WHERE id = ? FOR UPDATE
                """, seriesId).stream().findFirst().orElseThrow(() -> new ApiException(IssuanceError.SERIES_NOT_FOUND));
    }

    /** Yêu cầu đang ký mà đề nghị đã lỗi hoặc bị hủy thì đóng thành FAILED để lập được yêu cầu mới. */
    private void closeStale(UUID seriesId) {
        jdbc.queryForList("""
                SELECT id, signing_proposal_id FROM issuance.series_pause_request
                WHERE series_id = ? AND status = 'SIGNING' AND signing_proposal_id IS NOT NULL
                """, seriesId).forEach(r -> {
            if (proposals.status((UUID) r.get("signing_proposal_id")).filter(CLOSED_PROPOSAL::contains).isPresent()) {
                jdbc.update("UPDATE issuance.series_pause_request SET status = 'FAILED', updated_at = ? WHERE id = ?",
                        Timestamp.from(clock.instant()), r.get("id"));
            }
        });
    }

    private UUID insert(UUID seriesId, String action, String reason, String status) {
        CurrentUser user = CurrentUser.get();
        Timestamp now = Timestamp.from(clock.instant());
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("action", action);
        basis.put("seriesId", seriesId.toString());
        basis.put("reason", reason);
        basis.put("requestedBy", user.subject());
        basis.put("requestedAt", now.toInstant().toString());
        UUID id = Ids.newId();
        jdbc.update("""
                INSERT INTO issuance.series_pause_request (id, series_id, action, reason, basis_hash, status, requested_by,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, seriesId, action, reason, Hashes.payloadHash(basis), status, user.subject(), now, now);
        return id;
    }

    /** Q172: tạm dừng khẩn cấp; đề nghị Safe 2/2 ký ngay. */
    @Transactional
    public SigningProposals.Ref requestPause(UUID seriesId, String reason) {
        Map<String, Object> s = lockSeries(seriesId);
        if (!PAUSABLE.contains(s.get("status")) || s.get("controller_address") == null) {
            throw new ApiException(IssuanceError.SERIES_NOT_PAUSABLE, String.valueOf(s.get("status")));
        }
        if (Boolean.TRUE.equals(s.get("paused"))) {
            throw new ApiException(IssuanceError.SERIES_ALREADY_PAUSED);
        }
        closeStale(seriesId);
        openRequest(seriesId).ifPresent(r -> {
            throw new ApiException(IssuanceError.PAUSE_REQUEST_PENDING, r.toString());
        });
        UUID id = insert(seriesId, "PAUSE", reason, "SIGNING");
        SigningProposals.Ref ref = propose(id, "enforcement2of2", "pause");
        audit.record("SERIES_PAUSE_REQUESTED", "series/" + seriesId, Map.of("requestId", id.toString(), "reason", reason));
        return ref;
    }

    /** Q173: mở lại; gửi duyệt 4 mắt Holco. */
    @Transactional
    public UUID requestUnpause(UUID seriesId, String reason) {
        Map<String, Object> s = lockSeries(seriesId);
        if (!Boolean.TRUE.equals(s.get("paused"))) {
            throw new ApiException(IssuanceError.SERIES_NOT_PAUSED);
        }
        closeStale(seriesId);
        openRequest(seriesId).ifPresent(r -> {
            throw new ApiException(IssuanceError.PAUSE_REQUEST_PENDING, r.toString());
        });
        UUID id = insert(seriesId, "UNPAUSE", reason, "PENDING_APPROVAL");
        UUID approval = approvals.request(UNPAUSE_ACTION, "series-pause-request/" + id,
                Map.of("seriesId", seriesId.toString(), "requestId", id.toString(), "reason", reason));
        jdbc.update("UPDATE issuance.series_pause_request SET approval_request_id = ? WHERE id = ?", approval, id);
        audit.record("SERIES_UNPAUSE_REQUESTED", "series/" + seriesId, Map.of("requestId", id.toString(), "reason", reason));
        return approval;
    }

    /** Hook duyệt: trả lại thì REJECTED; đạt thì để bộ nhận {@code approval.decided} tạo đề nghị ký. */
    public void applyDecision(UUID approvalId, boolean approve) {
        if (!approve) {
            jdbc.update("""
                    UPDATE issuance.series_pause_request SET status = 'REJECTED', updated_at = ?
                    WHERE approval_request_id = ? AND status = 'PENDING_APPROVAL'
                    """, Timestamp.from(clock.instant()), approvalId);
        }
    }

    /** Yêu cầu mở lại gắn với yêu cầu duyệt đã đạt. */
    public Optional<UUID> unpauseOfApproval(UUID approvalId) {
        return jdbc.queryForList("""
                SELECT id FROM issuance.series_pause_request WHERE approval_request_id = ? AND status = 'PENDING_APPROVAL'
                """, UUID.class, approvalId).stream().findFirst();
    }

    /** Duyệt đạt: đề nghị Safe 3/3 ký {@code unpause()}. */
    @Transactional
    public SigningProposals.Ref proposeUnpause(UUID requestId) {
        jdbc.update("UPDATE issuance.series_pause_request SET status = 'SIGNING', updated_at = ? WHERE id = ?",
                Timestamp.from(clock.instant()), requestId);
        return propose(requestId, "governance3of3", "unpause");
    }

    private SigningProposals.Ref propose(UUID requestId, String safe, String function) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT r.series_id, r.basis_hash, s.controller_address FROM issuance.series_pause_request r
                JOIN issuance.series s ON s.id = r.series_id WHERE r.id = ?
                """, requestId);
        String controller = ((String) r.get("controller_address")).toLowerCase(Locale.ROOT);
        SeriesController c = calls.load(SeriesController.class, controller);
        String data = "pause".equals(function) ? c.pause().encodeFunctionCall() : c.unpause().encodeFunctionCall();
        SigningProposals.Ref ref = proposals.create(new SigningProposals.Request("SERIES_" + function.toUpperCase(Locale.ROOT),
                "series-pause-request/" + requestId, null, safe, controller, function, data, (String) r.get("basis_hash")));
        jdbc.update("UPDATE issuance.series_pause_request SET signing_proposal_id = ?, updated_at = ? WHERE id = ?", ref.id(),
                Timestamp.from(clock.instant()), requestId);
        return ref;
    }

    /** Q174: {@code Paused(by)} hoặc {@code Unpaused(by)} của controller. */
    @Transactional
    public void onIndexed(UUID seriesId, String eventName, String txHash) {
        boolean paused = "Paused".equals(eventName);
        if (!paused && !"Unpaused".equals(eventName)) {
            return;
        }
        Timestamp now = Timestamp.from(clock.instant());
        int changed = jdbc.update("""
                UPDATE issuance.series SET paused = ?, paused_at = ?, updated_at = ? WHERE id = ? AND paused <> ?
                """, paused, paused ? now : null, now, seriesId, paused);
        List<Map<String, Object>> req = jdbc.queryForList("""
                SELECT id, reason FROM issuance.series_pause_request
                WHERE series_id = ? AND action = ? AND status = 'SIGNING' FOR UPDATE
                """, seriesId, paused ? "PAUSE" : "UNPAUSE");
        UUID requestId = req.isEmpty() ? null : (UUID) req.getFirst().get("id");
        if (requestId != null) {
            jdbc.update("""
                    UPDATE issuance.series_pause_request SET status = 'APPLIED', applied_tx_hash = ?, updated_at = ? WHERE id = ?
                    """, txHash.toLowerCase(Locale.ROOT), now, requestId);
        }
        if (changed == 0) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seriesId", seriesId.toString());
        if (requestId != null) {
            data.put("requestId", requestId.toString());
            data.put("reason", req.getFirst().get("reason"));
        } else {
            // Không do nền tảng lập (gọi thẳng Safe): vẫn phản ánh trạng thái chuỗi
            data.put("requestId", new UUID(0, 0).toString());
        }
        data.put("txHash", txHash);
        events.publish(paused ? PAUSED_EVENT : UNPAUSED_EVENT, "series/" + seriesId, data);
        audit.record(paused ? "SERIES_PAUSED" : "SERIES_UNPAUSED", "series/" + seriesId, data);
        notifications.notify(new Notifications.Notice(
                paused ? Notifications.Kind.SERIES_PAUSED : Notifications.Kind.SERIES_UNPAUSED,
                paused ? Notifications.Severity.CRITICAL : Notifications.Severity.INFO,
                (paused ? "Series tạm dừng: " : "Series mở lại: ") + seriesId,
                data.get("reason") == null ? null : String.valueOf(data.get("reason")), "series/" + seriesId, NOTIFY,
                null));
    }
}
