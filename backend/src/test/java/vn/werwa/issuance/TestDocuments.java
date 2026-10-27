package vn.werwa.issuance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/** Tải tài liệu thử qua API, cho kiểm thử của mô-đun khác cần mã tài liệu có thật (Q26). */
public final class TestDocuments {

    private TestDocuments() {}

    public static MvcResult upload(MockMvc mvc, RequestPostProcessor who, String docType, String visibility,
            String content, UUID supersedesId) throws Exception {
        var request = multipart("/v1/documents")
                .file(new MockMultipartFile("file", "tai-lieu.pdf", "application/pdf",
                        content.getBytes(StandardCharsets.UTF_8)))
                .param("docType", docType)
                .param("title", "Tài liệu thử " + docType)
                .param("visibility", visibility)
                .with(who)
                .header("Idempotency-Key", "k-" + UUID.randomUUID());
        if (supersedesId != null) {
            request.param("supersedesId", supersedesId.toString());
        }
        return mvc.perform(request).andReturn();
    }

    public static UUID uploadId(MockMvc mvc, RequestPostProcessor who) throws Exception {
        return uploadId(mvc, who, "AUTHORIZATION");
    }

    @SuppressWarnings("unchecked")
    public static UUID uploadId(MockMvc mvc, RequestPostProcessor who, String docType) throws Exception {
        MvcResult r = upload(mvc, who, docType, "INTERNAL", "nội dung " + UUID.randomUUID(), null);
        if (r.getResponse().getStatus() != 201) {
            throw new IllegalStateException("tải tài liệu thử lỗi: " + r.getResponse().getContentAsString());
        }
        Map<String, Object> body = JsonMapper.builder().build().readValue(r.getResponse().getContentAsString(), Map.class);
        return UUID.fromString((String) body.get("id"));
    }
}
