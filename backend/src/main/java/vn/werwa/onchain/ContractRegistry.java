package vn.werwa.onchain;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.werwa.platform.id.Ids;

/**
 * {@code onchain.contract_registry}: hợp đồng nền tảng biết và ABI để giải mã log. Hợp đồng dùng chung nạp từ
 * {@code werwa.contracts.*} lúc khởi động; hợp đồng của từng series thêm khi indexer đọc {@code SeriesCreated}.
 */
public class ContractRegistry {

    private static final Logger log = LoggerFactory.getLogger(ContractRegistry.class);

    /** Tên hợp đồng dùng chung (khóa của {@code devnet.json}, đã bỏ chữ hoa) sang tệp ABI. Hợp đồng không có ABI thì null. */
    static final Map<String, String> SHARED_ABI = Map.of(
            "auditanchor", "AuditAnchor",
            "seriesfactory", "SeriesFactory",
            "identityregistry", "IdentityRegistry",
            "identityregistrystorage", "IdentityRegistryStorage",
            "claimtopicsregistry", "ClaimTopicsRegistry",
            "trustedissuersregistry", "TrustedIssuersRegistry",
            "holcoclaimissuer", "ClaimIssuer",
            "timelock", "WeRWATimelock",
            "moduledeployer", "ModuleDeployer");

    /** Trường của {@code SeriesCreated} sang tệp ABI của hợp đồng series. */
    static final Map<String, String> SERIES_ABI = Map.of(
            "controller", "SeriesController",
            "token", "Token",
            "compliance", "ModularCompliance",
            "investorClassModule", "InvestorClassModule",
            "holdingLimitModule", "HoldingLimitModule",
            "transferGateModule", "TransferGateModule");

    public record Entry(String address, String kind, String abiName, String seriesKey) {}

    private final JdbcTemplate jdbc;
    private final long chainId;
    private final Clock clock;
    private final Map<String, Entry> byAddress = new ConcurrentHashMap<>();

    public ContractRegistry(JdbcTemplate jdbc, long chainId, Clock clock) {
        this.jdbc = jdbc;
        this.chainId = chainId;
        this.clock = clock;
    }

    /** Nạp hợp đồng dùng chung từ cấu hình, rồi đọc toàn bộ registry vào bộ nhớ. */
    public void seed(Map<String, String> sharedContracts) {
        sharedContracts.forEach((kind, address) -> register(address, kind, SHARED_ABI.get(kind), null));
        jdbc.query("SELECT address, contract_kind, abi_name, series_key FROM onchain.contract_registry WHERE chain_id = ? AND active",
                rs -> {
                    Entry e = new Entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
                    byAddress.put(e.address(), e);
                }, chainId);
        log.info("Registry hợp đồng: {} địa chỉ trên chain {}", byAddress.size(), chainId);
    }

    /** Thêm bộ hợp đồng của một series từ đối số của {@code SeriesCreated}. */
    public void registerSeries(Map<String, Object> seriesCreated) {
        String seriesKey = String.valueOf(seriesCreated.get("seriesId"));
        SERIES_ABI.forEach((field, abi) -> {
            Object address = seriesCreated.get(field);
            if (address != null) {
                register(String.valueOf(address), "series." + field, abi, seriesKey);
            }
        });
    }

    public Optional<Entry> find(String address) {
        return Optional.ofNullable(byAddress.get(normalize(address)));
    }

    public Optional<String> addressOf(String kind) {
        String k = kind.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
        return byAddress.values().stream().filter(e -> e.kind().equals(k)).map(Entry::address).findFirst();
    }

    private void register(String address, String kind, String abiName, String seriesKey) {
        String a = normalize(address);
        jdbc.update("""
                INSERT INTO onchain.contract_registry
                    (id, chain_id, contract_kind, address, abi_name, series_key, active, registered_at)
                VALUES (?, ?, ?, ?, ?, ?, true, ?)
                ON CONFLICT (chain_id, address) DO NOTHING
                """, Ids.newId(), chainId, kind, a, abiName, seriesKey, Timestamp.from(clock.instant()));
        byAddress.put(a, new Entry(a, kind, abiName, seriesKey));
    }

    static String normalize(String address) {
        return address.toLowerCase(Locale.ROOT);
    }
}
