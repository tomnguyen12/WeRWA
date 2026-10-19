# Câu hỏi còn mở và giả định đang dùng

Gặp điểm dưới đây khi viết code: làm theo **giả định đang dùng** và ghi rõ trong PR. Không tự quyết khác đi.

## Giả định đang dùng (chốt 16/10/2026, chỉnh lại sau)

| Mã | Chủ đề | Giả định |
|---|---|---|
| G1 | Chuyển OTC đợt đầu (3.08) | Chỉ chuyển không kèm thanh toán; hai bên xác nhận, thêm lưu ký nếu bên chuyển là ví omnibus |
| G2 | Ngưỡng phong tỏa (4.02) | 2/2 VSDC + Holco |
| G3 | Series vi phạm | DEFAULTED vẫn cho chuyển nhượng OTC |
| G4 | Ai đánh dấu vi phạm | Holco (Đ1) |
| G5 | Tạm dừng | Dừng cần 2/2 VSDC + Holco; mở lại cần 3/3 |
| G6 | Hủy trái phiếu khi đáo hạn | `burnRedeemed` bỏ qua vị thế đang phong tỏa |
| G7 | Giới hạn nắm giữ | Ví omnibus được miễn kiểm giới hạn trên chuỗi |
| G8 | Thời gian chờ của bộ điều phối ghi sổ | Ký danh sách 2 ngày làm việc; lô BIDV 4 giờ làm việc; VSDC 1 ngày làm việc (tham số) |

## Chờ Tommy quyết

| Chủ đề | Ảnh hưởng tới code |
|---|---|
| Ngày phát hành đợt đầu | Lịch Đ1, ngày chốt mã hợp đồng |
| Kiểm toán hợp đồng trong nước hay quốc tế | Lịch chốt mã |
| Mua hay thuê HSM | `infra/`, cấu hình PKCS#11 |
| Giấy phép GPL-3.0 của T-REX: triển khai lên node BIDV, VSDC có là chuyển giao không | Không chặn XD; nếu không chấp nhận thì tự viết theo giao diện ERC-3643 |
| Bổ sung HLD v0.3 và ADR cũ | Bảng ánh xạ ADR cũ sang bộ v1 |

## Sửa trang nghiệp vụ (để sau thiết kế và demo)

- Euroclear/Clearstream: "Go-live" → "mở rộng sau".
- Vai Cơ quan giám sát: "node quan sát" → "công cụ giám sát".
- RACI: bỏ "DvP" ở đợt đầu.

## Câu hỏi phát sinh khi viết code

Thêm dòng mới ở đây kèm ngày, người hỏi, chỗ trong code bị ảnh hưởng.

| Ngày | Câu hỏi | Ảnh hưởng | Trạng thái |
|---|---|---|---|
| | | | |
