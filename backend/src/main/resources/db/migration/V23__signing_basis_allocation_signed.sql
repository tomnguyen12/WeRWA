-- B7b: mã băm căn cứ của đề nghị ký (US-2.06-AC3; Q126); danh sách phân bổ đã ký trên chuỗi (Q124).
ALTER TABLE governance.signing_proposal ADD COLUMN basis_hash text CHECK (basis_hash ~ '^0x[0-9a-f]{64}$');
ALTER TABLE distribution.allocation_list
    ADD COLUMN signed_tx_hash text CHECK (signed_tx_hash ~ '^0x[0-9a-f]{64}$'),
    ADD COLUMN signed_at      timestamptz;
