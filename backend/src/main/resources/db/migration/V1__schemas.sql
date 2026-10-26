-- B0: bảy schema của backend WeRWA.
-- Sáu schema phân hệ trùng sáu mô-đun (ADR-10); mỗi mô-đun chỉ đọc ghi schema của mình.
-- Schema platform chỉ chứa bảng kỹ thuật không thuộc phân hệ (Q1): lịch sử Flyway, sổ sự kiện, Idempotency-Key, inbox.
-- Bảng nghiệp vụ thêm ở migration của nhóm PR tương ứng.

CREATE SCHEMA IF NOT EXISTS platform;
CREATE SCHEMA IF NOT EXISTS onboarding;
CREATE SCHEMA IF NOT EXISTS issuance;
CREATE SCHEMA IF NOT EXISTS distribution;
CREATE SCHEMA IF NOT EXISTS governance;
CREATE SCHEMA IF NOT EXISTS integration;
CREATE SCHEMA IF NOT EXISTS onchain;
