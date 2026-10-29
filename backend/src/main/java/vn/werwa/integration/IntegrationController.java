package vn.werwa.integration;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import vn.werwa.integration.bank.InboundBankService;
import vn.werwa.platform.inbound.BankChannel;
import vn.werwa.platform.openapi.api.IntegrationApi;
import vn.werwa.platform.openapi.model.Ack;
import vn.werwa.platform.openapi.model.BidvCreditNotification;

/** Webhook BIDV: báo có (B8a), xác nhận chi coupon, gốc (B11c; Q104). */
@RestController
class IntegrationController implements IntegrationApi {

    private final InboundBankService inbound;
    private final HttpServletRequest request;

    IntegrationController(InboundBankService inbound, HttpServletRequest request) {
        this.inbound = inbound;
        this.request = request;
    }

    @Override
    public ResponseEntity<Ack> bidvPayoutConfirmation(String xBankSignature, String idempotencyKey,
            vn.werwa.platform.openapi.model.BidvPayoutConfirmation body) {
        byte[] raw = (byte[]) request.getAttribute(BankChannel.RAW_BODY_ATTRIBUTE);
        java.util.List<InboundBankService.PayoutLine> lines = body.getLines().stream().map(l -> {
            vn.werwa.platform.money.Money amount =
                    vn.werwa.platform.money.Money.parse(l.getAmount().getAmountMinor(), l.getAmount().getCurrency());
            return new InboundBankService.PayoutLine(l.getLineRef(), amount.amountMinor(), amount.currency(),
                    l.getStatus().getValue(), l.getBankRef());
        }).toList();
        InboundBankService.Received r = inbound.payoutConfirmation(body.getInstructionRef(), lines, idempotencyKey, raw,
                xBankSignature);
        Ack ack = new Ack();
        ack.setReceived(true);
        ack.setMessageId(r.messageId());
        return ResponseEntity.ok(ack);
    }

    @Override
    public ResponseEntity<Ack> bidvCreditNotification(String xBankSignature, String idempotencyKey,
            BidvCreditNotification body) {
        byte[] raw = (byte[]) request.getAttribute(BankChannel.RAW_BODY_ATTRIBUTE);
        vn.werwa.platform.money.Money amount =
                vn.werwa.platform.money.Money.parse(body.getAmount().getAmountMinor(), body.getAmount().getCurrency());
        InboundBankService.Received r = inbound.credit(new InboundBankService.Credit(body.getBankRef(),
                body.getVirtualAccount(), amount.amountMinor(), amount.currency(), body.getValueDate(), body.getPayerName(),
                body.getRemittanceInfo()), raw, xBankSignature);
        Ack ack = new Ack();
        ack.setReceived(true);
        ack.setMessageId(r.messageId());
        return ResponseEntity.ok(ack);
    }
}
