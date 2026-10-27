-- B4a: Quản trị lõi (mô hình dữ liệu 02, phân hệ 4; ADR-13; US-4.08).
-- Người dùng ghi theo subject OIDC (Keycloak, dạng UUID) tới khi có bảng app_user ở B5.

-- Yêu cầu duyệt 4 mắt (Q18, Q19).
CREATE TABLE governance.approval_request (
    id              uuid        PRIMARY KEY,
    action_type     text        NOT NULL,
    subject_ref     text        NOT NULL,
    payload_hash    text        NOT NULL CHECK (payload_hash ~ '^0x[0-9a-f]{64}$'),
    maker_subject   text        NOT NULL,
    maker_org_id    uuid        NOT NULL,
    checker_subject text,
    status          text        NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    comment         text,
    created_at      timestamptz NOT NULL,
    decided_at      timestamptz,
    CHECK (checker_subject IS NULL OR checker_subject <> maker_subject)
);
-- Mỗi đối tượng chỉ có một yêu cầu đang chờ cho mỗi loại thao tác.
CREATE UNIQUE INDEX approval_request_one_pending
    ON governance.approval_request (action_type, subject_ref) WHERE status = 'PENDING';
CREATE INDEX approval_request_queue ON governance.approval_request (maker_org_id, status, id);

-- Nhật ký kiểm toán nối mã băm (ADR-13): entry_hash = SHA-256(prev_hash ‖ bản ghi chuẩn hóa). Chỉ ghi thêm.
CREATE TABLE governance.audit_log (
    seq           bigint      PRIMARY KEY,
    id            uuid        NOT NULL UNIQUE,
    occurred_at   timestamptz NOT NULL,
    actor_subject text,
    actor_org_id  uuid,
    action        text        NOT NULL,
    subject_ref   text        NOT NULL,
    payload_hash  text        NOT NULL CHECK (payload_hash ~ '^0x[0-9a-f]{64}$'),
    prev_hash     text        NOT NULL CHECK (prev_hash ~ '^0x[0-9a-f]{64}$'),
    entry_hash    text        NOT NULL CHECK (entry_hash ~ '^0x[0-9a-f]{64}$')
);
CREATE INDEX audit_log_occurred_at ON governance.audit_log (occurred_at);

-- Chặn sửa, xóa nhật ký từ ứng dụng. Không thay được bộ kiểm chuỗi: người có quyền DB vẫn tắt được trigger,
-- nên sửa trái phép vẫn phải lộ ra khi kiểm mã băm và đối chiếu với mã băm đã neo trên chuỗi.
CREATE FUNCTION governance.audit_log_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'governance.audit_log chỉ ghi thêm';
END;
$$;
CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON governance.audit_log
    FOR EACH ROW EXECUTE FUNCTION governance.audit_log_append_only();

-- Neo hằng ngày lên AuditAnchor (Q21): mỗi ngày nghiệp vụ một dòng.
CREATE TABLE governance.audit_anchor (
    business_date   date        PRIMARY KEY,
    last_seq        bigint      NOT NULL,
    last_entry_hash text        NOT NULL CHECK (last_entry_hash ~ '^0x[0-9a-f]{64}$'),
    status          text        NOT NULL CHECK (status IN ('SUBMITTED', 'ANCHORED', 'FAILED', 'CONFLICT')),
    chain_tx_id     uuid,
    tx_hash         text,
    block_number    bigint,
    failure_reason  text,
    created_at      timestamptz NOT NULL,
    anchored_at     timestamptz
);
