-- B11a: nghĩa vụ trả nợ của kỳ, danh sách chốt, dòng chi trả (US-2.09; Q68–Q72).

CREATE TABLE issuance.debt_service (
    id                       uuid        PRIMARY KEY,
    series_event_id          uuid        NOT NULL UNIQUE REFERENCES issuance.series_event (id),
    amount_due_minor         bigint      NOT NULL CHECK (amount_due_minor >= 0),
    rounding_residual_minor  bigint      NOT NULL CHECK (rounding_residual_minor >= 0),
    currency                 char(3)     NOT NULL,
    status                   text        NOT NULL CHECK (status IN ('CALCULATED')),
    calculated_at            timestamptz NOT NULL
);
COMMENT ON TABLE issuance.debt_service IS 'Các cột theo dõi tiền (funded, shortfall, grace) thêm ở B11b';

-- Danh sách chốt: chụp từ chuỗi ở B11b; B11a dùng để tính dòng chi trả
CREATE TABLE distribution.record_snapshot (
    id                    uuid        PRIMARY KEY,
    series_id             uuid        NOT NULL,
    period_no             integer     NOT NULL CHECK (period_no >= 1),
    record_block          bigint      NOT NULL,
    register_hash         text        NOT NULL CHECK (register_hash ~ '^0x[0-9a-f]{64}$'),
    holder_count          integer     NOT NULL,
    total_quantity        bigint      NOT NULL,
    registrar_attested_at timestamptz,
    calculated_at         timestamptz,
    created_at            timestamptz NOT NULL,
    UNIQUE (series_id, period_no)
);

CREATE TABLE distribution.snapshot_holding (
    record_snapshot_id uuid   NOT NULL REFERENCES distribution.record_snapshot (id),
    wallet_id          uuid   NOT NULL,
    investor_org_id    uuid   NOT NULL,
    quantity           bigint NOT NULL CHECK (quantity > 0),
    frozen_quantity    bigint NOT NULL DEFAULT 0 CHECK (frozen_quantity >= 0 AND frozen_quantity <= quantity),
    PRIMARY KEY (record_snapshot_id, wallet_id)
);

CREATE TABLE distribution.distribution_line (
    id                      uuid    PRIMARY KEY,
    record_snapshot_id      uuid    NOT NULL REFERENCES distribution.record_snapshot (id),
    wallet_id               uuid    NOT NULL,
    investor_org_id         uuid    NOT NULL,
    quantity                bigint  NOT NULL CHECK (quantity > 0),
    gross_minor             bigint  NOT NULL CHECK (gross_minor >= 0),
    tax_minor               bigint  NOT NULL CHECK (tax_minor >= 0),
    net_minor               bigint  NOT NULL CHECK (net_minor = gross_minor - tax_minor),
    currency                char(3) NOT NULL,
    withheld_reason         text    CHECK (withheld_reason IN ('FROZEN')),
    payment_instruction_ref text,
    UNIQUE (record_snapshot_id, wallet_id, withheld_reason)
);
