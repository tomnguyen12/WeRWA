package vn.werwa.distribution.order;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.distribution.DistributionError;
import vn.werwa.distribution.DistributionProperties;
import vn.werwa.distribution.offering.OfferingService;
import vn.werwa.distribution.offering.OfferingService.Offering;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.issuance.api.SeriesCatalog;
import vn.werwa.onboarding.api.Investors;
import vn.werwa.onboarding.api.Wallets;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.security.OrgScope;
import vn.werwa.platform.security.Role;
import vn.werwa.platform.web.ApiException;
import vn.werwa.platform.web.ETags;
import vn.werwa.platform.web.Paging;

/**
 * Sổ lệnh (US-3.02; máy Lệnh cạnh 1–3). Nhà đầu tư đặt lệnh cho chính mình; thành viên tổ hợp nhập thay khách, lệnh ghi
 * người và tổ chức nhập. Mỗi lần đặt, sửa, hủy là một revision. Sau giờ đóng sổ mọi lệnh khóa.
 */
public class OrderService {

    private static final java.util.Set<Role> SEE_ALL = java.util.EnumSet.of(Role.SYNDICATE, Role.ISSUER, Role.OPERATOR);

    private final JdbcTemplate jdbc;
    private final OfferingService offerings;
    private final SeriesCatalog series;
    private final Investors investors;
    private final Wallets wallets;
    private final AuditLog audit;
    private final DistributionProperties props;
    private final Clock clock;

    public OrderService(JdbcTemplate jdbc, OfferingService offerings, SeriesCatalog series, Investors investors,
            Wallets wallets, AuditLog audit, DistributionProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.offerings = offerings;
        this.series = series;
        this.investors = investors;
        this.wallets = wallets;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    public record Order(
            UUID id, UUID offeringId, UUID investorOrgId, UUID walletId, int ratePpm, long quantity, String enteredBy,
            UUID enteredByOrgId, String status, int version) {}

    /** US-3.02-AC1, AC2. */
    @Transactional
    public Order place(UUID offeringId, UUID investorOrgId, UUID walletId, int ratePpm, long quantity) {
        CurrentUser user = CurrentUser.get();
        if (!user.roles().contains(Role.SYNDICATE)) {
            // Nhà đầu tư chỉ đặt lệnh cho tổ chức mình; tổ hợp nhập thay khách (AC2)
            OrgScope.requireOwnOrganization(investorOrgId);
        }
        Offering offering = offerings.requireBookOpen(offeringId);
        checkEligibility(offering, investorOrgId, walletId, ratePpm, quantity);
        UUID id = Ids.newId();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO distribution."order"
                    (id, offering_id, investor_org_id, wallet_id, rate_ppm, quantity, entered_by_user_id, entered_by_org_id,
                     eligibility_checked_at, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PLACED', ?, ?)
                """, id, offeringId, investorOrgId, walletId, ratePpm, quantity, user.subject(), user.organizationId(), now,
                now, now);
        revision(id, 1, ratePpm, quantity, "PLACE", user);
        audit.record("ORDER_PLACED", "order/" + id, Map.of("offeringId", offeringId.toString(), "investorOrgId",
                investorOrgId.toString(), "ratePpm", ratePpm, "quantity", quantity, "enteredByOrgId",
                user.organizationId().toString()));
        return get(id);
    }

    /** US-3.02-AC3, máy Lệnh cạnh 1: sửa trước giờ đóng sổ, kiểm lại điều kiện. */
    @Transactional
    public Order amend(UUID id, String ifMatch, Integer ratePpm, Long quantity) {
        Order o = lockOwned(id);
        ETags.requireMatch(ifMatch, o.version());
        if ("CANCELLED".equals(o.status())) {
            throw new ApiException(DistributionError.ORDER_CANCELLED);
        }
        Offering offering = offerings.requireBookOpen(o.offeringId());
        int rate = ratePpm == null ? o.ratePpm() : ratePpm;
        long qty = quantity == null ? o.quantity() : quantity;
        checkEligibility(offering, o.investorOrgId(), o.walletId(), rate, qty);
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                UPDATE distribution."order" SET rate_ppm = ?, quantity = ?, status = 'AMENDED', eligibility_checked_at = ?,
                    version = version + 1, updated_at = ? WHERE id = ?
                """, rate, qty, now, now, id);
        revision(id, nextRevision(id), rate, qty, "AMEND", CurrentUser.get());
        audit.record("ORDER_AMENDED", "order/" + id, Map.of("ratePpm", rate, "quantity", qty));
        return get(id);
    }

    /** Máy Lệnh cạnh 2. */
    @Transactional
    public Order cancel(UUID id, String ifMatch) {
        Order o = lockOwned(id);
        ETags.requireMatch(ifMatch, o.version());
        if ("CANCELLED".equals(o.status())) {
            throw new ApiException(DistributionError.ORDER_CANCELLED);
        }
        offerings.requireBookOpen(o.offeringId());
        jdbc.update("""
                UPDATE distribution."order" SET status = 'CANCELLED', version = version + 1, updated_at = ? WHERE id = ?
                """, Timestamp.from(clock.instant()), id);
        revision(id, nextRevision(id), o.ratePpm(), o.quantity(), "CANCEL", CurrentUser.get());
        audit.record("ORDER_CANCELLED", "order/" + id, Map.of("offeringId", o.offeringId().toString()));
        return get(id);
    }

    /** Xem lệnh: nhà đầu tư chỉ lệnh của mình; tổ hợp, UBND, Holco xem mọi lệnh. */
    public Order view(UUID id) {
        Order o = get(id);
        requireVisible(o);
        return o;
    }

    public Paging.Page<Order> list(UUID offeringId, String cursor, Integer limit) {
        offerings.get(offeringId);
        int size = Paging.limit(limit);
        UUID after = Paging.decodeCursor(cursor);
        CurrentUser user = CurrentUser.get();
        boolean all = user.hasAnyRole(SEE_ALL);
        StringBuilder sql = new StringBuilder(SELECT + " WHERE offering_id = ?");
        List<Object> args = new java.util.ArrayList<>(List.of(offeringId));
        if (!all) {
            sql.append(" AND investor_org_id = ?");
            args.add(user.organizationId());
        }
        if (after != null) {
            sql.append(" AND id > ?");
            args.add(after);
        }
        sql.append(" ORDER BY id LIMIT ?");
        args.add(size + 1);
        List<Order> rows = jdbc.query(sql.toString(), OrderService::row, args.toArray());
        return Paging.Page.of(rows, size, Order::id);
    }

    public Order get(UUID id) {
        List<Order> rows = jdbc.query(SELECT + " WHERE id = ?", OrderService::row, id);
        if (rows.isEmpty()) {
            throw new ApiException(DistributionError.ORDER_NOT_FOUND);
        }
        return rows.getFirst();
    }

    /** Lệnh còn hiệu lực của một đợt, khóa để lập danh sách phân bổ. */
    public Map<UUID, Order> activeOrders(UUID offeringId) {
        Map<UUID, Order> out = new java.util.LinkedHashMap<>();
        jdbc.query(SELECT + " WHERE offering_id = ? AND status <> 'CANCELLED' ORDER BY id", OrderService::row, offeringId)
                .forEach(o -> out.put(o.id(), o));
        return out;
    }

    private void checkEligibility(Offering offering, UUID investorOrgId, UUID walletId, int ratePpm, long quantity) {
        Instant now = clock.instant();
        String investorClass = investors.eligibleClass(investorOrgId, now)
                .orElseThrow(() -> new ApiException(DistributionError.KYC_NOT_VALID));
        List<String> allowed = series.find(offering.seriesId()).orElseThrow().investorClasses();
        if (!allowed.contains(investorClass)) {
            throw new ApiException(DistributionError.INVESTOR_CLASS_NOT_ELIGIBLE, investorClass);
        }
        Wallets.Wallet wallet = wallets.registered(walletId)
                .filter(w -> w.organizationId().equals(investorOrgId))
                .orElseThrow(() -> new ApiException(DistributionError.WALLET_NOT_REGISTERED));
        if (ratePpm < offering.rateGuidanceLowPpm() || ratePpm > offering.rateGuidanceHighPpm()) {
            throw new ApiException(DistributionError.RATE_OUTSIDE_GUIDANCE,
                    offering.rateGuidanceLowPpm() + "–" + offering.rateGuidanceHighPpm() + " ppm");
        }
        if (quantity <= 0 || quantity % props.orderQuantityStep() != 0) {
            throw new ApiException(DistributionError.QUANTITY_NOT_MULTIPLE, "bội số " + props.orderQuantityStep());
        }
        if (wallet.address() == null) {
            throw new ApiException(DistributionError.WALLET_NOT_REGISTERED);
        }
    }

    private Order lockOwned(UUID id) {
        List<Order> rows = jdbc.query(SELECT + " WHERE id = ? FOR UPDATE", OrderService::row, id);
        if (rows.isEmpty()) {
            throw new ApiException(DistributionError.ORDER_NOT_FOUND);
        }
        Order o = rows.getFirst();
        if (!CurrentUser.get().roles().contains(Role.SYNDICATE)) {
            OrgScope.requireOwnOrganization(o.investorOrgId());
        }
        return o;
    }

    private void requireVisible(Order o) {
        if (!CurrentUser.get().hasAnyRole(SEE_ALL)) {
            OrgScope.requireOwnOrganization(o.investorOrgId());
        }
    }

    private int nextRevision(UUID orderId) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(revision_no), 0) + 1 FROM distribution.order_revision WHERE order_id = ?",
                Integer.class, orderId);
    }

    private void revision(UUID orderId, int no, int ratePpm, long quantity, String action, CurrentUser user) {
        jdbc.update("""
                INSERT INTO distribution.order_revision
                    (id, order_id, revision_no, rate_ppm, quantity, action, actor_user_id, actor_org_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Ids.newId(), orderId, no, ratePpm, quantity, action, user.subject(), user.organizationId(),
                Timestamp.from(clock.instant()));
    }

    private static final String SELECT = """
            SELECT id, offering_id, investor_org_id, wallet_id, rate_ppm, quantity, entered_by_user_id, entered_by_org_id,
                   status, version
            FROM distribution."order\"""";

    private static Order row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Order(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getInt(5), rs.getLong(6), rs.getString(7), rs.getObject(8, UUID.class),
                rs.getString(9), rs.getInt(10));
    }
}
