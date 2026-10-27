package vn.werwa.platform;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.OrgScope;
import vn.werwa.platform.web.ETags;
import vn.werwa.platform.web.Paging;

/**
 * Controller chỉ có trong kiểm thử: gắn vào vài đường dẫn thật của hợp đồng để kiểm lớp nền B1 (quyền, Idempotency-Key,
 * If-Match, phân trang, problem+json). Controller thật của từng API viết ở nhóm PR nghiệp vụ.
 */
@RestController
class ContractProbeController {

    static final AtomicInteger CREATED = new AtomicInteger();

    @PostMapping("/v1/series")
    ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        if (Boolean.TRUE.equals(body.get("fail"))) {
            throw new IllegalStateException("lỗi giả lập");
        }
        CREATED.incrementAndGet();
        UUID id = Ids.newId();
        return ResponseEntity.created(URI.create("/v1/series/" + id))
                .body(Map.of("id", id.toString(), "legalName", String.valueOf(body.get("legalName"))));
    }

    @GetMapping("/v1/series/{id}")
    Map<String, Object> get(@PathVariable UUID id) {
        OrgScope.requireOwnOrganization(id);
        return Map.of("id", id.toString());
    }

    @PatchMapping("/v1/orders/{id}")
    ResponseEntity<Map<String, Object>> amend(
            @PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        ETags.requireMatch(ifMatch, 3);
        return ResponseEntity.ok().eTag(ETags.of(4)).body(Map.of("id", id.toString()));
    }

    @GetMapping("/v1/holdings")
    Map<String, Object> holdings(@RequestParam(required = false) Integer limit) {
        return Map.of("limit", Paging.limit(limit));
    }
}
