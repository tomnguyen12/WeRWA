package vn.werwa.issuance;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.werwa.issuance.decision.IssuerDecisionService;
import vn.werwa.issuance.document.DocumentService;
import vn.werwa.issuance.program.ProgramService;
import vn.werwa.issuance.series.SeriesService;
import vn.werwa.platform.web.ETags;
import vn.werwa.platform.openapi.api.IssuanceApi;
import vn.werwa.platform.openapi.model.Document;
import vn.werwa.platform.openapi.model.DocumentDownload;
import vn.werwa.platform.openapi.model.IssuerDecisionCreate;
import vn.werwa.platform.openapi.model.Program;
import vn.werwa.platform.openapi.model.ProgramCreate;
import vn.werwa.platform.openapi.model.ProgramLimits;
import vn.werwa.platform.openapi.model.Series;
import vn.werwa.platform.openapi.model.SeriesCreate;
import vn.werwa.platform.openapi.model.SeriesEvent;
import vn.werwa.platform.openapi.model.SigningProposalRef;

/** API Phát hành: tài liệu (B6a); Đề án, series, quyết định UBND (B6b). Triển khai series làm ở B6c; tới lúc đó trả 501 (Q20). */
@RestController
class IssuanceController implements IssuanceApi {

    private final DocumentService documents;
    private final ProgramService programs;
    private final SeriesService series;
    private final IssuerDecisionService decisions;

    IssuanceController(DocumentService documents, ProgramService programs, SeriesService series,
            IssuerDecisionService decisions) {
        this.documents = documents;
        this.programs = programs;
        this.series = series;
        this.decisions = decisions;
    }

    @Override
    public ResponseEntity<Program> createProgram(String idempotencyKey, ProgramCreate body) {
        ProgramService.Program p = programs.create(new ProgramService.Input(body.getTitle(), body.getHdndResolutionNo(),
                body.getApprovedOn(), body.getCurrency(), money(body.getMaxPrincipal()), body.getRateFloorPpm(),
                body.getRateCapPpm(), money(body.getCostCap()), body.getTenorRange(), body.getEligibleInvestorClasses(),
                body.getIssuanceMethods(), body.getDocumentId(), body.getAmendsProgramId()));
        return ResponseEntity.created(URI.create("/v1/programs/" + p.id())).body(view(p));
    }

    @Override
    public ResponseEntity<Program> getProgram(UUID id) {
        return ResponseEntity.ok(view(programs.get(id)));
    }

    @Override
    public ResponseEntity<ProgramLimits> getProgramLimits(UUID id) {
        ProgramService.Limits l = programs.limits(id);
        ProgramLimits v = new ProgramLimits();
        v.setProgramId(l.programId());
        v.setMaxPrincipal(money(l.maxPrincipal()));
        v.setReserved(money(l.reserved()));
        v.setUsed(money(l.used()));
        v.setAvailable(money(l.available()));
        return ResponseEntity.ok(v);
    }

    @Override
    public ResponseEntity<Series> createSeries(String idempotencyKey, SeriesCreate body) {
        SeriesService.Series s = series.create(body.getProgramId(), new SeriesService.Terms(body.getSeriesCode(),
                body.getCurrency(), money(body.getFaceValue()), body.getIssuePricePpm(), body.getMaxQuantity(),
                body.getCouponType() == null ? null : body.getCouponType().getValue(), body.getCouponFrequency().getValue(),
                body.getDayCount().getValue(), body.getIssueDate(), body.getMaturityDate(),
                body.getCalendars() == null ? null : body.getCalendars().stream().map(c -> c.getValue()).toList(),
                body.getGraceBusinessDays(), body.getWithholdingTaxPpm(), body.getHoldingLimit(), body.getInvestorClasses(),
                body.getTermsDocumentId(), null));
        return ResponseEntity.created(URI.create("/v1/series/" + s.id())).eTag(ETags.of(s.version())).body(view(s));
    }

    @Override
    public ResponseEntity<Series> getSeries(UUID id) {
        SeriesService.Series s = series.get(id);
        return ResponseEntity.ok().eTag(ETags.of(s.version())).body(view(s));
    }

    @Override
    public ResponseEntity<SigningProposalRef> submitSeries(UUID id, String idempotencyKey) {
        UUID approval = series.submit(id);
        SigningProposalRef ref = new SigningProposalRef();
        ref.setApprovalRequestId(approval);
        ref.setStatus(SigningProposalRef.StatusEnum.PENDING_APPROVAL);
        return ResponseEntity.accepted().body(ref);
    }

    @Override
    public ResponseEntity<Void> recordIssuerDecision(String idempotencyKey, IssuerDecisionCreate body) {
        IssuerDecisionService.Decision d = decisions.record(body.getDecisionNo(), body.getDecidedOn(),
                body.getSubject().getValue(), body.getSeriesId(), body.getOfferingId(), body.getDocumentId());
        return ResponseEntity.created(URI.create("/v1/issuer-decisions/" + d.id())).build();
    }

    static Program view(ProgramService.Program p) {
        Program v = new Program();
        v.setId(p.id());
        v.setTitle(p.title());
        v.setHdndResolutionNo(p.hdndResolutionNo());
        v.setApprovedOn(p.approvedOn());
        v.setCurrency(p.currency());
        v.setMaxPrincipal(money(p.maxPrincipal()));
        v.setRateFloorPpm(p.rateFloorPpm());
        v.setRateCapPpm(p.rateCapPpm());
        v.setCostCap(money(p.costCap()));
        v.setTenorRange(p.tenorRange());
        v.setEligibleInvestorClasses(p.eligibleInvestorClasses());
        v.setIssuanceMethods(p.issuanceMethods());
        v.setDocumentId(p.documentId());
        v.setAmendsProgramId(p.amendsProgramId());
        v.setStatus(Program.StatusEnum.fromValue(p.status()));
        return v;
    }

    static Series view(SeriesService.Series s) {
        SeriesService.Terms t = s.terms();
        Series v = new Series();
        v.setId(s.id());
        v.setProgramId(s.programId());
        v.setSeriesCode(t.seriesCode());
        v.setCurrency(t.currency());
        v.setFaceValue(money(t.faceValue()));
        v.setIssuePricePpm(t.issuePricePpm());
        v.setMaxQuantity(t.maxQuantity());
        v.setCouponType(Series.CouponTypeEnum.fromValue(t.couponType()));
        v.setCouponFrequency(Series.CouponFrequencyEnum.fromValue(t.couponFrequency()));
        v.setDayCount(Series.DayCountEnum.fromValue(t.dayCount()));
        v.setIssueDate(t.issueDate());
        v.setMaturityDate(t.maturityDate());
        v.setCalendars(t.calendars().stream().map(Series.CalendarsEnum::fromValue).toList());
        v.setGraceBusinessDays(t.graceBusinessDays());
        v.setWithholdingTaxPpm(t.withholdingTaxPpm());
        v.setHoldingLimit(t.holdingLimit());
        v.setInvestorClasses(t.investorClasses());
        v.setTermsDocumentId(t.termsDocumentId());
        v.setStatus(Series.StatusEnum.fromValue(s.status()));
        v.setTermsHash(s.termsHash());
        v.setIsin(s.isin());
        v.setChainId(s.chainId() == null ? null : s.chainId().intValue());
        v.setControllerAddress(s.controllerAddress());
        v.setTokenAddress(s.tokenAddress());
        v.setEvents(s.events().stream().map(e -> {
            SeriesEvent ev = new SeriesEvent();
            ev.setEventType(SeriesEvent.EventTypeEnum.fromValue(e.eventType()));
            ev.setPeriodNo(e.periodNo());
            ev.setPeriodStart(e.periodStart());
            ev.setPeriodEnd(e.periodEnd());
            ev.setRecordDate(e.recordDate());
            ev.setPaymentDate(e.paymentDate());
            return ev;
        }).toList());
        return v;
    }

    static vn.werwa.platform.money.Money money(vn.werwa.platform.openapi.model.Money m) {
        return vn.werwa.platform.money.Money.parse(m.getAmountMinor(), m.getCurrency());
    }

    static vn.werwa.platform.openapi.model.Money money(vn.werwa.platform.money.Money m) {
        vn.werwa.platform.openapi.model.Money v = new vn.werwa.platform.openapi.model.Money();
        v.setAmountMinor(m.amountMinorString());
        v.setCurrency(m.currency());
        return v;
    }

    @Override
    public ResponseEntity<Document> uploadDocument(String idempotencyKey, MultipartFile file, String docType, String title,
            UUID supersedesId, String visibility) {
        DocumentService.Doc doc = documents.upload(docType, title, supersedesId, visibility,
                file.getContentType() == null ? "application/octet-stream" : file.getContentType(), file.getSize(),
                () -> open(file));
        return ResponseEntity.created(URI.create("/v1/documents/" + doc.id())).body(view(doc));
    }

    @Override
    public ResponseEntity<Document> getDocument(UUID id) {
        return ResponseEntity.ok(view(documents.get(id)));
    }

    @Override
    public ResponseEntity<DocumentDownload> getDocumentContent(UUID id) {
        DocumentService.Download d = documents.download(id);
        DocumentDownload out = new DocumentDownload();
        out.setUrl(URI.create(d.url()));
        out.setExpiresAt(d.expiresAt().atOffset(ZoneOffset.UTC));
        out.setSha256(d.sha256());
        return ResponseEntity.ok(out);
    }

    static Document view(DocumentService.Doc d) {
        Document v = new Document();
        v.setId(d.id());
        v.setDocType(d.docType());
        v.setTitle(d.title());
        v.setVersionNo(d.versionNo());
        v.setSha256(d.sha256());
        v.setSupersedesId(d.supersedesId());
        v.setVisibility(Document.VisibilityEnum.fromValue(d.visibility()));
        return v;
    }

    private static InputStream open(MultipartFile file) {
        try {
            return file.getInputStream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
