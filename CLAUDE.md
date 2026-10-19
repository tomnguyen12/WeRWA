# CLAUDE.md — WeRWA

Hướng dẫn cho mọi phiên Claude làm việc trong repo này. Đọc hết trước khi sửa bất cứ thứ gì.

## WeRWA là gì

Nền tảng ghi sổ trái phiếu số cho đợt phát hành trái phiếu của UBND TP.HCM tại VIFC. Holco xây và vận hành nền tảng; VSDC là tổ chức quản lý sổ đăng ký và thuê Holco vận hành sổ; BIDV là đại lý thanh toán (IPA); Trustee đại diện người sở hữu trái phiếu. Nhà đầu tư chỉ là tổ chức, không có đợt bán lẻ.

Thuật ngữ trong tài liệu: **XD** = Build, bản cho buổi xét duyệt nền tảng đầu tháng 02/2027; **Đ1** = phần cần thêm trước đợt phát hành đầu; **ĐS** = để sau; **GĐ1/GĐ2** = giai đoạn nền tảng chuỗi; **n.xx** = mã chức năng trong Kiến trúc 6 phân hệ.

## Nguồn sự thật, theo thứ tự ưu tiên

1. `docs/business/` — nghiệp vụ (Cấu trúc các bên, Kiến trúc 6 phân hệ / 55 chức năng). **Chỉ đọc.** Chỉ Tommy đổi nghiệp vụ. Thấy mâu thuẫn thì ghi vào `docs/open-questions.md` và dừng phần việc đó, không tự suy diễn.
2. `docs/design/` — thiết kế đã duyệt: ADR, mô hình dữ liệu, máy trạng thái, hợp đồng thông minh, API và sự kiện, user story.
3. `api/` — hợp đồng máy đọc được: `openapi.yaml`, `events/*.schema.json`, `abi/` (sinh từ `contracts/`).
4. Code.

Code phải khớp thiết kế. Cần lệch thiết kế thì mở đề xuất trong `docs/decisions/` (theo mẫu) và chờ SA duyệt; không âm thầm sửa thiết kế trong PR code.

Không lấy nghiệp vụ từ các bản dự thảo nghị quyết hay mô hình nghiệp vụ v0.7.1. Thiết kế chỉ dựa trên `docs/business/`.

## Quyết định đã khóa (không mở lại trong PR code)

- Đợt đầu: không thanh toán trên WeRWA. Ghi sổ theo xác nhận của IPA (BIDV). DvP để các đợt sau.
- Mint chỉ khi mục có trong **cả hai** cây Merkle: danh sách phân bổ do UBND ký và lô xác nhận tiền do BIDV ký; mỗi mục mint đúng một lần. Trái phiếu bị khóa tới khi VSDC xác nhận sổ.
- Chuỗi GĐ1: Hyperledger Besu, QBFT, có cấp phép, phí gas 0, khối 2 giây. GĐ2: L1 riêng tương thích EVM. Mô-đun Onchain chỉ dùng JSON-RPC chuẩn EVM, không dùng tính năng riêng của Besu.
- 4 validator: V1, V2 của Holco ở hai vùng (chạy song song, mỗi node một khóa), V3 BIDV, V4 VSDC. O1 là node chỉ đọc ở mỗi vùng. **Ứng dụng không bao giờ đọc từ validator.**
- Token ERC-3643 (T-REX, ghim phiên bản) với ONCHAINID, decimals 0 (1 token = 1 trái phiếu). Một bộ hợp đồng mỗi series qua factory; sổ định danh dùng chung.
- Ký theo vai: 3/3 UBND+VSDC+Holco triển khai/hủy series; VSDC gắn ISIN; UBND ký gốc cây phân bổ và lãi suất; BIDV ký gốc lô tiền; khóa vận hành Holco mint; 2/2 VSDC+Holco phong tỏa, khôi phục ví, tạm dừng; mở lại cần 3/3. Nâng cấp qua proxy + timelock.
- Khóa: mỗi bên giữ khóa của mình. Hiện dùng SoftHSM qua PKCS#11; HSM thật trước đợt đầu. Holco không giữ khóa ví nhà đầu tư.
- Ứng dụng: một khối triển khai (modular monolith), Spring Modulith, outbox + NATS JetStream.

## Ngăn xếp

| Phần | Công nghệ |
|---|---|
| Hợp đồng | Solidity, Foundry, T-REX (ghim), Safe cho multisig |
| Backend | Java 21, Spring Boot, Spring Modulith, web3j, PostgreSQL, Flyway |
| Sự kiện | Outbox trong cùng giao dịch DB → NATS JetStream; vỏ CloudEvents 1.0 |
| Frontend | React, TypeScript |
| Định danh | Keycloak (OIDC), MFA bắt buộc |
| Hạ tầng | Cloud trong nước, hai vùng; sau chuyển về DC của tập đoàn |

## Cấu trúc repo

```
docs/business/      nghiệp vụ (chỉ đọc)
docs/design/        thiết kế đã duyệt + diagrams/
docs/decisions/     đề xuất đổi thiết kế (ADR-17 trở đi)
api/openapi.yaml    API REST
api/events/         lược đồ sự kiện + catalog.yaml
api/abi/            ABI xuất từ contracts/ (sinh tự động, không sửa tay)
contracts/          Foundry (chưa tạo)
backend/            Spring Boot, một mô-đun mỗi phân hệ (chưa tạo)
frontend/           React (chưa tạo)
infra/              mạng Besu, compose, IaC (chưa tạo)
```

Sáu mô-đun backend trùng sáu phân hệ và sáu schema PostgreSQL: `onboarding`, `issuance`, `distribution`, `governance`, `integration`, `onchain`. Mô-đun chỉ gọi nhau qua gói `api` công khai; còn lại đi bằng sự kiện.

## Thứ tự luồng việc

1. `contracts/` trước. ABI xuất vào `api/abi/` là đầu vào của backend.
2. `backend/` theo `api/openapi.yaml`, `api/events/` và `api/abi/`.
3. `frontend/` theo `api/openapi.yaml`.
4. `infra/` song song.

Mỗi luồng việc là một phiên Claude riêng. Phiên nào cũng đọc file này và chỉ sửa thư mục của luồng mình, cộng với `api/` khi được giao.

## Quy ước bắt buộc

- **Chỉ viết code khi được giao việc cụ thể** (issue hoặc lệnh trong phiên). Không tự mở rộng phạm vi, không dựng trước phần chưa được giao.
- Tên mã (lớp, hàm, biến, bảng, cột, enum, mã lỗi) bằng tiếng Anh, dùng đúng tên trong `docs/design/`. Tài liệu viết tiếng Việt.
- Tiền: `amount_minor` (số nguyên đơn vị nhỏ nhất) + `currency`; trong JSON là chuỗi số. Không dùng số thực cho tiền.
- Lãi suất và tỷ lệ: số nguyên phần triệu (ppm). Lưu theo từng kỳ.
- Khóa chính UUIDv7. Thời gian UTC, ISO-8601; ngày nghiệp vụ `YYYY-MM-DD`.
- Lá Merkle: `keccak256(bytes.concat(keccak256(abi.encode(seriesController, allocationId, wallet, quantity))))`; lá lô tiền: `keccak256(bytes.concat(keccak256(abi.encode(seriesController, allocationId, amountMinor, bankRefHash))))`.
- API: mọi lệnh ghi bắt buộc `Idempotency-Key`; lỗi dạng `application/problem+json` có `code`; ghi đè có `If-Match`; thao tác dẫn tới ký trên chuỗi trả `202` kèm mã đề nghị ký.
- Sự kiện: tên `werwa.<module>.<entity>.<event>.v<n>`; bên nhận xử lý lặp lại an toàn; đổi không tương thích thì tăng phiên bản và phát song song.
- Người lập và người duyệt của cùng một việc phải khác nhau (duyệt 4 mắt).
- Trạng thái ngoài chuỗi chỉ đổi sau khi giao dịch đã lên khối và indexer đã đọc sự kiện.

## Bảo mật

- Không bao giờ commit khóa riêng, mnemonic, mật khẩu, token. Khóa ký đi qua PKCS#11.
- Không đưa dữ liệu cá nhân của nhà đầu tư lên chuỗi; claim chỉ có mã loại và hạn.
- Hợp đồng: không nhận tiền, không `tx.origin`, không `delegatecall` tự do, không `selfdestruct`; giữ khoảng trống lưu trữ cho hợp đồng nâng cấp được.
- T-REX là GPL-3.0: không sửa mã T-REX; phần WeRWA là hợp đồng riêng gọi T-REX qua giao diện chuẩn.

## Kiểm thử là điều kiện xong việc

- Mỗi tiêu chí chấp nhận (Cho · Khi · Thì) trong `docs/design/06-user-story.md` thành ít nhất một kiểm thử tự động, đặt tên theo mã `US-x.yy-ACn`.
- Hợp đồng: Foundry invariant I1–I8 chạy mỗi commit; độ phủ dòng phần WeRWA ≥ 95%; Slither không có lỗi mức cao.
- Backend: `ApplicationModules.verify()` của Spring Modulith phải qua; kiểm thử tích hợp chạy với Besu và PostgreSQL thật qua Testcontainers.
- API: request/response trong kiểm thử phải khớp `api/openapi.yaml`.

## Câu hỏi còn mở

Xem `docs/open-questions.md`. Gặp điểm thuộc danh sách đó thì làm theo giả định đang dùng và ghi rõ trong PR.
