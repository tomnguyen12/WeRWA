# WeRWA

Nền tảng ghi sổ trái phiếu số cho đợt phát hành trái phiếu của UBND TP.HCM tại VIFC, chạy trên chuỗi khối có cấp phép (Hyperledger Besu, ERC-3643).

**Trạng thái:** thiết kế đã duyệt, chưa có mã ứng dụng. Mã chỉ được viết khi có lệnh riêng cho từng luồng việc.

## Bắt đầu từ đâu

- [`CLAUDE.md`](CLAUDE.md): nguồn sự thật, quyết định đã khóa, quy ước — đọc trước tiên.
- [`docs/`](docs/README.md): nghiệp vụ, thiết kế đã duyệt, câu hỏi còn mở.
- [`api/`](api/README.md): OpenAPI, lược đồ sự kiện, ABI.
- [`CONTRIBUTING.md`](CONTRIBUTING.md): nhánh, commit, PR, CI.

## Luồng việc

| Thư mục | Nội dung | Thứ tự |
|---|---|---|
| `contracts/` | Solidity, Foundry | 1 — ABI là đầu vào của backend |
| `backend/` | Java 21, Spring Boot, Spring Modulith | 2 |
| `frontend/` | React, TypeScript | 3 |
| `infra/` | Mạng Besu, môi trường, IaC | song song |

Các thư mục này tạo ở PR khởi tạo của từng luồng.
