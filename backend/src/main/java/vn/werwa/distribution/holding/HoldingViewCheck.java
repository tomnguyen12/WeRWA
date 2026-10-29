package vn.werwa.distribution.holding;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import vn.werwa.onchain.api.TokenBalances;
import vn.werwa.platform.reconciliation.ReconciliationCheck;

/**
 * R5 (Q89; US-3.07-AC1): số dư từng ví trong bảng đọc bằng {@code balanceOf} trên chuỗi tại khối cuối bộ chiếu đã ghi.
 * Gồm cả địa chỉ chưa có ví trong onboarding (Q86). Không đọc được chuỗi thì ghi một chênh lệch
 * {@code CHAIN_UNAVAILABLE} và dừng.
 */
public class HoldingViewCheck implements ReconciliationCheck {

    private final HoldingsService holdings;
    private final TokenBalances balances;

    public HoldingViewCheck(HoldingsService holdings, TokenBalances balances) {
        this.holdings = holdings;
        this.balances = balances;
    }

    @Override
    public String rule() {
        return "R5";
    }

    @Override
    public String scope() {
        return "CHAIN_VS_VIEW";
    }

    @Override
    public List<Finding> run(LocalDate runDate) {
        long block = holdings.asOfBlock();
        List<Finding> out = new ArrayList<>();
        if (block == 0) {
            return out;
        }
        for (HoldingsService.Holding h : holdings.allForReconciliation()) {
            String subject = "holding/" + h.seriesId() + "/" + h.walletAddress();
            try {
                long chain = balances.balanceOf(h.tokenAddress(), h.walletAddress(), block);
                if (chain != h.balance()) {
                    out.add(new Finding("BALANCE_MISMATCH", subject, Long.toString(chain), Long.toString(h.balance())));
                }
            } catch (RuntimeException e) {
                // O1 không trả lời: một chênh lệch cho cả lần chạy, không thử từng ví (mỗi lần thử chờ hết thời gian)
                out.add(new Finding("CHAIN_UNAVAILABLE", "chain/block/" + block, "balanceOf tại khối " + block,
                        String.valueOf(e.getMessage())));
                break;
            }
        }
        return out;
    }
}
