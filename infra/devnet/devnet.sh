#!/usr/bin/env bash
# Điều khiển devnet WeRWA (Besu QBFT có cấp phép, ADR-01, ADR-02).
#
#   ./devnet.sh init        sinh khóa node, genesis, danh sách node tĩnh và danh sách cấp phép vào out/
#   ./devnet.sh up          init (nếu chưa) rồi chạy 6 node, chờ có khối
#   ./devnet.sh status      khối, peer, validator nhìn từ O1-a và O1-b
#   ./devnet.sh allowlist   sinh lại danh sách cấp phép, khởi động lại từng node (mạng luôn còn 3/4)
#   ./devnet.sh down        dừng mạng, giữ dữ liệu chuỗi
#   ./devnet.sh reset       dừng mạng, xóa dữ liệu chuỗi; giữ khóa và genesis
#   ./devnet.sh clean       dừng mạng, xóa toàn bộ out/ (khóa, genesis, dữ liệu)
#
# Biến môi trường:
#   BESU_IMAGE        mặc định hyperledger/besu:26.10.0
#   DEVNET_CHAIN_ID   mặc định 270201; chỉ có tác dụng khi sinh genesis lần đầu
#
# Khóa devnet nằm trong out/ (gitignore). Không dùng khóa devnet ở môi trường nào khác.

set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

BESU_IMAGE="${BESU_IMAGE:-hyperledger/besu:26.10.0}"
DEVNET_CHAIN_ID="${DEVNET_CHAIN_ID:-270201}"
export BESU_IMAGE

NODES=(v1 v2 v3 v4 o1-a o1-b)
VALIDATORS=(v1 v2 v3 v4)
# Tài khoản thử của devnet, được cấp phép gửi giao dịch. Module khác thêm địa chỉ qua out/allowlist.d/*.txt.
TEST_ACCOUNTS=(smoke)

declare -A NODE_IP=(
  [v1]=172.30.10.11 [v2]=172.30.10.12 [v3]=172.30.10.13 [v4]=172.30.10.14
  [o1-a]=172.30.10.21 [o1-b]=172.30.10.22
)


besu_tool() {
  docker run --rm --user "$(id -u):$(id -g)" -e JAVA_TOOL_OPTIONS= \
    -v "$DEVNET_OUT:/out" "$BESU_IMAGE" "$@" >/dev/null 2>&1
}

# gen_key <thư-mục-trong-out>: sinh khóa secp256k1 (key, address, pub) nếu chưa có.
gen_key() {
  local rel="$1" dir="$DEVNET_OUT/$1"
  [ -f "$dir/key" ] && return 0
  mkdir -p "$dir"
  besu_tool --data-path="/out/$rel" public-key export-address --to="/out/$rel/address" \
    || die "không sinh được khóa $rel"
  besu_tool --data-path="/out/$rel" --node-private-key-file="/out/$rel/key" \
    public-key export --to="/out/$rel/pub" || die "không xuất được khóa công khai $rel"
  chmod 600 "$dir/key"
  log "đã sinh khóa $rel: $(cat "$dir/address")"
}

enode() { printf 'enode://%s@%s:30303' "$(sed 's/^0x//' "$DEVNET_OUT/nodes/$1/pub")" "${NODE_IP[$1]}"; }

# Danh sách tài khoản được gửi giao dịch: tài khoản thử + mọi dòng trong out/allowlist.d/*.txt.
allowed_accounts() {
  {
    for a in "${TEST_ACCOUNTS[@]}"; do cat "$DEVNET_OUT/accounts/$a/address"; echo; done
    if compgen -G "$DEVNET_OUT/allowlist.d/*.txt" >/dev/null; then
      cat "$DEVNET_OUT"/allowlist.d/*.txt
    fi
  } | sed 's/#.*//' | tr -d ' \t\r' | tr 'A-F' 'a-f' | grep -E '^0x[0-9a-f]{40}$' | sort -u
}

toml_array() { jq -R . | jq -sc . | sed 's/,/, /g'; }

write_permissions() {
  local f="$DEVNET_OUT/shared/permissions_config.toml"
  {
    echo "# Sinh bởi devnet.sh — không sửa tay. Thêm tài khoản qua out/allowlist.d/*.txt rồi chạy ./devnet.sh allowlist"
    printf 'nodes-allowlist=%s\n' "$(for n in "${NODES[@]}"; do enode "$n"; echo; done | toml_array)"
    printf 'accounts-allowlist=%s\n' "$(allowed_accounts | toml_array)"
  } >"$f.tmp"
  mv "$f.tmp" "$f"
  # Besu ghi lại tệp cấp phép khi nạp, nên mỗi node giữ một bản riêng ghi được.
  for n in "${NODES[@]}"; do
    mkdir -p "$DEVNET_OUT/perm/$n"
    cp "$f" "$DEVNET_OUT/perm/$n/permissions_config.toml"
  done
  log "danh sách cấp phép: ${#NODES[@]} node, $(allowed_accounts | wc -l | tr -d ' ') tài khoản"
}

cmd_init() {
  need docker; need jq
  mkdir -p "$DEVNET_OUT"/{shared,nodes,accounts,allowlist.d}
  for n in "${NODES[@]}"; do gen_key "nodes/$n"; mkdir -p "$DEVNET_OUT/data/$n"; done
  for a in "${TEST_ACCOUNTS[@]}"; do gen_key "accounts/$a"; done

  local genesis="$DEVNET_OUT/shared/genesis.json"
  if [ ! -f "$genesis" ]; then
    for v in "${VALIDATORS[@]}"; do node_address "$v"; echo; done | jq -R . | jq -s . \
      >"$DEVNET_OUT/shared/validators.json"
    besu_tool rlp encode --type=QBFT_EXTRA_DATA \
      --from=/out/shared/validators.json --to=/out/shared/extradata \
      || die "không mã hóa được extraData"
    jq --argjson chainId "$DEVNET_CHAIN_ID" --arg extra "$(tr -d '\n' <"$DEVNET_OUT/shared/extradata")" \
      '.config.chainId = $chainId | .extraData = $extra' \
      "$DEVNET_DIR/config/genesis.template.json" >"$genesis"
    log "đã sinh genesis: chainId $DEVNET_CHAIN_ID, validator ${VALIDATORS[*]}"
  fi

  for n in "${NODES[@]}"; do enode "$n"; echo; done | jq -R . | jq -s . >"$DEVNET_OUT/shared/static-nodes.json"
  write_permissions
}

cmd_up() {
  cmd_init
  dc up -d
  log "chờ O1-a trả lời…"
  wait_rpc "$O1A_RPC" 180 || die "O1-a không trả lời; xem: docker compose -p $COMPOSE_PROJECT logs o1-a"
  wait_blocks "$O1A_RPC" 2 60 || die "mạng không tạo khối"
  log "devnet chạy: khối $(block_number "$O1A_RPC")"
}

cmd_status() {
  local url name
  for name in o1-a o1-b; do
    url=$O1A_RPC; [ "$name" = o1-b ] && url=$O1B_RPC
    printf '%-5s khối=%-8s peer=%s\n' "$name" "$(block_number "$url")" \
      "$(rpc "$url" net_peerCount 2>/dev/null | xargs printf '%d' 2>/dev/null || echo -)"
  done
  echo "validator (theo O1-a):"
  validators_at "$O1A_RPC" | sed 's/^/  /' || true
  dc ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'
}

cmd_allowlist() {
  [ -f "$DEVNET_OUT/shared/genesis.json" ] || die "chưa init"
  write_permissions
  # Khởi động lại từng node. Chỉ sang node kế tiếp khi node vừa khởi động đã nối lại
  # (O1 thấy đủ 5 peer) và mạng tạo thêm khối, để không bao giờ thiếu 2 validator cùng lúc.
  local n probe peers t
  for n in "${NODES[@]}"; do
    probe=$O1A_RPC; [ "$n" = o1-a ] && probe=$O1B_RPC
    dc restart "$n" >/dev/null
    sleep 5
    t=0
    while :; do
      peers=$(rpc "$probe" net_peerCount 2>/dev/null | xargs printf '%d' 2>/dev/null || echo 0)
      [ "$peers" -ge $((${#NODES[@]} - 1)) ] && break
      [ "$t" -ge 180 ] && die "$n không nối lại mạng sau khi khởi động lại"
      sleep 2; t=$((t + 2))
    done
    wait_blocks "$probe" 2 90 || die "mạng không tạo khối sau khi khởi động lại $n"
    log "đã nạp lại danh sách cấp phép: $n"
  done
}

cmd_down() { dc down; }

cmd_reset() {
  dc down
  rm -rf "$DEVNET_OUT/data"
  for n in "${NODES[@]}"; do mkdir -p "$DEVNET_OUT/data/$n"; done
  log "đã xóa dữ liệu chuỗi; khóa và genesis giữ nguyên"
}

cmd_clean() {
  dc down 2>/dev/null || true
  rm -rf "$DEVNET_OUT"
  log "đã xóa out/"
}

case "${1:-}" in
  init) cmd_init ;;
  up) cmd_up ;;
  status) cmd_status ;;
  allowlist) cmd_allowlist ;;
  down) cmd_down ;;
  reset) cmd_reset ;;
  clean) cmd_clean ;;
  *) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
