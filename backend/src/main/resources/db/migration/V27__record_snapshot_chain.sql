-- B11b (Q156–Q159): chụp danh sách chốt lên chuỗi, VSDC xác nhận.

ALTER TABLE distribution.record_snapshot
    ADD COLUMN status              text NOT NULL DEFAULT 'TAKEN'
        CHECK (status IN ('BUILT', 'SUBMITTED', 'TAKEN', 'ATTESTING', 'ATTESTED', 'FAILED')),
    ADD COLUMN series_controller   text CHECK (series_controller ~ '^0x[0-9a-f]{40}$'),
    ADD COLUMN take_chain_tx_id    uuid,
    ADD COLUMN taken_tx_hash       text CHECK (taken_tx_hash ~ '^0x[0-9a-f]{64}$'),
    ADD COLUMN signing_proposal_id uuid,
    ADD COLUMN attest_tx_hash      text CHECK (attest_tx_hash ~ '^0x[0-9a-f]{64}$'),
    ADD COLUMN failure_reason      text,
    ADD COLUMN overdue_alerted_at  timestamptz;
-- Bản chụp ghi trước B11b (nạp tay ở kiểm thử B11a, B11c): mặc định TAKEN; đã có xác nhận thì ATTESTED.
-- Bộ chụp của B11b luôn ghi trạng thái tường minh.
UPDATE distribution.record_snapshot SET status = 'ATTESTED' WHERE registrar_attested_at IS NOT NULL;
CREATE INDEX record_snapshot_take_tx ON distribution.record_snapshot (take_chain_tx_id) WHERE take_chain_tx_id IS NOT NULL;
