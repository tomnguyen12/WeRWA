# Bàn giao luồng backend WeRWA (31/10/2026)

Tài liệu cho phiên Claude (hoặc người) tiếp nhận luồng `backend/`. Đọc `CLAUDE.md` ở gốc repo trước, rồi `backend/README.md`, rồi tài liệu này.

## 1. Trạng thái một dòng

16 nhóm backend (B12a → B14) đã viết xong trên chuỗi nhánh nối tiếp nhau, **chưa biên dịch và chưa chạy kiểm thử lần nào** (GitHub Actions khóa thanh toán; máy làm việc của phiên cũ không tới được Maven Central). Mọi thứ đã commit và đẩy lên GitHub. Việc đầu tiên: chạy CI một lần trên nhánh cuối, sửa lỗi.

## 2. Nhánh và PR

| Thứ | Ở đâu |
|---|---|
| Nhánh cuối, chứa mọi nhóm | `feat/backend/b14-tam-dung` (77 commit so với `main`) |
| Nhánh từng nhóm | `feat/backend/<nhóm>`; bản sao `wip/backend/<nhóm>`. B10a, B11a, B4c, B9a chỉ còn ở `wip/backend/…` |
| PR CI (gộp trước nhất) | #73 `feat/ci/self-hosted-runner` |
| PR API (gộp theo thứ tự) | #55 → #57 → #58 → #59 → #60 → #61 → #62 → #63 → #64 → #65 → #66 → #67 → #68 → #69 → #70 → #71 → #72 (đặc tả 0.8.0 → 0.24.0) |
| PR của phiên khác | #56 Mailpit (infra), độc lập |
| PR backend | Chưa mở. Mở sau khi CI qua: một PR từ `feat/backend/b14-tam-dung`, hoặc từng nhóm theo chuỗi dưới |

Chuỗi backend, mỗi nhánh tách từ nhánh trước và gộp nhánh API cùng nhóm:

```
b12a-tham-so → b10a-phong-toa → b11a-coupon → b4c-doi-soat → b9a-chuyen-otc → b9c-so-huu → b12b-giam-sat
→ b11c-ky-tra-no → b4b-ky-multisig → b6c-trien-khai → b7b-ky-danh-sach → b8b-ky-lo-mint → b9d-xac-nhan-so
→ b10b-ky-phong-toa → b11b-chup-so → b13b-dao-han → b14-tam-dung
```

Remote: `https://github.com/tomnguyen12/WeRWA.git` (repo private). `git remote` của bản clone cũ hay tự về `tomnguyen12/werwa` viết thường; đẩy vẫn qua nhờ chuyển hướng, nhưng nên đẩy thẳng URL đúng.

## 3. CI

- Mọi workflow trong `.github/workflows/` chỉ chạy khi ra lệnh (`workflow_dispatch`) trên các nhánh đã gộp #73; khối trigger cũ giữ dạng chú thích. `main` vẫn còn trigger tự động cho tới khi #73 được gộp.
- Chạy: GitHub → Actions → `backend` → Run workflow → nhánh `feat/backend/b14-tam-dung`; hoặc `gh workflow run backend.yml --ref feat/backend/b14-tam-dung -R tomnguyen12/WeRWA` từ máy có quyền. Phiên Claude cloud không gọi được đường API Actions (proxy chặn), nhưng đọc được kết quả qua annotation: `gh api repos/tomnguyen12/WeRWA/check-runs/<id>/annotations`.
- Runner: biến kho `WERWA_BACKEND_RUNNER` (JSON), ví dụ `["self-hosted","linux","werwa-ci"]`; không đặt thì `ubuntu-latest`. Dựng runner tự host trên VM Ubuntu 24.04: `infra/ci/README.md`, `infra/ci/runner-setup.sh` (phương án A, Tommy duyệt 31/10). Chưa rõ khóa thanh toán có chặn cả runner tự host; nếu có thì phương án B (bộ chạy độc lập, báo trạng thái commit qua API), dùng lại `backend/ci/run.sh`.
- Chạy tay không qua GitHub: máy có Docker, JDK 21, Maven, Foundry 1.8.5, `jq` → `cd backend && ci/run.sh`, đọc `backend/build.log`.
- Quy ước khi CI còn khóa: đầu mỗi nhánh `feat/backend/*` là commit rỗng `chore: đẩy nhánh khi CI GitHub còn khóa thanh toán [skip ci]`, kèm bản sao `wip/`.

## 4. Quy trình mỗi nhóm (Tommy đã thống nhất)

1. Đề xuất sơ đồ luồng (vẽ, không mô tả bằng chữ) và bảng Q (mã Q tiếp theo là **Q178**).
2. Tommy trả lời "duyệt" (hoặc sửa từng mã).
3. Ghi các dòng Q vào `docs/open-questions.md` trong nhánh `feat/api/<nhóm>.0-…`, tăng phiên bản `api/openapi.yaml`, chạy `npx @redocly/cli@1.25.0 lint api/openapi.yaml` và kiểm catalog sự kiện; mở PR API (base `main`, thân "Gộp sau #N", diff là commit cuối).
4. Làm backend trên `feat/backend/<nhóm>` tách từ nhánh backend trước, gộp nhánh API của nhóm.
5. Commit, thêm commit rỗng `[skip ci]` nếu CI còn khóa, đẩy `feat/` và `wip/`, kiểm không có workflow chạy, đặt upstream cho nhánh.
6. Báo Tommy: kết quả, thứ tự gộp, việc cần phiên khác, nhóm tiếp theo. Claude không tự gộp PR.

Ràng buộc: không sửa `docs/business/`, `docs/design/` (lệch thiết kế → đề xuất trong `docs/decisions/`); không commit khóa, mnemonic, mật khẩu, token; tài liệu tiếng Việt, tên mã tiếng Anh; người lập ≠ người duyệt; trạng thái ngoài chuỗi chỉ đổi khi indexer đã đọc sự kiện. Trong tài liệu và sản phẩm giao: ghi "Tommy Nguyen" / "Tư vấn độc lập", không gắn AIQ/AIQuinta.

## 5. Đã làm (Q77–Q177)

| Nhóm | Nội dung | Q | Lớp chính |
|---|---|---|---|
| B9a | Chuyển OTC ngoài chuỗi | Q77–Q82 | `distribution/transfer/TransferService` |
| B9c | Sổ người sở hữu, sao kê, R5 | Q83–Q89 | `distribution/holding/*`, `onchain/Web3jTokenBalances` |
| B12b | Công cụ giám sát, R7 | Q90–Q97 | `governance/supervision/*` |
| B11c | Kỳ trả nợ ngoài chuỗi, lệnh chi BIDV | Q98–Q105 | `distribution/payout/DebtServiceRunner`, `issuance/series/DebtServiceLedger` |
| B4b | Thu chữ ký multisig, Safe 1.4.1 | Q106–Q113 | `governance/signing/*`, `onchain/Web3jSafes` |
| B6c | Triển khai series 3/3, ISIN | Q114–Q120 | `issuance/series/SeriesDeployment` |
| B7b | UBND ký danh sách phân bổ | Q121–Q127 | `distribution/allocation/AllocationSigning` |
| B8b | BIDV ký lô, bộ điều phối mint, R4 | Q128–Q138 | `distribution/payment/{PaymentBatchSigning,MintOrchestrator,SupplyCheck}` |
| B9d | VSDC xác nhận sổ, sửa mục | Q139–Q147 | `distribution/register/RegisterAttestationService` |
| B10b | Ký 2/2 phong tỏa, giải tỏa, chuyển cưỡng chế | Q148–Q154 | `governance/enforcement/EnforcementSigning` |
| B11b | Chụp danh sách chốt, VSDC xác nhận | Q155–Q162 | `distribution/payout/SnapshotService` |
| B13b | Đáo hạn, hủy trái phiếu, đóng series | Q163–Q170 | `issuance/series/SeriesMaturity`, `distribution/payout/RedemptionService` |
| B14 | Tạm dừng, mở lại series | Q171–Q177 | `issuance/series/SeriesPause` |

Nhóm trước đó (B0–B8a, B10a, B11a, B12a, B4c) xem `docs/open-questions.md` và `backend/README.md`. Migration tới `V29`. Kiểm thử chuỗi dùng nền `ChainIntegrationTest` → `SeriesChainTest` → `distribution/AllocationChainTest` (dựng sẵn `readySeries`, `approvedList`, `signList`, `mintedSeries`, `activeSeries`, `approvedOrder`, `frozen`); ONCHAINID thử ở `onchain/TestIdentities`.

## 6. Khi CI chạy lần đầu: chỗ dễ lỗi

- Tên lớp sinh từ OpenAPI phải đoán: `SigningProposalPartiesInner`, `ListSigningProposals200Response`, `ListHoldingMovements200Response`, `IsinAssign`, `BidvPayoutConfirmationLinesInner`, `PaymentBatchItemsInner`, `ListPaymentBatches200Response`, `RegisterAttestationHoldersInner`, `RegisterRejectionItemsInner`, `TakeSnapshotRequest`, `RedeemSeries202Response`.
- Lớp hợp đồng sinh từ ABI: `SeriesController.MintItem`, `SeriesController.Snapshot`, các hàm `token()`, `isMinted`, `frozenRedemptionPolicy()`, `burnRedeemed(List<String>)`.
- Mã hóa tay `DynamicStruct` của `deploySeries`; chữ ký Safe lồng EIP-1271.
- `@Import` trên lớp nền trừu tượng `SeriesChainTest` có đăng ký bộ ký thử không.
- SQL cập nhật kết quả lệnh (B7b), dựng sổ từ sao kê (B11b), tìm khối theo thời gian (`SnapshotService.recordBlock`).
- `EnforcementIT` (B10a) dùng series không có controller: sau B10b, lệnh duyệt xong có thể chuyển FAILED bất đồng bộ, có thể phải sửa khẳng định.
- `ApiContractTest` đếm 87 thao tác.
- Mã lỗi `SERIES_PAUSED` (409) chưa ghi trong mô tả OpenAPI; bổ sung ở lần sửa spec kế.

## 7. Đang chờ phiên khác hoặc SA

| Việc | Chờ | Q |
|---|---|---|
| B9b: gửi chuyển OTC lên chuỗi | Phiên contracts thiết kế `TransferRelay` (EIP-712) | Q80 |
| B5b: ONCHAINID, claim, đăng ký ví | Phiên contracts thêm `IdFactory`; phiên WeHSM ký claim | Q23, Q24 |
| Cho chụp lại danh sách chốt trước khi VSDC xác nhận | SA, phiên contracts | Q159 |
| Phân bổ sang "Đã có tiền" khi báo có khớp | SA duyệt ADR-0021 | Q128 |
| `werwa.sh demo mint-tamper` cho cảnh demo | Phiên infra | Q137 |
| Biến `WERWA_SIGNING_SAFEORGANIZATIONS_*`, khóa relayer (D8), Mailpit, giả lập BIDV, chọn kho S3 | Phiên infra | — |
| Định dạng Open API thật của BIDV | BIDV | Q100 |

Ngoài phạm vi XD: khôi phục ví (1.07), `markDefault`/`markCured` (Đ1, hợp đồng chưa có).

## 8. Việc tiếp theo

1. Tommy gộp #73 vào `main` (tắt CI tự động cả repo), dựng runner hoặc sửa thanh toán.
2. Chạy `backend` một lần trên `feat/backend/b14-tam-dung`; đọc annotation; sửa lỗi trên chính nhánh này (hoặc nhánh `fix/backend/…` tách từ nó); chạy lại tới khi qua.
3. Mở PR backend (một PR hoặc theo nhóm), báo Tommy thứ tự gộp sau các PR API.
4. Khi phiên contracts/WeHSM xong Q80, Q23, Q24: làm B9b, B5b theo quy trình mục 4.
