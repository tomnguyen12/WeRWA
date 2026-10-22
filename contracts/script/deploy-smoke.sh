#!/usr/bin/env bash
# Triển khai thử DeployPlatform lên anvil bằng `forge script --broadcast` (I1).
# Bắt các lỗi chỉ xuất hiện khi broadcast thật, ví dụ forge chặn `address(this)` trong script,
# ở cả hai chế độ ký mà infra dùng: --private-key và --unlocked --sender.
set -euo pipefail
cd "$(dirname "$0")/.."
PORT="${ANVIL_PORT:-8599}"
RPC="http://127.0.0.1:$PORT"
anvil --port "$PORT" --gas-price 0 --base-fee 0 --code-size-limit 50000 --silent &
ANVIL=$!
trap 'kill $ANVIL 2>/dev/null || true; rm -rf broadcast/DeployPlatform.s.sol/31337' EXIT
for _ in $(seq 1 50); do cast chain-id --rpc-url "$RPC" >/dev/null 2>&1 && break; sleep 0.2; done

export WERWA_GOVERNANCE_3OF3=0x00000000000000000000000000000000000000A3
export WERWA_CANCELLERS=0x00000000000000000000000000000000000000B1,0x00000000000000000000000000000000000000B2,0x00000000000000000000000000000000000000B3
export WERWA_HOLCO_SAFE=0x00000000000000000000000000000000000000C1
export WERWA_HOLCO_CLAIM_SIGNER=0x00000000000000000000000000000000000000C2
export WERWA_HOLCO_OPERATOR=0x00000000000000000000000000000000000000C3

# Tài khoản thử của anvil, sinh lúc chạy từ mnemonic thử công khai của anvil (không lưu khóa trong repo).
ANVIL_MNEMONIC="test test test test test test test test test test test junk"
KEY0="$(cast wallet private-key --mnemonic "$ANVIL_MNEMONIC" --mnemonic-index 0)"
SENDER1="$(cast wallet address --mnemonic "$ANVIL_MNEMONIC" --mnemonic-index 1)"

run() {
  local mode="$1"; shift
  if ! forge script script/DeployPlatform.s.sol --rpc-url "$RPC" --broadcast "$@" >"$TMPDIR_LOG/$mode.log" 2>&1; then
    echo "DeployPlatform thất bại ($mode):" >&2
    grep -m3 -E 'Error|error' "$TMPDIR_LOG/$mode.log" >&2 || tail -20 "$TMPDIR_LOG/$mode.log" >&2
    exit 1
  fi
  grep -q "ONCHAIN EXECUTION COMPLETE & SUCCESSFUL" "$TMPDIR_LOG/$mode.log" || { echo "Không thấy xác nhận broadcast ($mode)" >&2; exit 1; }
  echo "DeployPlatform broadcast đạt ($mode)"
}
TMPDIR_LOG="$(mktemp -d)"
run private-key --private-key "$KEY0"
run unlocked-sender --unlocked --sender "$SENDER1"
