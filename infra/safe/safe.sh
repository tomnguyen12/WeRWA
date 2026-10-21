#!/usr/bin/env bash
# Safe 1.4.1 trên devnet WeRWA, và Safe của từng bên theo ADR-05.
#
#   ./safe.sh up      triển khai hợp đồng Safe 1.4.1 (nếu chưa có) rồi tạo các Safe; chạy lại an toàn
#   ./safe.sh show    in danh sách Safe đã tạo
#
# Hợp đồng Safe: bytecode lấy từ gói npm @safe-global/safe-contracts@1.4.1 (kiểm integrity sha512),
# triển khai qua Safe Singleton Factory (có sẵn trong genesis) với salt 0, nên địa chỉ và mã băm code
# trùng bản chính thức ở safe-1.4.1.json. Script dừng nếu lệch.
#
# Safe tạo ra (owner là EOA thử của devnet; khóa trong devnet/out/safe/signers/, gitignore):
#   ubnd, vsdc, bidv, holco     Safe tổ chức, 3 người ký, ngưỡng 2 (devnet; thật do tổ chức tự đặt)
#   governance3of3              Safe liên bên: owner là Safe UBND, VSDC, Holco; ngưỡng 3
#   enforcement2of2             Safe liên bên: owner là Safe VSDC, Holco; ngưỡng 2
#
# Mọi Safe dùng singleton SafeL2 (phát sự kiện cho indexer) và CompatibilityFallbackHandler
# (để Safe tổ chức xác thực được chữ ký hợp đồng EIP-1271 khi làm owner của Safe liên bên).
# Không bật module, không đặt guard.
#
# Giao dịch gửi bằng tài khoản devnet `bootstrap` qua V1; mọi lệnh đọc qua O1 (ADR-12).
# Kết quả: devnet/out/safe/safes.json và devnet/out/safe/safes.env (biến cho DeployPlatform).

set -euo pipefail
SAFE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SAFE_DIR/../devnet/lib.sh"

need cast; need jq; need curl; need openssl

REF="$SAFE_DIR/safe-1.4.1.json"
WORK="$DEVNET_OUT/safe"
PKG="$WORK/pkg"
SIGNERS="$WORK/signers"
BOOTSTRAP_KEY_FILE="$DEVNET_OUT/accounts/bootstrap/key"
ORGS=(ubnd vsdc bidv holco)
ORG_SIGNERS=3
ORG_THRESHOLD=2
ZERO=0x0000000000000000000000000000000000000000

ref() { jq -r "$1" "$REF"; }
lower() { tr 'A-F' 'a-f'; }
code_hash() { local c; c=$(cast code --rpc-url "$O1A_RPC" "$1"); [ "$c" = 0x ] && echo none || cast keccak "$c"; }

send() { # send <to> <calldata> → mã giao dịch; dừng nếu giao dịch thất bại
  local out
  out=$(cast send --json --legacy --gas-price 0 --gas-limit 15000000 \
    --private-key "$(cat "$BOOTSTRAP_KEY_FILE")" --rpc-url "$V1_RPC" "$1" "$2") || die "gửi giao dịch tới $1 thất bại"
  [ "$(jq -r .status <<<"$out")" = 0x1 ] || die "giao dịch $(jq -r .transactionHash <<<"$out") bị revert"
  # Chờ O1 có khối chứa giao dịch trước khi đọc tiếp.
  local blk; blk=$(hex_to_dec "$(jq -r .blockNumber <<<"$out")")
  until [ "$(block_number "$O1A_RPC")" -ge "$blk" ]; do sleep 1; done
  jq -r .transactionHash <<<"$out"
}

fetch_package() {
  local tgz="$WORK/safe-contracts-1.4.1.tgz" want got
  mkdir -p "$WORK"
  [ -f "$tgz" ] || curl -sSfL -o "$tgz" "$(ref .package.tarball)" || die "không tải được gói Safe"
  want=$(ref .package.integrity | sed 's/^sha512-//')
  got=$(openssl dgst -sha512 -binary "$tgz" | base64 | tr -d '\n')
  [ "$want" = "$got" ] || { rm -f "$tgz"; die "integrity gói Safe không khớp"; }
  rm -rf "$PKG"; mkdir -p "$PKG"; tar -xzf "$tgz" -C "$PKG" --strip-components=1
}

deploy_singletons() {
  local factory salt name addr want_hash have art initcode
  factory=$(ref .singletonFactory); salt=$(ref .salt)
  [ "$(code_hash "$factory")" != none ] || die "không có Safe Singleton Factory ở $factory; genesis cũ? chạy devnet.sh clean rồi up"
  for name in $(ref '.contracts | keys[]'); do
    addr=$(ref ".contracts.$name.address"); want_hash=$(ref ".contracts.$name.codeHash")
    have=$(code_hash "$addr")
    if [ "$have" = none ]; then
      [ -d "$PKG" ] || fetch_package
      art="$PKG/build/artifacts/$(ref ".contracts.$name.artifact")"
      initcode=$(jq -r .bytecode "$art")
      send "$factory" "${salt}${initcode#0x}" >/dev/null
      have=$(code_hash "$addr")
      log "đã triển khai $name tại $addr"
    fi
    [ "$have" = "$want_hash" ] || die "$name tại $addr: mã băm code $have khác bản chính thức $want_hash"
  done
}

signer() { # signer <org> <i> → địa chỉ; sinh khóa nếu chưa có
  local dir="$SIGNERS/$1-$2" w
  if [ ! -f "$dir/key" ]; then
    mkdir -p "$dir"
    w=$(cast wallet new --json | jq -c '(.data // .) | if type == "array" then .[0] else . end')
    (umask 077; jq -r .private_key <<<"$w" >"$dir/key")
    jq -r .address <<<"$w" >"$dir/address"
  fi
  cat "$dir/address"
}

# create_safe <tên> <ngưỡng> <owner...> → địa chỉ Safe (tạo nếu chưa có)
create_safe() {
  local name="$1" threshold="$2"; shift 2
  local owners init salt_nonce factory singleton handler addr
  factory=$(ref .contracts.SafeProxyFactory.address)
  singleton=$(ref .contracts.SafeL2.address)
  handler=$(ref .contracts.CompatibilityFallbackHandler.address)
  owners="[$(IFS=,; echo "$*")]"
  init=$(cast calldata "setup(address[],uint256,address,bytes,address,address,uint256,address)" \
    "$owners" "$threshold" "$ZERO" 0x "$handler" "$ZERO" 0 "$ZERO")
  salt_nonce=$(cast to-dec "$(cast keccak "WERWA.DEVNET.SAFE.$name")")
  # Địa chỉ theo công thức của SafeProxyFactory 1.4.1:
  #   salt = keccak256(keccak256(initializer) ‖ saltNonce); code = proxyCreationCode ‖ uint256(singleton)
  local creation salt
  creation=$(cast call --rpc-url "$O1A_RPC" "$factory" "proxyCreationCode()(bytes)")
  salt=$(cast keccak "$(cast concat-hex "$(cast keccak "$init")" "$(cast to-uint256 "$salt_nonce")")")
  addr=$(compute_create2 "$factory" "$salt" "$(cast concat-hex "$creation" "$(cast abi-encode "f(address)" "$singleton")")")
  if [ "$(code_hash "$addr")" = none ]; then
    send "$factory" "$(cast calldata "createProxyWithNonce(address,bytes,uint256)" "$singleton" "$init" "$salt_nonce")" >/dev/null
    [ "$(code_hash "$addr")" != none ] || die "không tạo được Safe $name tại địa chỉ dự tính $addr"
    log "đã tạo Safe $name tại $addr (ngưỡng $threshold/$#)"
  fi
  echo "$addr"
}

compute_create2() { # compute_create2 <deployer> <salt> <init-code>
  local h x
  h=$(cast keccak "$3")
  x=$(cast keccak "0xff$(sed 's/^0x//' <<<"$1" | lower)$(sed 's/^0x//' <<<"$2")$(sed 's/^0x//' <<<"$h")")
  cast to-check-sum-address "0x${x:26}"
}

cmd_up() {
  [ -f "$BOOTSTRAP_KEY_FILE" ] || die "thiếu tài khoản bootstrap; chạy devnet/devnet.sh up"
  wait_rpc "$O1A_RPC" 60 || die "O1-a không trả lời"
  deploy_singletons

  local org i owners json
  declare -A SAFE
  json=$(jq -n --argjson chainId "$(cast chain-id --rpc-url "$O1A_RPC")" \
    --arg version "$(ref .version)" --argjson contracts "$(ref '.contracts | map_values(.address)')" \
    '{chainId: $chainId, safeVersion: $version, contracts: $contracts, safes: {}}')
  for org in "${ORGS[@]}"; do
    owners=()
    for i in $(seq 1 "$ORG_SIGNERS"); do owners+=("$(signer "$org" "$i")"); done
    SAFE[$org]=$(create_safe "$org" "$ORG_THRESHOLD" "${owners[@]}")
    json=$(jq --arg k "$org" --arg a "${SAFE[$org]}" --argjson t "$ORG_THRESHOLD" \
      --argjson o "$(printf '%s\n' "${owners[@]}" | jq -R . | jq -s .)" \
      '.safes[$k] = {address: $a, kind: "organization", threshold: $t, owners: $o}' <<<"$json")
  done
  SAFE[governance3of3]=$(create_safe governance3of3 3 "${SAFE[ubnd]}" "${SAFE[vsdc]}" "${SAFE[holco]}")
  SAFE[enforcement2of2]=$(create_safe enforcement2of2 2 "${SAFE[vsdc]}" "${SAFE[holco]}")
  json=$(jq --arg g "${SAFE[governance3of3]}" --arg e "${SAFE[enforcement2of2]}" \
    --arg u "${SAFE[ubnd]}" --arg v "${SAFE[vsdc]}" --arg h "${SAFE[holco]}" '
    .safes.governance3of3 = {address: $g, kind: "inter-party", threshold: 3, owners: [$u, $v, $h], members: ["ubnd", "vsdc", "holco"]} |
    .safes.enforcement2of2 = {address: $e, kind: "inter-party", threshold: 2, owners: [$v, $h], members: ["vsdc", "holco"]}' <<<"$json")
  echo "$json" >"$WORK/safes.json"

  cat >"$WORK/safes.env" <<EOF
# Sinh bởi infra/safe/safe.sh — địa chỉ Safe trên devnet (không phải khóa).
WERWA_UBND_SAFE=${SAFE[ubnd]}
WERWA_VSDC_SAFE=${SAFE[vsdc]}
WERWA_BIDV_SAFE=${SAFE[bidv]}
WERWA_HOLCO_SAFE=${SAFE[holco]}
WERWA_GOVERNANCE_3OF3=${SAFE[governance3of3]}
WERWA_ENFORCEMENT_2OF2=${SAFE[enforcement2of2]}
WERWA_CANCELLERS=${SAFE[ubnd]},${SAFE[vsdc]},${SAFE[holco]}
EOF
  log "Safe sẵn sàng: $WORK/safes.json"
}

cmd_show() { jq -r '.safes | to_entries[] | "\(.key)\t\(.value.address)\t\(.value.threshold)/\(.value.owners | length)"' "$WORK/safes.json"; }

case "${1:-}" in
  up) cmd_up ;;
  show) cmd_show ;;
  *) sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
