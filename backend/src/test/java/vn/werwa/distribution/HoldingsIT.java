package vn.werwa.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.IntegrationTest;
import vn.werwa.distribution.holding.HoldingViewCheck;
import vn.werwa.distribution.holding.HoldingsService;
import vn.werwa.issuance.TestDocuments;
import vn.werwa.platform.OpenApiSchemas;
import vn.werwa.platform.events.EventHandler;
import vn.werwa.platform.events.InboundEvent;
import vn.werwa.platform.reconciliation.ReconciliationCheck.Finding;

/**
 * Sổ người sở hữu và sao kê (US-3.07; Q83–Q89). Sự kiện indexer được dựng giả và đưa thẳng vào bộ chiếu, như sự kiện
 * thật nhận qua NATS. Mỗi ca dùng token, ví và số khối riêng.
 */
class HoldingsIT extends IntegrationTest {

    static final String HOLCO = "01a15bc1-f409-7589-aef1-774ceddccf3f";
    static final String AGENCY = "01a15bc1-f401-70b2-9cfa-eb63c4f24ecc";
    static final String VSDC = "01a15bc1-f403-7f0b-8fb9-a75cdc4b5b00";
    static final String ZERO = "0x0000000000000000000000000000000000000000";
    static final long CHAIN = 20261;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Random RANDOM = new Random();
    /** Số khối tăng dần giữa các ca để không trùng khóa chống trùng. */
    static final AtomicLong BLOCKS = new AtomicLong(5_000_000L + RANDOM.nextInt(1_000_000) * 100L);

    static RequestPostProcessor as(String org, String role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", org).claim("roles", List.of(role)));
    }

    static final RequestPostProcessor OPERATOR = as(HOLCO, "OPERATOR");
    static final RequestPostProcessor REGISTRAR = as(VSDC, "REGISTRAR");
    static final RequestPostProcessor AGENCY_USER = as(AGENCY, "OPERATING_AGENCY");

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HoldingsService holdings;

    @Autowired
    @Qualifier("distributionOnEventIndexed")
    EventHandler projector;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ---------- dữ liệu thử ----------

    static String address() {
        return "0x" + HexFormat.of().formatHex(java.nio.ByteBuffer.allocate(20).putLong(RANDOM.nextLong())
                .putLong(RANDOM.nextLong()).putInt(RANDOM.nextInt()).array());
    }

    static String txHash() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return "0x" + HexFormat.of().formatHex(b);
    }

    UUID organization() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO onboarding.organization (id, legal_name, registration_no, country, roles, kyb_status, created_at, updated_at)
                VALUES (?, ?, ?, 'SG', ARRAY['INVESTOR'], 'KYB_APPROVED', now(), now())
                """, id, "Tổ chức thử " + id, "H-" + id);
        return id;
    }

    record Wallet(UUID id, String address) {}

    Wallet wallet(UUID org) {
        Wallet w = new Wallet(UUID.randomUUID(), address());
        jdbc.update("""
                INSERT INTO onboarding.wallet (id, organization_id, chain_id, address, wallet_type, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'SELF', 'REGISTERED', now(), now())
                """, w.id(), org, CHAIN, w.address());
        return w;
    }

    /** Series có token ở {@code token}, trạng thái {@code status} (địa chỉ token và trạng thái là phần B6c, nạp thẳng DB). */
    UUID series(String token, String status) throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("title", "Đề án " + UUID.randomUUID());
        p.put("hdndResolutionNo", "NQ-" + UUID.randomUUID());
        p.put("approvedOn", "2026-09-30");
        p.put("currency", "USD");
        p.put("maxPrincipal", Map.of("amountMinor", "100000000", "currency", "USD"));
        p.put("rateFloorPpm", 30_000);
        p.put("rateCapPpm", 70_000);
        p.put("costCap", Map.of("amountMinor", "100000000", "currency", "USD"));
        p.put("eligibleInvestorClasses", List.of("FOREIGN_INSTITUTION"));
        p.put("documentId", TestDocuments.uploadId(mvc, OPERATOR, "HDND_RESOLUTION").toString());
        MvcResult program = post("/v1/programs", AGENCY_USER, p);
        assertThat(program.getResponse().getStatus()).as(program.getResponse().getContentAsString()).isEqualTo(201);
        UUID approval = jdbc.queryForObject("SELECT approval_request_id FROM issuance.issuance_program WHERE id = ?",
                UUID.class, id(program));
        post("/v1/approvals/" + approval + "/decision", as(AGENCY, "OPERATING_AGENCY"), Map.of("decision", "APPROVE"));
        Map<String, Object> s = new HashMap<>();
        s.put("programId", id(program).toString());
        s.put("seriesCode", "S-" + UUID.randomUUID());
        s.put("currency", "USD");
        s.put("faceValue", Map.of("amountMinor", "100000", "currency", "USD"));
        s.put("issuePricePpm", 1_000_000);
        s.put("maxQuantity", 1000);
        s.put("couponFrequency", "SEMI_ANNUAL");
        s.put("dayCount", "30_360");
        s.put("issueDate", "2027-03-02");
        s.put("maturityDate", "2030-03-02");
        s.put("termsDocumentId", TestDocuments.uploadId(mvc, OPERATOR, "TERM_SHEET").toString());
        MvcResult created = post("/v1/series", OPERATOR, s);
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        jdbc.update("UPDATE issuance.series SET status = ?, token_address = ? WHERE id = ?", status, token, id(created));
        return id(created);
    }

    /** Dựng {@code werwa.onchain.event.indexed.v1} như indexer phát (Q84) rồi đưa vào bộ chiếu. */
    void emit(String token, String eventName, long block, int logIndex, String txHash, Map<String, Object> args) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contract", token);
        data.put("eventName", eventName);
        data.put("block", block);
        data.put("logIndex", logIndex);
        data.put("txHash", txHash);
        data.put("contractKind", "series.token");
        data.put("chainId", CHAIN);
        data.put("blockTime", Instant.parse("2027-03-10T00:00:00Z").plusSeconds(2 * (block % 1_000_000)).toString());
        data.put("args", args);
        projector.handle(new InboundEvent(UUID.randomUUID().toString(), "werwa.onchain.event.indexed.v1",
                "/werwa/onchain", "contract/" + token, Instant.now().toString(), null, data, JSON));
    }

    void transfer(String token, long block, int logIndex, String tx, String from, String to, long value) {
        emit(token, "Transfer", block, logIndex, tx, Map.of("from", from, "to", to, "value", Long.toString(value)));
    }

    MvcResult post(String path, RequestPostProcessor who, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path).with(who).header("Idempotency-Key", "k-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body))).andReturn();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items(String path, RequestPostProcessor who, String schema) throws Exception {
        MvcResult r = mvc.perform(get(path).with(who)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        List<Map<String, Object>> items = (List<Map<String, Object>>) body(r).get("items");
        for (Map<String, Object> item : items) {
            assertThat(OpenApiSchemas.validate(schema, JSON.writeValueAsString(item))).isEmpty();
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(MvcResult r) throws Exception {
        return JSON.readValue(r.getResponse().getContentAsString(), Map.class);
    }

    static UUID id(MvcResult r) throws Exception {
        return UUID.fromString((String) body(r).get("id"));
    }

    static Map<String, Object> holdingOf(List<Map<String, Object>> rows, UUID wallet) {
        return rows.stream().filter(h -> wallet.toString().equals(h.get("walletId"))).findFirst().orElseThrow();
    }

    // ---------- US-3.07 ----------

    @Test
    @DisplayName("US-3.07-AC3 sao kê có mọi biến động kèm mã giao dịch; nhận lặp sự kiện không đếm hai lần")
    void US_3_07_AC3_statementHasEveryMovementWithTxHash() throws Exception {
        String token = address();
        UUID series = series(token, "ACTIVE");
        UUID orgA = organization();
        UUID orgB = organization();
        Wallet a = wallet(orgA);
        Wallet b = wallet(orgB);
        long block = BLOCKS.addAndGet(100);
        String mintTx = txHash();
        String transferTx = txHash();
        String freezeTx = txHash();
        transfer(token, block, 0, mintTx, ZERO, a.address(), 100);
        transfer(token, block + 1, 3, transferTx, a.address(), b.address(), 30);
        emit(token, "TokensFrozen", block + 2, 1, freezeTx, Map.of("_userAddress", b.address(), "_amount", "10"));
        // JetStream gửi lại: không đổi gì
        transfer(token, block + 1, 3, transferTx, a.address(), b.address(), 30);

        RequestPostProcessor userA = as(orgA.toString(), "INVESTOR");
        List<Map<String, Object>> statementA = items("/v1/holdings/movements?walletId=" + a.id(), userA,
                "HoldingMovement");
        assertThat(statementA).extracting(m -> m.get("kind")).containsExactly("TRANSFER_OUT", "MINT");
        assertThat(statementA).extracting(m -> m.get("txHash")).containsExactly(transferTx, mintTx);
        assertThat(statementA.getFirst().get("counterpartyWallet")).isEqualTo(b.address());
        assertThat(statementA.getFirst().get("quantity")).isEqualTo(30);

        List<Map<String, Object>> statementB = items("/v1/holdings/movements?seriesId=" + series, REGISTRAR,
                "HoldingMovement");
        assertThat(statementB).extracting(m -> m.get("kind")).containsExactly("FREEZE", "TRANSFER_IN", "TRANSFER_OUT",
                "MINT");

        // Lọc theo giờ khối: from bao gồm, to không bao gồm
        Instant transferTime = Instant.parse("2027-03-10T00:00:00Z").plusSeconds(2 * ((block + 1) % 1_000_000));
        List<Map<String, Object>> window = items("/v1/holdings/movements?seriesId=" + series + "&from=" + transferTime
                + "&to=" + transferTime.plusSeconds(2), REGISTRAR, "HoldingMovement");
        assertThat(window).extracting(m -> m.get("kind")).containsExactly("TRANSFER_IN", "TRANSFER_OUT");

        // Phân trang theo thứ tự sao kê
        MvcResult first = mvc.perform(get("/v1/holdings/movements").param("seriesId", series.toString())
                .param("limit", "3").with(REGISTRAR)).andReturn();
        String cursor = (String) body(first).get("nextCursor");
        assertThat(cursor).isNotNull();
        assertThat(items("/v1/holdings/movements?seriesId=" + series + "&limit=3&cursor=" + cursor, REGISTRAR,
                "HoldingMovement")).extracting(m -> m.get("kind")).containsExactly("MINT");

        List<Map<String, Object>> view = items("/v1/holdings?seriesId=" + series, REGISTRAR, "Holding");
        assertThat(holdingOf(view, a.id())).containsEntry("balance", 70).containsEntry("frozen", 0);
        assertThat(holdingOf(view, b.id())).containsEntry("balance", 30).containsEntry("frozen", 10)
                .containsEntry("locked", 0);
        assertThat(((Number) view.getFirst().get("asOfBlock")).longValue()).isGreaterThanOrEqualTo(block + 2);
    }

    @Test
    @DisplayName("US-3.07-AC2 người dùng của nhà đầu tư A chỉ thấy ví của A; VSDC thấy toàn bộ")
    void US_3_07_AC2_investorSeesOwnWalletsOnly() throws Exception {
        String token = address();
        UUID series = series(token, "ISSUING");
        UUID orgA = organization();
        UUID orgB = organization();
        Wallet a = wallet(orgA);
        Wallet b = wallet(orgB);
        long block = BLOCKS.addAndGet(100);
        transfer(token, block, 0, txHash(), ZERO, a.address(), 5);
        transfer(token, block, 1, txHash(), ZERO, b.address(), 7);

        RequestPostProcessor userA = as(orgA.toString(), "INVESTOR");
        List<Map<String, Object>> mine = items("/v1/holdings?seriesId=" + series, userA, "Holding");
        assertThat(mine).extracting(h -> h.get("walletId")).containsExactly(a.id().toString());
        // Q87: series chưa Đang lưu hành thì toàn bộ số dư bị khóa
        assertThat(mine.getFirst()).containsEntry("balance", 5).containsEntry("locked", 5);
        assertThat(items("/v1/holdings?walletId=" + b.id(), userA, "Holding")).isEmpty();
        assertThat(items("/v1/holdings/movements?walletId=" + b.id(), userA, "HoldingMovement")).isEmpty();
        assertThat(items("/v1/holdings?seriesId=" + series, REGISTRAR, "Holding")).hasSize(2);
        assertThat(items("/v1/holdings?seriesId=" + series, OPERATOR, "Holding")).hasSize(2);
        assertThat(mvc.perform(get("/v1/holdings").with(as(AGENCY, "OPERATING_AGENCY"))).andReturn().getResponse()
                .getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("US-3.07-AC1 đối soát R5: bảng đọc khớp chuỗi tại cùng số khối; lệch thì có chênh lệch")
    void US_3_07_AC1_viewMatchesChainAtSameBlock() throws Exception {
        String token = address();
        series(token, "ACTIVE");
        Wallet a = wallet(organization());
        String unregistered = address();
        long block = BLOCKS.addAndGet(100);
        transfer(token, block, 0, txHash(), ZERO, a.address(), 40);
        transfer(token, block, 1, txHash(), a.address(), unregistered, 15);

        Map<String, Long> chain = new HashMap<>(Map.of(a.address(), 25L, unregistered, 15L));
        List<Long> askedBlocks = new java.util.ArrayList<>();
        HoldingViewCheck check = new HoldingViewCheck(holdings, (t, w, at) -> {
            askedBlocks.add(at);
            return token.equals(t) ? chain.getOrDefault(w, 0L) : -1;
        });
        assertThat(mine(check.run(java.time.LocalDate.now()), token)).isEmpty();
        assertThat(askedBlocks).allSatisfy(at -> assertThat(at).isEqualTo(holdings.asOfBlock()));

        // Q86: địa chỉ chưa có ví trong onboarding vẫn được đối chiếu
        chain.put(unregistered, 16L);
        List<Finding> breaks = mine(check.run(java.time.LocalDate.now()), token);
        assertThat(breaks).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo("BALANCE_MISMATCH");
            assertThat(f.subjectRef()).endsWith(unregistered);
            assertThat(f.expected()).isEqualTo("16");
            assertThat(f.actual()).isEqualTo("15");
        });
    }

    @Test
    @DisplayName("Q86 địa chỉ được đăng ký ví sau khi đã có biến động thì hiện lại trong sổ")
    void walletRegisteredLaterIsBackfilled() throws Exception {
        String token = address();
        UUID series = series(token, "ACTIVE");
        UUID org = organization();
        String later = address();
        long block = BLOCKS.addAndGet(100);
        transfer(token, block, 0, txHash(), ZERO, later, 9);
        assertThat(items("/v1/holdings?seriesId=" + series, REGISTRAR, "Holding")).isEmpty();

        UUID walletId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO onboarding.wallet (id, organization_id, chain_id, address, wallet_type, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'SELF', 'REGISTERED', now(), now())
                """, walletId, org, CHAIN, later);
        Wallet other = wallet(organization());
        transfer(token, block + 1, 0, txHash(), later, other.address(), 4);
        List<Map<String, Object>> view = items("/v1/holdings?seriesId=" + series, REGISTRAR, "Holding");
        assertThat(holdingOf(view, walletId)).containsEntry("balance", 5);
        assertThat(items("/v1/holdings/movements?walletId=" + walletId, as(org.toString(), "INVESTOR"),
                "HoldingMovement")).extracting(m -> m.get("kind")).containsExactly("TRANSFER_OUT", "MINT");
    }

    @Test
    @DisplayName("Q85 AddressFrozen tạo cảnh báo vận hành, không đổi số dư")
    void addressFrozenRaisesAlert() throws Exception {
        String token = address();
        series(token, "ACTIVE");
        String wallet = address();
        long block = BLOCKS.addAndGet(100);
        emit(token, "AddressFrozen", block, 0, txHash(),
                Map.of("_userAddress", wallet, "_isFrozen", true, "_owner", address()));
        emit(token, "AddressFrozen", block, 0, txHash(),
                Map.of("_userAddress", wallet, "_isFrozen", true, "_owner", address()));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM governance.operational_alert WHERE name = 'TokenAddressFrozen' AND summary LIKE ?
                """, Integer.class, "%" + wallet + "%")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM distribution.holding_movement WHERE wallet_address = ?",
                Integer.class, wallet)).isZero();
    }

    @Test
    @DisplayName("Q86 token chưa biết series thì ném lỗi để JetStream gửi lại; log của hợp đồng khác bỏ qua")
    void unknownTokenIsRetried() {
        String token = address();
        long block = BLOCKS.addAndGet(100);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> transfer(token, block, 0, txHash(), ZERO, address(), 1)).isInstanceOf(IllegalStateException.class);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contract", token);
        data.put("eventName", "StateChanged");
        data.put("block", block);
        data.put("logIndex", 1);
        data.put("contractKind", "series.controller");
        projector.handle(new InboundEvent(UUID.randomUUID().toString(), "werwa.onchain.event.indexed.v1",
                "/werwa/onchain", "contract/" + token, Instant.now().toString(), null, data, JSON));
    }

    static List<Finding> mine(List<Finding> all, String token) {
        return all.stream().filter(f -> !f.expected().equals("-1")).toList();
    }
}
