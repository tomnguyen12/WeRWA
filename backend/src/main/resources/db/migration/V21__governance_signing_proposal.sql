-- B4b: đề nghị ký multisig và chữ ký (US-6.04; Q106–Q113). Owner, ngưỡng, nonce của Safe đọc trên chuỗi khi đề nghị
-- bắt đầu thu chữ ký; parties ghi lại ngưỡng của từng Safe tổ chức tại lúc đó.
CREATE TABLE governance.signing_proposal (
    id                  uuid        PRIMARY KEY,
    purpose             text        NOT NULL,
    subject_ref         text        NOT NULL,
    approval_request_id uuid,
    safe_address        text        NOT NULL CHECK (safe_address ~ '^0x[0-9a-f]{40}$'),
    target_contract     text        NOT NULL CHECK (target_contract ~ '^0x[0-9a-f]{40}$'),
    function_name       text        NOT NULL,
    calldata            text        NOT NULL CHECK (calldata ~ '^0x([0-9a-f]{2})*$'),
    calldata_hash       text        NOT NULL CHECK (calldata_hash ~ '^0x[0-9a-f]{64}$'),
    nonce               bigint,
    safe_tx_hash        text        CHECK (safe_tx_hash ~ '^0x[0-9a-f]{64}$'),
    threshold           integer,
    parties             jsonb,
    status              text        NOT NULL CHECK (status IN ('QUEUED', 'COLLECTING', 'THRESHOLD_REACHED', 'SENT', 'MINED',
        'FAILED', 'CANCELLED')),
    chain_tx_id         uuid,
    tx_hash             text,
    failure_reason      text,
    created_by          text        NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CHECK (status = 'QUEUED' OR status = 'CANCELLED' OR nonce IS NOT NULL)
);

-- Q110: mỗi Safe thực hiện chỉ một đề nghị đang thu chữ ký hoặc đang gửi
CREATE UNIQUE INDEX signing_proposal_one_active ON governance.signing_proposal (safe_address)
    WHERE status IN ('COLLECTING', 'THRESHOLD_REACHED', 'SENT');
CREATE INDEX signing_proposal_chain_tx ON governance.signing_proposal (chain_tx_id);

CREATE TABLE governance.signing_signature (
    proposal_id  uuid        NOT NULL REFERENCES governance.signing_proposal (id),
    signer       text        NOT NULL CHECK (signer ~ '^0x[0-9a-f]{40}$'),
    party_safe   text        NOT NULL CHECK (party_safe ~ '^0x[0-9a-f]{40}$'),
    signature    text        NOT NULL CHECK (signature ~ '^0x[0-9a-f]{130}$'),
    submitted_by text        NOT NULL,
    submitted_at timestamptz NOT NULL,
    PRIMARY KEY (proposal_id, signer)
);
