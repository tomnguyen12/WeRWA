<!-- Sinh tự động từ trang thiết kế đã duyệt: https://claude.ai/artifact/6CjQmJAPmKiFu5h1vsE2q5
     Sửa nội dung thiết kế thì sửa ở đây qua PR có SA duyệt; trang HTML chỉ còn là bản lưu. -->

Bước 4 · Đã duyệt · 19/10/2026

# Máy trạng thái WeRWA

Series, lệnh, phân bổ, kỳ trả nợ và phong tỏa cho luồng phát hành đợt đầu · Tommy Nguyen, Tư vấn độc lập

Năm máy trạng thái theo khung đã duyệt ngày 18/10, dựa trên ADR v1 và mô hình dữ liệu. Mỗi mũi tên mang một số; bảng ngay dưới sơ đồ ghi điều kiện, bên thực hiện và bước đó có ký trên chuỗi hay không. Mã trạng thái tiếng Anh dùng thẳng trong code và trong hợp đồng.

## Quy tắc chung

1.  Trạng thái có ghi trên chuỗi chỉ đổi ở ngoài chuỗi sau khi giao dịch đã lên khối và indexer đã đọc được sự kiện. Giao dịch lỗi hoặc bị hợp đồng từ chối thì giữ nguyên trạng thái và sinh cảnh báo.
2.  Mỗi lần chuyển trạng thái ghi một dòng nhật ký kiểm toán: người thực hiện, căn cứ, mã băm nội dung.
3.  Hợp đồng từ chối mọi chuyển trạng thái không có trong sơ đồ, nên không ai đi tắt được, kể cả Holco.

![Quy tắc ghi: yêu cầu chuyển trạng thái qua duyệt 4 mắt, đề nghị ký, giao dịch lên khối, indexer ghi sự kiện rồi mới cập nhật trạng thái ngoài chuỗi; nếu giao dịch lỗi hoặc bị hợp đồng từ chối thì giữ nguyên trạng thái và cảnh báo.](diagrams/03-may-trang-thai-h1.svg)

***Hình 1.** Đường đi của mọi chuyển trạng thái cần ký trên chuỗi. Trạng thái ngoài chuỗi chỉ cập nhật ở bước cuối, sau khi chuỗi đã ghi.*

## Series · series.status · SeriesController.state

![Máy trạng thái series từ Nháp tới Đã đóng, có nhánh Hủy đợt trước khi mint và nhánh Vi phạm khi chậm trả quá ân hạn.](diagrams/03-may-trang-thai-h2.svg)

***Hình 2.** Đang chào bán và Đã đóng sổ chỉ có ở ngoài chuỗi; trên chuỗi series vẫn là READY. Trái phiếu chỉ chuyển nhượng được từ Đang lưu hành. Hủy đợt chỉ được trước khi mint mục đầu tiên.*

| \#  | Từ → Đến                             | Điều kiện                                                                                                                        | Bên thực hiện                     | Trên chuỗi                                        |
|-----|--------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|-----------------------------------|---------------------------------------------------|
| 1   | Nháp → Chờ duyệt                     | Hồ sơ series đủ trường bắt buộc; term sheet đã tải lên                                                                           | Holco · người lập                 | Ngoài chuỗi             |
| 2   | Chờ duyệt → Chờ ký 3 bên             | Duyệt 4 mắt đạt; kiểm khung Đề án và giữ chỗ trong sổ hạn mức (2.02)                                                             | Holco · người duyệt               | Ngoài chuỗi             |
| 3   | Chờ duyệt → Nháp                     | Từ chối, ghi lý do                                                                                                               | Holco · người duyệt               | Ngoài chuỗi             |
| 4   | Chờ ký → Đã triển khai               | UBND, VSDC, Holco ký 3/3; factory triển khai bộ hợp đồng với maxQuantity, termsHash                                              | UBND, VSDC, Holco                 | Ký trên chuỗi      |
| 5   | Đã triển khai → Sẵn sàng             | VSDC gắn ISIN, mỗi series một lần                                                                                                | VSDC                              | Ký trên chuỗi      |
| 6   | Sẵn sàng → Đang chào bán             | Công bố đợt chào bán (3.01), mở sổ lệnh. Trên chuỗi vẫn là READY                                                                 | Tổ hợp, UBND                      | Ngoài chuỗi             |
| 7   | Đang chào bán → Đã đóng sổ           | Đến giờ đóng sổ; mọi lệnh bị khóa                                                                                                | Hệ thống                          | Ngoài chuỗi             |
| 8   | Đã đóng sổ → Đã chốt phân bổ         | UBND duyệt 4 mắt rồi ký gốc danh sách phân bổ và lãi suất các kỳ. Hợp đồng kiểm lãi suất trong khung, tổng phân bổ ≤ maxQuantity | UBND                              | Ký trên chuỗi      |
| 9   | Đã chốt phân bổ → Đang ghi sổ        | Mục đầu tiên được mint; trái phiếu bị khóa chuyển nhượng                                                                         | Hợp đồng, khi Holco mint          | Hợp đồng tự chuyển |
| 10  | Đang ghi sổ → Đang ghi sổ            | VSDC từ chối xác nhận; Holco hủy hoặc mint bù trong phạm vi danh sách UBND đã ký rồi gửi lại                                     | Holco · khóa vận hành             | Ký trên chuỗi      |
| 11  | Đang ghi sổ → Đang lưu hành          | VSDC ký xác nhận sổ sau phát hành; mở chuyển nhượng                                                                              | VSDC                              | Ký trên chuỗi      |
| 12  | Đang lưu hành → Vi phạm              | Một kỳ trả nợ quá ân hạn chưa chi đủ (máy Kỳ trả nợ, cạnh 10). Báo UBND, trustee, cơ quan quản lý. Đ1                            | Holco · khóa vận hành, ghi căn cứ | Ký trên chuỗi      |
| 13  | Vi phạm → Đang lưu hành              | Đã chi đủ khoản nợ quá hạn. Đ1                                                                                                   | Holco · khóa vận hành, ghi căn cứ | Ký trên chuỗi      |
| 14  | Đang lưu hành → Đã đáo hạn           | Kỳ gốc đã chi theo danh sách VSDC xác nhận; IPA xác nhận chi gốc; Holco hủy toàn bộ trái phiếu                                   | Holco · khóa vận hành             | Ký trên chuỗi      |
| 15  | Đã đáo hạn → Đã đóng                 | Tổng cung bằng 0, đối soát cuối khớp                                                                                             | Holco · khóa vận hành             | Ký trên chuỗi      |
| 16  | Sẵn sàng … Đã chốt phân bổ → Hủy đợt | Quyết định của UBND; chỉ khi chưa mint mục nào. Hủy series là thao tác multisig 3 bên                                            | UBND, VSDC, Holco                 | Ký trên chuỗi      |

## Lệnh · distribution.order

![Máy trạng thái lệnh: Đã đặt, sửa nhiều lần, hủy trước đóng sổ, khóa khi đóng sổ, rồi được hoặc không được phân bổ.](diagrams/03-may-trang-thai-h3.svg)

***Hình 3.** Toàn bộ vòng đời lệnh nằm ngoài chuỗi. Lệnh chỉ có kết quả khi danh sách phân bổ được UBND ký.*

| \#  | Từ → Đến                     | Điều kiện                                                                                                     | Bên thực hiện                                | Trên chuỗi                            |
|-----|------------------------------|---------------------------------------------------------------------------------------------------------------|----------------------------------------------|---------------------------------------|
| 1   | Đã đặt → Đã đặt              | Sửa lãi suất hoặc khối lượng trước giờ đóng sổ; mỗi lần sửa ghi một revision và kiểm lại điều kiện nhà đầu tư | Nhà đầu tư, hoặc thành viên tổ hợp nhập thay | Ngoài chuỗi |
| 2   | Đã đặt → Đã hủy              | Hủy trước giờ đóng sổ                                                                                         | Nhà đầu tư, tổ hợp                           | Ngoài chuỗi |
| 3   | Đã đặt → Đã khóa             | Đến giờ đóng sổ                                                                                               | Hệ thống                                     | Ngoài chuỗi |
| 4   | Đã khóa → Được phân bổ       | Danh sách phân bổ UBND ký có lệnh này với số lượng lớn hơn 0                                                  | Theo máy Series, cạnh 8                      | Ngoài chuỗi |
| 5   | Đã khóa → Không được phân bổ | Danh sách UBND ký không có lệnh này                                                                           | Theo máy Series, cạnh 8                      | Ngoài chuỗi |

## Phân bổ · distribution.allocation

![Máy trạng thái phân bổ: Đề xuất, Đã ký, Chờ tiền, Đã có tiền, Đã mint, Đã xác nhận; nhánh Quá hạn và Cần sửa.](diagrams/03-may-trang-thai-h4.svg)

***Hình 4.** Một phân bổ được mint khi có đủ chữ ký của UBND (danh sách) và BIDV (lô đã nhận tiền), và trở thành sở hữu có hiệu lực khi VSDC xác nhận sổ.*

| \#  | Từ → Đến              | Điều kiện                                                                                               | Bên thực hiện         | Trên chuỗi                                   |
|-----|-----------------------|---------------------------------------------------------------------------------------------------------|-----------------------|----------------------------------------------|
| 1   | Đề xuất → Đề xuất     | Tổ hợp điều chỉnh; mỗi lần tạo phiên bản danh sách mới, điều chỉnh tay phải ghi lý do                   | Tổ hợp                | Ngoài chuỗi        |
| 2   | Đề xuất → Đã ký       | UBND ký gốc danh sách chứa phân bổ này (máy Series, cạnh 8)                                             | UBND                  | Ký trên chuỗi |
| 3   | Đã ký → Chờ tiền      | Gửi thông báo số tiền và hạn thanh toán cho nhà đầu tư                                                  | Hệ thống, tổ hợp      | Ngoài chuỗi        |
| 4   | Chờ tiền → Đã có tiền | Xác nhận IPA khớp số tiền và nằm trong lô BIDV đã ký                                                    | BIDV                  | Ký trên chuỗi |
| 5   | Đã có tiền → Đã mint  | Hợp đồng kiểm hai đường chứng minh và chưa mint lần nào                                                 | Holco · khóa vận hành | Ký trên chuỗi |
| 6   | Đã mint → Đã xác nhận | VSDC ký xác nhận sổ cho cả series (máy Series, cạnh 11)                                                 | VSDC                  | Ký trên chuỗi |
| 7   | Chờ tiền → Quá hạn    | Quá hạn thanh toán chưa có tiền; báo tổ hợp. Bản XD dừng ở đây; hủy hoặc chuyển phân bổ thuộc 3.04 (Đ1) | Hệ thống              | Ngoài chuỗi        |
| 8   | Đã mint → Cần sửa     | VSDC chỉ ra mục sai khi từ chối xác nhận                                                                | VSDC                  | Ngoài chuỗi        |
| 9   | Cần sửa → Đã mint     | Holco hủy hoặc mint bù trong phạm vi danh sách UBND đã ký                                               | Holco · khóa vận hành | Ký trên chuỗi |

## Kỳ trả nợ · issuance.debt_service · coupon và gốc

![Máy trạng thái kỳ trả nợ: lên lịch, nhắc T−15, kiểm tiền T−2, chụp sổ T−1, VSDC xác nhận danh sách, gửi lệnh chi, đã chi, đối soát; nhánh chậm trả và vi phạm.](diagrams/03-may-trang-thai-h5.svg)

***Hình 5.** Danh sách người nhận được chụp và VSDC xác nhận dù tiền đủ hay thiếu. Thiếu tiền tại ngày T thì vào ân hạn; quá ân hạn thì series chuyển Vi phạm. Kỳ gốc chạy cùng luồng, sau đó trái phiếu bị hủy.*

| \#  | Từ → Đến                                | Điều kiện                                                                                                                          | Bên thực hiện         | Trên chuỗi                                   |
|-----|-----------------------------------------|------------------------------------------------------------------------------------------------------------------------------------|-----------------------|----------------------------------------------|
| 1   | Đã lên lịch → Đã nhắc                   | Ngày T−15: gửi số tiền dự kiến cho UBND và Kho bạc                                                                                 | Hệ thống              | Ngoài chuỗi        |
| 2   | Đã nhắc → Đã kiểm tiền                  | Ngày T−2: đọc số dư tài khoản IPA qua Open API, so với số phải trả. Thiếu thì cảnh báo UBND, Holco, cơ quan quản lý và báo trustee | Hệ thống, BIDV        | Ngoài chuỗi        |
| 3   | Đã kiểm tiền → Đã chụp sổ               | Cuối ngày T−1: chụp số dư tại khối chốt, ghi registerHash lên chuỗi                                                                | Holco · khóa vận hành | Ký trên chuỗi |
| 4   | Đã chụp sổ → Danh sách đã xác nhận      | VSDC đối chiếu và ký xác nhận danh sách người nhận                                                                                 | VSDC                  | Ký trên chuỗi |
| 5   | Danh sách đã xác nhận → Đã gửi lệnh chi | Ngày T, tài khoản IPA đủ tiền; vị thế bị phong tỏa được giữ lại                                                                    | Holco gửi, BIDV nhận  | Ngoài chuỗi        |
| 6   | Đã gửi lệnh chi → Đã chi                | IPA xác nhận đã chi qua lưu ký                                                                                                     | BIDV                  | Ngoài chuỗi        |
| 7   | Đã chi → Đã đối soát                    | Khớp chuỗi, ngân hàng và danh sách. Nếu là kỳ gốc thì chuyển máy Series sang Đã đáo hạn                                            | Holco                 | Ngoài chuỗi        |
| 8   | Danh sách đã xác nhận → Chậm trả        | Ngày T chưa đủ tiền; bắt đầu tính ân hạn theo tham số series                                                                       | Hệ thống              | Ngoài chuỗi        |
| 9   | Chậm trả → Đã gửi lệnh chi              | Đủ tiền trong thời gian ân hạn                                                                                                     | Hệ thống              | Ngoài chuỗi        |
| 10  | Chậm trả → Series vi phạm               | Quá ân hạn chưa đủ tiền. Đ1                                                                                                        | Holco · khóa vận hành | Ký trên chuỗi |

## Phong tỏa · governance.enforcement_order · theo vị thế

![Máy trạng thái phong tỏa theo vị thế: tiếp nhận lệnh, duyệt 4 mắt, VSDC và Holco ký, đã phong tỏa, rồi giải tỏa hoặc chuyển cưỡng chế; nhánh trả lại khi thiếu căn cứ.](diagrams/03-may-trang-thai-h6.svg)

***Hình 6.** Phong tỏa áp cho số lượng của một vị thế, không cho cả series. Giải tỏa và chuyển cưỡng chế là lệnh mới, đi lại đủ các bước duyệt và ký.*

| \#  | Từ → Đến                           | Điều kiện                                                                                                                                 | Bên thực hiện       | Trên chuỗi                                   |
|-----|------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|---------------------|----------------------------------------------|
| 1   | Tiếp nhận → Chờ duyệt              | Ghi cơ quan ra lệnh, số lệnh, ví, series, số lượng; tải văn bản căn cứ và tính mã băm                                                     | Holco · người lập   | Ngoài chuỗi        |
| 2   | Chờ duyệt → Chờ ký                 | Duyệt 4 mắt đạt                                                                                                                           | Holco · người duyệt | Ngoài chuỗi        |
| 3   | Chờ duyệt → Trả lại                | Thiếu căn cứ hoặc thông tin không khớp sổ                                                                                                 | Holco · người duyệt | Ngoài chuỗi        |
| 4   | Chờ ký → Đã phong tỏa              | VSDC và Holco ký 2/2 (giả định); hợp đồng phong tỏa đúng số lượng của vị thế, ghi basisHash. Coupon của phần bị phong tỏa giữ lại tại IPA | VSDC, Holco         | Ký trên chuỗi |
| 5   | Đã phong tỏa → Đã giải tỏa         | Có lệnh giải tỏa, đi lại các bước 1 đến 4 với lệnh mới                                                                                    | VSDC, Holco         | Ký trên chuỗi |
| 6   | Đã phong tỏa → Đã chuyển cưỡng chế | Có lệnh chuyển cưỡng chế, đi lại các bước 1 đến 4; ví nhận phải đủ điều kiện                                                              | VSDC, Holco         | Ký trên chuỗi |

## Trạng thái trên chuỗi của series

Hợp đồng điều khiển series giữ một enum gồm các trạng thái có ghi trên chuỗi. Bảng này là nguồn cho đặc tả hợp đồng ở bước 5.

| Giá trị enum          | Cho phép                                                               | Chặn                      |
|-----------------------|------------------------------------------------------------------------|---------------------------|
| `DEPLOYED`            | VSDC gắn ISIN; hủy series                                              | Mint, chuyển nhượng       |
| `READY`               | UBND ghi danh sách phân bổ; hủy series                                 | Mint, chuyển nhượng       |
| `ALLOCATED`           | BIDV ghi lô; Holco mint; hủy series nếu chưa mint                      | Chuyển nhượng             |
| `ISSUING`             | BIDV ghi lô; Holco mint, hủy trong danh sách; VSDC xác nhận sổ         | Chuyển nhượng; hủy series |
| `ACTIVE`              | Chuyển nhượng qua kiểm tra tuân thủ; chụp sổ; phong tỏa; khôi phục ví  | Mint                      |
| `DEFAULTED`           | Như ACTIVE, có cờ vi phạm; khi có sàn thì báo tạm ngừng giao dịch (ĐS) | Mint                      |
| `MATURED`             | Holco hủy trái phiếu sau xác nhận chi gốc                              | Chuyển nhượng, mint       |
| `CLOSED`, `CANCELLED` | Chỉ đọc                                                                | Mọi thao tác ghi          |

Căn cứ: Kiến trúc phân hệ WeRWA (16/10/2026), Quyết định công nghệ WeRWA và Mô hình dữ liệu WeRWA (duyệt 18/10/2026). Cạnh ghi Đ1 là chức năng cần trước đợt phát hành đầu, không bắt buộc cho buổi xét duyệt. Bước tiếp theo: đặc tả hợp đồng thông minh.
