-- B4c: đối soát hằng ngày, chênh lệch cần người đóng (US-4.09; Q73–Q76).

CREATE TABLE governance.reconciliation_run (
    id          uuid        PRIMARY KEY,
    run_date    date        NOT NULL,
    scope       text        NOT NULL CHECK (scope IN ('CHAIN_VS_VIEW', 'CHAIN_VS_BANK', 'BLOCK_HASH_VSDC', 'LIMITS',
                                                      'AUDIT_CHAIN')),
    break_count integer     NOT NULL,
    started_at  timestamptz NOT NULL,
    finished_at timestamptz NOT NULL
);
CREATE INDEX reconciliation_run_date ON governance.reconciliation_run (run_date, scope);

CREATE TABLE governance.reconciliation_break (
    id          uuid        PRIMARY KEY,
    run_id      uuid        NOT NULL REFERENCES governance.reconciliation_run (id),
    rule        text        NOT NULL CHECK (rule ~ '^R[1-8]$'),
    kind        text        NOT NULL,
    subject_ref text        NOT NULL,
    expected    text,
    actual      text,
    resolved_by text,
    resolved_at timestamptz,
    resolution  text,
    CHECK ((resolved_at IS NULL) = (resolution IS NULL))
);
CREATE INDEX reconciliation_break_open ON governance.reconciliation_break (run_id) WHERE resolved_at IS NULL;
