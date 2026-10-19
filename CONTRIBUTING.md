# Quy ước làm việc

## Nhánh

- `main` được bảo vệ: không push thẳng, chỉ merge qua PR, CI phải xanh.
- Tên nhánh: `<loại>/<luồng>/<mã>-<mô-tả-ngắn>`
  - loại: `feat`, `fix`, `docs`, `adr`, `chore`, `test`
  - luồng: `contracts`, `backend`, `frontend`, `infra`, `api`, `docs`
  - mã: mã user story (`us-3.05`) hoặc số issue
  - ví dụ: `feat/contracts/us-3.05-mint-dual-proof`, `adr/docs/017-otc-dvp`

## Commit

Theo Conventional Commits, phạm vi là mô-đun hoặc thư mục:

```
feat(distribution): gom xác nhận khớp thành lô BIDV

Refs: US-3.05
```

Phạm vi hợp lệ: `onboarding`, `issuance`, `distribution`, `governance`, `integration`, `onchain`, `contracts`, `frontend`, `infra`, `api`, `docs`.

## Pull request

- Một PR một việc; nêu user story và tiêu chí chấp nhận đã đáp ứng.
- Ít nhất một người duyệt. Thư mục trong `CODEOWNERS` cần chủ sở hữu duyệt: `docs/`, `api/`, `contracts/` cần SA.
- PR đổi `api/openapi.yaml` hoặc `api/events/` phải tách riêng, merge trước PR code dùng nó.
- PR lệch `docs/design/` phải có đề xuất trong `docs/decisions/` đã duyệt.
- Không merge PR có `TODO` không gắn issue.

## Đổi thiết kế

1. Tạo `docs/decisions/NNNN-ten-ngan.md` theo `docs/decisions/0000-template.md` (đánh số từ 0017, nối tiếp ADR-01..16).
2. PR riêng, nhãn `adr`, SA duyệt.
3. Sau khi duyệt, cập nhật trang liên quan trong `docs/design/` trong cùng PR hoặc PR kế tiếp.

## Phiên bản hợp đồng máy đọc

- OpenAPI: `info.version` theo semver. Đổi không tương thích → tăng major và giữ `/v1` song song tới khi giao diện chuyển xong.
- Sự kiện: đổi không tương thích → tệp schema mới `.v2`, phát song song hai phiên bản trong thời gian chuyển.
- ABI: sinh từ `contracts/` khi build; không sửa tay `api/abi/`.

## CI

| Luồng | Cổng bắt buộc |
|---|---|
| `api/` | Lint OpenAPI (Redocly), kiểm lược đồ sự kiện hợp lệ JSON Schema 2020-12 |
| `contracts/` | `forge build`, `forge test` gồm invariant I1–I8, độ phủ ≥ 95% phần WeRWA, Slither, kiểm bố cục lưu trữ khi đổi hợp đồng nâng cấp được |
| `backend/` | Build, unit test, `ApplicationModules.verify()`, kiểm thử tích hợp Testcontainers (Besu, PostgreSQL, NATS), kiểm khớp OpenAPI |
| `frontend/` | Typecheck, lint, unit test, client sinh từ OpenAPI không lệch |
| Mọi PR | Quét bí mật (không có khóa, mật khẩu trong diff) |

Hiện mới có cổng `api/` (`.github/workflows/spec-check.yml`). Các cổng khác thêm cùng PR khởi tạo của từng luồng.
