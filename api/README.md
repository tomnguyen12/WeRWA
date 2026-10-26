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

Ghi chú B1.0 (duyệt 26/10/2026): mỗi API có `x-werwa-roles`, danh sách vai được gọi theo enum `roles` của `Organization`; `[ANY]` là mọi vai, quyền kiểm theo đối tượng ở tầng nghiệp vụ. `x-werwa-callers` giữ nguyên làm mô tả. Backend kiểm vai theo `x-werwa-roles`; CI `spec-check` chặn API thiếu trường này hoặc dùng vai lạ.

| Bên gọi | Vai |
|---|---|
| Holco | `OPERATOR` |
| UBND | `ISSUER` |
| CQĐH | `OPERATING_AGENCY` |
| Tổ hợp | `SYNDICATE` |
| VSDC | `REGISTRAR` |
| BIDV | `PAYING_AGENT` (webhook dùng mTLS, không dùng JWT) |
| Lưu ký | `CUSTODIAN` |
| Nhà đầu tư | `INVESTOR` |
| Cơ quan giám sát | `SUPERVISOR` |
| Sở TC | chưa có vai; tạm dùng tài khoản thuộc tổ chức CQĐH (Q7) |
| Mọi bên có quyền, Người duyệt, Người ký | `ANY` |

Kiểm tra cục bộ:

```bash
npx @redocly/cli@1.25.0 lint api/openapi.yaml
```
