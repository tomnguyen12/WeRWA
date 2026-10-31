-- B7a: đợt chào bán, sổ lệnh, danh sách phân bổ (US-3.01, 3.02, 3.03); ví (Q41); duyệt khác tổ chức (Q40).

-- Q40: yêu cầu duyệt có thể chỉ định vai người duyệt (khác tổ chức người lập), ví dụ ISSUER cho danh sách phân bổ.
ALTER TABLE governance.approval_request ADD COLUMN checker_role text;

-- Q41: ví theo mô hình dữ liệu. Luồng đăng ký lên sổ định danh (registered_tx_id) làm ở B5b.
CREATE TABLE onboarding.wallet (
    id                 uuid        PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES onboarding.organization (id),
    chain_id           bigint      NOT NULL,
    address            text        NOT NULL CHECK (address ~ '^0x[0-9a-f]{40}$'),
    wallet_type        text        NOT NULL CHECK (wallet_type IN ('SELF', 'OMNIBUS')),
    onchain_id_address text        CHECK (onchain_id_address ~ '^0x[0-9a-f]{40}$'),
    registered_tx_id   uuid,
    status             text        NOT NULL CHECK (status IN ('PENDING', 'REGISTERED', 'REVOKED')),
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    UNIQUE (chain_id, address)
);

CREATE TABLE distribution.offering (
    id                       uuid        PRIMARY KEY,
    series_id                uuid        NOT NULL,
    method                   text        NOT NULL CHECK (method IN ('BOOKBUILDING')),
    rate_guidance_low_ppm    integer     NOT NULL CHECK (rate_guidance_low_ppm >= 0),
    rate_guidance_high_ppm   integer     NOT NULL CHECK (rate_guidance_high_ppm >= rate_guidance_low_ppm),
    target_quantity          bigint      NOT NULL CHECK (target_quantity > 0),
    book_open_at             timestamptz NOT NULL,
    book_close_at            timestamptz NOT NULL CHECK (book_close_at > book_open_at),
    announcement_document_id uuid,
    status                   text        NOT NULL CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED', 'ALLOCATED', 'CANCELLED')),
    owner_org_id             uuid        NOT NULL,
    version                  integer     NOT NULL DEFAULT 1,
    created_by               text        NOT NULL,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);
-- Một series chỉ có một đợt chào bán còn hiệu lực
CREATE UNIQUE INDEX offering_one_active_per_series ON distribution.offering (series_id) WHERE status <> 'CANCELLED';

CREATE TABLE distribution."order" (
    id                     uuid        PRIMARY KEY,
    offering_id            uuid        NOT NULL REFERENCES distribution.offering (id),
    investor_org_id        uuid        NOT NULL,
    wallet_id              uuid        NOT NULL,
    rate_ppm               integer     NOT NULL CHECK (rate_ppm >= 0),
    quantity               bigint      NOT NULL CHECK (quantity > 0),
    entered_by_user_id     text        NOT NULL,
    entered_by_org_id      uuid        NOT NULL,
    eligibility_checked_at timestamptz NOT NULL,
    status                 text        NOT NULL CHECK (status IN ('PLACED', 'AMENDED', 'CANCELLED', 'ALLOCATED',
                                                                  'PARTIALLY_ALLOCATED', 'NOT_ALLOCATED')),
    version                integer     NOT NULL DEFAULT 1,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL
);
CREATE INDEX order_offering ON distribution."order" (offering_id, id);

-- Chỉ ghi thêm: mỗi lần đặt, sửa, hủy là một revision
CREATE TABLE distribution.order_revision (
    id            uuid        PRIMARY KEY,
    order_id      uuid        NOT NULL REFERENCES distribution."order" (id),
    revision_no   integer     NOT NULL,
    rate_ppm      integer     NOT NULL,
    quantity      bigint      NOT NULL,
    action        text        NOT NULL CHECK (action IN ('PLACE', 'AMEND', 'CANCEL')),
    actor_user_id text        NOT NULL,
    actor_org_id  uuid        NOT NULL,
    created_at    timestamptz NOT NULL,
    UNIQUE (order_id, revision_no)
);

CREATE TABLE distribution.allocation_list (
    id                     uuid        PRIMARY KEY,
    offering_id            uuid        NOT NULL REFERENCES distribution.offering (id),
    list_version           integer     NOT NULL,
    coupon_rate_ppm        integer     NOT NULL CHECK (coupon_rate_ppm >= 0),
    period_rates_ppm       integer[]   NOT NULL,
    total_quantity         bigint      NOT NULL,
    merkle_root            text        NOT NULL CHECK (merkle_root ~ '^0x[0-9a-f]{64}$'),
    list_hash              text        CHECK (list_hash ~ '^0x[0-9a-f]{64}$'),
    issuer_decision_id     uuid,
    issuer_decision_hash   text,
    approval_request_id    uuid,
    approved_at            timestamptz,
    signing_proposal_id    uuid,
    status                 text        NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'SIGNED', 'REJECTED', 'SUPERSEDED')),
    created_by             text        NOT NULL,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    UNIQUE (offering_id, list_version)
);
-- Một đợt chào bán chỉ có một danh sách đã ký
CREATE UNIQUE INDEX allocation_list_one_signed ON distribution.allocation_list (offering_id) WHERE status = 'SIGNED';

CREATE TABLE distribution.allocation (
    id                 uuid    PRIMARY KEY,
    allocation_list_id uuid    NOT NULL REFERENCES distribution.allocation_list (id),
    order_id           uuid    NOT NULL REFERENCES distribution."order" (id),
    investor_org_id    uuid    NOT NULL,
    wallet_id          uuid    NOT NULL,
    wallet_address     text    NOT NULL CHECK (wallet_address ~ '^0x[0-9a-f]{40}$'),
    quantity           bigint  NOT NULL CHECK (quantity > 0),
    amount_due_minor   bigint  NOT NULL CHECK (amount_due_minor > 0),
    currency           char(3) NOT NULL,
    adjustment_reason  text,
    leaf_hash          text    NOT NULL CHECK (leaf_hash ~ '^0x[0-9a-f]{64}$'),
    UNIQUE (allocation_list_id, order_id)
);
