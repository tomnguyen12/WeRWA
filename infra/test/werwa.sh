#!/usr/bin/env bash
# Kiểm lệnh dựng trọn devnet infra/werwa.sh. Chạy sau `infra/werwa.sh up` (đủ cả giám sát).
# Cần: docker, curl, jq, cast, nats, openssl, python3.
#
#   W1  mọi phần khỏe: 4 validator tạo khối, hai O1 cùng mã băm khối; 6 Safe; nền tảng đã triển khai (timelock,
#       tokenDeployer có code); werwa_app đăng nhập DB werwa; stream WERWA có; issuer Keycloak đúng; Prometheus
#       thấy 6 node; không có cảnh báo critical; devnet, services, Keycloak cùng ở mạng werwa-devnet-app
#   W2  `up` lần nữa không tạo lại gì: cùng địa chỉ hợp đồng và Safe, cùng mật khẩu, khóa OTP và tệp env gộp; nền tảng,
#       realm, người dùng thử đều báo đã có; chuỗi chạy tiếp
#   W3  `down` dừng mọi container; `up` lại: chuỗi chạy tiếp từ khối cũ, hợp đồng còn, người dùng thử đăng
#       nhập được bằng khóa OTP cũ
#   W5  tệp env gộp (I11): out/werwa.env và out/werwa.docker.env có đủ biến, tên theo relaxed binding của Spring,
#       quyền 600, gitignore; mọi giá trị dùng được thật: DB, NATS, OIDC (issuer, JWKS), RPC đọc chỉ là O1 và
#       trả đúng chain ID, mỗi hợp đồng và Safe có code, Alertmanager trả lời; biến thể Docker thử từ trong mạng
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
    sha256sum "$INFRA/services/out/.env" "$INFRA/keycloak/out/.env" "$USERS_FILE" "$INFRA/out/werwa.env" "$INFRA/out/werwa.docker.env" "$DEVNET_OUT/shared/genesis.json" 2>/dev/null | cut -d' ' -f1
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

# ── W5 tệp env gộp (I11) ─────────────────────────────────────────────────────
# Mọi giá trị được thử dùng thật: biến thể máy từ máy chạy kiểm thử, biến thể Docker từ một container trong
# mạng werwa-devnet-app (ảnh PostgreSQL đã có sẵn: psql cho DB, /dev/tcp của bash cho HTTP và NATS).
ENV_HOST=$("$WERWA" env); ENV_DOCKER=$("$WERWA" env --docker)
ev() { grep -E "^$2=" "$1" | cut -d= -f2-; }
PROBE_IMAGE="${POSTGRES_IMAGE:-postgres:18.6}"
in_net() { docker run --rm -i --network "$APP_NETWORK" "$PROBE_IMAGE" bash -s -- "$@"; }
# http_in_net <method> <url> [body] → thân phản hồi HTTP, gọi từ trong mạng ứng dụng.
http_in_net() {
  in_net "$1" "$2" "${3:-}" <<'SH' | tr -d '\r' | sed '1,/^$/d'
m="$1"; u="${2#http://}"; b="$3"; hp="${u%%/*}"; p="/${u#*/}"; [ "$u" = "$hp" ] && p=/
h="${hp%%:*}"; port="${hp##*:}"
exec 3<>"/dev/tcp/$h/$port"
printf '%s %s HTTP/1.0\r\nHost: %s\r\nContent-Type: application/json\r\nContent-Length: %s\r\n\r\n%s' "$m" "$p" "$hp" "${#b}" "$b" >&3
cat <&3
SH
}
rpc_chain_id() { curl -sf --max-time 5 -H 'content-type: application/json' --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' "$1" | jq -r .result; }
CHAIN_HEX=$(printf '0x%x' "$(jq -r .chainId "$PLAT")")
REQUIRED_KEYS=(SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD
  WERWA_NATS_URL WERWA_NATS_USERNAME WERWA_NATS_PASSWORD WERWA_NATS_STREAM
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES WERWA_OIDC_CLIENTID
  WERWA_CHAIN_ID WERWA_CHAIN_READURLS WERWA_CHAIN_SUBMITURLS WERWA_CHAIN_DEPLOYEDATBLOCK WERWA_CHAIN_SIGNINGMODE
  WERWA_MONITORING_ALERTMANAGERURL SPRING_MAIL_HOST SPRING_MAIL_PORT)

for f in "$ENV_HOST" "$ENV_DOCKER"; do
  n=$(basename "$f")
  miss=""; for k in "${REQUIRED_KEYS[@]}"; do [ -n "$(ev "$f" "$k")" ] || miss="$miss $k"; done
  check "W5 $n có đủ biến bắt buộc" "${miss:-đủ}" "đủ"
  check "W5 $n: mỗi hợp đồng trong devnet.json có một biến WERWA_CONTRACTS_*" \
    "$(grep -c '^WERWA_CONTRACTS_' "$f")" "$(jq '.contracts | length' "$PLAT")"
  check "W5 $n: mỗi Safe có một biến WERWA_SAFES_*" "$(grep -c '^WERWA_SAFES_' "$f")" "$(jq '.safes | length' "$SAFES")"
  # Tên theo relaxed binding của Spring (I11): chỉ chữ hoa, số, gạch dưới; không có tên kiểu *_URI, *_SET_* tách
  # từ property có gạch ngang (issuer-uri phải là ISSUERURI).
  bad=$( { grep -E '^[^#].*=' "$f" | cut -d= -f1 | grep -vE '^[A-Z0-9_]+$'
          grep -oE '^[A-Z0-9_]*(_ISSUER_URI|_JWK_SET_URI|_CLIENT_ID|_READ_URLS|_SUBMIT_URLS|_DEPLOYED_AT_BLOCK|_SIGNING_MODE|_ALERTMANAGER_URL)=' "$f"
        } | tr '\n' ' ' || true)
  check "W5 $n: tên biến theo relaxed binding (I11)" "${bad:-đúng}" "đúng"
  check "W5 $n quyền 600" "$(stat -c '%a' "$f")" 600
  if git -C "$INFRA" check-ignore -q "$f"; then pass "W5 $n bị git bỏ qua"; else fail "W5 $n không bị git bỏ qua"; fi
done
check "W5 issuer giống nhau ở hai biến thể (I8)" \
  "$(ev "$ENV_DOCKER" SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI)" "$(ev "$ENV_HOST" SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI)"

# Biến thể máy
H=$ENV_HOST
jdbc=$(ev "$H" SPRING_DATASOURCE_URL); hostport=${jdbc#jdbc:postgresql://}; hostport=${hostport%%/*}
check "W5 werwa.env: PostgreSQL đăng nhập được bằng URL, user, mật khẩu trong tệp" \
  "$(docker run --rm --network host -e PGPASSWORD="$(ev "$H" SPRING_DATASOURCE_PASSWORD)" "$PROBE_IMAGE" \
      psql -h "${hostport%%:*}" -p "${hostport##*:}" -U "$(ev "$H" SPRING_DATASOURCE_USERNAME)" -d werwa -tAXc 'select current_user' 2>&1 | tr -d '\r')" \
  "$(ev "$H" SPRING_DATASOURCE_USERNAME)"
if nats --server "$(ev "$H" WERWA_NATS_URL)" --user "$(ev "$H" WERWA_NATS_USERNAME)" --password "$(ev "$H" WERWA_NATS_PASSWORD)" \
     --timeout 3s rtt >/dev/null 2>&1; then pass "W5 werwa.env: NATS kết nối được bằng user, mật khẩu trong tệp"
else fail "W5 werwa.env: NATS không kết nối được"; fi
iss=$(ev "$H" SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI)
check "W5 werwa.env: discovery của issuer trả đúng issuer" "$(curl -sf "$iss/.well-known/openid-configuration" | jq -r .issuer)" "$iss"
check "W5 werwa.env: JWKS có khóa ký" "$(curl -sf "$(ev "$H" SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI)" | jq '[.keys[] | select(.use == "sig")] | length > 0')" true
check "W5 werwa.env: WERWA_CHAIN_ID đúng chain" "$(ev "$H" WERWA_CHAIN_ID)" "$(jq -r .chainId "$PLAT")"
o1_ports=$(for n in o1-a o1-b; do dc port "$n" 8545; done | sed 's/^/http:\/\//' | sort | tr '\n' ',')
check "W5 werwa.env: WERWA_CHAIN_READURLS là đúng hai O1 (ADR-12), không có validator" \
  "$(tr ',' '\n' <<<"$(ev "$H" WERWA_CHAIN_READURLS)" | sort | tr '\n' ',')" "$o1_ports"
for u in $(tr ',' ' ' <<<"$(ev "$H" WERWA_CHAIN_READURLS),$(ev "$H" WERWA_CHAIN_SUBMITURLS)"); do
  check "W5 werwa.env: $u trả đúng chain ID" "$(rpc_chain_id "$u")" "$CHAIN_HEX"
done
nocode=""
while IFS='=' read -r k a; do has_code "$a" || nocode="$nocode $k"; done < <(grep -E '^WERWA_(CONTRACTS|SAFES)_' "$H")
check "W5 werwa.env: mọi WERWA_CONTRACTS_*, WERWA_SAFES_* có code trên chuỗi" "${nocode:-đủ}" "đủ"
check "W5 werwa.env: Alertmanager trả lời" "$(curl -s -o /dev/null -w '%{http_code}' "$(ev "$H" WERWA_MONITORING_ALERTMANAGERURL)/-/ready")" 200
# Thư thử qua SMTP theo SPRING_MAIL_* (Q62); đọc lại qua API của Mailpit trên máy.
mail_found() { curl -sf http://127.0.0.1:8025/api/v1/search --get --data-urlencode "query=subject:\"$1\"" | jq '.messages | length'; }
TAG_H="w5-host-$(date +%s%N)"; m=$(mktemp)
printf 'From: no-reply@werwa.local\r\nTo: org@test.werwa.local\r\nSubject: %s\r\n\r\nW5\r\n' "$TAG_H" >"$m"
curl -s --max-time 10 --url "smtp://$(ev "$H" SPRING_MAIL_HOST):$(ev "$H" SPRING_MAIL_PORT)" --mail-from no-reply@werwa.local \
  --mail-rcpt org@test.werwa.local --upload-file "$m" || true
rm -f "$m"
check "W5 werwa.env: thư gửi qua SPRING_MAIL_HOST/PORT tới Mailpit" "$(mail_found "$TAG_H")" 1

# Biến thể Docker, gọi từ trong mạng werwa-devnet-app
D=$ENV_DOCKER
jdbc=$(ev "$D" SPRING_DATASOURCE_URL); hostport=${jdbc#jdbc:postgresql://}; hostport=${hostport%%/*}
check "W5 werwa.docker.env: PostgreSQL đăng nhập được từ trong mạng" \
  "$(docker run --rm --network "$APP_NETWORK" -e PGPASSWORD="$(ev "$D" SPRING_DATASOURCE_PASSWORD)" "$PROBE_IMAGE" \
      psql -h "${hostport%%:*}" -p "${hostport##*:}" -U "$(ev "$D" SPRING_DATASOURCE_USERNAME)" -d werwa -tAXc 'select current_user' 2>&1 | tr -d '\r')" \
  "$(ev "$D" SPRING_DATASOURCE_USERNAME)"
nu=$(ev "$D" WERWA_NATS_URL); nu=${nu#nats://}
check "W5 werwa.docker.env: NATS trả lời từ trong mạng" \
  "$(in_net "${nu%%:*}" "${nu##*:}" <<<'exec 3<>"/dev/tcp/$1/$2"; head -c 4 <&3' 2>/dev/null)" "INFO"
check "W5 werwa.docker.env: JWKS có khóa ký, gọi từ trong mạng" \
  "$(http_in_net GET "$(ev "$D" SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI)" | jq '[.keys[] | select(.use == "sig")] | length > 0')" true
grep -q 'o1-' <<<"$(ev "$D" WERWA_CHAIN_READURLS)" && ! grep -qE '//v[0-9]' <<<"$(ev "$D" WERWA_CHAIN_READURLS)" \
  && pass "W5 werwa.docker.env: WERWA_CHAIN_READURLS chỉ có O1 (ADR-12)" || fail "W5 werwa.docker.env: WERWA_CHAIN_READURLS có node không phải O1"
for u in $(tr ',' ' ' <<<"$(ev "$D" WERWA_CHAIN_READURLS),$(ev "$D" WERWA_CHAIN_SUBMITURLS)"); do
  check "W5 werwa.docker.env: $u trả đúng chain ID từ trong mạng" \
    "$(http_in_net POST "$u" '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' | jq -r .result)" "$CHAIN_HEX"
done
TAG_D="w5-docker-$(date +%s%N)"
in_net "$(ev "$D" SPRING_MAIL_HOST)" "$(ev "$D" SPRING_MAIL_PORT)" "$TAG_D" >/dev/null 2>&1 <<'SH' || true
exec 3<>"/dev/tcp/$1/$2"
rd() { local l; while IFS= read -r l <&3; do case "$l" in [0-9][0-9][0-9]-*) ;; *) break ;; esac; done; }
say() { printf '%s\r\n' "$1" >&3; rd; }
rd
say "EHLO probe.werwa.devnet"; say "MAIL FROM:<no-reply@werwa.local>"; say "RCPT TO:<org@test.werwa.local>"; say "DATA"
printf 'From: no-reply@werwa.local\r\nTo: org@test.werwa.local\r\nSubject: %s\r\n\r\nW5\r\n.\r\n' "$3" >&3; rd
say "QUIT"
SH
check "W5 werwa.docker.env: thư gửi qua SPRING_MAIL_HOST/PORT từ trong mạng tới Mailpit" "$(mail_found "$TAG_D")" 1
check "W5 werwa.docker.env: Alertmanager trả lời từ trong mạng" \
  "$(http_in_net GET "$(ev "$D" WERWA_MONITORING_ALERTMANAGERURL)/-/ready" | head -c 2)" "OK"
check "W5 hai biến thể cùng mật khẩu, hợp đồng, Safe" \
  "$(grep -E '^(SPRING_DATASOURCE_PASSWORD|WERWA_NATS_PASSWORD|WERWA_CONTRACTS_|WERWA_SAFES_|WERWA_CHAIN_ID)' "$D" | sha256sum)" \
  "$(grep -E '^(SPRING_DATASOURCE_PASSWORD|WERWA_NATS_PASSWORD|WERWA_CONTRACTS_|WERWA_SAFES_|WERWA_CHAIN_ID)' "$H" | sha256sum)"

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
