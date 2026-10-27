package vn.werwa.bidvsim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Báo có giả lập (US-5.02-AC1, AC3, AC4). Người demo (hoặc màn hình giả lập của frontend) tạo báo có theo tài khoản ảo;
 * giả lập dựng thông điệp {@code BidvCreditNotification} đúng hợp đồng, ký và gọi
 * {@code POST /v1/inbound/bidv/credit-notifications}. Gửi lại cùng {@code bankRef} để demo chặn trùng.
 */
@RestController
@RequestMapping("/sim/credits")
class CreditController {

    private final RestClient platform;
    private final MessageSigner signer;
    private final JsonMapper json;
    private final Clock clock;
    private final Map<String, Sent> sent = new ConcurrentHashMap<>();

    CreditController(RestClient platform, MessageSigner signer, JsonMapper json, Clock clock) {
        this.platform = platform;
        this.signer = signer;
        this.json = json;
        this.clock = clock;
    }

    record CreditRequest(
            @NotBlank String virtualAccount,
            @NotBlank @Pattern(regexp = "^[0-9]+$") String amountMinor,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @NotBlank String payerName,
            LocalDate valueDate,
            String bankRef,
            String remittanceInfo) {}

    record Sent(String bankRef, String body, int platformStatus, String platformResponse) {}

    @PostMapping
    ResponseEntity<Sent> credit(@Valid @RequestBody CreditRequest req) {
        String bankRef = req.bankRef() == null || req.bankRef().isBlank()
                ? "BIDV" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase()
                : req.bankRef();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("bankRef", bankRef);
        msg.put("virtualAccount", req.virtualAccount());
        msg.put("amount", Map.of("amountMinor", req.amountMinor(), "currency", req.currency()));
        msg.put("valueDate", (req.valueDate() == null ? LocalDate.now(clock.withZone(ZoneId.of("Asia/Ho_Chi_Minh")))
                : req.valueDate()).toString());
        msg.put("payerName", req.payerName());
        if (req.remittanceInfo() != null) {
            msg.put("remittanceInfo", req.remittanceInfo());
        }
        return ResponseEntity.ok(send(bankRef, json.writeValueAsString(msg)));
    }

    /** US-5.02-AC3: gửi lại nguyên thông điệp cũ; nền tảng phải không xử lý lần hai. */
    @PostMapping("/{bankRef}/resend")
    ResponseEntity<Sent> resend(@PathVariable String bankRef) {
        Sent previous = sent.get(bankRef);
        if (previous == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(send(bankRef, previous.body()));
    }

    @GetMapping
    List<Sent> list() {
        return new ArrayList<>(sent.values());
    }

    private Sent send(String bankRef, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ResponseEntity<String> response = platform.post()
                .uri("/v1/inbound/bidv/credit-notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Bank-Signature", signer.sign(bytes))
                .header("Idempotency-Key", bankRef)
                .body(bytes)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (rq, rs) -> { })
                .toEntity(String.class);
        Sent s = new Sent(bankRef, body, response.getStatusCode().value(), response.getBody());
        sent.put(bankRef, s);
        return s;
    }
}
