package vn.werwa.governance;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RestController;
import vn.werwa.governance.approval.ApprovalService;
import vn.werwa.governance.approval.ApprovalService.Row;
import vn.werwa.platform.openapi.api.GovernanceApi;
import vn.werwa.platform.openapi.model.ApprovalRequest;
import vn.werwa.platform.openapi.model.AuditEntry;
import vn.werwa.platform.openapi.model.AuditEntryAnchor;
import vn.werwa.platform.openapi.model.Decision;
import vn.werwa.platform.openapi.model.ListApprovals200Response;
import vn.werwa.platform.openapi.model.ListAuditLog200Response;
import vn.werwa.platform.web.Paging;

/**
 * API của mô-đun Quản trị đã làm ở B4a: hàng chờ duyệt, quyết định duyệt, nhật ký kiểm toán. API khác của nhóm
 * {@code governance} trả 501 tới nhóm PR làm chúng (Q20).
 */
@RestController
class GovernanceController implements GovernanceApi {

    private final ApprovalService approvals;
    private final JdbcTemplate jdbc;
    private final vn.werwa.governance.alert.AlertService alerts;

    private final vn.werwa.governance.parameter.ParameterService parameters;
    private final vn.werwa.governance.calendar.CalendarAdjustmentService calendars;
    private final vn.werwa.governance.notification.NotificationService notifications;
    private final vn.werwa.governance.enforcement.EnforcementService enforcement;
    private final vn.werwa.governance.reconciliation.ReconciliationService reconciliation;
    private final vn.werwa.governance.supervision.SupervisionService supervision;

    GovernanceController(ApprovalService approvals, JdbcTemplate jdbc, vn.werwa.governance.alert.AlertService alerts,
            vn.werwa.governance.parameter.ParameterService parameters,
            vn.werwa.governance.calendar.CalendarAdjustmentService calendars,
            vn.werwa.governance.notification.NotificationService notifications,
            vn.werwa.governance.enforcement.EnforcementService enforcement,
            vn.werwa.governance.reconciliation.ReconciliationService reconciliation,
            vn.werwa.governance.supervision.SupervisionService supervision) {
        this.supervision = supervision;
        this.enforcement = enforcement;
        this.reconciliation = reconciliation;
        this.approvals = approvals;
        this.jdbc = jdbc;
        this.alerts = alerts;
        this.parameters = parameters;
        this.calendars = calendars;
        this.notifications = notifications;
    }

    // ---------- Công cụ giám sát (B12b; Q90–Q96) ----------

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.ListSupervisionTransactions200Response> listSupervisionTransactions(
            Long afterBlock, UUID seriesId, String cursor, Integer limit) {
        Paging.Page<vn.werwa.onchain.api.IndexedEvents.ChainEvent> page = supervision.transactions(afterBlock, seriesId,
                cursor, limit);
        var out = new vn.werwa.platform.openapi.model.ListSupervisionTransactions200Response();
        out.setItems(page.items().stream().map(e -> {
            var v = new vn.werwa.platform.openapi.model.ChainEvent();
            v.setBlock(e.block());
            v.setLogIndex(e.logIndex());
            v.setTxHash(e.txHash());
            v.setBlockTime(e.blockTime() == null ? null : e.blockTime().atOffset(java.time.ZoneOffset.UTC));
            v.setContract(e.contract());
            v.setContractKind(e.contractKind() == null ? "unknown" : e.contractKind());
            v.setEventName(e.eventName());
            v.setSeriesId(e.seriesId());
            v.setArgs(e.args());
            return v;
        }).toList());
        out.setNextCursor(page.nextCursor());
        return ResponseEntity.ok(out);
    }

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.SupervisionStatus> getSupervisionStatus() {
        vn.werwa.governance.supervision.SupervisionService.Status s = supervision.status();
        var v = new vn.werwa.platform.openapi.model.SupervisionStatus();
        v.setHeadBlock(s.headBlock());
        v.setIndexedBlock(s.indexedBlock());
        v.setLagSeconds(s.lagSeconds());
        v.setMaxLagSeconds(Math.toIntExact(s.maxLagSeconds()));
        v.setWithinLimit(s.withinLimit());
        v.setCheckedAt(s.checkedAt().atOffset(java.time.ZoneOffset.UTC));
        return ResponseEntity.ok(v);
    }

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.BlockHashAttestation> attestBlockHash(String idempotencyKey,
            vn.werwa.platform.openapi.model.BlockHashAttestationCreate body) {
        vn.werwa.governance.supervision.SupervisionService.Attestation a = supervision.attest(body.getBlockNumber(),
                body.getBlockHash());
        var v = new vn.werwa.platform.openapi.model.BlockHashAttestation();
        v.setId(a.id());
        v.setBlockNumber(a.blockNumber());
        v.setBlockHash(a.blockHash());
        v.setO1BlockHash(a.o1BlockHash());
        v.setStatus(vn.werwa.platform.openapi.model.BlockHashAttestation.StatusEnum.fromValue(a.status()));
        v.setAttestedAt(a.attestedAt().atOffset(java.time.ZoneOffset.UTC));
        return ResponseEntity.created(java.net.URI.create("/v1/supervision/block-hash-attestations/" + a.id())).body(v);
    }

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.ListBlockHashChecks200Response> listBlockHashChecks(
            String cursor, Integer limit) {
        Paging.Page<vn.werwa.governance.reconciliation.ReconciliationService.Run> page = reconciliation.list(
                "BLOCK_HASH_VSDC", cursor, limit);
        var out = new vn.werwa.platform.openapi.model.ListBlockHashChecks200Response();
        out.setItems(page.items().stream().map(GovernanceController::view).toList());
        out.setNextCursor(page.nextCursor());
        return ResponseEntity.ok(out);
    }

    // ---------- Đối soát (B4c; Q73–Q76) ----------

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.ListReconciliationRuns200Response> listReconciliationRuns(
            String cursor, Integer limit) {
        Paging.Page<vn.werwa.governance.reconciliation.ReconciliationService.Run> page = reconciliation.list(cursor, limit);
        var out = new vn.werwa.platform.openapi.model.ListReconciliationRuns200Response();
        out.setItems(page.items().stream().map(GovernanceController::view).toList());
        out.setNextCursor(page.nextCursor());
        return ResponseEntity.ok(out);
    }

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.ReconciliationBreak> resolveReconciliationBreak(UUID id,
            String idempotencyKey, vn.werwa.platform.openapi.model.ReconciliationResolve body) {
        return ResponseEntity.ok(view(reconciliation.resolve(id, body.getReason())));
    }

    static vn.werwa.platform.openapi.model.ReconciliationRun view(
            vn.werwa.governance.reconciliation.ReconciliationService.Run r) {
        var v = new vn.werwa.platform.openapi.model.ReconciliationRun();
        v.setId(r.id());
        v.setRunDate(r.runDate());
        v.setScope(vn.werwa.platform.openapi.model.ReconciliationRun.ScopeEnum.fromValue(r.scope()));
        v.setBreakCount(r.breakCount());
        v.setBreaks(r.breaks().stream().map(GovernanceController::view).toList());
        return v;
    }

    static vn.werwa.platform.openapi.model.ReconciliationBreak view(
            vn.werwa.governance.reconciliation.ReconciliationService.Break b) {
        var v = new vn.werwa.platform.openapi.model.ReconciliationBreak();
        v.setId(b.id());
        v.setRule(vn.werwa.platform.openapi.model.ReconciliationBreak.RuleEnum.fromValue(b.rule()));
        v.setKind(b.kind());
        v.setSubjectRef(b.subjectRef());
        v.setExpected(b.expected());
        v.setActual(b.actual());
        v.setResolvedBy(b.resolvedBy() == null ? null : UUID.fromString(b.resolvedBy()));
        v.setResolvedAt(b.resolvedAt() == null ? null : b.resolvedAt().atOffset(ZoneOffset.UTC));
        v.setResolution(b.resolution());
        return v;
    }

    // ---------- Lệnh phong tỏa (B10a; Q64–Q67) ----------

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.EnforcementOrder> receiveEnforcementOrder(String idempotencyKey,
            vn.werwa.platform.openapi.model.EnforcementOrderCreate body) {
        var o = enforcement.receive(new vn.werwa.governance.enforcement.EnforcementService.Input(body.getAuthorityName(),
                body.getOrderNo(), body.getIssuedOn(), body.getBasisDocumentId(), body.getAction().getValue(),
                body.getSeriesId(), body.getWalletId(), body.getToWalletId(), body.getQuantity()));
        return ResponseEntity.created(java.net.URI.create("/v1/enforcement-orders/" + o.id())).body(view(o));
    }

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.EnforcementOrder> getEnforcementOrder(UUID id) {
        return ResponseEntity.ok(view(enforcement.get(id)));
    }

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.ListEnforcementOrders200Response> listEnforcementOrders(
            String cursor, Integer limit) {
        Paging.Page<vn.werwa.governance.enforcement.EnforcementService.Order> page = enforcement.list(cursor, limit);
        var out = new vn.werwa.platform.openapi.model.ListEnforcementOrders200Response();
        out.setItems(page.items().stream().map(GovernanceController::view).toList());
        out.setNextCursor(page.nextCursor());
        return ResponseEntity.ok(out);
    }

    static vn.werwa.platform.openapi.model.EnforcementOrder view(vn.werwa.governance.enforcement.EnforcementService.Order o) {
        var v = new vn.werwa.platform.openapi.model.EnforcementOrder();
        v.setId(o.id());
        v.setAuthorityName(o.authorityName());
        v.setOrderNo(o.orderNo());
        v.setIssuedOn(o.issuedOn());
        v.setBasisDocumentId(o.basisDocumentId());
        v.setAction(vn.werwa.platform.openapi.model.EnforcementOrder.ActionEnum.fromValue(o.action()));
        v.setSeriesId(o.seriesId());
        v.setWalletId(o.walletId());
        v.setToWalletId(o.toWalletId());
        v.setQuantity(o.quantity());
        v.setBasisHash(o.basisHash());
        v.setApprovalRequestId(o.approvalRequestId());
        v.setStatus(vn.werwa.platform.openapi.model.EnforcementOrder.StatusEnum.fromValue(o.status()));
        return v;
    }

    // ---------- Tham số (Q60) ----------

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.ListParameters200Response> listParameters() {
        var out = new vn.werwa.platform.openapi.model.ListParameters200Response();
        out.setItems(parameters.list().stream().map(c -> {
            var v = new vn.werwa.platform.openapi.model.Parameter();
            v.setKey(c.definition().key());
            v.setType(vn.werwa.platform.openapi.model.Parameter.TypeEnum.fromValue(c.definition().type().name()));
            v.setAllowedValues(c.definition().allowed());
            v.setValue(c.value());
            v.setSource(vn.werwa.platform.openapi.model.Parameter.SourceEnum.fromValue(c.value() == null ? "DEFAULT" : "TABLE"));
            v.setEffectiveFrom(c.effectiveFrom());
            v.setScheduled(c.scheduled().stream().map(GovernanceController::view).toList());
            return v;
        }).toList());
        return ResponseEntity.ok(out);
    }

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.ParameterChange> requestParameterChange(String key,
            String idempotencyKey, vn.werwa.platform.openapi.model.ParameterChangeCreate body) {
        var c = parameters.request(key, body.getValue(), body.getEffectiveFrom(), body.getReason());
        return ResponseEntity.status(201).body(view(c));
    }

    static vn.werwa.platform.openapi.model.ParameterChange view(vn.werwa.governance.parameter.ParameterService.Change c) {
        var v = new vn.werwa.platform.openapi.model.ParameterChange();
        v.setId(c.id());
        v.setKey(c.key());
        v.setValue(c.value());
        v.setEffectiveFrom(c.effectiveFrom());
        v.setReason(c.reason());
        v.setStatus(vn.werwa.platform.openapi.model.ParameterChange.StatusEnum.fromValue(c.status()));
        v.setApprovalRequestId(c.approvalRequestId());
        return v;
    }

    // ---------- Lịch ngày nghỉ (Q61) ----------

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.CalendarYear> listCalendarDays(String code, Integer year) {
        var y = calendars.year(code, year);
        var v = new vn.werwa.platform.openapi.model.CalendarYear();
        v.setCalendarCode(y.calendarCode());
        v.setYear(y.year());
        v.setHolidays(y.holidays().stream().map(GovernanceController::view).toList());
        v.setWorkingDays(y.workingDays().stream().map(GovernanceController::view).toList());
        return ResponseEntity.ok(v);
    }

    static vn.werwa.platform.openapi.model.CalendarDay view(vn.werwa.governance.calendar.CalendarAdjustmentService.Day d) {
        var v = new vn.werwa.platform.openapi.model.CalendarDay();
        v.setDay(d.day());
        v.setName(d.name());
        v.setSource(vn.werwa.platform.openapi.model.CalendarDay.SourceEnum.fromValue(d.source()));
        return v;
    }

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.CalendarAdjustment> requestCalendarAdjustment(String code,
            String idempotencyKey, vn.werwa.platform.openapi.model.CalendarAdjustmentCreate body) {
        var r = calendars.request(code, body.getDay(), body.getKind().getValue(), body.getName());
        var v = new vn.werwa.platform.openapi.model.CalendarAdjustment();
        v.setId(r.id());
        v.setCalendarCode(r.calendarCode());
        v.setDay(r.day());
        v.setKind(vn.werwa.platform.openapi.model.CalendarAdjustment.KindEnum.fromValue(r.kind()));
        v.setName(r.name());
        v.setStatus(vn.werwa.platform.openapi.model.CalendarAdjustment.StatusEnum.fromValue(r.status()));
        v.setApprovalRequestId(r.approvalRequestId());
        return ResponseEntity.status(201).body(v);
    }

    // ---------- Thông báo (Q62) ----------

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.ListNotifications200Response> listNotifications(String cursor,
            Integer limit) {
        Paging.Page<vn.werwa.governance.notification.NotificationService.Item> page = notifications.list(cursor, limit);
        var out = new vn.werwa.platform.openapi.model.ListNotifications200Response();
        out.setItems(page.items().stream().map(GovernanceController::view).toList());
        out.setNextCursor(page.nextCursor());
        return ResponseEntity.ok(out);
    }

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.Notification> markNotificationRead(UUID id, String idempotencyKey) {
        return ResponseEntity.ok(view(notifications.markRead(id)));
    }

    static vn.werwa.platform.openapi.model.Notification view(vn.werwa.governance.notification.NotificationService.Item n) {
        var v = new vn.werwa.platform.openapi.model.Notification();
        v.setId(n.id());
        v.setKind(vn.werwa.platform.openapi.model.Notification.KindEnum.fromValue(n.kind()));
        v.setSeverity(vn.werwa.platform.openapi.model.Notification.SeverityEnum.fromValue(n.severity()));
        v.setTitle(n.title());
        v.setBody(n.body());
        v.setSubjectRef(n.subjectRef());
        v.setCreatedAt(n.createdAt().atOffset(ZoneOffset.UTC));
        v.setRead(n.read());
        return v;
    }

    /** I5, US-6.01-AC3 (Q56, Q57). */
    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.Ack> receiveAlertmanagerWebhook(
            vn.werwa.platform.openapi.model.AlertmanagerWebhook body) {
        List<vn.werwa.governance.alert.AlertService.Incoming> incoming = body.getAlerts().stream()
                .map(a -> new vn.werwa.governance.alert.AlertService.Incoming(a.getFingerprint(),
                        a.getStatus() == vn.werwa.platform.openapi.model.AlertmanagerAlert.StatusEnum.FIRING,
                        a.getLabels(), a.getAnnotations(), a.getStartsAt().toInstant(),
                        a.getEndsAt() == null ? null : a.getEndsAt().toInstant()))
                .toList();
        alerts.receive("alertmanager", incoming);
        vn.werwa.platform.openapi.model.Ack ack = new vn.werwa.platform.openapi.model.Ack();
        ack.setReceived(true);
        return ResponseEntity.ok(ack);
    }

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.ListAlerts200Response> listAlerts(String status, String cursor,
            Integer limit) {
        Paging.Page<vn.werwa.governance.alert.AlertService.Alert> page = alerts.list(status, cursor, limit);
        vn.werwa.platform.openapi.model.ListAlerts200Response out = new vn.werwa.platform.openapi.model.ListAlerts200Response();
        out.setItems(page.items().stream().map(GovernanceController::view).toList());
        out.setNextCursor(page.nextCursor());
        return ResponseEntity.ok(out);
    }

    @Override
    public ResponseEntity<vn.werwa.platform.openapi.model.OperationalAlert> acknowledgeAlert(UUID id, String idempotencyKey) {
        return ResponseEntity.ok(view(alerts.acknowledge(id)));
    }

    static vn.werwa.platform.openapi.model.OperationalAlert view(vn.werwa.governance.alert.AlertService.Alert a) {
        vn.werwa.platform.openapi.model.OperationalAlert v = new vn.werwa.platform.openapi.model.OperationalAlert();
        v.setId(a.id());
        v.setFingerprint(a.fingerprint());
        v.setName(a.name());
        v.setSeverity(a.severity());
        v.setSummary(a.summary());
        v.setLabels(a.labels());
        v.setStatus(vn.werwa.platform.openapi.model.OperationalAlert.StatusEnum.fromValue(a.status()));
        v.setStartsAt(a.startsAt().atOffset(ZoneOffset.UTC));
        v.setEndsAt(a.endsAt() == null ? null : a.endsAt().atOffset(ZoneOffset.UTC));
        v.setAcknowledgedBy(a.acknowledgedBy());
        v.setAcknowledgedAt(a.acknowledgedAt() == null ? null : a.acknowledgedAt().atOffset(ZoneOffset.UTC));
        return v;
    }

    @Override
    public ResponseEntity<ListApprovals200Response> listApprovals(String cursor, Integer limit) {
        Paging.Page<Row> page = approvals.queue(cursor, limit);
        return ResponseEntity.ok(new ListApprovals200Response(page.items().stream().map(GovernanceController::view).toList())
                .nextCursor(page.nextCursor()));
    }

    @Override
    public ResponseEntity<ApprovalRequest> decideApproval(UUID id, String idempotencyKey, Decision decision) {
        boolean approve = decision.getDecision() == Decision.DecisionEnum.APPROVE;
        return ResponseEntity.ok(view(approvals.decideRow(id, approve, decision.getComment())));
    }

    @Override
    public ResponseEntity<ListAuditLog200Response> listAuditLog(String cursor, Integer limit) {
        int size = Paging.limit(limit);
        Long after = Paging.decodeSeqCursor(cursor);
        List<AuditEntry> rows = jdbc.query("""
                SELECT l.seq, l.occurred_at, l.actor_subject, l.actor_org_id, l.action, l.subject_ref, l.payload_hash,
                       l.prev_hash, l.entry_hash, a.business_date, a.tx_hash
                FROM governance.audit_log l
                LEFT JOIN LATERAL (
                    SELECT business_date, tx_hash FROM governance.audit_anchor
                    WHERE status = 'ANCHORED' AND last_seq >= l.seq ORDER BY business_date LIMIT 1) a ON true
                WHERE l.seq > ? ORDER BY l.seq LIMIT ?
                """, (rs, i) -> {
            AuditEntry e = new AuditEntry(
                    (int) rs.getLong(1),
                    rs.getTimestamp(2).toInstant().atOffset(ZoneOffset.UTC),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9));
            e.setActorUserId(uuidOrNull(rs.getString(3)));
            e.setActorOrgId(rs.getObject(4, UUID.class));
            if (rs.getString(11) != null) {
                e.setAnchor(new AuditEntryAnchor().businessDate(rs.getObject(10, LocalDate.class)).txHash(rs.getString(11)));
            }
            return e;
        }, after == null ? 0L : after, size + 1);
        String next = null;
        if (rows.size() > size) {
            rows = rows.subList(0, size);
            next = Paging.encodeSeqCursor(rows.getLast().getSeq());
        }
        return ResponseEntity.ok(new ListAuditLog200Response(rows).nextCursor(next));
    }

    static ApprovalRequest view(Row r) {
        ApprovalRequest v = new ApprovalRequest(r.id(), r.actionType(), r.subjectRef(), r.payloadHash(),
                UUID.fromString(r.makerSubject()), ApprovalRequest.StatusEnum.fromValue(r.status()));
        v.setCheckerUserId(uuidOrNull(r.checkerSubject()));
        v.setComment(r.comment());
        v.setDecidedAt(r.decidedAt() == null ? null : r.decidedAt().toInstant().atOffset(ZoneOffset.UTC));
        return v;
    }

    /** Người dùng ghi theo subject OIDC (UUID của Keycloak) tới khi có bảng app_user ở B5. */
    private static UUID uuidOrNull(String subject) {
        if (subject == null) {
            return null;
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
