package vn.werwa.integration;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import vn.werwa.integration.bank.InboundBankService;
import vn.werwa.platform.inbound.BankChannel;
import vn.werwa.platform.openapi.api.IntegrationApi;
import vn.werwa.platform.openapi.model.Ack;
import vn.werwa.platform.openapi.model.BidvCreditNotification;

/** Webhook BIDV (B8a). Xác nhận chi coupon, gốc làm ở B10; tới lúc đó trả 501 (Q20). */
@RestController
class IntegrationController implements IntegrationApi {

    private final InboundBankService inbound;
    private final HttpServletRequest request;

    IntegrationController(InboundBankService inbound, HttpServletRequest request) {
        this.inbound = inbound;
        this.request = request;
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
