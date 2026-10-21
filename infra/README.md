# infra/ — hạ tầng WeRWA

Mạng Besu QBFT có cấp phép cho devnet, theo ADR-01 (nền tảng chuỗi), ADR-02 (topology), ADR-12 (đường đọc) và ADR-16 (hạ tầng) trong `docs/design/01-quyet-dinh-cong-nghe.md`.

## Devnet

| Node | Bên | Vai | RPC trên máy chạy devnet |
|---|---|---|---|
| V1 | Holco, vùng A | validator, nhận giao dịch | `127.0.0.1:8645` |
| V2 | Holco, vùng B | validator, nhận giao dịch | `127.0.0.1:8655` |
| V3 | BIDV (Holco chạy hộ ở devnet) | validator | không mở |
| V4 | VSDC (Holco chạy hộ ở devnet) | validator | không mở |
| O1-a | Holco, vùng A | chỉ đọc | `127.0.0.1:8545` |
| O1-b | Holco, vùng B | chỉ đọc | `127.0.0.1:8555` |

| Tham số | Giá trị | Ở đâu |
|---|---|---|
| Phiên bản Besu | 26.10.0 (`hyperledger/besu:26.10.0`) | `BESU_IMAGE` |
| Đồng thuận | QBFT, khối 2 giây, `requesttimeoutseconds` 4 | `devnet/config/genesis.template.json` |
| Phí gas | 0: `zeroBaseFee` trong genesis, `min-gas-price = 0` | genesis, `devnet/config/node.toml` |
| Mốc EVM | London, khớp `evm_version = london` của `contracts/foundry.toml` | genesis |
| Giới hạn gas khối | 100 000 000 | genesis |
| Chain ID | 270201 (giả định cho devnet) | `DEVNET_CHAIN_ID` |
| Cấp phép | Danh sách node và danh sách tài khoản gửi giao dịch, cấu hình cục bộ | `devnet/out/shared/permissions_config.toml` |

### Lệnh

Cần Docker có Compose v2, `jq`, `curl`. Kiểm thử cần thêm `cast` (Foundry 1.8.5).

```bash
cd infra
devnet/devnet.sh up          # sinh khóa, genesis, cấp phép (lần đầu) rồi chạy mạng
devnet/devnet.sh status      # khối, peer, validator nhìn từ O1
test/devnet-smoke.sh         # kiểm thử khói S1–S6
devnet/devnet.sh down        # dừng, giữ dữ liệu chuỗi
devnet/devnet.sh reset       # xóa dữ liệu chuỗi, giữ khóa và genesis
devnet/devnet.sh clean       # xóa toàn bộ devnet/out/
```

Mọi thứ sinh ra nằm trong `devnet/out/` (gitignore): khóa node, khóa tài khoản thử, genesis, danh sách node tĩnh, tệp cấp phép, dữ liệu chuỗi. Khóa devnet chỉ dùng cho devnet.

### Đọc và ghi

- Ứng dụng, indexer và kiểm thử **chỉ đọc qua O1** (ADR-12). Giao dịch gửi tới V1 hoặc V2.
- Besu không giới hạn được từng hàm JSON-RPC nếu không bật xác thực JWT, nên RPC của V1, V2 vẫn trả lời lệnh đọc. Ở devnet, việc chỉ đọc qua O1 là quy ước. Giới hạn cứng làm ở môi trường XD.
- V3 và V4 không mở RPC.

### Mạng Docker

- `werwa-devnet-p2p`: chỉ node Besu, địa chỉ tĩnh `172.30.10.0/24`.
- `werwa-devnet-app`: nơi ứng dụng, module ký và kiểm thử tham gia (`external: true` trong compose của module đó). Trên mạng này có `v1.werwa.devnet`, `v2.werwa.devnet` (cổng 8545, gửi giao dịch) và `o1-a.werwa.devnet`, `o1-b.werwa.devnet` (cổng 8545, đọc).

### Cấp phép tài khoản cho module khác

Besu chỉ nhận giao dịch từ tài khoản trong danh sách. Module khác (ví dụ module ký SoftHSM) cấp phép cho tài khoản của mình như sau:

1. Ghi địa chỉ vào `devnet/out/allowlist.d/<tên-module>.txt`, mỗi dòng một địa chỉ, `#` để chú thích.
2. Chạy `devnet/devnet.sh allowlist`. Script sinh lại tệp cấp phép và khởi động lại từng node một, chờ mạng tạo khối rồi mới sang node kế tiếp, nên mạng luôn còn 3/4 validator.

### Mô phỏng hai vùng

Vùng chỉ là nhãn `werwa.region` trên container. Mất vùng A ở devnet là dừng `v1` và `o1-a`.

## Kiểm thử

| Mã | Kiểm | Tệp |
|---|---|---|
| S1 | 10 khối liên tiếp trong 18–25 giây | `test/devnet-smoke.sh` |
| S2 | Danh sách validator đọc qua O1 đúng 4 khóa V1–V4 | như trên |
| S3 | O1-a và O1-b có cùng mã băm khối | như trên |
| S4 | Giao dịch giá gas 0 từ tài khoản được cấp phép lên khối; `baseFeePerGas` 0 | như trên |
| S5 | Tài khoản ngoài danh sách bị từ chối (`-32007`) | như trên |
| S6 | Node ngoài danh sách: 0 peer, không nhận được khối | như trên |

CI: `.github/workflows/infra.yml` chạy với PR đổi `infra/`.
