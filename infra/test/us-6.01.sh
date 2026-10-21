#!/usr/bin/env bash
# US-6.01 — Mạng Besu có cấp phép: chịu được một node lỗi (docs/design/06-user-story.md).
# Chạy sau `infra/devnet/devnet.sh up`. Cần: curl, jq, docker, cast (Foundry).
#
#   US-6.01-AC1  Cho 4 validator QBFT và O1 ở mỗi vùng · Khi tắt một validator · Thì mạng vẫn tạo khối
#                a) lần lượt tắt từng validator V1, V2, V3, V4
#                b) mất cả vùng A (V1 và O1-a): đọc qua O1-b, mạng vẫn tạo khối (ADR-02)
#   US-6.01-AC2  Cho tắt hai validator · Khi bật lại · Thì mạng dừng tạo khối rồi tự chạy tiếp, không mất dữ liệu
#
# Mỗi bước đọc qua O1, không đọc từ validator (ADR-12). Script luôn bật lại mọi node khi kết thúc.
#
# US-6.01-AC3 (cảnh báo trên màn hình vận hành) không thuộc luồng infra/ ở lượt này.

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../devnet" && pwd)/lib.sh"

need cast; need jq; need docker
trap 'annotate "kiểm thử dừng ở dòng $LINENO: $BASH_COMMAND"' ERR
trap 'dc start v1 v2 v3 v4 o1-a o1-b >/dev/null 2>&1 || true' EXIT

ALL_PEERS=5           # mỗi node nối với 5 node còn lại
PRODUCE_TIMEOUT=30    # 3 khối với 3/4 validator; khi node tắt đến lượt đề xuất, QBFT đổi vòng sau 4 giây
RECOVER_TIMEOUT=240   # sau khi mất đồng thuận, thời gian chờ đổi vòng tăng theo cấp số nhân

stop() { dc stop "$@" >/dev/null; log "đã tắt $*"; }
start() { dc start "$@" >/dev/null; log "đã bật $*"; }

# Chờ mạng trở lại đủ 6 node trước mỗi bước, để các bước không chồng lên nhau.
settle() {
  wait_rpc "$O1A_RPC" 180 || die "O1-a không trả lời"
  wait_peers "$O1A_RPC" "$ALL_PEERS" 180 || die "O1-a chưa thấy đủ $ALL_PEERS peer"
  wait_peers "$O1B_RPC" "$ALL_PEERS" 180 || die "O1-b chưa thấy đủ $ALL_PEERS peer"
  wait_blocks "$O1A_RPC" 2 60 || die "mạng chưa tạo khối"
}

settle

# ── US-6.01-AC1 a) tắt từng validator ──────────────────────────────────────────
for v in v1 v2 v3 v4; do
  stop "$v"
  if wait_blocks "$O1A_RPC" 3 "$PRODUCE_TIMEOUT"; then
    pass "US-6.01-AC1 tắt $v: mạng vẫn tạo khối (khối $(block_number "$O1A_RPC"))"
  else
    fail "US-6.01-AC1 tắt $v: không có 3 khối mới trong ${PRODUCE_TIMEOUT}s"
  fi
  start "$v"
  settle
done

# ── US-6.01-AC1 b) mất vùng A: V1 và O1-a ──────────────────────────────────────
stop v1 o1-a
if wait_blocks "$O1B_RPC" 3 "$PRODUCE_TIMEOUT"; then
  pass "US-6.01-AC1 mất vùng A (V1, O1-a): V2, V3, V4 vẫn tạo khối, đọc qua O1-b (khối $(block_number "$O1B_RPC"))"
else
  fail "US-6.01-AC1 mất vùng A: O1-b không thấy 3 khối mới trong ${PRODUCE_TIMEOUT}s"
fi
start v1 o1-a
settle

# ── US-6.01-AC2 tắt hai validator, bật lại ─────────────────────────────────────
# Dữ liệu trước sự cố: một giao dịch đã lên khối và mã băm của khối đầu chuỗi.
SMOKE_KEY=$(cat "$DEVNET_OUT/accounts/smoke/key")
tx_json=$(cast send --json --legacy --gas-price 0 --gas-limit 21000 --private-key "$SMOKE_KEY" \
  --rpc-url "$V1_RPC" 0x000000000000000000000000000000000000dEaD)
tx_hash=$(jq -r '.transactionHash' <<<"$tx_json")
tx_block=$(hex_to_dec "$(jq -r '.blockNumber' <<<"$tx_json")")
wait_blocks "$O1A_RPC" 1 30 || true
mark=$(block_number "$O1A_RPC")
mark_hash=$(block_hash "$O1A_RPC" "$mark")
log "trước sự cố: khối $mark ($mark_hash), giao dịch $tx_hash ở khối $tx_block"

stop v2 v4
sleep 6   # khối đang được đề xuất lúc tắt có thể vẫn chốt
halted_at=$(block_number "$O1A_RPC")
sleep 20
after=$(block_number "$O1A_RPC")
if [ "$after" = "$halted_at" ]; then
  pass "US-6.01-AC2 tắt V2, V4: mạng dừng tạo khối ở khối $halted_at (20 giây không có khối mới)"
else
  fail "US-6.01-AC2 tắt V2, V4: mạng vẫn tạo khối ($halted_at → $after), sai với QBFT 3/4"
fi

start v2 v4
recovered=0
if wait_blocks "$O1A_RPC" 3 "$RECOVER_TIMEOUT"; then recovered=1; fi
if [ "$recovered" = 1 ]; then
  pass "US-6.01-AC2 bật lại V2, V4: mạng tự chạy tiếp (khối $(block_number "$O1A_RPC"))"
else
  fail "US-6.01-AC2 bật lại V2, V4: không có khối mới trong ${RECOVER_TIMEOUT}s"
fi

# Không mất dữ liệu: khối trước sự cố giữ nguyên mã băm ở cả hai O1, giao dịch vẫn còn và đúng khối.
ha=$(block_hash "$O1A_RPC" "$mark")
hb=$(block_hash "$O1B_RPC" "$mark")
receipt=$(rpc "$O1B_RPC" eth_getTransactionReceipt "[\"$tx_hash\"]")
r_block=$(hex_to_dec "$(jq -r '.blockNumber' <<<"$receipt")")
r_status=$(jq -r '.status' <<<"$receipt")
if [ "$ha" = "$mark_hash" ] && [ "$hb" = "$mark_hash" ] && [ "$r_block" = "$tx_block" ] && [ "$r_status" = 0x1 ]; then
  pass "US-6.01-AC2 không mất dữ liệu: khối $mark giữ mã băm ở O1-a và O1-b; giao dịch vẫn ở khối $tx_block"
else
  fail "US-6.01-AC2 dữ liệu lệch: khối $mark $mark_hash → $ha / $hb; giao dịch khối $tx_block → $r_block ($r_status)"
fi

settle
[ "$FAILED" = 0 ] || die "US-6.01 trượt"
log "US-6.01: AC1, AC2 đạt"
