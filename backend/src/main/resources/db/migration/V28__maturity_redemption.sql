-- B13b (Q164–Q168): đáo hạn, hủy trái phiếu, đóng series.

ALTER TABLE issuance.debt_service ADD COLUMN payout_hash text CHECK (payout_hash ~ '^0x[0-9a-f]{64}$');
ALTER TABLE issuance.series ADD COLUMN mature_chain_tx_id uuid;

-- Mỗi lô burnRedeemed là một giao dịch
CREATE TABLE distribution.redemption_batch (
    id          uuid        PRIMARY KEY,
    series_id   uuid        NOT NULL,
    chain_tx_id uuid        NOT NULL UNIQUE,
    wallets     text[]      NOT NULL,
    status      text        NOT NULL CHECK (status IN ('SUBMITTED', 'MINED', 'FAILED')),
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);
CREATE INDEX redemption_batch_open ON distribution.redemption_batch (series_id) WHERE status = 'SUBMITTED';

-- Q167: phần đã hủy và phần còn phong tỏa theo sự kiện Redeemed
CREATE TABLE distribution.redemption (
    id               uuid        PRIMARY KEY,
    series_id        uuid        NOT NULL,
    wallet_address   text        NOT NULL CHECK (wallet_address ~ '^0x[0-9a-f]{40}$'),
    burned           bigint      NOT NULL CHECK (burned >= 0),
    frozen_remaining bigint      NOT NULL CHECK (frozen_remaining >= 0),
    frozen_burned    bigint      NOT NULL DEFAULT 0 CHECK (frozen_burned >= 0),
    block_number     bigint      NOT NULL,
    tx_hash          text        NOT NULL CHECK (tx_hash ~ '^0x[0-9a-f]{64}$'),
    created_at       timestamptz NOT NULL,
    UNIQUE (tx_hash, wallet_address)
);
CREATE INDEX redemption_series ON distribution.redemption (series_id, block_number);

-- Đóng series: chỉ gửi close một lần mỗi series
CREATE TABLE distribution.series_closure (
    series_id   uuid        PRIMARY KEY,
    chain_tx_id uuid,
    status      text        NOT NULL CHECK (status IN ('SUBMITTED', 'FAILED', 'FROZEN_REMAINING')),
    updated_at  timestamptz NOT NULL
);
