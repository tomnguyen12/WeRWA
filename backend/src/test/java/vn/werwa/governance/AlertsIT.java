package vn.werwa.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.IntegrationTest;
import vn.werwa.platform.OpenApiSchemas;

/** Cảnh báo hạ tầng vào màn hình vận hành (I5, US-6.01-AC3; Q56–Q58). */
class AlertsIT extends IntegrationTest {

    static final String HOLCO = "01a15bc1-f409-7589-aef1-774ceddccf3f";
    static final String SUPERVISOR = "01a15bc1-f408-7d65-ace1-f4c87b5c1cdc";
    static final String INVESTOR_A = "01a15bc1-f40a-7520-aeb5-19bb9d2faaa5";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    static RequestPostProcessor as(String subject, String org, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("org_id", org).claim("roles", List.of(role)));
    }

    static final RequestPostProcessor ONCALL = as("8e1f0000-0000-4000-8000-000000000001", HOLCO, "OPERATOR");
    static final RequestPostProcessor SUPERVISOR_USER = as("8e1f0000-0000-4000-8000-000000000002", SUPERVISOR, "SUPERVISOR");

    /** Thông điệp đúng định dạng webhook v4 của Alertmanager. */
    static String webhook(String status, String fingerprint, String startsAt, String endsAt) {
        return """
                {"receiver":"werwa","status":"%1$s","alerts":[{"status":"%1$s",
                 "labels":{"alertname":"ValidatorDown","severity":"critical","instance":"v3:9545"},
                 "annotations":{"summary":"Chỉ còn 3 validator đang ký khối"},
                 "startsAt":"%3$s","endsAt":"%4$s","generatorURL":"http://prometheus:9090/graph","fingerprint":"%2$s"}],
                 "groupLabels":{"alertname":"ValidatorDown"},"commonLabels":{"alertname":"ValidatorDown","severity":"critical"},
                 "commonAnnotations":{},"externalURL":"http://alertmanager:9093","version":"4",
                 "groupKey":"{}:{alertname=\\"ValidatorDown\\"}","truncatedAlerts":0}
                """.formatted(status, fingerprint, startsAt, endsAt);
    }

    MvcResult send(String body, String token) throws Exception {
        var request = post("/v1/inbound/alerts/alertmanager").contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request).andReturn();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(MvcResult r) throws Exception {
        return JSON.readValue(r.getResponse().getContentAsString(), Map.class);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> find(String fingerprint, RequestPostProcessor who) throws Exception {
        MvcResult r = mvc.perform(get("/v1/alerts").param("limit", "200").with(who)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        List<Map<String, Object>> items = (List<Map<String, Object>>) body(r).get("items");
        for (Map<String, Object> a : items) {
            assertThat(OpenApiSchemas.validate("OperationalAlert", JSON.writeValueAsString(a))).isEmpty();
        }
        return items.stream().filter(a -> fingerprint.equals(a.get("fingerprint"))).findFirst().orElse(null);
    }

    @Test
    @DisplayName("Q56 webhook cảnh báo thiếu hoặc sai token thì 401")
    void webhookNeedsTheConfiguredToken() throws Exception {
        String body = webhook("firing", "fp-" + UUID.randomUUID(), "2026-10-27T08:00:00Z", "0001-01-01T00:00:00Z");
        for (String token : new String[] {null, "wrong-token"}) {
            MvcResult r = send(body, token);
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(body(r).get("code")).isEqualTo("INBOUND_TOKEN_INVALID");
        }
    }

    @Test
    @DisplayName("US-6.01-AC3 cảnh báo còn 3 validator hiện trên màn hình vận hành; xác nhận, hết cảnh báo")
    void US_6_01_AC3_infrastructureAlertReachesTheOperationsScreen() throws Exception {
        String fp = "fp-" + UUID.randomUUID();
        String firing = webhook("firing", fp, "2026-10-27T08:00:00Z", "0001-01-01T00:00:00Z");
        MvcResult r = send(firing, "test-alert-token");
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(OpenApiSchemas.validate("Ack", r.getResponse().getContentAsString())).isEmpty();
        send(firing, "test-alert-token"); // Alertmanager gửi lại theo repeat_interval: không tạo bản ghi mới

        Map<String, Object> alert = find(fp, ONCALL);
        assertThat(alert).isNotNull();
        assertThat(alert.get("status")).isEqualTo("FIRING");
        assertThat(alert.get("name")).isEqualTo("ValidatorDown");
        assertThat(alert.get("severity")).isEqualTo("critical");
        assertThat(alert.get("summary")).isEqualTo("Chỉ còn 3 validator đang ký khối");
        assertThat(alert.get("endsAt")).isNull();
        assertThat(find(fp, SUPERVISOR_USER)).isNotNull();
        assertThat(mvc.perform(get("/v1/alerts").with(as("x", INVESTOR_A, "INVESTOR"))).andReturn().getResponse()
                .getStatus()).isEqualTo(403);

        String id = (String) alert.get("id");
        MvcResult denied = mvc.perform(post("/v1/alerts/" + id + "/acknowledge").with(SUPERVISOR_USER)
                .header("Idempotency-Key", "k-" + UUID.randomUUID())).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        MvcResult ack = mvc.perform(post("/v1/alerts/" + id + "/acknowledge").with(ONCALL)
                .header("Idempotency-Key", "k-" + UUID.randomUUID())).andReturn();
        assertThat(ack.getResponse().getStatus()).isEqualTo(200);
        assertThat(OpenApiSchemas.validate("OperationalAlert", ack.getResponse().getContentAsString())).isEmpty();
        assertThat(body(ack).get("status")).isEqualTo("ACKNOWLEDGED");
        assertThat(body(ack).get("acknowledgedBy")).isEqualTo("8e1f0000-0000-4000-8000-000000000001");

        send(webhook("resolved", fp, "2026-10-27T08:00:00Z", "2026-10-27T08:05:00Z"), "test-alert-token");
        Map<String, Object> resolved = find(fp, ONCALL);
        assertThat(resolved.get("status")).isEqualTo("RESOLVED");
        assertThat(resolved.get("acknowledgedBy")).isEqualTo("8e1f0000-0000-4000-8000-000000000001");
        assertThat((String) resolved.get("endsAt")).startsWith("2026-10-27T08:05");

        // Cùng cảnh báo cháy lại lần sau (startsAt mới) là một bản ghi mới
        send(webhook("firing", fp, "2026-10-28T01:00:00Z", "0001-01-01T00:00:00Z"), "test-alert-token");
        MvcResult firingOnly = mvc.perform(get("/v1/alerts").param("status", "FIRING").param("limit", "200").with(ONCALL))
                .andReturn();
        assertThat(firingOnly.getResponse().getContentAsString()).contains(fp).doesNotContain(id);
    }
}
