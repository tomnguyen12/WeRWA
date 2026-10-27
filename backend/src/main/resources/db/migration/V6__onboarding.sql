-- B5a: Onboarding ngoài chuỗi (mô hình dữ liệu 02, phân hệ 1; US-1.01–1.03; Q25, Q26)
-- và nhật ký thông điệp của mô-đun Tích hợp (US-5.04-AC3: lưu nguyên văn).

CREATE TABLE onboarding.organization (
    id               uuid        PRIMARY KEY,
    legal_name       text        NOT NULL,
    registration_no  text        NOT NULL,
    country          text        NOT NULL CHECK (country ~ '^[A-Z]{2}$'),
    roles            text[]      NOT NULL CHECK (cardinality(roles) > 0),
    kyb_status       text        NOT NULL CHECK (kyb_status IN ('DRAFT', 'PENDING_REVIEW', 'KYB_APPROVED', 'KYB_REJECTED')),
    kyb_document_ids uuid[]      NOT NULL DEFAULT '{}',
    kyb_approval_id  uuid,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    UNIQUE (country, registration_no)
);

-- Người ký được ủy quyền (US-1.01-AC3): địa chỉ ký duy nhất trên toàn nền tảng.
CREATE TABLE onboarding.authorized_signer (
    id                   uuid        PRIMARY KEY,
    organization_id      uuid        NOT NULL REFERENCES onboarding.organization (id),
    user_id              uuid        NOT NULL,
    signing_address      text        NOT NULL UNIQUE CHECK (signing_address ~ '^0x[0-9a-f]{40}$'),
    scopes               text[]      NOT NULL,
    valid_from           date        NOT NULL,
    valid_to             date,
    authorization_doc_id uuid        NOT NULL,
    created_at           timestamptz NOT NULL,
    CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

-- Danh mục loại nhà đầu tư (Q25): mã uint16 dùng trong claim INVESTOR_CLASS và tham số series.
CREATE TABLE onboarding.investor_class (
    code    smallint PRIMARY KEY CHECK (code > 0),
    key     text     NOT NULL UNIQUE,
    name_vi text     NOT NULL
);
INSERT INTO onboarding.investor_class (code, key, name_vi) VALUES
    (1, 'FOREIGN_INSTITUTION', 'Tổ chức nước ngoài'),
    (2, 'VIFC_MEMBER', 'Thành viên VIFC');

CREATE TABLE onboarding.investor_profile (
    organization_id uuid        PRIMARY KEY REFERENCES onboarding.organization (id),
    investor_class  text        REFERENCES onboarding.investor_class (key),
    updated_at      timestamptz NOT NULL
);

-- Hồ sơ KYC nhà đầu tư tổ chức (US-1.02): kết quả sàng lọc lưu nguyên văn.
CREATE TABLE onboarding.kyc_case (
    id               uuid        PRIMARY KEY,
    organization_id  uuid        NOT NULL REFERENCES onboarding.organization (id),
    status           text        NOT NULL CHECK (status IN ('OPEN', 'NEEDS_REVIEW', 'APPROVED', 'REJECTED', 'EXPIRED')),
    document_ids     uuid[]      NOT NULL DEFAULT '{}',
    provider         text        NOT NULL,
    provider_ref     text        NOT NULL,
    screening_result jsonb       NOT NULL,
    sanctions_hit    boolean     NOT NULL,
    pep_hit          boolean     NOT NULL,
    opened_by        text        NOT NULL,
    opened_by_org_id uuid        NOT NULL,
    investor_class   text        REFERENCES onboarding.investor_class (key),
    decision_reason  text,
    decided_by       text,
    decided_at       timestamptz,
    expires_at       timestamptz,
    claim_tx_id      uuid,
    created_at       timestamptz NOT NULL,
    CHECK (decided_by IS NULL OR decided_by <> opened_by)
);
CREATE INDEX kyc_case_organization ON onboarding.kyc_case (organization_id, created_at);

-- Thông điệp trao đổi với bên ngoài (mô hình dữ liệu 02, phân hệ 5), lưu nguyên văn.
CREATE TABLE integration.outbound_message (
    id              uuid        PRIMARY KEY,
    counterparty    text        NOT NULL CHECK (counterparty IN ('BIDV', 'VSDC', 'TREASURY', 'KYC')),
    message_type    text        NOT NULL,
    idempotency_key text        NOT NULL,
    raw_payload     jsonb       NOT NULL,
    raw_response    jsonb,
    status          text        NOT NULL CHECK (status IN ('SENT', 'SUCCEEDED', 'FAILED')),
    created_at      timestamptz NOT NULL,
    completed_at    timestamptz,
    UNIQUE (counterparty, idempotency_key)
);
