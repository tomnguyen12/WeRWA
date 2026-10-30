package vn.werwa.governance.enforcement;

import java.math.BigInteger;
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
import org.web3j.utils.Numeric;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.api.OperationalAlerts;
import vn.werwa.governance.api.SigningProposals;
import vn.werwa.governance.approval.GovernanceError;
import vn.werwa.onchain.api.ContractCalls;
import vn.werwa.onchain.api.contracts.IdentityRegistry;
import vn.werwa.onchain.api.contracts.SeriesController;
import vn.werwa.onchain.api.contracts.Token;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.registry.SeriesRegistry;
import vn.werwa.platform.web.ApiException;

/**
 * VSDC và Holco ký 2/2 lệnh phong tỏa, giải tỏa, chuyển cưỡng chế (US-4.02-AC2; máy Phong tỏa cạnh 4–6; Q148–Q152).
 *
 * <p>Lệnh đã duyệt 4 mắt được kiểm trên chuỗi qua O1; đạt thì đề nghị Safe {@code enforcement2of2} gọi hàm tương ứng
 * của controller với {@code orderHash}; không đạt thì lệnh FAILED (làm lại bằng lệnh mới). Indexer đọc sự kiện có
 * {@code orderHash} thì lệnh APPLIED.
 */
public class EnforcementSigning {

    public static final String APPLIED_EVENT = "werwa.governance.enforcement.applied.v1";
    static final String SAFE = "enforcement2of2";
    static final Set<String> ENFORCEABLE = Set.of("ACTIVE", "DEFAULTED", "MATURED");
    private static final Set<String> OPEN = Set.of("QUEUED", "COLLECTING", "THRESHOLD_REACHED", "SENT");
    private static final Map<String, String> EVENT_ACTION = Map.of("PositionFrozen", "FREEZE", "PositionUnfrozen",
            "UNFREEZE", "ForcedTransfer", "FORCED_TRANSFER");

    private final JdbcTemplate jdbc;
    private final SeriesRegistry series;
    private final ContractCalls calls;
    private final SigningProposals proposals;
    private final OperationalAlerts alerts;
    private final Events events;
    private final AuditLog audit;
    private final Clock clock;

    public EnforcementSigning(JdbcTemplate jdbc, SeriesRegistry series, ContractCalls calls, SigningProposals proposals,
            OperationalAlerts alerts, Events events, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.series = series;
        this.calls = calls;
        this.proposals = proposals;
        this.alerts = alerts;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    /** Lệnh gắn với một yêu cầu duyệt. */
    public Optional<UUID> orderOfApproval(UUID approvalId) {
        return jdbc.queryForList("SELECT id FROM governance.enforcement_order WHERE approval_request_id = ?", UUID.class,
                approvalId).stream().findFirst();
    }

    /**
     * Q149, Q150: kiểm trên chuỗi rồi tạo đề nghị ký. {@code manual} = Holco tạo lại qua API: lệnh không chờ ký hoặc còn
     * đề nghị mở thì ném lỗi. Trả rỗng khi lệnh vừa sang FAILED hoặc không có gì để làm.
     */
    @Transactional
    public Optional<SigningProposals.Ref> propose(UUID orderId, boolean manual) {
        Map<String, Object> o = jdbc.queryForList("""
                SELECT status, action, series_id, wallet_address, to_wallet_address, quantity, basis_hash, signing_proposal_id
                FROM governance.enforcement_order WHERE id = ? FOR UPDATE
                """, orderId).stream().findFirst()
                .orElseThrow(() -> new ApiException(GovernanceError.ENFORCEMENT_ORDER_NOT_FOUND));
        if (!"PENDING_SIGNATURES".equals(o.get("status"))) {
            if (manual) {
                throw new ApiException(GovernanceError.ENFORCEMENT_NOT_SIGNABLE, String.valueOf(o.get("status")));
            }
            return Optional.empty();
        }
        UUID previous = (UUID) o.get("signing_proposal_id");
        if (previous != null && proposals.status(previous).filter(OPEN::contains).isPresent()) {
            if (manual) {
                throw new ApiException(GovernanceError.ENFORCEMENT_SIGNING_PENDING, previous.toString());
            }
            return Optional.empty();
        }
        UUID seriesId = (UUID) o.get("series_id");
        String action = (String) o.get("action");
        String wallet = ((String) o.get("wallet_address")).toLowerCase(Locale.ROOT);
        String to = o.get("to_wallet_address") == null ? null
                : ((String) o.get("to_wallet_address")).toLowerCase(Locale.ROOT);
        long qty = ((Number) o.get("quantity")).longValue();
        byte[] orderHash = Numeric.hexStringToByteArray((String) o.get("basis_hash"));

        String controller = series.controllerAddress(seriesId).orElse(null);
        String status = series.seriesStatus(seriesId).orElse("");
        if (controller == null || !ENFORCEABLE.contains(status)) {
            return fail(orderId, "SERIES_NOT_ENFORCEABLE", "series đang " + status);
        }
        SeriesController c = calls.load(SeriesController.class, controller);
        try {
            Token token = calls.load(Token.class, c.token().send());
            long balance = token.balanceOf(wallet).send().longValueExact();
            long frozen = token.getFrozenTokens(wallet).send().longValueExact();
            switch (action) {
                case "FREEZE" -> {
                    if (qty > balance - frozen) {
                        return fail(orderId, "EXCEEDS_BALANCE", "số dư " + balance + ", đã phong tỏa " + frozen);
                    }
                }
                case "UNFREEZE" -> {
                    if (qty > frozen) {
                        return fail(orderId, "EXCEEDS_FROZEN", "đã phong tỏa " + frozen);
                    }
                }
                case "FORCED_TRANSFER" -> {
                    if (qty > balance) {
                        return fail(orderId, "EXCEEDS_BALANCE", "số dư " + balance);
                    }
                    String registry = token.identityRegistry().send();
                    if (!Boolean.TRUE.equals(calls.load(IdentityRegistry.class, registry).isVerified(to).send())) {
                        return fail(orderId, "RECIPIENT_NOT_VERIFIED", "ví nhận " + to);
                    }
                }
                default -> throw new IllegalStateException("hành động lạ " + action);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("không đọc được token qua O1: " + e.getMessage(), e);
        }
        String calldata = switch (action) {
            case "FREEZE" -> c.freeze(wallet, BigInteger.valueOf(qty), orderHash).encodeFunctionCall();
            case "UNFREEZE" -> c.unfreeze(wallet, BigInteger.valueOf(qty), orderHash).encodeFunctionCall();
            default -> c.forcedTransfer(wallet, to, BigInteger.valueOf(qty), orderHash).encodeFunctionCall();
        };
        String function = switch (action) {
            case "FREEZE" -> "freeze";
            case "UNFREEZE" -> "unfreeze";
            default -> "forcedTransfer";
        };
        SigningProposals.Ref ref = proposals.create(new SigningProposals.Request("ENFORCEMENT_" + action,
                "enforcement-order/" + orderId, null, SAFE, controller, function, calldata, (String) o.get("basis_hash")));
        jdbc.update("UPDATE governance.enforcement_order SET signing_proposal_id = ?, updated_at = ? WHERE id = ?", ref.id(),
                Timestamp.from(clock.instant()), orderId);
        audit.record("ENFORCEMENT_SIGNING_PROPOSED", "enforcement-order/" + orderId,
                Map.of("signingProposalId", ref.id().toString(), "action", action));
        return Optional.of(ref);
    }

    private Optional<SigningProposals.Ref> fail(UUID orderId, String reason, String detail) {
        jdbc.update("""
                UPDATE governance.enforcement_order SET status = 'FAILED', failure_reason = ?, updated_at = ? WHERE id = ?
                """, reason, Timestamp.from(clock.instant()), orderId);
        alerts.raise("enforcement-not-applicable:" + orderId, "EnforcementNotApplicable", "warning",
                "Lệnh phong tỏa " + orderId + " không áp dụng được (" + reason + "): " + detail);
        audit.record("ENFORCEMENT_ORDER_FAILED", "enforcement-order/" + orderId, Map.of("reason", reason, "detail", detail));
        return Optional.empty();
    }

    /**
     * Q152: {@code PositionFrozen(wallet, quantity, orderHash)}, {@code PositionUnfrozen(...)},
     * {@code ForcedTransfer(from, to, quantity, orderHash)} của controller.
     */
    @Transactional
    public boolean onIndexed(String eventName, String controller, Map<String, Object> args, String txHash) {
        String action = EVENT_ACTION.get(eventName);
        if (action == null) {
            return false;
        }
        String hash = String.valueOf(args.get("orderHash")).toLowerCase(Locale.ROOT);
        String wallet = String.valueOf(args.getOrDefault("wallet", args.get("from"))).toLowerCase(Locale.ROOT);
        String to = args.get("to") == null ? null : String.valueOf(args.get("to")).toLowerCase(Locale.ROOT);
        long qty = Long.parseLong(String.valueOf(args.get("quantity")));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, status, series_id, to_wallet_address FROM governance.enforcement_order
                WHERE basis_hash = ? AND action = ? AND lower(wallet_address) = ? AND quantity = ? FOR UPDATE
                """, hash, action, wallet, qty);
        if (rows.isEmpty() || (to != null && !to.equalsIgnoreCase(String.valueOf(rows.getFirst().get("to_wallet_address"))))) {
            alerts.raise("enforcement-unmatched:" + controller + ":" + hash, "EnforcementUnmatched", "critical",
                    "Controller " + controller + " ghi " + eventName + " (tx " + txHash + ", orderHash " + hash
                            + ") không khớp lệnh nào chờ ký");
            return true;
        }
        Map<String, Object> r = rows.getFirst();
        if ("APPLIED".equals(r.get("status"))) {
            return true;
        }
        UUID id = (UUID) r.get("id");
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                UPDATE governance.enforcement_order SET status = 'APPLIED', applied_tx_hash = ?, applied_at = ?, updated_at = ?
                WHERE id = ?
                """, txHash.toLowerCase(Locale.ROOT), now, now, id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", id.toString());
        data.put("action", action);
        data.put("seriesId", r.get("series_id").toString());
        data.put("wallet", wallet);
        if (to != null) {
            data.put("toWallet", to);
        }
        data.put("quantity", qty);
        data.put("txHash", txHash);
        events.publish(APPLIED_EVENT, "enforcement-order/" + id, data);
        audit.record("ENFORCEMENT_ORDER_APPLIED", "enforcement-order/" + id, data);
        return true;
    }
}
