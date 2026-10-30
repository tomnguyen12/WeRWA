-- B9d (Q140–Q145): VSDC xác nhận sổ sau phát hành; sửa mục VSDC chỉ ra.

CREATE TABLE distribution.register_attestation (
    id                  uuid        PRIMARY KEY,
    series_id           uuid        NOT NULL,
    series_controller   text        NOT NULL CHECK (series_controller ~ '^0x[0-9a-f]{40}$'),
    at_block            bigint      NOT NULL CHECK (at_block >= 0),
    register_hash       text        NOT NULL CHECK (register_hash ~ '^0x[0-9a-f]{64}$'),
    holder_count        integer     NOT NULL CHECK (holder_count >= 0),
    total_quantity      bigint      NOT NULL CHECK (total_quantity >= 0),
    status              text        NOT NULL CHECK (status IN ('PROPOSED', 'SIGNING', 'ATTESTED', 'REJECTED', 'FAILED')),
    signing_proposal_id uuid,
    rejection           jsonb,
    rejected_by         text,
    attested_tx_hash    text        CHECK (attested_tx_hash ~ '^0x[0-9a-f]{64}$'),
    attested_at         timestamptz,
    overdue_alerted_at  timestamptz,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL
);
-- Mỗi series chỉ một lần lập đang mở
CREATE UNIQUE INDEX register_attestation_open ON distribution.register_attestation (series_id)
    WHERE status IN ('PROPOSED', 'SIGNING');
CREATE INDEX register_attestation_series ON distribution.register_attestation (series_id, created_at);

CREATE TABLE distribution.register_attestation_line (
    register_attestation_id uuid    NOT NULL REFERENCES distribution.register_attestation (id),
    wallet_address          text    NOT NULL CHECK (wallet_address ~ '^0x[0-9a-f]{40}$'),
    investor_org_id         uuid,
    quantity                bigint  NOT NULL CHECK (quantity > 0),
    allocation_ids          uuid[]  NOT NULL,
    PRIMARY KEY (register_attestation_id, wallet_address)
);

-- Q145: yêu cầu sửa mục đang duyệt hoặc đang gửi
ALTER TABLE distribution.allocation
    ADD COLUMN correction_reason        text,
    ADD COLUMN correction_action        text CHECK (correction_action IN ('BURN', 'DISMISS')),
    ADD COLUMN correction_approval_id   uuid,
    ADD COLUMN correction_chain_tx_id   uuid,
    ADD COLUMN correction_burned_block  bigint,
    ADD COLUMN confirmed_at             timestamptz;
