<!-- Sinh tự động từ trang thiết kế đã duyệt: https://claude.ai/artifact/P4FnqzYKEhy8gTTn7MuNeW
     Sửa nội dung thiết kế thì sửa ở đây qua PR có SA duyệt; trang HTML chỉ còn là bản lưu. -->

Thiết kế phân hệ · bản duyệt 16/10/2026

# Kiến trúc phân hệ WeRWA

Theo mô hình nghiệp vụ và cấu trúc các bên đã chốt theo dự thảo Nghị quyết HĐND TP.HCM · Tommy Nguyen, Tư vấn độc lập

Nền tảng gồm 6 phân hệ: ba phân hệ nghiệp vụ (Onboarding, Phát hành, Quản lý phân phối), một phân hệ Quản trị áp lên mọi thao tác, và hai phân hệ nền (Tích hợp, Onchain). Giao diện của từng bên là lớp trình bày, không phải phân hệ.

Phân hệ

6

Chức năng

55

Cho xét duyệt 02/2027

32

Trước đợt đầu

17

Các đợt sau

6

## 01 · Nguyên tắc thiết kế

1.  **Sổ đăng ký trên chuỗi là nguồn dữ liệu gốc.** VSDC giữ tư cách pháp lý của sổ; Holco chỉ vận hành.
2.  **Tiền nằm ngoài chuỗi.** Đợt đầu chỉ nhận xác nhận thanh toán của BIDV; DvP trên nền tảng để các đợt sau.
3.  **Quy tắc là tham số lấy từ Đề án:** đối tượng nhà đầu tư, khung lãi suất, khối lượng, đồng tiền.
4.  **Hai lớp duyệt cho thao tác trọng yếu:** duyệt 4 mắt trên nền tảng, rồi chữ ký nhiều bên trên chuỗi.
5.  **Ranh giới Phát hành và Quản lý phân phối:** Phát hành là phía tổ chức phát hành (Đề án đến đóng series, gồm nghĩa vụ trả nợ); Quản lý phân phối là phía nhà đầu tư (chào bán, nắm giữ, chuyển nhượng, nhận tiền).

## 02 · Sơ đồ phân hệ

![Kiến trúc 6 phân hệ: các bên dùng nền tảng qua giao diện; ba phân hệ nghiệp vụ Onboarding, Phát hành, Quản lý phân phối; Quản trị áp cho mọi thao tác; Tích hợp và Onchain ở lớp nền.](diagrams/02-kien-truc-phan-he-h1.svg)

***Hình 1.** Ba phân hệ nghiệp vụ ghi dữ liệu xuống Onchain và ra ngoài qua Tích hợp; mọi thao tác đi qua quy tắc và quyền của Quản trị (tím). Ô xanh là phần chạy trên chuỗi.*

## 03 · Chức năng theo phân hệ

### 1Onboarding7 chức năng

Đưa các bên vào nền tảng, đủ điều kiện để giao dịch.

| \#   | Chức năng                                         | Bên sử dụng                                              | Bản phát hành                     |
|------|---------------------------------------------------|----------------------------------------------------------|-----------------------------------|
| 1.01 | KYB tổ chức tham gia: người đại diện, quyền ký    | UBND, CQĐH, VSDC, BIDV, tổ hợp, lưu ký, trustee, Kho bạc | XD |
| 1.02 | KYC/KYB nhà đầu tư tổ chức; sàng lọc cấm vận, PEP | Nhà đầu tư, tổ hợp, lưu ký                               | XD |
| 1.03 | Phân loại nhà đầu tư theo đối tượng trong Đề án   | Holco                                                    | XD |
| 1.04 | Tạo ví và gắn định danh                           | Nhà đầu tư, lưu ký                                       | XD |
| 1.05 | Ví omnibus cho ngân hàng lưu ký, KYC nhìn xuyên   | Ngân hàng lưu ký                                         | Đ1 |
| 1.06 | KYC định kỳ, tự chặn khi hết hạn                  | Nhà đầu tư, Holco                                        | Đ1 |
| 1.07 | Khôi phục ví bị mất                               | Nhà đầu tư, VSDC                                         | Đ1 |

### 2Phát hành13 chức năng

Vòng đời trái phiếu phía tổ chức phát hành, từ Đề án đến đóng series.

| \#   | Chức năng                                                                              | Bên sử dụng                   | Bản phát hành                                                       |
|------|----------------------------------------------------------------------------------------|-------------------------------|---------------------------------------------------------------------|
| 2.01 | Hồ sơ Đề án đã duyệt và các khung: khối lượng, lãi suất, chi phí, đồng tiền, đối tượng | UBND, CQĐH                    | XD                                   |
| 2.02 | Sổ hạn mức đã dùng và còn lại; chặn đợt phát hành vượt khung                           | UBND, CQĐH, Sở Tài chính      | XD                                   |
| 2.03 | Chương trình trung hạn; danh mục dự án sử dụng vốn                                     | UBND, CQĐH                    | Đ1                                   |
| 2.04 | Hồ sơ phát hành theo Đ7, có phiên bản tài liệu                                         | UBND, tổ hợp, tư vấn pháp lý  | XD                                   |
| 2.05 | Term sheet, tham số series, lịch sự kiện theo ngày làm việc VN và New York             | UBND, tổ hợp, Holco           | XD                                   |
| 2.06 | Ghi nhận quyết định của UBND về điều khoản và lãi suất (Đ8.3e)                         | UBND                          | XD                                   |
| 2.07 | Vòng đời series và mã ISIN                                                             | Holco, VSDC                   | XD                                   |
| 2.08 | Theo dõi nộp tiền trả nợ từ Kho bạc qua IPA: nhắc T−15, kiểm tra T−2                   | UBND, Kho bạc, BIDV           | Đ1                                   |
| 2.09 | Tính coupon; khấu trừ thuế                                                             | Holco, BIDV                   | XD Đ1 |
| 2.10 | Đáo hạn và hoàn gốc                                                                    | UBND, BIDV, VSDC              | XD                                   |
| 2.11 | Chậm trả, ân hạn, chuyển trạng thái vi phạm                                            | UBND, trustee                 | Đ1                                   |
| 2.12 | Phát hành bổ sung, mua lại, hoán đổi, chào mua, mua lại trước hạn                      | UBND, tổ hợp                  | ĐS                                   |
| 2.13 | Nhãn xanh hoặc bền vững                                                                | UBND, đơn vị đánh giá độc lập | ĐS                                   |

### 3Quản lý phân phối12 chức năng

Phía nhà đầu tư: chào bán, thanh toán, nắm giữ, chuyển nhượng, nhận tiền.

| \#   | Chức năng                                                                                     | Bên sử dụng                | Bản phát hành                                                       |
|------|-----------------------------------------------------------------------------------------------|----------------------------|---------------------------------------------------------------------|
| 3.01 | Công bố đợt chào bán                                                                          | UBND, tổ hợp               | XD                                   |
| 3.02 | Sổ lệnh: đặt, sửa, hủy; tổ hợp nhập lệnh thay khách; kiểm tra điều kiện khi đặt               | Tổ hợp, nhà đầu tư         | XD                                   |
| 3.03 | Chốt lãi suất trong khung, phân bổ theo chính sách, nhật ký điều chỉnh                        | Tổ hợp, UBND               | XD                                   |
| 3.04 | Tăng quy mô; ngưỡng tối thiểu và hủy đợt                                                      | Tổ hợp, UBND               | Đ1                                   |
| 3.05 | Thanh toán đợt đầu: nhận xác nhận của IPA rồi ghi sổ                                          | BIDV, Holco                | XD                                   |
| 3.06 | Theo dõi chuyển vốn về Kho bạc                                                                | BIDV, Kho bạc, UBND        | Đ1                                   |
| 3.07 | Sổ người sở hữu, sao kê theo nhà đầu tư                                                       | Nhà đầu tư, lưu ký, VSDC   | XD                                   |
| 3.08 | Chuyển nhượng OTC; chuyển không kèm thanh toán                                                | Nhà đầu tư, lưu ký, VSDC   | XD Đ1 |
| 3.09 | Chụp danh sách tại ngày chốt; chi coupon và gốc qua IPA và lưu ký                             | VSDC, BIDV, lưu ký         | XD                                   |
| 3.10 | Trustee và quyền người sở hữu: cổng trustee, biểu quyết theo số dư, lấy ý kiến sửa điều khoản | Trustee, nhà đầu tư        | Đ1                                   |
| 3.11 | Đấu thầu; kênh đại lý phân phối và bán trực tiếp                                              | Tổ hợp, đại lý, nhà đầu tư | ĐS                                   |
| 3.12 | DvP trên nền tảng; xử lý thanh toán thất bại                                                  | BIDV, nhà đầu tư           | ĐS                                   |

### 4Quản trị11 chức năng

Tuân thủ, giám sát và báo cáo, bảo mật, vận hành nền tảng.

| \#   | Chức năng                                                                    | Bên sử dụng                 | Bản phát hành                     |
|------|------------------------------------------------------------------------------|-----------------------------|-----------------------------------|
| 4.01 | Tuân thủ · Bộ quy tắc có tham số lấy từ Đề án, áp cho OTC và sàn             | Holco                       | XD |
| 4.02 | Tuân thủ · Phong tỏa và chuyển cưỡng chế, ghi căn cứ                         | Cơ quan có thẩm quyền, VSDC | XD |
| 4.03 | Tuân thủ · Cảnh báo tập trung sở hữu, giao dịch bất thường                   | Holco, Cơ quan giám sát     | Đ1 |
| 4.04 | Giám sát · Quyền xem thời gian thực cho Cơ quan giám sát VIFC                | Cơ quan giám sát            | XD |
| 4.05 | Giám sát · Báo cáo Đ11.4 cho BTC, NHNN, HĐND; báo cáo sử dụng vốn theo dự án | UBND, Sở Tài chính          | Đ1 |
| 4.06 | Giám sát · Công bố thông tin theo Đ10; kho tài liệu nhà đầu tư               | UBND, nhà đầu tư            | Đ1 |
| 4.07 | Bảo mật · Phân quyền theo tổ chức và vai trò, MFA                            | Mọi bên                     | XD |
| 4.08 | Bảo mật · Luồng duyệt 4 mắt; nhật ký kiểm toán không sửa được                | Mọi bên                     | XD |
| 4.09 | Vận hành · Đối soát hằng ngày chuỗi, sổ, ngân hàng, Kho bạc                  | Holco, BIDV, VSDC           | XD |
| 4.10 | Vận hành · Tính phí, hóa đơn                                                 | Holco, UBND                 | Đ1 |
| 4.11 | Vận hành · Tham số hệ thống, lịch ngày nghỉ, thông báo                       | Holco                       | XD |

### 5Tích hợp6 chức năng

Kết nối với hệ thống của các bên bên ngoài.

| \#   | Chức năng                                                                         | Bên sử dụng        | Bản phát hành                                                               |
|------|-----------------------------------------------------------------------------------|--------------------|-----------------------------------------------------------------------------|
| 5.01 | VSDC: ISIN, xác nhận sổ, xác nhận danh sách                                       | VSDC               | Đ1                                           |
| 5.02 | BIDV qua Open API: báo có, lệnh chi, sao kê; không dùng SWIFT                     | BIDV               | XD giả lập Đ1 |
| 5.03 | Kho bạc: tiếp nhận xác nhận, giai đoạn đầu bằng tệp hoặc nhập tay                 | Kho bạc            | Đ1                                           |
| 5.04 | Nhà cung cấp KYC và dữ liệu cấm vận                                               | Holco              | XD                                           |
| 5.05 | Giao diện chung kết nối sàn và Euroclear/Clearstream; đồng bộ tạm ngừng giao dịch | Sàn, ICSD          | ĐS                                           |
| 5.06 | Xuất sổ phục vụ chuyển hạ tầng (Đ9.2)                                             | VSDC, hạ tầng VIFC | ĐS                                           |

### 6Onchain6 chức năng

Sổ cái, hợp đồng số và khóa.

| \#   | Chức năng                                                                                                | Bên sử dụng       | Bản phát hành                     |
|------|----------------------------------------------------------------------------------------------------------|-------------------|-----------------------------------|
| 6.01 | Mạng blockchain có cấp phép; node xác thực của VSDC, BIDV, Holco; giám sát mạng                          | VSDC, BIDV, Holco | XD |
| 6.02 | Hợp đồng trái phiếu số (ERC-3643): phát hành, hủy, chuyển nhượng, kiểm tra điều kiện, phong tỏa, chụp sổ | Hệ thống          | XD |
| 6.03 | Định danh trên chuỗi (ONCHAINID) và claim KYC                                                            | Hệ thống          | XD |
| 6.04 | Ví chữ ký nhiều bên: tạo và hủy series, phong tỏa, nâng cấp                                              | UBND, VSDC, Holco | XD |
| 6.05 | Nâng cấp hợp đồng có thời gian chờ                                                                       | Holco, VSDC       | Đ1 |
| 6.06 | Quản lý khóa bằng HSM                                                                                    | Holco             | Đ1 |

## 04 · Luồng giữa các phân hệ

Mỗi hàng là một phân hệ; hàng trên cùng là việc do các bên bên ngoài làm (ô cam, viền đứt). Ô xanh là bước chạy trên chuỗi.

### Phát hành đợt đầu

![Luồng phát hành đợt đầu, phần chuẩn bị và chào bán](diagrams/02-kien-truc-phan-he-h2.svg)

***Hình 2.** Thanh toán đợt đầu diễn ra ngoài nền tảng: BIDV nhận tiền, Tích hợp nhận xác nhận của IPA, rồi Onchain mới mint trái phiếu. Phát hành chặn ngay từ bước 2 nếu đợt vượt khung Đề án.*

### Trả coupon

![Luồng trả coupon, phần nộp tiền và kiểm tra](diagrams/02-kien-truc-phan-he-h3.svg)

***Hình 3.** Nếu ở bước 4 tiền chưa đủ, Phát hành cảnh báo UBND, Holco, cơ quan quản lý và báo trustee; quá thời gian ân hạn thì series chuyển trạng thái vi phạm. Hoàn gốc khi đáo hạn chạy cùng luồng, sau đó trái phiếu bị hủy.*

### Phong tỏa

![Luồng phong tỏa, phần tiếp nhận và thực thi](diagrams/02-kien-truc-phan-he-h4.svg)

***Hình 4.** Phong tỏa áp cho từng vị thế, không cho cả series. Coupon của vị thế bị phong tỏa được giữ lại tại IPA cho tới khi có lệnh giải tỏa.*

Căn cứ: mô hình nghiệp vụ WeRWA và cấu trúc các bên theo dự thảo Nghị quyết HĐND TP.HCM (05/10/2026). Mã chức năng dạng n.xx (phân hệ.thứ tự) dùng để nối với backlog.
