-- B1: Idempotency-Key của mọi lệnh ghi (đặc tả API 05; Q1: bảng kỹ thuật ở schema platform; Q6: theo người dùng).
-- Giữ 24 giờ; ứng dụng dọn định kỳ. Thân response lưu nguyên văn để trả lại đúng kết quả lần đầu.

CREATE TABLE platform.idempotency_key (
    subject               text        NOT NULL,
    idem_key              text        NOT NULL CHECK (length(idem_key) BETWEEN 1 AND 128),
    operation_id          text        NOT NULL,
    request_hash          text        NOT NULL,
    state                 text        NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    response_status       integer,
    response_content_type text,
    response_location     text,
    response_etag         text,
    response_body         bytea,
    created_at            timestamptz NOT NULL,
    completed_at          timestamptz,
    PRIMARY KEY (subject, idem_key)
);

CREATE INDEX idempotency_key_created_at ON platform.idempotency_key (created_at);
