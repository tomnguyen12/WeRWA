# backend/ — ứng dụng WeRWA

Một khối triển khai (ADR-10), Java 21, Spring Boot 4.1, Spring Modulith 2.1, PostgreSQL, Flyway. Quyết định riêng của luồng backend: D1–D9, Q1–Q3 trong `docs/open-questions.md`.

## Mô-đun

```mermaid
flowchart LR
  subgraph Khối ứng dụng
    ON[onboarding] & IS[issuance] & DI[distribution] & GO[governance] & IN[integration] & OC[onchain]
    PL[platform · dùng chung]
  end
  ON & IS & DI & GO & IN & OC -. sự kiện qua NATS .-> ON & IS & DI & GO & IN & OC
  ON & IS & DI & GO & IN & OC --> PL
```

| Gói | Mô-đun | Schema |
|---|---|---|
| `vn.werwa.onboarding` | Onboarding · phân hệ 1 | `onboarding` |
| `vn.werwa.issuance` | Phát hành · phân hệ 2 | `issuance` |
| `vn.werwa.distribution` | Phân phối · phân hệ 3 | `distribution` |
| `vn.werwa.governance` | Quản trị · phân hệ 4 | `governance` |
| `vn.werwa.integration` | Tích hợp · phân hệ 5 | `integration` |
| `vn.werwa.onchain` | Onchain · phân hệ 6 | `onchain` |
| `vn.werwa.platform` | Dùng chung, không nghiệp vụ (D3) | `platform`, chỉ bảng kỹ thuật (Q1) |

- Mô-đun phân hệ chỉ gọi nhau qua gói `api` (named interface); còn lại đi bằng sự kiện, kể cả trong cùng khối (D4).
- `platform` là mô-đun mở: mọi mô-đun dùng được, nhưng `platform` không phụ thuộc mô-đun phân hệ nào.
- Mỗi mô-đun chỉ đọc ghi schema của mình. Migration ở `src/main/resources/db/migration`, đặt tên `V<n>__<mô-đun>_<việc>.sql`.
- `ModularityTests` chạy `ApplicationModules.verify()`; vi phạm ranh giới thì build đỏ. Tài liệu mô-đun sinh ở `target/spring-modulith-docs` (CI đính kèm).

## Chạy trên máy

Cần JDK 21, Maven 3.9+, Docker (cho kiểm thử tích hợp). Dựng devnet rồi nạp tệp env gộp (I11):

```bash
infra/werwa.sh up --signer wehsm            # hoặc không có --signer: chưa mint, cấp claim được
set -a; source "$(infra/werwa.sh env)"; set +a
cd backend && mvn spring-boot:run
```

| Lệnh | Việc |
|---|---|
| `mvn test` | Kiểm thử đơn vị và `ApplicationModules.verify()` |
| `mvn verify` | Thêm kiểm thử tích hợp `*IT` (Testcontainers: PostgreSQL 18.6; Besu, NATS từ nhóm B2, B3) |

## Cấu hình

`WerwaProperties` (`werwa.*`) bind đúng tên biến của `infra/werwa.sh env` theo I11, I13 và kiểm khi khởi động:

- `werwa.chain.read-urls` không được trùng `submit-urls`: ứng dụng chỉ đọc qua O1 (ADR-12).
- `signing-mode=pkcs11` thì bắt buộc `werwa.signer.url`, `werwa.signer.token`.
- Hợp đồng và Safe tra bằng `contract("seriesFactory")`, `safe("governance3of3")`: biến môi trường làm mất chữ hoa, nên không đọc map trực tiếp.

## Nền API (B1)

Mọi API `/v1/**` đi qua chuỗi kiểm dưới đây trước khi tới controller. Controller của mô-đun cài interface sinh từ `api/openapi.yaml` (gói `vn.werwa.platform.openapi.api`, D6), không tự khai đường dẫn.

```mermaid
flowchart LR
  R[Request] --> J[JWT Keycloak: issuer, audience] --> C[Hợp đồng: API có trong đặc tả, x-werwa-roles, vai thuộc tổ chức] --> I[Idempotency-Key với lệnh ghi] --> H[Controller]
  J -. lỗi .-> P[problem+json có code]
  C -. lỗi .-> P
  I -. lỗi .-> P
  H -. ApiException .-> P
```

| Cần | Dùng |
|---|---|
| Lỗi nghiệp vụ | Enum mã lỗi của mô-đun cài `ErrorCode`, ném `ApiException` |
| Người gọi | `CurrentUser.get()`: `subject`, `organizationId`, `roles` |
| Đối tượng thuộc tổ chức khác (US-4.07-AC2) | `OrgScope.requireOwnOrganization(owner)` hoặc `requireOwnOrganizationOr(owner, vai xem chéo)` |
| Ghi đè | Cột `version`; trả `ETag: ETags.of(version)`; kiểm `ETags.requireMatch(ifMatch, version)` |
| Khóa chính | `Ids.newId()` (UUIDv7) |
| Tiền, tỷ lệ | `Money` (`amountMinor` nguyên), `Ppm`; `Ppm.applyExact` theo quy tắc C5 |
| Phân trang | `Paging.limit(limit)`, `Paging.decodeCursor`, `Paging.Page.of(rows, limit, idOf)` |
| Kiểm response khớp đặc tả trong test | `OpenApiSchemas.validate("<Schema>", json)` |

`Idempotency-Key` theo người dùng (Q6), giữ 24 giờ ở `platform.idempotency_key`; trả lại kèm `Idempotent-Replayed: true`; kết quả 5xx không lưu. Webhook ngân hàng (`/v1/inbound/**`, mTLS) đóng tới B8. Vai tổ chức đọc từ tệp seed devnet tới B5 (Q5).

## Quy trình làm việc

```mermaid
flowchart TD
  A[Tommy giao việc] --> B[Claude lập kế hoạch PR] --> C[Tommy duyệt kế hoạch]
  C --> D[PR api/ riêng nếu cần, merge trước] --> E[Code và kiểm thử trên nhánh feat/backend/…]
  E --> F[Push, đọc CI tới khi xanh · Q3] --> G[Mở PR] --> H[Tommy review và merge]
  E -. lệch thiết kế .-> X[Đề xuất docs/decisions/] -.-> C
  E -. điểm chưa rõ .-> Y[Ghi docs/open-questions.md, dừng phần đó] -.-> C
```

## Lộ trình nhóm PR

| Thời gian | Nhóm | Nội dung | Cảnh demo |
|---|---|---|---|
| Chuẩn bị | B0 | Khung dự án, 7 mô-đun, Flyway, cấu hình, CI | |
| | B1 | Nền API: problem+json, Idempotency-Key, If-Match, UUIDv7, tiền và ppm, JWT `org_id`/`roles`, maker ≠ checker, sinh interface từ OpenAPI | |
| | B2 | Outbox → NATS JetStream, CloudEvents, inbox chống trùng | |
| Sprint 1 | B3 | Onchain: web3j từ ABI, `chain_tx`, gửi qua V1/V2, đọc O1, ký qua WeHSM, indexer | ⑩ |
| | B4 | Quản trị: duyệt 4 mắt, đề nghị ký EIP-712, Safe EIP-1271, nhật ký nối mã băm, neo hằng ngày | xuyên suốt |
| Sprint 2 | B5 | Onboarding: KYB, KYC, phân loại, ví, claim | ② ③ |
| | B6 | Phát hành: Đề án, hạn mức, tài liệu, series, quyết định UBND, ISIN | ① |
| Sprint 3 | B7 | Đợt chào bán, sổ lệnh, danh sách phân bổ, Merkle | ④ |
| | B8 | Giả lập BIDV, bộ điều phối ghi sổ, mint | ⑤ ⑥ |
| Sprint 4 | B9 | Sổ người sở hữu, sao kê, chuyển OTC | ⑦ |
| | B10 | Phong tỏa, chuyển cưỡng chế | ⑨ |
| Sprint 5 | B11 | Coupon, danh sách chốt, đối soát | ⑧ |
| | B12 | Giám sát, tham số, lịch ngày nghỉ, webhook cảnh báo (I5) | xuyên suốt |
| | B13 | Đáo hạn, hoàn gốc | ⑪ |
