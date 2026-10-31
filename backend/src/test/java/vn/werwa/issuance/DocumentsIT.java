package vn.werwa.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.IntegrationTest;
import vn.werwa.platform.OpenApiSchemas;

/** Hồ sơ phát hành có phiên bản (US-2.04-AC1, AC3; Q29, Q30) với PostgreSQL thật và kho S3 giả. */
class DocumentsIT extends IntegrationTest {

    static final String HOLCO = "01a15bc1-f409-7589-aef1-774ceddccf3f";
    static final String UBND = "01a15bc1-f400-7c28-a7a8-d5d4cc19328b";
    static final String INVESTOR_A = "01a15bc1-f40a-7520-aeb5-19bb9d2faaa5";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    static RequestPostProcessor as(String subject, String org, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("org_id", org).claim("roles", List.of(role)));
    }

    static RequestPostProcessor ubnd() {
        return as("ubnd-user", UBND, "ISSUER");
    }

    static RequestPostProcessor investor() {
        return as("inv-a", INVESTOR_A, "INVESTOR");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(MvcResult r) throws Exception {
        return JSON.readValue(r.getResponse().getContentAsString(), Map.class);
    }

    static void matches(String schema, MvcResult r) throws Exception {
        assertThat(OpenApiSchemas.validate(schema, r.getResponse().getContentAsString())).isEmpty();
    }

    static String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    MvcResult upload(RequestPostProcessor who, String visibility, String content, UUID supersedes) throws Exception {
        return TestDocuments.upload(mvc, who, "TERM_SHEET", visibility, content, supersedes);
    }

    @Test
    @DisplayName("US-2.04-AC1 tải lên nhận mã tài liệu và SHA-256; bản mới trỏ về bản trước, không ghi đè")
    void US_2_04_AC1_uploadReturnsIdAndSha256AndNewVersionsDoNotOverwrite() throws Exception {
        String v1Content = "term sheet v1 " + UUID.randomUUID();
        MvcResult v1 = upload(ubnd(), "INTERNAL", v1Content, null);
        assertThat(v1.getResponse().getStatus()).isEqualTo(201);
        matches("Document", v1);
        assertThat(body(v1).get("sha256")).isEqualTo(sha256(v1Content));
        assertThat(body(v1).get("versionNo")).isEqualTo(1);
        UUID v1Id = UUID.fromString((String) body(v1).get("id"));
        assertThat(v1.getResponse().getHeader("Location")).isEqualTo("/v1/documents/" + v1Id);

        String v2Content = "term sheet v2 " + UUID.randomUUID();
        MvcResult v2 = upload(ubnd(), "INTERNAL", v2Content, v1Id);
        assertThat(v2.getResponse().getStatus()).isEqualTo(201);
        matches("Document", v2);
        assertThat(body(v2).get("versionNo")).isEqualTo(2);
        assertThat(body(v2).get("supersedesId")).isEqualTo(v1Id.toString());
        assertThat(body(v2).get("id")).isNotEqualTo(v1Id.toString());

        // Bản 1 vẫn còn nguyên
        MvcResult old = mvc.perform(get("/v1/documents/" + v1Id).with(ubnd())).andReturn();
        assertThat(old.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(old).get("sha256")).isEqualTo(sha256(v1Content));

        // Không rẽ nhánh: bản 1 đã có bản mới thì không tải bản khác thay bản 1 được
        MvcResult branch = upload(ubnd(), "INTERNAL", "nhánh " + UUID.randomUUID(), v1Id);
        assertThat(branch.getResponse().getStatus()).isEqualTo(409);
        matches("Problem", branch);
        assertThat(body(branch).get("code")).isEqualTo("DOCUMENT_ALREADY_SUPERSEDED");

        List<String> audit = jdbc.queryForList(
                "SELECT action FROM governance.audit_log WHERE subject_ref = ?", String.class, "document/" + v1Id);
        assertThat(audit).contains("DOCUMENT_UPLOADED");
    }

    @Test
    @DisplayName("US-2.04-AC3 nhà đầu tư không thấy tài liệu INTERNAL; thấy tài liệu INVESTOR")
    void US_2_04_AC3_investorCannotSeeInternalDocuments() throws Exception {
        UUID internal = UUID.fromString((String) body(upload(ubnd(), "INTERNAL", "nội bộ " + UUID.randomUUID(), null))
                .get("id"));
        UUID shared = UUID.fromString((String) body(upload(ubnd(), "INVESTOR", "bản chào " + UUID.randomUUID(), null))
                .get("id"));

        for (String path : List.of("/v1/documents/" + internal, "/v1/documents/" + internal + "/content")) {
            MvcResult hidden = mvc.perform(get(path).with(investor())).andReturn();
            assertThat(hidden.getResponse().getStatus()).as(path).isEqualTo(404);
            matches("Problem", hidden);
            assertThat(body(hidden).get("code")).isEqualTo("DOCUMENT_NOT_FOUND");
        }
        assertThat(mvc.perform(get("/v1/documents/" + internal).with(as("holco", HOLCO, "OPERATOR")))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        MvcResult visible = mvc.perform(get("/v1/documents/" + shared).with(investor())).andReturn();
        assertThat(visible.getResponse().getStatus()).isEqualTo(200);
        matches("Document", visible);
    }

    @Test
    @DisplayName("Q29 nội dung tải về qua URL ký sẵn có hạn; nội dung khớp SHA-256")
    void contentIsServedThroughAShortLivedPresignedUrl() throws Exception {
        String content = "bản chào " + UUID.randomUUID();
        UUID id = UUID.fromString((String) body(upload(ubnd(), "INVESTOR", content, null)).get("id"));

        MvcResult r = mvc.perform(get("/v1/documents/" + id + "/content").with(investor())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        matches("DocumentDownload", r);
        assertThat(body(r).get("sha256")).isEqualTo(sha256(content));

        HttpResponse<String> fetched = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create((String) body(r).get("url"))).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(content);
    }

    @Test
    void unknownDocumentIs404AndEmptyFileIs400() throws Exception {
        MvcResult missing = mvc.perform(get("/v1/documents/" + UUID.randomUUID()).with(ubnd())).andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(missing).get("code")).isEqualTo("DOCUMENT_NOT_FOUND");

        MvcResult empty = upload(ubnd(), "INTERNAL", "", null);
        assertThat(empty.getResponse().getStatus()).isEqualTo(400);
        matches("Problem", empty);
        assertThat(body(empty).get("code")).isEqualTo("DOCUMENT_EMPTY");
    }
}
