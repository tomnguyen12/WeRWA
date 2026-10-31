package vn.werwa.distribution.payment;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.distribution.DistributionError;
import vn.werwa.distribution.allocation.AllocationMerkle;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.api.BusinessCalendar;
import vn.werwa.issuance.api.SeriesCatalog;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.web.ApiException;

/**
 * Theo dõi tiền của bộ điều phối ghi sổ (US-3.05-AC2; Q52–Q54): đối chiếu báo có với phân bổ, đánh dấu quá hạn, gom các
 * xác nhận khớp thành lô cho BIDV ký (ký ở B8b), và báo tiến độ.
 */
public class PaymentService {

    static final String MATCHED_EVENT = "werwa.distribution.payment.matched.v1";
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JdbcTemplate jdbc;
    private final SeriesCatalog series;
    private final BusinessCalendar calendar;
    private final AuditLog audit;
    private final Events events;
    private final int graceBusinessDays;
    private final Clock clock;

    public PaymentService(JdbcTemplate jdbc, SeriesCatalog series, BusinessCalendar calendar, AuditLog audit, Events events,
            int graceBusinessDays, Clock clock) {
        this.jdbc = jdbc;
        this.series = series;
        this.calendar = calendar;
        this.audit = audit;
        this.events = events;
        this.graceBusinessDays = graceBusinessDays;
        this.clock = clock;
    }

    public record Credit(
            String bankRef, String virtualAccount, long amountMinor, String currency, LocalDate valueDate, String payerName,
            UUID messageId) {}

    /** Q52: đối chiếu một báo có; chạy lại an toàn theo {@code bankRef}. Trả kết quả đối chiếu. */
    @Transactional
    public String match(Credit c) {
        List<String> done = jdbc.queryForList(
                "SELECT match_result FROM distribution.payment_confirmation WHERE bank_ref = ?", String.class, c.bankRef());
        if (!done.isEmpty()) {
            return done.getFirst();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.status, a.amount_due_minor, a.currency, l.status AS list_status
                FROM distribution.allocation a JOIN distribution.allocation_list l ON l.id = a.allocation_list_id
                WHERE a.virtual_account = ? FOR UPDATE OF a
                """, c.virtualAccount());
        UUID allocation = null;
        String result;
        String detail;
        if (rows.isEmpty()) {
            result = "UNKNOWN";
            detail = "tài khoản ảo không thuộc phân bổ nào";
        } else {
            Map<String, Object> a = rows.getFirst();
            allocation = (UUID) a.get("id");
            String status = (String) a.get("status");
            long due = ((Number) a.get("amount_due_minor")).longValue();
            if (!"SIGNED".equals(a.get("list_status"))) {
                result = "UNKNOWN";
                detail = "danh sách phân bổ chưa được UBND ký";
            } else if (!c.currency().equals(a.get("currency"))) {
                result = "UNKNOWN";
                detail = "đồng tiền " + c.currency() + " khác " + a.get("currency");
            } else if (!"AWAITING_PAYMENT".equals(status) && !"OVERDUE".equals(status)) {
                result = "OVER";
                detail = "phân bổ đã có tiền (" + status + ")";
            } else if (c.amountMinor() == due) {
                result = "MATCHED";
                detail = null;
            } else {
                result = c.amountMinor() < due ? "SHORT" : "OVER";
                detail = "nhận " + c.amountMinor() + ", phải trả " + due;
            }
        }
        UUID id = Ids.newId();
        jdbc.update("""
                INSERT INTO distribution.payment_confirmation
                    (id, bank_ref, allocation_id, virtual_account, payer_name, amount_minor, currency, value_date,
                     inbound_message_id, match_result, match_detail, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, c.bankRef(), allocation, c.virtualAccount(), c.payerName(), c.amountMinor(), c.currency(),
                c.valueDate(), c.messageId(), result, detail, Timestamp.from(clock.instant()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bankRef", c.bankRef());
        data.put("amountMinor", Long.toString(c.amountMinor()));
        data.put("currency", c.currency());
        data.put("matchResult", result);
        if (allocation != null) {
            data.put("allocationId", allocation.toString());
        }
        if ("MATCHED".equals(result)) {
            jdbc.update("UPDATE distribution.allocation SET status = 'PAID' WHERE id = ?", allocation);
            events.publish(MATCHED_EVENT, "allocation/" + allocation, data);
        }
        audit.record("PAYMENT_" + result, "payment-confirmation/" + id, data);
        return result;
    }

    /** Q54: hết ngày làm việc của hạn trả (cộng ân hạn) mà chưa có tiền thì quá hạn. Trả số phân bổ vừa quá hạn. */
    @Transactional
    public int markOverdue(Instant now) {
        LocalDate today = now.atZone(VN).toLocalDate();
        List<Map<String, Object>> waiting = jdbc.queryForList("""
                SELECT id, payment_due_date FROM distribution.allocation
                WHERE status = 'AWAITING_PAYMENT' AND payment_due_date IS NOT NULL AND payment_due_date < ?
                """, today);
        int count = 0;
        for (Map<String, Object> a : waiting) {
            LocalDate deadline = deadline(((java.sql.Date) a.get("payment_due_date")).toLocalDate());
            if (today.isAfter(deadline)) {
                jdbc.update("UPDATE distribution.allocation SET status = 'OVERDUE' WHERE id = ? AND status = 'AWAITING_PAYMENT'",
                        a.get("id"));
                audit.record("ALLOCATION_OVERDUE", "allocation/" + a.get("id"), Map.of("deadline", deadline.toString()));
                count++;
            }
        }
        return count;
    }

    LocalDate deadline(LocalDate due) {
        LocalDate d = calendar.following(due, List.of("VN"));
        for (int i = 0; i < graceBusinessDays; i++) {
            d = calendar.following(d.plusDays(1), List.of("VN"));
        }
        return d;
    }

    /** Q53: gom các xác nhận MATCHED chưa vào lô, mỗi đợt chào bán một lô. Trả mã các lô vừa tạo. */
    @Transactional
    public List<UUID> buildBatches() {
        List<UUID> created = new ArrayList<>();
        List<UUID> offerings = jdbc.queryForList("""
                SELECT DISTINCT l.offering_id FROM distribution.payment_confirmation p
                JOIN distribution.allocation a ON a.id = p.allocation_id
                JOIN distribution.allocation_list l ON l.id = a.allocation_list_id
                WHERE p.match_result = 'MATCHED' AND p.payment_batch_id IS NULL
                """, UUID.class);
        for (UUID offering : offerings) {
            jdbc.queryForList("SELECT id FROM distribution.offering WHERE id = ? FOR UPDATE", UUID.class, offering);
            UUID seriesId = jdbc.queryForObject("SELECT series_id FROM distribution.offering WHERE id = ?", UUID.class, offering);
            String controller = series.find(seriesId).orElseThrow().controllerAddress();
            List<Map<String, Object>> items = jdbc.queryForList("""
                    SELECT p.id, p.allocation_id, p.amount_minor, p.currency, p.bank_ref FROM distribution.payment_confirmation p
                    JOIN distribution.allocation a ON a.id = p.allocation_id
                    JOIN distribution.allocation_list l ON l.id = a.allocation_list_id
                    WHERE l.offering_id = ? AND p.match_result = 'MATCHED' AND p.payment_batch_id IS NULL
                    ORDER BY p.allocation_id FOR UPDATE OF p
                    """, offering);
            if (items.isEmpty()) {
                continue;
            }
            List<byte[]> leaves = new ArrayList<>();
            long total = 0;
            for (Map<String, Object> it : items) {
                long amount = ((Number) it.get("amount_minor")).longValue();
                total = Math.addExact(total, amount);
                leaves.add(AllocationMerkle.paymentLeaf(controller, (UUID) it.get("allocation_id"), amount,
                        (String) it.get("bank_ref")));
            }
            String root = AllocationMerkle.hex(AllocationMerkle.root(leaves));
            int batchNo = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(batch_no), 0) + 1 FROM distribution.payment_batch WHERE offering_id = ?",
                    Integer.class, offering);
            UUID batch = Ids.newId();
            jdbc.update("""
                    INSERT INTO distribution.payment_batch
                        (id, offering_id, batch_no, item_count, total_amount_minor, currency, merkle_root, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'BUILT', ?)
                    """, batch, offering, batchNo, items.size(), total, items.getFirst().get("currency"), root,
                    Timestamp.from(clock.instant()));
            for (int i = 0; i < items.size(); i++) {
                jdbc.update("""
                        UPDATE distribution.payment_confirmation SET payment_batch_id = ?, payment_leaf_hash = ? WHERE id = ?
                        """, batch, AllocationMerkle.hex(leaves.get(i)), items.get(i).get("id"));
            }
            audit.record("PAYMENT_BATCH_BUILT", "payment-batch/" + batch, Map.of("offeringId", offering.toString(),
                    "batchNo", batchNo, "itemCount", items.size(), "totalAmountMinor", Long.toString(total),
                    "merkleRoot", root));
            created.add(batch);
        }
        return created;
    }

    public enum Step { WAIT_LIST_SIGNED, TRACK_PAYMENTS, WAIT_BIDV_BATCH, MINTING, WAIT_VSDC, DONE }

    public record Progress(
            UUID seriesId, Step step, long allocated, long paid, long minted, long overdue, List<String> alerts) {}

    /** Tiến độ ghi sổ của một series (US-3.05, {@code GET /issuance-progress}). */
    public Progress progress(UUID seriesId) {
        if (series.find(seriesId).isEmpty()) {
            throw new ApiException(DistributionError.SERIES_NOT_FOUND);
        }
        List<Map<String, Object>> lists = jdbc.queryForList("""
                SELECT l.id, l.status FROM distribution.allocation_list l
                JOIN distribution.offering o ON o.id = l.offering_id
                WHERE o.series_id = ? AND l.status IN ('SUBMITTED', 'SIGNED') ORDER BY l.list_version DESC LIMIT 1
                """, seriesId);
        if (lists.isEmpty() || !"SIGNED".equals(lists.getFirst().get("status"))) {
            return new Progress(seriesId, Step.WAIT_LIST_SIGNED, 0, 0, 0, 0, List.of());
        }
        UUID list = (UUID) lists.getFirst().get("id");
        Map<String, Long> qty = new LinkedHashMap<>();
        jdbc.query("SELECT status, SUM(quantity) FROM distribution.allocation WHERE allocation_list_id = ? GROUP BY status",
                rs -> {
                    qty.put(rs.getString(1), rs.getLong(2));
                }, list);
        long total = qty.values().stream().mapToLong(Long::longValue).sum();
        long awaiting = qty.getOrDefault("AWAITING_PAYMENT", 0L);
        long overdue = qty.getOrDefault("OVERDUE", 0L);
        long minted = qty.getOrDefault("MINTED", 0L) + qty.getOrDefault("CONFIRMED", 0L);
        long paid = qty.getOrDefault("PAID", 0L) + minted;
        long confirmed = qty.getOrDefault("CONFIRMED", 0L);
        Integer unbatched = jdbc.queryForObject("""
                SELECT count(*) FROM distribution.payment_confirmation p JOIN distribution.allocation a ON a.id = p.allocation_id
                WHERE a.allocation_list_id = ? AND p.match_result = 'MATCHED'
                  AND (p.payment_batch_id IS NULL OR p.payment_batch_id IN
                       (SELECT id FROM distribution.payment_batch WHERE status <> 'SIGNED'))
                """, Integer.class, list);
        Step step;
        if (confirmed == total && total > 0) {
            step = Step.DONE;
        } else if (awaiting > 0) {
            step = Step.TRACK_PAYMENTS;
        } else if (unbatched > 0) {
            step = Step.WAIT_BIDV_BATCH;
        } else if (paid > minted) {
            step = Step.MINTING;
        } else {
            step = Step.WAIT_VSDC;
        }
        List<String> alerts = new ArrayList<>();
        jdbc.query("""
                SELECT p.match_result, p.bank_ref, p.match_detail FROM distribution.payment_confirmation p
                JOIN distribution.allocation a ON a.id = p.allocation_id
                WHERE a.allocation_list_id = ? AND p.match_result <> 'MATCHED' ORDER BY p.created_at
                """, rs -> {
                    alerts.add(rs.getString(1) + " " + rs.getString(2) + ": " + rs.getString(3));
                }, list);
        jdbc.query("SELECT id FROM distribution.allocation WHERE allocation_list_id = ? AND status = 'OVERDUE' ORDER BY id",
                rs -> {
                    alerts.add("OVERDUE allocation " + rs.getString(1));
                }, list);
        return new Progress(seriesId, step, total, paid, minted, overdue, alerts);
    }
}
