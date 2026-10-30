-- B10b (Q149–Q152): VSDC và Holco ký 2/2 lệnh phong tỏa, giải tỏa, chuyển cưỡng chế.

ALTER TABLE governance.enforcement_order DROP CONSTRAINT enforcement_order_status_check;
ALTER TABLE governance.enforcement_order
    ADD CONSTRAINT enforcement_order_status_check CHECK (status IN ('RECEIVED', 'PENDING_APPROVAL', 'PENDING_SIGNATURES',
                                                                     'APPLIED', 'REJECTED', 'FAILED')),
    ADD COLUMN failure_reason      text CHECK (failure_reason IN ('SERIES_NOT_ENFORCEABLE', 'EXCEEDS_BALANCE',
                                                                  'EXCEEDS_FROZEN', 'RECIPIENT_NOT_VERIFIED')),
    ADD COLUMN signing_proposal_id uuid,
    ADD COLUMN applied_tx_hash     text CHECK (applied_tx_hash ~ '^0x[0-9a-f]{64}$'),
    ADD COLUMN applied_at          timestamptz;

CREATE INDEX enforcement_order_basis_hash ON governance.enforcement_order (basis_hash);
