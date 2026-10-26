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

## Sự kiện giữa mô-đun (B2)

Mọi sự kiện giữa mô-đun đi qua NATS JetStream, kể cả trong cùng khối (D4). Không dùng `@ApplicationModuleListener` hay `@EventListener` giữa các mô-đun.

```mermaid
flowchart LR
  A[Mô-đun: events.publish trong giao dịch] --> B[(platform.event_publication)]
  B -- sau commit --> C[OutboxRelay: CloudEvents, Nats-Msg-Id]
  C --> D[[Stream WERWA]]
  D --> E[Consumer bền mô-đun__loại]
  E --> F[Giao dịch: inbox + EventHandler] -- commit --> G[ack]
  F -. lỗi .-> H[Gửi lại theo Q10] -. hết lần .-> I[(platform.event_dead_letter)]
  C -. NATS lỗi .-> J[Phát lại mỗi 30 giây]
```

| Cần | Dùng |
|---|---|
| Phát sự kiện | `events.publish(type, subject, data)` trong giao dịch; `type` phải có trong `api/events/catalog.yaml` và thuộc mô-đun phát |
| Nhận sự kiện | Bean cài `EventHandler`: `consumer()` là tên mô-đun, `eventType()`, `handle(InboundEvent)`; đọc dữ liệu bằng `event.data(Kiểu.class)` |
| Kiểm sự kiện khớp lược đồ trong test | `EventSchemas.validate(type, cloudEventBytes)` |
| Kiểm thử tích hợp | Kế thừa `IntegrationTest`: PostgreSQL và NATS thật, stream tạo từ tệp của infra |

Consumer mới đọc từ đầu stream (giữ 30 ngày); inbox chặn xử lý trùng. Thêm bên nhận cho loại sự kiện đã có thì bên đó sẽ nhận cả sự kiện cũ còn trong stream.

## Onchain (B3)

```mermaid
flowchart LR
  M[Mô-đun: chain.submit trong giao dịch] --> P[Chạy thử eth_call trên O1] -- từ chối --> R[ChainCallRejected CHAIN_*]
  P --> S[Ký qua WeHSM eth_signTransaction, giữ nonce] --> B[(chain_tx BUILT)]
  B -- sau commit --> V[Gửi V1, lỗi thì V2] --> T[(SENT)]
  I[Indexer đọc O1] --> E[(indexed_event)] --> X[event.indexed.v1]
  T -- indexer đã đọc tới khối --> D[(MINED / FAILED)] --> Y[tx.mined.v1 / tx.failed.v1]
```

| Cần | Dùng |
|---|---|
| Mã hóa calldata | `calls.load(AuditAnchor.class, calls.address("auditAnchor")).anchor(...).encodeFunctionCall()`; lớp hợp đồng sinh từ `api/abi` vào `vn.werwa.onchain.api.contracts` |
| Gửi giao dịch khóa vận hành Holco | `chain.submit(new TxRequest(purpose, subjectRef, SignerKey.HOLCO_OPERATOR, to, data))` trong giao dịch DB; kết quả qua `werwa.onchain.tx.mined.v1` / `tx.failed.v1` |
| Đọc sự kiện chuỗi | Nhận `werwa.onchain.event.indexed.v1`, đọc chi tiết ở `onchain.indexed_event` qua API của Onchain |
| Kiểm thử có chuỗi | Kế thừa `ChainIntegrationTest`: Besu QBFT một node, `DeployPlatform` thật, ký bằng khóa thử (Q15). Cần Foundry 1.8.5 và `jq` trên máy |

Chế độ ký `devnet` (I12) không ký được: gửi giao dịch trả 503 `CHAIN_SIGNER_UNAVAILABLE`. Chế độ `pkcs11` ký qua WeHSM bằng `eth_signTransaction` (Q14), chờ phiên WeHSM bổ sung.

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
