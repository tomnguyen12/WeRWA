package vn.werwa.distribution.payment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import vn.werwa.issuance.api.SeriesCatalog;
import vn.werwa.onchain.api.ContractCalls;
import vn.werwa.onchain.api.IndexedEvents;
import vn.werwa.onchain.api.TokenBalances;
import vn.werwa.onchain.api.contracts.SeriesController;
import vn.werwa.platform.reconciliation.ReconciliationCheck;

/**
 * R4 (Q138; mô hình dữ liệu R4, bất biến I3): tổng cung token tại khối cuối indexer đã đọc bằng tổng số lượng phân bổ đã
 * mint lên tới khối đó. Phần hủy khi đáo hạn (B13b) được trừ ra. Không đọc được chuỗi thì một chênh lệch
 * {@code CHAIN_UNAVAILABLE} và dừng.
 */
public class SupplyCheck implements ReconciliationCheck {

    private final MintOrchestrator mints;
    private final SeriesCatalog series;
    private final ContractCalls calls;
    private final TokenBalances balances;
    private final IndexedEvents indexed;
    private final vn.werwa.distribution.payout.RedemptionService redemptions;

    public SupplyCheck(MintOrchestrator mints, SeriesCatalog series, ContractCalls calls, TokenBalances balances,
            IndexedEvents indexed, vn.werwa.distribution.payout.RedemptionService redemptions) {
        this.redemptions = redemptions;
        this.mints = mints;
        this.series = series;
        this.calls = calls;
        this.balances = balances;
        this.indexed = indexed;
    }

    @Override
    public String rule() {
        return "R4";
    }

    @Override
    public String scope() {
        return "CHAIN_VS_VIEW";
    }

    @Override
    public List<Finding> run(LocalDate runDate) {
        List<Finding> out = new ArrayList<>();
        List<UUID> ids = mints.seriesWithMints();
        if (ids.isEmpty()) {
            return out;
        }
        long block;
        try {
            block = indexed.indexedBlock();
        } catch (RuntimeException e) {
            out.add(new Finding("CHAIN_UNAVAILABLE", "chain", "khối indexer đã đọc", String.valueOf(e.getMessage())));
            return out;
        }
        for (UUID id : ids) {
            String controller = series.find(id).map(SeriesCatalog.SeriesInfo::controllerAddress).orElse(null);
            if (controller == null) {
                continue;
            }
            try {
                String token = calls.load(SeriesController.class, controller).token().send();
                long chain = balances.totalSupply(token, block);
                // Q168: đã mint trừ phần hủy khi đáo hạn
                long minted = mints.mintedUpTo(id, block) - redemptions.redeemedUpTo(id, block);
                if (chain != minted) {
                    out.add(new Finding("SUPPLY_MISMATCH", "series/" + id + "/block/" + block, Long.toString(minted),
                            Long.toString(chain)));
                }
            } catch (Exception e) {
                out.add(new Finding("CHAIN_UNAVAILABLE", "chain/block/" + block, "totalSupply tại khối " + block,
                        String.valueOf(e.getMessage())));
                break;
            }
        }
        return out;
    }
}
