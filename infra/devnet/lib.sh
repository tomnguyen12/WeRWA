#!/usr/bin/env bash
# Hàm dùng chung cho devnet.sh và kiểm thử trong infra/test/.
# Chỉ dùng JSON-RPC chuẩn EVM qua curl + jq, cộng API QBFT của Besu ở O1 để đọc danh sách validator.

DEVNET_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEVNET_OUT="${DEVNET_OUT:-$DEVNET_DIR/out}"
COMPOSE_PROJECT="werwa-devnet"

# Cổng trên máy chạy devnet (chỉ 127.0.0.1). Ứng dụng đọc qua O1, gửi giao dịch qua V1/V2.
O1A_RPC="${O1A_RPC:-http://127.0.0.1:8545}"
O1B_RPC="${O1B_RPC:-http://127.0.0.1:8555}"
V1_RPC="${V1_RPC:-http://127.0.0.1:8645}"
V2_RPC="${V2_RPC:-http://127.0.0.1:8655}"

log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
# Trên GitHub Actions in thêm annotation để đọc được lỗi từ trang PR.
annotate() { [ -n "${GITHUB_ACTIONS:-}" ] && printf '::error::%s\n' "$*" || true; }
die() { log "LỖI: $*"; annotate "$*"; exit 1; }

dc() {
  DEVNET_UID="${DEVNET_UID:-$(id -u)}" DEVNET_GID="${DEVNET_GID:-$(id -g)}" \
    docker compose -p "$COMPOSE_PROJECT" -f "$DEVNET_DIR/compose.yaml" "$@"
}

# rpc <url> <method> [params-json] → in `.result` (chuỗi JSON thô), lỗi thì trả mã khác 0.
rpc() {
  local url="$1" method="$2" params="${3:-[]}" body
  body=$(curl -sS --max-time 5 -H 'content-type: application/json' \
    --data "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$method\",\"params\":$params}" "$url") || return 1
  if [ "$(jq -r 'has("error")' <<<"$body")" = "true" ]; then
    printf '%s\n' "$(jq -c '.error' <<<"$body")" >&2
    return 2
  fi
  jq -c '.result' <<<"$body"
}

hex_to_dec() { printf '%d\n' "$(tr -d '"' <<<"$1")"; }

# block_number <url> → số khối (thập phân), rỗng nếu node không trả lời.
block_number() {
  local r
  r=$(rpc "$1" eth_blockNumber 2>/dev/null) || { echo ""; return 0; }
  hex_to_dec "$r"
}

# wait_rpc <url> <timeout-giây>: chờ node trả lời JSON-RPC.
wait_rpc() {
  local url="$1" timeout="${2:-120}" t=0
  until [ -n "$(block_number "$url")" ]; do
    [ "$t" -ge "$timeout" ] && return 1
    sleep 2; t=$((t + 2))
  done
}

# wait_blocks <url> <số-khối-thêm> <timeout-giây>: chờ chuỗi tăng thêm n khối.
wait_blocks() {
  local url="$1" n="$2" timeout="${3:-60}" start now t=0
  start=$(block_number "$url"); [ -n "$start" ] || start=0
  while :; do
    now=$(block_number "$url")
    [ -n "$now" ] && [ "$now" -ge $((start + n)) ] && return 0
    [ "$t" -ge "$timeout" ] && return 1
    sleep 1; t=$((t + 1))
  done
}

# validators_at <url> [block-tag] → danh sách địa chỉ validator, mỗi dòng một địa chỉ.
validators_at() {
  rpc "$1" qbft_getValidatorsByBlockNumber "[\"${2:-latest}\"]" | jq -r '.[]' | tr 'A-F' 'a-f'
}

node_address() { tr 'A-F' 'a-f' <"$DEVNET_OUT/nodes/$1/address"; }
