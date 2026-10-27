-- B6a: kho tài liệu có phiên bản (US-2.04; Q29, Q30). Nội dung nằm ở kho S3, bảng giữ siêu dữ liệu và SHA-256.
-- Không ghi đè: phiên bản mới là bản ghi mới trỏ về bản trước (supersedes_id).

CREATE TABLE issuance.document (
    id            uuid        PRIMARY KEY,
    doc_type      text        NOT NULL,
    title         text        NOT NULL,
    version_no    integer     NOT NULL CHECK (version_no >= 1),
    storage_key   text        NOT NULL UNIQUE,
    sha256        text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes    bigint      NOT NULL,
    content_type  text        NOT NULL,
    supersedes_id uuid        UNIQUE REFERENCES issuance.document (id),
    visibility    text        NOT NULL CHECK (visibility IN ('INTERNAL', 'INVESTOR', 'PUBLIC')),
    uploaded_by   text        NOT NULL,
    owner_org_id  uuid        NOT NULL,
    uploaded_at   timestamptz NOT NULL
);
COMMENT ON COLUMN issuance.document.supersedes_id IS 'UNIQUE: mỗi bản chỉ có một bản kế tiếp, nên chuỗi phiên bản không rẽ nhánh';
