# contracts/ — hợp đồng thông minh WeRWA

Foundry. Đặc tả ở `docs/design/04-hop-dong-thong-minh.md`; máy trạng thái ở `docs/design/03-may-trang-thai.md`; mã băm lá ở `docs/design/02-mo-hinh-du-lieu.md`.

## Phiên bản đã ghim

| Thành phần | Phiên bản | Commit | Thư mục |
|---|---|---|---|
| T-REX (ERC-3643) | 4.1.3 | `b6c5fab` | `lib/trex` |
| ONCHAINID | 2.2.1 | `a483cda` | `lib/onchain-id` |
| OpenZeppelin contracts | 4.9.6 | `dc44c9f` | `lib/openzeppelin-contracts` |
| OpenZeppelin contracts-upgradeable | 4.9.6 | `2d081f2` | `lib/openzeppelin-contracts-upgradeable` |
| forge-std | 1.17.0 | `f3dae6e` | `lib/forge-std` |
| Solidity | 0.8.17, optimizer 200 runs, EVM london | | `foundry.toml` |
| Foundry (CI) | 1.8.5 | | `.github/workflows/contracts.yml` |

OpenZeppelin và ONCHAINID ghim đúng bản trong `package-lock.json` của T-REX 4.1.3. Không sửa mã trong `lib/` (T-REX là GPL-3.0). Đổi phiên bản phải có đề xuất trong `docs/decisions/`.

## Lệnh

```bash
git submodule update --init --recursive
cd contracts
forge build
forge test                                    # unit, fuzz, invariant
forge coverage --report lcov --report-file lcov.info --no-match-coverage '(test|script|lib)/'
python3 script/check-coverage.py lcov.info 95 # cổng độ phủ phần WeRWA
slither . --config-file slither.config.json   # chỉ phân tích src/
python3 script/check-storage-layout.py        # bố cục lưu trữ hợp đồng nâng cấp được
script/export-abi.sh                          # xuất ABI vào api/abi/
script/export-abi.sh --check                  # CI: api/abi/ khớp bản build
```

## Cấu trúc

```
src/                    hợp đồng WeRWA
test/                   kiểm thử; tên theo mã US-x.yy-ACn
test/utils/TREXSuite.sol  bộ dựng T-REX + ONCHAINID cho kiểm thử
test/utils/PlatformFixture.sol  dựng toàn nền tảng như script triển khai
test/invariant/         bất biến I1–I8 (SeriesHandler + SeriesInvariants)
script/                 xuất ABI, cổng độ phủ, kiểm bố cục lưu trữ
storage-layout/         ảnh chụp bố cục lưu trữ đã duyệt (tạo khi có hợp đồng nâng cấp được)
```

## ABI

`script/abi-exports.txt` liệt kê hợp đồng được xuất. Thêm dòng khi có hợp đồng mới trong `src/`, chạy `script/export-abi.sh` và commit `api/abi/` cùng PR. Không sửa tay `api/abi/`.

## Bố cục lưu trữ

Hợp đồng nâng cấp được liệt kê trong `script/storage-layout.txt`. Khi đổi biến lưu trữ: chỉ thêm biến mới trước `__gap` và bớt `__gap` tương ứng, chạy `check-storage-layout.py --update`, đưa ảnh chụp mới vào PR để SA duyệt.

## Bất biến I1–I8

`test/invariant/SeriesInvariants.t.sol` chạy trên một series do `SeriesFactory` triển khai.

`SeriesHandler` gọi ngẫu nhiên mọi thao tác:
- bằng đúng vai và bằng vai sai;
- cố mint mục giả;
- chuyển nhượng khi đang bị khóa;
- chuyển tới ví chưa xác minh;
- phong tỏa và chuyển cưỡng chế;
- đáo hạn, hủy trái phiếu và đóng series.

Hàm `advance` đẩy series theo đúng luồng, để các lượt fuzz đi tới ACTIVE, MATURED và CLOSED.

Mỗi lượt chạy `forge test` đều chạy bất biến. Profile `ci` dùng 512 lượt × độ sâu 128.

Để kiểm bộ bất biến có bắt được lỗi, đã thử sửa sai mã rồi chạy lại:
- mở `TransferGateModule` ở ISSUING → I4 báo lỗi;
- bỏ điều kiện hủy series → I5 và I8 báo lỗi.
