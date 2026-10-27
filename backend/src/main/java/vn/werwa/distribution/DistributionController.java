package vn.werwa.distribution;

import java.net.URI;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import vn.werwa.distribution.allocation.AllocationListService;
import vn.werwa.distribution.offering.OfferingService;
import vn.werwa.distribution.order.OrderService;
import vn.werwa.platform.openapi.api.DistributionApi;
import vn.werwa.platform.openapi.model.AllocationItem;
import vn.werwa.platform.openapi.model.AllocationList;
import vn.werwa.platform.openapi.model.AllocationListCreate;
import vn.werwa.platform.openapi.model.AllocationProof;
import vn.werwa.platform.openapi.model.ListOrders200Response;
import vn.werwa.platform.openapi.model.Money;
import vn.werwa.platform.openapi.model.Offering;
import vn.werwa.platform.openapi.model.OfferingCreate;
import vn.werwa.platform.openapi.model.Order;
import vn.werwa.platform.openapi.model.OrderAmend;
import vn.werwa.platform.openapi.model.OrderPlace;
import vn.werwa.platform.openapi.model.SigningProposalRef;
import vn.werwa.platform.web.ETags;
import vn.werwa.platform.web.Paging;

/** API Phân phối của B7a. Chuyển nhượng, số dư, danh sách chốt, tiến độ phát hành làm ở các nhóm sau; trả 501 (Q20). */
@RestController
class DistributionController implements DistributionApi {

    private final OfferingService offerings;
    private final OrderService orders;
    private final AllocationListService lists;

    DistributionController(OfferingService offerings, OrderService orders, AllocationListService lists) {
        this.offerings = offerings;
        this.orders = orders;
        this.lists = lists;
    }

    @Override
    public ResponseEntity<Offering> createOffering(String idempotencyKey, OfferingCreate body) {
        OfferingService.Offering o = offerings.create(body.getSeriesId(), body.getMethod().getValue(),
                body.getRateGuidanceLowPpm(), body.getRateGuidanceHighPpm(), body.getTargetQuantity(),
                body.getBookOpenAt().toInstant(), body.getBookCloseAt().toInstant(), body.getAnnouncementDocumentId());
        return ResponseEntity.created(URI.create("/v1/offerings/" + o.id())).body(view(o));
    }

    @Override
    public ResponseEntity<Offering> getOffering(UUID id) {
        return ResponseEntity.ok(view(offerings.get(id)));
    }

    @Override
    public ResponseEntity<Offering> openOffering(UUID id, String idempotencyKey) {
        return ResponseEntity.ok(view(offerings.open(id)));
    }

    @Override
    public ResponseEntity<Offering> closeOffering(UUID id, String idempotencyKey) {
        return ResponseEntity.ok(view(offerings.close(id)));
    }

    @Override
    public ResponseEntity<Order> placeOrder(UUID id, String idempotencyKey, OrderPlace body) {
        OrderService.Order o = orders.place(id, body.getInvestorOrgId(), body.getWalletId(), body.getRatePpm(),
                body.getQuantity());
        return ResponseEntity.created(URI.create("/v1/orders/" + o.id())).eTag(ETags.of(o.version())).body(view(o));
    }

    @Override
    public ResponseEntity<ListOrders200Response> listOrders(UUID id, String cursor, Integer limit) {
        Paging.Page<OrderService.Order> page = orders.list(id, cursor, limit);
        ListOrders200Response out = new ListOrders200Response();
        out.setItems(page.items().stream().map(DistributionController::view).toList());
        out.setNextCursor(page.nextCursor());
        return ResponseEntity.ok(out);
    }

    @Override
    public ResponseEntity<Order> getOrder(UUID id) {
        OrderService.Order o = orders.view(id);
        return ResponseEntity.ok().eTag(ETags.of(o.version())).body(view(o));
    }

    @Override
    public ResponseEntity<Order> amendOrder(UUID id, String ifMatch, String idempotencyKey, OrderAmend body) {
        OrderService.Order o = orders.amend(id, ifMatch, body.getRatePpm(), body.getQuantity());
        return ResponseEntity.ok().eTag(ETags.of(o.version())).body(view(o));
    }

    @Override
    public ResponseEntity<Order> cancelOrder(UUID id, String ifMatch, String idempotencyKey) {
        OrderService.Order o = orders.cancel(id, ifMatch);
        return ResponseEntity.ok().eTag(ETags.of(o.version())).body(view(o));
    }

    @Override
    public ResponseEntity<AllocationList> proposeAllocationList(UUID id, String idempotencyKey, AllocationListCreate body) {
        List<AllocationListService.ItemInput> items = body.getItems().stream()
                .map(i -> new AllocationListService.ItemInput(i.getOrderId(), i.getQuantity(), i.getAdjustmentReason()))
                .toList();
        AllocationListService.AllocationList l = lists.propose(id, body.getCouponRatePpm(), items);
        return ResponseEntity.created(URI.create("/v1/allocation-lists/" + l.id())).body(view(l));
    }

    @Override
    public ResponseEntity<AllocationList> getAllocationList(UUID id) {
        return ResponseEntity.ok(view(lists.get(id)));
    }

    @Override
    public ResponseEntity<SigningProposalRef> submitAllocationList(UUID id, String idempotencyKey) {
        SigningProposalRef ref = new SigningProposalRef();
        ref.setApprovalRequestId(lists.submit(id));
        ref.setStatus(SigningProposalRef.StatusEnum.PENDING_APPROVAL);
        return ResponseEntity.accepted().body(ref);
    }

    @Override
    public ResponseEntity<AllocationProof> getAllocationProof(UUID id, UUID allocationId) {
        AllocationListService.Proof p = lists.proof(id, allocationId);
        AllocationProof v = new AllocationProof();
        v.setAllocationId(p.allocationId());
        v.setSeriesController(p.seriesController());
        v.setWallet(p.wallet());
        v.setQuantity(p.quantity());
        v.setLeaf(p.leaf());
        v.setProof(p.proof());
        v.setMerkleRoot(p.merkleRoot());
        return ResponseEntity.ok(v);
    }

    static Offering view(OfferingService.Offering o) {
        Offering v = new Offering();
        v.setId(o.id());
        v.setSeriesId(o.seriesId());
        v.setMethod(Offering.MethodEnum.fromValue(o.method()));
        v.setRateGuidanceLowPpm(o.rateGuidanceLowPpm());
        v.setRateGuidanceHighPpm(o.rateGuidanceHighPpm());
        v.setTargetQuantity(o.targetQuantity());
        v.setBookOpenAt(o.bookOpenAt().atOffset(ZoneOffset.UTC));
        v.setBookCloseAt(o.bookCloseAt().atOffset(ZoneOffset.UTC));
        v.setAnnouncementDocumentId(o.announcementDocumentId());
        v.setStatus(Offering.StatusEnum.fromValue(o.status()));
        return v;
    }

    static Order view(OrderService.Order o) {
        Order v = new Order();
        v.setId(o.id());
        v.setOfferingId(o.offeringId());
        v.setInvestorOrgId(o.investorOrgId());
        v.setWalletId(o.walletId());
        v.setRatePpm(o.ratePpm());
        v.setQuantity(o.quantity());
        v.setEnteredByOrgId(o.enteredByOrgId());
        v.setStatus(Order.StatusEnum.fromValue(o.status()));
        v.setVersion(o.version());
        return v;
    }

    static AllocationList view(AllocationListService.AllocationList l) {
        AllocationList v = new AllocationList();
        v.setId(l.id());
        v.setOfferingId(l.offeringId());
        v.setListVersion(l.listVersion());
        v.setCouponRatePpm(l.couponRatePpm());
        v.setTotalQuantity(l.totalQuantity());
        v.setMerkleRoot(l.merkleRoot());
        v.setListHash(l.listHash());
        v.setApprovalRequestId(l.approvalRequestId());
        v.setStatus(AllocationList.StatusEnum.fromValue(l.status()));
        v.setItems(l.items().stream().map(i -> {
            AllocationItem it = new AllocationItem();
            it.setAllocationId(i.allocationId());
            it.setOrderId(i.orderId());
            it.setInvestorOrgId(i.investorOrgId());
            it.setWalletId(i.walletId());
            it.setWallet(i.wallet());
            it.setQuantity(i.quantity());
            Money m = new Money();
            m.setAmountMinor(Long.toString(i.amountDueMinor()));
            m.setCurrency(i.currency());
            it.setAmountDue(m);
            it.setAdjustmentReason(i.adjustmentReason());
            it.setLeaf(i.leaf());
            return it;
        }).toList());
        return v;
    }
}
