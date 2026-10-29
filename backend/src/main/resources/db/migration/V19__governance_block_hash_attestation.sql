-- B12b: VSDC gửi mã băm khối đọc từ V4 (Q95); R7 so với O1 cùng số khối (Q96). Mỗi khối một lần.
CREATE TABLE governance.block_hash_attestation (
    id              uuid        PRIMARY KEY,
    chain_id        bigint      NOT NULL,
    block_number    bigint      NOT NULL CHECK (block_number >= 0),
    block_hash      text        NOT NULL CHECK (block_hash ~ '^0x[0-9a-f]{64}$'),
    o1_block_hash   text        CHECK (o1_block_hash ~ '^0x[0-9a-f]{64}$'),
    status          text        NOT NULL CHECK (status IN ('MATCHED', 'MISMATCHED', 'PENDING')),
    attested_by     text        NOT NULL,
    attested_by_org uuid        NOT NULL,
    attested_at     timestamptz NOT NULL,
    checked_at      timestamptz,
    reported_at     timestamptz,
    UNIQUE (chain_id, block_number)
);

CREATE INDEX block_hash_attestation_attested_idx ON governance.block_hash_attestation (attested_at);
