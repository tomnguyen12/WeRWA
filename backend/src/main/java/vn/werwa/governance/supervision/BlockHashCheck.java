package vn.werwa.governance.supervision;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import vn.werwa.governance.api.BusinessCalendar;
import vn.werwa.platform.reconciliation.ReconciliationCheck;

/**
 * R7 (Q95, Q96; US-4.04-AC3): mã băm khối VSDC đọc từ V4 khớp mã băm trên O1 cùng số khối. Mỗi khối lệch thành một
 * chênh lệch ở lần đối soát đầu tiên thấy nó. VSDC quá 1 ngày làm việc chưa gửi mã băm cũng là chênh lệch.
 */
public class BlockHashCheck implements ReconciliationCheck {

    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    static final List<String> CALENDARS = List.of("VN");

    private final SupervisionService supervision;
    private final BusinessCalendar calendar;

    public BlockHashCheck(SupervisionService supervision, BusinessCalendar calendar) {
        this.supervision = supervision;
        this.calendar = calendar;
    }

    @Override
    public String rule() {
        return "R7";
    }

    @Override
    public String scope() {
        return "BLOCK_HASH_VSDC";
    }

    @Override
    public List<Finding> run(LocalDate runDate) {
        List<Finding> out = new ArrayList<>();
        supervision.recheckPending();
        for (SupervisionService.Attestation a : supervision.takeUnreportedMismatches()) {
            out.add(new Finding("BLOCK_HASH_MISMATCH", "block/" + a.blockNumber(), a.blockHash(), a.o1BlockHash()));
        }
        // Hạn gửi: từ đầu ngày làm việc liền trước ngày đối soát (giờ VN) phải có ít nhất một lần gửi
        LocalDate since = calendar.preceding(runDate.minusDays(1), CALENDARS);
        Optional<java.time.Instant> last = supervision.lastAttestedAt();
        if (last.isEmpty() || last.get().isBefore(since.atStartOfDay(VN).toInstant())) {
            out.add(new Finding("ATTESTATION_MISSING", "attestation/" + runDate, "mã băm khối từ " + since,
                    last.map(java.time.Instant::toString).orElse("chưa gửi lần nào")));
        }
        return out;
    }
}
