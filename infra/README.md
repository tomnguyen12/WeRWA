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

## Keycloak (định danh, ADR-15)

`keycloak/keycloak.sh up` chạy Keycloak 26.8.1 (`quay.io/keycloak/keycloak:26.8.1`) ở chế độ production, lưu trên PostgreSQL của `infra/services` (DB `keycloak`, role `keycloak`, không phải superuser), rồi tạo realm `werwa` từ `keycloak/realm/`. Chạy lại an toàn: realm đã có thì so với tệp theo `keycloak/realm/drift.jq`, lệch thì dừng; đổi có chủ ý thì `reset` rồi `up`. Cần chạy `services/services.sh up` trước.

| | Trên máy | Trong mạng `werwa-devnet-app` |
|---|---|---|
| OIDC, đăng nhập | `http://localhost:8180/realms/werwa` | `http://keycloak.werwa.devnet:8080/realms/werwa` |
| Health | `http://127.0.0.1:9180/health/ready` | |

**Realm `werwa`:**

- **Vai:** 11 realm role trùng enum `roles` của `api/openapi.yaml`. Vai mặc định `default-roles-werwa` rỗng: không `offline_access`, không quyền tự quản tài khoản.
- **Claim (I3):** `roles` là mảng phẳng các vai của người dùng; `org_id` lấy từ thuộc tính người dùng `org_id` (I7), khai báo trong User Profile, chỉ admin xem và sửa. `werwa-web` tắt `fullScopeAllowed` và chỉ map 11 vai, nên token không mang vai nào khác. Không dùng client scope `roles` (`realm_access`).
- **Client:** `werwa-web` công khai, authorization code + PKCE S256, redirect `http://localhost:5173/*`, không implicit, không direct grant. `werwa-api` là resource server; access token có `aud: werwa-api`.
- **MFA (I4):** browser flow `werwa browser`: mật khẩu rồi OTP, cả hai REQUIRED, nên người dùng chưa có OTP bị buộc cài khi đăng nhập. TOTP SHA-1, 6 số, 30 giây, không dùng lại mã. Direct grant (ROPC) của realm đi qua flow `werwa direct grant` luôn từ chối, kể cả `admin-cli`. Tắt `delete_credential` để người dùng không tự gỡ OTP. Token chưa mang mức xác thực (`acr` là `1`); MFA được bảo đảm bởi flow, không phải bởi claim.
- **Phiên (I9, tham số khi tạo realm):** access token 5 phút (`WERWA_KC_ACCESS_TOKEN_LIFESPAN`), SSO idle 15 phút (`WERWA_KC_SSO_IDLE`), SSO tối đa 8 giờ (`WERWA_KC_SSO_MAX`).
- **Khác:** không tự đăng ký, không quên mật khẩu, mật khẩu tối thiểu 12 ký tự, chống dò mật khẩu (khóa tạm sau 5 lần sai), ghi sự kiện đăng nhập 30 ngày và sự kiện admin (không ghi chi tiết để không lộ bí mật trong nhật ký).
- **Issuer (I8):** cố định `http://localhost:8180/realms/werwa`, bật backchannel động: backend trong mạng `werwa-devnet-app` lấy JWKS qua `keycloak.werwa.devnet` mà vẫn kiểm đúng issuer. Devnet chạy HTTP; TLS làm ở môi trường XD.
- **Admin realm `master` (I10):** `admin`, mật khẩu trong `keycloak/out/.env`, không OTP; chỉ mở ở `127.0.0.1`, không phải người dùng nền tảng.

Kết quả trong `keycloak/out/` (gitignore): `.env` (mật khẩu DB và admin), `werwa-realm.json` (realm đã dựng, không có bí mật), `backend.env` (`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`, `..._JWK_SET_URI`, `..._AUDIENCES`).

Lệnh: `up`, `status`, `down` (giữ dữ liệu), `reset` (xóa DB `keycloak`, giữ mật khẩu và khóa OTP người dùng thử), `clean` (xóa cả `out/`).

### Người dùng thử (I4, I6)

`up` tạo người dùng thử theo `keycloak/seed/orgs.json`: 12 tổ chức, mỗi vai một tổ chức, riêng INVESTOR có `investor-a` và `investor-b`. `org_id` là UUID cố định trong tệp seed, để dữ liệu mẫu bảng `organization` của backend khớp claim. Mỗi tổ chức có 2 người dùng `<code>-maker` và `<code>-checker` (người lập, người duyệt), mang đúng vai của tổ chức. Tổng 24 người dùng.

| Tổ chức (`code`) | Vai | `org_id` |
|---|---|---|
| `ubnd` | ISSUER | `01a15bc1-f400-7c28-a7a8-d5d4cc19328b` |
| `operating-agency` | OPERATING_AGENCY | `01a15bc1-f401-70b2-9cfa-eb63c4f24ecc` |
| `syndicate` | SYNDICATE | `01a15bc1-f402-7188-aa1d-f504e6afe7e1` |
| `vsdc` | REGISTRAR | `01a15bc1-f403-7f0b-8fb9-a75cdc4b5b00` |
| `bidv` | PAYING_AGENT | `01a15bc1-f404-72f3-86c3-4f3f7c24f440` |
| `treasury` | TREASURY | `01a15bc1-f405-7f16-adca-5a6b16465e6f` |
| `custodian` | CUSTODIAN | `01a15bc1-f406-76b6-ac98-237eb9f37341` |
| `trustee` | TRUSTEE | `01a15bc1-f407-79bc-8f1d-6af20348c738` |
| `supervisor` | SUPERVISOR | `01a15bc1-f408-7d65-ace1-f4c87b5c1cdc` |
| `holco` | OPERATOR | `01a15bc1-f409-7589-aef1-774ceddccf3f` |
| `investor-a` | INVESTOR | `01a15bc1-f40a-7520-aeb5-19bb9d2faaa5` |
| `investor-b` | INVESTOR | `01a15bc1-f40b-7c54-aa64-c99f087b0103` |

Mật khẩu và khóa OTP sinh ngẫu nhiên lần đầu vào `keycloak/out/users.json` (quyền 600, gitignore; chỉ cho devnet), gồm cả URI `otpauth://` để nạp vào ứng dụng xác thực khi đăng nhập tay. Người dùng được tạo qua `partialImport`, vì Admin REST API không có lệnh tạo credential OTP. Nếu người dùng đã có trên Keycloak mà mất `users.json`, `up` dừng và đề nghị `reset`, để khóa OTP không lệch.

Khóa OTP là chuỗi Keycloak lưu nguyên; khóa HMAC là byte UTF-8 của chuỗi đó. `keycloak/totp.py <khóa>` tính mã hiện tại.

**Đăng nhập trong kiểm thử:** `keycloak/login.sh <username>` đi đúng browser flow bằng curl (code + PKCE S256, form mật khẩu, form OTP, đổi code lấy token) và in phản hồi token; `--claims` in claim của access token; `--list` liệt kê người dùng thử. Không dùng direct grant. Keycloak không nhận lại mã OTP đã dùng, nên khi đăng nhập cùng người dùng hai lần trong một bước 30 giây, công cụ chờ sang bước kế tiếp (ghi ở `out/.otp-used/`). Kiểm thử của backend và frontend dùng lại `login.sh` hoặc các hàm `login_*` trong `keycloak/lib.sh`.


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
| K1 | Realm có đúng 11 vai trùng enum `roles` của OpenAPI; `werwa-web` chỉ đưa đúng 11 vai đó vào token | `test/keycloak.sh` |
| K2 | Vai mặc định rỗng; mapper `roles`, `org_id`, audience `werwa-api`; `org_id` chỉ admin xem và sửa | như trên |
| K3 | Mật khẩu và OTP đều REQUIRED; `CONFIGURE_TOTP` mặc định; TOTP SHA-1/6/30; `werwa-web` công khai, PKCE S256, không implicit, không direct grant; direct grant bị chặn với mọi client | như trên |
| K4 | Bảng Keycloak ở DB `keycloak` của role `keycloak` (không superuser); `keycloak` và `werwa_app` không vào được DB của nhau | như trên |
| K5 | Khởi động lại Keycloak: realm còn; `up` lần nữa không tạo lại, không lệch | như trên |
| K6 | Issuer cố định nhìn từ máy và từ trong mạng `werwa-devnet-app`; JWKS trong mạng qua `keycloak.werwa.devnet` | như trên |
| US-4.07-AC1 | Đúng mật khẩu chưa có code, phải qua form OTP; OTP sai bị từ chối; OTP đúng nhận code và đổi được token | `test/keycloak-users.sh` |
| K7 | Cả 24 người dùng thử đăng nhập bằng khóa OTP sinh sẵn; access token và ID token có `org_id` đúng seed, `roles` đúng vai, không vai khác; `iss`, `aud`, `azp` đúng | như trên |
| K8 | Direct grant với mật khẩu và OTP đúng của người dùng thật vẫn không cấp token | như trên |
| K9 | Người dùng mới chưa có OTP bị buộc cài OTP sau mật khẩu, không nhận code | như trên |
| K10 | Trước idle refresh được; quá SSO idle refresh bị từ chối (CI tạo realm với idle 60 giây; mặc định 900 thì bỏ qua) | như trên |
| K11 | Khởi động lại Keycloak: người dùng và khóa OTP còn, đăng nhập lại được | như trên |
| K12 | `out/users.json` quyền 600 và bị git bỏ qua | như trên |
| US-6.01-AC3 (hạ tầng) | Đủ 6 node thì không có cảnh báo. Tắt V3: trong 60 giây Alertmanager có `WerwaValidatorDown` (v3) và `WerwaOnlyThreeValidators`, không báo nhầm mất đồng thuận. Bật lại: trong 90 giây cảnh báo hết | `test/us-6.01-ac3.sh` |

US-6.01-AC3: hạ tầng giao cảnh báo tới Alertmanager và webhook; phần hiện lên màn hình vận hành thuộc backend và frontend (I5 trong `docs/open-questions.md`).

Sau khi mất đồng thuận, QBFT tăng thời gian chờ đổi vòng theo cấp số nhân, nên mạng cần khoảng 1–2 phút sau khi bật lại mới có khối mới (đo tại máy: 78 giây). Kiểm thử cho tối đa 240 giây.

CI: `.github/workflows/infra.yml` chạy với PR đổi `infra/` hoặc `api/openapi.yaml` (K1 đọc enum vai từ đó).
