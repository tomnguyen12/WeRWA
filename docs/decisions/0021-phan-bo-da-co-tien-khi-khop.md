# ADR-0021 — Phân bổ sang "Đã có tiền" khi báo có khớp; lô BIDV đã ký là điều kiện mint

- **Trạng thái:** Đề xuất
- **Ngày:** 2026-10-29
- **Người đề xuất:** Claude (phiên backend), theo Q128 Tommy Nguyen duyệt 29/10/2026
- **Người duyệt:** SA

## Bối cảnh

Máy trạng thái Phân bổ (`docs/design/03-may-trang-thai.md`, Hình 4, cạnh 4) cho phân bổ sang **Đã có tiền** khi "xác nhận IPA khớp số tiền và nằm trong lô BIDV đã ký", tức là khi BIDV đã ký gốc lô trên chuỗi.

B8a (Q52, Q53, chức năng 3.05) đã làm khác: báo có của BIDV được đối chiếu ngay khi nhận; đúng số tiền và đồng tiền thì xác nhận MATCHED và phân bổ sang `PAID`. Bộ lập lịch gom các xác nhận MATCHED vào lô sau đó (mặc định 15 phút). Tiến độ ghi sổ (`GET /v1/series/{id}/issuance-progress`) đã đếm `paid` theo cách này.

Giữa lúc khớp và lúc BIDV ký lô có một khoảng thời gian. Trong khoảng này, theo thiết kế, phân bổ vẫn là **Chờ tiền** dù tiền đã về tài khoản ảo.

## Phương án đã cân nhắc

1. **Giữ như B8a:** `PAID` khi báo có MATCHED. Lô BIDV đã ký (`payment_batch.status = SIGNED`) là điều kiện riêng của bộ điều phối mint, cùng với điều kiện hợp đồng kiểm đường chứng minh của lô.
2. **Theo đúng thiết kế:** giữ `AWAITING_PAYMENT` tới khi indexer đọc `PaymentBatchAdded` của lô chứa xác nhận, rồi mới sang `PAID`. Phải sửa B8a (đối chiếu, tiến độ, cảnh báo quá hạn) và thêm một trạng thái hoặc cờ để biết phân bổ "đã khớp nhưng chưa vào lô đã ký", vì cảnh báo quá hạn (cạnh 7) không được bắn khi tiền đã về.

## Quyết định

Chọn phương án 1. Phân bổ sang `PAID` khi xác nhận IPA khớp số tiền (Q52). Không mint phân bổ nào khi lô chứa xác nhận của nó chưa `SIGNED`; bộ điều phối mint (Q133) chỉ lấy phân bổ `PAID` có `payment_confirmation.payment_batch_id` trỏ tới lô `SIGNED`. Hợp đồng vẫn là chốt chặn cuối: `mint` kiểm `_paymentBatchHash[paymentRoot]` và đường chứng minh lô.

Lý do: tách hai việc khác nhau. "Tiền đã về đúng" là sự kiện nghiệp vụ, cần để dừng cảnh báo quá hạn và báo tiến độ cho tổ hợp. "BIDV đã cam kết trên chuỗi" là điều kiện ghi sổ. Bất biến "không ghi sổ trái phiếu chưa có tiền BIDV xác nhận" (US-3.05) vẫn giữ nguyên.

## Hệ quả

- **Trang thiết kế phải sửa:** `03-may-trang-thai.md`, máy Phân bổ cạnh 4. Điều kiện sửa thành "Xác nhận IPA khớp số tiền"; bên thực hiện là "Hệ thống, theo báo có của BIDV"; ngoài chuỗi. Cạnh 5 thêm "lô chứa xác nhận đã được BIDV ký". Hình 4 không đổi tên trạng thái.
- **Ảnh hưởng tới `api/`:** không đổi lược đồ. `AllocationItem.status = PAID` nghĩa là đã khớp tiền; `PaymentBatch.items[].allocationStatus` cho biết mục đã vào lô nào.
- **Ảnh hưởng tới dữ liệu và hợp đồng:** không. Hợp đồng đã kiểm lô BIDV khi mint.
