#!/usr/bin/env bash
# Dựng trọn devnet WeRWA bằng một lệnh, cho đội backend và frontend.
#
#   ./werwa.sh up [--without monitoring] [--signer devnet|wehsm]
#                                          kiểm công cụ, rồi dựng lần lượt: mạng dùng chung, devnet Besu,
#                                          Safe các bên, (WeHSM), DeployPlatform, PostgreSQL và NATS, Keycloak,
#                                          giám sát. Chạy lại an toàn: phần đã có thì giữ nguyên
#   ./werwa.sh status                      bảng gộp trạng thái mọi phần
#   ./werwa.sh down                        dừng mọi phần, giữ dữ liệu (chuỗi, DB, người dùng thử)
#   ./werwa.sh reset                       xóa dữ liệu chuỗi, DB, realm và giám sát; giữ khóa, mật khẩu,
#                                          khóa OTP. `up` sau đó triển khai lại nền tảng lên chuỗi mới
#   ./werwa.sh clean                       xóa mọi thứ của devnet: container, volume, mạng, mọi out/
#   ./werwa.sh env [--docker]              in đường dẫn tệp biến môi trường gộp cho backend, ví dụ
#                                          set -a; source "$(infra/werwa.sh env)"; set +a
#
# Tệp env gộp (I11, quyền 600, gitignore), `up` ghi sau khi dựng xong:
#   out/werwa.env          backend chạy trên máy: địa chỉ 127.0.0.1, localhost
#   out/werwa.docker.env   backend chạy trong mạng werwa-devnet-app: tên *.werwa.devnet
# Tên biến theo chuẩn Spring: property có sẵn của Spring giữ tên chuẩn; phần riêng ứng với
# @ConfigurationProperties(prefix = "werwa") theo relaxed binding (bỏ dấu gạch ngang, viết hoa).
#
# Các script con (devnet/, safe/, platform/, services/, keycloak/, monitoring/) vẫn chạy riêng được;
# werwa.sh chỉ gọi chúng theo đúng thứ tự. Tắt theo thứ tự ngược lại: giám sát dùng mạng p2p của devnet,
# Keycloak lưu trên PostgreSQL của services.
#
# Chế độ ký của DeployPlatform (I12): mặc định `devnet` (tài khoản thử, chưa mint được). `--signer wehsm`
# dựng WeHSM và node HSM (infra/signer) rồi triển khai qua WeHSM, chế độ `pkcs11`: khóa Holco nằm trong HSM,
# mint và cấp claim được. Nền tảng đã triển khai qua WeHSM thì `up` tự bật lại WeHSM, không cần cờ.
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
PROJECTS=(werwa-monitoring werwa-keycloak werwa-services werwa-signer werwa-devnet)
OUT_DIRS=(devnet/out services/out keycloak/out monitoring/out signer/out out)

ENV_OUT="$INFRA/out"
ENV_HOST="$ENV_OUT/werwa.env"
ENV_DOCKER="$ENV_OUT/werwa.docker.env"
# Issuer cố định (I8), trùng KC_URL của infra/keycloak/lib.sh.
KC_ISSUER="http://localhost:8180/realms/werwa"

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
  local monitoring=1 signer="" plat="$DEVNET_OUT/platform/devnet.json"
  while [ $# -gt 0 ]; do
    case "$1" in
      --without) [ "${2:-}" = monitoring ] || die "chỉ hỗ trợ --without monitoring"; monitoring=0; shift 2 ;;
      --signer) signer="${2:-}"; [ "$signer" = devnet ] || [ "$signer" = wehsm ] || die "--signer devnet|wehsm"; shift 2 ;;
      *) die "tham số lạ: $*" ;;
    esac
  done
  # Không có cờ: theo lần dựng trước (nền tảng đã triển khai qua WeHSM, hoặc WeHSM đã dựng và chưa clean),
  # mặc định devnet (I12).
  if [ -z "$signer" ]; then
    signer=devnet
    { [ -f "$plat" ] && [ "$(jq -r .signingMode "$plat")" = pkcs11 ]; } && signer=wehsm
    { [ ! -f "$plat" ] && [ -f "$INFRA/signer/out/signer.env" ]; } && signer=wehsm
  fi
  if [ "$signer" = wehsm ] && [ -f "$plat" ] && [ "$(jq -r .signingMode "$plat")" != pkcs11 ]; then
    die "nền tảng đã triển khai ở chế độ $(jq -r .signingMode "$plat"); chạy ./werwa.sh reset rồi up --signer wehsm"
  fi
  local t0; t0=$(date +%s)
  step "kiểm công cụ"; preflight
  step "mạng dùng chung"; ensure_app_network
  step "devnet Besu";            run devnet     "$INFRA/devnet/devnet.sh" up
  step "Safe các bên";           run safe       "$INFRA/safe/safe.sh" up
  if [ "$signer" = wehsm ]; then
    step "WeHSM và node HSM";    run signer     "$INFRA/signer/signer.sh" up
    step "triển khai nền tảng qua WeHSM"; run platform "$INFRA/signer/signer.sh" deploy-platform
  else
    step "triển khai nền tảng";  run platform   "$INFRA/platform/deploy.sh"
  fi
  step "PostgreSQL, NATS";       run services   "$INFRA/services/services.sh" up
  step "Keycloak";               run keycloak   "$INFRA/keycloak/keycloak.sh" up
  if [ "$monitoring" = 1 ]; then
    step "giám sát";             run monitoring "$INFRA/monitoring/monitoring.sh" up
  else
    log "bỏ qua giám sát (--without monitoring)"
  fi
  step "tệp env gộp cho backend"; write_env "$monitoring"
  log "devnet WeRWA sẵn sàng sau $(( $(date +%s) - t0 )) giây"
  log "biến cho backend: $ENV_HOST (backend trong Docker: $ENV_DOCKER)"
  cmd_status
}

# ── tệp env gộp (I11) ─────────────────────────────────────────────────────────
# render_env <host|docker> <có giám sát 1|0>: in nội dung tệp env cho một biến thể.
svc_secret() { grep -E "^$1=" "$INFRA/services/out/.env" | cut -d= -f2-; }

render_env() {
  local v="$1" mon="$2" plat="$DEVNET_OUT/platform/devnet.json" safes="$DEVNET_OUT/safe/safes.json"
  local pg nats mail kc_jwks read submit am where
  if [ "$v" = host ]; then
    where="chạy trên máy"
    pg=127.0.0.1:5432; nats=127.0.0.1:4222; mail=127.0.0.1; kc_jwks=http://127.0.0.1:8180; am=http://127.0.0.1:9093
    read="$O1A_RPC,$O1B_RPC"; submit="$V1_RPC,$V2_RPC"
  else
    where="chạy trong mạng $APP_NETWORK"
    pg=postgres.werwa.devnet:5432; nats=nats.werwa.devnet:4222; mail=mailpit.werwa.devnet; kc_jwks=http://keycloak.werwa.devnet:8080
    am=http://alertmanager.werwa.devnet:9093
    read=http://o1-a.werwa.devnet:8545,http://o1-b.werwa.devnet:8545
    submit=http://v1.werwa.devnet:8545,http://v2.werwa.devnet:8545
  fi
  cat <<ENVEOF
# Sinh bởi infra/werwa.sh — biến cho backend WeRWA ($where).
# Chỉ cho devnet: có mật khẩu, không commit, không dùng ở môi trường khác. Tên biến theo I11.

# PostgreSQL (DB werwa, role werwa_app; sáu schema do Flyway tạo)
SPRING_DATASOURCE_URL=jdbc:postgresql://$pg/werwa
SPRING_DATASOURCE_USERNAME=werwa_app
SPRING_DATASOURCE_PASSWORD=$(svc_secret WERWA_APP_DB_PASSWORD)

# NATS JetStream (stream WERWA, I2) → werwa.nats.*
WERWA_NATS_URL=nats://$nats
WERWA_NATS_USERNAME=werwa_app
WERWA_NATS_PASSWORD=$(svc_secret NATS_APP_PASSWORD)
WERWA_NATS_STREAM=WERWA

# SMTP cho email thông báo (Q62): Mailpit nhận mọi thư, không chuyển ra ngoài; xem tại http://127.0.0.1:8025
SPRING_MAIL_HOST=$mail
SPRING_MAIL_PORT=1025

# OIDC (Keycloak realm werwa, I3, I8): issuer cố định ở mọi biến thể, JWKS theo nơi chạy
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI=$KC_ISSUER
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI=$kc_jwks/realms/werwa/protocol/openid-connect/certs
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=werwa-api
WERWA_OIDC_CLIENTID=werwa-web

# Chuỗi → werwa.chain.*: đọc chỉ qua O1 (ADR-12), gửi giao dịch qua V1, V2; danh sách theo thứ tự ưu tiên
WERWA_CHAIN_ID=$(jq -r .chainId "$plat")
WERWA_CHAIN_READURLS=$read
WERWA_CHAIN_SUBMITURLS=$submit
WERWA_CHAIN_DEPLOYEDATBLOCK=$(jq -r .deployedAtBlock "$plat")
WERWA_CHAIN_SIGNINGMODE=$(jq -r .signingMode "$plat")

# Hợp đồng dùng chung (devnet/out/platform/devnet.json) → werwa.contracts.<tên>
ENVEOF
  jq -r '.contracts | to_entries[] | "WERWA_CONTRACTS_\(.key | ascii_upcase)=\(.value)"' "$plat"
  echo
  echo "# Safe các bên (ADR-05, devnet/out/safe/safes.json) → werwa.safes.<tên>"
  jq -r '.safes | to_entries[] | "WERWA_SAFES_\(.key | ascii_upcase)=\(.value.address)"' "$safes"
  if [ -f "$INFRA/signer/out/signer.env" ] && [ "$(jq -r .signingMode "$plat")" = pkcs11 ]; then
    local signer_url=http://127.0.0.1:8745
    [ "$v" = docker ] && signer_url=http://wehsm.werwa.devnet:8545
    echo
    echo "# Ký giao dịch qua WeHSM (ADR-06, I13): eth_sendTransaction với Basic auth wehsm:<token> → werwa.signer.*"
    echo "WERWA_SIGNER_URL=$signer_url"
    echo "WERWA_SIGNER_TOKEN=$(cat "$INFRA/signer/out/secrets/wehsm/token")"
  fi
  if [ "$mon" = 1 ]; then
    echo
    echo "# Giám sát (US-6.01-AC3, I5) → werwa.monitoring.*"
    echo "WERWA_MONITORING_ALERTMANAGERURL=$am"
  fi
}

write_env() {
  local mon="${1:-1}"
  [ -f "$DEVNET_OUT/platform/devnet.json" ] || die "chưa có devnet.json; chạy ./werwa.sh up"
  [ -f "$INFRA/services/out/.env" ] || die "chưa có services/out/.env; chạy ./werwa.sh up"
  mkdir -p "$ENV_OUT"
  (umask 077
   render_env host "$mon" >"$ENV_HOST.tmp" && mv "$ENV_HOST.tmp" "$ENV_HOST"
   render_env docker "$mon" >"$ENV_DOCKER.tmp" && mv "$ENV_DOCKER.tmp" "$ENV_DOCKER")
}

cmd_env() {
  local f="$ENV_HOST"
  [ "${1:-}" = --docker ] && f="$ENV_DOCKER"
  [ -f "$f" ] || die "chưa có $f; chạy ./werwa.sh up"
  echo "$f"
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
  row "Mailpit" "$(ok_http http://127.0.0.1:8025/readyz)" "SMTP 127.0.0.1:1025, xem thư http://127.0.0.1:8025"
  row "Keycloak" "$(ok_http http://127.0.0.1:9180/health/ready)" "http://localhost:8180/realms/werwa"
  if [ -f "$INFRA/signer/out/mode" ]; then
    row "WeHSM" "$(ok_http http://127.0.0.1:8745/health)" "http://127.0.0.1:8745, chế độ $(cat "$INFRA/signer/out/mode")"
  else row "WeHSM" "không dùng" "chế độ ký devnet (up --signer wehsm để dùng)"; fi
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
  [ -d "$INFRA/signer/out" ] && soft "$INFRA/signer/signer.sh" down
  soft "$INFRA/devnet/devnet.sh" down
  log "đã dừng; dữ liệu còn nguyên, chạy ./werwa.sh up để bật lại"
}

cmd_reset() {
  soft "$INFRA/monitoring/monitoring.sh" clean
  # Keycloak reset xóa DB keycloak qua PostgreSQL của services, nên làm khi services còn chạy.
  has_env keycloak && soft "$INFRA/keycloak/keycloak.sh" reset
  has_env services && soft "$INFRA/services/services.sh" reset
  # Khóa trong HSM giữ nguyên; WeHSM dừng để lần up sau chạy lại từ chế độ deploy trên chuỗi mới.
  [ -d "$INFRA/signer/out" ] && soft "$INFRA/signer/signer.sh" down
  soft "$INFRA/devnet/devnet.sh" reset
  # Chuỗi mới không còn Safe và hợp đồng: bỏ kết quả cũ để `up` tạo lại.
  rm -rf "$DEVNET_OUT/safe" "$DEVNET_OUT/platform"
  log "đã xóa dữ liệu; khóa, mật khẩu, khóa OTP còn. Chạy ./werwa.sh up để dựng lại"
}

cmd_clean() {
  soft "$INFRA/monitoring/monitoring.sh" clean
  has_env keycloak && soft "$INFRA/keycloak/keycloak.sh" clean
  has_env services && soft "$INFRA/services/services.sh" clean
  [ -d "$INFRA/signer/out" ] && soft "$INFRA/signer/signer.sh" clean
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
  env) shift; cmd_env "$@" ;;
  *) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
