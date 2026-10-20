<!-- Sinh tự động từ trang thiết kế đã duyệt: https://claude.ai/artifact/9zmtyxojDU6ZyY7RfLTnj4
     Sửa nội dung thiết kế thì sửa ở đây qua PR có SA duyệt; trang HTML chỉ còn là bản lưu. -->

Bước 6 · Đã duyệt · 19/10/2026

# API và sự kiện WeRWA

Hợp đồng API cho giao diện các bên và sự kiện giữa 6 phân hệ, luồng phát hành đợt đầu · Tommy Nguyen, Tư vấn độc lập

Đặc tả theo ADR v1 (một khối triển khai, Spring Modulith, outbox và NATS), mô hình dữ liệu, máy trạng thái và hợp đồng thông minh đã duyệt. Bản OpenAPI 3.1 và lược đồ sự kiện chi tiết sinh từ trang này ở bước viết code.

## Quyết định ngày 19/10

| Chủ đề                 | Quyết định                                                                                                                                                                      |
|------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Điều phối luồng ghi sổ | Một bộ điều phối trong mô-đun Phân phối theo dõi từng series từ lúc danh sách được ký tới khi VSDC xác nhận sổ, tự thử lại và cảnh báo khi kẹt. Các luồng khác đi bằng sự kiện. |
| Thu chữ ký multisig    | Nền tảng tự thu chữ ký EIP-712 vào bảng đề nghị ký, đủ ngưỡng thì gửi giao dịch. Không vận hành dịch vụ thu chữ ký của Safe.                                                    |

## Ba kiểu giao tiếp

| Kiểu              | Dùng cho                               | Quy ước                                                                                                                    |
|-------------------|----------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| API cho giao diện | Màn hình của các bên; webhook của BIDV | REST/JSON, OpenAPI 3.1, OIDC, đường dẫn `/v1`. Webhook ngân hàng dùng mTLS và chữ ký thông điệp.                           |
| Gọi trong khối    | Truy vấn đồng bộ giữa mô-đun           | Chỉ qua giao diện Java công khai của mô-đun (gói `api`); Spring Modulith chặn khi build nếu gọi vào phần nội bộ.           |
| Sự kiện           | Báo việc đã xảy ra                     | Ghi outbox cùng giao dịch cơ sở dữ liệu, phát qua NATS JetStream; vỏ CloudEvents 1.0; bên nhận phải xử lý lặp lại an toàn. |

## Luồng ghi sổ đợt đầu giữa các mô-đun

![Luồng gọi và sự kiện giữa các mô-đun khi ghi sổ đợt đầu: UBND ký danh sách phân bổ, BIDV ký lô đã nhận tiền, bộ điều phối mint từng mục, VSDC xác nhận sổ.](diagrams/05-api-va-su-kien-h1.svg)

***Hình 1.** Nét liền là lệnh hoặc lời gọi; nét đứt là sự kiện; nét xanh là giao dịch lên chuỗi. Bộ điều phối trong Phân phối giữ tiến độ cả luồng: chờ danh sách ký, gom tiền thành lô cho BIDV ký, mint từng mục, rồi mời VSDC xác nhận sổ. Bước 9 đến 15 lặp theo từng lô và từng mục.*

### Bộ điều phối ghi sổ

| Bước             | Chờ sự kiện               | Làm gì                                                           | Hết thời gian chờ                                    |
|------------------|---------------------------|------------------------------------------------------------------|------------------------------------------------------|
| Chờ danh sách ký | allocation_list.signed    | Ghi phân bổ sang Chờ tiền, gửi thông báo thanh toán              | Quá hạn ký 2 ngày làm việc: cảnh báo UBND            |
| Theo dõi tiền    | payment.matched           | Gom các xác nhận khớp thành lô, tạo đề nghị ký cho BIDV          | Quá hạn thanh toán: phân bổ sang Quá hạn, báo tổ hợp |
| Chờ lô BIDV      | payment_batch.signed      | Đưa các mục của lô vào hàng mint                                 | Lô chưa ký sau 4 giờ làm việc: cảnh báo BIDV         |
| Mint             | mint.completed, tx.failed | Gửi mint từng mục; lỗi tạm thì thử lại tối đa 3 lần, giãn dần    | Lỗi bị hợp đồng từ chối: dừng mục đó, cảnh báo Holco |
| Chờ VSDC         | register.attested         | Khi mọi mục có tiền đã mint: tạo đề nghị ký xác nhận sổ cho VSDC | Quá 1 ngày làm việc: cảnh báo VSDC                   |
| Hoàn tất         | —                         | Báo Phát hành chuyển series sang lưu hành; đối soát              | —                                                    |

Thời gian chờ là tham số; số trong bảng là giá trị mặc định đề xuất. Mọi cảnh báo vào hàng chờ vận hành của Holco và gửi bên liên quan.

## Thu chữ ký multisig

![Thu chữ ký: người ký ký trên ví, nền tảng kiểm người ký, đủ ngưỡng Safe tổ chức thì ghép chữ ký EIP-1271, đủ ngưỡng Safe liên bên thì gửi giao dịch.](diagrams/05-api-va-su-kien-h2.svg)

***Hình 2.** Safe liên bên (3/3, 2/2) có thành viên là Safe của từng tổ chức. Nền tảng kiểm người ký thuộc Safe của tổ chức nào, đủ ngưỡng tổ chức thì ghép thành chữ ký hợp đồng theo EIP-1271, đủ ngưỡng liên bên thì gửi giao dịch qua tài khoản chuyển tiếp không có quyền gì trên hợp đồng.*

## Danh mục API cho bản XD

| Đường dẫn                                             | Ai gọi                         | Việc                                                              | Chức năng   |
|-------------------------------------------------------|--------------------------------|-------------------------------------------------------------------|-------------|
| **1 · Onboarding**                                    |                                |                                                                   |             |
| `POST /v1/organizations`                              | Holco                          | Tạo tổ chức, vai, hồ sơ KYB                                       | 1.01        |
| `POST /v1/organizations/{id}/kyb-decision`            | Holco · người duyệt            | Duyệt hoặc từ chối KYB                                            | 1.01        |
| `POST /v1/organizations/{id}/signers`                 | Holco                          | Đăng ký người ký được ủy quyền và địa chỉ ký                      | 1.01        |
| `POST /v1/kyc-cases`                                  | Tổ hợp, Holco                  | Mở hồ sơ KYC nhà đầu tư, gọi sàng lọc cấm vận                     | 1.02        |
| `POST /v1/kyc-cases/{id}/decision`                    | Holco · người duyệt            | Kết luận KYC; được duyệt thì phát claim                           | 1.02 · 1.03 |
| `POST /v1/wallets`                                    | Nhà đầu tư, lưu ký             | Đăng ký ví; nền tảng kiểm chủ ví bằng chữ ký rồi ghi sổ định danh | 1.04        |
| **2 · Phát hành**                                     |                                |                                                                   |             |
| `POST /v1/programs`                                   | Cơ quan điều hành              | Nhập Đề án đã duyệt và các khung                                  | 2.01        |
| `GET /v1/programs/{id}/limits`                        | UBND, CQĐH, Sở TC              | Hạn mức đã dùng và còn lại                                        | 2.02        |
| `POST /v1/documents`                                  | Mọi bên có quyền               | Tải tài liệu, trả về mã và SHA-256                                | 2.04        |
| `POST /v1/series`                                     | Holco, tổ hợp                  | Tạo series từ term sheet                                          | 2.05        |
| `POST /v1/series/{id}/submit`                         | Holco · người lập              | Gửi duyệt 4 mắt                                                   | 2.05        |
| `POST /v1/series/{id}/deploy`                         | Holco                          | Tạo đề nghị ký triển khai 3/3                                     | 2.07        |
| `POST /v1/issuer-decisions`                           | UBND                           | Ghi quyết định về điều khoản, lãi suất                            | 2.06        |
| `GET /v1/series/{id}`                                 | Mọi bên có quyền               | Series, trạng thái, lịch sự kiện                                  | 2.07        |
| **3 · Phân phối**                                     |                                |                                                                   |             |
| `POST /v1/offerings`                                  | Tổ hợp, UBND                   | Tạo đợt chào bán                                                  | 3.01        |
| `POST /v1/offerings/{id}/open · /close`               | Tổ hợp                         | Mở, đóng sổ lệnh                                                  | 3.01        |
| `POST /v1/offerings/{id}/orders`                      | Nhà đầu tư, tổ hợp nhập thay   | Đặt lệnh; kiểm điều kiện ngay khi đặt                             | 3.02        |
| `PATCH /v1/orders/{id} · DELETE`                      | Như trên                       | Sửa, hủy trước giờ đóng sổ                                        | 3.02        |
| `POST /v1/offerings/{id}/allocation-lists`            | Tổ hợp                         | Đề xuất danh sách phân bổ, có phiên bản                           | 3.03        |
| `POST /v1/allocation-lists/{id}/submit`               | Tổ hợp                         | Gửi UBND duyệt và ký                                              | 3.03        |
| `GET /v1/allocation-lists/{id}/proofs/{allocationId}` | Nhà đầu tư, lưu ký             | Đường chứng minh để tự kiểm phân bổ của mình                      | 3.03        |
| `GET /v1/series/{id}/issuance-progress`               | UBND, Holco, VSDC              | Tiến độ của bộ điều phối ghi sổ                                   | 3.05        |
| `POST /v1/transfers · /{id}/confirm`                  | Nhà đầu tư, lưu ký             | Chuyển OTC không kèm thanh toán, hai bên xác nhận                 | 3.08        |
| `GET /v1/holdings`                                    | Nhà đầu tư, lưu ký, VSDC       | Số dư theo ví, theo series                                        | 3.07        |
| `GET /v1/series/{id}/snapshots/{period}`              | VSDC, BIDV, lưu ký             | Danh sách chốt của kỳ                                             | 3.09        |
| **4 · Quản trị**                                      |                                |                                                                   |             |
| `GET /v1/approvals · POST /{id}/decision`             | Người duyệt                    | Hàng chờ duyệt 4 mắt, ra quyết định                               | 4.08        |
| `GET /v1/signing-proposals/{id}`                      | Người ký                       | Dữ liệu EIP-712 cần ký, chữ ký đã có                              | 6.04        |
| `POST /v1/signing-proposals/{id}/signatures`          | Người ký                       | Nộp chữ ký từ ví                                                  | 6.04        |
| `POST /v1/enforcement-orders`                         | Holco                          | Tiếp nhận lệnh phong tỏa, kèm căn cứ                              | 4.02        |
| `GET /v1/supervision/…`                               | Cơ quan giám sát               | Series, sổ, giao dịch, kết quả đối chiếu mã băm                   | 4.04        |
| `GET /v1/audit-log`                                   | Holco, Cơ quan giám sát        | Nhật ký kèm bằng chứng neo                                        | 4.08        |
| `GET /v1/reconciliation/runs`                         | Holco, BIDV, VSDC              | Kết quả đối soát hằng ngày                                        | 4.09        |
| **5 · Tích hợp · bên ngoài gọi vào**                  |                                |                                                                   |             |
| `POST /v1/inbound/bidv/credit-notifications`          | BIDV · mTLS, chữ ký thông điệp | Báo có theo tài khoản ảo                                          | 5.02        |
| `POST /v1/inbound/bidv/payout-confirmations`          | BIDV · như trên                | Xác nhận đã chi coupon, gốc                                       | 5.02        |

## Danh mục sự kiện

Tên theo dạng `werwa.<mô-đun>.<đối tượng>.<việc đã xảy ra>.v<n>`. Đổi cấu trúc dữ liệu không tương thích thì tăng số phiên bản và phát song song hai phiên bản trong thời gian chuyển.

| Sự kiện                                         | Bên phát   | Bên nhận                       | Dữ liệu chính                                   |
|-------------------------------------------------|------------|--------------------------------|-------------------------------------------------|
| **Onboarding**                                  |            |                                |                                                 |
| `werwa.onboarding.organization.kyb_approved.v1` | Onboarding | Quản trị                       | organizationId, roles                           |
| `werwa.onboarding.investor.kyc_approved.v1`     | Onboarding | Phân phối, Onchain             | organizationId, investorClass, expiresAt        |
| `werwa.onboarding.investor.kyc_expired.v1`      | Onboarding | Phân phối                      | organizationId                                  |
| `werwa.onboarding.wallet.registered.v1`         | Onboarding | Phân phối                      | walletId, address, onchainId                    |
| **Phát hành**                                   |            |                                |                                                 |
| `werwa.issuance.series.approved.v1`             | Phát hành  | Quản trị                       | seriesId, termsHash                             |
| `werwa.issuance.series.deployed.v1`             | Phát hành  | Phân phối                      | seriesId, controllerAddress, tokenAddress       |
| `werwa.issuance.series.isin_assigned.v1`        | Phát hành  | Phân phối                      | seriesId, isin                                  |
| `werwa.issuance.series.state_changed.v1`        | Phát hành  | Mọi mô-đun                     | seriesId, from, to, txHash                      |
| `werwa.issuance.debt_service.funding_short.v1`  | Phát hành  | Quản trị (cảnh báo)            | seriesId, period, shortfallMinor                |
| **Phân phối**                                   |            |                                |                                                 |
| `werwa.distribution.offering.closed.v1`         | Phân phối  | Phát hành                      | offeringId, orderCount                          |
| `werwa.distribution.allocation_list.signed.v1`  | Phân phối  | Phát hành, Tích hợp            | listId, merkleRoot, totalQuantity               |
| `werwa.distribution.payment.matched.v1`         | Phân phối  | Bộ điều phối                   | allocationId, amountMinor, bankRef              |
| `werwa.distribution.payment_batch.signed.v1`    | Phân phối  | Bộ điều phối                   | batchId, merkleRoot, itemCount                  |
| `werwa.distribution.mint.completed.v1`          | Phân phối  | Bộ điều phối, Quản trị         | allocationId, wallet, quantity, txHash          |
| `werwa.distribution.register.attested.v1`       | Phân phối  | Phát hành, Quản trị            | seriesId, registerHash, block                   |
| `werwa.distribution.snapshot.attested.v1`       | Phân phối  | Phát hành, Tích hợp            | seriesId, period, registerHash                  |
| **Quản trị**                                    |            |                                |                                                 |
| `werwa.governance.approval.decided.v1`          | Quản trị   | Mô-đun yêu cầu                 | approvalId, subjectRef, decision                |
| `werwa.governance.signing.threshold_reached.v1` | Quản trị   | Onchain                        | proposalId, safe, calldataHash                  |
| `werwa.governance.enforcement.applied.v1`       | Quản trị   | Phân phối, Phát hành           | orderId, wallet, quantity, action               |
| **Tích hợp**                                    |            |                                |                                                 |
| `werwa.integration.bank.credit_received.v1`     | Tích hợp   | Phân phối                      | bankRef, virtualAccount, amountMinor, valueDate |
| `werwa.integration.bank.payout_confirmed.v1`    | Tích hợp   | Phân phối                      | instructionRef, lines                           |
| **Onchain**                                     |            |                                |                                                 |
| `werwa.onchain.tx.mined.v1`                     | Onchain    | Mô-đun gửi giao dịch           | txId, txHash, block, subjectRef                 |
| `werwa.onchain.tx.failed.v1`                    | Onchain    | Mô-đun gửi giao dịch, Quản trị | txId, reason                                    |
| `werwa.onchain.event.indexed.v1`                | Onchain    | Phân phối (bảng đọc)           | contract, eventName, block, logIndex            |

## Quy ước

### Vỏ sự kiện

    {
      "specversion": "1.0",
      "id": "0192a1f0-…",          // UUIDv7, dùng chống trùng
      "type": "werwa.distribution.mint.completed.v1",
      "source": "/werwa/distribution",
      "subject": "allocation/0192a0c2-…",
      "time": "2027-01-20T03:15:22Z",
      "traceparent": "00-…",       // nối vết qua các mô-đun
      "data": {
        "allocationId": "0192a0c2-…",
        "wallet": "0x…",
        "quantity": 500,
        "txHash": "0x…"
      }
    }

### Lỗi API

    HTTP 409
    Content-Type: application/problem+json
    {
      "type": "https://errors.werwa/allocation/exceeds-max",
      "title": "Tổng phân bổ vượt khối lượng series",
      "status": 409,
      "detail": "Tổng 120 000 vượt 100 000 trái phiếu.",
      "instance": "/v1/allocation-lists/0192…/submit",
      "code": "ALLOCATION_EXCEEDS_MAX"
    }

- **Chống trùng:** mọi lệnh ghi (POST, PATCH, DELETE) bắt buộc header `Idempotency-Key`; cùng khóa trong 24 giờ trả lại đúng kết quả lần đầu.
- **Ghi đè:** bản ghi có phiên bản dùng `If-Match` với ETag; sai phiên bản trả 412.
- **Tiền:** chuỗi số theo đơn vị nhỏ nhất kèm mã tiền, ví dụ `{"amountMinor":"100000000","currency":"USD"}`, để không mất chính xác trong JavaScript. Lãi suất là số nguyên phần triệu.
- **Thời gian:** ISO-8601 theo UTC; ngày nghiệp vụ dạng `YYYY-MM-DD`.
- **Phân trang:** theo con trỏ (`cursor`, `limit` tối đa 200).
- **Quyền:** mỗi API kiểm tổ chức và vai của người gọi theo bảng trên; người lập và người duyệt của cùng một việc phải khác nhau.
- **Thao tác dẫn tới ký trên chuỗi** luôn trả `202 Accepted` kèm mã đề nghị ký; kết quả cuối báo qua sự kiện và qua `GET` trạng thái.

Căn cứ: Quyết định công nghệ, Mô hình dữ liệu, Máy trạng thái và Hợp đồng thông minh WeRWA (duyệt 18–19/10/2026). Bước tiếp theo: user story và tiêu chí chấp nhận cho các chức năng XD.
