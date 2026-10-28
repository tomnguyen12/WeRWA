-- B10a: tiếp nhận lệnh phong tỏa, giải tỏa, chuyển cưỡng chế (US-4.02-AC1, AC4; Q64–Q67).

CREATE TABLE governance.enforcement_order (
    id                    uuid        PRIMARY KEY,
    authority_name        text        NOT NULL,
    order_no              text        NOT NULL,
    issued_on             date        NOT NULL,
    basis_document_id     uuid        NOT NULL,
    basis_document_sha256 text        NOT NULL,
    action                text        NOT NULL CHECK (action IN ('FREEZE', 'UNFREEZE', 'FORCED_TRANSFER')),
    series_id             uuid        NOT NULL,
    wallet_id             uuid        NOT NULL,
    wallet_address        text        NOT NULL,
    to_wallet_id          uuid,
    to_wallet_address     text,
    quantity              bigint      NOT NULL CHECK (quantity > 0),
    basis_hash            text        NOT NULL CHECK (basis_hash ~ '^0x[0-9a-f]{64}$'),
    status                text        NOT NULL CHECK (status IN ('RECEIVED', 'PENDING_APPROVAL', 'PENDING_SIGNATURES',
                                                                 'APPLIED', 'REJECTED')),
    approval_request_id   uuid,
    reject_reason         text,
    owner_org_id          uuid        NOT NULL,
    created_by            text        NOT NULL,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    UNIQUE (authority_name, order_no, action),
    CHECK ((action = 'FORCED_TRANSFER') = (to_wallet_id IS NOT NULL))
);
