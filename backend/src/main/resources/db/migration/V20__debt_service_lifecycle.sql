-- B11c: vòng đời ngoài chuỗi của kỳ trả nợ (máy Kỳ trả nợ cạnh 1, 2, 5, 6, 7, 8, 9; Q98–Q105).

-- Q99: dòng kỳ trả nợ có thể có trước khi tính (nhắc T−15 dùng số dự kiến), nên số phải trả và giờ tính để trống được.
ALTER TABLE issuance.debt_service
    ALTER COLUMN amount_due_minor DROP NOT NULL,
    ALTER COLUMN rounding_residual_minor DROP NOT NULL,
    ALTER COLUMN calculated_at DROP NOT NULL,
    ADD COLUMN estimated_minor    bigint CHECK (estimated_minor >= 0),
    ADD COLUMN reminded_at        timestamptz,
    ADD COLUMN funded_minor       bigint CHECK (funded_minor >= 0),
    ADD COLUMN funding_checked_at timestamptz,
    ADD COLUMN shortfall_minor    bigint CHECK (shortfall_minor >= 0),
    ADD COLUMN grace_until        date,
    ADD COLUMN instruction_ref    text UNIQUE,
    ADD COLUMN instructed_at      timestamptz,
    ADD COLUMN paid_at            timestamptz,
    ADD COLUMN paid_minor         bigint CHECK (paid_minor >= 0),
    ADD COLUMN withheld_minor     bigint CHECK (withheld_minor >= 0),
    ADD COLUMN reconciled_at      timestamptz,
    ADD COLUMN updated_at         timestamptz;
ALTER TABLE issuance.debt_service DROP CONSTRAINT debt_service_status_check;
ALTER TABLE issuance.debt_service ADD CONSTRAINT debt_service_status_check CHECK (status IN
    ('CALCULATED', 'REMINDED', 'FUNDS_CHECKED', 'INSTRUCTED', 'PAID', 'RECONCILED', 'LATE', 'GRACE_EXPIRED'));
COMMENT ON TABLE issuance.debt_service IS 'Nghĩa vụ trả nợ của một kỳ coupon hoặc kỳ gốc (B11a tính, B11c theo dõi tiền và lệnh chi)';

-- Q103: kỳ gốc dùng chung danh sách chốt với kỳ coupon cuối, nên dòng chi trả có loại.
ALTER TABLE distribution.distribution_line
    ADD COLUMN line_kind    text NOT NULL DEFAULT 'COUPON' CHECK (line_kind IN ('COUPON', 'PRINCIPAL')),
    ADD COLUMN payout_status text CHECK (payout_status IN ('INSTRUCTED', 'PAID', 'FAILED')),
    ADD COLUMN bank_ref     text,
    ADD COLUMN paid_minor   bigint CHECK (paid_minor >= 0),
    ADD COLUMN settled_at   timestamptz;
DO $$
DECLARE c text;
BEGIN
    SELECT conname INTO c FROM pg_constraint
    WHERE conrelid = 'distribution.distribution_line'::regclass AND contype = 'u';
    EXECUTE format('ALTER TABLE distribution.distribution_line DROP CONSTRAINT %I', c);
END $$;
ALTER TABLE distribution.distribution_line
    ADD CONSTRAINT distribution_line_unique UNIQUE (record_snapshot_id, line_kind, wallet_id, withheld_reason);

-- Q100: lệnh chi đã gửi BIDV, lưu nguyên văn; mã lệnh là id sự kiện lịch của kỳ (gửi lại an toàn).
CREATE TABLE integration.payout_instruction (
    instruction_ref text        PRIMARY KEY,
    counterparty    text        NOT NULL CHECK (counterparty IN ('BIDV')),
    currency        char(3)     NOT NULL,
    line_count      integer     NOT NULL,
    total_minor     bigint      NOT NULL CHECK (total_minor >= 0),
    payload         jsonb       NOT NULL,
    submitted_at    timestamptz NOT NULL
);

-- Q104: xác nhận chi theo từng dòng, chặn trùng theo (instructionRef, lineRef).
CREATE TABLE integration.payout_confirmation_line (
    instruction_ref text        NOT NULL,
    line_ref        text        NOT NULL,
    status          text        NOT NULL CHECK (status IN ('PAID', 'FAILED')),
    amount_minor    bigint      NOT NULL CHECK (amount_minor >= 0),
    currency        char(3)     NOT NULL,
    bank_ref        text,
    message_id      uuid        NOT NULL,
    received_at     timestamptz NOT NULL,
    PRIMARY KEY (instruction_ref, line_ref)
);
