#!/usr/bin/env bash
# Kiểm thử khói của devnet (PR khởi tạo infra/): mạng đúng cấu hình đã chốt ở ADR-01, ADR-02.
# Chạy sau `infra/devnet/devnet.sh up`. Cần: curl, jq, docker, cast (Foundry).
#
#   S1  khối 2 giây
#   S2  đúng 4 validator V1–V4, đọc qua O1
#   S3  hai O1 cùng sổ
#   S4  phí gas 0: giao dịch giá gas 0 từ tài khoản được cấp phép lên khối
#   S5  tài khoản ngoài danh sách bị từ chối
#   S6  node ngoài danh sách không kết nối được, không nhận được khối

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../devnet" && pwd)/lib.sh"

need() { command -v "$1" >/dev/null 2>&1 || die "thiếu lệnh $1"; }
need cast; need jq; need docker

FAILED=0
pass() { log "ĐẠT  $1"; }
fail() { log "TRƯỢT $1"; FAILED=1; }

block_ts() { hex_to_dec "$(rpc "$O1A_RPC" eth_getBlockByNumber "[\"$(printf '0x%x' "$1")\",false]" | jq '.timestamp')"; }

# S1 — khối 2 giây: đo 10 khoảng liên tiếp gần nhất.
wait_blocks "$O1A_RPC" 11 60 || die "mạng không tạo đủ khối"
head_n=$(block_number "$O1A_RPC")
span=$(( $(block_ts "$head_n") - $(block_ts $((head_n - 10))) ))
if [ "$span" -ge 18 ] && [ "$span" -le 25 ]; then pass "S1 khối 2 giây (10 khối trong ${span}s)"
else fail "S1 khối 2 giây: 10 khối trong ${span}s"; fi

# S2 — đúng 4 validator, đúng khóa V1–V4.
expected=$(for v in v1 v2 v3 v4; do node_address "$v"; echo; done | sort)
actual=$(validators_at "$O1A_RPC" | sort)
if [ "$expected" = "$actual" ]; then pass "S2 bốn validator V1–V4"
else fail "S2 validator: mong đợi [$expected], thực tế [$actual]"; fi

# S3 — hai O1 cùng sổ ở cùng một khối.
h=$(( $(block_number "$O1A_RPC") - 1 ))
wait_blocks "$O1B_RPC" 0 30 || true
ha=$(rpc "$O1A_RPC" eth_getBlockByNumber "[\"$(printf '0x%x' $h)\",false]" | jq -r .hash)
hb=$(rpc "$O1B_RPC" eth_getBlockByNumber "[\"$(printf '0x%x' $h)\",false]" | jq -r .hash)
if [ "$ha" = "$hb" ] && [ "$ha" != null ]; then pass "S3 O1-a và O1-b cùng mã băm khối $h"
else fail "S3 O1 lệch ở khối $h: $ha / $hb"; fi

# S4 — phí gas 0. Gửi qua V1, đọc biên nhận qua O1.
SMOKE_KEY=$(cat "$DEVNET_OUT/accounts/smoke/key")
base_fee=$(rpc "$O1A_RPC" eth_getBlockByNumber '["latest",false]' | jq -r '.baseFeePerGas // "0x0"')
tx=$(cast send --async --legacy --gas-price 0 --gas-limit 21000 --private-key "$SMOKE_KEY" \
  --rpc-url "$V1_RPC" 0x000000000000000000000000000000000000dEaD 2>&1) || true
if [[ "$tx" =~ ^0x[0-9a-fA-F]{64}$ ]]; then
  receipt=""
  for _ in $(seq 1 15); do
    receipt=$(rpc "$O1A_RPC" eth_getTransactionReceipt "[\"$tx\"]" 2>/dev/null || true)
    [ -n "$receipt" ] && [ "$receipt" != null ] && break
    sleep 1
  done
  status=$(jq -r '.status // empty' <<<"${receipt:-null}")
  gas_price=$(jq -r '.effectiveGasPrice // empty' <<<"${receipt:-null}")
  if [ "$status" = 0x1 ] && [ "$(hex_to_dec "${gas_price:-0x1}")" = 0 ] && [ "$(hex_to_dec "$base_fee")" = 0 ]; then
    pass "S4 phí gas 0 (baseFee 0, giá gas thực 0, tx $tx)"
  else fail "S4 phí gas: status=$status effectiveGasPrice=$gas_price baseFee=$base_fee"; fi
else fail "S4 không gửi được giao dịch giá gas 0: $tx"; fi

# S5 — tài khoản ngoài danh sách bị từ chối.
stranger_key=$(cast wallet new --json | jq -r '(.data // .) | if type == "array" then .[0] else . end | .private_key')
out=$(cast send --async --legacy --gas-price 0 --gas-limit 21000 --private-key "$stranger_key" \
  --rpc-url "$V1_RPC" 0x000000000000000000000000000000000000dEaD 2>&1) && rc=0 || rc=$?
if [ "$rc" -ne 0 ] && grep -qiE 'permission|not authori|allowlist' <<<"$out"; then
  pass "S5 tài khoản ngoài danh sách bị từ chối"
else fail "S5 tài khoản ngoài danh sách không bị chặn: $out"; fi

# S6 — node lạ (khóa mới, cùng genesis, cùng danh sách node tĩnh) không vào được mạng.
ROGUE=werwa-devnet-rogue
rogue_dir=$(mktemp -d)
cleanup() { docker rm -f "$ROGUE" >/dev/null 2>&1 || true; rm -rf "$rogue_dir"; }
trap cleanup EXIT
chmod 777 "$rogue_dir"
ROGUE_RPC=http://127.0.0.1:8599
docker run -d --name "$ROGUE" --network werwa-devnet-p2p --ip 172.30.10.99 -p 127.0.0.1:8599:8545 \
  -v "$DEVNET_OUT/shared/genesis.json:/shared/genesis.json:ro" \
  -v "$DEVNET_OUT/shared/static-nodes.json:/shared/static-nodes.json:ro" \
  -v "$rogue_dir:/data" "${BESU_IMAGE:-hyperledger/besu:26.10.0}" \
  --genesis-file=/shared/genesis.json --data-path=/data --static-nodes-file=/shared/static-nodes.json \
  --discovery-enabled=false --p2p-host=172.30.10.99 --min-gas-price=0 \
  --rpc-http-enabled --rpc-http-host=0.0.0.0 --rpc-http-api=ETH,NET --host-allowlist='*' >/dev/null
wait_rpc "$ROGUE_RPC" 120 || die "node lạ không khởi động được"
sleep 20
rogue_peers=$(hex_to_dec "$(rpc "$ROGUE_RPC" net_peerCount)")
rogue_head=$(block_number "$ROGUE_RPC")
if [ "$rogue_peers" = 0 ] && [ "$rogue_head" = 0 ]; then pass "S6 node ngoài danh sách: 0 peer, không nhận khối"
else fail "S6 node ngoài danh sách: $rogue_peers peer, khối $rogue_head"; fi

[ "$FAILED" = 0 ] || die "kiểm thử khói devnet trượt"
log "kiểm thử khói devnet: tất cả đạt"
