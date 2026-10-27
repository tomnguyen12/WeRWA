package vn.werwa.onchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.nats.client.Connection;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import vn.werwa.onchain.api.ChainCallRejected;
import vn.werwa.onchain.api.ChainTransactions;
import vn.werwa.onchain.api.ContractCalls;
import vn.werwa.onchain.api.SignerKey;
import vn.werwa.onchain.api.TxRequest;
import vn.werwa.onchain.api.contracts.AuditAnchor;
import vn.werwa.platform.events.EventHandler;
import vn.werwa.platform.events.EventSchemas;
import vn.werwa.platform.events.InboundEvent;

/**
 * Mô-đun Onchain với Besu thật (B3; Q14–Q17): gửi giao dịch của khóa vận hành, indexer đọc log, chốt kết quả sau khi
 * indexer đọc tới khối, chạy thử chặn lệnh bị từ chối, đọc và gửi chuyển sang node kế tiếp.
 */
@Import(OnchainIT.TestBeans.class)
class OnchainIT extends ChainIntegrationTest {

    static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired
    ChainTransactions chain;

    @Autowired
    ContractCalls calls;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LocalKeySigner signer;

    @Autowired
    TestBeans beans;

    @Autowired
    Connection nats;

    UUID anchor(long businessDate) {
        String address = calls.address("auditAnchor");
        String data = calls.load(AuditAnchor.class, address)
                .anchor(BigInteger.valueOf(businessDate), BigInteger.valueOf(7), Hash.sha3(("ngày " + businessDate).getBytes()))
                .encodeFunctionCall();
        return tx.execute(s -> chain.submit(
                new TxRequest("AUDIT_ANCHOR", "audit-anchor/" + businessDate, SignerKey.HOLCO_OPERATOR, address, data)));
    }

    String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM onchain.chain_tx WHERE id = ?", String.class, id);
    }

    @Test
    void registryHoldsTheSharedContracts() {
        assertThat(jdbc.queryForList("SELECT contract_kind FROM onchain.contract_registry", String.class))
                .contains("auditanchor", "seriesfactory", "identityregistry");
        assertThat(calls.address("auditAnchor")).isEqualTo(CONTRACTS.get("auditAnchor").toLowerCase());
    }

    @Test
    void operatorTransactionIsMinedIndexedThenReported() throws Exception {
        UUID id = anchor(20270120);

        await().atMost(WAIT).until(() -> "MINED".equals(status(id)));
        Long block = jdbc.queryForObject("SELECT block_number FROM onchain.chain_tx WHERE id = ?", Long.class, id);
        Long indexed = jdbc.queryForObject("SELECT last_block FROM onchain.indexer_cursor", Long.class);
        assertThat(indexed).isGreaterThanOrEqualTo(block);

        Map<String, Object> event = jdbc.queryForMap("""
                SELECT e.event_name, e.decoded->>'businessDate' AS business_date, e.decoded->>'operator' AS operator
                FROM onchain.indexed_event e JOIN onchain.chain_tx t ON t.tx_hash = e.tx_hash WHERE t.id = ?
                """, id);
        assertThat(event.get("event_name")).isEqualTo("AuditAnchored");
        assertThat(event.get("business_date")).isEqualTo("20270120");
        assertThat(((String) event.get("operator")).toLowerCase()).isEqualTo(OPERATOR.getAddress().toLowerCase());

        await().atMost(WAIT).until(() -> beans.mined.containsKey(id.toString()));
        InboundEvent mined = beans.mined.get(id.toString());
        assertThat(mined.data().get("subjectRef")).isEqualTo("audit-anchor/20270120");
        assertThat(EventSchemas.validate(TxTracker.MINED,
                        nats.jetStreamManagement().getLastMessage("WERWA", TxTracker.MINED).getData()))
                .isEmpty();
        assertThat(EventSchemas.validate(Indexer.INDEXED,
                        nats.jetStreamManagement().getLastMessage("WERWA", Indexer.INDEXED).getData()))
                .isEmpty();
    }

    @Test
    void rejectedCallIsStoppedBeforeSigning() {
        UUID first = anchor(20270121);
        await().atMost(WAIT).until(() -> "MINED".equals(status(first)));
        int signedBefore = signer.signed.get();
        int rowsBefore = jdbc.queryForObject("SELECT count(*) FROM onchain.chain_tx", Integer.class);

        assertThatThrownBy(() -> anchor(20270121))
                .isInstanceOf(ChainCallRejected.class)
                .satisfies(e -> assertThat(((ChainCallRejected) e).error().code()).isEqualTo("CHAIN_ALREADY_ANCHORED"));
        assertThat(signer.signed.get()).isEqualTo(signedBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onchain.chain_tx", Integer.class)).isEqualTo(rowsBefore);
    }

    @Test
    void rollbackNeverSends() {
        String address = calls.address("auditAnchor");
        String data = calls.load(AuditAnchor.class, address)
                .anchor(BigInteger.valueOf(20270122), BigInteger.ONE, new byte[32]).encodeFunctionCall();
        UUID id = tx.execute(s -> {
            UUID submitted = chain.submit(
                    new TxRequest("AUDIT_ANCHOR", "audit-anchor/20270122", SignerKey.HOLCO_OPERATOR, address, data));
            s.setRollbackOnly();
            return submitted;
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onchain.chain_tx WHERE id = ?", Integer.class, id)).isZero();
        // Ngày đó vẫn neo được: giao dịch đã rollback không lên chuỗi.
        UUID again = anchor(20270122);
        await().atMost(WAIT).until(() -> "MINED".equals(status(again)));
    }

    @Test
    void consecutiveTransactionsTakeConsecutiveNonces() {
        UUID a = anchor(20270123);
        UUID b = anchor(20270124);
        await().atMost(WAIT).until(() -> "MINED".equals(status(a)) && "MINED".equals(status(b)));
        Long na = jdbc.queryForObject("SELECT nonce FROM onchain.chain_tx WHERE id = ?", Long.class, a);
        Long nb = jdbc.queryForObject("SELECT nonce FROM onchain.chain_tx WHERE id = ?", Long.class, b);
        assertThat(nb).isEqualTo(na + 1);
    }

    @Test
    void submitOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> chain.submit(new TxRequest("X", "x/1", SignerKey.HOLCO_OPERATOR,
                        calls.address("auditAnchor"), Numeric.toHexString(new byte[4]))))
                .hasMessageContaining("giao dịch DB");
    }

    @TestConfiguration
    static class TestBeans {

        final Map<String, InboundEvent> mined = new ConcurrentHashMap<>();

        @Bean
        @Primary
        LocalKeySigner localKeySigner() {
            return new LocalKeySigner(OPERATOR);
        }

        @Bean
        EventHandler governanceOnTxMined() {
            return new EventHandler() {
                @Override
                public String consumer() {
                    return "onchain-test";
                }

                @Override
                public String eventType() {
                    return TxTracker.MINED;
                }

                @Override
                public void handle(InboundEvent event) {
                    mined.put(String.valueOf(event.data().get("txId")), event);
                }
            };
        }
    }
}
