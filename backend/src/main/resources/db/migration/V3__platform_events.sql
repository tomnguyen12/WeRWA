-- B2: sự kiện giữa mô-đun qua outbox và NATS JetStream (D4; Q1: bảng kỹ thuật ở schema platform).

-- Sổ sự kiện của Spring Modulith 2.1 (outbox) và bảng lưu trữ sự kiện đã phát (Q11: giữ 30 ngày).
CREATE TABLE platform.event_publication (
    id                     uuid                     NOT NULL,
    listener_id            text                     NOT NULL,
    event_type             text                     NOT NULL,
    serialized_event       text                     NOT NULL,
    publication_date       timestamp with time zone NOT NULL,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    integer,
    last_resubmission_date timestamp with time zone,
    PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx ON platform.event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON platform.event_publication (completion_date);

CREATE TABLE platform.event_publication_archive (
    id                     uuid                     NOT NULL,
    listener_id            text                     NOT NULL,
    event_type             text                     NOT NULL,
    serialized_event       text                     NOT NULL,
    publication_date       timestamp with time zone NOT NULL,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    integer,
    last_resubmission_date timestamp with time zone,
    PRIMARY KEY (id)
);
CREATE INDEX event_publication_archive_serialized_event_hash_idx ON platform.event_publication_archive USING hash (serialized_event);
CREATE INDEX event_publication_archive_by_completion_date_idx ON platform.event_publication_archive (completion_date);

-- Dấu sự kiện đã xử lý của từng bên nhận: chống xử lý trùng (bên nhận xử lý lặp lại an toàn).
CREATE TABLE platform.inbox (
    consumer     text        NOT NULL,
    event_id     uuid        NOT NULL,
    event_type   text        NOT NULL,
    processed_at timestamptz NOT NULL,
    PRIMARY KEY (consumer, event_id)
);
CREATE INDEX inbox_processed_at ON platform.inbox (processed_at);

-- Sự kiện xử lý lỗi quá số lần thử (Q10) hoặc không đọc được; vận hành xem và xử lý tay.
CREATE TABLE platform.event_dead_letter (
    id         uuid        PRIMARY KEY,
    consumer   text        NOT NULL,
    event_id   uuid,
    event_type text        NOT NULL,
    payload    text        NOT NULL,
    error      text        NOT NULL,
    deliveries integer     NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX event_dead_letter_created_at ON platform.event_dead_letter (created_at);
