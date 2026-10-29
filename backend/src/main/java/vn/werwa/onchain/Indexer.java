package vn.werwa.onchain;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.protocol.core.methods.response.Log;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.onchain.AbiCatalog.EventDef;
import vn.werwa.onchain.ContractRegistry.Entry;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.id.Ids;

/**
 * Indexer đọc log từ O1 (ADR-12): mỗi lượt đọc tối đa {@code werwa.onchain.log-batch-blocks} khối kể từ khối đã đọc
 * xong, giữ log của hợp đồng có trong registry, giải mã theo ABI, ghi {@code indexed_event} và phát
 * {@code werwa.onchain.event.indexed.v1} (Q17). Cả lượt nằm trong một giao dịch DB cùng con trỏ khối, nên chạy lại an
 * toàn. Sự kiện mang tham số đã giải mã, mã chuỗi và giờ khối (Q84). {@code SeriesCreated} của factory thêm bộ
 * hợp đồng series vào registry ngay trong lượt đó.
 */
public class Indexer {

    private static final Logger log = LoggerFactory.getLogger(Indexer.class);
    static final String INDEXED = "werwa.onchain.event.indexed.v1";

    private final ChainNodes nodes;
    private final ContractRegistry registry;
    private final AbiCatalog abi;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Events events;
    private final JsonMapper json;
    private final OnchainProperties props;
    private final long deployedAtBlock;
    private final Clock clock;

    public Indexer(
            ChainNodes nodes,
            ContractRegistry registry,
            AbiCatalog abi,
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            Events events,
            JsonMapper json,
            OnchainProperties props,
            long deployedAtBlock,
            Clock clock) {
        this.nodes = nodes;
        this.registry = registry;
        this.abi = abi;
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.json = json;
        this.props = props;
        this.deployedAtBlock = deployedAtBlock;
        this.clock = clock;
    }

    /** Khối cuối đã đọc xong; trước khi chạy lần đầu là khối ngay trước khối triển khai nền tảng. */
    public long lastIndexedBlock() {
        List<Long> rows = jdbc.queryForList(
                "SELECT last_block FROM onchain.indexer_cursor WHERE chain_id = ?", Long.class, nodes.chainId());
        return rows.isEmpty() ? deployedAtBlock - 1 : rows.getFirst();
    }

    /** Đọc một lượt; trả số khối đã đọc. */
    public long poll() {
        long last = lastIndexedBlock();
        long head = nodes.read("eth_blockNumber", w -> w.ethBlockNumber().send().getBlockNumber()).longValueExact();
        if (head <= last) {
            return 0;
        }
        long from = last + 1;
        long to = Math.min(head, last + props.logBatchBlocks());
        List<Log> logs = nodes.logs(from, to);
        // Q84: giờ của khối cho sao kê; đọc header một lần cho mỗi khối có log, ngoài giao dịch DB
        Map<Long, java.time.Instant> blockTimes = new java.util.HashMap<>();
        for (Log l : logs) {
            blockTimes.computeIfAbsent(l.getBlockNumber().longValueExact(), this::blockTime);
        }
        tx.executeWithoutResult(status -> {
            for (Log l : logs) {
                index(l, blockTimes.get(l.getBlockNumber().longValueExact()));
            }
            jdbc.update("""
                    INSERT INTO onchain.indexer_cursor (chain_id, last_block, updated_at) VALUES (?, ?, ?)
                    ON CONFLICT (chain_id) DO UPDATE SET last_block = excluded.last_block, updated_at = excluded.updated_at
                    """, nodes.chainId(), to, Timestamp.from(clock.instant()));
        });
        if (!logs.isEmpty()) {
            log.debug("Indexer đọc khối {}–{}: {} log", from, to, logs.size());
        }
        return to - last;
    }

    private java.time.Instant blockTime(long block) {
        java.math.BigInteger seconds = nodes.read("eth_getBlockByNumber", w -> w.ethGetBlockByNumber(
                org.web3j.protocol.core.DefaultBlockParameter.valueOf(java.math.BigInteger.valueOf(block)), false).send()
                .getBlock().getTimestamp());
        return java.time.Instant.ofEpochSecond(seconds.longValueExact());
    }

    private void index(Log l, java.time.Instant blockTime) {
        Optional<Entry> contract = registry.find(l.getAddress());
        if (contract.isEmpty() || l.getTopics().isEmpty()) {
            return;
        }
        Entry entry = contract.get();
        String topic0 = l.getTopics().getFirst();
        Optional<EventDef> def = entry.abiName() == null ? Optional.empty() : abi.event(entry.abiName(), topic0);
        String eventName = def.map(EventDef::name).orElse("UNKNOWN:" + topic0);
        Map<String, Object> decoded = def.map(d -> abi.decode(d, l)).orElseGet(() -> Map.of("_data", l.getData()));

        long block = l.getBlockNumber().longValueExact();
        int logIndex = l.getLogIndex().intValueExact();
        int inserted = jdbc.update("""
                INSERT INTO onchain.indexed_event
                    (id, chain_id, block_number, log_index, tx_hash, contract_address, event_name, decoded, indexed_at,
                     block_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (chain_id, block_number, log_index) DO NOTHING
                """, Ids.newId(), nodes.chainId(), block, logIndex, l.getTransactionHash(), entry.address(), eventName,
                json.writeValueAsString(decoded), Timestamp.from(clock.instant()), Timestamp.from(blockTime));
        if (inserted == 0) {
            return;
        }
        if ("SeriesFactory".equals(entry.abiName()) && "SeriesCreated".equals(eventName)) {
            registry.registerSeries(decoded);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contract", entry.address());
        data.put("eventName", eventName);
        data.put("block", block);
        data.put("logIndex", logIndex);
        data.put("txHash", l.getTransactionHash());
        data.put("contractKind", entry.kind());
        data.put("chainId", nodes.chainId());
        data.put("blockTime", blockTime.toString());
        data.put("args", decoded);
        events.publish(INDEXED, "contract/" + entry.address(), data);
    }
}
