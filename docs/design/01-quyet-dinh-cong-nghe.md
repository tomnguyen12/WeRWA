<!-- Sinh tự động từ trang thiết kế đã duyệt: https://claude.ai/artifact/MhgNcNvRsWq61uYRdoptUu
     Sửa nội dung thiết kế thì sửa ở đây qua PR có SA duyệt; trang HTML chỉ còn là bản lưu. -->

Bước 2 · Đã duyệt · cập nhật 18/10/2026

# Quyết định công nghệ WeRWA

Bộ ADR v1 cho luồng phát hành đợt đầu, phạm vi bản xét duyệt đầu tháng 02/2027 · Tommy Nguyen, Tư vấn độc lập

Bộ ADR lập trực tiếp từ hai trang nghiệp vụ chuẩn (Cấu trúc các bên, Kiến trúc 6 phân hệ). Mỗi ADR ghi bối cảnh, phương án đã cân nhắc, quyết định, hệ quả và chức năng liên quan theo mã n.xx của trang Kiến trúc phân hệ.

**Giả định đang dùng (chốt ngày 16/10, chỉnh lại sau)**

- **Chuyển nhượng OTC đợt đầu (3.08):** chỉ chuyển không kèm thanh toán. Hai bên xác nhận trên WeRWA, thêm ngân hàng lưu ký nếu bên chuyển là ví omnibus. Tiền hai bên tự thanh toán ngoài nền tảng.
- **Phong tỏa (4.02):** VSDC và Holco ký, ngưỡng 2/2.

## Tóm tắt

| ADR           | Chủ đề                      | Quyết định                                                                                                                    | Trạng thái                                                                    | Chức năng          |
|---------------|-----------------------------|-------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------|--------------------|
| [01](#adr-01) | Nền tảng chuỗi              | GĐ1: Hyperledger Besu (QBFT). GĐ2: chain riêng tương thích EVM, Cosmos SDK + EVM hoặc NOWChain                                | Chốt                                         | 6.01               |
| [02](#adr-02) | Topology mạng               | GĐ1 tối thiểu 4 validator, hai node Holco chạy song song; GĐ2 mở rộng thêm bên                                                | Chốt                                         | 6.01 · 4.04 · 4.09 |
| [03](#adr-03) | Chuẩn token                 | ERC-3643 với ONCHAINID; 1 token là 1 trái phiếu                                                                               | Chốt                                         | 6.02 · 6.03        |
| [04](#adr-04) | Hợp đồng theo series        | Bộ hợp đồng riêng cho từng series, sinh từ factory                                                                            | Chốt                                         | 2.07 · 4.01 · 6.02 |
| [05](#adr-05) | Chữ ký nhiều bên            | Mỗi bước ký theo đúng vai trong luồng nghiệp vụ; Holco mint trong danh sách UBND duyệt, VSDC xác nhận sổ mới mở chuyển nhượng | Chốt Giả định | 6.04 · 6.05 · 4.02 |
| [06](#adr-06) | Quản lý khóa                | Mỗi bên giữ khóa của mình; giai đoạn đầu SoftHSM, trước đợt đầu chuyển HSM phần cứng                                          | Chốt                                         | 6.06 · 1.01        |
| [07](#adr-07) | Ví nhà đầu tư               | Ví của L1 hoặc ví bất kỳ tương thích EVM; nhà đầu tư giữ khóa; omnibus cho lưu ký                                             | Chốt                                         | 1.04 · 1.05 · 1.07 |
| [08](#adr-08) | Dữ liệu trên và ngoài chuỗi | Trên chuỗi: số dư, trạng thái, claim, mã băm; ngoài chuỗi: hồ sơ, KYC, sổ lệnh                                                | Chốt                                         | 6.02 · 6.03 · 1.02 |
| [09](#adr-09) | Ghi sổ theo xác nhận IPA    | Mint khi mục có trong danh sách UBND ký và lô xác nhận BIDV ký; trái phiếu khóa tới khi VSDC xác nhận sổ                      | Chốt                                         | 3.03 · 3.05 · 4.09 |
| [10](#adr-10) | Kiến trúc ứng dụng          | Một khối triển khai, 6 mô-đun theo 6 phân hệ (Spring Modulith), sự kiện qua outbox                                            | Chốt                                         | toàn bộ            |
| [11](#adr-11) | Ngăn xếp                    | Java với Spring Boot; giao diện React; Solidity với Foundry; PostgreSQL                                                       | Chốt                                         | toàn bộ            |
| [12](#adr-12) | Đường đọc                   | O1 → indexer → bảng đọc cho portal, báo cáo, công cụ giám sát                                                                 | Chốt                                         | 3.07 · 4.04        |
| [13](#adr-13) | Duyệt và kiểm toán          | Duyệt 4 mắt ngoài chuỗi; nhật ký nối mã băm, neo lên chuỗi hằng ngày                                                          | Chốt                                         | 4.08 · 4.09        |
| [14](#adr-14) | Tích hợp bên ngoài          | BIDV Open API (XD giả lập); VSDC, Kho bạc giai đoạn đầu qua giao diện hoặc tệp; Euroclear/Clearstream để mở rộng sau          | Chốt                                         | 5.01 – 5.04        |
| [15](#adr-15) | Định danh người dùng        | OIDC, MFA bắt buộc, phân quyền theo tổ chức và vai                                                                            | Chốt                                         | 4.07 · 1.01        |
| [16](#adr-16) | Hạ tầng                     | Cloud trong nước, hai vùng (chính và DR); sau chuyển về DC của tập đoàn                                                       | Chốt                                         | phi chức năng      |

Số ADR của bộ v1 không trùng nghĩa với ADR-14, 16, 17 cũ mà mô hình nghiệp vụ v0.7.1 dẫn chiếu. Khi có HLD v0.3, em bổ sung bảng ADR mới thay ADR cũ nào.

## Các quyết định

### ADR-01 — Nền tảng chuỗi

*Chốt 18/10*

- **Bối cảnh:** Sổ đăng ký cần mạng có cấp phép: chỉ các bên được chỉ định chạy node. Mạng phải chạy được ERC-3643 và chốt khối tức thì để ghi nhận quyền sở hữu không bị đảo ngược. Nhà đầu tư không trả phí giao dịch. Về lâu dài WeRWA chạy trên chain riêng của hệ sinh thái.
- **Quyết định:** Hai giai đoạn. GĐ1 dùng Hyperledger Besu với đồng thuận QBFT để phát triển nhanh cho XD và đợt đầu: cấp phép ở mức node và tài khoản, phí gas bằng 0, thời gian khối là tham số (đề xuất 2 giây). GĐ2 chuyển sang chain riêng (L1) tương thích EVM: Cosmos SDK có module EVM, hoặc NOWChain (chain tự phát triển, làm cho đáp ứng các điều kiện dưới đây).
- **Điều kiện GĐ2:**
  - Tương thích EVM ở mức bytecode và JSON-RPC, để giữ nguyên hợp đồng ERC-3643, ví và ứng dụng.
  - Đồng thuận BFT có chốt khối tức thì và cấp phép được validator.
- **Đã cân nhắc:**
  - Đi thẳng chain riêng từ GĐ1: đúng hướng dài hạn, nhưng thêm rủi ro nền tảng mới cho mốc 02/2027.
  - Hyperledger Fabric: không chạy ERC-3643, phải tự viết lại chuẩn token.
  - Chuỗi công khai hoặc L2: không kiểm soát được ai chạy node, trái nguyên tắc sổ thuộc VSDC.
- **Hệ quả:**
  - Mô-đun Onchain chỉ nói chuyện với chuỗi qua JSON-RPC chuẩn EVM, không dùng tính năng riêng của Besu, để đổi chain không sửa nghiệp vụ.
  - Chuyển sang chain riêng là một sự kiện quản trị có quy trình (Hình 1): chụp sổ, VSDC xác nhận, phát hành lại bằng multisig, khóa hợp đồng cũ. Dùng chung cơ chế xuất sổ của 5.06.
  - Không dùng giao dịch riêng tư của Besu. Dữ liệu nhạy cảm để ngoài chuỗi (ADR-08).

![Lộ trình nền tảng chuỗi: GĐ1 Besu QBFT, GĐ2 chain riêng tương thích EVM; chuyển sổ qua bốn bước; hợp đồng ERC-3643, ví EVM và ứng dụng giữ nguyên.](diagrams/01-quyet-dinh-cong-nghe-h1.svg)

***Hình 1.** Đổi chain chỉ thay phần mềm node và đồng thuận. Sổ được chuyển bằng một sự kiện quản trị có VSDC xác nhận, không phải viết lại hợp đồng hay đổi ví của nhà đầu tư.*

### ADR-02 — Topology mạng

*Chốt 18/10*

- **Bối cảnh:** Nghiệp vụ giao vai xác thực cho VSDC, BIDV và Holco. Mạng cần chịu được một node lỗi, và Holco không được tự quyết. Cơ quan giám sát cần dữ liệu thời gian thực nhưng không tham gia đồng thuận.
- **Quyết định:** GĐ1 chạy cấu hình tối thiểu: 4 validator (V1, V2 của Holco ở hai vùng cloud; V3 của BIDV; V4 của VSDC) và mỗi vùng một node chỉ-đọc O1. GĐ2 mở rộng khi có thêm bên được giao vai, thêm theo bội số giữ công thức 3f+1. Ứng dụng không đọc thẳng từ validator. Hằng ngày công cụ giám sát so mã băm khối từ O1 với mã băm VSDC xác nhận từ V4.
- **V1 và V2:** Chạy song song, không phải dự phòng nóng. Đây là hai validator độc lập, mỗi node một khóa riêng, cùng bỏ phiếu mọi khối. Mất cả vùng chính thì V2, V3, V4 vẫn đủ 3/4 nên mạng chạy tiếp. Cấu hình dự phòng nóng (một khóa, hai máy) bị loại vì hai máy cùng khóa có thể ký trùng, và mạng thực chất chỉ còn 3 validator nên không chịu được lỗi nào.
- **GĐ2 mở rộng:**
  - Thêm validator khi có bên mới được giao vai, ví dụ hạ tầng thị trường của VIFC. Lên 7 validator thì chịu được 2 lỗi.
  - Thêm node chỉ-đọc cho sàn hoặc Cơ quan giám sát nếu họ yêu cầu.
  - Thêm hoặc bớt validator là thao tác multisig, ghi nhận trên chuỗi.
- **Hệ quả:**
  - Mỗi khối cần 3/4 validator. Holco giữ 2 nên luôn cần thêm VSDC hoặc BIDV.
  - Khi mất một vùng, mạng còn đúng 3 validator và không chịu thêm lỗi nào. Cảnh báo ngay và thời gian dựng lại validator ghi ở bước 8.
  - Ở môi trường XD, nếu VSDC và BIDV chưa sẵn sàng chạy node, Holco chạy hộ V3, V4 trên hạ tầng tách biệt; trước đợt đầu chuyển về hạ tầng và khóa của hai bên.

![Topology mạng: ứng dụng WeRWA gửi giao dịch tới V1 và V2 của Holco, đọc qua hai node O1; bốn validator V1 V2 V3 V4 cùng đồng thuận QBFT; công cụ giám sát lấy dữ liệu từ O1 và đối chiếu mã băm khối hằng ngày với VSDC.](diagrams/01-quyet-dinh-cong-nghe-h2.svg)

***Hình 2.** Cấu hình GĐ1. Ứng dụng chỉ ghi qua validator của Holco và chỉ đọc qua O1. Công cụ giám sát đối chiếu mã băm khối với VSDC hằng ngày, nên không phụ thuộc hoàn toàn vào dữ liệu Holco cung cấp.*

### ADR-03 — Chuẩn token

*Chốt 18/10*

- **Bối cảnh:** Nghiệp vụ cần chặn nhà đầu tư không đủ điều kiện ngay ở hợp đồng, phong tỏa theo từng vị thế, chuyển cưỡng chế và khôi phục ví (4.01, 4.02, 1.07).
- **Quyết định:** ERC-3643 (T-REX): hợp đồng token, sổ định danh, module tuân thủ và ONCHAINID. Token không chia lẻ (decimals = 0): 1 token là 1 trái phiếu, mệnh giá là tham số của series.
- **Đã cân nhắc:**
  - ERC-1400: ít được dùng, không có chuẩn định danh đi kèm.
  - ERC-20 với whitelist tự viết: phải tự làm phong tỏa một phần, chuyển cưỡng chế và khôi phục ví, tức là viết lại phần ERC-3643 đã có.
- **Hệ quả:**
  - Phong tỏa theo vị thế dùng cơ chế đóng băng một phần số dư; chuyển cưỡng chế và khôi phục ví dùng sẵn hàm của chuẩn. Tất cả chỉ gọi được qua ví multisig (ADR-05).
  - Claim KYC do Holco cấp theo kết quả 1.02; ngân hàng lưu ký có thể được thêm làm bên cấp claim cho khách nhìn xuyên của họ.
  - Claim chỉ chứa loại nhà đầu tư và hạn hiệu lực, không chứa dữ liệu cá nhân.

### ADR-04 — Hợp đồng theo series

*Chốt 18/10*

- **Bối cảnh:** Mỗi đợt có ISIN, điều khoản và đối tượng nhà đầu tư riêng theo Đề án. Lỗi ở một series không được ảnh hưởng series khác.
- **Quyết định:** Factory triển khai một bộ hợp đồng (token và module tuân thủ) cho mỗi series. Sổ định danh dùng chung giữa các series để nhà đầu tư chỉ KYC một lần. Module tuân thủ đọc tham số lấy từ Đề án và term sheet: đối tượng nhà đầu tư, hạn mức sở hữu, khối lượng tối đa.
- **Đã cân nhắc:** Một hợp đồng chứa nhiều series: tiết kiệm triển khai, nhưng một lỗi lan sang mọi series và khó đối chiếu theo ISIN.
- **Hệ quả:**
  - Mỗi ISIN gắn với một địa chỉ hợp đồng, ghi vào hồ sơ series (2.07).
  - Khối lượng tối đa ghi trong hợp đồng là lớp chặn thứ hai sau kiểm tra khung Đề án ngoài chuỗi (2.02).
  - Giả định Chuyển nhượng OTC đợt đầu: trên chuỗi là chuyển thường qua kiểm tra tuân thủ, việc hai bên cùng xác nhận nằm ở ngoài chuỗi.

### ADR-05 — Chữ ký theo vai

*Chốt 18/10 · Giả định một phần*

- **Bối cảnh:** Luồng phát hành đợt đầu (trang Kiến trúc phân hệ, hình 2) có các bước: ký 3 bên triển khai series, VSDC cấp ISIN, chốt lãi suất và phân bổ, BIDV nhận tiền, nhận xác nhận IPA, mint vào ví, VSDC xác nhận sổ. Theo RACI, UBND quyết định lãi suất và khối lượng; VSDC là chủ sổ pháp lý. Holco không được tự tạo trái phiếu.
- **Quyết định:** Mỗi thao tác trên chuỗi do đúng bên giữ vai đó trong luồng nghiệp vụ ký, không thêm bước ký mà nghiệp vụ không có. Holco mint được, nhưng chỉ trong giới hạn ba bên khác đã ký trên chuỗi: khối lượng series (3 bên ký), danh sách phân bổ (UBND ký) và lô xác nhận đã nhận tiền (BIDV ký). Trái phiếu mint ra bị khóa chuyển nhượng cho tới khi VSDC ký xác nhận sổ.
- **Đã cân nhắc:**
  - Ba bên ký 3/3 cho danh sách phân bổ (bản nháp trước): thêm một bước ký mà nghiệp vụ không có; VSDC chỉ xác nhận sổ sau phát hành.
  - Một khóa quản trị của Holco: trái nguyên tắc tách vai.
  - Ký từng lệnh mint: không vận hành được với nhiều nhà đầu tư.
- **Hệ quả:**
  - Holco mint sai (ngoài danh sách, vượt khối lượng, chưa có tiền) thì hợp đồng từ chối. Nếu mint đúng danh sách nhưng sai thực tế, VSDC không xác nhận và trái phiếu vẫn bị khóa.
  - Sửa sai lệch sau khi đã mint: hủy hoặc bổ sung trong phạm vi danh sách UBND đã duyệt; ngoài phạm vi đó phải qua multisig 3 bên.
  - Thao tác chờ khi một bên ký vắng mặt, nên hợp đồng dịch vụ cần thời hạn ký.

| Bước nghiệp vụ                      | Thao tác trên chuỗi                                       | Bên ký                              | Giới hạn hợp đồng kiểm                                                                                                                 | Bản                                            |
|-------------------------------------|-----------------------------------------------------------|-------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------|
| 4 · Ký 3 bên triển khai series      | Triển khai series; hủy series                             | UBND, VSDC, Holco · 3/3             | Tham số series, khối lượng tối đa trong khung Đề án                                                                                    | XD              |
| 5 · VSDC cấp ISIN                   | Gắn ISIN vào series                                       | VSDC · 1                            | Chỉ một lần cho mỗi series                                                                                                             | XD              |
| 7 · Chốt lãi suất, phân bổ          | Ghi lãi suất và gốc danh sách phân bổ                     | UBND · 1, sau duyệt 4 mắt phía UBND | Tổng phân bổ không vượt khối lượng series                                                                                              | XD              |
| 8, 9 · BIDV nhận tiền, xác nhận IPA | Ghi gốc lô đã nhận tiền                                   | BIDV · 1, ký theo lô                | Mỗi mục thuộc một phân bổ đã ký; tham số tắt được nếu BIDV không ký                                                                    | XD khóa giả lập |
| 10 · Mint                           | Mint vào ví nhà đầu tư                                    | Khóa vận hành Holco                 | Mục có trong danh sách UBND ký và lô BIDV ký; số tiền bằng số lượng nhân giá phát hành; mỗi mục một lần; trái phiếu khóa chuyển nhượng | XD              |
| 11 · VSDC xác nhận sổ               | Ký xác nhận sổ sau phát hành; series sang "Đang lưu hành" | VSDC · 1                            | Mở chuyển nhượng cho toàn series                                                                                                       | XD              |
| Đáo hạn, hoàn gốc (2.10)            | Hủy trái phiếu, đóng series                               | Khóa vận hành Holco                 | Chỉ khi series đáo hạn, có xác nhận chi gốc của IPA theo danh sách VSDC đã xác nhận                                                    | XD              |
| Phong tỏa (4.02)                    | Phong tỏa, giải tỏa, chuyển cưỡng chế                     | VSDC, Holco · 2/2                   | Ghi căn cứ lệnh của cơ quan có thẩm quyền                                                                                              | Giả định        |
| Khôi phục ví (1.07)                 | Chuyển số dư sang ví mới                                  | VSDC, Holco · 2/2                   | Ví mới gắn cùng ONCHAINID                                                                                                              | Giả định Đ1     |
| Nâng cấp hợp đồng (6.05)            | Nâng cấp                                                  | UBND, VSDC, Holco · 3/3             | Qua timelock, báo trước                                                                                                                | Đ1                                             |

### ADR-06 — Quản lý khóa

*Chốt 18/10*

- **Bối cảnh:** Chữ ký nhiều bên chỉ có ý nghĩa khi mỗi bên thực sự giữ khóa của mình.
- **Quyết định:** Khóa ký của UBND, VSDC và BIDV do chính bên đó giữ. Holco cung cấp ứng dụng ký nhưng không giữ khóa của bên khác. Giai đoạn đầu dùng SoftHSM cho khóa vận hành Holco và khóa validator; ứng dụng ký qua giao diện PKCS#11 nên khi đổi sang HSM phần cứng hoặc HSM của nhà cung cấp cloud chỉ đổi cấu hình. Trước đợt phát hành đầu phải chuyển sang HSM phần cứng, có lễ tạo khóa, xoay vòng và thu hồi (6.06).
- **Đã cân nhắc:** Holco giữ hộ khóa của các bên: rẻ và dễ triển khai, nhưng biến multisig thành một bên duy nhất.
- **Hệ quả:** SoftHSM lưu khóa dưới dạng tệp mã hóa, không chống được người có quyền quản trị máy, nên chỉ dùng cho môi trường XD và thử nghiệm, không dùng khi có tài sản thật. KYB tổ chức (1.01) gồm đăng ký địa chỉ ký và danh sách người ký được ủy quyền của từng bên. Thay người ký là thao tác multisig.

### ADR-07 — Ví nhà đầu tư

*Chốt 18/10*

- **Bối cảnh:** Theo quy tắc tách vai, bên vận hành không giữ sổ pháp lý, không giữ tiền. Giữ khóa thay nhà đầu tư tức là giữ hộ chứng khoán.
- **Quyết định:** Nhà đầu tư dùng ví của L1 hoặc ví bất kỳ tương thích EVM. Ví chỉ giao dịch được trái phiếu sau khi địa chỉ được gắn với ONCHAINID đã có claim KYC. Nhà đầu tư tổ chức có thể nắm giữ qua ví omnibus của ngân hàng lưu ký (1.05, Đ1). Bản XD dùng ví thử.
- **Giả định:** Giả định Ví của L1 là ví không giữ hộ: khóa nằm ở phía nhà đầu tư, Holco không giữ khóa thay. Nếu ví của L1 có chế độ giữ hộ thì cần quyết định riêng vì đụng quy tắc tách vai.
- **Đã cân nhắc:** Ví lưu ký do Holco giữ: dễ cho nhà đầu tư, nhưng Holco thành bên giữ hộ.
- **Hệ quả:**
  - Vì dùng chuẩn EVM, ví giữ nguyên khi chuyển từ Besu sang chain riêng (ADR-01).
  - Mất khóa xử lý bằng khôi phục ví qua multisig (ADR-05).
  - Với ví omnibus, ngân hàng lưu ký chịu KYC nhìn xuyên và chia coupon cho khách của họ.

### ADR-08 — Dữ liệu trên và ngoài chuỗi

*Chốt 18/10*

- **Bối cảnh:** Sổ trên chuỗi là nguồn gốc của quyền sở hữu, nhưng không xóa được. Dữ liệu cá nhân và sổ lệnh trong lúc dựng sổ phải được bảo mật.
- **Quyết định:** Trên chuỗi: số dư, trạng thái phong tỏa, claim, tham số tuân thủ, sự kiện series và mã băm của tài liệu (term sheet, quyết định UBND, danh sách phân bổ, danh sách chốt). Ngoài chuỗi: hồ sơ KYC, tài liệu gốc, sổ lệnh, dữ liệu ngân hàng, nhật ký duyệt.
- **Đã cân nhắc:** Ghi sổ lệnh lên chuỗi: minh bạch hơn, nhưng lộ giá và tên nhà đầu tư cho mọi bên chạy node trong lúc dựng sổ.
- **Hệ quả:** Mô hình dữ liệu chi tiết ở bước 3. Tài liệu ngoài chuỗi kiểm được tính toàn vẹn bằng mã băm trên chuỗi.

### ADR-09 — Ghi sổ theo xác nhận IPA

*Chốt 18/10*

- **Bối cảnh:** Đã chốt: đợt đầu chưa thanh toán trên WeRWA. Tiền đi theo cơ chế thanh toán của đợt; WeRWA chỉ ghi sổ khi BIDV xác nhận đã nhận đủ. VSDC xác nhận sổ sau phát hành.
- **Quyết định:** Mint chỉ chạy khi đủ ba điều kiện, hợp đồng tự kiểm cả ba: mục nằm trong danh sách phân bổ UBND đã ký; mục nằm trong một lô xác nhận BIDV đã ký với số tiền khớp; mục đó chưa được mint. Trái phiếu mint ra bị khóa chuyển nhượng cho tới khi VSDC ký xác nhận sổ. Xác nhận IPA vào qua mô-đun Tích hợp, được kiểm tra nguồn và chặn trùng theo mã tham chiếu ngân hàng.
- **Đã cân nhắc:** Mint ngay khi chốt phân bổ, hủy nếu không có tiền: đơn giản hơn, nhưng sổ có thời điểm ghi trái phiếu chưa được thanh toán.
- **Hệ quả:**
  - Phân bổ chưa có tiền giữ ở trạng thái chờ và báo tổ hợp; việc hủy hay chuyển phân bổ theo chức năng 3.04 (Đ1).
  - Đợt sau thay xác nhận IPA bằng DvP (3.12) mà không phải đổi hợp đồng token.

![Luồng ghi sổ đợt đầu: tổ hợp đề xuất phân bổ, UBND ký danh sách phân bổ lên chuỗi; BIDV nhận USD ngoài nền tảng, Tích hợp nhận xác nhận IPA, Phân phối đối chiếu đủ tiền rồi Holco mint vào ví ở trạng thái khóa; thiếu tiền thì giữ chờ; VSDC ký xác nhận sổ, series mở chuyển nhượng, sau đó đối soát.](diagrams/01-quyet-dinh-cong-nghe-h3.svg)

***Hình 3.** Ô cam là việc bên ngoài làm, ô tím là bước của Quản trị, ô xanh là bước trên chuỗi. Mỗi bước do đúng bên giữ vai trong luồng nghiệp vụ ký: UBND duyệt danh sách, BIDV xác nhận tiền, Holco mint trong giới hạn đó, VSDC ký xác nhận sổ thì trái phiếu mới chuyển nhượng được.*

### ADR-10 — Kiến trúc ứng dụng

*Chốt 18/10*

- **Bối cảnh:** 6 phân hệ có ranh giới rõ, 32 chức năng XD phải chạy trước đầu tháng 02/2027 với một đội. Về sau có thể cần tách riêng Tích hợp hoặc Onchain.
- **Quyết định:** Một khối triển khai chia 6 mô-đun theo 6 phân hệ; Quản trị là lớp dùng chung cho quyền, duyệt và nhật ký. Mô-đun không đọc bảng của nhau, chỉ gọi qua giao diện công khai hoặc sự kiện. Sự kiện ghi vào outbox trong cùng giao dịch cơ sở dữ liệu rồi mới phát đi, để không mất sự kiện khi lỗi giữa chừng. Dùng Spring Modulith để kiểm ranh giới mô-đun khi build và làm sổ đăng ký sự kiện.
- **Đã cân nhắc:** Microservices ngay từ đầu: tách triển khai độc lập, nhưng tốn công vận hành và giao dịch phân tán khi đội còn nhỏ và hạn gần.
- **Hệ quả:** Hợp đồng sự kiện giữa mô-đun (bước 6) là ranh giới thật, nên tách một mô-đun thành dịch vụ sau này không đổi hợp đồng.

![Kiến trúc ứng dụng: giao diện các bên gọi vào một khối ứng dụng gồm 6 mô-đun; ứng dụng ghi PostgreSQL, gửi giao dịch lên mạng Besu; indexer đọc sự kiện từ O1 vào bảng đọc; Tích hợp gọi BIDV, VSDC, Kho bạc, KYC.](diagrams/01-quyet-dinh-cong-nghe-h4.svg)

***Hình 4.** Mỗi phân hệ là một mô-đun trong cùng khối triển khai. Ghi đi qua mô-đun Onchain tới validator của Holco; đọc đi qua O1 và indexer vào bảng đọc.*

### ADR-11 — Ngăn xếp công nghệ

*Chốt 18/10*

- **Bối cảnh:** Đội phát triển dùng Java/Spring. Cần thư viện EVM ổn định và hợp với ngân hàng, VSDC khi tích hợp.
- **Quyết định:** Backend Java 21 với Spring Boot và Spring Modulith; thư viện EVM web3j; PostgreSQL; broker nhẹ cho sự kiện (đề xuất NATS JetStream). Giao diện React với TypeScript. Hợp đồng Solidity, kiểm thử bằng Foundry.
- **Đã cân nhắc:** TypeScript với NestJS: một ngôn ngữ cho backend và giao diện, nhưng đội đã quen Java.
- **Hệ quả:** Lớp Java gọi hợp đồng sinh tự động từ ABI bằng web3j. Quy ước repo và CLAUDE.md ở bước 8.

### ADR-12 — Đường đọc

*Chốt 18/10*

- **Bối cảnh:** Portal, sao kê (3.07), báo cáo và công cụ giám sát (4.04) cần truy vấn nhanh mà không đè tải lên node.
- **Quyết định:** Indexer đọc sự kiện từ O1 và ghi vào bảng đọc trong PostgreSQL. Màn hình và báo cáo đọc bảng đọc; khi cần bằng chứng thì đọc trực tiếp trạng thái trên O1.
- **Đã cân nhắc:** Gọi node cho mỗi truy vấn: đơn giản, nhưng chậm với báo cáo theo nhà đầu tư và theo ngày.
- **Hệ quả:** QBFT chốt khối tức thì nên indexer không phải xử lý đảo khối. Đối soát hằng ngày (4.09) so bảng đọc với trạng thái chuỗi.

### ADR-13 — Duyệt và kiểm toán

*Chốt 18/10*

- **Bối cảnh:** Lớp duyệt thứ nhất là duyệt 4 mắt trên nền tảng (4.08). Nhật ký kiểm toán phải không sửa được và Cơ quan giám sát kiểm được.
- **Quyết định:** Duyệt 4 mắt ngoài chuỗi, người lập và người duyệt là hai tài khoản khác nhau; duyệt xong mới tạo đề nghị multisig. Nhật ký kiểm toán nối mã băm: mỗi bản ghi chứa mã băm của bản trước. Mỗi ngày neo mã băm cuối lên chuỗi.
- **Đã cân nhắc:** Ghi từng bản ghi nhật ký lên chuỗi: chắc chắn hơn, nhưng đưa dữ liệu nội bộ lên sổ mà mọi node đều đọc được.
- **Hệ quả:** Sửa nhật ký sau khi neo sẽ lộ ra khi đối chiếu. Xuất nhật ký cho Cơ quan giám sát kèm bằng chứng neo.

### ADR-14 — Tích hợp bên ngoài

*Chốt 18/10*

- **Bối cảnh:** Trang Kiến trúc phân hệ xếp BIDV giả lập ở XD, kết nối VSDC và Kho bạc ở Đ1, không dùng SWIFT.
- **Quyết định:** Mỗi bên ngoài có một adapter riêng trong mô-đun Tích hợp, có chặn trùng và thử lại.
- **Phạm vi:**
  - **BIDV (5.02):** báo có, lệnh chi, sao kê qua Open API. Bản XD dùng giả lập có cùng hợp đồng API để đổi sang thật không sửa nghiệp vụ.
  - **VSDC (5.01):** bản XD nhập ISIN và xác nhận qua giao diện của VSDC; Đ1 kết nối hệ thống.
  - **Kho bạc (5.03):** tệp hoặc nhập tay.
  - **KYC và cấm vận (5.04):** qua adapter để đổi nhà cung cấp được.
  - **Euroclear/Clearstream (5.05):** chỉ mở rộng sau, hiện không làm. Ví omnibus (ADR-07) đã đủ chỗ để nối về sau.
- **Hệ quả:** Hợp đồng API với BIDV cần chốt sớm (việc C3 trong Tiến độ) để giả lập không lệch với API thật.

### ADR-15 — Định danh người dùng

*Chốt 18/10*

- **Bối cảnh:** Mọi bên dùng nền tảng qua giao diện; quyền theo tổ chức và vai (4.07).
- **Quyết định:** OIDC với máy chủ định danh tự vận hành (đề xuất Keycloak). MFA bắt buộc với mọi người dùng. Phân quyền theo tổ chức và vai. Người ký multisig gắn với người dùng đã qua KYB của tổ chức (1.01).
- **Đã cân nhắc:** Dịch vụ định danh trên cloud nước ngoài: nhanh triển khai, nhưng dữ liệu người dùng nằm ngoài Việt Nam.
- **Hệ quả:** Từ Đ1 có thể nối đăng nhập một lần với hệ thống của từng tổ chức.

### ADR-16 — Hạ tầng

*Chốt 18/10*

- **Bối cảnh:** Topology cần hai vị trí độc lập cho Holco. Dữ liệu cá nhân của nhà đầu tư cần lưu tại Việt Nam.
- **Quyết định:** Trước mắt dùng cloud trong nước, hai vùng hoặc hai trung tâm dữ liệu của nhà cung cấp: vùng 1 chạy chính, vùng 2 làm DR. Sau này chuyển về DC của tập đoàn. Node của BIDV và VSDC đặt tại hạ tầng của họ.
- **Yêu cầu với nhà cung cấp:**
  - Hai vị trí cách biệt về điện, mạng.
  - Có HSM hoặc KMS dùng HSM cho khóa vận hành và validator từ Đ1.
  - Kubernetes và PostgreSQL có sao chép giữa hai vị trí.
- **Hệ quả:**
  - Triển khai bằng container và hạ tầng dạng mã để chuyển từ cloud về DC tập đoàn không phải viết lại.
  - Chuyển hạ tầng làm từng validator một, nên mạng luôn còn đủ 3/4.
  - Mục tiêu thời gian khôi phục và mất dữ liệu tối đa ghi ở bước 8.

## Còn mở

- **Ba dòng ở trang nghiệp vụ cần sửa** Euroclear/Clearstream "ở Go-live" thành "mở rộng sau"; vai của Cơ quan giám sát "node quan sát" thành "công cụ giám sát"; dòng RACI "Thu tiền, DvP, ghi sổ" bỏ chữ DvP.
- **Cập nhật sau** Nhà cung cấp cloud trong nước (ADR-16) và thời điểm chuyển từ SoftHSM sang HSM phần cứng (ADR-06).

Căn cứ: trang Cấu trúc các bên WeRWA (15/10/2026) và Kiến trúc phân hệ WeRWA (16/10/2026); mô hình nghiệp vụ v0.7.1 để tham khảo. Mã n.xx là mã chức năng của trang Kiến trúc phân hệ. Bước tiếp theo: mô hình dữ liệu nghiệp vụ, trên chuỗi và ngoài chuỗi.
