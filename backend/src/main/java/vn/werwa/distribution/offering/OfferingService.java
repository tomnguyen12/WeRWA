package vn.werwa.distribution.offering;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.distribution.DistributionError;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.issuance.api.SeriesCatalog;
import vn.werwa.issuance.api.SeriesCatalog.SeriesInfo;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.web.ApiException;

/** Đợt chào bán và giờ sổ lệnh (US-3.01; Q46). */
public class OfferingService {

    static final String CLOSED_EVENT = "werwa.distribution.offering.closed.v1";

    private final JdbcTemplate jdbc;
    private final SeriesCatalog series;
    private final AuditLog audit;
    private final Events events;
    private final Clock clock;

    public OfferingService(JdbcTemplate jdbc, SeriesCatalog series, AuditLog audit, Events events, Clock clock) {
        this.jdbc = jdbc;
        this.series = series;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    public record Offering(
            UUID id, UUID seriesId, String method, int rateGuidanceLowPpm, int rateGuidanceHighPpm, long targetQuantity,
            Instant bookOpenAt, Instant bookCloseAt, UUID announcementDocumentId, String status, int version) {}

    /** US-3.01-AC1, AC3. */
    @Transactional
    public Offering create(UUID seriesId, String method, Integer low, Integer high, long targetQuantity, Instant openAt,
            Instant closeAt, UUID announcementDocumentId) {
        SeriesInfo s = series.find(seriesId).orElseThrow(() -> new ApiException(DistributionError.SERIES_NOT_FOUND));
        if (!"READY".equals(s.status()) || s.isin() == null) {
            throw new ApiException(DistributionError.SERIES_ISIN_REQUIRED, "series đang " + s.status());
        }
        if (low == null || high == null || low > high) {
            throw new ApiException(DistributionError.OFFERING_INVALID, "cần rateGuidanceLowPpm ≤ rateGuidanceHighPpm");
        }
        if (low < s.rateFloorPpm() || high > s.rateCapPpm()) {
            throw new ApiException(DistributionError.RATE_OUT_OF_RANGE,
                    "khung Đề án " + s.rateFloorPpm() + "–" + s.rateCapPpm() + " ppm");
        }
        if (targetQuantity <= 0 || targetQuantity > s.maxQuantity()) {
            throw new ApiException(DistributionError.OFFERING_INVALID, "targetQuantity phải trong 1–" + s.maxQuantity());
        }
        if (!closeAt.isAfter(openAt)) {
            throw new ApiException(DistributionError.OFFERING_INVALID, "bookCloseAt phải sau bookOpenAt");
        }
        UUID id = Ids.newId();
        CurrentUser user = CurrentUser.get();
        Timestamp now = Timestamp.from(clock.instant());
        try {
            jdbc.update("""
                    INSERT INTO distribution.offering
                        (id, series_id, method, rate_guidance_low_ppm, rate_guidance_high_ppm, target_quantity, book_open_at,
                         book_close_at, announcement_document_id, status, owner_org_id, created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?)
                    """, id, seriesId, method, low, high, targetQuantity, Timestamp.from(openAt), Timestamp.from(closeAt),
                    announcementDocumentId, user.organizationId(), user.subject(), now, now);
        } catch (DuplicateKeyException e) {
            throw new ApiException(DistributionError.OFFERING_EXISTS);
        }
        audit.record("OFFERING_CREATED", "offering/" + id, Map.of("seriesId", seriesId.toString(),
                "rateGuidance", low + "–" + high, "targetQuantity", targetQuantity));
        return get(id);
    }

    /** US-3.01-AC2: mở sổ; series ngoài chuỗi sang Đang chào bán. */
    @Transactional
    public Offering open(UUID id) {
        Offering o = lock(id);
        if (!"DRAFT".equals(o.status())) {
            throw new ApiException(DistributionError.OFFERING_NOT_DRAFT, "đang " + o.status());
        }
        series.markOffering(o.seriesId());
        setStatus(id, "OPEN");
        audit.record("BOOK_OPENED", "offering/" + id, Map.of("seriesId", o.seriesId().toString()));
        return get(id);
    }

    /** Máy Lệnh cạnh 3: đóng sổ, mọi lệnh khóa; series sang Đã đóng sổ. */
    @Transactional
    public Offering close(UUID id) {
        Offering o = lock(id);
        if (!"OPEN".equals(o.status())) {
            throw new ApiException(DistributionError.BOOK_NOT_OPEN, "đang " + o.status());
        }
        series.markBookClosed(o.seriesId());
        setStatus(id, "CLOSED");
        Integer orders = jdbc.queryForObject(
                "SELECT count(*) FROM distribution.\"order\" WHERE offering_id = ? AND status <> 'CANCELLED'", Integer.class,
                id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("offeringId", id.toString());
        data.put("orderCount", orders);
        data.put("seriesId", o.seriesId().toString());
        events.publish(CLOSED_EVENT, "offering/" + id, data);
        audit.record("BOOK_CLOSED", "offering/" + id, data);
        return get(id);
    }

    /** Q46: mở, đóng sổ theo giờ. Mỗi đợt chạy trong giao dịch riêng của {@link #open} hoặc {@link #close}. */
    public List<UUID> dueToOpen(Instant now) {
        return jdbc.queryForList("SELECT id FROM distribution.offering WHERE status = 'DRAFT' AND book_open_at <= ?",
                UUID.class, Timestamp.from(now));
    }

    public List<UUID> dueToClose(Instant now) {
        return jdbc.queryForList("SELECT id FROM distribution.offering WHERE status = 'OPEN' AND book_close_at <= ?",
                UUID.class, Timestamp.from(now));
    }

    /** Sổ còn nhận lệnh: OPEN và chưa tới giờ đóng (bộ lập lịch có thể trễ tới một phút). */
    public Offering requireBookOpen(UUID id) {
        Offering o = get(id);
        if (!"OPEN".equals(o.status()) || !clock.instant().isBefore(o.bookCloseAt())) {
            throw new ApiException(DistributionError.BOOK_NOT_OPEN, "đang " + o.status());
        }
        return o;
    }

    @Transactional
    public void markAllocated(UUID id) {
        setStatus(id, "ALLOCATED");
    }

    public Offering get(UUID id) {
        List<Offering> rows = jdbc.query(SELECT + " WHERE id = ?", OfferingService::row, id);
        if (rows.isEmpty()) {
            throw new ApiException(DistributionError.OFFERING_NOT_FOUND);
        }
        return rows.getFirst();
    }

    private Offering lock(UUID id) {
        List<Offering> rows = jdbc.query(SELECT + " WHERE id = ? FOR UPDATE", OfferingService::row, id);
        if (rows.isEmpty()) {
            throw new ApiException(DistributionError.OFFERING_NOT_FOUND);
        }
        return rows.getFirst();
    }

    private void setStatus(UUID id, String status) {
        jdbc.update("UPDATE distribution.offering SET status = ?, version = version + 1, updated_at = ? WHERE id = ?",
                status, Timestamp.from(clock.instant()), id);
    }

    private static final String SELECT = """
            SELECT id, series_id, method, rate_guidance_low_ppm, rate_guidance_high_ppm, target_quantity, book_open_at,
                   book_close_at, announcement_document_id, status, version
            FROM distribution.offering""";

    private static Offering row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Offering(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4),
                rs.getInt(5), rs.getLong(6), rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant(),
                rs.getObject(9, UUID.class), rs.getString(10), rs.getInt(11));
    }
}
