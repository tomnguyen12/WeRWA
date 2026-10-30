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
import vn.werwa.distribution.transfer.TransferService;
import vn.werwa.platform.openapi.api.DistributionApi;
import vn.werwa.platform.openapi.model.AllocationItem;
import vn.werwa.platform.openapi.model.AllocationList;
import vn.werwa.platform.openapi.model.AllocationListCreate;
import vn.werwa.platform.openapi.model.AllocationProof;
import vn.werwa.platform.openapi.model.IssuanceProgress;
import vn.werwa.platform.openapi.model.ListOrders200Response;
import vn.werwa.platform.openapi.model.Money;
import vn.werwa.platform.openapi.model.Offering;
import vn.werwa.platform.openapi.model.OfferingCreate;
import vn.werwa.platform.openapi.model.Order;
import vn.werwa.platform.openapi.model.OrderAmend;
import vn.werwa.platform.openapi.model.OrderPlace;
import vn.werwa.platform.openapi.model.Holding;
import vn.werwa.platform.openapi.model.HoldingMovement;
import vn.werwa.platform.openapi.model.ListHoldingMovements200Response;
import vn.werwa.platform.openapi.model.ListHoldings200Response;
import vn.werwa.platform.openapi.model.ListTransfers200Response;
import vn.werwa.platform.openapi.model.SigningProposalRef;
import vn.werwa.platform.openapi.model.Transfer;
import vn.werwa.platform.openapi.model.TransferCreate;
import vn.werwa.platform.web.ETags;
import vn.werwa.platform.web.Paging;

/** API Phân phối (B7a, B8a, B11a, B9a, B9c, B7b, B8b, B9d, B11b). Thao tác chưa làm trả 501 (Q20). */
@RestController
class DistributionController implements DistributionApi {

    private final OfferingService offerings;
    private final OrderService orders;
    private final AllocationListService lists;
    private final vn.werwa.distribution.payment.PaymentService payments;
    private final vn.werwa.distribution.payout.PayoutService payouts;
    private final TransferService transfers;
    private final vn.werwa.distribution.holding.HoldingsService holdings;
    private final vn.werwa.distribution.allocation.AllocationSigning allocationSigning;
    private final vn.werwa.distribution.payment.PaymentBatchSigning batchSigning;
    private final vn.werwa.distribution.payment.MintOrchestrator mints;
    private final vn.werwa.distribution.register.RegisterAttestationService register;
    private final vn.werwa.distribution.payout.SnapshotService snapshots;

    DistributionController(OfferingService offerings, OrderService orders, AllocationListService lists,
            vn.werwa.distribution.payment.PaymentService payments, vn.werwa.distribution.payout.PayoutService payouts,
            TransferService transfers, vn.werwa.distribution.holding.HoldingsService holdings,
            vn.werwa.distribution.allocation.AllocationSigning allocationSigning,
            vn.werwa.distribution.payment.PaymentBatchSigning batchSigning,
            vn.werwa.distribution.payment.MintOrchestrator mints,
            vn.werwa.distribution.register.RegisterAttestationService register,
            vn.werwa.distribution.payout.SnapshotService snapshots) {
        this.register = register;
        this.snapshots = snapshots;
        this.allocationSigning = allocationSigning;
        this.batchSigning = batchSigning;
        this.mints = mints;
        this.holdings = holdings;
        this.transfers = transfers;
        this.payouts = payouts;
        this.payments = payments;
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
        AllocationList v = view(lists.get(id));
        v.setSigningProposalId(lists.signingProposalId(id));
        return ResponseEntity.ok(v);
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

    @Override
    public ResponseEntity<IssuanceProgress> getIssuanceProgress(UUID id) {
        vn.werwa.distribution.payment.PaymentService.Progress p = payments.progress(id);
        IssuanceProgress v = new IssuanceProgress();
        v.setSeriesId(p.seriesId());
        v.setStep(IssuanceProgress.StepEnum.fromValue(p.step().name()));
        v.setAllocated(p.allocated());
        v.setPaid(p.paid());
        v.setMinted(p.minted());
        v.setOverdue(p.overdue());
        v.setAlerts(p.alerts());
        return ResponseEntity.ok(v);
    }

    /** US-2.09 (B11a): danh sách chốt đã tính dòng chi trả. */
    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.Snapshot> getSnapshot(UUID id, Integer period) {
        vn.werwa.distribution.payout.PayoutService.Snapshot s = payouts.snapshot(id, period);
        var v = new vn.werwa.platform.openapi.model.Snapshot();
        v.setSeriesId(s.seriesId());
        v.setPeriodNo(s.periodNo());
        v.setRecordBlock((int) s.recordBlock());
        v.setRegisterHash(s.registerHash());
        v.setRegistrarAttestedAt(s.registrarAttestedAt() == null ? null : s.registrarAttestedAt().atOffset(ZoneOffset.UTC));
        v.setStatus(s.status() == null ? null : vn.werwa.platform.openapi.model.Snapshot.StatusEnum.fromValue(s.status()));
        v.setSigningProposalId(s.signingProposalId());
        v.setTakenTxHash(s.takenTxHash());
        v.setAttestedTxHash(s.attestedTxHash());
        v.setHolderCount(s.holderCount());
        v.setTotalQuantity(s.totalQuantity());
        v.setRatePpm(s.ratePpm());
        v.setAmountDue(s.amountDueMinor() == null ? null : money(s.amountDueMinor(), s.currency()));
        v.setRoundingResidual(s.roundingResidualMinor() == null ? null : money(s.roundingResidualMinor(), s.currency()));
        v.setLines(s.lines().stream().map(l -> {
            var line = new vn.werwa.platform.openapi.model.SnapshotLinesInner();
            line.setWalletId(l.walletId());
            line.setInvestorOrgId(l.investorOrgId());
            line.setQuantity(l.quantity());
            line.setGross(money(l.grossMinor(), s.currency()));
            line.setTax(money(l.taxMinor(), s.currency()));
            line.setNet(money(l.netMinor(), s.currency()));
            line.setWithheldReason(l.withheldReason() == null ? null
                    : vn.werwa.platform.openapi.model.SnapshotLinesInner.WithheldReasonEnum.fromValue(l.withheldReason()));
            return line;
        }).toList());
        return ResponseEntity.ok(v);
    }

    static Money money(long minor, String currency) {
        Money m = new Money();
        m.setAmountMinor(Long.toString(minor));
        m.setCurrency(currency);
        return m;
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

    @Override
    public ResponseEntity<ListHoldings200Response> listHoldings(UUID seriesId, UUID walletId, String cursor,
            Integer limit) {
        vn.werwa.distribution.holding.HoldingsService.HoldingsPage page = holdings.list(seriesId, walletId, cursor, limit);
        ListHoldings200Response out = new ListHoldings200Response();
        out.setItems(page.items().stream().map(h -> {
            Holding v = new Holding();
            v.setSeriesId(h.seriesId());
            v.setWalletId(h.walletId());
            v.setBalance(h.balance());
            v.setFrozen(h.frozen());
            v.setLocked(h.locked());
            v.setAsOfBlock(Math.toIntExact(page.asOfBlock()));
            return v;
        }).toList());
        out.setNextCursor(page.nextCursor());
        return ResponseEntity.ok(out);
    }

    @Override
    public ResponseEntity<ListHoldingMovements200Response> listHoldingMovements(UUID walletId, UUID seriesId,
            java.time.OffsetDateTime from, java.time.OffsetDateTime to, String cursor, Integer limit) {
        Paging.Page<vn.werwa.distribution.holding.HoldingsService.Movement> page = holdings.movements(walletId, seriesId,
                from == null ? null : from.toInstant(), to == null ? null : to.toInstant(), cursor, limit);
        ListHoldingMovements200Response out = new ListHoldingMovements200Response();
        out.setItems(page.items().stream().map(m -> {
            HoldingMovement v = new HoldingMovement();
            v.setId(m.id());
            v.setSeriesId(m.seriesId());
            v.setWalletId(m.walletId());
            v.setKind(HoldingMovement.KindEnum.fromValue(m.kind()));
            v.setQuantity(m.quantity());
            v.setCounterpartyWallet(m.counterparty());
            v.setBlock(m.block());
            v.setLogIndex(m.logIndex());
            v.setTxHash(m.txHash());
            v.setBlockTime(m.blockTime().atOffset(ZoneOffset.UTC));
            return v;
        }).toList());
        out.setNextCursor(page.nextCursor());
        return ResponseEntity.ok(out);
    }

    /** Q127: tạo lại đề nghị ký setAllocation. */
    @Override
    public ResponseEntity<SigningProposalRef> proposeAllocationSigning(UUID id, String idempotencyKey) {
        vn.werwa.governance.api.SigningProposals.Ref r = allocationSigning.propose(id);
        SigningProposalRef ref = new SigningProposalRef();
        ref.setSigningProposalId(r.id());
        ref.setApprovalRequestId(lists.get(id).approvalRequestId());
        ref.setStatus(SigningProposalRef.StatusEnum.fromValue(r.status()));
        return ResponseEntity.accepted().body(ref);
    }

    // ---------- B8b: lô xác nhận BIDV, mint (Q131, Q135) ----------

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.ListPaymentBatches200Response> listPaymentBatches(
            UUID offeringId, String status, String cursor, Integer limit) {
        Paging.Page<vn.werwa.distribution.payment.PaymentBatchSigning.Batch> page =
                batchSigning.list(offeringId, status, cursor, limit);
        vn.werwa.platform.openapi.model.ListPaymentBatches200Response out =
                new vn.werwa.platform.openapi.model.ListPaymentBatches200Response();
        out.setItems(page.items().stream().map(DistributionController::view).toList());
        out.setNextCursor(page.nextCursor());
        return ResponseEntity.ok(out);
    }

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.PaymentBatch> getPaymentBatch(UUID id) {
        return ResponseEntity.ok(view(batchSigning.get(id)));
    }

    @Override
    public ResponseEntity<SigningProposalRef> proposePaymentBatchSigning(UUID id, String idempotencyKey) {
        vn.werwa.governance.api.SigningProposals.Ref r = batchSigning.propose(id);
        SigningProposalRef ref = new SigningProposalRef();
        ref.setSigningProposalId(r.id());
        ref.setStatus(SigningProposalRef.StatusEnum.fromValue(r.status()));
        return ResponseEntity.accepted().body(ref);
    }

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.ChainTxRef> retryMint(UUID id, String idempotencyKey) {
        vn.werwa.distribution.payment.MintOrchestrator.Attempt a = mints.submit(id, true);
        if (a.chainTxId() == null) {
            // Đã mint trên chuỗi, chờ indexer
            throw new vn.werwa.platform.web.ApiException(DistributionError.ALLOCATION_NOT_MINTABLE, a.skippedReason());
        }
        vn.werwa.platform.openapi.model.ChainTxRef ref = new vn.werwa.platform.openapi.model.ChainTxRef();
        ref.setChainTxId(a.chainTxId());
        ref.setStatus(vn.werwa.platform.openapi.model.ChainTxRef.StatusEnum.BUILT);
        return ResponseEntity.accepted().body(ref);
    }

    static vn.werwa.platform.openapi.model.PaymentBatch view(vn.werwa.distribution.payment.PaymentBatchSigning.Batch b) {
        vn.werwa.platform.openapi.model.PaymentBatch v = new vn.werwa.platform.openapi.model.PaymentBatch();
        v.setId(b.id());
        v.setOfferingId(b.offeringId());
        v.setBatchNo(b.batchNo());
        v.setStatus(vn.werwa.platform.openapi.model.PaymentBatch.StatusEnum.fromValue(b.status()));
        v.setItemCount(b.itemCount());
        v.setTotalAmount(money(b.totalAmountMinor(), b.currency()));
        v.setMerkleRoot(b.merkleRoot());
        v.setBatchHash(b.batchHash());
        v.setSigningProposalId(b.signingProposalId());
        v.setSignedTxHash(b.signedTxHash());
        v.setCreatedAt(utc(b.createdAt()));
        v.setItems(b.items().stream().map(i -> {
            vn.werwa.platform.openapi.model.PaymentBatchItemsInner it =
                    new vn.werwa.platform.openapi.model.PaymentBatchItemsInner();
            it.setAllocationId(i.allocationId());
            it.setAmount(money(i.amountMinor(), i.currency()));
            it.setBankRefHash(i.bankRefHash());
            it.setLeaf(i.leaf());
            it.setAllocationStatus(vn.werwa.platform.openapi.model.PaymentBatchItemsInner.AllocationStatusEnum
                    .fromValue(i.allocationStatus()));
            it.setMintTxHash(i.mintTxHash());
            it.setMintFailureReason(i.mintFailureReason());
            return it;
        }).toList());
        return v;
    }

    // ---------- B9d: VSDC xác nhận sổ, sửa mục (Q142, Q144, Q145) ----------

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.RegisterAttestation> getRegisterAttestation(UUID id) {
        return ResponseEntity.ok(view(register.latest(id)));
    }

    @Override
    public ResponseEntity<SigningProposalRef> proposeRegisterAttestation(UUID id, String idempotencyKey) {
        vn.werwa.governance.api.SigningProposals.Ref r = register.propose(id, true).orElseThrow();
        SigningProposalRef ref = new SigningProposalRef();
        ref.setSigningProposalId(r.id());
        ref.setStatus(SigningProposalRef.StatusEnum.fromValue(r.status()));
        return ResponseEntity.accepted().body(ref);
    }

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.RegisterAttestation> rejectRegisterAttestation(UUID id,
            String idempotencyKey, vn.werwa.platform.openapi.model.RegisterRejection body) {
        List<java.util.Map<String, String>> items = body.getItems().stream()
                .map(i -> java.util.Map.of("allocationId", i.getAllocationId().toString(), "reason", i.getReason()))
                .toList();
        return ResponseEntity.ok(view(register.reject(id, body.getReason(), items)));
    }

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.ApprovalRef> requestAllocationCorrection(UUID id,
            String idempotencyKey, vn.werwa.platform.openapi.model.AllocationCorrection body) {
        vn.werwa.platform.openapi.model.ApprovalRef ref = new vn.werwa.platform.openapi.model.ApprovalRef();
        ref.setApprovalRequestId(register.requestCorrection(id, body.getAction().getValue(), body.getReason()));
        return ResponseEntity.accepted().body(ref);
    }

    static vn.werwa.platform.openapi.model.RegisterAttestation view(
            vn.werwa.distribution.register.RegisterAttestationService.Attestation a) {
        vn.werwa.platform.openapi.model.RegisterAttestation v = new vn.werwa.platform.openapi.model.RegisterAttestation();
        v.setId(a.id());
        v.setSeriesId(a.seriesId());
        v.setAtBlock(a.atBlock());
        v.setRegisterHash(a.registerHash());
        v.setStatus(vn.werwa.platform.openapi.model.RegisterAttestation.StatusEnum.fromValue(a.status()));
        v.setSigningProposalId(a.signingProposalId());
        v.setHolderCount(a.holderCount());
        v.setTotalQuantity(a.totalQuantity());
        v.setHolders(a.holders().stream().map(h -> {
            vn.werwa.platform.openapi.model.RegisterAttestationHoldersInner x =
                    new vn.werwa.platform.openapi.model.RegisterAttestationHoldersInner();
            x.setWallet(h.wallet());
            x.setInvestorOrgId(h.investorOrgId());
            x.setQuantity(h.quantity());
            x.setAllocationIds(h.allocationIds());
            return x;
        }).toList());
        if (a.rejection() != null) {
            vn.werwa.platform.openapi.model.RegisterRejection r = new vn.werwa.platform.openapi.model.RegisterRejection();
            r.setReason(a.rejection().reason());
            r.setItems(a.rejection().items().stream().map(i -> {
                vn.werwa.platform.openapi.model.RegisterRejectionItemsInner it =
                        new vn.werwa.platform.openapi.model.RegisterRejectionItemsInner();
                it.setAllocationId(UUID.fromString(i.get("allocationId")));
                it.setReason(i.get("reason"));
                return it;
            }).toList());
            v.setRejection(r);
        }
        v.setAttestedTxHash(a.attestedTxHash());
        v.setAttestedAt(utc(a.attestedAt()));
        v.setCreatedAt(utc(a.createdAt()));
        return v;
    }

    // ---------- B11b: chụp danh sách chốt, VSDC xác nhận (Q160) ----------

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.ChainTxRef> takeSnapshot(UUID id, Integer period,
            String idempotencyKey, vn.werwa.platform.openapi.model.TakeSnapshotRequest body) {
        UUID tx = snapshots.take(id, period, body.getAsOf(), true).orElseThrow();
        var ref = new vn.werwa.platform.openapi.model.ChainTxRef();
        ref.setChainTxId(tx);
        ref.setStatus(vn.werwa.platform.openapi.model.ChainTxRef.StatusEnum.BUILT);
        return ResponseEntity.accepted().body(ref);
    }

    @Override
    public ResponseEntity<SigningProposalRef> proposeSnapshotAttestation(UUID id, Integer period, String idempotencyKey) {
        var r = snapshots.proposeAttestation(id, period, true).orElseThrow();
        SigningProposalRef ref = new SigningProposalRef();
        ref.setSigningProposalId(r.id());
        ref.setStatus(SigningProposalRef.StatusEnum.fromValue(r.status()));
        return ResponseEntity.accepted().body(ref);
    }

    @Override
    public ResponseEntity<Transfer> createTransfer(String idempotencyKey, TransferCreate body) {
        TransferService.Transfer t = transfers.create(body.getSeriesId(), body.getFromWalletId(), body.getToWalletId(),
                body.getQuantity());
        return ResponseEntity.created(URI.create("/v1/transfers/" + t.id())).body(view(t));
    }

    @Override
    public ResponseEntity<Transfer> confirmTransfer(UUID id, String idempotencyKey) {
        return ResponseEntity.accepted().body(view(transfers.confirm(id)));
    }

    @Override
    public ResponseEntity<Transfer> cancelTransfer(UUID id, String idempotencyKey) {
        return ResponseEntity.ok(view(transfers.cancel(id)));
    }

    @Override
    public ResponseEntity<Transfer> getTransfer(UUID id) {
        return ResponseEntity.ok(view(transfers.view(id)));
    }

    @Override
    public ResponseEntity<ListTransfers200Response> listTransfers(UUID seriesId, String status, String cursor,
            Integer limit) {
        Paging.Page<TransferService.Transfer> page = transfers.list(seriesId, status, cursor, limit);
        ListTransfers200Response out = new ListTransfers200Response();
        out.setItems(page.items().stream().map(DistributionController::view).toList());
        out.setNextCursor(page.nextCursor());
        return ResponseEntity.ok(out);
    }

    static Transfer view(TransferService.Transfer t) {
        Transfer v = new Transfer();
        v.setId(t.id());
        v.setSeriesId(t.seriesId());
        v.setFromWalletId(t.fromWalletId());
        v.setToWalletId(t.toWalletId());
        v.setQuantity(t.quantity());
        v.setStatus(Transfer.StatusEnum.fromValue(t.status()));
        v.setCreatedAt(utc(t.createdAt()));
        v.setFromConfirmedAt(utc(t.fromConfirmedAt()));
        v.setToConfirmedAt(utc(t.toConfirmedAt()));
        v.setCustodianConfirmedAt(utc(t.custodianConfirmedAt()));
        v.setCancelledAt(utc(t.cancelledAt()));
        v.setRejectReason(t.rejectReason());
        v.setTxHash(t.txHash());
        return v;
    }

    private static java.time.OffsetDateTime utc(java.time.Instant at) {
        return at == null ? null : at.atOffset(ZoneOffset.UTC);
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
            it.setStatus(AllocationItem.StatusEnum.fromValue(i.status()));
            it.setVirtualAccount(i.virtualAccount());
            it.setPaymentDueDate(i.paymentDueDate());
            return it;
        }).toList());
        return v;
    }
}
