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

## Safe 1.4.1

`safe/safe.sh up` dựng Safe trên devnet; chạy lại an toàn.

- **Hợp đồng Safe 1.4.1.** Bytecode lấy từ gói npm `@safe-global/safe-contracts@1.4.1`, kiểm integrity sha512. Triển khai qua Safe Singleton Factory với salt 0. Factory có sẵn trong genesis tại địa chỉ chuẩn `0x914d7Fec6aaC8cd542e72Bca78B30650d45643d7`, nên hợp đồng Safe nằm đúng địa chỉ và đúng mã băm code của bản chính thức (`safe/safe-1.4.1.json`, lấy từ `@safe-global/safe-deployments` 1.37.64). Lệch thì script dừng.
- **Safe các bên theo ADR-05.** Mọi Safe dùng singleton `SafeL2`, `CompatibilityFallbackHandler`, không module, không guard.

| Safe | Owner | Ngưỡng | Vai theo ADR-05 |
|---|---|---|---|
| `ubnd`, `vsdc`, `bidv`, `holco` | 3 người ký thử (EOA) của tổ chức | 2/3 (devnet; thật do tổ chức tự đặt) | ký của từng tổ chức |
| `governance3of3` | Safe UBND, VSDC, Holco | 3/3 | triển khai/hủy series, nâng cấp, mở lại sau tạm dừng |
| `enforcement2of2` | Safe VSDC, Holco | 2/2 | phong tỏa, khôi phục ví, tạm dừng |

Kết quả nằm trong `devnet/out/safe/`: `safes.json` (địa chỉ, owner, ngưỡng), `safes.env` (biến `WERWA_*` cho `DeployPlatform`) và `signers/` (khóa người ký thử, chỉ cho devnet). Người ký không cần được cấp phép gửi giao dịch: chữ ký thu ngoài chuỗi, giao dịch do tài khoản chuyển tiếp gửi (đặc tả API, mục thu chữ ký multisig). Ở devnet, tài khoản gửi là `bootstrap`.

Genesis có thêm factory, nên devnet tạo trước PR này phải chạy `devnet/devnet.sh clean` rồi `up`.

## Dịch vụ cho backend

`services/services.sh up` chạy PostgreSQL và NATS JetStream trên mạng `werwa-devnet-app`, chạy lại an toàn. Không cần bật devnet trước. Kiểm thử tích hợp của backend vẫn tự dựng dịch vụ bằng Testcontainers; bộ này dành cho chạy backend trên máy và cho devnet dùng chung.

| Dịch vụ | Ảnh | Trên máy | Trong mạng `werwa-devnet-app` | Ghi chú |
|---|---|---|---|---|
| PostgreSQL | `postgres:18.6` | `127.0.0.1:5432` | `postgres.werwa.devnet:5432` | DB `werwa`, chủ là role `werwa_app` (không phải superuser). Sáu schema do Flyway của backend tạo |
| NATS JetStream | `nats:2.15.1` | `127.0.0.1:4222`, giám sát `:8222` | `nats.werwa.devnet:4222` | Stream `WERWA` theo I2; người dùng `werwa_app` chỉ phát và nhận `werwa.>` |

**Stream `WERWA` (I2, chốt 21/10):** nhận mọi chủ đề `werwa.>`, chủ đề trùng tên sự kiện. Lưu trên đĩa, giữ 30 ngày. Chống trùng 2 phút theo header `Nats-Msg-Id`, backend đặt bằng id CloudEvent. Không cho xóa từng sự kiện hay xóa sạch stream. Định nghĩa ở `services/nats/stream-werwa.json`; nếu stream trên máy chủ lệch tệp này, `services.sh` dừng.

Mật khẩu sinh vào `services/out/.env`. Biến kết nối cho backend chạy trên máy ở `services/out/backend.env` (`SPRING_DATASOURCE_*`, `WERWA_NATS_*`). Cả hai gitignore.

Lệnh: `up`, `status`, `down` (giữ dữ liệu), `reset` (xóa dữ liệu, giữ mật khẩu), `clean` (xóa cả `out/`). Cần thêm `nats` (natscli 0.5.0) trên máy.
## Giám sát

`monitoring/monitoring.sh up` chạy Prometheus và Alertmanager sau khi devnet đã chạy. Dừng giám sát trước khi dừng devnet, vì giám sát dùng chung mạng `werwa-devnet-p2p`.

- **Prometheus** (`127.0.0.1:9090`) lấy metric của 6 node Besu (cổng 9545) mỗi 5 giây, gắn nhãn `node`, `role`, `party`, `region`.
- **Alertmanager** (`127.0.0.1:9093`) gom cảnh báo. Đặt `WERWA_ALERT_WEBHOOK` thì Alertmanager gửi webhook tới URL đó, kể cả khi cảnh báo hết; không đặt thì chỉ xem trên Alertmanager.

| Cảnh báo | Khi nào | Mức |
|---|---|---|
| `WerwaValidatorDown` | một validator không phản hồi 10 giây | warning |
| `WerwaOnlyThreeValidators` | còn đúng 3/4 validator (NFR: "cảnh báo khi còn 3 validator") | critical |
| `WerwaConsensusLost` | dưới 3/4 validator, mạng dừng tạo khối | critical |
| `WerwaBlockProductionStalled` | O1 không thấy khối mới trong 30 giây | critical |
| `WerwaReaderDown` | một O1 không phản hồi | warning |
| `WerwaReaderLagging` | O1 chậm hơn validator quá 5 khối trong 30 giây | warning |

Luật ở `monitoring/rules/werwa-chain.yml`.

**Giới hạn:**
- "Validator hoạt động" ở đây là node trả lời metric, chưa phải node đang ký khối. Node vừa khởi động lại được tính là hoạt động trước khi nối lại đồng thuận. `WerwaBlockProductionStalled` bù cho trường hợp này.
- Ở môi trường thật, V3 và V4 nằm ở hạ tầng của BIDV và VSDC. Holco chỉ lấy được metric của hai node này nếu hai bên mở cổng metric. Nếu không, phải đếm validator theo cách khác, ví dụ đếm chữ ký trong khối.

## Kiểm thử

| Mã | Kiểm | Tệp |
|---|---|---|
| S1 | 10 khối liên tiếp trong 18–25 giây | `test/devnet-smoke.sh` |
| S2 | Danh sách validator đọc qua O1 đúng 4 khóa V1–V4 | như trên |
| S3 | O1-a và O1-b có cùng mã băm khối | như trên |
| S4 | Giao dịch giá gas 0 từ tài khoản được cấp phép lên khối; `baseFeePerGas` 0 | như trên |
| S5 | Tài khoản ngoài danh sách bị từ chối (`-32007`) | như trên |
| S6 | Node ngoài danh sách: 0 peer, không nhận được khối | như trên |
| US-6.01-AC1 | Lần lượt tắt V1, V2, V3, V4: mỗi lần mạng vẫn tạo 3 khối mới trong 30 giây. Mất vùng A (V1 và O1-a): đọc qua O1-b, mạng vẫn tạo khối | `test/us-6.01.sh` |
| US-6.01-AC2 | Tắt V2 và V4: 20 giây không có khối mới. Bật lại: mạng tự chạy tiếp; khối trước sự cố giữ mã băm ở cả hai O1; giao dịch gửi trước sự cố vẫn ở đúng khối | như trên |
| US-6.04-AC1 | 9 hợp đồng Safe 1.4.1 đúng mã băm bản chính thức. Mỗi Safe: ngưỡng và owner đúng bảng trên, SafeL2 1.4.1, đúng fallback handler, không guard, không module. Người ký Safe tổ chức là 3 EOA riêng biệt. Safe liên bên có ngưỡng bằng số thành viên, không có BIDV | `test/us-6.04-ac1.sh` |
| P1–P4 | `werwa_app` đăng nhập DB `werwa`, tạo được 6 schema, không phải superuser, không tạo DB, không vào DB `postgres`; PostgreSQL 18 | `test/services.sh` |
| N1–N5 | Stream `WERWA` đúng I2; hai lần cùng `Nats-Msg-Id` chỉ giữ một bản; `werwa_app` chỉ phát vào `werwa.>`; kết nối ẩn danh bị từ chối; consumer bền và sự kiện còn sau khi NATS khởi động lại | như trên |
| US-6.01-AC3 (hạ tầng) | Đủ 6 node thì không có cảnh báo. Tắt V3: trong 60 giây Alertmanager có `WerwaValidatorDown` (v3) và `WerwaOnlyThreeValidators`, không báo nhầm mất đồng thuận. Bật lại: trong 90 giây cảnh báo hết | `test/us-6.01-ac3.sh` |

US-6.01-AC3: hạ tầng giao cảnh báo tới Alertmanager và webhook; phần hiện lên màn hình vận hành thuộc backend và frontend (I5 trong `docs/open-questions.md`).

Sau khi mất đồng thuận, QBFT tăng thời gian chờ đổi vòng theo cấp số nhân, nên mạng cần khoảng 1–2 phút sau khi bật lại mới có khối mới (đo tại máy: 78 giây). Kiểm thử cho tối đa 240 giây.

CI: `.github/workflows/infra.yml` chạy với PR đổi `infra/`.
