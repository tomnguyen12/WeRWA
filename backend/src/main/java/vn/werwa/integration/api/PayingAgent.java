package vn.werwa.integration.api;

import java.util.List;
import java.util.UUID;

/**
 * Chiều đi sang đại lý thanh toán BIDV (US-5.02-AC1; Q100): đọc số dư tài khoản IPA, gửi lệnh chi coupon hoặc gốc.
 * Bản đầu là giả lập; Open API thật của BIDV thay ở adapter, không đổi mã nghiệp vụ.
 */
public interface PayingAgent {

    /** Số dư khả dụng của tài khoản IPA theo loại tiền, đơn vị nhỏ nhất. */
    long ipaBalance(String currency);

    /** Gửi lệnh chi; cùng {@code instructionRef} gửi lại không tạo lệnh mới (Q102). */
    void submitPayout(Instruction instruction);

    /** @param lineRef mã dòng chi trả, BIDV trả lại trong xác nhận chi */
    record Line(String lineRef, UUID investorOrgId, UUID walletId, long amountMinor) {}

    record Instruction(String instructionRef, String currency, List<Line> lines) {}
}
