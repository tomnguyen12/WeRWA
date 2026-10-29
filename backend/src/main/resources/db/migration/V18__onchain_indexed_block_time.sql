-- B12b: giờ của khối cho dòng giao dịch của công cụ giám sát (US-4.04-AC1; Q92). Dòng đọc trước bản này để trống.
ALTER TABLE onchain.indexed_event ADD COLUMN block_time timestamptz;
