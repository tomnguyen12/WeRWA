#!/usr/bin/env bash
# WeHSM và node HSM giả lập trên devnet WeRWA (ADR-06, US-6.04-AC4).
# Hướng dẫn gốc: tomnguyen12/WeHSM, docs/tich-hop-werwa-s7.md (commit 0b058a8).
#
#   ./signer.sh up [--mode deploy|operate]   dựng node HSM và WeHSM; chạy lại an toàn. Cần devnet đang chạy.
#                                            Mặc định: operate nếu đã có devnet/out/platform/devnet.json,
#                                            ngược lại deploy
#   ./signer.sh mode deploy|operate          đổi chế độ WeHSM (khởi động lại container wehsm)
#   ./signer.sh status                       container, chế độ, địa chỉ khóa
#   ./signer.sh down                         dừng, giữ partition và khóa
#   ./signer.sh clean                        dừng, xóa partition (mất khóa), out/, keys.json và allowlist.d/wehsm.txt
#
# Chế độ (WeHSM 0007):
#   deploy    WEHSM_DEPLOYER_ENABLED=true, chưa có WEHSM_CONTRACTS_FILE: chỉ dùng khi chạy platform/deploy.sh
#   operate   WEHSM_DEPLOYER_ENABLED=false, WEHSM_CONTRACTS_FILE=devnet.json: holcoOperator ký theo chính sách
#
# `up` lần lượt: sinh PIN, token (out/secrets/), chạy node HSM (tự tạo partition), tạo chứng chỉ client và
# đăng ký trên node (`hsm-node register-client`), tạo gói client (out/hsm-client/), `wehsm keys init`,
# `wehsm keys export` ra devnet/out/keys.json và devnet/out/allowlist.d/wehsm.txt, cấp phép trên Besu
# (`devnet.sh allowlist`, chỉ khi địa chỉ chưa có), rồi chạy WeHSM ở chế độ đã chọn.
#
# Kết quả cho platform/deploy.sh và backend (out/signer.env, quyền 600):
#   WERWA_SIGNER_RPC=http://wehsm:<token>@127.0.0.1:8745   (forge 1.8.5 gửi user:token thành Basic auth)
#   WERWA_KEYS_FILE=<devnet/out/keys.json>
# Trong mạng werwa-devnet-app, WeHSM ở http://wehsm.werwa.devnet:8545.
#
# Ảnh: WEHSM_IMAGE, WEHSM_HSM_NODE_IMAGE (mặc định ghim digest trong compose.yaml). Package GHCR riêng tư:
# máy chạy cần `docker login ghcr.io` bằng token có quyền read:packages.

set -euo pipefail
SIGNER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SIGNER_DIR/../devnet/lib.sh"

OUT="$SIGNER_DIR/out"
SECRETS="$OUT/secrets"
CLIENT="$OUT/client"
PLATFORM_JSON="$DEVNET_OUT/platform/devnet.json"
KEYS_JSON="$DEVNET_OUT/keys.json"
ALLOWLIST="$DEVNET_OUT/allowlist.d/wehsm.txt"
WEHSM_HOST_RPC=http://127.0.0.1:8745

sc() {
  SIGNER_UID="$(id -u)" SIGNER_GID="$(id -g)" \
    docker compose -p werwa-signer -f "$SIGNER_DIR/compose.yaml" "$@"
}

mode_env() { # mode_env <deploy|operate>: in biến môi trường cho compose
  case "$1" in
    deploy) echo "WEHSM_DEPLOYER_ENABLED=true WEHSM_CONTRACTS_FILE=" ;;
    operate)
      [ -f "$PLATFORM_JSON" ] || die "chế độ operate cần $PLATFORM_JSON; chạy platform/deploy.sh trước"
      echo "WEHSM_DEPLOYER_ENABLED=false WEHSM_CONTRACTS_FILE=/run/werwa/devnet.json" ;;
    *) die "chế độ phải là deploy hoặc operate" ;;
  esac
}

ensure_secrets() {
  mkdir -p "$SECRETS/hsm" "$SECRETS/wehsm" "$OUT/hsm-node" "$OUT/wehsm-data" "$DEVNET_OUT/platform"
  chmod 700 "$OUT" "$SECRETS" "$SECRETS/hsm" "$SECRETS/wehsm"
  (
    umask 077
    [ -f "$SECRETS/hsm/hsm_so_pin" ] || openssl rand -hex 16 | tr -d '\n' >"$SECRETS/hsm/hsm_so_pin"
    [ -f "$SECRETS/hsm/hsm_user_pin" ] || openssl rand -hex 16 | tr -d '\n' >"$SECRETS/hsm/hsm_user_pin"
    # SoftHSM chỉ có một PIN người dùng, dùng cho cả vai tạo khóa và vai dùng khóa (WeHSM 0004).
    cp "$SECRETS/hsm/hsm_user_pin" "$SECRETS/wehsm/pin"
    [ -f "$SECRETS/wehsm/token" ] || openssl rand -hex 32 | tr -d '\n' >"$SECRETS/wehsm/token"
  )
}

wait_node() {
  local t=0
  until sc logs hsm-node 2>&1 | grep -q 'NTLS nghe'; do
    [ "$(docker inspect -f '{{.State.Running}}' "$(sc ps -q hsm-node)" 2>/dev/null)" = true ] \
      || { sc logs hsm-node >&2; die "node HSM đã dừng"; }
    [ "$t" -ge 120 ] && die "node HSM không lên sau 120 giây"
    sleep 2; t=$((t + 2))
  done
}

# Chứng chỉ client của WeHSM, đăng ký trên node, rồi đóng gói như cài Luna client.
ensure_client() {
  [ -f "$CLIENT/client.pem" ] || "$SIGNER_DIR/hsm-client.sh" create-cert "$CLIENT" wehsm.werwa.devnet >/dev/null
  sc exec -T hsm-node sh -c 'cat > /tmp/client.pem && hsm-node register-client /tmp/client.pem && rm -f /tmp/client.pem' \
    <"$CLIENT/client.pem" >/dev/null
  sc exec -T hsm-node hsm-node server-cert >"$CLIENT/server.pem"
  rm -rf "$OUT/hsm-client"
  WEHSM_HSM_ADDRESS=hsm.werwa.devnet:1792 WEHSM_HSM_SERVER_CERT="$CLIENT/server.pem" \
    WEHSM_HSM_CLIENT_CERT="$CLIENT/client.pem" WEHSM_HSM_CLIENT_KEY="$CLIENT/client.key" \
    "$SIGNER_DIR/hsm-client.sh" package "$OUT/hsm-client" /opt/hsm-client >/dev/null
}

# wehsm_cli <lệnh wehsm…>: chạy một lệnh của ảnh WeHSM (không phải serve), cùng cấu hình HSM với container.
wehsm_cli() {
  sc run --rm --no-deps -T -e WEHSM_ADMIN_PIN_FILE=/run/secrets/pin -v "$DEVNET_OUT:/out" wehsm "$@"
}

ensure_keys() {
  wehsm_cli keys init >/dev/null
  wehsm_cli keys export --out /out >/dev/null
  [ "$(jq -r '[.deployer, .holcoOperator, .holcoClaimSigner] | map(select(test("^0x[0-9a-fA-F]{40}$"))) | length' "$KEYS_JSON")" = 3 ] \
    || die "keys.json không đủ ba địa chỉ"
  log "khóa trong HSM: $(jq -c . "$KEYS_JSON")"
}

# Cấp phép trên Besu chỉ khi có địa chỉ chưa nằm trong danh sách (devnet.sh allowlist khởi động lại cả 6 node).
ensure_allowlist() {
  local perm="$DEVNET_OUT/shared/permissions_config.toml" a missing=0
  while read -r a; do
    [ -n "$a" ] || continue
    grep -qi "\"$a\"" "$perm" || missing=1
  done < <(sed 's/#.*//' "$ALLOWLIST" | tr -d ' \t\r')
  if [ "$missing" = 1 ]; then
    log "cấp phép địa chỉ WeHSM trên Besu (khởi động lại lần lượt từng node)"
    "$DEVNET_DIR/devnet.sh" allowlist
  fi
}

wait_wehsm() {
  local t=0 cid
  until curl -sf "$WEHSM_HOST_RPC/health" >/dev/null 2>&1; do
    cid=$(sc ps -q wehsm)
    if [ -z "$cid" ] || [ "$(docker inspect -f '{{.RestartCount}}' "$cid")" -gt 3 ]; then
      sc logs --tail 60 wehsm >&2; die "WeHSM không chạy được"
    fi
    [ "$t" -ge 120 ] && { sc logs --tail 60 wehsm >&2; die "WeHSM không sẵn sàng sau 120 giây"; }
    sleep 2; t=$((t + 2))
  done
}

start_wehsm() { # start_wehsm <deploy|operate>
  local mode="$1"
  env $(mode_env "$mode") SIGNER_UID="$(id -u)" SIGNER_GID="$(id -g)" \
    docker compose -p werwa-signer -f "$SIGNER_DIR/compose.yaml" up -d --force-recreate --no-deps wehsm >/dev/null
  echo "$mode" >"$OUT/mode"
  wait_wehsm
  log "WeHSM chạy ở chế độ $mode: $WEHSM_HOST_RPC"
}

write_env() {
  (umask 077; cat >"$OUT/signer.env" <<EOF
# Sinh bởi infra/signer/signer.sh — chỉ cho devnet, có token, không commit.
WERWA_SIGNER_RPC=http://wehsm:$(cat "$SECRETS/wehsm/token")@127.0.0.1:8745
WERWA_KEYS_FILE=$KEYS_JSON
EOF
  )
}

cmd_up() {
  local mode=""
  case "${1:-}" in
    "") ;;
    --mode) mode="${2:-}"; [ -n "$mode" ] || die "thiếu chế độ sau --mode" ;;
    *) die "tham số lạ: $*" ;;
  esac
  [ -n "$mode" ] || { [ -f "$PLATFORM_JSON" ] && mode=operate || mode=deploy; }
  mode_env "$mode" >/dev/null
  need docker; need openssl; need jq; need curl
  wait_rpc "$O1A_RPC" 30 || die "devnet chưa chạy; chạy devnet/devnet.sh up trước"
  ensure_app_network
  ensure_secrets
  sc pull -q 2>/dev/null || sc pull || die "không tải được ảnh WeHSM từ ghcr.io; chạy docker login ghcr.io (token read:packages)"
  sc up -d hsm-node >/dev/null
  wait_node
  ensure_client
  ensure_keys
  ensure_allowlist
  start_wehsm "$mode"
  write_env
  log "biến cho platform/deploy.sh: $OUT/signer.env"
}

cmd_status() {
  sc ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'
  echo "chế độ: $(cat "$OUT/mode" 2>/dev/null || echo -)"
  [ -f "$KEYS_JSON" ] && jq . "$KEYS_JSON" || true
}

case "${1:-}" in
  up) shift; cmd_up "$@" ;;
  mode) [ -f "$OUT/signer.env" ] || die "chưa dựng; chạy ./signer.sh up"; start_wehsm "${2:-}" ;;
  status) cmd_status ;;
  down) sc down ;;
  clean)
    sc down -v 2>/dev/null || true
    rm -rf "$OUT" "$KEYS_JSON" "$ALLOWLIST"
    log "đã xóa partition, khóa, out/ của signer; keys.json và allowlist.d/wehsm.txt"
    ;;
  *) sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
