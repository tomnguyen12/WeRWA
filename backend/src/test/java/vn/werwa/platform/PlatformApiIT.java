package vn.werwa.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.werwa.IntegrationTest;

/** Lớp nền API của B1 chạy với PostgreSQL thật: quyền theo hợp đồng, Idempotency-Key, If-Match, problem+json. */
class PlatformApiIT extends IntegrationTest {


    // Tổ chức thử của devnet (infra/keycloak/seed/orgs.json, I6)
    static final String HOLCO = "01a15bc1-f409-7589-aef1-774ceddccf3f";
    static final String UBND = "01a15bc1-f400-7c28-a7a8-d5d4cc19328b";
    static final String INVESTOR_A = "01a15bc1-f40a-7520-aeb5-19bb9d2faaa5";
    static final String INVESTOR_B = "01a15bc1-f40b-7c54-aa64-c99f087b0103";

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    static RequestPostProcessor user(String subject, String orgId, String... roles) {
        return jwt().jwt(j -> j.subject(subject).claim("org_id", orgId).claim("roles", List.of(roles)));
    }

    static RequestPostProcessor holcoMaker() {
        return user("holco-maker", HOLCO, "OPERATOR");
    }

    MvcResult problem(MockHttpServletRequestBuilder request, int status, String code) throws Exception {
        MvcResult result = mvc.perform(request)
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andReturn();
        assertThat(OpenApiSchemas.validate("Problem", result.getResponse().getContentAsString())).isEmpty();
        return result;
    }

    @Nested
    @DisplayName("US-4.07 phân quyền theo tổ chức và vai")
    class Authorization {

        @Test
        void withoutTokenIs401() throws Exception {
            problem(get("/v1/organizations/" + HOLCO), 401, "UNAUTHENTICATED");
        }

        @Test
        void roleNotInXWerwaRolesIs403() throws Exception {
            problem(post("/v1/organizations")
                            .with(user("ubnd-maker", UBND, "ISSUER"))
                            .header("Idempotency-Key", "k-role")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"legalName\":\"X\"}"),
                    403, "ROLE_NOT_ALLOWED");
        }

        @Test
        void userRoleOutsideOrganizationRolesIs403() throws Exception {
            problem(get("/v1/organizations/" + UBND).with(user("ubnd-maker", UBND, "OPERATOR")),
                    403, "ROLE_NOT_IN_ORGANIZATION");
        }

        @Test
        void unknownOrganizationIs403() throws Exception {
            problem(get("/v1/organizations/" + HOLCO).with(user("x", UUID.randomUUID().toString(), "OPERATOR")),
                    403, "ORGANIZATION_UNKNOWN");
        }

        @Test
        void missingOrgClaimIs401() throws Exception {
            problem(get("/v1/organizations/" + HOLCO)
                            .with(jwt().jwt(j -> j.subject("x").claim("roles", List.of("OPERATOR")))),
                    401, "TOKEN_CLAIMS_INVALID");
        }

        @Test
        void anyRoleApiAcceptsEveryRole() throws Exception {
            mvc.perform(get("/v1/organizations/" + INVESTOR_A).with(user("inv-a", INVESTOR_A, "INVESTOR")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("US-4.07-AC2 người dùng tổ chức A gọi đối tượng của tổ chức B bị 403")
        void US_4_07_AC2_crossOrganizationObjectIs403() throws Exception {
            problem(get("/v1/organizations/" + INVESTOR_B).with(user("inv-a", INVESTOR_A, "INVESTOR")),
                    403, "ORG_SCOPE_VIOLATION");
        }

        @Test
        void routeOutsideContractIs404() throws Exception {
            problem(get("/v1/not-in-contract").with(holcoMaker()), 404, "ROUTE_NOT_FOUND");
        }

        @Test
        void methodOutsideContractIs405() throws Exception {
            problem(put("/v1/organizations").with(holcoMaker()), 405, "METHOD_NOT_ALLOWED");
        }

        @Test
        void bankWebhookIsClosedUntilB8() throws Exception {
            problem(post("/v1/inbound/bidv/credit-notifications")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"),
                    403, "INBOUND_CHANNEL_NOT_ENABLED");
        }
    }

    @Nested
    @DisplayName("Idempotency-Key")
    class Idempotency {

        @Test
        void missingKeyIs400() throws Exception {
            problem(post("/v1/organizations")
                            .with(holcoMaker())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"legalName\":\"X\"}"),
                    400, "IDEMPOTENCY_KEY_REQUIRED");
        }

        @Test
        void invalidKeyIs400() throws Exception {
            problem(post("/v1/organizations")
                            .with(holcoMaker())
                            .header("Idempotency-Key", "x".repeat(129))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"legalName\":\"X\"}"),
                    400, "IDEMPOTENCY_KEY_INVALID");
        }

        @Test
        void sameKeySameRequestReplaysFirstResult() throws Exception {
            String key = "k-" + UUID.randomUUID();
            int before = ContractProbeController.CREATED.get();
            MvcResult first = mvc.perform(create(holcoMaker(), key, "{\"legalName\":\"A\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().doesNotExist("Idempotent-Replayed"))
                    .andReturn();
            MvcResult second = mvc.perform(create(holcoMaker(), key, "{\"legalName\":\"A\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andReturn();

            assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
            assertThat(second.getResponse().getHeader("Location")).isEqualTo(first.getResponse().getHeader("Location"));
            assertThat(ContractProbeController.CREATED.get()).isEqualTo(before + 1);
        }

        @Test
        void sameKeyDifferentBodyIs422() throws Exception {
            String key = "k-" + UUID.randomUUID();
            mvc.perform(create(holcoMaker(), key, "{\"legalName\":\"A\"}")).andExpect(status().isCreated());
            problem(create(holcoMaker(), key, "{\"legalName\":\"B\"}"), 422, "IDEMPOTENCY_KEY_REUSED");
        }

        @Test
        void sameKeyOtherUserIsIndependent() throws Exception {
            String key = "k-" + UUID.randomUUID();
            mvc.perform(create(holcoMaker(), key, "{\"legalName\":\"A\"}")).andExpect(status().isCreated());
            mvc.perform(create(user("holco-checker", HOLCO, "OPERATOR"), key, "{\"legalName\":\"A\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().doesNotExist("Idempotent-Replayed"));
        }

        @Test
        void serverErrorIsNotStoredSoRetryRuns() throws Exception {
            String key = "k-" + UUID.randomUUID();
            problem(create(holcoMaker(), key, "{\"fail\":true}"), 500, "INTERNAL_ERROR");
            // Lần thử lại cùng khóa chạy lại xử lý (không bị trả 500 cũ, không bị 409)
            problem(create(holcoMaker(), key, "{\"fail\":true}"), 500, "INTERNAL_ERROR");
        }

        @Test
        void clientErrorIsStoredAndReplayed() throws Exception {
            String key = "k-" + UUID.randomUUID();
            mvc.perform(post("/v1/organizations")
                            .with(holcoMaker())
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
            mvc.perform(post("/v1/organizations")
                            .with(holcoMaker())
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Idempotent-Replayed", "true"));
        }

        private MockHttpServletRequestBuilder create(RequestPostProcessor who, String key, String body) {
            return post("/v1/organizations")
                    .with(who)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body);
        }
    }

    @Nested
    @DisplayName("If-Match và phân trang")
    class Preconditions {

        static final String ORDER = "/v1/orders/" + UUID.randomUUID();

        @Test
        void missingIfMatchIs428() throws Exception {
            problem(patch(ORDER)
                            .with(user("inv-a", INVESTOR_A, "INVESTOR"))
                            .header("Idempotency-Key", "k-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"),
                    428, "PRECONDITION_REQUIRED");
        }

        @Test
        void staleIfMatchIs412() throws Exception {
            problem(patch(ORDER)
                            .with(user("inv-a", INVESTOR_A, "INVESTOR"))
                            .header("Idempotency-Key", "k-" + UUID.randomUUID())
                            .header("If-Match", "\"2\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"),
                    412, "PRECONDITION_FAILED");
        }

        @Test
        void currentIfMatchReturnsNewETag() throws Exception {
            mvc.perform(patch(ORDER)
                            .with(user("inv-a", INVESTOR_A, "INVESTOR"))
                            .header("Idempotency-Key", "k-" + UUID.randomUUID())
                            .header("If-Match", "\"3\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("ETag", "\"4\""));
        }

        @Test
        void limitAbove200Is400() throws Exception {
            problem(get("/v1/holdings?limit=201").with(user("inv-a", INVESTOR_A, "INVESTOR")),
                    400, "PAGE_LIMIT_INVALID");
        }
    }
}
