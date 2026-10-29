package vn.werwa.distribution.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
import vn.werwa.issuance.TestDocuments;
import vn.werwa.platform.OpenApiSchemas;
import vn.werwa.platform.web.ApiException;

/** Dòng chi trả coupon từ danh sách chốt (US-2.09; Q68–Q72) với PostgreSQL thật. */
class PayoutIT extends IntegrationTest {

    static final String HOLCO = "01a15bc1-f409-7589-aef1-774ceddccf3f";
    static final String AGENCY = "01a15bc1-f401-70b2-9cfa-eb63c4f24ecc";
    static final String VSDC = "01a15bc1-f403-7f0b-8fb9-a75cdc4b5b00";
    static final String CUSTODIAN = "01a15bc1-f406-76b6-ac98-237eb9f37341";
    static final String INVESTOR_A = "01a15bc1-f40a-7520-aeb5-19bb9d2faaa5";
    static final JsonMapper JSON = JsonMapper.builder().build();

    static RequestPostProcessor as(String subject, String org, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("org_id", org).claim("roles", List.of(role)));
    }

    static final RequestPostProcessor HOLCO_MAKER = as("bc1f0000-0000-4000-8000-000000000001", HOLCO, "OPERATOR");
    static final RequestPostProcessor AGENCY_MAKER = as("bc1f0000-0000-4000-8000-0000000000a1", AGENCY, "OPERATING_AGENCY");
    static final RequestPostProcessor AGENCY_CHECKER = as("bc1f0000-0000-4000-8000-0000000000a2", AGENCY, "OPERATING_AGENCY");
    static final RequestPostProcessor REGISTRAR = as("bc1f0000-0000-4000-8000-000000000003", VSDC, "REGISTRAR");
    static final RequestPostProcessor CUSTODIAN_USER = as("bc1f0000-0000-4000-8000-000000000004", CUSTODIAN, "CUSTODIAN");

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PayoutService payouts;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    MvcResult post(String path, RequestPostProcessor who, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path).with(who).header("Idempotency-Key", "k-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body))).andReturn();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(MvcResult r) throws Exception {
        return JSON.readValue(r.getResponse().getContentAsString(), Map.class);
    }

    UUID series() throws Exception {
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
        p.put("documentId", TestDocuments.uploadId(mvc, HOLCO_MAKER, "HDND_RESOLUTION").toString());
        MvcResult program = post("/v1/programs", AGENCY_MAKER, p);
        UUID programId = UUID.fromString((String) body(program).get("id"));
        UUID approval = jdbc.queryForObject("SELECT approval_request_id FROM issuance.issuance_program WHERE id = ?",
                UUID.class, programId);
        post("/v1/approvals/" + approval + "/decision", AGENCY_CHECKER, Map.of("decision", "APPROVE"));
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
        s.put("termsDocumentId", TestDocuments.uploadId(mvc, HOLCO_MAKER, "TERM_SHEET").toString());
        MvcResult series = post("/v1/series", HOLCO_MAKER, s);
        assertThat(series.getResponse().getStatus()).as(series.getResponse().getContentAsString()).isEqualTo(201);
        return UUID.fromString((String) body(series).get("id"));
    }

    /** Danh sách chốt kỳ 1 (B11b chụp từ chuỗi; ở đây nạp thẳng). */
    void snapshot(UUID series, UUID walletA, UUID walletCustodian) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO distribution.record_snapshot
                    (id, series_id, period_no, record_block, register_hash, holder_count, total_quantity, created_at)
                VALUES (?, ?, 1, 123456, ?, 2, 800, now())
                """, id, series, "0x" + "ab".repeat(32));
        jdbc.update("""
                INSERT INTO distribution.snapshot_holding (record_snapshot_id, wallet_id, investor_org_id, quantity, frozen_quantity)
                VALUES (?, ?, ?::uuid, 500, 200), (?, ?, ?::uuid, 300, 0)
                """, id, walletA, INVESTOR_A, id, walletCustodian, CUSTODIAN);
    }

    @Test
    @DisplayName("US-2.09 dòng chi trả có gross, thuế, net; phần phong tỏa giữ lại; tổng khớp số phải trả")
    @SuppressWarnings("unchecked")
    void couponLinesForAPeriod() throws Exception {
        UUID series = series();
        UUID walletA = UUID.randomUUID();
        UUID walletCustodian = UUID.randomUUID();
        snapshot(series, walletA, walletCustodian);

        // Q72: chưa có lãi suất của kỳ thì không tính được
        assertThatThrownBy(() -> payouts.calculate(series, 1)).isInstanceOf(ApiException.class)
                .hasMessageContaining("PERIOD_RATE_MISSING");
        MvcResult notYet = mvc.perform(get("/v1/series/" + series + "/snapshots/1").with(REGISTRAR)).andReturn();
        assertThat(notYet.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(notYet).get("code")).isEqualTo("SNAPSHOT_NOT_CALCULATED");

        jdbc.update("UPDATE issuance.series_event SET rate_ppm = 53000, rate_source = 'FIXED_AT_PRICING' WHERE series_id = ?",
                series);
        CouponCalculator.Result r = payouts.calculate(series, 1);
        assertThat(r.amountDueMinor()).isEqualTo(21_200_00);

        MvcResult res = mvc.perform(get("/v1/series/" + series + "/snapshots/1").with(REGISTRAR)).andReturn();
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(OpenApiSchemas.validate("Snapshot", res.getResponse().getContentAsString())).isEmpty();
        Map<String, Object> snap = body(res);
        assertThat(snap.get("ratePpm")).isEqualTo(53_000);
        assertThat(((Map<String, Object>) snap.get("amountDue")).get("amountMinor")).isEqualTo("2120000");
        assertThat(((Map<String, Object>) snap.get("roundingResidual")).get("amountMinor")).isEqualTo("0");
        List<Map<String, Object>> lines = (List<Map<String, Object>>) snap.get("lines");
        assertThat(lines).hasSize(3);
        assertThat(lines).filteredOn(l -> "FROZEN".equals(l.get("withheldReason"))).singleElement().satisfies(l -> {
            assertThat(l.get("walletId")).isEqualTo(walletA.toString());
            assertThat(l.get("quantity")).isEqualTo(200);
            assertThat(((Map<String, Object>) l.get("gross")).get("amountMinor")).isEqualTo("530000");
            assertThat(((Map<String, Object>) l.get("tax")).get("amountMinor")).isEqualTo("26500");
            assertThat(((Map<String, Object>) l.get("net")).get("amountMinor")).isEqualTo("503500");
        });
        long sum = lines.stream().mapToLong(l -> Long.parseLong((String) ((Map<String, Object>) l.get("gross"))
                .get("amountMinor"))).sum();
        assertThat(sum).isEqualTo(21_200_00);

        // Lưu ký chỉ thấy dòng của mình
        MvcResult custodian = mvc.perform(get("/v1/series/" + series + "/snapshots/1").with(CUSTODIAN_USER)).andReturn();
        assertThat(custodian.getResponse().getStatus()).isEqualTo(200);
        assertThat((List<?>) body(custodian).get("lines")).hasSize(1);

        // Tính lại cho cùng kết quả, không nhân đôi dòng
        payouts.calculate(series, 1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM distribution.distribution_line l
                JOIN distribution.record_snapshot s ON s.id = l.record_snapshot_id WHERE s.series_id = ?
                """, Integer.class, series)).isEqualTo(3);
        assertThat(mvc.perform(get("/v1/series/" + series + "/snapshots/2").with(REGISTRAR)).andReturn().getResponse()
                .getStatus()).isEqualTo(404);
    }
}
