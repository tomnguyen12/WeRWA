-- B12a: tham số có duyệt, điều chỉnh lịch có duyệt, thông báo (US-4.11; Q60–Q63).

CREATE TABLE governance.parameter_change (
    id                  uuid        PRIMARY KEY,
    key                 text        NOT NULL,
    value               jsonb       NOT NULL,
    effective_from      date        NOT NULL,
    reason              text        NOT NULL,
    status              text        NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED')),
    approval_request_id uuid,
    created_by          text        NOT NULL,
    created_at          timestamptz NOT NULL,
    decided_at          timestamptz
);
CREATE INDEX parameter_change_key ON governance.parameter_change (key, effective_from) WHERE status = 'APPROVED';

CREATE TABLE governance.calendar_adjustment_request (
    id                  uuid        PRIMARY KEY,
    calendar_code       text        NOT NULL CHECK (calendar_code IN ('VN', 'NY')),
    day                 date        NOT NULL,
    kind                text        NOT NULL CHECK (kind IN ('HOLIDAY', 'WORKING_DAY')),
    name                text        NOT NULL,
    status              text        NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED')),
    approval_request_id uuid,
    created_by          text        NOT NULL,
    created_at          timestamptz NOT NULL
);

CREATE TABLE governance.notification (
    id               uuid        PRIMARY KEY,
    kind             text        NOT NULL,
    severity         text        NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    title            text        NOT NULL,
    body             text,
    subject_ref      text,
    recipient_roles  text[]      NOT NULL,
    recipient_org_id uuid,
    email_status     text        NOT NULL CHECK (email_status IN ('PENDING', 'SENT', 'NO_RECIPIENT', 'FAILED')),
    email_attempts   integer     NOT NULL DEFAULT 0,
    email_sent_at    timestamptz,
    created_at       timestamptz NOT NULL
);
COMMENT ON COLUMN governance.notification.recipient_roles IS 'Rỗng là mọi vai; recipient_org_id NULL là mọi tổ chức có vai đó';
CREATE INDEX notification_email_pending ON governance.notification (created_at) WHERE email_status = 'PENDING';

CREATE TABLE governance.notification_read (
    notification_id uuid        NOT NULL REFERENCES governance.notification (id),
    user_subject    text        NOT NULL,
    read_at         timestamptz NOT NULL,
    PRIMARY KEY (notification_id, user_subject)
);

ALTER TABLE onboarding.organization ADD COLUMN notification_email text;
