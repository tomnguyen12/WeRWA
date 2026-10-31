-- B14 (Q172–Q175): tạm dừng và mở lại series.

ALTER TABLE issuance.series
    ADD COLUMN paused    boolean NOT NULL DEFAULT false,
    ADD COLUMN paused_at timestamptz;

CREATE TABLE issuance.series_pause_request (
    id                  uuid        PRIMARY KEY,
    series_id           uuid        NOT NULL REFERENCES issuance.series (id),
    action              text        NOT NULL CHECK (action IN ('PAUSE', 'UNPAUSE')),
    reason              text        NOT NULL,
    basis_hash          text        NOT NULL CHECK (basis_hash ~ '^0x[0-9a-f]{64}$'),
    status              text        NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'SIGNING', 'APPLIED', 'REJECTED', 'FAILED')),
    approval_request_id uuid,
    signing_proposal_id uuid,
    applied_tx_hash     text        CHECK (applied_tx_hash ~ '^0x[0-9a-f]{64}$'),
    requested_by        text        NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL
);
-- Mỗi series một yêu cầu đang mở
CREATE UNIQUE INDEX series_pause_request_open ON issuance.series_pause_request (series_id)
    WHERE status IN ('PENDING_APPROVAL', 'SIGNING');
