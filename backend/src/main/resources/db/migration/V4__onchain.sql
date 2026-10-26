-- B3: mô-đun Onchain (mô hình dữ liệu 02, phân hệ 6; ADR-12). Mọi bảng có chain_id để chuyển sang chain riêng ở GĐ2.

-- Hợp đồng nền tảng biết: dùng chung (nạp từ werwa.contracts.*) và theo từng series (từ sự kiện SeriesCreated).
CREATE TABLE onchain.contract_registry (
    id            uuid        PRIMARY KEY,
    chain_id      bigint      NOT NULL,
    contract_kind text        NOT NULL,
    address       text        NOT NULL CHECK (address ~ '^0x[0-9a-f]{40}$'),
    abi_name      text,
    series_key    text,
    series_id     uuid,
    abi_version   text,
    active        boolean     NOT NULL DEFAULT true,
    registered_at timestamptz NOT NULL,
    UNIQUE (chain_id, address)
);
COMMENT ON COLUMN onchain.contract_registry.series_key IS 'seriesId bytes32 trên chuỗi; series_id là khóa của mô-đun Phát hành, gắn sau';

-- Giao dịch ứng dụng gửi lên chuỗi. Raw tx lưu trước khi gửi, nên gửi lại an toàn (Q14).
CREATE TABLE onchain.chain_tx (
    id             uuid        PRIMARY KEY,
    chain_id       bigint      NOT NULL,
    from_address   text        NOT NULL,
    nonce          bigint      NOT NULL,
    purpose        text        NOT NULL,
    subject_ref    text        NOT NULL,
    to_address     text        NOT NULL,
    raw_tx         text        NOT NULL,
    tx_hash        text        NOT NULL,
    status         text        NOT NULL CHECK (status IN ('BUILT', 'SENT', 'MINED', 'FAILED')),
    block_number   bigint,
    failure_reason text,
    send_attempts  integer     NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL,
    sent_at        timestamptz,
    finished_at    timestamptz,
    UNIQUE (chain_id, from_address, nonce),
    UNIQUE (chain_id, tx_hash)
);
CREATE INDEX chain_tx_open ON onchain.chain_tx (status) WHERE status IN ('BUILT', 'SENT');

-- Log của hợp đồng trong registry, giải mã theo ABI (ADR-12, Q17).
CREATE TABLE onchain.indexed_event (
    id               uuid        PRIMARY KEY,
    chain_id         bigint      NOT NULL,
    block_number     bigint      NOT NULL,
    log_index        integer     NOT NULL,
    tx_hash          text        NOT NULL,
    contract_address text        NOT NULL,
    event_name       text        NOT NULL,
    decoded          jsonb       NOT NULL,
    indexed_at       timestamptz NOT NULL,
    UNIQUE (chain_id, block_number, log_index)
);
CREATE INDEX indexed_event_contract ON onchain.indexed_event (chain_id, contract_address, event_name);
CREATE INDEX indexed_event_tx ON onchain.indexed_event (chain_id, tx_hash);

-- Khối cuối indexer đã đọc xong. QBFT chốt khối ngay nên không cần xử lý đảo khối.
CREATE TABLE onchain.indexer_cursor (
    chain_id   bigint      PRIMARY KEY,
    last_block bigint      NOT NULL,
    updated_at timestamptz NOT NULL
);
