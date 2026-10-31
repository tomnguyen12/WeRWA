-- B6b: Đề án, sổ hạn mức, series, lịch sự kiện, quyết định UBND (US-2.01, 2.02, 2.05, 2.06); lịch ngày làm việc (Q32).

-- Bảng điều chỉnh lịch ngày làm việc (4.11; Q32). Ngày nghỉ theo luật tính trong ứng dụng; bảng này chỉ ghi ngoại lệ.
CREATE TABLE governance.holiday_calendar (
    calendar_code text        NOT NULL CHECK (calendar_code IN ('VN', 'NY')),
    day           date        NOT NULL,
    kind          text        NOT NULL CHECK (kind IN ('HOLIDAY', 'WORKING_DAY')),
    name          text        NOT NULL,
    created_by    text        NOT NULL,
    created_at    timestamptz NOT NULL,
    PRIMARY KEY (calendar_code, day)
);

CREATE TABLE issuance.issuance_program (
    id                        uuid        PRIMARY KEY,
    root_program_id           uuid        NOT NULL REFERENCES issuance.issuance_program (id),
    amends_program_id         uuid        REFERENCES issuance.issuance_program (id),
    title                     text        NOT NULL,
    hdnd_resolution_no        text        NOT NULL,
    approved_on               date        NOT NULL,
    currency                  char(3)     NOT NULL,
    max_principal_minor       bigint      NOT NULL CHECK (max_principal_minor > 0),
    rate_floor_ppm            integer     NOT NULL CHECK (rate_floor_ppm >= 0),
    rate_cap_ppm              integer     NOT NULL CHECK (rate_cap_ppm >= rate_floor_ppm),
    cost_cap_minor            bigint      NOT NULL CHECK (cost_cap_minor >= 0),
    tenor_range               text,
    eligible_investor_classes text[]      NOT NULL,
    issuance_methods          text[]      NOT NULL DEFAULT '{}',
    document_id               uuid        NOT NULL REFERENCES issuance.document (id),
    status                    text        NOT NULL CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'EFFECTIVE', 'SUPERSEDED')),
    approval_request_id       uuid,
    owner_org_id              uuid        NOT NULL,
    version                   integer     NOT NULL DEFAULT 1,
    created_by                text        NOT NULL,
    created_at                timestamptz NOT NULL,
    updated_at                timestamptz NOT NULL
);
-- Một chuỗi Đề án chỉ có một bản hiệu lực (Q37)
CREATE UNIQUE INDEX issuance_program_one_effective ON issuance.issuance_program (root_program_id) WHERE status = 'EFFECTIVE';

CREATE TABLE issuance.series (
    id                  uuid        PRIMARY KEY,
    program_id          uuid        NOT NULL REFERENCES issuance.issuance_program (id),
    series_code         text        NOT NULL UNIQUE,
    isin                text        UNIQUE,
    currency            char(3)     NOT NULL,
    face_value_minor    bigint      NOT NULL CHECK (face_value_minor > 0),
    issue_price_ppm     integer     NOT NULL CHECK (issue_price_ppm > 0),
    max_quantity        bigint      NOT NULL CHECK (max_quantity > 0),
    coupon_type         text        NOT NULL DEFAULT 'FIXED' CHECK (coupon_type IN ('FIXED')),
    coupon_frequency    text        NOT NULL CHECK (coupon_frequency IN ('ANNUAL', 'SEMI_ANNUAL', 'QUARTERLY')),
    day_count           text        NOT NULL CHECK (day_count IN ('30_360', 'ACT_ACT')),
    issue_date          date        NOT NULL,
    maturity_date       date        NOT NULL CHECK (maturity_date > issue_date),
    calendars           text[]      NOT NULL,
    grace_business_days integer     NOT NULL DEFAULT 0 CHECK (grace_business_days >= 0),
    withholding_tax_ppm integer     NOT NULL DEFAULT 0 CHECK (withholding_tax_ppm >= 0),
    holding_limit       bigint      CHECK (holding_limit > 0),
    investor_classes    text[]      NOT NULL,
    terms_document_id   uuid        NOT NULL REFERENCES issuance.document (id),
    document_ids        uuid[]      NOT NULL DEFAULT '{}',
    terms_hash          text        NOT NULL CHECK (terms_hash ~ '^0x[0-9a-f]{64}$'),
    status              text        NOT NULL,
    approval_request_id uuid,
    signing_proposal_id uuid,
    chain_id            bigint,
    controller_address  text,
    token_address       text,
    owner_org_id        uuid        NOT NULL,
    version             integer     NOT NULL DEFAULT 1,
    created_by          text        NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL
);

-- Sổ hạn mức (US-2.02): chỉ ghi thêm; số dư là tổng bút toán.
CREATE TABLE issuance.limit_ledger_entry (
    id              uuid        PRIMARY KEY,
    program_id      uuid        NOT NULL REFERENCES issuance.issuance_program (id),
    series_id       uuid        NOT NULL REFERENCES issuance.series (id),
    entry_type      text        NOT NULL CHECK (entry_type IN ('RESERVE', 'USE', 'RELEASE')),
    principal_minor bigint      NOT NULL CHECK (principal_minor > 0),
    created_by      text        NOT NULL,
    created_at      timestamptz NOT NULL
);
CREATE INDEX limit_ledger_entry_program ON issuance.limit_ledger_entry (program_id);

CREATE FUNCTION issuance.limit_ledger_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'issuance.limit_ledger_entry chỉ ghi thêm';
END $$;
CREATE TRIGGER limit_ledger_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON issuance.limit_ledger_entry
    FOR EACH STATEMENT EXECUTE FUNCTION issuance.limit_ledger_append_only();

-- Lịch sự kiện (US-2.05-AC2). Lãi suất ghi theo kỳ khi UBND ký danh sách phân bổ (B7).
CREATE TABLE issuance.series_event (
    id           uuid    PRIMARY KEY,
    series_id    uuid    NOT NULL REFERENCES issuance.series (id),
    event_type   text    NOT NULL CHECK (event_type IN ('COUPON', 'MATURITY')),
    period_no    integer NOT NULL CHECK (period_no >= 1),
    period_start date,
    period_end   date,
    record_date  date    NOT NULL,
    payment_date date    NOT NULL CHECK (record_date < payment_date),
    rate_ppm     integer CHECK (rate_ppm >= 0),
    rate_source  text    CHECK (rate_source IN ('FIXED_AT_PRICING')),
    UNIQUE (series_id, event_type, period_no)
);

CREATE TABLE issuance.issuer_decision (
    id            uuid        PRIMARY KEY,
    decision_no   text        NOT NULL UNIQUE,
    decided_on    date        NOT NULL,
    subject       text        NOT NULL CHECK (subject IN ('SERIES_TERMS', 'COUPON_RATE', 'ALLOCATION')),
    series_id     uuid        REFERENCES issuance.series (id),
    offering_id   uuid,
    document_id   uuid        NOT NULL REFERENCES issuance.document (id),
    decision_hash text        NOT NULL CHECK (decision_hash ~ '^0x[0-9a-f]{64}$'),
    owner_org_id  uuid        NOT NULL,
    created_by    text        NOT NULL,
    created_at    timestamptz NOT NULL,
    CHECK (series_id IS NOT NULL OR offering_id IS NOT NULL)
);
COMMENT ON COLUMN issuance.issuer_decision.decision_hash IS 'SHA-256 của JSON chuẩn hóa: số, ngày, đối tượng, SHA-256 tài liệu (US-2.06-AC3)';
