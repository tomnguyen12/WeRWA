#!/usr/bin/env bash
# Chạy contracts/script/DeployPlatform.s.sol lên devnet WeRWA.
#
#   ./deploy.sh        triển khai nền tảng (một lần cho mỗi devnet); in địa chỉ vào devnet/out/platform/devnet.json
#
# Đầu vào:
#   devnet/out/safe/safes.env   Safe các bên (infra/safe/safe.sh up): WERWA_GOVERNANCE_3OF3, WERWA_CANCELLERS, WERWA_HOLCO_SAFE
#   WERWA_TIMELOCK_DELAY        giây; mặc định 3600 (C15)
#
# Khóa ký — hai chế độ:
#   pkcs11   khi có WERWA_SIGNER_RPC: forge gửi eth_sendTransaction tới module ký của SoftHSM (`--unlocked`).
#            Địa chỉ khóa lấy từ WERWA_KEYS_FILE (mặc định devnet/out/keys.json):
#              { "deployer": "0x…", "holcoOperator": "0x…", "holcoClaimSigner": "0x…" }
#            Module ký phải chuyển tiếp các lệnh JSON-RPC khác tới V1 để forge mô phỏng được giao dịch.
#   devnet   khi chưa có module ký: khóa triển khai là tài khoản thử `deployer` của devnet (tệp trong out/,
#            chỉ cho devnet). Khóa vận hành và khóa ký claim chỉ là địa chỉ giữ chỗ, không có khóa riêng,
#            nên không ai mint hay cấp claim được cho tới khi triển khai lại ở chế độ pkcs11.
#
# Địa chỉ đọc theo tên trường của struct `Deployed` (read-run.sh), cộng `tokenDeployer` đọc qua SeriesFactory.
# Sau khi chạy, `test/deploy-platform.sh` kiểm quyền sở hữu và vai trên chuỗi.

set -euo pipefail
PLATFORM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$PLATFORM_DIR/../devnet/lib.sh"

need forge; need cast; need jq

REPO="$(cd "$PLATFORM_DIR/../.." && pwd)"
CONTRACTS="$REPO/contracts"
WORK="$DEVNET_OUT/platform"
SAFES_ENV="$DEVNET_OUT/safe/safes.env"
KEYS_FILE="${WERWA_KEYS_FILE:-$DEVNET_OUT/keys.json}"

[ -f "$SAFES_ENV" ] || die "thiếu $SAFES_ENV; chạy safe/safe.sh up"
[ -f "$CONTRACTS/lib/trex/package.json" ] || die "thiếu submodule của contracts/; chạy git submodule update --init --recursive"
mkdir -p "$WORK"
if [ -f "$WORK/devnet.json" ] && [ "$(cast code --rpc-url "$O1A_RPC" "$(jq -r .contracts.timelock "$WORK/devnet.json")")" != 0x ]; then
  log "nền tảng đã triển khai trên devnet này: $WORK/devnet.json"
  exit 0
fi

set -a; source "$SAFES_ENV"; set +a
export WERWA_TIMELOCK_DELAY="${WERWA_TIMELOCK_DELAY:-3600}"

placeholder() { cast wallet new --json | jq -r '(.data // .) | if type == "array" then .[0] else . end | .address'; }

if [ -n "${WERWA_SIGNER_RPC:-}" ]; then
  MODE=pkcs11
  [ -f "$KEYS_FILE" ] || die "chế độ pkcs11 cần $KEYS_FILE"
  DEPLOYER=$(jq -r .deployer "$KEYS_FILE")
  export WERWA_HOLCO_OPERATOR=$(jq -r .holcoOperator "$KEYS_FILE")
  export WERWA_HOLCO_CLAIM_SIGNER=$(jq -r .holcoClaimSigner "$KEYS_FILE")
  RPC="$WERWA_SIGNER_RPC"
  SIGN_ARGS=(--unlocked --sender "$DEPLOYER")
else
  MODE=devnet
  log "CHÚ Ý: chưa có WERWA_SIGNER_RPC — chạy chế độ devnet, khóa triển khai là tài khoản thử, không qua PKCS#11"
  DEPLOYER=$(cat "$DEVNET_OUT/accounts/deployer/address")
  export WERWA_HOLCO_OPERATOR=$(placeholder)
  export WERWA_HOLCO_CLAIM_SIGNER=$(placeholder)
  RPC="$V1_RPC"
  SIGN_ARGS=(--private-key "$(cat "$DEVNET_OUT/accounts/deployer/key")")
fi

log "triển khai DeployPlatform ($MODE), người gửi $DEPLOYER"
(cd "$CONTRACTS" && forge script script/DeployPlatform.s.sol \
  --rpc-url "$RPC" "${SIGN_ARGS[@]}" ${FORGE_SOLC:+--use "$FORGE_SOLC"} \
  --legacy --with-gas-price 0 --broadcast --slow) >"$WORK/forge.log" 2>&1 \
  || { tail -20 "$WORK/forge.log" >&2
       die "forge script thất bại: $(grep -m1 -E '^Error' "$WORK/forge.log" | cut -c1-300)"; }

chain_id=$(cast chain-id --rpc-url "$O1A_RPC")
run="$CONTRACTS/broadcast/DeployPlatform.s.sol/$chain_id/run-latest.json"
[ -f "$run" ] || die "không thấy $run"
# Ghép tên trường (từ ABI) với giá trị (từ bản ghi broadcast), không phụ thuộc vị trí trường.
contracts=$("$PLATFORM_DIR/read-run.sh" "$CONTRACTS/out/DeployPlatform.s.sol/DeployPlatform.json" "$run") \
  || die "không đọc được kết quả DeployPlatform (xem lỗi read-run ở trên)"
# tokenDeployer (ADR-0019) không nằm trong Deployed; đọc lại qua SeriesFactory nếu kết quả chưa có.
if ! jq -e 'has("tokenDeployer")' <<<"$contracts" >/dev/null; then
  td=$(cast call --rpc-url "$O1A_RPC" "$(jq -r .seriesFactory <<<"$contracts")" 'tokenDeployer()(address)')
  contracts=$(jq --arg v "$td" '.tokenDeployer = $v' <<<"$contracts")
fi
jq -n --argjson chainId "$chain_id" --arg mode "$MODE" --arg deployer "$DEPLOYER" \
  --arg operator "$WERWA_HOLCO_OPERATOR" --arg claimSigner "$WERWA_HOLCO_CLAIM_SIGNER" \
  --argjson delay "$WERWA_TIMELOCK_DELAY" --argjson contracts "$contracts" \
  --arg block "$(hex_to_dec "$(jq -r '.receipts[-1].blockNumber' "$run")")" '
  {chainId: $chainId, signingMode: $mode, deployer: $deployer, deployedAtBlock: ($block | tonumber),
   config: {holcoOperator: $operator, holcoClaimSigner: $claimSigner, timelockDelay: $delay},
   contracts: $contracts}' >"$WORK/devnet.json"
log "đã triển khai nền tảng: $WORK/devnet.json"
