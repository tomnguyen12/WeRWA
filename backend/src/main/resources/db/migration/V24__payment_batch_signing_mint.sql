-- B8b (Q129–Q135): BIDV ký gốc lô xác nhận tiền; bộ điều phối mint từng phân bổ.

ALTER TABLE distribution.payment_batch
    ADD COLUMN batch_hash     text CHECK (batch_hash ~ '^0x[0-9a-f]{64}$'),
    ADD COLUMN signed_tx_hash text CHECK (signed_tx_hash ~ '^0x[0-9a-f]{64}$'),
    ADD COLUMN signed_at      timestamptz;

ALTER TABLE distribution.allocation
    ADD COLUMN mint_chain_tx_id    uuid,
    ADD COLUMN minted_tx_hash      text CHECK (minted_tx_hash ~ '^0x[0-9a-f]{64}$'),
    ADD COLUMN minted_block        bigint,
    ADD COLUMN minted_at           timestamptz,
    ADD COLUMN mint_failure_reason text;

CREATE INDEX allocation_mint_tx ON distribution.allocation (mint_chain_tx_id) WHERE mint_chain_tx_id IS NOT NULL;
CREATE INDEX payment_batch_root ON distribution.payment_batch (merkle_root);
