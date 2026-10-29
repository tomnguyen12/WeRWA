package vn.werwa.governance.signing;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.api.SigningProposals;
import vn.werwa.governance.approval.GovernanceError;
import vn.werwa.onchain.api.ChainCallRejected;
import vn.werwa.onchain.api.ChainTransactions;
import vn.werwa.onchain.api.Safes;
import vn.werwa.onchain.api.SignerKey;
import vn.werwa.onchain.api.TxRequest;
import vn.werwa.platform.config.WerwaProperties;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.security.Role;
import vn.werwa.platform.web.ApiException;
import vn.werwa.platform.web.Paging;

/**
 * Thu chữ ký multisig và gửi giao dịch Safe (US-6.04 AC2, AC3; Q106–Q111). Safe tổ chức tự thực hiện: người ký ký SafeTx.
 * Safe liên bên: người ký ký SafeMessage của Safe tổ chức mình bọc SafeTx liên bên; đủ ngưỡng tổ chức thì ghép chữ ký
 * EIP-1271; đủ ngưỡng liên bên thì gửi {@code execTransaction} bằng khóa RELAYER. Mỗi Safe một đề nghị đang mở (Q110).
 */
public class SigningService implements SigningProposals {

    static final String THRESHOLD_REACHED = "werwa.governance.signing.threshold_reached.v1";
    private static final Set<Role> SEE_ALL = EnumSet.of(Role.OPERATOR, Role.SUPERVISOR);
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JdbcTemplate jdbc;
    private final Safes safes;
    private final ChainTransactions chain;
    private final vn.werwa.platform.registry.SignerRegistry signers;
    private final WerwaProperties werwa;
    private final SigningProperties props;
    private final Events events;
    private final AuditLog audit;
    private final JsonMapper json;
    private final Clock clock;
    private vn.werwa.governance.api.Notifications notifications;

    /** Q127: thông báo SIGNATURE_REQUESTED khi đề nghị bắt đầu thu chữ ký. */
    public void setNotifications(vn.werwa.governance.api.Notifications notifications) {
        this.notifications = notifications;
    }

    public SigningService(JdbcTemplate jdbc, Safes safes, ChainTransactions chain,
            vn.werwa.platform.registry.SignerRegistry signers, WerwaProperties werwa,
            SigningProperties props, Events events, AuditLog audit, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.safes = safes;
        this.chain = chain;
        this.signers = signers;
        this.werwa = werwa;
        this.props = props;
        this.events = events;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /** Một bên ký: Safe tổ chức, tổ chức của nó, ngưỡng của Safe đó. */
    public record Party(String safe, UUID organizationId, int threshold) {}

    public record Signature(String signer, String partySafe, String signature, Instant submittedAt) {}

    public record Proposal(
            UUID id, String purpose, String subjectRef, UUID approvalRequestId, String safe, String targetContract,
            String functionName, String calldata, String calldataHash, String basisHash, Long nonce, String safeTxHash,
            Integer threshold,
            List<Party> parties, String status, String txHash, String failureReason, Instant createdAt,
            List<Signature> signatures) {

        /** Safe thực hiện là Safe tổ chức: người ký ký thẳng SafeTx. */
        boolean direct() {
            return parties.size() == 1 && parties.getFirst().safe().equals(safe);
        }

        long signed(Party p) {
            return signatures.stream().filter(s -> s.partySafe().equals(p.safe())).count();
        }

        boolean complete() {
            int done = (int) parties.stream().filter(p -> signed(p) >= p.threshold()).count();
            return threshold != null && done >= threshold;
        }
    }

    // ---------- tạo, hàng chờ ----------

    @Override
    @Transactional
    public Ref create(Request r) {
        String safe = resolveSafe(r.safe());
        String target = lower(r.targetContract());
        String data = lower(r.calldata());
        List<Party> parties = parties(safe);
        UUID id = Ids.newId();
        Timestamp now = Timestamp.from(clock.instant());
        CurrentUser user = CurrentUser.find().orElse(null);
        jdbc.update("""
                INSERT INTO governance.signing_proposal
                    (id, purpose, subject_ref, approval_request_id, safe_address, target_contract, function_name, calldata,
                     calldata_hash, basis_hash, parties, status, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'QUEUED', ?, ?, ?)
                """, id, r.purpose(), r.subjectRef(), r.approvalRequestId(), safe, target, r.functionName(), data,
                Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(data))),
                r.basisHash() == null ? null : lower(r.basisHash()), json.writeValueAsString(parties),
                user == null ? "system" : user.subject(), now, now);
        audit.record("SIGNING_PROPOSAL_CREATED", "signing-proposal/" + id, Map.of("purpose", r.purpose(),
                "subjectRef", r.subjectRef(), "safe", safe, "target", target, "functionName", r.functionName()));
        activateNext(safe);
        return new Ref(id, get(id).status());
    }

    @Override
    public Optional<String> status(UUID id) {
        return jdbc.queryForList("SELECT status FROM governance.signing_proposal WHERE id = ?", String.class, id).stream()
                .findFirst();
    }

    /** Đề nghị QUEUED cũ nhất của Safe nhận nonce hiện tại trên chuỗi, nếu Safe không có đề nghị đang mở. */
    void activateNext(String safe) {
        Integer open = jdbc.queryForObject("""
                SELECT count(*) FROM governance.signing_proposal
                WHERE safe_address = ? AND status IN ('COLLECTING', 'THRESHOLD_REACHED', 'SENT')
                """, Integer.class, safe);
        if (open > 0) {
            return;
        }
        List<UUID> next = jdbc.queryForList("""
                SELECT id FROM governance.signing_proposal WHERE safe_address = ? AND status = 'QUEUED'
                ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, UUID.class, safe);
        if (next.isEmpty()) {
            return;
        }
        Proposal p = get(next.getFirst());
        long nonce = safes.nonce(safe);
        int threshold = safes.threshold(safe);
        List<Party> parties = parties(safe);
        String safeTxHash = Numeric.toHexString(SafeTypedData.safeTxHash(werwa.chain().id(), safe, p.targetContract(),
                p.calldata(), nonce));
        jdbc.update("""
                UPDATE governance.signing_proposal SET status = 'COLLECTING', nonce = ?, threshold = ?, parties = ?::jsonb,
                    safe_tx_hash = ?, updated_at = ? WHERE id = ?
                """, nonce, parties.size() == 1 && parties.getFirst().safe().equals(safe) ? 1 : threshold,
                json.writeValueAsString(parties), safeTxHash, Timestamp.from(clock.instant()), p.id());
        audit.record("SIGNING_PROPOSAL_COLLECTING", "signing-proposal/" + p.id(), Map.of("nonce", nonce, "safeTxHash",
                safeTxHash));
        if (notifications != null) {
            for (Party party : parties) {
                notifications.notify(new vn.werwa.governance.api.Notifications.Notice(
                        vn.werwa.governance.api.Notifications.Kind.SIGNATURE_REQUESTED,
                        vn.werwa.governance.api.Notifications.Severity.INFO,
                        "Cần ký: " + p.functionName() + " (" + p.purpose() + ")", p.subjectRef(),
                        "signing-proposal/" + p.id(), List.of(), party.organizationId()));
            }
        }
    }

    /** Bên ký của Safe thực hiện: chính nó nếu là Safe tổ chức, không thì từng owner (phải là Safe tổ chức). */
    private List<Party> parties(String safe) {
        Map<String, UUID> orgSafes = orgSafes();
        if (orgSafes.containsKey(safe)) {
            return List.of(new Party(safe, orgSafes.get(safe), safes.threshold(safe)));
        }
        List<Party> out = new ArrayList<>();
        for (String owner : safes.owners(safe)) {
            UUID org = orgSafes.get(owner);
            if (org == null) {
                throw new ApiException(GovernanceError.SAFE_UNKNOWN, "owner " + owner + " của " + safe);
            }
            out.add(new Party(owner, org, safes.threshold(owner)));
        }
        return out;
    }

    /** Địa chỉ Safe tổ chức (chữ thường) sang id tổ chức (Q107). */
    private Map<String, UUID> orgSafes() {
        Map<String, UUID> out = new LinkedHashMap<>();
        props.safeOrganizations().forEach((name, org) -> out.put(lower(werwa.safe(name)), org));
        return out;
    }

    private String resolveSafe(String nameOrAddress) {
        try {
            return lower(nameOrAddress.startsWith("0x") ? nameOrAddress : werwa.safe(nameOrAddress));
        } catch (RuntimeException e) {
            throw new ApiException(GovernanceError.SAFE_UNKNOWN, nameOrAddress);
        }
    }

    // ---------- chữ ký ----------

    /** Dữ liệu EIP-712 người ký của một bên cần ký (Q108). */
    public Map<String, Object> typedDataFor(Proposal p, Party party) {
        long chainId = werwa.chain().id();
        if (p.direct()) {
            return SafeTypedData.safeTxTypedData(chainId, p.safe(), p.targetContract(), p.calldata(), p.nonce());
        }
        return SafeTypedData.safeMessageTypedData(chainId, party.safe(),
                SafeTypedData.safeTxData(chainId, p.safe(), p.targetContract(), p.calldata(), p.nonce()));
    }

    private byte[] hashFor(Proposal p, Party party) {
        long chainId = werwa.chain().id();
        if (p.direct()) {
            return SafeTypedData.safeTxHash(chainId, p.safe(), p.targetContract(), p.calldata(), p.nonce());
        }
        return SafeTypedData.safeMessageHash(chainId, party.safe(),
                SafeTypedData.safeTxData(chainId, p.safe(), p.targetContract(), p.calldata(), p.nonce()));
    }

    /** US-6.04-AC2 (Q109): kiểm người ký rồi lưu; đủ ngưỡng thì gửi giao dịch (Q111). */
    @Transactional
    public Proposal submit(UUID id, String signer, String signature) {
        Proposal p = lock(id);
        if (!"COLLECTING".equals(p.status())) {
            throw new ApiException(GovernanceError.SIGNING_PROPOSAL_NOT_COLLECTING, p.status());
        }
        CurrentUser user = CurrentUser.get();
        Party party = p.parties().stream().filter(x -> x.organizationId().equals(user.organizationId())).findFirst()
                .orElseThrow(() -> new ApiException(GovernanceError.SIGNER_NOT_AUTHORIZED, "tổ chức không phải một bên ký"));
        String claimed = lower(signer);
        String sig = lower(signature);
        String recovered = SafeTypedData.recover(hashFor(p, party), sig);
        if (!claimed.equals(recovered)) {
            throw new ApiException(GovernanceError.SIGNATURE_INVALID);
        }
        if (!safes.owners(party.safe()).contains(claimed)) {
            throw new ApiException(GovernanceError.SIGNER_NOT_AUTHORIZED, "không phải owner của Safe " + party.safe());
        }
        Optional<UUID> signerOrg = signers.activeSignerOrganization(claimed, LocalDate.now(clock.withZone(VN)));
        if (signerOrg.isEmpty() || !signerOrg.get().equals(user.organizationId())) {
            throw new ApiException(GovernanceError.SIGNER_NOT_AUTHORIZED, "không phải người ký được ủy quyền còn hiệu lực");
        }
        try {
            jdbc.update("""
                    INSERT INTO governance.signing_signature (proposal_id, signer, party_safe, signature, submitted_by, submitted_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, id, claimed, party.safe(), sig, user.subject(), Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException e) {
            throw new ApiException(GovernanceError.SIGNATURE_DUPLICATE);
        }
        audit.record("SIGNING_SIGNATURE_SUBMITTED", "signing-proposal/" + id, Map.of("signer", claimed, "safe", party.safe()));
        Proposal now = get(id);
        if (now.complete()) {
            execute(now);
        }
        return get(id);
    }

    /** Q111: ghép chữ ký, gửi {@code execTransaction} bằng RELAYER; hợp đồng từ chối khi chạy thử thì FAILED. */
    private void execute(Proposal p) {
        Timestamp ts = Timestamp.from(clock.instant());
        jdbc.update("UPDATE governance.signing_proposal SET status = 'THRESHOLD_REACHED', updated_at = ? WHERE id = ?", ts,
                p.id());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("proposalId", p.id().toString());
        data.put("safe", p.safe());
        data.put("calldataHash", p.calldataHash());
        data.put("purpose", p.purpose());
        data.put("subjectRef", p.subjectRef());
        events.publish(THRESHOLD_REACHED, "signing-proposal/" + p.id(), data);

        byte[] signatures;
        if (p.direct()) {
            signatures = SafeTypedData.ownerSignatures(signaturesOf(p, p.safe()), p.parties().getFirst().threshold());
        } else {
            Map<String, byte[]> byOwner = new LinkedHashMap<>();
            p.parties().stream().filter(x -> p.signed(x) >= x.threshold())
                    .sorted(java.util.Comparator.comparing(Party::safe)).limit(p.threshold())
                    .forEach(x -> byOwner.put(x.safe(), SafeTypedData.ownerSignatures(signaturesOf(p, x.safe()),
                            x.threshold())));
            signatures = SafeTypedData.contractSignatures(byOwner);
        }
        String exec = FunctionEncoder.encode(new Function("execTransaction", List.of(new Address(p.targetContract()),
                new Uint256(0), new DynamicBytes(Numeric.hexStringToByteArray(p.calldata())), new Uint8(0), new Uint256(0),
                new Uint256(0), new Uint256(0), new Address(SafeTypedData.ZERO), new Address(SafeTypedData.ZERO),
                new DynamicBytes(signatures)), List.of()));
        try {
            UUID tx = chain.submit(new TxRequest(p.purpose(), p.subjectRef(), SignerKey.RELAYER, p.safe(), exec));
            jdbc.update("UPDATE governance.signing_proposal SET status = 'SENT', chain_tx_id = ?, updated_at = ? WHERE id = ?",
                    tx, ts, p.id());
            audit.record("SIGNING_PROPOSAL_SENT", "signing-proposal/" + p.id(), Map.of("chainTxId", tx.toString()));
        } catch (ChainCallRejected e) {
            finish(p.id(), "FAILED", null, e.contractError() + ": " + e.getMessage());
        }
    }

    private Map<String, String> signaturesOf(Proposal p, String partySafe) {
        Map<String, String> out = new LinkedHashMap<>();
        p.signatures().stream().filter(s -> s.partySafe().equals(partySafe)).forEach(s -> out.put(s.signer(), s.signature()));
        return out;
    }

    // ---------- kết quả, hủy ----------

    /** Giao dịch Safe đã lên khối (tx.mined). Trả false nếu giao dịch không thuộc đề nghị nào. */
    @Transactional
    public boolean mined(UUID chainTxId, String txHash) {
        return byChainTx(chainTxId).map(id -> {
            finish(id, "MINED", txHash, null);
            return true;
        }).orElse(false);
    }

    @Transactional
    public boolean failed(UUID chainTxId, String reason) {
        return byChainTx(chainTxId).map(id -> {
            finish(id, "FAILED", null, reason);
            return true;
        }).orElse(false);
    }

    /** Q110: Holco hủy đề nghị chưa gửi; đề nghị kế tiếp của Safe nhận nonce. */
    @Transactional
    public Proposal cancel(UUID id) {
        Proposal p = lock(id);
        if (!"QUEUED".equals(p.status()) && !"COLLECTING".equals(p.status())) {
            throw new ApiException(GovernanceError.SIGNING_PROPOSAL_NOT_CANCELLABLE, p.status());
        }
        finish(id, "CANCELLED", null, null);
        return get(id);
    }

    private void finish(UUID id, String status, String txHash, String reason) {
        Proposal p = get(id);
        jdbc.update("""
                UPDATE governance.signing_proposal SET status = ?, tx_hash = COALESCE(?, tx_hash), failure_reason = ?,
                    updated_at = ? WHERE id = ?
                """, status, txHash, reason, Timestamp.from(clock.instant()), id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", status);
        if (reason != null) {
            detail.put("reason", reason);
        }
        audit.record("SIGNING_PROPOSAL_" + status, "signing-proposal/" + id, detail);
        activateNext(p.safe());
    }

    private Optional<UUID> byChainTx(UUID chainTxId) {
        return jdbc.queryForList("SELECT id FROM governance.signing_proposal WHERE chain_tx_id = ? AND status = 'SENT'",
                UUID.class, chainTxId).stream().findFirst();
    }

    // ---------- đọc ----------

    /** Bên ký thấy đề nghị có tổ chức mình; Holco, Cơ quan giám sát thấy tất cả. */
    public Proposal view(UUID id) {
        Proposal p = get(id);
        CurrentUser user = CurrentUser.get();
        if (!user.hasAnyRole(SEE_ALL) && myParty(p).isEmpty()) {
            throw new ApiException(GovernanceError.SIGNING_PROPOSAL_NOT_FOUND);
        }
        return p;
    }

    public Optional<Party> myParty(Proposal p) {
        UUID org = CurrentUser.get().organizationId();
        return p.parties().stream().filter(x -> x.organizationId().equals(org)).findFirst();
    }

    /** Mới nhất trước; mặc định đề nghị đang thu chữ ký (Q112). */
    public Paging.Page<Proposal> list(String status, String cursor, Integer limit) {
        int size = Paging.limit(limit);
        UUID before = Paging.decodeCursor(cursor);
        CurrentUser user = CurrentUser.get();
        StringBuilder sql = new StringBuilder("SELECT id FROM governance.signing_proposal WHERE status = ?");
        List<Object> args = new ArrayList<>(List.of(status == null ? "COLLECTING" : status));
        if (!user.hasAnyRole(SEE_ALL)) {
            sql.append(" AND parties @> ?::jsonb");
            args.add("[{\"organizationId\":\"" + user.organizationId() + "\"}]");
        }
        if (before != null) {
            sql.append(" AND id < ?");
            args.add(before);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(size + 1);
        List<Proposal> rows = jdbc.queryForList(sql.toString(), UUID.class, args.toArray()).stream().map(this::get).toList();
        return Paging.Page.of(rows, size, Proposal::id);
    }

    public Proposal get(UUID id) {
        List<Proposal> rows = jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> row(rs), id);
        if (rows.isEmpty()) {
            throw new ApiException(GovernanceError.SIGNING_PROPOSAL_NOT_FOUND);
        }
        return rows.getFirst();
    }

    private Proposal lock(UUID id) {
        jdbc.queryForList("SELECT id FROM governance.signing_proposal WHERE id = ? FOR UPDATE", UUID.class, id).stream()
                .findFirst().orElseThrow(() -> new ApiException(GovernanceError.SIGNING_PROPOSAL_NOT_FOUND));
        return get(id);
    }

    private static final String SELECT = """
            SELECT id, purpose, subject_ref, approval_request_id, safe_address, target_contract, function_name, calldata,
                   calldata_hash, nonce, safe_tx_hash, threshold, parties::text, status, tx_hash, failure_reason, created_at,
                   basis_hash
            FROM governance.signing_proposal""";

    private Proposal row(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID id = rs.getObject(1, UUID.class);
        List<Party> parties = new ArrayList<>();
        String raw = rs.getString(13);
        if (raw != null) {
            for (Object o : json.readValue(raw, List.class)) {
                Map<?, ?> m = (Map<?, ?>) o;
                parties.add(new Party(String.valueOf(m.get("safe")), UUID.fromString(String.valueOf(m.get("organizationId"))),
                        ((Number) m.get("threshold")).intValue()));
            }
        }
        List<Signature> sigs = jdbc.query("""
                SELECT signer, party_safe, signature, submitted_at FROM governance.signing_signature
                WHERE proposal_id = ? ORDER BY submitted_at, signer
                """, (r, i) -> new Signature(r.getString(1), r.getString(2), r.getString(3),
                        r.getTimestamp(4).toInstant()), id);
        return new Proposal(id, rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(18),
                rs.getObject(10, Long.class),
                rs.getString(11), rs.getObject(12, Integer.class), parties, rs.getString(14), rs.getString(15),
                rs.getString(16), rs.getTimestamp(17).toInstant(), sigs);
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
