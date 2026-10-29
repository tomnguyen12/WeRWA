-- B9c: sổ người sở hữu, sao kê (US-3.07; Q83–Q89). Chỉ bộ chiếu từ sự kiện indexer ghi; dựng lại được từ chuỗi.

-- Q86: wallet_id, wallet_org_id chép từ onboarding lúc ghi (lọc quyền xem không đọc chéo schema); rỗng nếu địa chỉ
-- chưa có ví, điền lại ở lần ghi sau của địa chỉ đó.
-- Q85: mỗi log token thành một hoặc hai dòng biến động; chống trùng theo (chuỗi, khối, log, ví).
CREATE TABLE distribution.holding_movement (
    id                   uuid        PRIMARY KEY,
    chain_id             bigint      NOT NULL,
    series_id            uuid        NOT NULL,
    token_address        text        NOT NULL CHECK (token_address ~ '^0x[0-9a-f]{40}$'),
    wallet_address       text        NOT NULL CHECK (wallet_address ~ '^0x[0-9a-f]{40}$'),
    wallet_id            uuid,
    wallet_org_id        uuid,
    kind                 text        NOT NULL
        CHECK (kind IN ('MINT', 'TRANSFER_IN', 'TRANSFER_OUT', 'BURN', 'FREEZE', 'UNFREEZE')),
    quantity             bigint      NOT NULL CHECK (quantity >= 1),
    counterparty_address text        CHECK (counterparty_address ~ '^0x[0-9a-f]{40}$'),
    block_number         bigint      NOT NULL,
    log_index            integer     NOT NULL,
    tx_hash              text        NOT NULL CHECK (tx_hash ~ '^0x[0-9a-f]{64}$'),
    block_time           timestamptz NOT NULL,
    created_at           timestamptz NOT NULL,
    -- Thứ tự sao kê: khối, log, rồi dòng nhận sau dòng chuyển của cùng log
    position             bigint      GENERATED ALWAYS AS
        (block_number * 1000000 + log_index * 2 + CASE WHEN kind = 'TRANSFER_IN' THEN 1 ELSE 0 END) STORED,
    UNIQUE (chain_id, block_number, log_index, wallet_address)
);

CREATE INDEX holding_movement_wallet_idx ON distribution.holding_movement (wallet_id, block_time);
CREATE INDEX holding_movement_org_idx ON distribution.holding_movement (wallet_org_id, block_time);
CREATE INDEX holding_movement_series_idx ON distribution.holding_movement (series_id, block_time);
CREATE INDEX holding_movement_position_idx ON distribution.holding_movement (position);

-- Bảng đọc số dư theo mô hình dữ liệu (holding_view). Số dư có thể âm tạm thời nếu sự kiện đến sai thứ tự.
CREATE TABLE distribution.holding_view (
    series_id      uuid        NOT NULL,
    wallet_address text        NOT NULL CHECK (wallet_address ~ '^0x[0-9a-f]{40}$'),
    id             uuid        NOT NULL UNIQUE,
    chain_id       bigint      NOT NULL,
    token_address  text        NOT NULL CHECK (token_address ~ '^0x[0-9a-f]{40}$'),
    wallet_id      uuid,
    wallet_org_id  uuid,
    balance        bigint      NOT NULL,
    frozen         bigint      NOT NULL,
    last_block     bigint      NOT NULL,
    updated_at     timestamptz NOT NULL,
    PRIMARY KEY (series_id, wallet_address)
);

CREATE INDEX holding_view_wallet_idx ON distribution.holding_view (wallet_id);
CREATE INDEX holding_view_org_idx ON distribution.holding_view (wallet_org_id);
