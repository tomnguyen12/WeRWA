package vn.werwa.governance.parameter;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.governance.api.Approvals;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.api.Parameters;
import vn.werwa.governance.approval.GovernanceError;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.web.ApiException;

/**
 * Tham số hệ thống (US-4.11-AC2; Q60): đổi qua duyệt 4 mắt, có hiệu lực từ ngày chọn (giờ VN). Danh mục khóa và kiểu cố
 * định trong {@link #CATALOG}; khóa mới thêm ở đây cùng mô-đun đọc nó.
 */
public class ParameterService implements Parameters {

    public static final String CHANGE_ACTION = "PARAMETER_CHANGE";
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    public enum Type { INTEGER, STRING_LIST, ENUM }

    public record Definition(String key, Type type, long min, List<String> allowed) {}

    /** Khóa đã chuyển vào bảng ở B12a. */
    public static final Map<String, Definition> CATALOG = Map.of(
            "distribution.order-quantity-step", new Definition("distribution.order-quantity-step", Type.INTEGER, 1, null),
            "distribution.payment-grace-business-days",
            new Definition("distribution.payment-grace-business-days", Type.INTEGER, 0, null),
            "issuance.required-document-types",
            new Definition("issuance.required-document-types", Type.STRING_LIST, 1, null),
            "issuance.business-day-convention", new Definition("issuance.business-day-convention", Type.ENUM, 0,
                    List.of("FOLLOWING", "MODIFIED_FOLLOWING")),
            // Q93: độ trễ tối đa của indexer so với đầu chuỗi (US-4.04-AC1)
            "governance.supervision-max-lag-seconds",
            new Definition("governance.supervision-max-lag-seconds", Type.INTEGER, 1, null));

    private final JdbcTemplate jdbc;
    private final Approvals approvals;
    private final AuditLog audit;
    private final JsonMapper json;
    private final Clock clock;

    public ParameterService(JdbcTemplate jdbc, Approvals approvals, AuditLog audit, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public record Change(UUID id, String key, Object value, LocalDate effectiveFrom, String reason, String status,
            UUID approvalRequestId) {}

    public record Current(Definition definition, Object value, LocalDate effectiveFrom, List<Change> scheduled) {}

    @Override
    public long integer(String key, long fallback) {
        return current(key).map(v -> ((Number) v.value()).longValue()).orElse(fallback);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> stringList(String key, List<String> fallback) {
        return current(key).map(v -> List.copyOf((List<String>) v.value())).orElse(fallback);
    }

    @Override
    public String enumValue(String key, String fallback) {
        return current(key).map(v -> (String) v.value()).orElse(fallback);
    }

    Optional<Change> current(String key) {
        return jdbc.query(SELECT + " WHERE key = ? AND status = 'APPROVED' AND effective_from <= ? "
                + "ORDER BY effective_from DESC, decided_at DESC LIMIT 1", (rs, i) -> row(rs), key, today()).stream()
                .findFirst();
    }

    public List<Current> list() {
        List<Current> out = new ArrayList<>();
        CATALOG.values().stream().sorted(java.util.Comparator.comparing(Definition::key)).forEach(d -> {
            Optional<Change> c = current(d.key());
            List<Change> scheduled = jdbc.query(SELECT + " WHERE key = ? AND ((status = 'APPROVED' AND effective_from > ?) "
                    + "OR status = 'PENDING_APPROVAL') ORDER BY effective_from", (rs, i) -> row(rs), d.key(), today());
            out.add(new Current(d, c.map(Change::value).orElse(null), c.map(Change::effectiveFrom).orElse(null), scheduled));
        });
        return out;
    }

    /** Q60: đề nghị đổi; kiểm khóa và kiểu ngay. */
    @Transactional
    public Change request(String key, Object value, LocalDate effectiveFrom, String reason) {
        Definition d = CATALOG.get(key);
        if (d == null) {
            throw new ApiException(GovernanceError.PARAMETER_UNKNOWN, key);
        }
        validate(d, value);
        if (effectiveFrom.isBefore(today())) {
            throw new ApiException(GovernanceError.PARAMETER_INVALID, "effectiveFrom không được ở quá khứ");
        }
        UUID id = Ids.newId();
        CurrentUser user = CurrentUser.get();
        jdbc.update("""
                INSERT INTO governance.parameter_change
                    (id, key, value, effective_from, reason, status, created_by, created_at)
                VALUES (?, ?, ?::jsonb, ?, ?, 'PENDING_APPROVAL', ?, ?)
                """, id, key, json.writeValueAsString(value), effectiveFrom, reason, user.subject(),
                Timestamp.from(clock.instant()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("changeId", id.toString());
        payload.put("key", key);
        payload.put("value", value);
        payload.put("effectiveFrom", effectiveFrom.toString());
        payload.put("reason", reason);
        UUID approval = approvals.request(CHANGE_ACTION, "parameter/" + key, payload);
        jdbc.update("UPDATE governance.parameter_change SET approval_request_id = ? WHERE id = ?", approval, id);
        audit.record("PARAMETER_CHANGE_REQUESTED", "parameter/" + key, payload);
        return get(id);
    }

    @Transactional
    public void applyDecision(UUID approvalId, boolean approve) {
        int n = jdbc.update("""
                UPDATE governance.parameter_change SET status = ?, decided_at = ?
                WHERE approval_request_id = ? AND status = 'PENDING_APPROVAL'
                """, approve ? "APPROVED" : "REJECTED", Timestamp.from(clock.instant()), approvalId);
        if (n > 0) {
            Change c = jdbc.query(SELECT + " WHERE approval_request_id = ?", (rs, i) -> row(rs), approvalId).getFirst();
            audit.record(approve ? "PARAMETER_CHANGE_APPROVED" : "PARAMETER_CHANGE_REJECTED", "parameter/" + c.key(),
                    Map.of("changeId", c.id().toString(), "effectiveFrom", c.effectiveFrom().toString()));
        }
    }

    Change get(UUID id) {
        return jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> row(rs), id).getFirst();
    }

    private LocalDate today() {
        return clock.instant().atZone(VN).toLocalDate();
    }

    static void validate(Definition d, Object value) {
        boolean ok = switch (d.type()) {
            case INTEGER -> value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                    && n.longValue() >= d.min();
            case STRING_LIST -> value instanceof List<?> l && l.size() >= d.min()
                    && l.stream().allMatch(e -> e instanceof String s && !s.isBlank());
            case ENUM -> value instanceof String s && d.allowed().contains(s);
        };
        if (!ok) {
            throw new ApiException(GovernanceError.PARAMETER_INVALID, d.key() + " cần " + d.type()
                    + (d.allowed() == null ? (d.type() == Type.INTEGER ? " ≥ " + d.min() : "") : " trong " + d.allowed()));
        }
    }

    private static final String SELECT = """
            SELECT id, key, value::text, effective_from, reason, status, approval_request_id
            FROM governance.parameter_change""";

    private Change row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Change(rs.getObject(1, UUID.class), rs.getString(2), json.readValue(rs.getString(3), Object.class),
                rs.getObject(4, LocalDate.class), rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class));
    }
}
