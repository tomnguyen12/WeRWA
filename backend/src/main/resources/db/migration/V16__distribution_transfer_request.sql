-- B9a: chuyển OTC không kèm thanh toán, phần ngoài chuỗi (US-3.08; Q77–Q82).
-- from_org_id, to_org_id chép từ ví lúc tạo để lọc quyền xem (Q81). Gửi lên chuỗi (chain_tx_id, tx_hash) ở B9b.
CREATE TABLE distribution.transfer_request (
    id                     uuid        PRIMARY KEY,
    series_id              uuid        NOT NULL,
    from_wallet_id         uuid        NOT NULL,
    to_wallet_id           uuid        NOT NULL,
    from_org_id            uuid        NOT NULL,
    to_org_id              uuid        NOT NULL,
    from_wallet_type       text        NOT NULL CHECK (from_wallet_type IN ('SELF', 'OMNIBUS')),
    to_wallet_type         text        NOT NULL CHECK (to_wallet_type IN ('SELF', 'OMNIBUS')),
    quantity               bigint      NOT NULL CHECK (quantity >= 1),
    status                 text        NOT NULL
        CHECK (status IN ('PENDING_CONFIRMATIONS', 'SUBMITTED', 'COMPLETED', 'REJECTED', 'CANCELLED')),
    from_confirmed_at      timestamptz,
    to_confirmed_at        timestamptz,
    custodian_confirmed_at timestamptz,
    cancelled_at           timestamptz,
    reject_reason          text,
    chain_tx_id            uuid,
    tx_hash                text        CHECK (tx_hash ~ '^0x[0-9a-f]{64}$'),
    created_by             text        NOT NULL,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    CHECK (from_wallet_id <> to_wallet_id)
);

CREATE INDEX transfer_request_series_idx ON distribution.transfer_request (series_id);
CREATE INDEX transfer_request_from_org_idx ON distribution.transfer_request (from_org_id);
CREATE INDEX transfer_request_to_org_idx ON distribution.transfer_request (to_org_id);
