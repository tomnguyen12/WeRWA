<!-- Sinh tự động từ trang thiết kế đã duyệt: https://claude.ai/artifact/Kktg3GAhYhYstXQNuP5foj
     Sửa nội dung thiết kế thì sửa ở đây qua PR có SA duyệt; trang HTML chỉ còn là bản lưu. -->

Bước 7 · Đã duyệt · 19/10/2026

# User story WeRWA

32 chức năng cho buổi xét duyệt đầu tháng 02/2027, kèm tiêu chí chấp nhận và kịch bản demo · Tommy Nguyen, Tư vấn độc lập

Mỗi chức năng XD của trang Kiến trúc phân hệ là một user story. Tiêu chí chấp nhận viết theo dạng Cho · Khi · Thì để chuyển thẳng thành kiểm thử tự động. Tên mã lỗi, sự kiện và API lấy đúng theo các trang thiết kế đã duyệt.

## Kịch bản demo xét duyệt

![Kịch bản demo xét duyệt gồm 11 cảnh, từ Đề án và series tới đáo hạn; giám sát và nhật ký chạy xuyên suốt.](diagrams/06-user-story-h1.svg)

***Hình 1.** Mười một cảnh theo thứ tự trình diễn, mỗi cảnh ghi các chức năng nó chứng minh. Cảnh ⑥ là cảnh ngoại lệ có chủ đích: Holco cố mint ngoài danh sách và bị hợp đồng chặn. Ô xanh là cảnh có ghi sổ trên chuỗi do nhiều bên ký.*

[1 · Onboarding (4)](#e1) [2 · Phát hành (8)](#e2) [3 · Quản lý phân phối (7)](#e3) [4 · Quản trị (7)](#e4) [5 · Tích hợp (2)](#e5) [6 · Onchain (4)](#e6)

## 1 · Onboarding · 4 story

### US-1.01 — KYB tổ chức tham gia

*Cảnh demo ②*

**Là** Quản trị viên Holco, tôi muốn đăng ký và duyệt KYB các tổ chức tham gia cùng người ký được ủy quyền, để chỉ tổ chức đã xác minh mới thao tác và ký trên nền tảng.

1.  **Cho** một tổ chức mới với vai UBND · **Khi** người lập nhập hồ sơ và một người duyệt khác duyệt · **Thì** tổ chức sang KYB_APPROVED, phát sự kiện organization.kyb_approved, nhật ký ghi cả hai người
2.  **Cho** hồ sơ do chính người dùng A lập · **Khi** A bấm duyệt · **Thì** hệ thống trả 403, mã MAKER_CHECKER_SAME_USER
3.  **Cho** tổ chức đã duyệt KYB · **Khi** đăng ký người ký kèm địa chỉ ký và văn bản ủy quyền · **Thì** người ký có hiệu lực từ valid_from; địa chỉ trùng với người ký khác bị từ chối 409
4.  **Cho** người ký đã quá valid_to · **Khi** người đó nộp chữ ký cho một đề nghị ký · **Thì** chữ ký bị từ chối, mã SIGNER_NOT_ACTIVE

API: `POST /v1/organizations, /kyb-decision, /signers`

### US-1.02 — KYC/KYB nhà đầu tư tổ chức; sàng lọc cấm vận, PEP

*Cảnh demo ②*

**Là** Thành viên tổ hợp, Holco, tôi muốn mở hồ sơ KYC cho nhà đầu tư tổ chức và có kết quả sàng lọc, để chỉ nhà đầu tư đủ điều kiện được đặt lệnh.

1.  **Cho** nhà đầu tư tổ chức có hồ sơ đầy đủ · **Khi** mở hồ sơ KYC · **Thì** hệ thống gọi nhà cung cấp sàng lọc (giả lập ở XD) và lưu kết quả nguyên văn
2.  **Cho** kết quả sàng lọc trùng danh sách cấm vận · **Khi** người duyệt mở hồ sơ · **Thì** hồ sơ ở trạng thái cần xem xét; muốn kết luận đạt phải ghi lý do
3.  **Cho** hồ sơ đạt · **Khi** người duyệt kết luận · **Thì** hồ sơ có hạn hiệu lực theo tham số, claim KYC được phát lên ONCHAINID, phát sự kiện investor.kyc_approved
4.  **Cho** claim KYC trên chuỗi · **Khi** đọc nội dung claim · **Thì** claim chỉ có mã loại và hạn, không có dữ liệu cá nhân

API: `POST /v1/kyc-cases, /decision`

### US-1.03 — Phân loại nhà đầu tư theo Đề án

*Cảnh demo ③*

**Là** Holco, tôi muốn gán loại nhà đầu tư theo danh mục đối tượng của Đề án, để hệ thống và hợp đồng chặn được loại không được phép.

1.  **Cho** Đề án đang hiệu lực · **Khi** gán loại cho nhà đầu tư · **Thì** chỉ chọn được loại có trong danh mục của Đề án; claim INVESTOR_CLASS được phát lên chuỗi kèm hạn
2.  **Cho** nhà đầu tư có loại không thuộc đối tượng của series · **Khi** đặt lệnh · **Thì** từ chối với mã INVESTOR_CLASS_NOT_ELIGIBLE
3.  **Cho** như trên · **Khi** cố mint hoặc chuyển trái phiếu tới ví của nhà đầu tư đó bằng giao dịch gửi thẳng lên chuỗi · **Thì** hợp đồng từ chối (module loại nhà đầu tư)

API: `POST /v1/kyc-cases/{id}/decision`

### US-1.04 — Tạo ví và gắn định danh

*Cảnh demo ②*

**Là** Nhà đầu tư, ngân hàng lưu ký, tôi muốn đăng ký ví tương thích EVM của mình, để nhận và nắm giữ trái phiếu.

1.  **Cho** nhà đầu tư đã qua KYC · **Khi** đăng ký ví · **Thì** phải ký thông điệp thử thách bằng chính ví đó; sai chữ ký thì từ chối
2.  **Cho** chữ ký hợp lệ · **Khi** đăng ký hoàn tất · **Thì** ví được ghi vào sổ định danh gắn với ONCHAINID của tổ chức, phát sự kiện wallet.registered
3.  **Cho** địa chỉ đã thuộc tổ chức khác · **Khi** đăng ký lại · **Thì** trả 409
4.  **Cho** bất kỳ ví nào · **Khi** kiểm cơ sở dữ liệu và kho khóa của Holco · **Thì** không có khóa riêng của ví nhà đầu tư (ADR-07)

API: `POST /v1/wallets`

## 2 · Phát hành · 8 story

### US-2.01 — Đề án đã duyệt và các khung

*Cảnh demo ①*

**Là** Cơ quan điều hành, tôi muốn nhập Đề án đã được HĐND duyệt cùng các khung, để mọi series bị kiểm theo khung.

1.  **Cho** Đề án mới · **Khi** lưu · **Thì** bắt buộc có số nghị quyết, ngày duyệt, tài liệu, đồng tiền, khối lượng tối đa, khung lãi suất, trần chi phí, đối tượng nhà đầu tư
2.  **Cho** Đề án đủ thông tin · **Khi** duyệt 4 mắt đạt · **Thì** Đề án sang hiệu lực và chỉ đọc
3.  **Cho** Đề án đang hiệu lực · **Khi** cần đổi khung · **Thì** phải tạo Đề án điều chỉnh là bản ghi mới, liên kết bản cũ

API: `POST /v1/programs`

### US-2.02 — Sổ hạn mức; chặn vượt khung

*Cảnh demo ①*

**Là** UBND, Cơ quan điều hành, Sở Tài chính, tôi muốn xem hạn mức đã dùng và còn lại, và hệ thống chặn series vượt khung, để không phát hành quá Đề án.

1.  **Cho** series qua duyệt 4 mắt · **Khi** hệ thống giữ chỗ · **Thì** ghi bút toán RESERVE bằng khối lượng tối đa nhân mệnh giá
2.  **Cho** tổng giữ chỗ và đã dùng sẽ vượt khối lượng Đề án · **Khi** duyệt series · **Thì** từ chối với mã PROGRAM_LIMIT_EXCEEDED
3.  **Cho** series bị hủy hoặc đã phát hành xong · **Khi** hệ thống cập nhật · **Thì** ghi RELEASE hoặc USE theo số đã mint; sổ chỉ ghi thêm, số dư bằng tổng bút toán

API: `GET /v1/programs/{id}/limits`

### US-2.04 — Hồ sơ phát hành có phiên bản

*Cảnh demo ①*

**Là** UBND, tổ hợp, tư vấn pháp lý, tôi muốn tải và quản lý phiên bản tài liệu hồ sơ phát hành, để biết bản nào đã được dùng khi ký.

1.  **Cho** một tài liệu · **Khi** tải lên · **Thì** nhận mã tài liệu và SHA-256; mỗi lần tải là phiên bản mới, không ghi đè
2.  **Cho** danh sách tài liệu bắt buộc do Holco cấu hình · **Khi** gửi series đi duyệt mà còn thiếu · **Thì** từ chối, liệt kê tài liệu thiếu
3.  **Cho** tài liệu có mức hiển thị INTERNAL · **Khi** nhà đầu tư truy cập · **Thì** không thấy tài liệu đó

API: `POST /v1/documents`

### US-2.05 — Term sheet, tham số, lịch sự kiện

*Cảnh demo ①*

**Là** Holco, tổ hợp, tôi muốn nhập term sheet và để hệ thống sinh lịch sự kiện, để lịch trả nợ đúng ngày làm việc.

1.  **Cho** term sheet mới · **Khi** lưu · **Thì** đồng tiền, kỳ hạn, đối tượng được kiểm trong khung Đề án
2.  **Cho** series bằng USD, trả 6 tháng một lần · **Khi** sinh lịch · **Thì** có đủ ngày trả, ngày chốt (ngày làm việc liền trước) theo lịch VN và New York; ngày nghỉ dời theo quy tắc ngày làm việc kế tiếp (tham số)
3.  **Cho** series đã gửi duyệt · **Khi** sửa term sheet · **Thì** series quay về Nháp, phải duyệt lại; termsHash tính lại

API: `POST /v1/series, /submit`

### US-2.06 — Quyết định của UBND về điều khoản, lãi suất

*Cảnh demo ④*

**Là** UBND, tôi muốn ghi quyết định của UBND, để danh sách phân bổ và lãi suất có căn cứ.

1.  **Cho** quyết định mới · **Khi** lưu · **Thì** có số, ngày, tài liệu, đối tượng áp dụng
2.  **Cho** chưa có quyết định về lãi suất của đợt · **Khi** gửi danh sách phân bổ cho UBND ký · **Thì** từ chối với mã ISSUER_DECISION_REQUIRED
3.  **Cho** quyết định đã ghi · **Khi** tạo đề nghị ký · **Thì** mã băm quyết định đi kèm đề nghị ký

API: `POST /v1/issuer-decisions`

### US-2.07 — Vòng đời series và mã ISIN

*Cảnh demo ①*

**Là** Holco, VSDC, UBND, tôi muốn series đi đúng máy trạng thái đã duyệt, để không ai đi tắt được.

1.  **Cho** series ở Chờ ký 3 bên · **Khi** UBND, VSDC, Holco ký đủ 3/3 · **Thì** series sang Đã triển khai, lưu địa chỉ hợp đồng
2.  **Cho** series Đã triển khai · **Khi** VSDC nhập ISIN 12 ký tự có chữ số kiểm tra đúng ISO 6166 · **Thì** series sang Sẵn sàng; nhập lần hai bị từ chối
3.  **Cho** bất kỳ trạng thái nào · **Khi** yêu cầu chuyển không có trong sơ đồ (qua API hoặc giao dịch thẳng lên chuỗi) · **Thì** bị từ chối ở cả hai phía
4.  **Cho** mọi lần chuyển trạng thái · **Khi** hoàn tất · **Thì** có dòng nhật ký và sự kiện series.state_changed

API: `POST /v1/series/{id}/deploy`

### US-2.09 — Tính coupon; khấu trừ thuế

*Cảnh demo ⑧*

**Là** Holco, BIDV, tôi muốn hệ thống tính coupon từng nhà đầu tư và thuế khấu trừ, để IPA chi đúng.

1.  **Cho** 500 trái phiếu, mệnh giá 1.000 USD, lãi suất 5,30%/năm, 30/360, trả 6 tháng · **Khi** tính kỳ 1 · **Thì** gross = 13.250,00 USD
2.  **Cho** thuế suất của series · **Khi** tính · **Thì** có gross, thuế, net cho từng dòng; làm tròn xuống tới cent, phần làm tròn báo cáo riêng
3.  **Cho** vị thế đang bị phong tỏa · **Khi** tính · **Thì** dòng chi trả bị giữ lại với lý do FROZEN
4.  **Cho** bảng chi trả của kỳ · **Khi** đối chiếu · **Thì** tổng các dòng bằng số phải trả của kỳ

API: `GET /v1/series/{id}/snapshots/{period}`

### US-2.10 — Đáo hạn và hoàn gốc

*Cảnh demo ⑪*

**Là** UBND, BIDV, VSDC, Holco, tôi muốn kỳ gốc chạy như một kỳ trả nợ rồi hủy trái phiếu, để đóng series sạch.

1.  **Cho** tới kỳ gốc · **Khi** chạy luồng kỳ trả nợ · **Thì** có chụp sổ, VSDC xác nhận, lệnh chi, IPA xác nhận
2.  **Cho** IPA xác nhận chi gốc · **Khi** Holco gọi mature · **Thì** series sang Đã đáo hạn và chặn chuyển nhượng
3.  **Cho** series Đã đáo hạn · **Khi** hủy trái phiếu · **Thì** toàn bộ số dư không bị phong tỏa bị hủy; tổng cung bằng 0 thì đóng series

API: `—`

## 3 · Quản lý phân phối · 7 story

### US-3.01 — Công bố đợt chào bán

*Cảnh demo ④*

**Là** Tổ hợp, UBND, tôi muốn công bố đợt chào bán với khoảng lãi suất chỉ dẫn và lịch sổ lệnh, để nhà đầu tư đặt lệnh.

1.  **Cho** series ở Sẵn sàng · **Khi** tạo đợt chào bán · **Thì** khoảng lãi suất chỉ dẫn phải nằm trong khung Đề án
2.  **Cho** đợt chào bán đã tạo · **Khi** tới giờ mở sổ · **Thì** sổ lệnh mở; series ngoài chuỗi sang Đang chào bán
3.  **Cho** series chưa có ISIN · **Khi** tạo đợt chào bán · **Thì** từ chối

API: `POST /v1/offerings, /open, /close`

### US-3.02 — Sổ lệnh

*Cảnh demo ④*

**Là** Nhà đầu tư, thành viên tổ hợp, tôi muốn đặt, sửa, hủy lệnh và tổ hợp nhập thay khách, để dựng sổ lệnh.

1.  **Cho** sổ đang mở · **Khi** đặt lệnh · **Thì** kiểm KYC còn hạn, loại được phép, ví đã đăng ký, lãi suất trong khoảng chỉ dẫn, khối lượng là bội số tham số
2.  **Cho** thành viên tổ hợp nhập thay khách đã KYC · **Khi** đặt lệnh · **Thì** lệnh ghi entered_by là người và tổ chức nhập
3.  **Cho** trước giờ đóng sổ · **Khi** sửa hoặc hủy · **Thì** mỗi lần sửa có revision; sau giờ đóng sổ mọi lệnh khóa

API: `POST /v1/offerings/{id}/orders`

### US-3.03 — Chốt lãi suất, phân bổ, ký danh sách

*Cảnh demo ④*

**Là** Tổ hợp, UBND, tôi muốn đề xuất, điều chỉnh và để UBND ký danh sách phân bổ, để có căn cứ ghi sổ.

1.  **Cho** sổ đã đóng · **Khi** tổ hợp đề xuất danh sách · **Thì** mỗi điều chỉnh tay phải có lý do và tạo phiên bản mới
2.  **Cho** danh sách có tổng vượt khối lượng series hoặc lãi suất ngoài khung · **Khi** gửi UBND · **Thì** từ chối với ALLOCATION_EXCEEDS_MAX hoặc RATE_OUT_OF_RANGE
3.  **Cho** danh sách hợp lệ · **Khi** UBND duyệt 4 mắt rồi ký · **Thì** giao dịch lên khối, series sang Đã chốt phân bổ, lệnh sang Được hoặc Không được phân bổ
4.  **Cho** nhà đầu tư có phân bổ · **Khi** lấy đường chứng minh · **Thì** tự kiểm được phân bổ khớp gốc danh sách trên chuỗi

API: `POST /v1/allocation-lists, /submit, /proofs`

### US-3.05 — Thanh toán đợt đầu và ghi sổ

*Cảnh demo ⑤ ⑥*

**Là** BIDV, Holco, VSDC, tôi muốn trái phiếu chỉ được ghi sổ khi BIDV đã xác nhận tiền, để sổ không bao giờ ghi trái phiếu chưa thanh toán.

1.  **Cho** báo có đã xử lý · **Khi** BIDV gửi lại cùng mã tham chiếu · **Thì** không xử lý lần hai
2.  **Cho** báo có · **Khi** đối chiếu · **Thì** đúng số lượng × mệnh giá × giá phát hành thì MATCHED; lệch thì SHORT hoặc OVER và không vào lô
3.  **Cho** BIDV đã ký lô · **Khi** bộ điều phối mint · **Thì** mỗi phân bổ mint đúng một lần với hai đường chứng minh; trái phiếu bị khóa chuyển nhượng
4.  **Cho** Holco cố mint mục không có trong danh sách hoặc chưa có trong lô BIDV · **Khi** gửi giao dịch · **Thì** hợp đồng từ chối (cảnh demo)
5.  **Cho** mọi mục có tiền đã mint · **Khi** VSDC ký xác nhận sổ · **Thì** series sang Đang lưu hành, mở chuyển nhượng

API: `POST /v1/inbound/bidv/credit-notifications; GET /issuance-progress`

### US-3.07 — Sổ người sở hữu, sao kê

*Cảnh demo ⑦*

**Là** Nhà đầu tư, ngân hàng lưu ký, VSDC, tôi muốn xem số dư và sao kê, để đối chiếu với sổ của mình.

1.  **Cho** bảng đọc · **Khi** so với chuỗi tại cùng số khối · **Thì** số dư khớp; màn hình hiển thị số khối
2.  **Cho** người dùng của nhà đầu tư A · **Khi** xem sổ · **Thì** chỉ thấy ví của A; VSDC thấy toàn bộ
3.  **Cho** khoảng thời gian · **Khi** xuất sao kê · **Thì** có mọi biến động kèm mã giao dịch

API: `GET /v1/holdings`

### US-3.08 — Chuyển nhượng OTC không kèm thanh toán

*Cảnh demo ⑦*

**Là** Nhà đầu tư, ngân hàng lưu ký, tôi muốn chuyển trái phiếu cho nhà đầu tư khác đủ điều kiện, tiền tự thanh toán ngoài nền tảng.

1.  **Cho** series Đang lưu hành, VSDC đã xác nhận sổ · **Khi** bên chuyển tạo và bên nhận xác nhận (thêm lưu ký nếu ví omnibus) · **Thì** giao dịch lên chuỗi, số dư hai bên cập nhật
2.  **Cho** ví nhận không đủ điều kiện · **Khi** gửi giao dịch · **Thì** hợp đồng từ chối, yêu cầu ghi rõ lý do
3.  **Cho** phần số lượng đang bị phong tỏa · **Khi** chuyển · **Thì** không chuyển được

API: `POST /v1/transfers, /confirm`

### US-3.09 — Chụp danh sách; chi coupon và gốc

*Cảnh demo ⑧*

**Là** VSDC, BIDV, ngân hàng lưu ký, tôi muốn danh sách người nhận được chụp và xác nhận trước khi chi, để chi đúng người.

1.  **Cho** cuối ngày T−1 · **Khi** hệ thống chụp sổ · **Thì** ghi mã băm danh sách lên chuỗi
2.  **Cho** danh sách đã chụp · **Khi** VSDC xác nhận · **Thì** mới tạo được lệnh chi
3.  **Cho** ngày T, tài khoản IPA đủ tiền · **Khi** gửi lệnh chi (giả lập BIDV) · **Thì** IPA xác nhận thì kỳ sang Đã chi
4.  **Cho** ngày T chưa đủ tiền · **Khi** tới hạn · **Thì** kỳ sang Chậm trả, không gửi lệnh chi

API: `GET /v1/series/{id}/snapshots/{period}`

## 4 · Quản trị · 7 story

### US-4.01 — Bộ quy tắc tuân thủ theo tham số Đề án

*Cảnh demo ③*

**Là** Holco, tôi muốn luật tuân thủ lấy từ Đề án và term sheet, áp giống nhau ngoài chuỗi và trên chuỗi.

1.  **Cho** series đã triển khai · **Khi** đọc tham số module tuân thủ · **Thì** khớp tham số trong Đề án và term sheet, không nhập tay
2.  **Cho** cùng một bộ ca kiểm thử · **Khi** chạy kiểm ngoài chuỗi và kiểm trên chuỗi · **Thì** kết quả cho phép hoặc từ chối giống nhau
3.  **Cho** cần đổi tham số · **Khi** thực hiện · **Thì** chỉ qua multisig 3 bên

API: `—`

### US-4.02 — Phong tỏa và chuyển cưỡng chế

*Cảnh demo ⑨*

**Là** Holco, VSDC, tôi muốn phong tỏa đúng vị thế theo lệnh có căn cứ, để thi hành lệnh của cơ quan có thẩm quyền.

1.  **Cho** lệnh phong tỏa · **Khi** tiếp nhận · **Thì** ghi cơ quan, số lệnh, ví, số lượng, tài liệu căn cứ và mã băm
2.  **Cho** lệnh đã qua duyệt 4 mắt · **Khi** VSDC và Holco ký 2/2 · **Thì** hợp đồng phong tỏa đúng số lượng, ghi mã băm căn cứ
3.  **Cho** vị thế đang bị phong tỏa · **Khi** tới kỳ coupon · **Thì** phần coupon của vị thế bị giữ lại
4.  **Cho** cần giải tỏa hoặc chuyển cưỡng chế · **Khi** thực hiện · **Thì** phải là lệnh mới, đi lại đủ các bước

API: `POST /v1/enforcement-orders`

### US-4.04 — Quyền xem thời gian thực cho Cơ quan giám sát

*Cảnh demo xuyên suốt*

**Là** Cơ quan giám sát VIFC, tôi muốn xem series, sổ, giao dịch theo thời gian thực, để giám sát không cần chờ báo cáo.

1.  **Cho** giao dịch vừa lên khối · **Khi** mở công cụ giám sát · **Thì** thấy giao dịch với độ trễ không quá 10 giây (tham số)
2.  **Cho** người dùng của Cơ quan giám sát · **Khi** thử bất kỳ thao tác ghi nào · **Thì** bị từ chối
3.  **Cho** kết quả đối chiếu mã băm khối với VSDC hằng ngày · **Khi** có lệch · **Thì** hiển thị cảnh báo đỏ và tạo chênh lệch đối soát

API: `GET /v1/supervision/…`

### US-4.07 — Phân quyền theo tổ chức và vai; MFA

*Cảnh demo xuyên suốt*

**Là** Mọi bên, tôi muốn chỉ làm được đúng việc của tổ chức và vai mình.

1.  **Cho** người dùng bất kỳ · **Khi** đăng nhập · **Thì** qua OIDC, bắt buộc MFA
2.  **Cho** người dùng có vai của tổ chức A · **Khi** gọi API của đối tượng thuộc tổ chức B · **Thì** bị từ chối 403
3.  **Cho** phiên quá thời gian không hoạt động (tham số) · **Khi** gọi tiếp · **Thì** phải đăng nhập lại

API: `—`

### US-4.08 — Duyệt 4 mắt; nhật ký không sửa được

*Cảnh demo xuyên suốt*

**Là** Mọi bên, tôi muốn mọi thao tác trọng yếu có người duyệt khác người lập và nhật ký không sửa được.

1.  **Cho** yêu cầu duyệt đã gửi · **Khi** nội dung thay đổi · **Thì** yêu cầu bị hủy, phải gửi lại
2.  **Cho** nhật ký kiểm toán · **Khi** sửa một dòng trực tiếp trong cơ sở dữ liệu · **Thì** công cụ kiểm phát hiện chuỗi mã băm bị đứt
3.  **Cho** cuối ngày · **Khi** neo nhật ký · **Thì** mã băm cuối ngày có trên chuỗi và khớp nhật ký

API: `GET /v1/approvals, /audit-log`

### US-4.09 — Đối soát hằng ngày

*Cảnh demo ⑤ ⑧*

**Là** Holco, BIDV, VSDC, tôi muốn chuỗi, bảng đọc, ngân hàng và sổ được đối soát mỗi ngày.

1.  **Cho** cuối ngày · **Khi** chạy đối soát · **Thì** kiểm đủ R1 đến R8 của mô hình dữ liệu
2.  **Cho** có lệch · **Khi** đối soát xong · **Thì** mỗi lệch thành một chênh lệch cần người xử lý đóng
3.  **Cho** kết quả đối soát · **Khi** BIDV và VSDC xem · **Thì** thấy phần liên quan tới mình

API: `GET /v1/reconciliation/runs`

### US-4.11 — Tham số hệ thống, lịch ngày nghỉ, thông báo

*Cảnh demo xuyên suốt*

**Là** Holco, tôi muốn quản lý tham số và lịch ngày nghỉ, để lịch sự kiện và cảnh báo đúng.

1.  **Cho** năm mới · **Khi** nhập lịch ngày nghỉ VN, New York · **Thì** lịch có hiệu lực cho các series mới và kỳ chưa chốt
2.  **Cho** cần đổi tham số · **Khi** lưu · **Thì** phải qua duyệt 4 mắt, có ngày hiệu lực
3.  **Cho** sự kiện cảnh báo (thiếu tiền, quá hạn ký…) · **Khi** phát ra · **Thì** người nhận được thông báo trong ứng dụng và email

API: `—`

## 5 · Tích hợp · 2 story

### US-5.02 — BIDV Open API (giả lập ở XD)

*Cảnh demo ⑤ ⑧*

**Là** BIDV, Holco, tôi muốn nhận báo có, gửi lệnh chi và lấy sao kê qua Open API, để không nhập tay tiền.

1.  **Cho** giả lập BIDV · **Khi** gọi báo có, lệnh chi, sao kê · **Thì** đúng hợp đồng API đã chốt với BIDV
2.  **Cho** webhook sai chứng chỉ hoặc sai chữ ký · **Khi** tới nền tảng · **Thì** bị từ chối và ghi nhật ký
3.  **Cho** thông điệp trùng · **Khi** tới lần hai · **Thì** không xử lý lại
4.  **Cho** người demo · **Khi** dùng màn hình giả lập · **Thì** tạo được báo có và xác nhận chi

API: `POST /v1/inbound/bidv/…`

### US-5.04 — Nhà cung cấp KYC và cấm vận

*Cảnh demo ②*

**Là** Holco, tôi muốn sàng lọc qua một adapter, để đổi nhà cung cấp không sửa nghiệp vụ.

1.  **Cho** adapter · **Khi** đổi nhà cung cấp · **Thì** không đổi mã nghiệp vụ
2.  **Cho** giả lập ở XD · **Khi** chạy kịch bản trùng cấm vận · **Thì** trả kết quả trùng để demo
3.  **Cho** mọi kết quả · **Khi** nhận về · **Thì** lưu nguyên văn

API: `—`

## 6 · Onchain · 4 story

### US-6.01 — Mạng Besu có cấp phép

*Cảnh demo ⑩*

**Là** Holco, VSDC, BIDV, tôi muốn mạng chạy đúng topology đã chốt và chịu được một node lỗi.

1.  **Cho** 4 validator QBFT và O1 ở mỗi vùng · **Khi** tắt một validator · **Thì** mạng vẫn tạo khối (cảnh demo)
2.  **Cho** tắt hai validator · **Khi** bật lại · **Thì** mạng dừng tạo khối rồi tự chạy tiếp, không mất dữ liệu
3.  **Cho** chỉ còn 3 validator · **Khi** theo dõi · **Thì** có cảnh báo ngay trên màn hình vận hành

API: `—`

### US-6.02 — Hợp đồng trái phiếu số

*Cảnh demo xuyên suốt*

**Là** Holco, tôi muốn hợp đồng đúng đặc tả bước 5 và được kiểm thử tự động.

1.  **Cho** mỗi lần commit · **Khi** CI chạy · **Thì** kiểm thử bất biến I1 đến I8 đều qua
2.  **Cho** mã hợp đồng WeRWA · **Khi** đo · **Thì** độ phủ dòng tối thiểu 95%, Slither không có lỗi mức cao
3.  **Cho** mọi hàm đổi trạng thái · **Khi** gọi · **Thì** phát sự kiện, indexer đọc được

API: `—`

### US-6.03 — ONCHAINID và claim KYC

*Cảnh demo ②*

**Là** Holco, tôi muốn mỗi nhà đầu tư tổ chức có một định danh trên chuỗi với claim có hạn.

1.  **Cho** nhà đầu tư qua KYC · **Khi** tạo định danh · **Thì** có một ONCHAINID và claim do Holco ký
2.  **Cho** claim hết hạn · **Khi** chuyển trái phiếu tới ví của nhà đầu tư đó · **Thì** hợp đồng từ chối
3.  **Cho** KYC bị hủy · **Khi** thu hồi claim · **Thì** ví không nhận được trái phiếu nữa

API: `—`

### US-6.04 — Ví chữ ký nhiều bên

*Cảnh demo ① ④ ⑤*

**Là** UBND, VSDC, BIDV, Holco, tôi muốn ký trên ví của tổ chức mình và nền tảng gom đủ chữ ký mới gửi.

1.  **Cho** Safe tổ chức và Safe liên bên theo ADR-05 · **Khi** tạo · **Thì** ngưỡng đúng bảng ADR-05
2.  **Cho** đề nghị ký · **Khi** người ký nộp chữ ký EIP-712 · **Thì** nền tảng kiểm đúng chủ Safe; đủ ngưỡng tổ chức thì ghép chữ ký EIP-1271
3.  **Cho** chưa đủ ngưỡng liên bên · **Khi** chờ · **Thì** giao dịch không được gửi
4.  **Cho** khóa vận hành của Holco · **Khi** ký · **Thì** qua PKCS#11 tới SoftHSM, không có khóa ở dạng tệp trên máy ứng dụng

API: `GET/POST /v1/signing-proposals`

## Định nghĩa hoàn thành

- Mọi tiêu chí chấp nhận có kiểm thử tự động: kiểm thử tích hợp cho API và sự kiện, Foundry cho hợp đồng.
- API có trong OpenAPI 3.1; sự kiện có trong danh mục, đúng vỏ CloudEvents.
- Kiểm quyền theo tổ chức và vai; thao tác trọng yếu có duyệt 4 mắt và dòng nhật ký.
- Có dữ liệu mẫu cho cảnh demo tương ứng; chạy được trọn cảnh trên môi trường XD.
- Không có lỗi mức cao từ phân tích tĩnh; review mã bởi người thứ hai.

Căn cứ: Kiến trúc phân hệ WeRWA (32 chức năng XD) và các trang thiết kế bước 2–6 đã duyệt. Bước tiếp theo: yêu cầu phi chức năng, quy ước repo và CLAUDE.md để sinh code.
