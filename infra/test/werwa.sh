#!/usr/bin/env bash
# Kiểm lệnh dựng trọn devnet infra/werwa.sh. Chạy sau `infra/werwa.sh up` (đủ cả giám sát).
# Cần: docker, curl, jq, cast, nats, openssl, python3.
#
#   W1  mọi phần khỏe: 4 validator tạo khối, hai O1 cùng mã băm khối; 6 Safe; nền tảng đã triển khai (timelock,
#       tokenDeployer có code); werwa_app đăng nhập DB werwa; stream WERWA có; issuer Keycloak đúng; Prometheus
#       thấy 6 node; không có cảnh báo critical; devnet, services, Keycloak cùng ở mạng werwa-devnet-app
#   W2  `up` lần nữa không tạo lại gì: cùng địa chỉ hợp đồng và Safe, cùng mật khẩu và khóa OTP; nền tảng,
#       realm, người dùng thử đều báo đã có; chuỗi chạy tiếp
#   W3  `down` dừng mọi container; `up` lại: chuỗi chạy tiếp từ khối cũ, hợp đồng còn, người dùng thử đăng
#       nhập được bằng khóa OTP cũ
#   W4  (chỉ khi chạy với --clean) `clean` không để lại container, volume, mạng hay out/ nào của devnet WeRWA
#
#   test/werwa.sh            W1–W3 (W3 dừng rồi bật lại devnet)
#   test/werwa.sh --clean    W1–W4; W4 xóa toàn bộ devnet

set -euo pipefail
INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$INFRA/keycloak/lib.sh"

need docker; need curl; need jq; need cast; need nats; need openssl; need python3
trap 'annotate "kiểm thử dừng ở dòng $LINENO: $BASH_COMMAND"' ERR
trap '[ -n "$LOGIN_DIR" ] && rm -rf "$LOGIN_DIR"' EXIT
CLEAN=0; [ "${1:-}" = --clean ] && CLEAN=1

WERWA="$INFRA/werwa.sh"
PLAT="$DEVNET_OUT/platform/devnet.json"
SAFES="$DEVNET_OUT/safe/safes.json"
PROJECTS=(werwa-monitoring werwa-keycloak werwa-services werwa-devnet)
check() { local name="$1" got="$2" want="$3"; [ "$got" = "$want" ] && pass "$name" || fail "$name: $got, mong đợi $want"; }
lower() { tr 'A-F' 'a-f'; }
has_code() { [ "$(cast code --rpc-url "$O1A_RPC" "$1" 2>/dev/null)" != 0x ]; }
svc_env() { grep -E "^$1=" "$INFRA/services/out/.env" | cut -d= -f2-; }
running() {
  local p n=0
  for p in "${PROJECTS[@]}"; do n=$((n + $(docker ps -q --filter "label=com.docker.compose.project=$p" | grep -c . || true))); done
  echo "$n"
}
# Dấu vân tay của mọi thứ không được đổi khi chạy lại `up`.
fingerprint() {
  {
    jq -S '{chainId, deployer, contracts}' "$PLAT"
    jq -S '.safes | map_values(.address)' "$SAFES"
    sha256sum "$INFRA/services/out/.env" "$INFRA/keycloak/out/.env" "$USERS_FILE" "$DEVNET_OUT/shared/genesis.json" 2>/dev/null | cut -d' ' -f1
  } | sha256sum | cut -d' ' -f1
}

# ── W1 ────────────────────────────────────────────────────────────────────────
check "W1 4 validator" "$(validators_at "$O1A_RPC" | grep -c .)" 4
if wait_blocks "$O1A_RPC" 2 15; then pass "W1 chuỗi tạo khối"; else fail "W1 chuỗi không tạo khối trong 15 giây"; fi
b=$(( $(block_number "$O1A_RPC") - 1 ))
check "W1 O1-a và O1-b cùng mã băm khối $b" \
  "$(rpc "$O1B_RPC" eth_getBlockByNumber "[\"$(printf '0x%x' "$b")\",false]" | jq -r .hash)" \
  "$(rpc "$O1A_RPC" eth_getBlockByNumber "[\"$(printf '0x%x' "$b")\",false]" | jq -r .hash)"
check "W1 6 Safe các bên" "$(jq '.safes | length' "$SAFES")" 6
TL=$(jq -r .contracts.timelock "$PLAT"); TD=$(jq -r '.contracts.tokenDeployer // empty' "$PLAT")
if has_code "$TL"; then pass "W1 nền tảng: timelock có code"; else fail "W1 nền tảng: timelock $TL không có code"; fi
if [ -n "$TD" ] && has_code "$TD"; then pass "W1 nền tảng: tokenDeployer có code"; else fail "W1 nền tảng: tokenDeployer '$TD' không có code"; fi
check "W1 werwa_app đăng nhập DB werwa" \
  "$(svc exec -T -e PGPASSWORD="$(svc_env WERWA_APP_DB_PASSWORD)" postgres psql -h 127.0.0.1 -U werwa_app -d werwa -tAX \
      -c "select current_user || '@' || current_database()" 2>&1 | tr -d '\r')" "werwa_app@werwa"
check "W1 stream WERWA có trên NATS" \
  "$(nats --server nats://127.0.0.1:4222 --user werwa_admin --password "$(svc_env NATS_ADMIN_PASSWORD)" stream info WERWA --json 2>/dev/null | jq -r .config.name)" WERWA
check "W1 issuer Keycloak" \
  "$(curl -sf "$KC_URL/realms/$REALM/.well-known/openid-configuration" | jq -r .issuer)" "http://localhost:8180/realms/werwa"
targets=""
for _ in $(seq 1 15); do
  targets=$(curl -sf 'http://127.0.0.1:9090/api/v1/query' --data-urlencode 'query=sum(up{job="besu"})' | jq -r '.data.result[0].value[1] // 0')
  [ "$targets" = 6 ] && break; sleep 2
done
check "W1 Prometheus thấy 6 node Besu" "$targets" 6
check "W1 không có cảnh báo critical" \
  "$(curl -sf 'http://127.0.0.1:9093/api/v2/alerts?active=true&silenced=false&inhibited=false' | jq '[.[] | select(.labels.severity == "critical")] | length')" 0
on_app=$(docker network inspect "$APP_NETWORK" | jq -r '[.[0].Containers[].Name] | join(" ")')
miss=""
for c in v1 v2 o1-a o1-b postgres nats keycloak; do grep -q -- "$c" <<<"$on_app" || miss="$miss $c"; done
check "W1 devnet, services, Keycloak cùng ở mạng $APP_NETWORK" "${miss:-đủ}" "đủ"
check "W1 mạng $APP_NETWORK do script tạo (không thuộc project Compose nào)" \
  "$(docker network inspect "$APP_NETWORK" | jq -r '.[0].Labels["com.docker.compose.project"] // "không"')" "không"

# ── W2 ────────────────────────────────────────────────────────────────────────
fp1=$(fingerprint)
up_log=$("$WERWA" up 2>&1) || { printf '%s\n' "$up_log" >&2; fail "W2 up lần nữa thất bại"; }
check "W2 up lần nữa: không đổi hợp đồng, Safe, mật khẩu, khóa OTP" "$(fingerprint)" "$fp1"
for msg in "nền tảng đã triển khai trên devnet này" "realm werwa đã có; kiểm lệch" "người dùng thử: đủ 24"; do
  if grep -qF "$msg" <<<"$up_log"; then pass "W2 up lần nữa báo: $msg"; else fail "W2 up lần nữa không báo: $msg"; fi
done
if wait_blocks "$O1A_RPC" 2 15; then pass "W2 chuỗi chạy tiếp"; else fail "W2 chuỗi không tạo khối sau up lần nữa"; fi

# ── W3 ────────────────────────────────────────────────────────────────────────
before=$(block_number "$O1A_RPC")
"$WERWA" down >/dev/null 2>&1 || fail "W3 down thất bại"
check "W3 down: không còn container nào chạy" "$(running)" 0
"$WERWA" up >/dev/null 2>&1 || fail "W3 up sau down thất bại"
after=$(block_number "$O1A_RPC")
if [ -n "$after" ] && [ "$after" -gt "$before" ]; then pass "W3 chuỗi chạy tiếp từ khối cũ ($before → $after)"
else fail "W3 chuỗi không chạy tiếp: trước $before, sau ${after:-không trả lời}"; fi
check "W3 sau down/up: cùng hợp đồng, Safe, mật khẩu, khóa OTP" "$(fingerprint)" "$fp1"
if has_code "$TL"; then pass "W3 hợp đồng còn trên chuỗi"; else fail "W3 mất hợp đồng sau down/up"; fi
TOKEN=""
if tok=$(login_user holco-maker); then
  check "W3 người dùng thử đăng nhập bằng OTP cũ" \
    "$(jwt_claims "$(jq -r .access_token <<<"$tok")" | jq -r .org_id)" "$(user_field holco-maker orgId)"
else fail "W3 người dùng thử không đăng nhập được sau down/up"; fi

# ── W4 ────────────────────────────────────────────────────────────────────────
if [ "$CLEAN" = 1 ]; then
  "$WERWA" clean >/dev/null 2>&1 || fail "W4 clean thất bại"
  left=0
  for p in "${PROJECTS[@]}"; do
    left=$((left + $(docker ps -aq --filter "label=com.docker.compose.project=$p" | grep -c . || true)))
    left=$((left + $(docker volume ls -q --filter "label=com.docker.compose.project=$p" | grep -c . || true)))
  done
  check "W4 không còn container, volume của devnet" "$left" 0
  nets=""
  for n in "$APP_NETWORK" werwa-devnet-p2p; do docker network inspect "$n" >/dev/null 2>&1 && nets="$nets $n"; done
  check "W4 không còn mạng của devnet" "${nets:-không}" "không"
  outs=""
  for d in devnet/out services/out keycloak/out monitoring/out out; do [ -e "$INFRA/$d" ] && outs="$outs $d"; done
  check "W4 không còn out/" "${outs:-không}" "không"
else
  log "W4 bỏ qua (chạy với --clean để kiểm và xóa toàn bộ devnet)"
fi

[ "$FAILED" = 0 ] || die "kiểm werwa.sh trượt"
log "werwa.sh: tất cả đạt"
