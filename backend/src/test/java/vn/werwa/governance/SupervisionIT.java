package vn.werwa.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.web3j.crypto.Hash;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.governance.reconciliation.ReconciliationService;
import vn.werwa.governance.supervision.BlockHashCheck;
import vn.werwa.governance.supervision.SupervisionService;
import vn.werwa.onchain.ChainIntegrationTest;
import vn.werwa.onchain.api.ChainTransactions;
import vn.werwa.onchain.api.ContractCalls;
import vn.werwa.onchain.api.IndexedEvents;
import vn.werwa.onchain.api.SignerKey;
import vn.werwa.onchain.api.TxRequest;
import vn.werwa.onchain.api.contracts.AuditAnchor;
import vn.werwa.platform.OpenApiSchemas;
import vn.werwa.platform.web.ApiContract;
import vn.werwa.platform.web.ApiContract.Operation;

/** Công cụ giám sát với Besu thật (US-4.04; Q90–Q96). */
class SupervisionIT extends ChainIntegrationTest {

    static final String HOLCO = "01a15bc1-f409-7589-aef1-774ceddccf3f";
    static final String VSDC = "01a15bc1-f403-7f0b-8fb9-a75cdc4b5b00";
    static final String SUPERVISOR_ORG = "01a15bc1-f408-7d65-ace1-f4c87b5c1cdc";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Duration WAIT = Duration.ofSeconds(60);
    static final Random RANDOM = new Random();

    static RequestPostProcessor as(String org, String role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", org).claim("roles", List.of(role)));
    }

    static final RequestPostProcessor SUPERVISOR = as(SUPERVISOR_ORG, "SUPERVISOR");
    static final RequestPostProcessor REGISTRAR = as(VSDC, "REGISTRAR");

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ChainTransactions chain;

    @Autowired
    ContractCalls calls;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    IndexedEvents events;

    @Autowired
    SupervisionService supervision;

    @Autowired
    BlockHashCheck blockHashCheck;

    @Autowired
    ReconciliationService reconciliation;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    UUID anchor(long businessDate) {
        String address = calls.address("auditAnchor");
        String data = calls.load(AuditAnchor.class, address)
                .anchor(BigInteger.valueOf(businessDate), BigInteger.ONE, Hash.sha3(("giám sát " + businessDate).getBytes()))
                .encodeFunctionCall();
        return tx.execute(s -> chain.submit(
                new TxRequest("AUDIT_ANCHOR", "audit-anchor/" + businessDate, SignerKey.HOLCO_OPERATOR, address, data)));
    }

    MvcResult read(String path, RequestPostProcessor who) throws Exception {
        return mvc.perform(get(path).with(who)).andReturn();
    }

    MvcResult attest(long block, String hash) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/v1/supervision/block-hash-attestations").with(REGISTRAR)
                .header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("blockNumber", block, "blockHash", hash)))).andReturn();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(MvcResult r) throws Exception {
        return JSON.readValue(r.getResponse().getContentAsString(), Map.class);
    }

    static void ok(MvcResult r, int status, String schema) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
        if (schema != null) {
            assertThat(OpenApiSchemas.validate(schema, r.getResponse().getContentAsString())).isEmpty();
        }
    }

    // ---------- US-4.04 ----------

    @Test
    @DisplayName("US-4.04-AC1 giao dịch vừa lên khối hiện trong công cụ giám sát không quá 10 giây")
    @SuppressWarnings("unchecked")
    void US_4_04_AC1_newTransactionVisibleWithinLimit() throws Exception {
        long before = events.indexedBlock();
        long date = 20_300_000L + RANDOM.nextInt(9_999);
        UUID id = anchor(date);
        AtomicReference<Map<String, Object>> seen = new AtomicReference<>();
        AtomicReference<Instant> seenAt = new AtomicReference<>();
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).until(() -> {
            MvcResult r = read("/v1/supervision/transactions?afterBlock=" + before, SUPERVISOR);
            for (Map<String, Object> e : (List<Map<String, Object>>) body(r).get("items")) {
                Map<String, Object> args = (Map<String, Object>) e.get("args");
                if ("AuditAnchored".equals(e.get("eventName")) && Long.toString(date).equals(args.get("businessDate"))) {
                    seen.set(e);
                    seenAt.set(Instant.now());
                    return true;
                }
            }
            return false;
        });
        Map<String, Object> e = seen.get();
        assertThat(OpenApiSchemas.validate("ChainEvent", JSON.writeValueAsString(e))).isEmpty();
        assertThat(e.get("contractKind")).isEqualTo("auditanchor");
        assertThat(e.get("txHash")).isEqualTo(
                jdbc.queryForObject("SELECT tx_hash FROM onchain.chain_tx WHERE id = ?", String.class, id));
        Instant blockTime = Instant.parse((String) e.get("blockTime"));
        // Giờ khối tính theo giây nên cho thêm 1 giây làm tròn
        assertThat(Duration.between(blockTime, seenAt.get())).isLessThanOrEqualTo(Duration.ofSeconds(11));

        MvcResult status = read("/v1/supervision/status", SUPERVISOR);
        ok(status, 200, "SupervisionStatus");
        assertThat(body(status)).containsEntry("withinLimit", true).containsEntry("maxLagSeconds", 10);
        assertThat(((Number) body(status).get("indexedBlock")).longValue())
                .isLessThanOrEqualTo(((Number) body(status).get("headBlock")).longValue());
    }

    @Test
    @DisplayName("US-4.04 dòng giao dịch lọc theo series và phân trang theo thứ tự khối")
    @SuppressWarnings("unchecked")
    void transactionsFilterBySeriesAndPage() throws Exception {
        // Hợp đồng series nạp thẳng registry và indexed_event (triển khai series là phần B6c)
        UUID series = UUID.randomUUID();
        String key = "0x" + "0".repeat(32) + series.toString().replace("-", "");
        String contract = "0x" + HexFormat.of().formatHex(Hash.sha3(series.toString().getBytes())).substring(0, 40);
        jdbc.update("""
                INSERT INTO onchain.contract_registry (id, chain_id, contract_kind, address, abi_name, series_key, active, registered_at)
                VALUES (?, ?, 'series.controller', ?, 'SeriesController', ?, true, now())
                """, UUID.randomUUID(), CHAIN_ID, contract, key);
        long base = 900_000_000L + RANDOM.nextInt(1_000_000) * 10L;
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    INSERT INTO onchain.indexed_event (id, chain_id, block_number, log_index, tx_hash, contract_address,
                        event_name, decoded, indexed_at, block_time)
                    VALUES (?, ?, ?, 0, ?, ?, 'StateChanged', ?::jsonb, now(), now())
                    """, UUID.randomUUID(), CHAIN_ID, base + i, "0x" + "ab".repeat(31) + String.format("%02x", i), contract,
                    "{\"from\":\"" + i + "\",\"to\":\"" + (i + 1) + "\"}");
        }
        MvcResult first = read("/v1/supervision/transactions?seriesId=" + series + "&limit=2", SUPERVISOR);
        ok(first, 200, null);
        List<Map<String, Object>> page = (List<Map<String, Object>>) body(first).get("items");
        assertThat(page).extracting(e -> ((Number) e.get("block")).longValue()).containsExactly(base + 2, base + 1);
        assertThat(page).allSatisfy(e -> assertThat(e.get("seriesId")).isEqualTo(series.toString()));
        String cursor = (String) body(first).get("nextCursor");
        List<Map<String, Object>> rest = (List<Map<String, Object>>) body(read(
                "/v1/supervision/transactions?seriesId=" + series + "&limit=2&cursor=" + cursor, SUPERVISOR)).get("items");
        assertThat(rest).extracting(e -> ((Number) e.get("block")).longValue()).containsExactly(base);
        assertThat((List<?>) body(read("/v1/supervision/transactions?seriesId=" + series + "&afterBlock=" + (base + 1),
                SUPERVISOR)).get("items")).hasSize(1);
        assertThat(read("/v1/supervision/transactions", REGISTRAR).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("US-4.04-AC2 người dùng của Cơ quan giám sát thử mọi thao tác ghi đều bị từ chối")
    void US_4_04_AC2_supervisorCannotWrite() throws Exception {
        ApiContract contract = ApiContract.load(new ClassPathResource("contract/openapi.yaml"));
        int tried = 0;
        for (Operation op : contract.operations()) {
            if (HttpMethod.GET.equals(op.method()) || op.bankMtls() || op.inboundToken()) {
                continue;
            }
            String path = op.path().replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
            MvcResult r = mvc.perform(MockMvcRequestBuilders.request(op.method(), path).with(SUPERVISOR)
                    .header("Idempotency-Key", "k-" + UUID.randomUUID()).header("If-Match", "\"1\"")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
            assertThat(r.getResponse().getStatus()).as(op.operationId() + " " + r.getResponse().getContentAsString())
                    .isEqualTo(403);
            tried++;
        }
        assertThat(tried).isGreaterThan(30);
    }

    @Test
    @DisplayName("US-4.04-AC3 mã băm khối VSDC lệch O1 thì có chênh lệch R7 và cảnh báo đỏ")
    @SuppressWarnings("unchecked")
    void US_4_04_AC3_blockHashMismatchCreatesBreakAndRedAlert() throws Exception {
        await().atMost(WAIT).until(() -> events.headBlock() > 3);
        long head = events.headBlock();
        long good = head - 1;
        long bad = head - 2;
        String goodHash = events.block(good).orElseThrow().hash();

        MvcResult matched = attest(good, goodHash);
        ok(matched, 201, "BlockHashAttestation");
        assertThat(body(matched)).containsEntry("status", "MATCHED").containsEntry("o1BlockHash", goodHash);
        MvcResult mismatched = attest(bad, "0x" + "11".repeat(32));
        ok(mismatched, 201, "BlockHashAttestation");
        assertThat(body(mismatched).get("status")).isEqualTo("MISMATCHED");
        MvcResult again = attest(good, goodHash);
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(again).get("code")).isEqualTo("BLOCK_HASH_ALREADY_ATTESTED");
        // Khối O1 chưa có: chờ, R7 so lại sau
        MvcResult pending = attest(head + 1_000_000, "0x" + "22".repeat(32));
        assertThat(body(pending).get("status")).isEqualTo("PENDING");
        assertThat(mvc.perform(MockMvcRequestBuilders.post("/v1/supervision/block-hash-attestations").with(SUPERVISOR)
                .header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"blockNumber\":1,\"blockHash\":\"0x" + "33".repeat(32) + "\"}")).andReturn().getResponse()
                .getStatus()).isEqualTo(403);

        List<UUID> runs = reconciliation.runAll(LocalDate.now());
        MvcResult checks = read("/v1/supervision/block-hash-checks?limit=50", SUPERVISOR);
        ok(checks, 200, null);
        List<Map<String, Object>> items = (List<Map<String, Object>>) body(checks).get("items");
        assertThat(items).allSatisfy(run -> assertThat(run.get("scope")).isEqualTo("BLOCK_HASH_VSDC"));
        Map<String, Object> run = items.stream().filter(i -> runs.contains(UUID.fromString((String) i.get("id"))))
                .findFirst().orElseThrow();
        assertThat(OpenApiSchemas.validate("ReconciliationRun", JSON.writeValueAsString(run))).isEmpty();
        assertThat((List<Map<String, Object>>) run.get("breaks")).anySatisfy(b -> {
            assertThat(b.get("rule")).isEqualTo("R7");
            assertThat(b.get("kind")).isEqualTo("BLOCK_HASH_MISMATCH");
            assertThat(b.get("subjectRef")).isEqualTo("block/" + bad);
        });
        // Mỗi khối lệch chỉ báo ở lần đối soát đầu tiên thấy nó
        assertThat(blockHashCheck.run(LocalDate.now())).noneMatch(f -> ("block/" + bad).equals(f.subjectRef()));

        // Cảnh báo đỏ cho Cơ quan giám sát
        String notices = read("/v1/notifications?limit=200", SUPERVISOR).getResponse().getContentAsString();
        assertThat(notices).contains("block/" + bad).contains("CRITICAL");
    }

    @Test
    @DisplayName("Q96 VSDC quá 1 ngày làm việc chưa gửi mã băm thì R7 có chênh lệch")
    void attestationMissingIsABreak() {
        // Ngày đối soát rất xa sau lần gửi cuối
        assertThat(blockHashCheck.run(LocalDate.now().plusYears(5)))
                .anySatisfy(f -> assertThat(f.kind()).isEqualTo("ATTESTATION_MISSING"));
    }

    @Test
    @DisplayName("Q91 series cho Cơ quan giám sát kèm tóm tắt sổ")
    void superviseSeriesAnswersWithHoldingsSummary() throws Exception {
        String seriesId = jdbc.queryForList("SELECT id::text FROM issuance.series LIMIT 1", String.class).stream()
                .findFirst().orElse(null);
        if (seriesId == null) {
            assertThat(read("/v1/supervision/series/" + UUID.randomUUID(), SUPERVISOR).getResponse().getStatus())
                    .isEqualTo(404);
            return;
        }
        MvcResult r = read("/v1/supervision/series/" + seriesId, SUPERVISOR);
        ok(r, 200, "SupervisionSeries");
        assertThat(read("/v1/supervision/series/" + seriesId, REGISTRAR).getResponse().getStatus()).isEqualTo(403);
    }
}
