package vn.werwa.governance.supervision;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.api.Notifications;
import vn.werwa.governance.api.OperationalAlerts;
import vn.werwa.governance.api.Parameters;
import vn.werwa.governance.approval.GovernanceError;
import vn.werwa.onchain.api.IndexedEvents;
import vn.werwa.onchain.api.IndexedEvents.BlockHeader;
import vn.werwa.onchain.api.IndexedEvents.ChainEvent;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.security.Role;
import vn.werwa.platform.web.ApiException;
import vn.werwa.platform.web.Paging;

/**
 * Công cụ giám sát cho Cơ quan giám sát VIFC (US-4.04; Q90–Q96): dòng giao dịch trên chuỗi, độ trễ của indexer, mã băm
 * khối VSDC gửi để đối chiếu với O1. Ứng dụng không đọc validator; VSDC tự đọc V4 (Q95).
 */
public class SupervisionService {

    public static final String MAX_LAG_KEY = "governance.supervision-max-lag-seconds";
    static final long DEFAULT_MAX_LAG_SECONDS = 10;
    /** Khối 2 giây (ADR-01), dùng khi O1 không trả được giờ của khối đã đọc. */
    static final long BLOCK_SECONDS = 2;

    private final JdbcTemplate jdbc;
    private final IndexedEvents events;
    private final Parameters parameters;
    private final OperationalAlerts alerts;
    private final Notifications notifications;
    private final AuditLog audit;
    private final Clock clock;

    public SupervisionService(JdbcTemplate jdbc, IndexedEvents events, Parameters parameters, OperationalAlerts alerts,
            Notifications notifications, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.parameters = parameters;
        this.alerts = alerts;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    public record Status(
            long headBlock, long indexedBlock, long lagSeconds, long maxLagSeconds, boolean withinLimit, Instant checkedAt) {}

    public record Attestation(
            UUID id, long blockNumber, String blockHash, String o1BlockHash, String status, Instant attestedAt) {}

    /** Q92: mới nhất trước; con trỏ là vị trí (khối, log) của dòng cuối trang. */
    public Paging.Page<ChainEvent> transactions(Long afterBlock, UUID seriesId, String cursor, Integer limit) {
        int size = Paging.limit(limit);
        List<ChainEvent> rows = events.list(afterBlock, seriesId, Paging.decodeSeqCursor(cursor), size + 1);
        if (rows.size() <= size) {
            return new Paging.Page<>(List.copyOf(rows), null);
        }
        List<ChainEvent> items = List.copyOf(rows.subList(0, size));
        return new Paging.Page<>(items, Paging.encodeSeqCursor(items.getLast().position()));
    }

    /** Q93: độ trễ = giờ khối đầu chuỗi trừ giờ khối đã đọc, trên O1. */
    public Status status() {
        long head = events.headBlock();
        long indexed = events.indexedBlock();
        long lag = 0;
        if (indexed < head) {
            Optional<BlockHeader> headHeader = events.block(head);
            Optional<BlockHeader> indexedHeader = indexed < 0 ? Optional.empty() : events.block(indexed);
            lag = headHeader.isPresent() && indexedHeader.isPresent()
                    ? headHeader.get().time().getEpochSecond() - indexedHeader.get().time().getEpochSecond()
                    : (head - indexed) * BLOCK_SECONDS;
        }
        long max = parameters.integer(MAX_LAG_KEY, DEFAULT_MAX_LAG_SECONDS);
        long lagSeconds = Math.max(0, lag);
        return new Status(head, indexed, lagSeconds, max, lagSeconds <= max, clock.instant());
    }

    /** Q93: tác vụ định kỳ; vượt ngưỡng thì tạo cảnh báo vận hành (một cảnh báo mở cho tới khi được xử lý). */
    public void monitor() {
        Status s = status();
        if (!s.withinLimit()) {
            alerts.raise("indexer-lag", "IndexerLagHigh", "critical", "Indexer chậm " + s.lagSeconds()
                    + " giây so với đầu chuỗi (ngưỡng " + s.maxLagSeconds() + " giây); khối " + s.indexedBlock() + "/"
                    + s.headBlock());
        }
    }

    /** Q95: VSDC gửi mã băm khối đọc từ V4; mỗi khối một lần. So ngay nếu O1 đã có khối đó. */
    @Transactional
    public Attestation attest(long blockNumber, String blockHash) {
        CurrentUser user = CurrentUser.get();
        String hash = blockHash.toLowerCase(Locale.ROOT);
        Optional<BlockHeader> o1;
        try {
            o1 = events.block(blockNumber);
        } catch (RuntimeException e) {
            // O1 không trả lời: ghi chờ, R7 so lại
            o1 = Optional.empty();
        }
        String status = o1.map(h -> h.hash().equals(hash) ? "MATCHED" : "MISMATCHED").orElse("PENDING");
        UUID id = Ids.newId();
        Timestamp now = Timestamp.from(clock.instant());
        try {
            jdbc.update("""
                    INSERT INTO governance.block_hash_attestation
                        (id, chain_id, block_number, block_hash, o1_block_hash, status, attested_by, attested_by_org,
                         attested_at, checked_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, events.chainId(), blockNumber, hash, o1.map(BlockHeader::hash).orElse(null), status,
                    user.subject(), user.organizationId(), now, o1.isPresent() ? now : null);
        } catch (DuplicateKeyException e) {
            throw new ApiException(GovernanceError.BLOCK_HASH_ALREADY_ATTESTED, "khối " + blockNumber);
        }
        audit.record("BLOCK_HASH_ATTESTED", "block/" + blockNumber, Map.of("blockHash", hash, "status", status));
        if ("MISMATCHED".equals(status)) {
            mismatchNotice(blockNumber);
        }
        return get(id);
    }

    /** R7: so lại các mã băm còn chờ O1; trả các khối vừa chuyển sang lệch. */
    @Transactional
    public List<Long> recheckPending() {
        List<Long> mismatched = new java.util.ArrayList<>();
        for (Attestation a : jdbc.query(SELECT + " WHERE status = 'PENDING' AND chain_id = ? ORDER BY block_number",
                SupervisionService::row, events.chainId())) {
            Optional<BlockHeader> o1;
            try {
                o1 = events.block(a.blockNumber());
            } catch (RuntimeException e) {
                // O1 không trả lời: để lần sau
                return mismatched;
            }
            if (o1.isEmpty()) {
                continue;
            }
            String status = o1.get().hash().equals(a.blockHash()) ? "MATCHED" : "MISMATCHED";
            jdbc.update("""
                    UPDATE governance.block_hash_attestation SET o1_block_hash = ?, status = ?, checked_at = ? WHERE id = ?
                    """, o1.get().hash(), status, Timestamp.from(clock.instant()), a.id());
            if ("MISMATCHED".equals(status)) {
                mismatched.add(a.blockNumber());
            }
        }
        return mismatched;
    }

    /** Lệch chưa đưa vào lần đối soát nào; đánh dấu đã đưa. */
    @Transactional
    public List<Attestation> takeUnreportedMismatches() {
        List<Attestation> out = jdbc.query(SELECT + " WHERE status = 'MISMATCHED' AND reported_at IS NULL AND chain_id = ?"
                + " ORDER BY block_number", SupervisionService::row, events.chainId());
        Timestamp now = Timestamp.from(clock.instant());
        out.forEach(a -> jdbc.update("UPDATE governance.block_hash_attestation SET reported_at = ? WHERE id = ?", now,
                a.id()));
        return out;
    }

    /** Lần gửi mã băm gần nhất của VSDC; rỗng nếu chưa có. */
    public Optional<Instant> lastAttestedAt() {
        Timestamp t = jdbc.queryForObject(
                "SELECT max(attested_at) FROM governance.block_hash_attestation WHERE chain_id = ?", Timestamp.class,
                events.chainId());
        return Optional.ofNullable(t).map(Timestamp::toInstant);
    }

    public Attestation get(UUID id) {
        return jdbc.query(SELECT + " WHERE id = ?", SupervisionService::row, id).getFirst();
    }

    private void mismatchNotice(long blockNumber) {
        notifications.notify(new Notifications.Notice(Notifications.Kind.RECONCILIATION_BREAK,
                Notifications.Severity.CRITICAL, "Mã băm khối " + blockNumber + " của VSDC khác O1",
                "VSDC gửi mã băm đọc từ V4 khác mã băm trên O1 cùng số khối", "block/" + blockNumber,
                List.of(Role.SUPERVISOR, Role.OPERATOR), null));
    }

    private static final String SELECT = """
            SELECT id, block_number, block_hash, o1_block_hash, status, attested_at
            FROM governance.block_hash_attestation""";

    private static Attestation row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Attestation(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getTimestamp(6).toInstant());
    }
}
