# Hợp đồng máy đọc được

| Tệp | Nguồn | Ai sửa |
|---|---|---|
| `openapi.yaml` | `docs/design/05-api-va-su-kien.md` | PR riêng, SA duyệt, merge trước PR code |
| `events/catalog.yaml`, `events/*.schema.json` | như trên | như trên |
| `abi/` | sinh từ `contracts/` khi build | không sửa tay |

Bản 0.1.0 sinh từ trang thiết kế ngày 19/10/2026. Từ nay tệp trong thư mục này là bản gốc; trang thiết kế chỉ cập nhật khi đổi ý nghĩa.

Ghi chú bản 0.1.0 (hai điểm bổ sung đã duyệt 20/10/2026):

- `POST /v1/wallet-challenges` không có trong danh mục API của trang thiết kế; thêm vì US-1.04 AC1 yêu cầu ký thông điệp thử thách.
- `GET /v1/supervision/…` của trang thiết kế được tách thành `/v1/supervision/series/{id}` và `/v1/supervision/block-hash-checks`; chi tiết màn hình giám sát chốt khi làm US-4.04.
- Các trường của lược đồ lấy từ mô hình dữ liệu, đổi sang camelCase; tiền là đối tượng `Money` (`amountMinor` dạng chuỗi + `currency`).

Kiểm tra cục bộ:

```bash
npx @redocly/cli@1.25.0 lint api/openapi.yaml
```
