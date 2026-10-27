package vn.werwa.onboarding.kyc;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.integration.api.Screening;
import vn.werwa.onboarding.OnboardingError;
import vn.werwa.onboarding.OnboardingProperties;
import vn.werwa.onboarding.organization.OrganizationService;
import vn.werwa.onboarding.organization.OrganizationService.Org;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.web.ApiException;

/**
 * Hồ sơ KYC nhà đầu tư tổ chức (US-1.02, US-1.03 phần ngoài chuỗi; Q25). Mở hồ sơ là gọi sàng lọc qua mô-đun Tích hợp và
 * lưu kết quả nguyên văn; trùng cấm vận hoặc PEP thì hồ sơ cần xem xét. Kết luận do người khác người mở hồ sơ.
 *
 * <p>Claim KYC và INVESTOR_CLASS lên ONCHAINID làm ở B5b, khi nhận {@code investor.kyc_approved}.
 */
public class KycService {

    static final String KYC_APPROVED_EVENT = "werwa.onboarding.investor.kyc_approved.v1";

    private final JdbcTemplate jdbc;
    private final OrganizationService organizations;
    private final Screening screening;
    private final AuditLog audit;
    private final Events events;
    private final JsonMapper json;
    private final OnboardingProperties props;
    private final Clock clock;

    public KycService(JdbcTemplate jdbc, OrganizationService organizations, Screening screening, AuditLog audit,
            Events events, JsonMapper json, OnboardingProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.organizations = organizations;
        this.screening = screening;
        this.audit = audit;
        this.events = events;
        this.json = json;
        this.props = props;
        this.clock = clock;
    }

    public record Case(
            UUID id, UUID organizationId, String status, Map<String, Object> screeningResult, String investorClass,
            Instant expiresAt, String openedBy) {}

    /** US-1.02-AC1, AC2. */
    @Transactional
    public Case open(UUID organizationId, List<UUID> documents) {
        Org org = organizations.get(organizationId);
        if (!org.roles().contains("INVESTOR")) {
            throw new ApiException(OnboardingError.ORGANIZATION_NOT_INVESTOR);
        }
        organizations.requireDocuments(documents);
        CurrentUser opener = CurrentUser.get();
        Screening.Result result = screening.screen(
                new Screening.Subject(org.id(), org.legalName(), org.registrationNo(), org.country()));
        String status = result.sanctionsHit() || result.pepHit() ? "NEEDS_REVIEW" : "OPEN";
        UUID id = Ids.newId();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO onboarding.kyc_case
                        (id, organization_id, status, document_ids, provider, provider_ref, screening_result,
                         sanctions_hit, pep_hit, opened_by, opened_by_org_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                    """);
            ps.setObject(1, id);
            ps.setObject(2, organizationId);
            ps.setString(3, status);
            ps.setArray(4, con.createArrayOf("uuid", documents.toArray()));
            ps.setString(5, result.provider());
            ps.setString(6, result.providerRef());
            ps.setString(7, json.writeValueAsString(result.raw()));
            ps.setBoolean(8, result.sanctionsHit());
            ps.setBoolean(9, result.pepHit());
            ps.setString(10, opener.subject());
            ps.setObject(11, opener.organizationId());
            ps.setTimestamp(12, Timestamp.from(clock.instant()));
            return ps;
        });
        audit.record("KYC_CASE_OPENED", "kyc-case/" + id, Map.of("organizationId", organizationId.toString(),
                "providerRef", result.providerRef(), "status", status));
        return get(id);
    }

    /** US-1.02-AC2, AC3; US-1.03-AC1 (phần ngoài chuỗi). */
    @Transactional
    public Case decide(UUID id, boolean approve, String investorClass, String reason) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, opened_by, organization_id FROM onboarding.kyc_case WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new ApiException(OnboardingError.KYC_CASE_NOT_FOUND);
        }
        String status = (String) rows.getFirst().get("status");
        UUID organizationId = (UUID) rows.getFirst().get("organization_id");
        if (!"OPEN".equals(status) && !"NEEDS_REVIEW".equals(status)) {
            throw new ApiException(OnboardingError.KYC_NOT_OPEN, "đang " + status);
        }
        CurrentUser decider = CurrentUser.get();
        if (decider.subject().equals(rows.getFirst().get("opened_by"))) {
            throw new ApiException(OnboardingError.MAKER_CHECKER_SAME_USER);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        if (!approve) {
            jdbc.update("""
                    UPDATE onboarding.kyc_case SET status = 'REJECTED', decision_reason = ?, decided_by = ?, decided_at = ?
                    WHERE id = ?
                    """, reason, decider.subject(), Timestamp.from(now), id);
            audit.record("KYC_REJECTED", "kyc-case/" + id, Map.of("reason", String.valueOf(reason)));
            return get(id);
        }
        if (investorClass == null || investorClass.isBlank()) {
            throw new ApiException(OnboardingError.INVESTOR_CLASS_REQUIRED);
        }
        Integer known = jdbc.queryForObject(
                "SELECT count(*) FROM onboarding.investor_class WHERE key = ?", Integer.class, investorClass);
        if (known == 0) {
            throw new ApiException(OnboardingError.INVESTOR_CLASS_UNKNOWN, investorClass);
        }
        if ("NEEDS_REVIEW".equals(status) && (reason == null || reason.isBlank())) {
            throw new ApiException(OnboardingError.REASON_REQUIRED);
        }
        Instant expiresAt = now.plus(props.kycValidity());
        jdbc.update("""
                UPDATE onboarding.kyc_case SET status = 'APPROVED', investor_class = ?, decision_reason = ?, decided_by = ?,
                    decided_at = ?, expires_at = ?
                WHERE id = ?
                """, investorClass, reason, decider.subject(), Timestamp.from(now), Timestamp.from(expiresAt), id);
        jdbc.update("""
                INSERT INTO onboarding.investor_profile (organization_id, investor_class, updated_at) VALUES (?, ?, ?)
                ON CONFLICT (organization_id) DO UPDATE SET investor_class = excluded.investor_class, updated_at = excluded.updated_at
                """, organizationId, investorClass, Timestamp.from(now));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("organizationId", organizationId.toString());
        data.put("investorClass", investorClass);
        data.put("expiresAt", expiresAt.toString());
        data.put("kycCaseId", id.toString());
        events.publish(KYC_APPROVED_EVENT, "organization/" + organizationId, data);
        audit.record("KYC_APPROVED", "kyc-case/" + id, data);
        return get(id);
    }

    @SuppressWarnings("unchecked")
    public Case get(UUID id) {
        List<Case> rows = jdbc.query("""
                SELECT id, organization_id, status, screening_result::text, investor_class, expires_at, opened_by
                FROM onboarding.kyc_case WHERE id = ?
                """, (rs, i) -> new Case(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        json.readValue(rs.getString(4), Map.class), rs.getString(5),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getString(7)), id);
        if (rows.isEmpty()) {
            throw new ApiException(OnboardingError.KYC_CASE_NOT_FOUND);
        }
        return rows.getFirst();
    }
}
