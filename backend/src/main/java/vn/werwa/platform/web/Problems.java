package vn.werwa.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/** Dựng problem+json theo lược đồ {@code Problem} của OpenAPI: type, title, status, code, detail, instance. */
public final class Problems {

    public static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private Problems() {}

    public static ProblemDetail of(ErrorCode error, String detail, String instance) {
        ProblemDetail p = ProblemDetail.forStatus(error.status());
        p.setType(URI.create(error.type()));
        p.setTitle(error.title());
        p.setDetail(detail);
        if (instance != null) {
            p.setInstance(URI.create(instance));
        }
        p.setProperty("code", error.code());
        return p;
    }

    /** Ghi thẳng ra response, cho filter chạy trước DispatcherServlet (bảo mật, Idempotency-Key). */
    public static void write(HttpServletRequest request, HttpServletResponse response, ErrorCode error, String detail)
            throws IOException {
        response.resetBuffer();
        response.setStatus(error.status().value());
        response.setContentType(PROBLEM_JSON.toString());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        StringBuilder json = new StringBuilder(256).append('{');
        field(json, "type", error.type()).append(',');
        field(json, "title", error.title()).append(',');
        json.append("\"status\":").append(error.status().value()).append(',');
        field(json, "code", error.code());
        if (detail != null) {
            field(json.append(','), "detail", detail);
        }
        field(json.append(','), "instance", request.getRequestURI());
        json.append('}');
        response.getOutputStream().write(json.toString().getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();
    }

    private static StringBuilder field(StringBuilder json, String name, String value) {
        json.append('"').append(name).append("\":\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"');
    }
}
