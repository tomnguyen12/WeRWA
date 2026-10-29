package vn.werwa.onchain;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.response.EthBlock;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.onchain.api.IndexedEvents;

/** Đọc {@code onchain.indexed_event} kèm loại hợp đồng và series trong registry; thông tin khối đọc qua O1 (ADR-12). */
class JdbcIndexedEvents implements IndexedEvents {

    private final JdbcTemplate jdbc;
    private final ChainNodes nodes;
    private final Indexer indexer;
    private final JsonMapper json;

    JdbcIndexedEvents(JdbcTemplate jdbc, ChainNodes nodes, Indexer indexer, JsonMapper json) {
        this.jdbc = jdbc;
        this.nodes = nodes;
        this.indexer = indexer;
        this.json = json;
    }

    @Override
    public long chainId() {
        return nodes.chainId();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ChainEvent> list(Long afterBlock, UUID seriesId, Long beforePosition, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.block_number, e.log_index, e.tx_hash, e.block_time, e.contract_address, r.contract_kind,
                       e.event_name, r.series_key, e.decoded::text
                FROM onchain.indexed_event e
                LEFT JOIN onchain.contract_registry r ON r.chain_id = e.chain_id AND r.address = e.contract_address
                WHERE e.chain_id = ?""");
        List<Object> args = new ArrayList<>(List.of(nodes.chainId()));
        if (afterBlock != null) {
            sql.append(" AND e.block_number > ?");
            args.add(afterBlock);
        }
        if (seriesId != null) {
            sql.append(" AND lower(r.series_key) = ?");
            args.add(seriesKey(seriesId));
        }
        if (beforePosition != null) {
            sql.append(" AND e.block_number * 1000000 + e.log_index < ?");
            args.add(beforePosition);
        }
        sql.append(" ORDER BY e.block_number DESC, e.log_index DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> {
            Timestamp time = rs.getTimestamp(4);
            String key = rs.getString(8);
            return new ChainEvent(rs.getLong(1), rs.getInt(2), rs.getString(3), time == null ? null : time.toInstant(),
                    rs.getString(5), rs.getString(6), rs.getString(7), key == null ? null : seriesOf(key),
                    json.readValue(rs.getString(9), Map.class));
        }, args.toArray());
    }

    @Override
    public long indexedBlock() {
        return indexer.lastIndexedBlock();
    }

    @Override
    public long headBlock() {
        return nodes.read("eth_blockNumber", w -> w.ethBlockNumber().send().getBlockNumber()).longValueExact();
    }

    @Override
    public Optional<BlockHeader> block(long number) {
        EthBlock.Block b = nodes.read("eth_getBlockByNumber", w -> w.ethGetBlockByNumber(
                DefaultBlockParameter.valueOf(BigInteger.valueOf(number)), false).send().getBlock());
        return b == null ? Optional.empty()
                : Optional.of(new BlockHeader(number, b.getHash().toLowerCase(Locale.ROOT),
                        Instant.ofEpochSecond(b.getTimestamp().longValueExact())));
    }

    /** Q43: {@code bytes32(uint256(uint128(uuid)))}, 16 byte đầu là 0. */
    static String seriesKey(UUID id) {
        return "0x" + "0".repeat(32) + String.format("%016x%016x", id.getMostSignificantBits(), id.getLeastSignificantBits());
    }

    static UUID seriesOf(String key) {
        String hex = key.startsWith("0x") ? key.substring(2) : key;
        if (hex.length() != 64) {
            return null;
        }
        String low = hex.substring(32);
        return new UUID(Long.parseUnsignedLong(low.substring(0, 16), 16), Long.parseUnsignedLong(low.substring(16), 16));
    }
}
