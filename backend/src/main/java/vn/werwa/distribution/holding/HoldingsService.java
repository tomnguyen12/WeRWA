package vn.werwa.distribution.holding;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.governance.api.OperationalAlerts;
import vn.werwa.issuance.api.SeriesCatalog;
import vn.werwa.onboarding.api.Wallets;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.security.Role;
import vn.werwa.platform.web.Paging;

/**
 * Sổ người sở hữu (US-3.07; Q83–Q89). Bộ chiếu ghi biến động và số dư từ sự kiện token mà indexer đọc trên O1; số dư là
 * tổng biến động nên sự kiện đến sai thứ tự hay lặp lại vẫn ra đúng. Chỉ bộ chiếu ghi; dựng lại được từ chuỗi.
 */
public class HoldingsService implements vn.werwa.platform.registry.HoldingsSummaries {

    static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    /** Loại hợp đồng của token series trong registry của indexer. */
    public static final String TOKEN_KIND = "series.token";
    private static final Set<Role> SEE_ALL = EnumSet.of(Role.REGISTRAR, Role.OPERATOR, Role.SUPERVISOR);
    /** Trạng thái series mà {@code TransferGateModule} cho chuyển nhượng (Q87). */
    private static final Set<String> TRANSFERABLE = Set.of("ACTIVE", "DEFAULTED", "MATURED");

    private final JdbcTemplate jdbc;
    private final SeriesCatalog series;
    private final Wallets wallets;
    private final OperationalAlerts alerts;
    private final Clock clock;

    public HoldingsService(JdbcTemplate jdbc, SeriesCatalog series, Wallets wallets, OperationalAlerts alerts,
            Clock clock) {
        this.jdbc = jdbc;
        this.series = series;
        this.wallets = wallets;
        this.alerts = alerts;
        this.clock = clock;
    }

    /** Một log token đã giải mã, lấy từ {@code werwa.onchain.event.indexed.v1} (Q84). */
    public record IndexedLog(
            long chainId, String contract, String contractKind, String eventName, long block, int logIndex,
            String txHash, Instant blockTime, Map<String, Object> args) {}

    public record Holding(
            UUID id, UUID seriesId, UUID walletId, String walletAddress, String tokenAddress, long chainId,
            long balance, long frozen, long locked) {}

    public record Movement(
            UUID id, UUID seriesId, UUID walletId, String kind, long quantity, String counterparty, long block,
            int logIndex, String txHash, Instant blockTime, long position) {}

    /** Số dư theo đúng một số khối chung cho mọi dòng (Q87). */
    public record HoldingsPage(List<Holding> items, String nextCursor, long asOfBlock) {}

    /** Q85: ghi biến động từ một log token; log của hợp đồng khác bỏ qua. */
    @Transactional
    public void project(IndexedLog log) {
        if (!TOKEN_KIND.equals(log.contractKind())) {
            return;
        }
        String token = lower(log.contract());
        switch (log.eventName()) {
            case "Transfer" -> {
                String from = address(log.args().get("from"));
                String to = address(log.args().get("to"));
                long value = quantity(log.args().get("value"));
                if (value == 0 || from.equals(to)) {
                    return;
                }
                UUID seriesId = seriesOf(token);
                if (ZERO_ADDRESS.equals(from)) {
                    record(log, seriesId, token, to, "MINT", value, null);
                } else if (ZERO_ADDRESS.equals(to)) {
                    record(log, seriesId, token, from, "BURN", value, null);
                } else {
                    record(log, seriesId, token, from, "TRANSFER_OUT", value, to);
                    record(log, seriesId, token, to, "TRANSFER_IN", value, from);
                }
            }
            case "TokensFrozen", "TokensUnfrozen" -> {
                long value = quantity(log.args().get("_amount"));
                if (value == 0) {
                    return;
                }
                record(log, seriesOf(token), token, address(log.args().get("_userAddress")),
                        "TokensFrozen".equals(log.eventName()) ? "FREEZE" : "UNFREEZE", value, null);
            }
            case "AddressFrozen" -> {
                // Q85: WeRWA phong tỏa theo số lượng, không khóa cả ví; gặp là bất thường cần người xem
                String wallet = address(log.args().get("_userAddress"));
                alerts.raise("address-frozen:" + token + ":" + wallet + ":" + log.block(), "TokenAddressFrozen", "warning",
                        "Token " + token + " khóa cả ví " + wallet + " ở khối " + log.block() + " (tx " + log.txHash()
                                + "); WeRWA không dùng AddressFrozen");
            }
            default -> {
                // Sự kiện khác của token không đổi số dư
            }
        }
    }

    /** Số dư (US-3.07-AC2; Q87, Q88): nhà đầu tư, lưu ký chỉ thấy ví của tổ chức mình. */
    public HoldingsPage list(UUID seriesId, UUID walletId, String cursor, Integer limit) {
        int size = Paging.limit(limit);
        UUID after = Paging.decodeCursor(cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT id, series_id, wallet_id, wallet_address, token_address, chain_id, balance, frozen
                FROM distribution.holding_view WHERE wallet_id IS NOT NULL""");
        List<Object> args = new ArrayList<>();
        scope(sql, args);
        if (seriesId != null) {
            sql.append(" AND series_id = ?");
            args.add(seriesId);
        }
        if (walletId != null) {
            sql.append(" AND wallet_id = ?");
            args.add(walletId);
        }
        if (after != null) {
            sql.append(" AND id > ?");
            args.add(after);
        }
        sql.append(" ORDER BY id LIMIT ?");
        args.add(size + 1);
        Map<UUID, String> statuses = new HashMap<>();
        List<Holding> rows = jdbc.query(sql.toString(), (rs, i) -> {
            UUID s = rs.getObject(2, UUID.class);
            long balance = Math.max(0, rs.getLong(7));
            String status = statuses.computeIfAbsent(s, k -> series.find(k).map(SeriesCatalog.SeriesInfo::status).orElse(""));
            return new Holding(rs.getObject(1, UUID.class), s, rs.getObject(3, UUID.class), rs.getString(4),
                    rs.getString(5), rs.getLong(6), balance, Math.max(0, rs.getLong(8)),
                    TRANSFERABLE.contains(status) ? 0 : balance);
        }, args.toArray());
        Paging.Page<Holding> page = Paging.Page.of(rows, size, Holding::id);
        return new HoldingsPage(page.items(), page.nextCursor(), asOfBlock());
    }

    /** Sao kê (US-3.07-AC3; Q88): mới nhất trước, lọc theo giờ khối ({@code from} bao gồm, {@code to} không). */
    public Paging.Page<Movement> movements(UUID walletId, UUID seriesId, Instant from, Instant to, String cursor,
            Integer limit) {
        int size = Paging.limit(limit);
        Long before = Paging.decodeSeqCursor(cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT id, series_id, wallet_id, kind, quantity, counterparty_address, block_number, log_index, tx_hash,
                       block_time, position
                FROM distribution.holding_movement WHERE wallet_id IS NOT NULL""");
        List<Object> args = new ArrayList<>();
        scope(sql, args);
        if (walletId != null) {
            sql.append(" AND wallet_id = ?");
            args.add(walletId);
        }
        if (seriesId != null) {
            sql.append(" AND series_id = ?");
            args.add(seriesId);
        }
        if (from != null) {
            sql.append(" AND block_time >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND block_time < ?");
            args.add(Timestamp.from(to));
        }
        if (before != null) {
            sql.append(" AND position < ?");
            args.add(before);
        }
        sql.append(" ORDER BY position DESC, wallet_address LIMIT ?");
        args.add(size + 1);
        List<Movement> rows = jdbc.query(sql.toString(), (rs, i) -> new Movement(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), rs.getLong(5), rs.getString(6),
                rs.getLong(7), rs.getInt(8), rs.getString(9), rs.getTimestamp(10).toInstant(), rs.getLong(11)),
                args.toArray());
        if (rows.size() <= size) {
            return new Paging.Page<>(List.copyOf(rows), null);
        }
        List<Movement> items = List.copyOf(rows.subList(0, size));
        return new Paging.Page<>(items, Paging.encodeSeqCursor(items.getLast().position()));
    }

    /** Q91: tóm tắt sổ cho công cụ giám sát, gồm cả địa chỉ chưa có ví trong onboarding. */
    @Override
    public Summary summary(UUID seriesId) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE balance > 0), COALESCE(sum(GREATEST(balance, 0)), 0),
                       COALESCE(sum(GREATEST(frozen, 0)), 0)
                FROM distribution.holding_view WHERE series_id = ?
                """, (rs, i) -> new Summary(rs.getInt(1), rs.getLong(2), rs.getLong(3), asOfBlock()), seriesId);
    }

    /** Khối cuối bộ chiếu đã ghi (Q87); 0 khi chưa có biến động nào. */
    public long asOfBlock() {
        Long block = jdbc.queryForObject("SELECT max(block_number) FROM distribution.holding_movement", Long.class);
        return block == null ? 0 : block;
    }

    /** Số dư mọi ví trong bảng đọc, kể cả địa chỉ chưa có trong onboarding, cho đối soát R5 (Q86, Q89). */
    public List<Holding> allForReconciliation() {
        return jdbc.query("""
                SELECT id, series_id, wallet_id, wallet_address, token_address, chain_id, balance, frozen
                FROM distribution.holding_view ORDER BY series_id, wallet_address
                """, (rs, i) -> new Holding(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                0));
    }

    private void scope(StringBuilder sql, List<Object> args) {
        CurrentUser user = CurrentUser.get();
        if (!user.hasAnyRole(SEE_ALL)) {
            sql.append(" AND wallet_org_id = ?");
            args.add(user.organizationId());
        }
    }

    private void record(IndexedLog log, UUID seriesId, String token, String wallet, String kind, long quantity,
            String counterparty) {
        Optional<Wallets.Wallet> known = wallets.byAddress(log.chainId(), wallet);
        UUID walletId = known.map(Wallets.Wallet::id).orElse(null);
        UUID orgId = known.map(Wallets.Wallet::organizationId).orElse(null);
        Timestamp now = Timestamp.from(clock.instant());
        int inserted = jdbc.update("""
                INSERT INTO distribution.holding_movement
                    (id, chain_id, series_id, token_address, wallet_address, wallet_id, wallet_org_id, kind, quantity,
                     counterparty_address, block_number, log_index, tx_hash, block_time, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (chain_id, block_number, log_index, wallet_address) DO NOTHING
                """, Ids.newId(), log.chainId(), seriesId, token, wallet, walletId, orgId, kind, quantity, counterparty,
                log.block(), log.logIndex(), lower(log.txHash()), Timestamp.from(log.blockTime()), now);
        if (inserted == 0) {
            return;
        }
        long balanceDelta = switch (kind) {
            case "MINT", "TRANSFER_IN" -> quantity;
            case "BURN", "TRANSFER_OUT" -> -quantity;
            default -> 0;
        };
        long frozenDelta = switch (kind) {
            case "FREEZE" -> quantity;
            case "UNFREEZE" -> -quantity;
            default -> 0;
        };
        jdbc.update("""
                INSERT INTO distribution.holding_view
                    (series_id, wallet_address, id, chain_id, token_address, wallet_id, wallet_org_id, balance, frozen,
                     last_block, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (series_id, wallet_address) DO UPDATE SET
                    balance = holding_view.balance + excluded.balance,
                    frozen = holding_view.frozen + excluded.frozen,
                    wallet_id = COALESCE(holding_view.wallet_id, excluded.wallet_id),
                    wallet_org_id = COALESCE(holding_view.wallet_org_id, excluded.wallet_org_id),
                    last_block = GREATEST(holding_view.last_block, excluded.last_block),
                    updated_at = excluded.updated_at
                """, seriesId, wallet, Ids.newId(), log.chainId(), token, walletId, orgId, balanceDelta, frozenDelta,
                log.block(), now);
        if (walletId != null) {
            // Q86: địa chỉ được đăng ký ví sau khi đã có biến động thì điền lại cho các dòng cũ
            jdbc.update("""
                    UPDATE distribution.holding_movement SET wallet_id = ?, wallet_org_id = ?
                    WHERE chain_id = ? AND wallet_address = ? AND wallet_id IS NULL
                    """, walletId, orgId, log.chainId(), wallet);
        }
    }

    /**
     * Series của token; chưa biết thì ném lỗi để JetStream gửi lại, vì {@code token_address} do Phát hành ghi khi nhận
     * {@code SeriesCreated} và có thể chưa kịp (Q86).
     */
    private UUID seriesOf(String token) {
        return series.findByToken(token)
                .orElseThrow(() -> new IllegalStateException("chưa biết series của token " + token + ", chờ gửi lại"));
    }

    private static String address(Object raw) {
        if (raw == null) {
            throw new IllegalArgumentException("thiếu địa chỉ trong tham số sự kiện");
        }
        return lower(String.valueOf(raw));
    }

    private static long quantity(Object raw) {
        return new java.math.BigInteger(String.valueOf(raw)).longValueExact();
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
