package vn.werwa.issuance.program;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.governance.api.Approvals;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.issuance.IssuanceError;
import vn.werwa.issuance.document.DocumentService;
import vn.werwa.onboarding.api.InvestorClasses;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.money.Money;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.web.ApiException;

/**
 * Đề án và sổ hạn mức (US-2.01, US-2.02). Đề án mới là gửi duyệt 4 mắt; duyệt đạt thì hiệu lực và chỉ đọc. Đổi khung bằng
 * Đề án điều chỉnh (bản ghi mới trỏ bản cũ); bản điều chỉnh hiệu lực thì bản cũ chuyển SUPERSEDED.
 *
 * <p>Sổ hạn mức tính trên cả chuỗi Đề án (bản gốc và các bản điều chỉnh), so với khối lượng của bản đang hiệu lực (Q37).
 */
public class ProgramService {

    public static final String ACTIVATE_ACTION = "PROGRAM_ACTIVATE";

    private final JdbcTemplate jdbc;
    private final Approvals approvals;
    private final AuditLog audit;
    private final DocumentService documents;
    private final InvestorClasses investorClasses;
    private final Clock clock;

    public ProgramService(JdbcTemplate jdbc, Approvals approvals, AuditLog audit, DocumentService documents,
            InvestorClasses investorClasses, Clock clock) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.audit = audit;
        this.documents = documents;
        this.investorClasses = investorClasses;
        this.clock = clock;
    }

    public record Input(
            String title, String hdndResolutionNo, LocalDate approvedOn, String currency, Money maxPrincipal,
            int rateFloorPpm, int rateCapPpm, Money costCap, String tenorRange, List<String> eligibleInvestorClasses,
            List<String> issuanceMethods, UUID documentId, UUID amendsProgramId) {}

    public record Program(
            UUID id, UUID rootProgramId, UUID amendsProgramId, String title, String hdndResolutionNo, LocalDate approvedOn,
            String currency, Money maxPrincipal, int rateFloorPpm, int rateCapPpm, Money costCap, String tenorRange,
            List<String> eligibleInvestorClasses, List<String> issuanceMethods, UUID documentId, String status,
            UUID approvalRequestId) {

        /** Kỳ hạn tối thiểu và tối đa theo {@code tenorRange} (Q33); null nếu Đề án không giới hạn. */
        public Period[] tenor() {
            return tenorRange == null ? null : parseTenor(tenorRange);
        }
    }

    public record Limits(UUID programId, Money maxPrincipal, Money reserved, Money used, Money available) {}

    /** US-2.01-AC1, AC3. */
    @Transactional
    public Program create(Input in) {
        validate(in);
        documents.require(List.of(in.documentId()));
        UUID root;
        if (in.amendsProgramId() != null) {
            Program old = get(in.amendsProgramId());
            if (!"EFFECTIVE".equals(old.status())) {
                throw new ApiException(IssuanceError.PROGRAM_NOT_EFFECTIVE, "chỉ điều chỉnh Đề án đang hiệu lực");
            }
            if (!old.currency().equals(in.currency())) {
                throw new ApiException(IssuanceError.PROGRAM_INVALID, "Đề án điều chỉnh không được đổi đồng tiền");
            }
            root = old.rootProgramId();
        } else {
            root = null;
        }
        UUID id = Ids.newId();
        CurrentUser user = CurrentUser.get();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO issuance.issuance_program
                        (id, root_program_id, amends_program_id, title, hdnd_resolution_no, approved_on, currency,
                         max_principal_minor, rate_floor_ppm, rate_cap_ppm, cost_cap_minor, tenor_range,
                         eligible_investor_classes, issuance_methods, document_id, status, owner_org_id, created_by,
                         created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_REVIEW', ?, ?, ?, ?)
                    """);
            ps.setObject(1, id);
            ps.setObject(2, root == null ? id : root);
            ps.setObject(3, in.amendsProgramId());
            ps.setString(4, in.title());
            ps.setString(5, in.hdndResolutionNo());
            ps.setObject(6, in.approvedOn());
            ps.setString(7, in.currency());
            ps.setLong(8, in.maxPrincipal().amountMinor());
            ps.setInt(9, in.rateFloorPpm());
            ps.setInt(10, in.rateCapPpm());
            ps.setLong(11, in.costCap().amountMinor());
            ps.setString(12, in.tenorRange());
            ps.setArray(13, con.createArrayOf("text", in.eligibleInvestorClasses().toArray()));
            ps.setArray(14, con.createArrayOf("text",
                    (in.issuanceMethods() == null ? List.of() : in.issuanceMethods()).toArray()));
            ps.setObject(15, in.documentId());
            ps.setObject(16, user.organizationId());
            ps.setString(17, user.subject());
            ps.setTimestamp(18, now);
            ps.setTimestamp(19, now);
            return ps;
        });
        Program created = get(id);
        Map<String, Object> payload = payload(created);
        UUID approval = approvals.request(ACTIVATE_ACTION, "program/" + id, payload);
        jdbc.update("UPDATE issuance.issuance_program SET approval_request_id = ? WHERE id = ?", approval, id);
        audit.record("PROGRAM_CREATED", "program/" + id, payload);
        return get(id);
    }

    /** US-2.01-AC2: duyệt đạt thì hiệu lực; bản bị điều chỉnh chuyển SUPERSEDED. Chạy trong giao dịch duyệt (Q28). */
    @Transactional
    public void applyDecision(UUID approvalId, boolean approve) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM issuance.issuance_program WHERE approval_request_id = ? AND status = 'PENDING_REVIEW' FOR UPDATE
                """, UUID.class, approvalId);
        if (ids.isEmpty()) {
            return;
        }
        Program p = get(ids.getFirst());
        Timestamp now = Timestamp.from(clock.instant());
        if (!approve) {
            jdbc.update("UPDATE issuance.issuance_program SET status = 'DRAFT', version = version + 1, updated_at = ? "
                    + "WHERE id = ?", now, p.id());
            audit.record("PROGRAM_REJECTED", "program/" + p.id(), Map.of("approvalId", approvalId.toString()));
            return;
        }
        if (p.amendsProgramId() != null) {
            int changed = jdbc.update("""
                    UPDATE issuance.issuance_program SET status = 'SUPERSEDED', version = version + 1, updated_at = ?
                    WHERE id = ? AND status = 'EFFECTIVE'
                    """, now, p.amendsProgramId());
            if (changed == 0) {
                throw new ApiException(IssuanceError.PROGRAM_NOT_EFFECTIVE,
                        "Đề án được điều chỉnh không còn hiệu lực: " + p.amendsProgramId());
            }
            audit.record("PROGRAM_SUPERSEDED", "program/" + p.amendsProgramId(), Map.of("supersededBy", p.id().toString()));
        }
        jdbc.update("UPDATE issuance.issuance_program SET status = 'EFFECTIVE', version = version + 1, updated_at = ? "
                + "WHERE id = ?", now, p.id());
        audit.record("PROGRAM_EFFECTIVE", "program/" + p.id(), Map.of("approvalId", approvalId.toString()));
    }

    public Program get(UUID id) {
        List<Program> rows = jdbc.query(SELECT + " WHERE id = ?", ProgramService::program, id);
        if (rows.isEmpty()) {
            throw new ApiException(IssuanceError.PROGRAM_NOT_FOUND);
        }
        return rows.getFirst();
    }

    /** Bản đang hiệu lực của chuỗi chứa Đề án này, nếu có. */
    public java.util.Optional<Program> effectiveOf(UUID programId) {
        return jdbc.query(SELECT + " WHERE status = 'EFFECTIVE' AND root_program_id = "
                + "(SELECT root_program_id FROM issuance.issuance_program WHERE id = ?)", ProgramService::program, programId)
                .stream().findFirst();
    }

    /** US-2.02: số dư theo tổng bút toán trên cả chuỗi Đề án (Q37). */
    public Limits limits(UUID programId) {
        Program p = get(programId);
        Program basis = effectiveOf(programId).orElse(p);
        long[] sums = sums(p.rootProgramId());
        long reserve = sums[0];
        long use = sums[1];
        long release = sums[2];
        String c = p.currency();
        return new Limits(p.id(), basis.maxPrincipal(), new Money(reserve - use - release, c), new Money(use, c),
                new Money(Math.max(0, basis.maxPrincipal().amountMinor() - (reserve - release)), c));
    }

    /**
     * US-2.02-AC1, AC2: giữ chỗ khi series qua duyệt. Khóa Đề án đang hiệu lực của chuỗi để hai series duyệt cùng lúc
     * không cùng vượt khung.
     */
    @Transactional
    public void reserve(UUID programId, UUID seriesId, Money principal) {
        List<Program> effective = jdbc.query(SELECT + " WHERE status = 'EFFECTIVE' AND root_program_id = "
                + "(SELECT root_program_id FROM issuance.issuance_program WHERE id = ?) FOR UPDATE",
                ProgramService::program, programId);
        if (effective.isEmpty()) {
            throw new ApiException(IssuanceError.PROGRAM_NOT_EFFECTIVE);
        }
        Program basis = effective.getFirst();
        long[] sums = sums(basis.rootProgramId());
        long committed = sums[0] - sums[2];
        long after = Math.addExact(committed, principal.amountMinor());
        if (after > basis.maxPrincipal().amountMinor()) {
            throw new ApiException(IssuanceError.PROGRAM_LIMIT_EXCEEDED, "đã giữ chỗ và dùng " + committed + ", thêm "
                    + principal.amountMinor() + ", khối lượng " + basis.maxPrincipal().amountMinor() + " " + basis.currency());
        }
        jdbc.update("""
                INSERT INTO issuance.limit_ledger_entry (id, program_id, series_id, entry_type, principal_minor, created_by, created_at)
                VALUES (?, ?, ?, 'RESERVE', ?, ?, ?)
                """, Ids.newId(), programId, seriesId, principal.amountMinor(), CurrentUser.get().subject(),
                Timestamp.from(clock.instant()));
        audit.record("LIMIT_RESERVED", "program/" + programId,
                Map.of("seriesId", seriesId.toString(), "principalMinor", principal.amountMinorString()));
    }

    private long[] sums(UUID rootProgramId) {
        Map<String, Long> by = new LinkedHashMap<>();
        jdbc.query("""
                SELECT e.entry_type, COALESCE(SUM(e.principal_minor), 0) FROM issuance.limit_ledger_entry e
                JOIN issuance.issuance_program p ON p.id = e.program_id
                WHERE p.root_program_id = ? GROUP BY e.entry_type
                """, rs -> {
            by.put(rs.getString(1), rs.getLong(2));
        }, rootProgramId);
        return new long[] {by.getOrDefault("RESERVE", 0L), by.getOrDefault("USE", 0L), by.getOrDefault("RELEASE", 0L)};
    }

    private void validate(Input in) {
        List<String> problems = new ArrayList<>();
        if (!in.currency().equals(in.maxPrincipal().currency()) || !in.currency().equals(in.costCap().currency())) {
            problems.add("maxPrincipal và costCap phải cùng đồng tiền " + in.currency());
        }
        if (in.maxPrincipal().isZero()) {
            problems.add("maxPrincipal phải lớn hơn 0");
        }
        if (in.rateFloorPpm() > in.rateCapPpm()) {
            problems.add("rateFloorPpm lớn hơn rateCapPpm");
        }
        if (in.tenorRange() != null) {
            try {
                parseTenor(in.tenorRange());
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
        }
        if (in.eligibleInvestorClasses().isEmpty()) {
            problems.add("eligibleInvestorClasses không được rỗng");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(IssuanceError.PROGRAM_INVALID, String.join("; ", problems));
        }
        List<String> unknown = investorClasses.unknown(in.eligibleInvestorClasses());
        if (!unknown.isEmpty()) {
            throw new ApiException(IssuanceError.INVESTOR_CLASS_UNKNOWN, unknown.toString());
        }
    }

    /** Q33: khoảng ISO-8601 dạng {@code P1Y/P10Y}. */
    static Period[] parseTenor(String range) {
        String[] parts = range.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("tenorRange phải có dạng P1Y/P10Y");
        }
        try {
            Period min = Period.parse(parts[0]);
            Period max = Period.parse(parts[1]);
            LocalDate base = LocalDate.of(2000, 1, 1);
            if (min.isNegative() || base.plus(min).isAfter(base.plus(max))) {
                throw new IllegalArgumentException("tenorRange: kỳ hạn tối thiểu lớn hơn tối đa");
            }
            return new Period[] {min, max};
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("tenorRange phải có dạng P1Y/P10Y");
        }
    }

    static Map<String, Object> payload(Program p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("programId", p.id().toString());
        m.put("amendsProgramId", p.amendsProgramId() == null ? null : p.amendsProgramId().toString());
        m.put("title", p.title());
        m.put("hdndResolutionNo", p.hdndResolutionNo());
        m.put("approvedOn", p.approvedOn().toString());
        m.put("currency", p.currency());
        m.put("maxPrincipalMinor", p.maxPrincipal().amountMinorString());
        m.put("rateFloorPpm", p.rateFloorPpm());
        m.put("rateCapPpm", p.rateCapPpm());
        m.put("costCapMinor", p.costCap().amountMinorString());
        m.put("tenorRange", p.tenorRange());
        m.put("eligibleInvestorClasses", p.eligibleInvestorClasses());
        m.put("issuanceMethods", p.issuanceMethods());
        m.put("documentId", p.documentId().toString());
        return m;
    }

    private static final String SELECT = """
            SELECT id, root_program_id, amends_program_id, title, hdnd_resolution_no, approved_on, currency,
                   max_principal_minor, rate_floor_ppm, rate_cap_ppm, cost_cap_minor, tenor_range, eligible_investor_classes,
                   issuance_methods, document_id, status, approval_request_id
            FROM issuance.issuance_program""";

    static Program program(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        String currency = rs.getString(7);
        return new Program(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getString(5), rs.getObject(6, LocalDate.class), currency,
                new Money(rs.getLong(8), currency), rs.getInt(9), rs.getInt(10), new Money(rs.getLong(11), currency),
                rs.getString(12), Arrays.asList((String[]) rs.getArray(13).getArray()),
                Arrays.asList((String[]) rs.getArray(14).getArray()), rs.getObject(15, UUID.class), rs.getString(16),
                rs.getObject(17, UUID.class));
    }
}
