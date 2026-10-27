package vn.werwa.issuance.decision;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.issuance.IssuanceError;
import vn.werwa.issuance.document.DocumentService;
import vn.werwa.issuance.series.SeriesService;
import vn.werwa.platform.hash.Hashes;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.web.ApiException;

/**
 * Quyết định của UBND về điều khoản, lãi suất, phân bổ (US-2.06-AC1). Mã băm quyết định gồm số, ngày, đối tượng và SHA-256
 * của văn bản, để đi kèm đề nghị ký sau này (AC3, B7). Kiểm "đã có quyết định" khi gửi danh sách phân bổ (AC2) làm ở B7.
 */
public class IssuerDecisionService {

    private final JdbcTemplate jdbc;
    private final DocumentService documents;
    private final SeriesService series;
    private final AuditLog audit;
    private final Clock clock;

    public IssuerDecisionService(JdbcTemplate jdbc, DocumentService documents, SeriesService series, AuditLog audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.series = series;
        this.audit = audit;
        this.clock = clock;
    }

    public record Decision(
            UUID id, String decisionNo, LocalDate decidedOn, String subject, UUID seriesId, UUID offeringId, UUID documentId,
            String decisionHash) {}

    @Transactional
    public Decision record(String decisionNo, LocalDate decidedOn, String subject, UUID seriesId, UUID offeringId,
            UUID documentId) {
        // Đối tượng áp dụng: điều khoản theo series; phân bổ theo đợt chào bán; lãi suất theo series hoặc đợt chào bán
        boolean ok = switch (subject) {
            case "SERIES_TERMS" -> seriesId != null;
            case "ALLOCATION" -> offeringId != null;
            default -> seriesId != null || offeringId != null;
        };
        if (!ok) {
            throw new ApiException(IssuanceError.ISSUER_DECISION_INVALID,
                    subject + " cần " + ("ALLOCATION".equals(subject) ? "offeringId" : "seriesId"));
        }
        if (seriesId != null) {
            series.get(seriesId);
        }
        DocumentService.Doc doc = documents.require(List.of(documentId)).get(documentId);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("decisionNo", decisionNo);
        content.put("decidedOn", decidedOn.toString());
        content.put("subject", subject);
        content.put("seriesId", seriesId == null ? null : seriesId.toString());
        content.put("offeringId", offeringId == null ? null : offeringId.toString());
        content.put("documentSha256", doc.sha256());
        String hash = Hashes.payloadHash(content);
        UUID id = Ids.newId();
        CurrentUser user = CurrentUser.get();
        try {
            jdbc.update("""
                    INSERT INTO issuance.issuer_decision
                        (id, decision_no, decided_on, subject, series_id, offering_id, document_id, decision_hash,
                         owner_org_id, created_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, decisionNo, decidedOn, subject, seriesId, offeringId, documentId, hash, user.organizationId(),
                    user.subject(), Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException e) {
            throw new ApiException(IssuanceError.ISSUER_DECISION_EXISTS, decisionNo);
        }
        content.put("decisionHash", hash);
        audit.record("ISSUER_DECISION_RECORDED", "issuer-decision/" + id, content);
        return new Decision(id, decisionNo, decidedOn, subject, seriesId, offeringId, documentId, hash);
    }
}
