package vn.werwa.platform.web.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import vn.werwa.platform.audit.AuditSink;
import vn.werwa.platform.audit.WriteAudit;
import vn.werwa.platform.security.ContractAuthorizationFilter;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.web.ApiContract.Operation;
import vn.werwa.platform.web.PlatformError;
import vn.werwa.platform.web.Problems;
import vn.werwa.platform.web.idempotency.IdempotencyStore.Entry;
import vn.werwa.platform.web.idempotency.IdempotencyStore.StoredResponse;

/**
 * {@code Idempotency-Key} cho mọi lệnh ghi (đặc tả API, 05; Q6). Chỉ chạy với API mà hợp đồng khai báo header này bắt
 * buộc, sau khi đã kiểm quyền.
 *
 * <ul>
 *   <li>Thiếu khóa: 400. Khóa sai dạng (rỗng, dài quá 128, ký tự ngoài ASCII in được): 400.
 *   <li>Cùng người dùng, cùng khóa, cùng request trong 24 giờ: trả lại đúng kết quả lần đầu, thêm header
 *       {@code Idempotent-Replayed: true}.
 *   <li>Cùng khóa, request khác (phương thức, đường dẫn hoặc thân khác): 422. Lần đầu chưa xử lý xong: 409.
 *   <li>Kết quả 5xx không được lưu, để thử lại được.
 *   <li>Kết quả 2xx ghi một dòng nhật ký kiểm toán (Q22), sau khi xử lý xong.
 * </ul>
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Pattern KEY = Pattern.compile("^[\\x21-\\x7E]{1,128}$");

    private final IdempotencyStore store;
    private final AuditSink audit;

    public IdempotencyFilter(IdempotencyStore store, AuditSink audit) {
        this.store = store;
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getAttribute(ContractAuthorizationFilter.OPERATION_ATTRIBUTE) instanceof Operation op)
                || !op.idempotencyKeyRequired();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Operation op = (Operation) request.getAttribute(ContractAuthorizationFilter.OPERATION_ATTRIBUTE);
        String key = request.getHeader(HEADER);
        if (key == null) {
            Problems.write(request, response, PlatformError.IDEMPOTENCY_KEY_REQUIRED, null);
            return;
        }
        if (!KEY.matcher(key).matches()) {
            Problems.write(request, response, PlatformError.IDEMPOTENCY_KEY_INVALID, "1–128 ký tự ASCII in được");
            return;
        }
        String subject = CurrentUser.get().subject();

        HttpServletRequest effective = request;
        MessageDigest digest = sha256();
        digest.update((request.getMethod() + ' ' + request.getRequestURI() + '?' + nullToEmpty(request.getQueryString()))
                .getBytes(StandardCharsets.UTF_8));
        if (isMultipart(request)) {
            for (Part part : request.getParts()) {
                digest.update(part.getName().getBytes(StandardCharsets.UTF_8));
                try (InputStream in = part.getInputStream()) {
                    digest.update(in.readAllBytes());
                }
            }
        } else {
            CachedBodyRequest cached = new CachedBodyRequest(request);
            digest.update(cached.body());
            effective = cached;
        }
        String hash = HexFormat.of().formatHex(digest.digest());

        if (!store.reserve(subject, key, op.operationId(), hash)) {
            Optional<Entry> existing = store.find(subject, key);
            if (existing.isEmpty()) {
                Problems.write(request, response, PlatformError.IDEMPOTENCY_IN_PROGRESS, null);
            } else if (!existing.get().requestHash().equals(hash)) {
                Problems.write(request, response, PlatformError.IDEMPOTENCY_KEY_REUSED, null);
            } else if (!existing.get().completed()) {
                Problems.write(request, response, PlatformError.IDEMPOTENCY_IN_PROGRESS, null);
            } else {
                replay(existing.get(), response);
            }
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(effective, wrapper);
            int status = wrapper.getStatus();
            if (status < 500) {
                store.complete(subject, key, new StoredResponse(
                        status,
                        wrapper.getContentType(),
                        wrapper.getHeader(HttpHeaders.LOCATION),
                        wrapper.getHeader(HttpHeaders.ETAG),
                        wrapper.getContentAsByteArray()));
                stored = true;
            }
            if (status >= 200 && status < 300) {
                CurrentUser user = CurrentUser.get();
                try {
                    audit.recordWrite(new WriteAudit(user.subject(), user.organizationId(), op.operationId(),
                            request.getMethod(), request.getRequestURI(), "0x" + hash, status));
                } catch (RuntimeException e) {
                    // Nghiệp vụ đã commit; không làm hỏng response, nhưng phải báo để vận hành xử lý.
                    org.slf4j.LoggerFactory.getLogger(IdempotencyFilter.class)
                            .error("Không ghi được nhật ký kiểm toán cho {} {}", request.getMethod(), request.getRequestURI(), e);
                }
            }
        } finally {
            if (!stored) {
                store.release(subject, key);
            }
            wrapper.copyBodyToResponse();
        }
    }

    private static void replay(Entry entry, HttpServletResponse response) throws IOException {
        response.setStatus(entry.status());
        if (entry.contentType() != null) {
            response.setContentType(entry.contentType());
        }
        if (entry.location() != null) {
            response.setHeader(HttpHeaders.LOCATION, entry.location());
        }
        if (entry.etag() != null) {
            response.setHeader(HttpHeaders.ETAG, entry.etag());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        if (entry.body() != null) {
            response.getOutputStream().write(entry.body());
        }
        response.flushBuffer();
    }

    private static boolean isMultipart(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
