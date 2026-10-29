-- B6c: đề nghị ký gắn ISIN của VSDC (Q118); địa chỉ hợp đồng, ISIN, trạng thái lấy từ chuỗi qua indexer (Q117).
ALTER TABLE issuance.series ADD COLUMN isin_proposal_id uuid;
