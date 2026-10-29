package vn.werwa.bidvsim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Chiều đi giả lập (US-5.02-AC1, AC4; Q100): số dư tài khoản IPA, nhận lệnh chi, gửi xác nhận chi có chữ ký về
 * {@code POST /v1/inbound/bidv/payout-confirmations}. Người demo đặt số dư để diễn cảnh đủ hoặc thiếu tiền, và chọn dòng
 * chi lỗi. Định dạng thật của Open API BIDV chưa chốt; giả lập theo giao diện {@code PayingAgent} của nền tảng.
 */
@RestController
@RequestMapping("/sim")
class PayoutController {

    private final RestClient platform;
    private final MessageSigner signer;
    private final JsonMapper json;
    private final boolean autoConfirm;
    private final Map<String, Long> balances = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> payouts = new ConcurrentHashMap<>();

    PayoutController(RestClient platform, MessageSigner signer, JsonMapper json, SimulatorProperties props) {
        this.platform = platform;
        this.signer = signer;
        this.json = json;
        this.autoConfirm = props.autoConfirmPayouts();
    }

    record Balance(@NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @NotBlank @Pattern(regexp = "^[0-9]+$") String amountMinor) {}

    record Confirm(List<String> failedLineRefs) {}

    @GetMapping("/ipa-balance")
    Map<String, String> balance(@RequestParam String currency) {
        return Map.of("currency", currency, "amountMinor", Long.toString(balances.getOrDefault(currency, 0L)));
    }

    @PostMapping("/ipa-balance")
    Map<String, String> setBalance(@Valid @RequestBody Balance b) {
        balances.put(b.currency(), Long.parseLong(b.amountMinor()));
        return balance(b.currency());
    }

    /** Nhận lệnh chi; cùng mã lệnh gửi lại thì giữ lệnh cũ. Tự trừ số dư khi xác nhận chi. */
    @PostMapping("/payouts")
    ResponseEntity<Map<String, Object>> receive(@RequestBody Map<String, Object> instruction) {
        String ref = String.valueOf(instruction.get("instructionRef"));
        Map<String, Object> kept = payouts.putIfAbsent(ref, instruction);
        if (kept == null && autoConfirm) {
            // Gửi sau khi nền tảng đã ghi xong lệnh chi (nó gọi giả lập trong giao dịch của mình)
            java.util.concurrent.CompletableFuture.runAsync(() -> confirm(ref, new Confirm(List.of())),
                    java.util.concurrent.CompletableFuture.delayedExecutor(3, java.util.concurrent.TimeUnit.SECONDS));
        }
        return ResponseEntity.ok(payouts.get(ref));
    }

    @GetMapping("/payouts")
    List<Map<String, Object>> list() {
        return new ArrayList<>(payouts.values());
    }

    /** Gửi xác nhận chi: mọi dòng PAID, trừ các dòng chọn lỗi. */
    @PostMapping("/payouts/{ref}/confirm")
    @SuppressWarnings("unchecked")
    ResponseEntity<String> confirm(@PathVariable String ref, @RequestBody(required = false) Confirm req) {
        Map<String, Object> instruction = payouts.get(ref);
        if (instruction == null) {
            return ResponseEntity.notFound().build();
        }
        Set<String> failed = Set.copyOf(req == null || req.failedLineRefs() == null ? List.of() : req.failedLineRefs());
        String currency = String.valueOf(instruction.get("currency"));
        List<Map<String, Object>> lines = new ArrayList<>();
        long paid = 0;
        for (Map<String, Object> l : (List<Map<String, Object>>) instruction.get("lines")) {
            String lineRef = String.valueOf(l.get("lineRef"));
            boolean ok = !failed.contains(lineRef);
            String amount = String.valueOf(l.get("amountMinor"));
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("lineRef", lineRef);
            line.put("amount", Map.of("amountMinor", amount, "currency", currency));
            line.put("status", ok ? "PAID" : "FAILED");
            line.put("bankRef", "BIDVP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
            lines.add(line);
            if (ok) {
                paid += Long.parseLong(amount);
            }
        }
        balances.merge(currency, -paid, Long::sum);
        String body = json.writeValueAsString(Map.of("instructionRef", ref, "lines", lines));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ResponseEntity<String> response = platform.post()
                .uri("/v1/inbound/bidv/payout-confirmations")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Bank-Signature", signer.sign(bytes))
                .header("Idempotency-Key", "payout-" + ref + "-" + UUID.randomUUID())
                .body(bytes)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (rq, rs) -> { })
                .toEntity(String.class);
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }
}
