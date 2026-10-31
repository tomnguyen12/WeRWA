package vn.werwa.onboarding.organization;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
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
import vn.werwa.governance.api.Approvals;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.onboarding.OnboardingError;
import vn.werwa.onboarding.api.Signers;
import vn.werwa.platform.documents.DocumentRegistry;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.OrganizationDirectory;
import vn.werwa.platform.security.Role;
import vn.werwa.platform.web.ApiException;

/**
 * Tổ chức tham gia, KYB và người ký được ủy quyền (US-1.01). KYB duyệt 4 mắt qua mô-đun Quản trị: tạo tổ chức là gửi duyệt;
 * quyết định qua {@code /kyb-decision} hoặc {@code /v1/approvals/{id}/decision} đều dẫn về {@link #applyDecision} qua
 * {@code ApprovalHook} (Q28).
 *
 * <p>Cũng là nguồn vai tổ chức cho kiểm quyền (I7, thay tệp seed theo Q5): chỉ tổ chức đã qua KYB mới có vai.
 */
public class OrganizationService implements OrganizationDirectory, Signers {

    public static final String KYB_ACTION = "ORGANIZATION_KYB";
    static final String KYB_APPROVED_EVENT = "werwa.onboarding.organization.kyb_approved.v1";

    private final JdbcTemplate jdbc;
    private final Approvals approvals;
    private final AuditLog audit;
    private final Events events;
    private final Clock clock;
    private final DocumentRegistry documents;

    public OrganizationService(JdbcTemplate jdbc, Approvals approvals, AuditLog audit, Events events, Clock clock,
            DocumentRegistry documents) {
        this.documents = documents;
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    public record Org(
            UUID id, String legalName, String registrationNo, String country, List<String> roles, String kybStatus,
            UUID kybApprovalId) {}

    public record Signer(
            UUID id, UUID organizationId, UUID userId, String signingAddress, List<String> scopes, LocalDate validFrom,
            LocalDate validTo, UUID authorizationDocId) {}

    /** US-1.01-AC1: người lập nhập hồ sơ; tổ chức chờ duyệt KYB. */
    @Transactional
    public Org create(String legalName, String registrationNo, String country, List<String> roles, List<UUID> documents) {
        requireDocuments(documents);
        UUID id = Ids.newId();
        Timestamp now = Timestamp.from(clock.instant());
        try {
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO onboarding.organization
                            (id, legal_name, registration_no, country, roles, kyb_status, kyb_document_ids, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 'PENDING_REVIEW', ?, ?, ?)
                        """);
                ps.setObject(1, id);
                ps.setString(2, legalName);
                ps.setString(3, registrationNo);
                ps.setString(4, country);
                ps.setArray(5, con.createArrayOf("text", roles.toArray()));
                ps.setArray(6, con.createArrayOf("uuid", documents.toArray()));
                ps.setTimestamp(7, now);
                ps.setTimestamp(8, now);
                return ps;
            });
        } catch (DuplicateKeyException e) {
            throw new ApiException(OnboardingError.ORGANIZATION_EXISTS, country + " " + registrationNo);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("legalName", legalName);
        payload.put("registrationNo", registrationNo);
        payload.put("country", country);
        payload.put("roles", roles);
        payload.put("kybDocumentIds", documents.stream().map(UUID::toString).toList());
        UUID approval = approvals.request(KYB_ACTION, "organization/" + id, payload);
        jdbc.update("UPDATE onboarding.organization SET kyb_approval_id = ? WHERE id = ?", approval, id);
        audit.record("ORGANIZATION_CREATED", "organization/" + id, payload);
        return get(id);
    }

    /** US-1.01-AC1, AC2: người duyệt khác người lập ra quyết định KYB. */
    @Transactional
    public Org decideKyb(UUID id, boolean approve, String comment) {
        Org org = get(id);
        if (!"PENDING_REVIEW".equals(org.kybStatus()) || org.kybApprovalId() == null) {
            throw new ApiException(OnboardingError.KYB_NOT_PENDING, "đang " + org.kybStatus());
        }
        try {
            approvals.decide(org.kybApprovalId(), approve, comment);
        } catch (ApiException e) {
            if ("MAKER_CHECKER_SAME_USER".equals(e.error().code())) {
                throw new ApiException(OnboardingError.MAKER_CHECKER_SAME_USER);
            }
            throw e;
        }
        // Kết quả đã được áp qua ApprovalHook (Q28) trong cùng giao dịch
        return get(id);
    }

    /** Áp kết quả duyệt KYB; chạy lại an toàn. */
    @Transactional
    public void applyDecision(UUID approvalId, boolean approve) {
        List<Org> rows = jdbc.query(SELECT + " WHERE kyb_approval_id = ? AND kyb_status = 'PENDING_REVIEW' FOR UPDATE",
                OrganizationService::org, approvalId);
        if (rows.isEmpty()) {
            return;
        }
        Org org = rows.getFirst();
        jdbc.update("UPDATE onboarding.organization SET kyb_status = ?, updated_at = ? WHERE id = ?",
                approve ? "KYB_APPROVED" : "KYB_REJECTED", Timestamp.from(clock.instant()), org.id());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("organizationId", org.id().toString());
        data.put("roles", org.roles());
        if (approve) {
            events.publish(KYB_APPROVED_EVENT, "organization/" + org.id(), data);
        }
        audit.record(approve ? "ORGANIZATION_KYB_APPROVED" : "ORGANIZATION_KYB_REJECTED", "organization/" + org.id(), data);
    }

    public Org get(UUID id) {
        List<Org> rows = jdbc.query(SELECT + " WHERE id = ?", OrganizationService::org, id);
        if (rows.isEmpty()) {
            throw new ApiException(OnboardingError.ORGANIZATION_NOT_FOUND);
        }
        return rows.getFirst();
    }

    /** US-1.01-AC3: đăng ký người ký của tổ chức đã qua KYB; địa chỉ ký duy nhất. */
    @Transactional
    public Signer addSigner(UUID organizationId, UUID userId, String signingAddress, List<String> scopes,
            LocalDate validFrom, LocalDate validTo, UUID authorizationDocId) {
        Org org = get(organizationId);
        if (!"KYB_APPROVED".equals(org.kybStatus())) {
            throw new ApiException(OnboardingError.ORGANIZATION_NOT_APPROVED);
        }
        requireDocuments(List.of(authorizationDocId));
        UUID id = Ids.newId();
        String address = signingAddress.toLowerCase(Locale.ROOT);
        try {
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO onboarding.authorized_signer
                            (id, organization_id, user_id, signing_address, scopes, valid_from, valid_to,
                             authorization_doc_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """);
                ps.setObject(1, id);
                ps.setObject(2, organizationId);
                ps.setObject(3, userId);
                ps.setString(4, address);
                ps.setArray(5, con.createArrayOf("text", scopes.toArray()));
                ps.setObject(6, validFrom);
                ps.setObject(7, validTo);
                ps.setObject(8, authorizationDocId);
                ps.setTimestamp(9, Timestamp.from(clock.instant()));
                return ps;
            });
        } catch (DuplicateKeyException e) {
            throw new ApiException(OnboardingError.SIGNER_ADDRESS_TAKEN, address);
        }
        audit.record("SIGNER_ADDED", "organization/" + organizationId,
                Map.of("signerId", id.toString(), "signingAddress", address, "scopes", scopes,
                        "validFrom", validFrom.toString()));
        return new Signer(id, organizationId, userId, address, scopes, validFrom, validTo, authorizationDocId);
    }

    @Override
    public Optional<Active> active(String signingAddress, LocalDate on) {
        return jdbc.query("""
                SELECT s.id, s.organization_id, s.user_id, s.scopes FROM onboarding.authorized_signer s
                JOIN onboarding.organization o ON o.id = s.organization_id AND o.kyb_status = 'KYB_APPROVED'
                WHERE s.signing_address = ? AND s.valid_from <= ? AND (s.valid_to IS NULL OR s.valid_to >= ?)
                """, (rs, i) -> new Active(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), Set.copyOf(Arrays.asList((String[]) rs.getArray(4).getArray()))),
                signingAddress.toLowerCase(Locale.ROOT), on, on).stream().findFirst();
    }

    /** Vai của tổ chức cho kiểm quyền (I7): rỗng nếu tổ chức không có hoặc chưa qua KYB. */
    @Override
    public Optional<Set<Role>> rolesOf(UUID organizationId) {
        return jdbc.query("SELECT roles FROM onboarding.organization WHERE id = ? AND kyb_status = 'KYB_APPROVED'",
                (rs, i) -> {
                    Set<Role> roles = EnumSet.noneOf(Role.class);
                    for (String r : (String[]) rs.getArray(1).getArray()) {
                        roles.add(Role.valueOf(r));
                    }
                    return Set.copyOf(roles);
                }, organizationId).stream().findFirst();
    }

    /** Q26: từ B6a, mã tài liệu phải có trong kho tài liệu. */
    public void requireDocuments(java.util.Collection<UUID> ids) {
        java.util.Collection<UUID> missing = documents.missing(ids);
        if (!missing.isEmpty()) {
            throw new ApiException(OnboardingError.DOCUMENT_NOT_FOUND, missing.toString());
        }
    }

    private static final String SELECT = """
            SELECT id, legal_name, registration_no, country, roles, kyb_status, kyb_approval_id
            FROM onboarding.organization""";

    static Org org(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Array roles = rs.getArray(5);
        return new Org(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                List.of((String[]) roles.getArray()), rs.getString(6), rs.getObject(7, UUID.class));
    }
}
