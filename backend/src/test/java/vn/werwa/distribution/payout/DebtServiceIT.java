package vn.werwa.distribution.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import vn.werwa.integration.bank.SimulatedPayingAgent;
import vn.werwa.issuance.TestDocuments;
import vn.werwa.issuance.api.DebtServices;
import vn.werwa.platform.events.EventSchemas;

/**
 * Phần ngoài chuỗi của kỳ trả nợ (B11c; Q98–Q105): nhắc, kiểm tiền, lệnh chi, xác nhận chi, đối soát, chậm trả, ân hạn,
 * kỳ gốc. Chụp sổ và VSDC xác nhận (B11b) nạp thẳng vào DB. Mỗi ca dùng series riêng.
 */
class DebtServiceIT extends IntegrationTest {

    static final String HOLCO = "01a15bc1-f409-7589-aef1-774ceddccf3f";
    static final String AGENCY = "01a15bc1-f401-70b2-9cfa-eb63c4f24ecc";
    static final String INVESTOR_A = "01a15bc1-f40a-7520-aeb5-19bb9d2faaa5";
    static final String INVESTOR_B = "01a15bc1-f40b-7c54-aa64-c99f087b0103";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Duration WAIT = Duration.ofSeconds(30);

    static RequestPostProcessor as(String org, String role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", org).claim("roles", List.of(role)));
    }

    static final RequestPostProcessor OPERATOR = as(HOLCO, "OPERATOR");

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DebtServiceRunner runner;

    @Autowired
    DebtServices debts;

    @Autowired
    SimulatedPayingAgent agent;

    @Autowired
    io.nats.client.Connection nats;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ---------- dữ liệu thử ----------

    MvcResult post(String path, RequestPostProcessor who, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path).with(who).header("Idempotency-Key", "k-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body))).andReturn();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(MvcResult r) throws Exception {
        return JSON.readValue(r.getResponse().getContentAsString(), Map.class);
    }

    /** Series Đang lưu hành, tiền tệ riêng cho mỗi ca để số dư IPA giả lập không lẫn giữa các ca. */
    record Fixture(UUID series, String currency, DebtServices.Due coupon1, DebtServices.Due maturity) {}

    Fixture fixture(int graceBusinessDays) throws Exception {
        String currency = "X" + (char) ('A' + new java.util.Random().nextInt(26)) + (char) ('A' + new java.util.Random().nextInt(26));
        Map<String, Object> p = new HashMap<>();
        p.put("title", "Đề án " + UUID.randomUUID());
        p.put("hdndResolutionNo", "NQ-" + UUID.randomUUID());
        p.put("approvedOn", "2026-09-30");
        p.put("currency", "USD");
        p.put("maxPrincipal", Map.of("amountMinor", "100000000000", "currency", "USD"));
        p.put("rateFloorPpm", 30_000);
        p.put("rateCapPpm", 70_000);
        p.put("costCap", Map.of("amountMinor", "100000000", "currency", "USD"));
        p.put("eligibleInvestorClasses", List.of("FOREIGN_INSTITUTION"));
        p.put("documentId", TestDocuments.uploadId(mvc, OPERATOR, "HDND_RESOLUTION").toString());
        MvcResult program = post("/v1/programs", as(AGENCY, "OPERATING_AGENCY"), p);
        UUID programId = UUID.fromString((String) body(program).get("id"));
        UUID approval = jdbc.queryForObject("SELECT approval_request_id FROM issuance.issuance_program WHERE id = ?",
                UUID.class, programId);
        post("/v1/approvals/" + approval + "/decision", as(AGENCY, "OPERATING_AGENCY"), Map.of("decision", "APPROVE"));
        Map<String, Object> s = new HashMap<>();
        s.put("programId", programId.toString());
        s.put("seriesCode", "S-" + UUID.randomUUID());
        s.put("currency", "USD");
        s.put("faceValue", Map.of("amountMinor", "100000", "currency", "USD"));
        s.put("issuePricePpm", 1_000_000);
        s.put("maxQuantity", 10_000);
        s.put("couponFrequency", "SEMI_ANNUAL");
        s.put("dayCount", "30_360");
        s.put("issueDate", "2027-03-02");
        s.put("maturityDate", "2030-03-02");
        s.put("withholdingTaxPpm", 50_000);
        s.put("graceBusinessDays", graceBusinessDays);
        s.put("termsDocumentId", TestDocuments.uploadId(mvc, OPERATOR, "TERM_SHEET").toString());
        MvcResult created = post("/v1/series", OPERATOR, s);
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        UUID series = UUID.fromString((String) body(created).get("id"));
        // Trạng thái và tiền tệ riêng nạp thẳng (B6c); lãi suất kỳ ghi khi UBND ký danh sách (B7b)
        jdbc.update("UPDATE issuance.series SET status = 'ACTIVE', currency = ? WHERE id = ?", currency, series);
        jdbc.update("UPDATE issuance.series_event SET rate_ppm = 50000 WHERE series_id = ? AND event_type = 'COUPON'", series);
        UUID couponEvent = jdbc.queryForObject("""
                SELECT id FROM issuance.series_event WHERE series_id = ? AND event_type = 'COUPON' AND period_no = 1
                """, UUID.class, series);
        UUID maturityEvent = jdbc.queryForObject(
                "SELECT id FROM issuance.series_event WHERE series_id = ? AND event_type = 'MATURITY'", UUID.class, series);
        return new Fixture(series, currency, debts.get(couponEvent).orElseThrow(), debts.get(maturityEvent).orElseThrow());
    }

    /** Danh sách chốt của kỳ: A 500 (200 phong tỏa), B 300; {@code attested} là VSDC đã xác nhận (B11b). */
    void snapshot(UUID series, int period, boolean attested) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO distribution.record_snapshot (id, series_id, period_no, record_block, register_hash, holder_count,
                    total_quantity, registrar_attested_at, created_at)
                VALUES (?, ?, ?, 123456, ?, 2, 800, ?, now())
                """, id, series, period, "0x" + "cd".repeat(32), attested ? java.sql.Timestamp.from(java.time.Instant.now())
                : null);
        jdbc.update("""
                INSERT INTO distribution.snapshot_holding (record_snapshot_id, wallet_id, investor_org_id, quantity, frozen_quantity)
                VALUES (?, ?, ?::uuid, 500, 200), (?, ?, ?::uuid, 300, 0)
                """, id, UUID.randomUUID(), INVESTOR_A, id, UUID.randomUUID(), INVESTOR_B);
    }

    String status(UUID event) {
        return debts.get(event).orElseThrow().status();
    }

    int notices(String kind, DebtServices.Due d) {
        return jdbc.queryForObject("SELECT count(*) FROM governance.notification WHERE kind = ? AND subject_ref = ?",
                Integer.class, kind, "series/" + d.seriesId() + "/" + d.eventType().toLowerCase() + "/" + d.periodNo());
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> instructionLines(UUID event) throws Exception {
        String payload = (String) agent.submitted(event.toString()).getFirst().get("payload");
        return (List<Map<String, Object>>) JSON.readValue(payload, Map.class).get("lines");
    }

    /** Xác nhận chi có chữ ký BIDV, như giả lập gửi. */
    MvcResult confirm(UUID event, String currency, List<Map<String, Object>> lines, String failedLineRef)
            throws Exception {
        List<Map<String, Object>> out = lines.stream().map(l -> Map.<String, Object>of("lineRef", l.get("lineRef"),
                "amount", Map.of("amountMinor", l.get("amountMinor"), "currency", currency),
                "status", l.get("lineRef").equals(failedLineRef) ? "FAILED" : "PAID", "bankRef", "BIDVP" + l.get("lineRef")
                        .toString().substring(0, 8))).toList();
        byte[] raw = JSON.writeValueAsString(Map.of("instructionRef", event.toString(), "lines", out))
                .getBytes(StandardCharsets.UTF_8);
        return mvc.perform(MockMvcRequestBuilders.post("/v1/inbound/bidv/payout-confirmations")
                .header("X-Bank-Signature", bidvSign(raw)).header("Idempotency-Key", "p-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(raw)).andReturn();
    }

    // ---------- US-3.09 ----------

    @Test
    @DisplayName("US-3.09-AC3 nhắc T−15, kiểm tiền T−2; ngày T đủ tiền thì gửi lệnh chi, IPA xác nhận thì kỳ sang Đã chi và đối soát")
    void US_3_09_AC3_fundedPeriodIsPaidAndReconciled() throws Exception {
        Fixture f = fixture(2);
        DebtServices.Due c = f.coupon1();
        LocalDate t = c.paymentDate();
        snapshot(f.series(), 1, true);

        runner.run(t.minusDays(16));
        assertThat(status(c.eventId())).isNull();
        runner.run(t.minusDays(15));
        assertThat(status(c.eventId())).isEqualTo("REMINDED");
        assertThat(debts.get(c.eventId()).orElseThrow().estimatedMinor()).isPositive();
        assertThat(notices("DEBT_SERVICE_REMINDER", c)).isEqualTo(1);
        runner.run(t.minusDays(14));
        assertThat(notices("DEBT_SERVICE_REMINDER", c)).isEqualTo(1);

        // T−2: IPA chưa có tiền
        agent.setBalance(f.currency(), 0);
        runner.run(t.minusDays(2));
        assertThat(status(c.eventId())).isEqualTo("FUNDS_CHECKED");
        assertThat(debts.get(c.eventId()).orElseThrow().shortfallMinor()).isPositive();
        assertThat(notices("FUNDING_SHORT", c)).isEqualTo(1);
        byte[] shortEvent = nats.jetStreamManagement()
                .getLastMessage("WERWA", "werwa.issuance.debt_service.funding_short.v1").getData();
        assertThat(EventSchemas.validate("werwa.issuance.debt_service.funding_short.v1", shortEvent)).isEmpty();

        // Ngày T: đã đủ tiền
        agent.setBalance(f.currency(), 1_000_000_000L);
        runner.run(t);
        DebtServices.Due instructed = debts.get(c.eventId()).orElseThrow();
        assertThat(instructed.status()).isEqualTo("INSTRUCTED");
        assertThat(instructed.instructionRef()).isEqualTo(c.eventId().toString());
        // Q102: vị thế phong tỏa (200 của A) giữ lại; gửi 2 dòng 300 trái phiếu
        List<Map<String, Object>> lines = instructionLines(c.eventId());
        assertThat(lines).hasSize(2);
        Long frozenNet = jdbc.queryForObject("""
                SELECT net_minor FROM distribution.distribution_line l JOIN distribution.record_snapshot s
                    ON s.id = l.record_snapshot_id WHERE s.series_id = ? AND l.withheld_reason = 'FROZEN'
                """, Long.class, f.series());
        runner.run(t);
        assertThat(agent.submitted(c.eventId().toString())).hasSize(1);

        MvcResult ack = confirm(c.eventId(), f.currency(), lines, null);
        assertThat(ack.getResponse().getStatus()).as(ack.getResponse().getContentAsString()).isEqualTo(200);
        await().atMost(WAIT).until(() -> "RECONCILED".equals(status(c.eventId())));
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT paid_minor, withheld_minor FROM issuance.debt_service WHERE series_event_id = ?
                """, c.eventId());
        long paid = lines.stream().mapToLong(l -> Long.parseLong((String) l.get("amountMinor"))).sum();
        assertThat(((Number) row.get("paid_minor")).longValue()).isEqualTo(paid);
        assertThat(((Number) row.get("withheld_minor")).longValue()).isEqualTo(frozenNet);
        byte[] reconciled = nats.jetStreamManagement()
                .getLastMessage("WERWA", "werwa.issuance.debt_service.reconciled.v1").getData();
        assertThat(EventSchemas.validate("werwa.issuance.debt_service.reconciled.v1", reconciled)).isEmpty();

        // US-5.02-AC3: BIDV gửi lại xác nhận cùng dòng không xử lý lại
        assertThat(confirm(c.eventId(), f.currency(), lines, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration.payout_confirmation_line WHERE instruction_ref = ?",
                Integer.class, c.eventId().toString())).isEqualTo(2);
    }

    @Test
    @DisplayName("US-3.09-AC4 ngày T chưa đủ tiền thì kỳ sang Chậm trả, không gửi lệnh chi; đủ trong ân hạn thì gửi; quá ân hạn thì cảnh báo đỏ")
    void US_3_09_AC4_shortOnPaymentDateGoesLateThenGrace() throws Exception {
        Fixture f = fixture(2);
        DebtServices.Due c = f.coupon1();
        LocalDate t = c.paymentDate();
        snapshot(f.series(), 1, true);
        agent.setBalance(f.currency(), 10);

        runner.run(t);
        DebtServices.Due late = debts.get(c.eventId()).orElseThrow();
        assertThat(late.status()).isEqualTo("LATE");
        assertThat(late.graceUntil()).isAfter(t);
        assertThat(agent.submitted(c.eventId().toString())).isEmpty();

        // Trong ân hạn vẫn thiếu: giữ LATE
        runner.run(t.plusDays(1));
        assertThat(status(c.eventId())).isEqualTo("LATE");
        // Quá ân hạn
        runner.run(late.graceUntil().plusDays(1));
        assertThat(status(c.eventId())).isEqualTo("GRACE_EXPIRED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM governance.notification WHERE kind = 'PAYMENT_GRACE_EXPIRED' AND severity = 'CRITICAL'
                    AND subject_ref = ?
                """, Integer.class, "series/" + f.series() + "/coupon/1")).isEqualTo(1);

        // Kỳ khác của series khác: đủ tiền trong ân hạn thì gửi lệnh chi (cạnh 9)
        Fixture g = fixture(2);
        snapshot(g.series(), 1, true);
        agent.setBalance(g.currency(), 10);
        runner.run(g.coupon1().paymentDate());
        assertThat(status(g.coupon1().eventId())).isEqualTo("LATE");
        agent.setBalance(g.currency(), 1_000_000_000L);
        runner.run(g.coupon1().paymentDate().plusDays(1));
        assertThat(status(g.coupon1().eventId())).isEqualTo("INSTRUCTED");
    }

    @Test
    @DisplayName("US-3.09-AC2 chưa có VSDC xác nhận danh sách thì chưa tạo lệnh chi")
    void US_3_09_AC2_noInstructionBeforeRegistrarAttests() throws Exception {
        Fixture f = fixture(0);
        DebtServices.Due c = f.coupon1();
        snapshot(f.series(), 1, false);
        agent.setBalance(f.currency(), 1_000_000_000L);
        runner.run(c.paymentDate());
        assertThat(status(c.eventId())).isEqualTo("FUNDS_CHECKED");
        assertThat(agent.submitted(c.eventId().toString())).isEmpty();
        jdbc.update("UPDATE distribution.record_snapshot SET registrar_attested_at = now() WHERE series_id = ?", f.series());
        runner.run(c.paymentDate().plusDays(1));
        assertThat(status(c.eventId())).isEqualTo("INSTRUCTED");
    }

    // ---------- US-2.10 ----------

    @Test
    @DisplayName("US-2.10-AC1 kỳ gốc chạy như kỳ trả nợ: lệnh chi mệnh giá × số lượng, phần phong tỏa giữ lại, xác nhận rồi đối soát")
    void US_2_10_AC1_principalPeriodRunsLikeADebtService() throws Exception {
        Fixture f = fixture(0);
        DebtServices.Due m = f.maturity();
        snapshot(f.series(), m.periodNo(), true);
        agent.setBalance(f.currency(), 1_000_000_000L);

        runner.run(m.paymentDate());
        DebtServices.Due d = debts.get(m.eventId()).orElseThrow();
        assertThat(d.status()).isEqualTo("INSTRUCTED");
        // 800 trái phiếu × 100.000; phần phong tỏa 200 giữ lại
        assertThat(d.amountDueMinor()).isEqualTo(800L * 100_000);
        List<Map<String, Object>> lines = instructionLines(m.eventId());
        assertThat(lines).extracting(l -> l.get("amountMinor")).containsExactlyInAnyOrder("30000000", "30000000");
        assertThat(jdbc.queryForObject("""
                SELECT sum(tax_minor) FROM distribution.distribution_line l JOIN distribution.record_snapshot s
                    ON s.id = l.record_snapshot_id WHERE s.series_id = ? AND l.line_kind = 'PRINCIPAL'
                """, Long.class, f.series())).isZero();

        // Một dòng lỗi: báo Holco, kỳ giữ Đã gửi lệnh chi
        String failed = (String) lines.getFirst().get("lineRef");
        confirm(m.eventId(), f.currency(), lines, failed);
        await().atMost(WAIT).until(() -> notices("PAYOUT_FAILED", m) == 1);
        assertThat(status(m.eventId())).isEqualTo("INSTRUCTED");
    }

    @Test
    @DisplayName("US-2.10-AC1 kỳ gốc đã chi đủ thì đối soát và phát sự kiện cho bước hủy trái phiếu")
    void US_2_10_AC1_principalReconciledEmitsEvent() throws Exception {
        Fixture f = fixture(0);
        DebtServices.Due m = f.maturity();
        snapshot(f.series(), m.periodNo(), true);
        agent.setBalance(f.currency(), 1_000_000_000L);
        runner.run(m.paymentDate());
        confirm(m.eventId(), f.currency(), instructionLines(m.eventId()), null);
        await().atMost(WAIT).until(() -> "RECONCILED".equals(status(m.eventId())));
        assertThat(new String(nats.jetStreamManagement()
                .getLastMessage("WERWA", "werwa.issuance.debt_service.reconciled.v1").getData()))
                .contains(f.series().toString()).contains("\"eventType\":\"MATURITY\"").contains("\"withheldMinor\":\"20000000\"");
    }
}
