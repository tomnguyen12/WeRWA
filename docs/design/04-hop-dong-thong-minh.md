<!-- Sinh tự động từ trang thiết kế đã duyệt: https://claude.ai/artifact/S4NCGvthaa99dRHpBGKU8e
     Sửa nội dung thiết kế thì sửa ở đây qua PR có SA duyệt; trang HTML chỉ còn là bản lưu. -->

Bước 5 · Đã duyệt · 19/10/2026

# Hợp đồng thông minh WeRWA

Đặc tả hợp đồng cho luồng phát hành đợt đầu trên Besu, chuẩn ERC-3643 · Tommy Nguyen, Tư vấn độc lập

Đặc tả theo ADR v1, mô hình dữ liệu và máy trạng thái đã duyệt. Phần chuẩn ERC-3643 dùng nguyên bản tham chiếu; WeRWA thêm hợp đồng điều khiển series, factory và ba module tuân thủ. Tên hàm, sự kiện và lỗi ở đây là tên dùng trong code.

## Quyết định ngày 19/10

| Chủ đề          | Quyết định                                                                                       | Việc kèm theo                                                                                                                 |
|-----------------|--------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| Nền mã ERC-3643 | Dùng bản tham chiếu T-REX, ghim một phiên bản cố định                                            | Pháp lý kiểm giấy phép GPL-3.0 trước Đ1. Nếu không chấp nhận, tự viết theo giao diện ERC-3643; phần mở rộng WeRWA giữ nguyên. |
| Ký của tổ chức  | Mỗi tổ chức có một ví multisig (Safe) gồm những người ký được ủy quyền, ngưỡng do tổ chức tự đặt | Multisig liên bên (3/3, 2/2) có thành viên là các Safe của tổ chức. Đổi người ký không phải đổi hợp đồng series.              |
| Nâng cấp        | Hợp đồng sau proxy, nâng cấp qua timelock                                                        | Multisig 3/3 lên lịch; Safe của từng tổ chức có quyền hủy lịch. Thời gian chờ ngắn ở XD, đề xuất 7 ngày trước đợt đầu.        |

## Hợp đồng và vai

![Quan hệ hợp đồng và vai: sáu vai gọi vào hợp đồng điều khiển series; điều khiển series là agent duy nhất của token; token hỏi module tuân thủ và sổ định danh; timelock nâng cấp proxy.](diagrams/04-hop-dong-thong-minh-h1.svg)

***Hình 1.** Điều khiển series là agent duy nhất của token, nên không vai nào thao tác thẳng lên token. GOVERNANCE còn gọi `cancel` và `setPaymentAttestationRequired` trên điều khiển series. Timelock do multisig 3/3 lên lịch; Safe của UBND, VSDC hoặc Holco hủy được lịch đã đặt.*

| Hợp đồng                            | Vai trò                                                                                                                  | Nguồn     | Nâng cấp                                      |
|-------------------------------------|--------------------------------------------------------------------------------------------------------------------------|-----------|-----------------------------------------------|
| **Dùng chung**                      |                                                                                                                          |           |                                               |
| Sổ định danh, lưu trữ sổ định danh  | Ví → ONCHAINID, mã nước; dùng chung cho mọi series để KYC một lần                                                        | T-REX     | Theo cơ chế của T-REX, chủ sở hữu là timelock |
| Chủ đề claim, bên cấp claim tin cậy | Chủ đề KYC_INSTITUTION, INVESTOR_CLASS; bên cấp: Holco                                                                   | T-REX     | như trên                                      |
| ONCHAINID                           | Một định danh cho mỗi nhà đầu tư tổ chức, giữ claim                                                                      | ONCHAINID | Không                                         |
| `SeriesFactory`                     | Triển khai trọn bộ hợp đồng của một series trong một giao dịch                                                           | WeRWA     | Proxy, timelock                               |
| `AuditAnchor`                       | Ngày → mã băm cuối của nhật ký kiểm toán; chỉ OPERATOR ghi, mỗi ngày một lần                                             | WeRWA     | Không                                         |
| **Theo từng series**                |                                                                                                                          |           |                                               |
| Token                               | Số dư, phong tỏa một phần, chuyển cưỡng chế, khôi phục ví; agent duy nhất là điều khiển series                           | T-REX     | Theo T-REX, chủ sở hữu là timelock            |
| Modular compliance                  | Gắn ba module ở bảng dưới                                                                                                | T-REX     | như trên                                      |
| `SeriesController`                  | Máy trạng thái series, danh sách phân bổ, lô BIDV, mint có hai đường chứng minh, xác nhận sổ, chụp sổ, đáo hạn, tuân thủ | WeRWA     | Proxy, timelock                               |

### Module tuân thủ

| Module                | Luật                                                                                                                                     | Ghi chú                        |
|-----------------------|------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------|
| `InvestorClassModule` | Ví nhận có claim INVESTOR_CLASS hợp lệ, do bên cấp claim tin cậy ký, mã loại nằm trong danh sách series cho phép, chưa hết hạn           | Áp cho mint và chuyển nhượng   |
| `HoldingLimitModule`  | Số dư sau giao dịch ≤ holdingLimit của series (0 là không giới hạn). Ví omnibus được miễn; hạn mức theo chủ sở hữu thật kiểm ngoài chuỗi | Áp cho mint và chuyển nhượng   |
| `TransferGateModule`  | Chỉ cho chuyển nhượng khi điều khiển series ở ACTIVE hoặc DEFAULTED và VSDC đã xác nhận sổ. Mint (from = 0) và burn đi qua               | Đọc state từ điều khiển series |

## Trình tự gọi khi phát hành đợt đầu

![Trình tự gọi hợp đồng cho phát hành đợt đầu, từ triển khai series tới chuyển nhượng.](diagrams/04-hop-dong-thong-minh-h2.svg)

***Hình 2.** Mỗi bên tự gửi giao dịch từ Safe hoặc khóa của mình (phí gas bằng 0). Bước 5 lặp theo từng lô tiền về; bước 6 và 7 lặp theo từng phân bổ. Từ bước 8 trái phiếu mới chuyển nhượng được.*

## Đặc tả hàm của điều khiển series

Hàm đổi trạng thái đều phát sự kiện, kể cả khi trạng thái không đổi. Mọi lỗi dùng custom error của Solidity. Kiểu số lượng là `uint64` (đủ cho số trái phiếu), tiền là `uint256` theo đơn vị nhỏ nhất, lãi suất là `uint32` theo phần triệu.

| Hàm                                                                                           | Vai                      | Trạng thái cho phép        | Kiểm tra                                                                                                                                                                                                                                 | Kết quả · sự kiện                                               | Lỗi                                                                                                       |
|-----------------------------------------------------------------------------------------------|--------------------------|----------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| **Phát hành**                                                                                 |                          |                            |                                                                                                                                                                                                                                          |                                                                 |                                                                                                           |
| `initialize(SeriesTerms)`                                                                     | Factory                  | —                          | Gắn token, compliance, sổ định danh; lưu terms, termsHash, maxQuantity, khung lãi suất, ngày đáo hạn, số kỳ; gán vai                                                                                                                     | → DEPLOYED · SeriesDeployed                                     | AlreadyInitialized                                                                                        |
| `setIsin(bytes12 isin)`                                                                       | REGISTRAR                | DEPLOYED                   | ISIN chưa gán; đúng 12 ký tự                                                                                                                                                                                                             | → READY · IsinAssigned                                          | IsinAlreadySet                                                                                            |
| `setAllocation(bytes32 root, bytes32 listHash, uint64 totalQuantity, uint32[] periodRatePpm)` | ISSUER                   | READY                      | totalQuantity ≤ maxQuantity; số phần tử lãi suất = số kỳ; mỗi lãi suất trong khung                                                                                                                                                       | → ALLOCATED · AllocationSet                                     | ExceedsMaxQuantity, RateOutOfRange, PeriodCountMismatch                                                   |
| `addPaymentBatch(bytes32 root, bytes32 batchHash)`                                            | PAYING_AGENT             | ALLOCATED, ISSUING         | Gốc lô chưa có                                                                                                                                                                                                                           | PaymentBatchAdded                                               | PaymentRootExists                                                                                         |
| `mint(MintItem item, bytes32[] allocProof, bytes32 paymentRoot, bytes32[] paymentProof)`      | OPERATOR                 | ALLOCATED, ISSUING         | Chưa mint allocationId; chứng minh phân bổ đúng với allocationRoot; nếu bật điều kiện BIDV: paymentRoot đã có, chứng minh đúng, amountMinor = quantity × faceValue × issuePrice; totalMinted + quantity ≤ totalAllocated; ví đã xác minh | Token mint; ALLOCATED → ISSUING ở mục đầu · Minted              | AlreadyMinted, InvalidAllocationProof, InvalidPaymentProof, AmountMismatch, ExceedsAllocated, NotVerified |
| `correctionBurn(bytes32 allocationId, address wallet, uint64 quantity)`                       | OPERATOR                 | ISSUING                    | allocationId đã mint vào đúng ví với đúng số lượng                                                                                                                                                                                       | Token burn; mở lại allocationId để mint đúng · CorrectionBurned | NotMinted, MismatchedItem                                                                                 |
| `attestRegister(bytes32 registerHash, uint64 atBlock)`                                        | REGISTRAR                | ISSUING                    | atBlock ≤ block hiện tại                                                                                                                                                                                                                 | → ACTIVE; mở chuyển nhượng · RegisterAttested                   | BadState                                                                                                  |
| `cancel(bytes32 decisionHash)`                                                                | GOVERNANCE               | DEPLOYED, READY, ALLOCATED | totalMinted = 0                                                                                                                                                                                                                          | → CANCELLED · SeriesCancelled                                   | AlreadyMinted                                                                                             |
| **Lưu hành và trả nợ**                                                                        |                          |                            |                                                                                                                                                                                                                                          |                                                                 |                                                                                                           |
| `takeSnapshot(uint16 period, uint64 recordBlock, bytes32 registerHash)`                       | OPERATOR                 | ACTIVE, DEFAULTED          | Kỳ hợp lệ, chưa chụp; recordBlock ≤ block hiện tại                                                                                                                                                                                       | SnapshotTaken                                                   | SnapshotExists                                                                                            |
| `attestSnapshot(uint16 period, bytes32 registerHash)`                                         | REGISTRAR                | ACTIVE, DEFAULTED          | Mã băm khớp bản Holco đã ghi                                                                                                                                                                                                             | SnapshotAttested                                                | HashMismatch                                                                                              |
| `markDefault(uint16 period, bytes32 basisHash)`                                               | OPERATOR                 | ACTIVE                     | Kỳ đã qua ngày trả và hết ân hạn (Đ1)                                                                                                                                                                                                    | → DEFAULTED · SeriesDefaulted                                   | GraceNotOver                                                                                              |
| `markCured(uint16 period, bytes32 basisHash)`                                                 | OPERATOR                 | DEFAULTED                  | Đ1                                                                                                                                                                                                                                       | → ACTIVE · DefaultCured                                         | BadState                                                                                                  |
| `mature(bytes32 payoutHash)`                                                                  | OPERATOR                 | ACTIVE, DEFAULTED          | Đã tới ngày đáo hạn; danh sách kỳ gốc đã được VSDC xác nhận                                                                                                                                                                              | → MATURED; chặn chuyển nhượng · SeriesMatured                   | NotYetMatured, SnapshotNotAttested                                                                        |
| `burnRedeemed(address[] holders)`                                                             | OPERATOR                 | MATURED                    | Hủy toàn bộ số dư không bị phong tỏa của từng ví                                                                                                                                                                                         | Token burn · Redeemed                                           | —                                                                                                         |
| `close()`                                                                                     | OPERATOR                 | MATURED                    | Tổng cung = 0                                                                                                                                                                                                                            | → CLOSED · SeriesClosed                                         | SupplyNotZero                                                                                             |
| **Tuân thủ**                                                                                  |                          |                            |                                                                                                                                                                                                                                          |                                                                 |                                                                                                           |
| `freeze(address wallet, uint64 qty, bytes32 orderHash)`                                       | ENFORCEMENT              | ACTIVE, DEFAULTED, MATURED | qty ≤ phần chưa phong tỏa                                                                                                                                                                                                                | Token freezePartialTokens · PositionFrozen                      | ExceedsBalance                                                                                            |
| `unfreeze(address wallet, uint64 qty, bytes32 orderHash)`                                     | ENFORCEMENT              | như trên                   | qty ≤ phần đang phong tỏa                                                                                                                                                                                                                | PositionUnfrozen                                                | ExceedsFrozen                                                                                             |
| `forcedTransfer(address from, address to, uint64 qty, bytes32 orderHash)`                     | ENFORCEMENT              | như trên                   | Ví nhận đã xác minh                                                                                                                                                                                                                      | Token forcedTransfer · ForcedTransfer                           | NotVerified                                                                                               |
| `recoverWallet(address lost, address newWallet, address onchainId, bytes32 basisHash)`        | ENFORCEMENT              | ACTIVE, DEFAULTED          | Ví mới gắn cùng ONCHAINID (Đ1)                                                                                                                                                                                                           | Token recoveryAddress · WalletRecovered                         | IdentityMismatch                                                                                          |
| `pause() / unpause()`                                                                         | ENFORCEMENT / GOVERNANCE | mọi trạng thái trừ CLOSED  | Dừng khẩn cấp do 2 bên; chỉ 3 bên mở lại                                                                                                                                                                                                 | Paused / Unpaused                                               | —                                                                                                         |
| `setPaymentAttestationRequired(bool)`                                                         | GOVERNANCE               | trước ACTIVE               | Tắt điều kiện BIDV nếu BIDV không ký                                                                                                                                                                                                     | PaymentAttestationToggled                                       | BadState                                                                                                  |

### Cấu trúc dữ liệu đầu vào

    struct SeriesTerms {
      bytes32 seriesId;          // từ UUID của series
      bytes32 termsHash;         // SHA-256 của term sheet đã duyệt
      bytes3  currency;          // "USD"
      uint256 faceValueMinor;    // mệnh giá, đơn vị nhỏ nhất
      uint32  issuePricePpm;     // giá phát hành, 1 000 000 = 100%
      uint64  maxQuantity;       // khối lượng tối đa
      uint32  rateFloorPpm;      // khung lãi suất của Đề án
      uint32  rateCapPpm;
      uint16  periodCount;       // số kỳ coupon
      uint64  maturityDate;      // unix time, ngày đáo hạn đã điều chỉnh
      uint16[] investorClasses;  // loại nhà đầu tư được phép
      uint64  holdingLimit;      // 0 = không giới hạn
      address issuer; address registrar; address payingAgent;
      address operator; address enforcement; address governance;
    }

    struct MintItem {
      bytes32 allocationId;      // khớp lá phân bổ và lá lô BIDV
      address wallet;
      uint64  quantity;
      uint256 amountMinor;       // tiền đã nhận theo lô BIDV
      bytes32 bankRefHash;       // keccak256 mã tham chiếu ngân hàng
    }

Cách tính lá phân bổ và lá lô BIDV theo trang Mô hình dữ liệu, mục "Mã băm lá".

## Kiểm thử bất biến

Viết bằng Foundry invariant test, chạy trên mọi lần commit. Mỗi điều tương ứng một ràng buộc của mô hình dữ liệu.

**I1**totalMinted ≤ totalAllocated ≤ maxQuantity ở mọi thời điểm (R2).

**I2**Mỗi allocationId có tối đa một lần mint còn hiệu lực (R3).

**I3**Tổng cung của token bằng totalMinted trừ số đã hủy (R4).

**I4**Không chuyển nhượng nào thành công khi state không phải ACTIVE hoặc DEFAULTED, hoặc khi chưa có xác nhận sổ của VSDC.

**I5**Trạng thái chỉ đổi theo các cạnh của máy Series; mọi hàm đổi trạng thái chỉ chạy với đúng vai.

**I6**Số lượng phong tỏa của mỗi ví không vượt số dư của ví đó.

**I7**Không ví chưa xác minh nào nhận được trái phiếu, kể cả qua chuyển cưỡng chế.

**I8**CANCELLED chỉ đạt được khi totalMinted = 0.

## Yêu cầu bảo mật

- Ghim phiên bản trình biên dịch Solidity theo bản T-REX đã ghim; bật optimizer với cấu hình cố định để kiểm lại được bytecode.
- Không nhận tiền trên hợp đồng, không gọi hợp đồng ngoài ngoài bộ đã liệt kê, không dùng `tx.origin`, `delegatecall` tự do hay `selfdestruct`.
- Hợp đồng nâng cấp được giữ khoảng trống lưu trữ, có kiểm tra bố cục lưu trữ khi nâng cấp.
- Phân tích tĩnh bằng Slither trong CI; độ phủ kiểm thử dòng của phần WeRWA tối thiểu 95%.
- Kiểm toán bảo mật bởi đơn vị độc lập trước Đ1, sau khi chốt mã. Bản XD chạy trên mạng thử, không có tài sản thật.

## Còn mở

- **Giấy phép GPL-3.0 của T-REX** GPL chỉ buộc công bố mã nguồn khi chuyển giao bản sao cho bên khác. Holco tự vận hành nền tảng nên phần ứng dụng (Java, React, cơ sở dữ liệu) và phần mềm node không bị ảnh hưởng. Điểm cần pháp lý xác nhận: triển khai hợp đồng lên node của BIDV và VSDC có tính là chuyển giao không. Nếu có, hệ quả xấu nhất là cung cấp mã nguồn phần hợp đồng (T-REX và phần WeRWA dựa trên nó) theo GPL cho các bên đó, phù hợp với yêu cầu minh bạch của sổ nợ công. GPL chắc chắn áp dụng cho phần hợp đồng khi Holco giao mã cho bên khác: VSDC hoặc tổ chức lưu ký nhận lại vận hành, cấp phép nền tảng cho địa phương khác, đối tác phát triển ngoài Holco. Thiết kế giữ phần WeRWA là hợp đồng riêng, gọi T-REX qua giao diện chuẩn, không sửa mã T-REX. Câu hỏi cho pháp lý: (1) triển khai lên node bên khác có là chuyển giao không; (2) Holco có cần giữ kín mã hợp đồng mở rộng không. Không chặn việc xây bản XD.

Căn cứ: Quyết định công nghệ WeRWA, Mô hình dữ liệu WeRWA (duyệt 18/10/2026), Máy trạng thái WeRWA (duyệt 19/10/2026). Bước tiếp theo: hợp đồng API và sự kiện giữa các phân hệ.
