package vn.werwa.integration.kyc;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import vn.werwa.integration.api.Screening.Subject;
import vn.werwa.platform.id.Ids;

/**
 * Nhà cung cấp giả lập cho XD (US-5.04-AC2). Kịch bản demo: tên pháp lý chứa {@code SANCTIONED} thì trùng danh sách cấm vận;
 * chứa {@code PEP} thì trùng danh sách PEP. Trả về đúng dạng một nhà cung cấp thật: mã tham chiếu, danh sách đã kiểm,
 * các mục trùng.
 */
public class SimulatedScreeningProvider implements ScreeningProvider {

    private final Clock clock;

    public SimulatedScreeningProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "SIMULATOR";
    }

    @Override
    public Map<String, Object> screen(Subject subject) {
        String name = subject.legalName().toUpperCase(Locale.ROOT);
        boolean sanctions = name.contains("SANCTIONED");
        boolean pep = name.contains("PEP");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("reference", "SIM-" + Ids.newId());
        raw.put("screenedAt", clock.instant().toString());
        raw.put("subject", Map.of("legalName", subject.legalName(), "registrationNo", subject.registrationNo(),
                "country", subject.country()));
        raw.put("lists", List.of("UN", "OFAC-SDN", "EU", "PEP"));
        raw.put("sanctionsHit", sanctions);
        raw.put("pepHit", pep);
        raw.put("matches", sanctions
                ? List.of(Map.of("list", "OFAC-SDN", "name", subject.legalName(), "score", 97))
                : pep ? List.of(Map.of("list", "PEP", "name", subject.legalName(), "score", 88)) : List.of());
        return raw;
    }
}
