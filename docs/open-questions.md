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
| 20/10/2026 · C1 · phiên contracts | Định dạng claim. Sổ định danh T-REX không kiểm hạn claim, nên hạn phải kiểm trong module. Dữ liệu claim INVESTOR_CLASS là `abi.encode(uint16 classCode, uint64 validUntil)`; claim KYC_INSTITUTION là `abi.encode(uint64 validUntil)`. `InvestorClassModule` kiểm hạn của cả hai claim. Mã số chủ đề claim chưa chốt: tạm dùng `uint256(keccak256("WERWA.CLAIM.KYC_INSTITUTION"))` và `uint256(keccak256("WERWA.CLAIM.INVESTOR_CLASS"))` | `InvestorClassModule`, `test/utils/TREXSuite.sol`; backend cấp claim (US-1.02, US-6.03) | Định dạng đã duyệt 20/10. Mã chủ đề: giả định, chờ chốt |
| 20/10/2026 · C2 · phiên contracts | Ví được miễn hạn mức (G7). Tommy quyết "thiết kế cho cả 2 và chọn cấu hình sau": hỗ trợ cả (a) ví omnibus của lưu ký và (b) ví của WeRWA, chọn theo series bằng `exemptKinds`; mặc định chỉ (a) | `HoldingLimitModule` (PR 2) | Đã duyệt 20/10 theo ADR-0017 |
| 20/10/2026 · C3 · phiên contracts | Giao diện `AuditAnchor`: `anchor(uint32 businessDate, uint64 lastSeq, bytes32 lastEntryHash)` với `businessDate` dạng YYYYMMDD; sự kiện `AuditAnchored`; lỗi `AlreadyAnchored`, `Unauthorized`; `setOperator` do GOVERNANCE gọi để xoay khóa | `AuditAnchor` (PR 3) | Đã duyệt 20/10 |
| 20/10/2026 · C4 · phiên contracts | Tham số sự kiện của `SeriesController` chưa có trong đặc tả: đề xuất trong PR 4. Phát thêm `StateChanged(from, to)` ở mỗi lần chuyển trạng thái | `SeriesController`, indexer (`series.state_changed`) | Đã duyệt `StateChanged` 20/10; tham số duyệt trong PR 4 |
| 20/10/2026 · C5 · phiên contracts | Kiểm tiền khi mint: `quantity × faceValueMinor × issuePricePpm` phải chia hết cho 10⁶, nếu không thì `AmountMismatch`. Backend đối chiếu theo đúng quy tắc này (US-3.05-AC2) | `SeriesController.mint`, `distribution` | Đã duyệt 20/10 |
| 20/10/2026 · C6 · phiên contracts | Phạm vi `pause`: T-REX chỉ chặn chuyển nhượng; controller chặn thêm `mint`, `correctionBurn`, `burnRedeemed`; phong tỏa vẫn chạy. Cách hiểu đang dùng: "mọi trạng thái trừ CLOSED" cũng trừ CANCELLED, vì CANCELLED chỉ đọc | `SeriesController` (PR 4) | Đã duyệt 20/10; phần CANCELLED đã xác nhận 20/10 |
| 20/10/2026 · C7 · phiên contracts | ISIN kiểm trên chuỗi: 2 chữ cái đầu, ký tự A–Z0–9, chữ số kiểm tra ISO 6166 | `SeriesController.setIsin` | Đã duyệt 20/10 |
| 20/10/2026 · C8 · phiên contracts | SPDX của mã WeRWA: `GPL-3.0-or-later` vì import T-REX; xem lại khi pháp lý trả lời câu hỏi GPL | Mọi tệp `contracts/` | Đã duyệt 20/10 |
| 20/10/2026 · C9 · phiên contracts | `markDefault` không kiểm được `GraceNotOver` vì `SeriesTerms` không có ngày trả từng kỳ và số ngày ân hạn. Cách hiểu đang dùng: hoãn `markDefault`/`markCured` (Đ1) sang sprint Đ1; khi làm cần chọn thêm trường vào `SeriesTerms` (ADR-0017) hay chỉ kiểm vai và trạng thái | `SeriesController` (PR 5 bỏ hai hàm này) | Đã xác nhận 20/10: hoãn sang Đ1 |
| 20/10/2026 · C10 · phiên contracts | `mature`: kỳ gốc là kỳ `periodCount` (gốc chi cùng kỳ coupon cuối); phải có `attestSnapshot` của kỳ đó | `SeriesController.mature` | Đã duyệt 20/10 |
| 20/10/2026 · C11 · phiên contracts | `recoverWallet` qua `recoveryAddress` của T-REX gọi `deleteIdentity(lost)` trên sổ định danh dùng chung, làm hỏng số dư ví cũ ở mọi series khác, và buộc token làm agent của sổ dùng chung. Đề xuất: Holco đăng ký ví mới riêng; controller dùng `forcedTransfer` rồi phong tỏa lại. Cách hiểu đang dùng: hoãn sang Đ1 và mở ADR-0017 khi làm | `SeriesController.recoverWallet` (PR 5 bỏ hàm này) | Đã xác nhận 20/10: hoãn sang Đ1, mở ADR khi làm |
| 20/10/2026 · C12 · phiên contracts | `close` cần tổng cung bằng 0, nhưng `burnRedeemed` bỏ qua vị thế phong tỏa (G6), nên series còn vị thế phong tỏa không bao giờ đóng được | `SeriesController.close`; nghiệp vụ đáo hạn | Tommy quyết 20/10: "cấu hình để lựa chọn 2 phương án khi vận hành" → ADR-0018, đã duyệt 20/10 |
| 20/10/2026 · C13 · phiên contracts | Proxy: `SeriesController` dùng beacon (timelock nâng cấp mọi series một lần); `SeriesFactory` dùng Transparent proxy. Cách hiểu đang dùng: ba module không nâng cấp được; đổi module bằng cách timelock gỡ module cũ và gắn module mới vào compliance | PR 4, PR 6 | Đã duyệt 20/10; module không nâng cấp đã xác nhận 20/10 |
| 20/10/2026 · C14 · phiên contracts | `SeriesFactory.deploySeries(SeriesTerms terms, string name, string symbol)` do GOVERNANCE (3/3) gọi; tên và ký hiệu token truyền riêng, không đổi struct | `SeriesFactory` (PR 6) | Đã duyệt 20/10 |
| 20/10/2026 · C15 · phiên contracts | Timelock: thời gian chờ ở XD 1 giờ (tham số); ai cũng thực thi được sau khi hết thời gian chờ | `TimelockController` (PR 6) | Đã duyệt 20/10 |
| 20/10/2026 · C16 · phiên contracts | Token T-REX khởi tạo ở trạng thái tạm dừng (`paused = true`). Đề xuất: `SeriesController.initialize` mở tạm dừng token ngay, vì `TransferGateModule` đã chặn chuyển nhượng tới khi VSDC xác nhận sổ; `pause`/`unpause` của controller gọi xuống token | `SeriesController.initialize`, `pause`, `unpause` (PR 4) | Đề xuất được xác nhận 20/10 (cách hiểu: lời "xác nhận" gồm cả C16) |
