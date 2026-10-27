package vn.werwa.issuance.series;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.governance.api.Approvals;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.issuance.IssuanceError;
import vn.werwa.issuance.document.DocumentService;
import vn.werwa.issuance.program.ProgramService;
import vn.werwa.issuance.program.ProgramService.Program;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.hash.Hashes;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.money.Money;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.web.ApiException;

/**
 * Series: term sheet, lịch sự kiện, gửi duyệt và giữ chỗ hạn mức (US-2.04-AC2, US-2.05, máy Series cạnh 1–3).
 * Triển khai 3/3 và ISIN làm ở B6c.
 */
public class SeriesService {

    public static final String SUBMIT_ACTION = "SERIES_SUBMIT";
    static final String APPROVED_EVENT = "werwa.issuance.series.approved.v1";

    private final JdbcTemplate jdbc;
    private final ProgramService programs;
    private final DocumentService documents;
    private final Approvals approvals;
    private final AuditLog audit;
    private final Events events;
    private final ScheduleGenerator schedule;
    private final List<String> requiredDocumentTypes;
    private final Clock clock;

    public SeriesService(JdbcTemplate jdbc, ProgramService programs, DocumentService documents, Approvals approvals,
            AuditLog audit, Events events, ScheduleGenerator schedule, List<String> requiredDocumentTypes, Clock clock) {
        this.jdbc = jdbc;
        this.programs = programs;
        this.documents = documents;
        this.approvals = approvals;
        this.audit = audit;
        this.events = events;
        this.schedule = schedule;
        this.requiredDocumentTypes = List.copyOf(requiredDocumentTypes);
        this.clock = clock;
    }

    /** Term sheet; trường không bắt buộc để null thì lấy mặc định. */
    public record Terms(
            String seriesCode, String currency, Money faceValue, int issuePricePpm, long maxQuantity, String couponType,
            String couponFrequency, String dayCount, LocalDate issueDate, LocalDate maturityDate, List<String> calendars,
            Integer graceBusinessDays, Integer withholdingTaxPpm, Long holdingLimit, List<String> investorClasses,
            UUID termsDocumentId, List<UUID> documentIds) {}

    public record Series(
            UUID id, UUID programId, Terms terms, String status, String termsHash, String isin, Long chainId,
            String controllerAddress, String tokenAddress, UUID approvalRequestId, UUID signingProposalId, int version,
            List<ScheduleGenerator.Event> events) {}

    /** US-2.05-AC1, AC2. */
    @Transactional
    public Series create(UUID programId, Terms input) {
        Program program = programs.get(programId);
        if (!"EFFECTIVE".equals(program.status())) {
            throw new ApiException(IssuanceError.PROGRAM_NOT_EFFECTIVE);
        }
        Terms terms = withDefaults(input, program);
        check(terms, program);
        Map<UUID, DocumentService.Doc> docs = documents.require(documentIds(terms));
        String hash = termsHash(programId, terms, docs);
        UUID id = Ids.newId();
        CurrentUser user = CurrentUser.get();
        Timestamp now = Timestamp.from(clock.instant());
        try {
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO issuance.series
                            (id, program_id, series_code, currency, face_value_minor, issue_price_ppm, max_quantity,
                             coupon_type, coupon_frequency, day_count, issue_date, maturity_date, calendars,
                             grace_business_days, withholding_tax_ppm, holding_limit, investor_classes, terms_document_id,
                             document_ids, terms_hash, status, owner_org_id, created_by, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?)
                        """);
                ps.setObject(1, id);
                ps.setObject(2, programId);
                ps.setString(3, terms.seriesCode());
                bindTerms(con, ps, 4, terms);
                ps.setString(20, hash);
                ps.setObject(21, user.organizationId());
                ps.setString(22, user.subject());
                ps.setTimestamp(23, now);
                ps.setTimestamp(24, now);
                return ps;
            });
        } catch (DuplicateKeyException e) {
            throw new ApiException(IssuanceError.SERIES_CODE_TAKEN, terms.seriesCode());
        }
        writeSchedule(id, terms);
        audit.record("SERIES_CREATED", "series/" + id, Map.of("seriesCode", terms.seriesCode(), "termsHash", hash));
        return get(id);
    }

    /**
     * US-2.05-AC3: sửa term sheet. Series đang chờ duyệt thì quay về Nháp và yêu cầu duyệt cũ bị hủy; termsHash và lịch
     * được tính lại. Mã series không đổi.
     */
    @Transactional
    public Series update(UUID id, String ifMatch, Terms input) {
        Series current = lock(id);
        vn.werwa.platform.web.ETags.requireMatch(ifMatch, current.version());
        if (!"DRAFT".equals(current.status()) && !"PENDING_REVIEW".equals(current.status())) {
            throw new ApiException(IssuanceError.SERIES_NOT_EDITABLE, "đang " + current.status());
        }
        Program program = programs.get(current.programId());
        Terms terms = withDefaults(new Terms(current.terms().seriesCode(), input.currency(), input.faceValue(),
                input.issuePricePpm(), input.maxQuantity(), input.couponType(), input.couponFrequency(), input.dayCount(),
                input.issueDate(), input.maturityDate(), input.calendars(), input.graceBusinessDays(),
                input.withholdingTaxPpm(), input.holdingLimit(), input.investorClasses(), input.termsDocumentId(),
                input.documentIds()), program);
        check(terms, program);
        Map<UUID, DocumentService.Doc> docs = documents.require(documentIds(terms));
        String hash = termsHash(current.programId(), terms, docs);
        if ("PENDING_REVIEW".equals(current.status()) && current.approvalRequestId() != null) {
            approvals.cancel(current.approvalRequestId(), "term sheet đã sửa");
        }
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    UPDATE issuance.series SET currency = ?, face_value_minor = ?, issue_price_ppm = ?, max_quantity = ?,
                        coupon_type = ?, coupon_frequency = ?, day_count = ?, issue_date = ?, maturity_date = ?, calendars = ?,
                        grace_business_days = ?, withholding_tax_ppm = ?, holding_limit = ?, investor_classes = ?,
                        terms_document_id = ?, document_ids = ?, terms_hash = ?, status = 'DRAFT', approval_request_id = NULL,
                        version = version + 1, updated_at = ?
                    WHERE id = ?
                    """);
            bindTerms(con, ps, 1, terms);
            ps.setString(17, hash);
            ps.setTimestamp(18, Timestamp.from(clock.instant()));
            ps.setObject(19, id);
            return ps;
        });
        jdbc.update("DELETE FROM issuance.series_event WHERE series_id = ?", id);
        writeSchedule(id, terms);
        audit.record("SERIES_TERMS_CHANGED", "series/" + id, Map.of("from", current.status(), "oldTermsHash",
                current.termsHash(), "termsHash", hash));
        return get(id);
    }

    /** Máy Series cạnh 1; US-2.04-AC2. Trả mã yêu cầu duyệt. */
    @Transactional
    public UUID submit(UUID id) {
        Series s = lock(id);
        if (!"DRAFT".equals(s.status())) {
            throw new ApiException(IssuanceError.SERIES_NOT_DRAFT, "đang " + s.status());
        }
        Set<String> present = new LinkedHashSet<>();
        documents.require(documentIds(s.terms())).values().forEach(d -> present.add(d.docType()));
        List<String> missing = requiredDocumentTypes.stream().filter(t -> !present.contains(t)).toList();
        if (!missing.isEmpty()) {
            throw new ApiException(IssuanceError.REQUIRED_DOCUMENTS_MISSING, String.join(", ", missing));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("seriesId", id.toString());
        payload.put("seriesCode", s.terms().seriesCode());
        payload.put("termsHash", s.termsHash());
        UUID approval = approvals.request(SUBMIT_ACTION, "series/" + id, payload);
        jdbc.update("""
                UPDATE issuance.series SET status = 'PENDING_REVIEW', approval_request_id = ?, version = version + 1,
                    updated_at = ? WHERE id = ?
                """, approval, Timestamp.from(clock.instant()), id);
        audit.record("SERIES_SUBMITTED", "series/" + id, payload);
        return approval;
    }

    /**
     * Máy Series cạnh 2, 3 (Q28): duyệt đạt thì giữ chỗ hạn mức (US-2.02-AC1, AC2) và chờ ký 3 bên; từ chối thì về Nháp.
     */
    @Transactional
    public void applyDecision(UUID approvalId, boolean approve, String comment) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM issuance.series WHERE approval_request_id = ? AND status = 'PENDING_REVIEW' FOR UPDATE
                """, UUID.class, approvalId);
        if (ids.isEmpty()) {
            return;
        }
        Series s = get(ids.getFirst());
        Timestamp now = Timestamp.from(clock.instant());
        if (!approve) {
            jdbc.update("""
                    UPDATE issuance.series SET status = 'DRAFT', approval_request_id = NULL, version = version + 1,
                        updated_at = ? WHERE id = ?
                    """, now, s.id());
            audit.record("SERIES_REJECTED", "series/" + s.id(),
                    Map.of("approvalId", approvalId.toString(), "reason", String.valueOf(comment)));
            return;
        }
        Program basis = programs.effectiveOf(s.programId())
                .orElseThrow(() -> new ApiException(IssuanceError.PROGRAM_NOT_EFFECTIVE));
        checkAgainstProgram(s.terms(), basis);
        programs.reserve(s.programId(), s.id(), s.terms().faceValue().times(s.terms().maxQuantity()));
        jdbc.update("""
                UPDATE issuance.series SET status = 'PENDING_SIGNATURES', version = version + 1, updated_at = ? WHERE id = ?
                """, now, s.id());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seriesId", s.id().toString());
        data.put("termsHash", s.termsHash());
        data.put("programId", s.programId().toString());
        data.put("approvalId", approvalId.toString());
        events.publish(APPROVED_EVENT, "series/" + s.id(), data);
        audit.record("SERIES_APPROVED", "series/" + s.id(), data);
    }

    public Series get(UUID id) {
        List<Series> rows = jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> series(rs), id);
        if (rows.isEmpty()) {
            throw new ApiException(IssuanceError.SERIES_NOT_FOUND);
        }
        return withEvents(rows.getFirst());
    }

    private Series lock(UUID id) {
        List<Series> rows = jdbc.query(SELECT + " WHERE id = ? FOR UPDATE", (rs, i) -> series(rs), id);
        if (rows.isEmpty()) {
            throw new ApiException(IssuanceError.SERIES_NOT_FOUND);
        }
        return rows.getFirst();
    }

    private Series withEvents(Series s) {
        List<ScheduleGenerator.Event> evs = jdbc.query("""
                SELECT event_type, period_no, period_start, period_end, record_date, payment_date FROM issuance.series_event
                WHERE series_id = ? ORDER BY period_no, event_type
                """, (rs, i) -> new ScheduleGenerator.Event(rs.getString(1), rs.getInt(2),
                        rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class),
                        rs.getObject(6, LocalDate.class)), s.id());
        return new Series(s.id(), s.programId(), s.terms(), s.status(), s.termsHash(), s.isin(), s.chainId(),
                s.controllerAddress(), s.tokenAddress(), s.approvalRequestId(), s.signingProposalId(), s.version(), evs);
    }

    private void writeSchedule(UUID seriesId, Terms terms) {
        for (ScheduleGenerator.Event e : schedule.generate(terms.issueDate(), terms.maturityDate(), terms.couponFrequency(),
                terms.calendars())) {
            jdbc.update("""
                    INSERT INTO issuance.series_event
                        (id, series_id, event_type, period_no, period_start, period_end, record_date, payment_date)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), seriesId, e.eventType(), e.periodNo(), e.periodStart(), e.periodEnd(), e.recordDate(),
                    e.paymentDate());
        }
    }

    static Terms withDefaults(Terms t, Program program) {
        List<String> calendars = t.calendars() == null || t.calendars().isEmpty()
                ? ("VND".equals(t.currency()) ? List.of("VN") : List.of("VN", "NY"))
                : List.copyOf(new LinkedHashSet<>(t.calendars()));
        List<String> classes = t.investorClasses() == null || t.investorClasses().isEmpty()
                ? program.eligibleInvestorClasses() : t.investorClasses();
        return new Terms(t.seriesCode(), t.currency(), t.faceValue(), t.issuePricePpm(), t.maxQuantity(),
                t.couponType() == null ? "FIXED" : t.couponType(), t.couponFrequency(), t.dayCount(), t.issueDate(),
                t.maturityDate(), calendars, t.graceBusinessDays() == null ? 0 : t.graceBusinessDays(),
                t.withholdingTaxPpm() == null ? 0 : t.withholdingTaxPpm(), t.holdingLimit(), classes,
                t.termsDocumentId(), t.documentIds() == null ? List.of() : List.copyOf(new LinkedHashSet<>(t.documentIds())));
    }

    /** US-2.05-AC1: đồng tiền, kỳ hạn, đối tượng nằm trong khung Đề án. */
    static void check(Terms t, Program program) {
        List<String> problems = new ArrayList<>();
        if (!t.faceValue().currency().equals(t.currency())) {
            problems.add("faceValue phải cùng đồng tiền " + t.currency());
        }
        if (t.faceValue().isZero() || t.maxQuantity() <= 0 || t.issuePricePpm() <= 0) {
            problems.add("faceValue, maxQuantity, issuePricePpm phải lớn hơn 0");
        }
        if (!t.maturityDate().isAfter(t.issueDate())) {
            problems.add("maturityDate phải sau issueDate");
        }
        if (t.holdingLimit() != null && t.holdingLimit() <= 0) {
            problems.add("holdingLimit phải lớn hơn 0");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(vn.werwa.platform.web.PlatformError.VALIDATION_FAILED, String.join("; ", problems));
        }
        checkAgainstProgram(t, program);
    }

    static void checkAgainstProgram(Terms t, Program program) {
        List<String> problems = new ArrayList<>();
        if (!program.currency().equals(t.currency())) {
            problems.add("đồng tiền " + t.currency() + " khác Đề án " + program.currency());
        }
        Period[] tenor = program.tenor();
        if (tenor != null) {
            if (t.maturityDate().isBefore(t.issueDate().plus(tenor[0]))
                    || t.maturityDate().isAfter(t.issueDate().plus(tenor[1]))) {
                problems.add("kỳ hạn ngoài khung " + program.tenorRange());
            }
        }
        if (!problems.isEmpty()) {
            throw new ApiException(IssuanceError.SERIES_OUTSIDE_PROGRAM, String.join("; ", problems));
        }
        List<String> notEligible = t.investorClasses().stream()
                .filter(c -> !program.eligibleInvestorClasses().contains(c)).toList();
        if (!notEligible.isEmpty()) {
            throw new ApiException(IssuanceError.INVESTOR_CLASS_NOT_ELIGIBLE, notEligible.toString());
        }
    }

    static List<UUID> documentIds(Terms t) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        ids.add(t.termsDocumentId());
        ids.addAll(t.documentIds());
        return List.copyOf(ids);
    }

    /** Q34, Q38: SHA-256 của JSON chuẩn hóa gồm mọi tham số series và SHA-256 của các tài liệu. */
    static String termsHash(UUID programId, Terms t, Map<UUID, DocumentService.Doc> docs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("programId", programId.toString());
        m.put("seriesCode", t.seriesCode());
        m.put("currency", t.currency());
        m.put("faceValueMinor", t.faceValue().amountMinorString());
        m.put("issuePricePpm", t.issuePricePpm());
        m.put("maxQuantity", Long.toString(t.maxQuantity()));
        m.put("couponType", t.couponType());
        m.put("couponFrequency", t.couponFrequency());
        m.put("dayCount", t.dayCount());
        m.put("issueDate", t.issueDate().toString());
        m.put("maturityDate", t.maturityDate().toString());
        m.put("calendars", t.calendars());
        m.put("graceBusinessDays", t.graceBusinessDays());
        m.put("withholdingTaxPpm", t.withholdingTaxPpm());
        m.put("holdingLimit", t.holdingLimit() == null ? null : Long.toString(t.holdingLimit()));
        m.put("investorClasses", t.investorClasses());
        m.put("termsDocumentSha256", docs.get(t.termsDocumentId()).sha256());
        m.put("documentSha256", t.documentIds().stream().map(d -> docs.get(d).sha256()).toList());
        return Hashes.payloadHash(m);
    }

    private static void bindTerms(java.sql.Connection con, java.sql.PreparedStatement ps, int from, Terms t)
            throws java.sql.SQLException {
        int i = from;
        ps.setString(i++, t.currency());
        ps.setLong(i++, t.faceValue().amountMinor());
        ps.setInt(i++, t.issuePricePpm());
        ps.setLong(i++, t.maxQuantity());
        ps.setString(i++, t.couponType());
        ps.setString(i++, t.couponFrequency());
        ps.setString(i++, t.dayCount());
        ps.setObject(i++, t.issueDate());
        ps.setObject(i++, t.maturityDate());
        ps.setArray(i++, con.createArrayOf("text", t.calendars().toArray()));
        ps.setInt(i++, t.graceBusinessDays());
        ps.setInt(i++, t.withholdingTaxPpm());
        ps.setObject(i++, t.holdingLimit());
        ps.setArray(i++, con.createArrayOf("text", t.investorClasses().toArray()));
        ps.setObject(i++, t.termsDocumentId());
        ps.setArray(i, con.createArrayOf("uuid", t.documentIds().toArray()));
    }

    private static final String SELECT = """
            SELECT id, program_id, series_code, currency, face_value_minor, issue_price_ppm, max_quantity, coupon_type,
                   coupon_frequency, day_count, issue_date, maturity_date, calendars, grace_business_days,
                   withholding_tax_ppm, holding_limit, investor_classes, terms_document_id, document_ids, terms_hash, status,
                   isin, chain_id, controller_address, token_address, approval_request_id, version, signing_proposal_id
            FROM issuance.series""";

    private static Series series(java.sql.ResultSet rs) throws java.sql.SQLException {
        String currency = rs.getString(4);
        Terms terms = new Terms(rs.getString(3), currency, new Money(rs.getLong(5), currency), rs.getInt(6), rs.getLong(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getObject(11, LocalDate.class),
                rs.getObject(12, LocalDate.class), Arrays.asList((String[]) rs.getArray(13).getArray()), rs.getInt(14),
                rs.getInt(15), rs.getObject(16, Long.class), Arrays.asList((String[]) rs.getArray(17).getArray()),
                rs.getObject(18, UUID.class), Arrays.asList((UUID[]) rs.getArray(19).getArray()));
        return new Series(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), terms, rs.getString(21),
                rs.getString(20), rs.getString(22), rs.getObject(23, Long.class), rs.getString(24), rs.getString(25),
                rs.getObject(26, UUID.class), rs.getObject(28, UUID.class), rs.getInt(27), List.of());
    }
}
