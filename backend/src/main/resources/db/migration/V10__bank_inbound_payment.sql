-- B8a: báo có BIDV, tài khoản ảo, đối chiếu, lô xác nhận (US-3.05, US-5.02; Q49–Q54).

CREATE TABLE integration.inbound_message (
    id                uuid        PRIMARY KEY,
    counterparty      text        NOT NULL CHECK (counterparty IN ('BIDV', 'VSDC', 'TREASURY', 'KYC')),
    message_type      text        NOT NULL,
    idempotency_key   text,
    raw_payload       text        NOT NULL,
    signature         text,
    signature_valid   boolean     NOT NULL,
    received_at       timestamptz NOT NULL,
    processing_status text        NOT NULL CHECK (processing_status IN ('PROCESSED', 'REJECTED')),
    attempts          integer     NOT NULL DEFAULT 1
);
COMMENT ON COLUMN integration.inbound_message.raw_payload IS 'Thân thô đúng như nhận, kể cả thông điệp sai chữ ký (US-5.02-AC2)';
-- Q50: chặn trùng theo mã tham chiếu ngân hàng, chỉ với thông điệp đúng chữ ký
CREATE UNIQUE INDEX inbound_message_dedup ON integration.inbound_message (counterparty, idempotency_key) WHERE signature_valid;

CREATE SEQUENCE integration.virtual_account_seq;
CREATE TABLE integration.virtual_account (
    account_no   text        PRIMARY KEY,
    counterparty text        NOT NULL,
    reference_id uuid        NOT NULL UNIQUE,
    currency     char(3)     NOT NULL,
    created_at   timestamptz NOT NULL
);

-- Q51, Q54: trạng thái phân bổ theo máy trạng thái thiết kế; tài khoản ảo và hạn trả
ALTER TABLE distribution.allocation
    ADD COLUMN status text NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED', 'SIGNED', 'AWAITING_PAYMENT', 'PAID',
        'OVERDUE', 'MINTED', 'CONFIRMED', 'NEEDS_CORRECTION')),
    ADD COLUMN virtual_account text UNIQUE,
    ADD COLUMN payment_due_date date;

CREATE TABLE distribution.payment_batch (
    id                  uuid        PRIMARY KEY,
    offering_id         uuid        NOT NULL REFERENCES distribution.offering (id),
    batch_no            integer     NOT NULL,
    item_count          integer     NOT NULL CHECK (item_count > 0),
    total_amount_minor  bigint      NOT NULL,
    currency            char(3)     NOT NULL,
    merkle_root         text        NOT NULL CHECK (merkle_root ~ '^0x[0-9a-f]{64}$'),
    status              text        NOT NULL CHECK (status IN ('BUILT', 'SIGNING', 'SIGNED')),
    signing_proposal_id uuid,
    chain_tx_id         uuid,
    created_at          timestamptz NOT NULL,
    UNIQUE (offering_id, batch_no)
);

CREATE TABLE distribution.payment_confirmation (
    id                 uuid        PRIMARY KEY,
    bank_ref           text        NOT NULL UNIQUE,
    allocation_id      uuid        REFERENCES distribution.allocation (id),
    virtual_account    text        NOT NULL,
    payer_name         text,
    amount_minor       bigint      NOT NULL,
    currency           char(3)     NOT NULL,
    value_date         date        NOT NULL,
    inbound_message_id uuid        NOT NULL,
    match_result       text        NOT NULL CHECK (match_result IN ('MATCHED', 'SHORT', 'OVER', 'UNKNOWN')),
    match_detail       text,
    payment_batch_id   uuid        REFERENCES distribution.payment_batch (id),
    payment_leaf_hash  text        CHECK (payment_leaf_hash ~ '^0x[0-9a-f]{64}$'),
    created_at         timestamptz NOT NULL,
    -- Chỉ MATCHED mới vào lô (mô hình dữ liệu)
    CHECK (payment_batch_id IS NULL OR match_result = 'MATCHED')
);
-- Mỗi phân bổ chỉ một xác nhận MATCHED (R3: mint một lần)
CREATE UNIQUE INDEX payment_confirmation_one_match ON distribution.payment_confirmation (allocation_id)
    WHERE match_result = 'MATCHED';
