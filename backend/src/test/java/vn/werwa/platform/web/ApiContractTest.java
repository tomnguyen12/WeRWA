package vn.werwa.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import vn.werwa.platform.security.Role;
import vn.werwa.platform.web.ApiContract.Operation;

/** Hợp đồng {@code api/openapi.yaml} đọc đúng: vai, kênh, header bắt buộc, khớp đường dẫn (B1.0). */
class ApiContractTest {

    static final ApiContract CONTRACT = ApiContract.load(new ClassPathResource("contract/openapi.yaml"));

    @Test
    void readsEveryOperation() {
        assertThat(CONTRACT.operations()).hasSize(64);
        assertThat(CONTRACT.operations()).allMatch(op -> op.anyRole() || !op.roles().isEmpty());
    }

    @Test
    void readsRolesAndHeaders() {
        Operation create = op(HttpMethod.POST, "/v1/organizations");
        assertThat(create.operationId()).isEqualTo("createOrganization");
        assertThat(create.roles()).containsExactly(Role.OPERATOR);
        assertThat(create.anyRole()).isFalse();
        assertThat(create.idempotencyKeyRequired()).isTrue();
        assertThat(create.ifMatchRequired()).isFalse();

        Operation get = op(HttpMethod.GET, "/v1/organizations/0192a1f0-0000-7000-8000-000000000000");
        assertThat(get.anyRole()).isTrue();
        assertThat(get.idempotencyKeyRequired()).isFalse();

        Operation amend = op(HttpMethod.PATCH, "/v1/orders/x");
        assertThat(amend.ifMatchRequired()).isTrue();
        assertThat(amend.roles()).containsExactlyInAnyOrder(Role.INVESTOR, Role.SYNDICATE);

        Operation limits = op(HttpMethod.GET, "/v1/programs/x/limits");
        assertThat(limits.roles()).containsExactlyInAnyOrder(Role.ISSUER, Role.OPERATING_AGENCY);
    }

    @Test
    void bankWebhooksUseMtls() {
        assertThat(op(HttpMethod.POST, "/v1/inbound/bidv/credit-notifications").bankMtls()).isTrue();
        assertThat(op(HttpMethod.POST, "/v1/organizations").bankMtls()).isFalse();
    }

    @Test
    void picksTheMostSpecificPath() {
        assertThat(op(HttpMethod.POST, "/v1/offerings/x/open").operationId()).isEqualTo("openOffering");
        assertThat(op(HttpMethod.GET, "/v1/allocation-lists/x/proofs/y").operationId()).isEqualTo("getAllocationProof");
        assertThat(op(HttpMethod.GET, "/v1/series/x/issuance-progress").operationId()).isEqualTo("getIssuanceProgress");
    }

    @Test
    void distinguishesUnknownPathFromUnknownMethod() {
        assertThat(CONTRACT.match(HttpMethod.PUT, "/v1/organizations")).isEmpty();
        assertThat(CONTRACT.hasPath("/v1/organizations")).isTrue();
        assertThat(CONTRACT.hasPath("/v1/nothing")).isFalse();
    }

    private static Operation op(HttpMethod method, String path) {
        return CONTRACT.match(method, path).orElseThrow();
    }
}
