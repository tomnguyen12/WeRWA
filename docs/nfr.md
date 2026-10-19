# Yêu cầu phi chức năng WeRWA

> **Trạng thái: đề xuất, chờ duyệt.** Những dòng ghi "đã chốt" lấy từ thiết kế đã duyệt; dòng ghi "đề xuất" là số SA đưa ra để duyệt, chưa phải cam kết. Duyệt xong thì bỏ cột trạng thái.

## Sẵn sàng và phục hồi

| Mục | Yêu cầu | Trạng thái |
|---|---|---|
| Chịu lỗi mạng chuỗi | 4 validator QBFT chịu được 1 node hỏng mà vẫn tạo khối; mất 2 node thì dừng tạo khối, bật lại tự chạy tiếp không mất dữ liệu (US-6.01) | đã chốt |
| Hai vùng | Vùng chính và vùng DR, mỗi vùng một validator Holco và một O1 | đã chốt (ADR-02, ADR-16) |
| Sẵn sàng ứng dụng giờ làm việc | 99,5% theo tháng cho portal và API | đề xuất |
| RPO ứng dụng | ≤ 5 phút (PostgreSQL sao chép liên tục sang vùng DR) | đề xuất |
| RTO ứng dụng | ≤ 4 giờ, diễn tập DR trước đợt đầu | đề xuất |
| RPO sổ trên chuỗi | 0 với khối đã chốt (QBFT chốt tức thì, sổ có ở 4 node) | đã chốt theo thiết kế |

## Hiệu năng

| Mục | Yêu cầu | Trạng thái |
|---|---|---|
| Thời gian khối | 2 giây, là tham số | đã chốt (ADR-01) |
| Độ trễ từ khối tới bảng đọc | Indexer cập nhật bảng đọc ≤ 10 giây sau khi khối có sự kiện | đề xuất |
| API đọc | p95 ≤ 500 ms ở tải thiết kế | đề xuất |
| Tải thiết kế đợt đầu | ≤ 500 nhà đầu tư tổ chức, ≤ 5 000 lệnh mỗi đợt, ≤ 50 người dùng đồng thời | đề xuất, cần UBND và tổ hợp xác nhận quy mô |
| Mint cả đợt | Toàn bộ mục đã có tiền mint xong trong một ngày làm việc kể từ khi BIDV ký lô | đề xuất |

## Bảo mật

| Mục | Yêu cầu | Trạng thái |
|---|---|---|
| Xác thực | OIDC, MFA bắt buộc với mọi người dùng | đã chốt (ADR-15) |
| Phân quyền | Theo tổ chức và vai; duyệt 4 mắt cho thao tác ghi quan trọng | đã chốt (ADR-13, ADR-15) |
| Khóa | PKCS#11; SoftHSM giai đoạn đầu, HSM phần cứng trước đợt đầu; mỗi bên giữ khóa của mình | đã chốt (ADR-06) |
| Webhook ngân hàng | mTLS và chữ ký thông điệp | đã chốt (bước 6) |
| Dữ liệu cá nhân | Không lên chuỗi; mã hóa khi lưu và khi truyền (TLS 1.2 trở lên) | đã chốt phần không lên chuỗi (ADR-08); mã hóa là đề xuất |
| Hợp đồng | Kiểm toán độc lập sau khi chốt mã, trước đợt đầu; Slither trong CI | đã chốt (bước 5) |
| Kiểm thử xâm nhập | Trước đợt đầu, bởi đơn vị độc lập | đề xuất |

## Kiểm toán và lưu trữ

| Mục | Yêu cầu | Trạng thái |
|---|---|---|
| Nhật ký | Nối mã băm, neo mã băm cuối ngày lên chuỗi | đã chốt (ADR-13) |
| Đối soát | Hằng ngày: chuỗi với bảng đọc, chuỗi với ngân hàng, mã băm khối O1 với V4 của VSDC | đã chốt |
| Thời hạn lưu | Hồ sơ, nhật ký, tài liệu giữ tối thiểu tới đáo hạn series cộng 10 năm | đề xuất, chờ pháp lý xác nhận |

## Vận hành

| Mục | Yêu cầu | Trạng thái |
|---|---|---|
| Giám sát | Cảnh báo khi còn 3 validator, khi bộ điều phối ghi sổ quá hạn chờ, khi đối soát có lệch | đã chốt (bước 6, US-6.01) |
| Triển khai | Không dừng dịch vụ khi nâng cấp ứng dụng; nâng cấp hợp đồng qua timelock | đã chốt phần hợp đồng; ứng dụng là đề xuất |
| Đường đọc | Ứng dụng chỉ đọc từ O1, không đọc từ validator | đã chốt (ADR-12) |
