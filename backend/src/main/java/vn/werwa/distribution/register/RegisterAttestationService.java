package vn.werwa.distribution.register;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.distribution.DistributionError;
import vn.werwa.distribution.allocation.AllocationMerkle;
import vn.werwa.governance.api.Approvals;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.api.BusinessCalendar;
import vn.werwa.governance.api.Notifications;
import vn.werwa.governance.api.OperationalAlerts;
import vn.werwa.governance.api.SigningProposals;
import vn.werwa.issuance.api.SeriesCatalog;
import vn.werwa.onchain.api.ChainCallRejected;
import vn.werwa.onchain.api.ChainTransactions;
import vn.werwa.onchain.api.ContractCalls;
import vn.werwa.onchain.api.SignerKey;
import vn.werwa.onchain.api.TokenBalances;
import vn.werwa.onchain.api.TxRequest;
import vn.werwa.onchain.api.contracts.SeriesController;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.security.Role;
import vn.werwa.platform.web.ApiException;

/**
 * VSDC xác nhận sổ sau phát hành và sửa mục VSDC chỉ ra (US-3.05-AC5; máy Series cạnh 10, 11; máy Phân bổ cạnh 6, 8, 9;
 * Q139–Q146).
 *
 * <p>Khi mọi mục có tiền đã mint, lập sổ từ phân bổ MINTED gộp theo ví, kiểm với {@code balanceOf} trên chuỗi, rồi đề
 * nghị Safe VSDC ký {@code attestRegister(registerHash, atBlock)}. Indexer đọc {@code RegisterAttested} thì mục
 * CONFIRMED. VSDC từ chối thì mục được chỉ ra sang NEEDS_CORRECTION; Holco hủy ({@code correctionBurn}) hoặc bác chỉ ra
 * qua duyệt 4 mắt, rồi sổ được lập lại.
 */
public class RegisterAttestationService {

    public static final String PURPOSE = "REGISTER_ATTEST";
    public static final String CORRECTION_ACTION = "ALLOCATION_CORRECTION";
    public static final String CORRECTION_PURPOSE = "CORRECTION_BURN";
    public static final String ATTESTED_EVENT = "werwa.distribution.register.attested.v1";
    static final String BURNED = "CORRECTION_BURNED";
    private static final Set<String> CLOSED_PROPOSAL = Set.of("FAILED", "CANCELLED");
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final SeriesCatalog series;
    private final SigningProposals proposals;
    private final Approvals approvals;
    private final ChainTransactions chain;
    private final ContractCalls calls;
    private final TokenBalances balances;
    private final OperationalAlerts alerts;
    private final Notifications notifications;
    private final BusinessCalendar calendar;
    private final vn.werwa.governance.api.Parameters parameters;
    private final Events events;
    private final AuditLog audit;
    private final Clock clock;

    public RegisterAttestationService(JdbcTemplate jdbc, SeriesCatalog series, SigningProposals proposals,
            Approvals approvals, ChainTransactions chain, ContractCalls calls, TokenBalances balances,
            OperationalAlerts alerts, Notifications notifications, BusinessCalendar calendar,
            vn.werwa.governance.api.Parameters parameters, Events events, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.series = series;
        this.proposals = proposals;
        this.approvals = approvals;
        this.chain = chain;
        this.calls = calls;
        this.balances = balances;
        this.alerts = alerts;
        this.notifications = notifications;
        this.calendar = calendar;
        this.parameters = parameters;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------- mã băm sổ (Q140) ----------

    /**
     * {@code keccak256(abi.encode(seriesController, uint64 atBlock, address[] wallets, uint64[] quantities))}, ví tăng
     * dần theo địa chỉ. Dùng lại cho danh sách chốt kỳ (B11b).
     */
    public static String registerHash(String controller, long atBlock, Map<String, Long> holdings) {
        TreeMap<String, Long> sorted = new TreeMap<>();
        holdings.forEach((w, q) -> sorted.put(w.toLowerCase(Locale.ROOT), q));
        List<Address> wallets = sorted.keySet().stream().map(Address::new).toList();
        List<Uint64> quantities = sorted.values().stream().map(q -> new Uint64(BigInteger.valueOf(q))).toList();
        String encoded = FunctionEncoder.encodeConstructor(List.of(new Address(controller),
                new Uint64(BigInteger.valueOf(atBlock)),
                new DynamicArray<>(Address.class, wallets), new DynamicArray<>(Uint64.class, quantities)));
        return Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(encoded)));
    }

    // ---------- đọc ----------

    public record Holder(String wallet, UUID investorOrgId, long quantity, List<UUID> allocationIds) {}

    public record Rejection(String reason, List<Map<String, String>> items) {}

    public record Attestation(UUID id, UUID seriesId, long atBlock, String registerHash, String status,
            UUID signingProposalId, int holderCount, long totalQuantity, List<Holder> holders, Rejection rejection,
            String attestedTxHash, Instant attestedAt, Instant createdAt) {}

    public Attestation latest(UUID seriesId) {
        return jdbc.queryForList("""
                SELECT id FROM distribution.register_attestation WHERE series_id = ? ORDER BY created_at DESC, id DESC LIMIT 1
                """, UUID.class, seriesId).stream().findFirst().map(this::get)
                .orElseThrow(() -> new ApiException(DistributionError.REGISTER_ATTESTATION_NOT_FOUND));
    }

    @SuppressWarnings("unchecked")
    public Attestation get(UUID id) {
        Map<String, Object> r = jdbc.queryForList("SELECT * FROM distribution.register_attestation WHERE id = ?", id)
                .stream().findFirst().orElseThrow(() -> new ApiException(DistributionError.REGISTER_ATTESTATION_NOT_FOUND));
        List<Holder> holders = jdbc.query("""
                SELECT wallet_address, investor_org_id, quantity, allocation_ids FROM distribution.register_attestation_line
                WHERE register_attestation_id = ? ORDER BY wallet_address
                """, (rs, i) -> new Holder(rs.getString(1), rs.getObject(2, UUID.class), rs.getLong(3),
                Arrays.asList((UUID[]) rs.getArray(4).getArray())), id);
        Rejection rejection = null;
        if (r.get("rejection") != null) {
            Map<String, Object> j = JSON.readValue(r.get("rejection").toString(), Map.class);
            rejection = new Rejection((String) j.get("reason"), (List<Map<String, String>>) j.get("items"));
        }
        return new Attestation(id, (UUID) r.get("series_id"), ((Number) r.get("at_block")).longValue(),
                (String) r.get("register_hash"), (String) r.get("status"), (UUID) r.get("signing_proposal_id"),
                ((Number) r.get("holder_count")).intValue(), ((Number) r.get("total_quantity")).longValue(), holders,
                rejection, (String) r.get("attested_tx_hash"),
                r.get("attested_at") == null ? null : ((Timestamp) r.get("attested_at")).toInstant(),
                ((Timestamp) r.get("created_at")).toInstant());
    }

    // ---------- lập sổ (Q140, Q141) ----------

    /**
     * Lập sổ và đề nghị VSDC ký nếu đủ điều kiện. {@code manual} = Holco gọi API: không đủ điều kiện thì ném lỗi; tự động
     * thì trả rỗng.
     */
    @Transactional
    public Optional<SigningProposals.Ref> propose(UUID seriesId, boolean manual) {
        SeriesCatalog.SeriesInfo s = series.find(seriesId)
                .orElseThrow(() -> new ApiException(DistributionError.SERIES_NOT_FOUND));
        if (s.controllerAddress() == null) {
            return refuse(manual, DistributionError.REGISTER_NOT_READY, "series chưa triển khai");
        }
        closeFailed(seriesId);
        List<Map<String, Object>> open = jdbc.queryForList("""
                SELECT id, status FROM distribution.register_attestation WHERE series_id = ? AND status IN ('PROPOSED', 'SIGNING')
                FOR UPDATE
                """, seriesId);
        if (!open.isEmpty()) {
            return refuse(manual, DistributionError.REGISTER_ATTESTATION_PENDING, open.getFirst().get("id").toString());
        }
        Integer attested = jdbc.queryForObject("""
                SELECT count(*) FROM distribution.register_attestation WHERE series_id = ? AND status = 'ATTESTED'
                """, Integer.class, seriesId);
        if (attested != null && attested > 0) {
            return refuse(manual, DistributionError.REGISTER_NOT_READY, "series đã được VSDC xác nhận sổ");
        }
        Map<String, Object> c = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE a.status = 'MINTED') AS minted,
                       count(*) FILTER (WHERE a.status = 'PAID' AND COALESCE(a.mint_failure_reason, '') <> 'CORRECTION_BURNED') AS unminted,
                       count(*) FILTER (WHERE a.status = 'AWAITING_PAYMENT') AS awaiting,
                       count(*) FILTER (WHERE a.status = 'NEEDS_CORRECTION') AS correcting,
                       COALESCE(max(GREATEST(COALESCE(a.minted_block, 0), COALESCE(a.correction_burned_block, 0))), 0) AS at_block
                FROM distribution.allocation a
                JOIN distribution.allocation_list l ON l.id = a.allocation_list_id
                JOIN distribution.offering o ON o.id = l.offering_id
                WHERE o.series_id = ? AND l.status = 'SIGNED'
                """, seriesId);
        long minted = ((Number) c.get("minted")).longValue();
        long unminted = ((Number) c.get("unminted")).longValue();
        long awaiting = ((Number) c.get("awaiting")).longValue();
        long correcting = ((Number) c.get("correcting")).longValue();
        if (minted == 0 || unminted > 0 || awaiting > 0 || correcting > 0) {
            return refuse(manual, DistributionError.REGISTER_NOT_READY, "đã mint " + minted + ", có tiền chưa mint "
                    + unminted + ", chờ tiền " + awaiting + ", cần sửa " + correcting);
        }
        long atBlock = ((Number) c.get("at_block")).longValue();
        String controller = s.controllerAddress().toLowerCase(Locale.ROOT);

        // Sổ từ phân bổ MINTED gộp theo ví
        Map<String, Long> holdings = new LinkedHashMap<>();
        Map<String, UUID> orgs = new LinkedHashMap<>();
        Map<String, List<UUID>> ids = new LinkedHashMap<>();
        jdbc.query("""
                SELECT a.wallet_address, a.investor_org_id, a.quantity, a.id FROM distribution.allocation a
                JOIN distribution.allocation_list l ON l.id = a.allocation_list_id
                JOIN distribution.offering o ON o.id = l.offering_id
                WHERE o.series_id = ? AND l.status = 'SIGNED' AND a.status = 'MINTED' ORDER BY a.wallet_address, a.id
                """, rs -> {
            String w = rs.getString(1);
            holdings.merge(w, rs.getLong(3), Long::sum);
            orgs.putIfAbsent(w, rs.getObject(2, UUID.class));
            ids.computeIfAbsent(w, k -> new ArrayList<>()).add(rs.getObject(4, UUID.class));
        }, seriesId);

        // Q140: kiểm với số dư trên chuỗi tại atBlock
        String token;
        try {
            token = calls.load(SeriesController.class, controller).token().send();
        } catch (Exception e) {
            throw new IllegalStateException("không đọc được token của " + controller + ": " + e.getMessage(), e);
        }
        for (Map.Entry<String, Long> h : holdings.entrySet()) {
            long chainBalance = balances.balanceOf(token, h.getKey(), atBlock);
            if (chainBalance != h.getValue()) {
                alerts.raise("register-view:" + seriesId, "RegisterViewMismatch", "critical", "Sổ lập cho VSDC của series "
                        + seriesId + " lệch chuỗi tại khối " + atBlock + ": ví " + h.getKey() + " phân bổ " + h.getValue()
                        + ", trên chuỗi " + chainBalance);
                return refuse(manual, DistributionError.REGISTER_VIEW_MISMATCH, h.getKey());
            }
        }

        String hash = registerHash(controller, atBlock, holdings);
        long total = holdings.values().stream().mapToLong(Long::longValue).sum();
        UUID id = Ids.newId();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO distribution.register_attestation (id, series_id, series_controller, at_block, register_hash,
                    holder_count, total_quantity, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PROPOSED', ?, ?)
                """, id, seriesId, controller, atBlock, hash, holdings.size(), total, now, now);
        for (Map.Entry<String, Long> h : holdings.entrySet()) {
            jdbc.update("""
                    INSERT INTO distribution.register_attestation_line
                        (register_attestation_id, wallet_address, investor_org_id, quantity, allocation_ids)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, h.getKey(), orgs.get(h.getKey()), h.getValue(),
                    (Object) ids.get(h.getKey()).toArray(new UUID[0]));
        }
        String calldata = FunctionEncoder.encode(new Function("attestRegister", List.of(
                new Bytes32(Numeric.hexStringToByteArray(hash)), new Uint64(BigInteger.valueOf(atBlock))), List.of()));
        SigningProposals.Ref ref = proposals.create(new SigningProposals.Request(PURPOSE, "register-attestation/" + id,
                null, "vsdc", controller, "attestRegister", calldata, hash));
        jdbc.update("""
                UPDATE distribution.register_attestation SET status = 'SIGNING', signing_proposal_id = ?, updated_at = ?
                WHERE id = ?
                """, ref.id(), now, id);
        audit.record("REGISTER_ATTESTATION_PROPOSED", "register-attestation/" + id, Map.of("seriesId", seriesId.toString(),
                "atBlock", atBlock, "registerHash", hash, "holderCount", holdings.size(), "totalQuantity", total,
                "signingProposalId", ref.id().toString()));
        return Optional.of(ref);
    }

    private static <T> Optional<T> refuse(boolean manual, DistributionError error, String detail) {
        if (manual) {
            throw new ApiException(error, detail);
        }
        return Optional.empty();
    }

    /** Lần lập đang ký mà đề nghị đã FAILED hoặc bị hủy ngoài luồng: đóng thành FAILED để lập lại được. */
    private void closeFailed(UUID seriesId) {
        jdbc.queryForList("""
                SELECT id, signing_proposal_id FROM distribution.register_attestation
                WHERE series_id = ? AND status = 'SIGNING' AND signing_proposal_id IS NOT NULL
                """, seriesId).forEach(r -> {
            UUID proposal = (UUID) r.get("signing_proposal_id");
            if (proposals.status(proposal).filter(CLOSED_PROPOSAL::contains).isPresent()) {
                jdbc.update("""
                        UPDATE distribution.register_attestation SET status = 'FAILED', updated_at = ? WHERE id = ?
                        """, Timestamp.from(clock.instant()), r.get("id"));
            }
        });
    }

    /** Series có phân bổ đã mint mà chưa được xác nhận (đối tượng của lịch quét). */
    public List<UUID> candidates() {
        return jdbc.queryForList("""
                SELECT DISTINCT o.series_id FROM distribution.allocation a
                JOIN distribution.allocation_list l ON l.id = a.allocation_list_id
                JOIN distribution.offering o ON o.id = l.offering_id
                WHERE l.status = 'SIGNED' AND a.status IN ('MINTED', 'NEEDS_CORRECTION')
                """, UUID.class);
    }

    /** Series của một controller (để lập sổ sau sự kiện chuỗi). */
    public Optional<UUID> seriesOf(String controller) {
        return series.findByController(controller);
    }

    // ---------- VSDC ký (Q143) ----------

    @Transactional
    public void onRegisterAttested(String controller, Map<String, Object> args, String txHash) {
        String hash = String.valueOf(args.get("registerHash")).toLowerCase(Locale.ROOT);
        long atBlock = Long.parseLong(String.valueOf(args.get("atBlock")));
        UUID seriesId = series.findByController(controller)
                .orElseThrow(() -> new IllegalStateException("chưa biết series của controller " + controller));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, status FROM distribution.register_attestation
                WHERE series_id = ? AND register_hash = ? AND at_block = ? FOR UPDATE
                """, seriesId, hash, atBlock);
        if (rows.isEmpty()) {
            alerts.raise("register-attested:" + controller + ":" + hash, "RegisterAttestationUnmatched", "critical",
                    "Controller " + controller + " ghi xác nhận sổ " + hash + " tại khối " + atBlock + " (tx " + txHash
                            + ") không khớp lần lập sổ nào");
            return;
        }
        Map<String, Object> r = rows.getFirst();
        if ("ATTESTED".equals(r.get("status"))) {
            return;
        }
        UUID id = (UUID) r.get("id");
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                UPDATE distribution.register_attestation SET status = 'ATTESTED', attested_tx_hash = ?, attested_at = ?,
                    updated_at = ? WHERE id = ?
                """, txHash.toLowerCase(Locale.ROOT), now, now, id);
        int confirmed = jdbc.update("""
                UPDATE distribution.allocation SET status = 'CONFIRMED', confirmed_at = ?
                WHERE status = 'MINTED' AND id IN (SELECT unnest(allocation_ids) FROM distribution.register_attestation_line
                                                    WHERE register_attestation_id = ?)
                """, now, id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seriesId", seriesId.toString());
        data.put("registerHash", hash);
        data.put("block", atBlock);
        data.put("attestationId", id.toString());
        data.put("txHash", txHash);
        data.put("confirmedAllocations", confirmed);
        events.publish(ATTESTED_EVENT, "series/" + seriesId, data);
        audit.record("REGISTER_ATTESTED", "register-attestation/" + id, data);
    }

    // ---------- VSDC từ chối (Q144) ----------

    @Transactional
    public Attestation reject(UUID id, String reason, List<Map<String, String>> items) {
        Map<String, Object> r = jdbc.queryForList("""
                SELECT status, signing_proposal_id, series_id FROM distribution.register_attestation WHERE id = ? FOR UPDATE
                """, id).stream().findFirst()
                .orElseThrow(() -> new ApiException(DistributionError.REGISTER_ATTESTATION_NOT_FOUND));
        if (!"SIGNING".equals(r.get("status")) && !"PROPOSED".equals(r.get("status"))) {
            throw new ApiException(DistributionError.REGISTER_ATTESTATION_NOT_PENDING, String.valueOf(r.get("status")));
        }
        Set<UUID> inRegister = new java.util.HashSet<>(jdbc.queryForList("""
                SELECT unnest(allocation_ids) FROM distribution.register_attestation_line WHERE register_attestation_id = ?
                """, UUID.class, id));
        for (Map<String, String> item : items) {
            if (!inRegister.contains(UUID.fromString(item.get("allocationId")))) {
                throw new ApiException(DistributionError.REGISTER_REJECTION_INVALID, item.get("allocationId"));
            }
        }
        if (r.get("signing_proposal_id") != null) {
            proposals.cancel((UUID) r.get("signing_proposal_id"));
        }
        Timestamp now = Timestamp.from(clock.instant());
        Map<String, Object> rejection = new LinkedHashMap<>();
        rejection.put("reason", reason);
        rejection.put("items", items);
        jdbc.update("""
                UPDATE distribution.register_attestation SET status = 'REJECTED', rejection = ?::jsonb, rejected_by = ?,
                    updated_at = ? WHERE id = ?
                """, JSON.writeValueAsString(rejection), CurrentUser.get().subject(), now, id);
        for (Map<String, String> item : items) {
            jdbc.update("""
                    UPDATE distribution.allocation SET status = 'NEEDS_CORRECTION', correction_reason = ?,
                        correction_action = NULL, correction_approval_id = NULL
                    WHERE id = ? AND status = 'MINTED'
                    """, item.get("reason"), UUID.fromString(item.get("allocationId")));
        }
        alerts.raise("register-rejected:" + id, "RegisterAttestationRejected", "warning", "VSDC từ chối xác nhận sổ series "
                + r.get("series_id") + " (" + items.size() + " mục cần sửa): " + reason);
        audit.record("REGISTER_ATTESTATION_REJECTED", "register-attestation/" + id, rejection);
        return get(id);
    }

    // ---------- Holco sửa mục (Q145) ----------

    @Transactional
    public UUID requestCorrection(UUID allocationId, String action, String reason) {
        String status = jdbc.queryForList("SELECT status FROM distribution.allocation WHERE id = ? FOR UPDATE", String.class,
                allocationId).stream().findFirst()
                .orElseThrow(() -> new ApiException(DistributionError.ALLOCATION_NOT_FOUND));
        if (!"NEEDS_CORRECTION".equals(status)) {
            throw new ApiException(DistributionError.ALLOCATION_NOT_IN_CORRECTION, status);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("allocationId", allocationId.toString());
        payload.put("action", action);
        payload.put("reason", reason);
        UUID approval = approvals.request(CORRECTION_ACTION, "allocation/" + allocationId, payload);
        jdbc.update("""
                UPDATE distribution.allocation SET correction_action = ?, correction_approval_id = ? WHERE id = ?
                """, action, approval, allocationId);
        jdbc.update("UPDATE distribution.allocation SET correction_reason = COALESCE(correction_reason, ?) WHERE id = ?",
                reason, allocationId);
        return approval;
    }

    /** Hook duyệt (trong giao dịch duyệt): DISMISS áp ngay; BURN để bộ nhận {@code approval.decided} gửi giao dịch. */
    public void applyCorrectionDecision(UUID approvalId, boolean approve) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, correction_action FROM distribution.allocation WHERE correction_approval_id = ? FOR UPDATE
                """, approvalId);
        if (rows.isEmpty()) {
            return;
        }
        UUID id = (UUID) rows.getFirst().get("id");
        if (!approve) {
            jdbc.update("UPDATE distribution.allocation SET correction_action = NULL, correction_approval_id = NULL WHERE id = ?",
                    id);
            return;
        }
        if ("DISMISS".equals(rows.getFirst().get("correction_action"))) {
            jdbc.update("""
                    UPDATE distribution.allocation SET status = 'MINTED', correction_action = NULL, correction_approval_id = NULL,
                        correction_reason = NULL WHERE id = ? AND status = 'NEEDS_CORRECTION'
                    """, id);
            audit.record("ALLOCATION_CORRECTION_DISMISSED", "allocation/" + id, Map.of("approvalId", approvalId.toString()));
        }
    }

    /** Phân bổ cần hủy theo yêu cầu duyệt đã đạt. */
    public Optional<UUID> burnOfApproval(UUID approvalId) {
        return jdbc.queryForList("""
                SELECT id FROM distribution.allocation WHERE correction_approval_id = ? AND correction_action = 'BURN'
                  AND status = 'NEEDS_CORRECTION' AND correction_chain_tx_id IS NULL
                """, UUID.class, approvalId).stream().findFirst();
    }

    /** Gửi {@code correctionBurn} bằng khóa vận hành (sau duyệt đạt). */
    @Transactional
    public UUID submitBurn(UUID allocationId) {
        Map<String, Object> a = jdbc.queryForMap("""
                SELECT a.id, a.wallet_address, a.quantity, a.status, o.series_id FROM distribution.allocation a
                JOIN distribution.allocation_list l ON l.id = a.allocation_list_id
                JOIN distribution.offering o ON o.id = l.offering_id
                WHERE a.id = ? FOR UPDATE OF a
                """, allocationId);
        String controller = series.find((UUID) a.get("series_id")).orElseThrow().controllerAddress().toLowerCase(Locale.ROOT);
        String data = calls.load(SeriesController.class, controller).correctionBurn(AllocationMerkle.uuidToBytes32(allocationId),
                (String) a.get("wallet_address"), BigInteger.valueOf(((Number) a.get("quantity")).longValue()))
                .encodeFunctionCall();
        try {
            UUID tx = chain.submit(new TxRequest(CORRECTION_PURPOSE, "allocation/" + allocationId, SignerKey.HOLCO_OPERATOR,
                    controller, data));
            jdbc.update("UPDATE distribution.allocation SET correction_chain_tx_id = ? WHERE id = ?", tx, allocationId);
            audit.record("ALLOCATION_CORRECTION_BURN_SUBMITTED", "allocation/" + allocationId,
                    Map.of("chainTxId", tx.toString()));
            return tx;
        } catch (ChainCallRejected e) {
            correctionFailed(allocationId, e.contractError());
            return null;
        }
    }

    private void correctionFailed(UUID allocationId, String reason) {
        jdbc.update("""
                UPDATE distribution.allocation SET correction_chain_tx_id = NULL, correction_action = NULL,
                    correction_approval_id = NULL WHERE id = ?
                """, allocationId);
        alerts.raise("correction-burn:" + allocationId, "CorrectionBurnFailed", "critical",
                "Hủy phân bổ " + allocationId + " bị hợp đồng từ chối: " + reason);
        audit.record("ALLOCATION_CORRECTION_BURN_FAILED", "allocation/" + allocationId, Map.of("reason", reason));
    }

    /** Giao dịch hủy revert trên chuỗi. */
    @Transactional
    public void onTxFailed(UUID chainTxId, String reason) {
        jdbc.queryForList("SELECT id FROM distribution.allocation WHERE correction_chain_tx_id = ? FOR UPDATE", UUID.class,
                chainTxId).forEach(id -> correctionFailed(id, reason));
    }

    /** Q145: {@code CorrectionBurned(allocationId, wallet, quantity)}: mục về PAID, giữ lại, không tự mint lại. */
    @Transactional
    public Optional<UUID> onCorrectionBurned(String controller, Map<String, Object> args, String txHash, long block) {
        UUID allocationId = vn.werwa.distribution.payment.MintOrchestrator.uuidOf(String.valueOf(args.get("allocationId")));
        int updated = jdbc.update("""
                UPDATE distribution.allocation SET status = 'PAID', mint_failure_reason = 'CORRECTION_BURNED',
                    mint_chain_tx_id = NULL, minted_tx_hash = NULL, minted_block = NULL, minted_at = NULL,
                    correction_chain_tx_id = NULL, correction_action = NULL, correction_approval_id = NULL,
                    correction_burned_block = ?
                WHERE id = ? AND status = 'NEEDS_CORRECTION'
                """, block, allocationId);
        if (updated == 0) {
            alerts.raise("correction-burned:" + controller + ":" + allocationId, "CorrectionBurnUnmatched", "critical",
                    "Controller " + controller + " hủy phân bổ " + allocationId + " (tx " + txHash
                            + ") không khớp yêu cầu sửa nào");
            return Optional.empty();
        }
        audit.record("ALLOCATION_CORRECTION_BURNED", "allocation/" + allocationId, Map.of("txHash", txHash, "block", block));
        return series.findByController(controller);
    }

    // ---------- hạn ký (Q146) ----------

    @Transactional
    public int alertOverdue(Instant now) {
        long days = parameters.integer("distribution.register-attestation-business-days", 1);
        int count = 0;
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT id, series_id, created_at FROM distribution.register_attestation
                WHERE status = 'SIGNING' AND overdue_alerted_at IS NULL
                """)) {
            LocalDate d = ((Timestamp) r.get("created_at")).toInstant().atZone(VN).toLocalDate();
            for (int i = 0; i < days; i++) {
                d = calendar.following(d.plusDays(1), List.of("VN"));
            }
            if (now.atZone(VN).toLocalDate().isAfter(d)) {
                alerts.raise("register-overdue:" + r.get("id"), "RegisterAttestationOverdue", "warning",
                        "VSDC chưa ký xác nhận sổ series " + r.get("series_id") + " quá " + days + " ngày làm việc");
                notifications.notify(new Notifications.Notice(Notifications.Kind.SIGNATURE_REQUESTED,
                        Notifications.Severity.WARNING, "Nhắc ký xác nhận sổ",
                        "Sổ series " + r.get("series_id") + " chờ VSDC ký quá hạn", "register-attestation/" + r.get("id"),
                        List.of(Role.REGISTRAR), null));
                jdbc.update("UPDATE distribution.register_attestation SET overdue_alerted_at = ? WHERE id = ?",
                        Timestamp.from(now), r.get("id"));
                count++;
            }
        }
        return count;
    }
}
