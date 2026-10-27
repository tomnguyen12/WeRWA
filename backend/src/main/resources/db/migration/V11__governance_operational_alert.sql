-- B12a: cảnh báo vận hành (I5, US-6.01-AC3; Q56–Q59).

CREATE TABLE governance.operational_alert (
    id              uuid        PRIMARY KEY,
    fingerprint     text        NOT NULL,
    name            text        NOT NULL,
    severity        text,
    summary         text,
    labels          jsonb       NOT NULL,
    annotations     jsonb       NOT NULL,
    status          text        NOT NULL CHECK (status IN ('FIRING', 'ACKNOWLEDGED', 'RESOLVED')),
    source          text        NOT NULL,
    starts_at       timestamptz NOT NULL,
    ends_at         timestamptz,
    acknowledged_by text,
    acknowledged_at timestamptz,
    received_at     timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    UNIQUE (fingerprint, starts_at)
);
CREATE INDEX operational_alert_status ON governance.operational_alert (status, id);
