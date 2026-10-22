#!/usr/bin/env bash
# Dựng trọn devnet WeRWA bằng một lệnh, cho đội backend và frontend.
#
#   ./werwa.sh up [--without monitoring]   kiểm công cụ, rồi dựng lần lượt: mạng dùng chung, devnet Besu,
#                                          Safe các bên, DeployPlatform, PostgreSQL và NATS, Keycloak,
#                                          giám sát. Chạy lại an toàn: phần đã có thì giữ nguyên
#   ./werwa.sh status                      bảng gộp trạng thái mọi phần
#   ./werwa.sh down                        dừng mọi phần, giữ dữ liệu (chuỗi, DB, người dùng thử)
#   ./werwa.sh reset                       xóa dữ liệu chuỗi, DB, realm và giám sát; giữ khóa, mật khẩu,
#                                          khóa OTP. `up` sau đó triển khai lại nền tảng lên chuỗi mới
#   ./werwa.sh clean                       xóa mọi thứ của devnet: container, volume, mạng, mọi out/
#
# Các script con (devnet/, safe/, platform/, services/, keycloak/, monitoring/) vẫn chạy riêng được;
# werwa.sh chỉ gọi chúng theo đúng thứ tự. Tắt theo thứ tự ngược lại: giám sát dùng mạng p2p của devnet,
# Keycloak lưu trên PostgreSQL của services.
#
# Chế độ ký của DeployPlatform: mặc định `devnet` (tài khoản thử, chưa mint được); đặt WERWA_SIGNER_RPC
# (module ký SoftHSM) thì platform/deploy.sh chuyển sang `pkcs11`.
#
# Công cụ cần: docker (Compose v2), curl, jq, openssl, base32, python3, forge và cast (Foundry 1.8.5),
# nats (natscli 0.5.0); submodule của contracts/. Lệch phiên bản Foundry, natscli thì dừng; đặt
# WERWA_SKIP_VERSION_CHECK=1 để bỏ qua kiểm phiên bản (không bỏ qua kiểm có công cụ).

set -euo pipefail
INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$INFRA/devnet/lib.sh"

FOUNDRY_VERSION=1.8.5
NATSCLI_VERSION=0.5.0
P2P_NETWORK=werwa-devnet-p2p
PROJECTS=(werwa-monitoring werwa-keycloak werwa-services werwa-devnet)
OUT_DIRS=(devnet/out services/out keycloak/out monitoring/out out)

step() { log "── $* ──"; }
run() { local name="$1"; shift; "$@" || die "bước \"$name\" thất bại: $*"; }

preflight() {
  local missing=() t
  for t in docker curl jq openssl base32 python3 forge cast nats; do
    command -v "$t" >/dev/null 2>&1 || missing+=("$t")
  done
  docker compose version >/dev/null 2>&1 || missing+=("docker compose (v2)")
  [ "${#missing[@]}" = 0 ] || die "thiếu công cụ: ${missing[*]} (xem đầu infra/werwa.sh)"
  [ -f "$INFRA/../contracts/lib/trex/package.json" ] \
    || die "thiếu submodule của contracts/; chạy: git submodule update --init --recursive"
  docker info >/dev/null 2>&1 || die "Docker không chạy hoặc không có quyền dùng Docker"
  if [ "${WERWA_SKIP_VERSION_CHECK:-0}" != 1 ]; then
    local fv nv bad=()
    fv=$(forge --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
    nv=$(nats --version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
    [ "$fv" = "$FOUNDRY_VERSION" ] || bad+=("forge $fv (cần $FOUNDRY_VERSION)")
    [ "$nv" = "$NATSCLI_VERSION" ] || bad+=("nats $nv (cần $NATSCLI_VERSION)")
    [ "${#bad[@]}" = 0 ] || die "lệch phiên bản: ${bad[*]}; đặt WERWA_SKIP_VERSION_CHECK=1 nếu chấp nhận"
  fi
}

cmd_up() {
  local monitoring=1
  case "${1:-}" in
    "") ;;
    --without) [ "${2:-}" = monitoring ] || die "chỉ hỗ trợ --without monitoring"; monitoring=0 ;;
    *) die "tham số lạ: $*" ;;
  esac
  local t0; t0=$(date +%s)
  step "kiểm công cụ"; preflight
  step "mạng dùng chung"; ensure_app_network
  step "devnet Besu";            run devnet     "$INFRA/devnet/devnet.sh" up
  step "Safe các bên";           run safe       "$INFRA/safe/safe.sh" up
  step "triển khai nền tảng";    run platform   "$INFRA/platform/deploy.sh"
  step "PostgreSQL, NATS";       run services   "$INFRA/services/services.sh" up
  step "Keycloak";               run keycloak   "$INFRA/keycloak/keycloak.sh" up
  if [ "$monitoring" = 1 ]; then
    step "giám sát";             run monitoring "$INFRA/monitoring/monitoring.sh" up
  else
    log "bỏ qua giám sát (--without monitoring)"
  fi
  log "devnet WeRWA sẵn sàng sau $(( $(date +%s) - t0 )) giây"
  cmd_status
}

# ── status ────────────────────────────────────────────────────────────────────
ok_http() { curl -sf --max-time 3 "$1" >/dev/null 2>&1 && echo "sẵn sàng" || echo "không chạy"; }
row() { printf '%-20s %-12s %s\n' "$1" "$2" "${3:-}"; }

cmd_status() {
  local blk vals plat="$DEVNET_OUT/platform/devnet.json" pg="không chạy" alerts
  row "PHẦN" "TRẠNG THÁI" "CHI TIẾT"
  blk=$(block_number "$O1A_RPC")
  if [ -n "$blk" ]; then
    vals=$(validators_at "$O1A_RPC" 2>/dev/null | grep -c . || true)
    row "chuỗi Besu" "chạy" "khối $blk, $vals validator, đọc O1-a $O1A_RPC, gửi V1 $V1_RPC"
  else row "chuỗi Besu" "không chạy"; fi
  if [ -f "$DEVNET_OUT/safe/safes.json" ]; then
    row "Safe" "có" "$(jq '.safes | length' "$DEVNET_OUT/safe/safes.json") Safe ($DEVNET_OUT/safe/safes.json)"
  else row "Safe" "chưa có"; fi
  if [ -f "$plat" ] && [ -n "$blk" ] && [ "$(cast code --rpc-url "$O1A_RPC" "$(jq -r .contracts.timelock "$plat")" 2>/dev/null)" != 0x ]; then
    row "nền tảng" "đã triển khai" "$(jq '.contracts | length' "$plat") hợp đồng, chế độ $(jq -r .signingMode "$plat"), khối $(jq -r .deployedAtBlock "$plat")"
  else row "nền tảng" "chưa triển khai"; fi
  if [ -f "$INFRA/services/out/.env" ] && docker compose -p werwa-services -f "$INFRA/services/compose.yaml" \
       --env-file "$INFRA/services/out/.env" exec -T postgres pg_isready -U postgres -q >/dev/null 2>&1; then pg="sẵn sàng"; fi
  row "PostgreSQL" "$pg" "127.0.0.1:5432, DB werwa"
  row "NATS JetStream" "$(ok_http 'http://127.0.0.1:8222/healthz?js-enabled-only=true')" "127.0.0.1:4222, stream WERWA"
  row "Keycloak" "$(ok_http http://127.0.0.1:9180/health/ready)" "http://localhost:8180/realms/werwa"
  row "Prometheus" "$(ok_http http://127.0.0.1:9090/-/ready)" "http://127.0.0.1:9090"
  if alerts=$(curl -sf --max-time 3 'http://127.0.0.1:9093/api/v2/alerts?active=true&silenced=false&inhibited=false' 2>/dev/null); then
    row "Alertmanager" "sẵn sàng" "$(jq length <<<"$alerts") cảnh báo đang bật $(jq -r '[.[].labels.alertname] | unique | join(", ")' <<<"$alerts")"
  else row "Alertmanager" "không chạy"; fi
}

# ── down, reset, clean ───────────────────────────────────────────────────────
# Script con nào lỗi khi tắt thì ghi lại và tắt tiếp phần sau, để không bỏ sót container.
soft() { "$@" || log "CHÚ Ý: lỗi khi chạy $* (tắt tiếp phần sau)"; }
has_env() { [ -f "$INFRA/$1/out/.env" ]; }

cmd_down() {
  soft "$INFRA/monitoring/monitoring.sh" down
  has_env keycloak && soft "$INFRA/keycloak/keycloak.sh" down
  has_env services && soft "$INFRA/services/services.sh" down
  soft "$INFRA/devnet/devnet.sh" down
  log "đã dừng; dữ liệu còn nguyên, chạy ./werwa.sh up để bật lại"
}

cmd_reset() {
  soft "$INFRA/monitoring/monitoring.sh" clean
  # Keycloak reset xóa DB keycloak qua PostgreSQL của services, nên làm khi services còn chạy.
  has_env keycloak && soft "$INFRA/keycloak/keycloak.sh" reset
  has_env services && soft "$INFRA/services/services.sh" reset
  soft "$INFRA/devnet/devnet.sh" reset
  # Chuỗi mới không còn Safe và hợp đồng: bỏ kết quả cũ để `up` tạo lại.
  rm -rf "$DEVNET_OUT/safe" "$DEVNET_OUT/platform"
  log "đã xóa dữ liệu; khóa, mật khẩu, khóa OTP còn. Chạy ./werwa.sh up để dựng lại"
}

cmd_clean() {
  soft "$INFRA/monitoring/monitoring.sh" clean
  has_env keycloak && soft "$INFRA/keycloak/keycloak.sh" clean
  has_env services && soft "$INFRA/services/services.sh" clean
  soft "$INFRA/devnet/devnet.sh" clean
  # Phần còn sót (script con hỏng giữa chừng, tệp thiếu): gỡ theo nhãn project của Compose.
  local p ids
  for p in "${PROJECTS[@]}"; do
    ids=$(docker ps -aq --filter "label=com.docker.compose.project=$p")
    [ -z "$ids" ] || docker rm -f $ids >/dev/null
    ids=$(docker volume ls -q --filter "label=com.docker.compose.project=$p")
    [ -z "$ids" ] || docker volume rm $ids >/dev/null
  done
  for p in "$APP_NETWORK" "$P2P_NETWORK"; do
    docker network inspect "$p" >/dev/null 2>&1 && { docker network rm "$p" >/dev/null || log "CHÚ Ý: không gỡ được mạng $p (còn container khác dùng?)"; }
  done
  for p in "${OUT_DIRS[@]}"; do rm -rf "${INFRA:?}/$p"; done
  log "đã xóa toàn bộ devnet WeRWA"
}

case "${1:-}" in
  up) shift; cmd_up "$@" ;;
  status) cmd_status ;;
  down) cmd_down ;;
  reset) cmd_reset ;;
  clean) cmd_clean ;;
  *) sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
