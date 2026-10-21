#!/usr/bin/env bash
# Kiểm người dùng thử và đăng nhập của realm werwa (infra/keycloak). Chạy sau `infra/keycloak/keycloak.sh up`.
# Cần: docker, curl, jq, openssl, python3.
#
#   US-4.07-AC1  đăng nhập qua OIDC bắt buộc MFA: đúng mật khẩu chưa có code, phải qua form OTP;
#                OTP sai bị từ chối; OTP đúng thì nhận code và đổi được token
#   K7   cả 24 người dùng thử đăng nhập được bằng khóa OTP sinh sẵn (I4); access token và ID token có
#        org_id đúng seed/orgs.json (I6), roles đúng vai của tổ chức, không có vai khác; iss, aud, azp đúng
#   K8   direct grant (ROPC) với mật khẩu và OTP đúng của người dùng thật vẫn không cấp token
#   K9   người dùng mới chưa có OTP: sau mật khẩu bị buộc cài OTP, không nhận code
#   K10  quá SSO idle thì refresh token bị từ chối; trước đó refresh được (cần WERWA_KC_SSO_IDLE ≤ 120
#        khi tạo realm, CI đặt 60; mặc định 900 thì bỏ qua và ghi rõ)
#   K11  khởi động lại Keycloak: người dùng và khóa OTP còn, đăng nhập lại được
#   K12  out/users.json chỉ chủ đọc được (600) và bị git bỏ qua

set -euo pipefail
INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$INFRA/keycloak/lib.sh"

need docker; need curl; need jq; need openssl; need python3
trap 'annotate "kiểm thử dừng ở dòng $LINENO: $BASH_COMMAND"' ERR
trap '[ -n "$LOGIN_DIR" ] && rm -rf "$LOGIN_DIR"' EXIT

[ -f "$USERS_FILE" ] || die "chưa có $USERS_FILE; chạy keycloak/keycloak.sh up"
check() { local name="$1" got="$2" want="$3"; [ "$got" = "$want" ] && pass "$name" || fail "$name: $got, mong đợi $want"; }
ISS="http://localhost:8180/realms/$REALM"

# ── US-4.07-AC1 ───────────────────────────────────────────────────────────────
U=holco-checker
login_start
check "US-4.07-AC1 mở đăng nhập: trang mật khẩu" "$(page_id "$LOGIN_PAGE")" login-login
login_password "$U" "$(user_field "$U" password)"
check "US-4.07-AC1 đúng mật khẩu: chuyển sang form OTP" "$(page_id "$LOGIN_PAGE")" login-login-otp
check "US-4.07-AC1 đúng mật khẩu: chưa có code" "$(login_code)" ""
good=$(otp_now "$U")
bad=$(printf '%06d' $(( (10#$good + 1) % 1000000 )))
login_otp "$bad"
check "US-4.07-AC1 OTP sai: không có code" "$(login_code)" ""
check "US-4.07-AC1 OTP sai: vẫn ở form OTP" "$(page_id "$LOGIN_PAGE")" login-login-otp
login_otp "$good"
if [ -n "$(login_code)" ]; then pass "US-4.07-AC1 OTP đúng: nhận code"; else fail "US-4.07-AC1 OTP đúng: không có code"; fi
check "US-4.07-AC1 OTP đúng: đổi code lấy được access token" \
  "$(login_exchange | jq -r 'if .access_token then "có" else (.error // "không") end')" "có"

# ── K7 claim của từng người dùng thử ──────────────────────────────────────────
ok=0 total=0
while IFS= read -r u; do
  total=$((total + 1))
  want=$(jq -c --arg u "$u" '.[] | select(.username == $u) | {org_id: .orgId, roles: (.roles | sort)}' "$USERS_FILE")
  if ! tok=$(login_user "$u"); then fail "K7 $u không đăng nhập được"; continue; fi
  at=$(jwt_claims "$(jq -r .access_token <<<"$tok")")
  id=$(jwt_claims "$(jq -r .id_token <<<"$tok")")
  got_at=$(jq -c '{org_id, roles: ((.roles // []) | sort)}' <<<"$at")
  got_id=$(jq -c '{org_id, roles: ((.roles // []) | sort)}' <<<"$id")
  meta=$(jq -c --arg iss "$ISS" '[.iss == $iss, ([.aud] | flatten | index("werwa-api") != null), .azp == "werwa-web",
    (has("realm_access") or has("resource_access") | not)]' <<<"$at")
  if [ "$got_at" = "$want" ] && [ "$got_id" = "$want" ] && [ "$meta" = '[true,true,true,true]' ]; then ok=$((ok + 1))
  else fail "K7 $u: access $got_at, ID $got_id, [iss, aud, azp, không realm_access] $meta; mong đợi $want"; fi
done < <(jq -r '.[].username' "$USERS_FILE")
check "K7 người dùng thử đăng nhập bằng OTP sinh sẵn, claim đúng" "$ok/$total" "24/24"

# ── K8 direct grant với thông tin đăng nhập thật ─────────────────────────────
U=ubnd-maker
for c in admin-cli werwa-web; do
  body=$(curl -s "$KC_URL/realms/$REALM/protocol/openid-connect/token" \
    --data-urlencode grant_type=password --data-urlencode client_id="$c" \
    --data-urlencode username="$U" --data-urlencode password="$(user_field "$U" password)" \
    --data-urlencode totp="$(python3 "$KC_DIR/totp.py" "$(user_field "$U" otpSecret)")" || true)
  if jq -e '.access_token' <<<"$body" >/dev/null 2>&1; then fail "K8 direct grant qua $c cấp token cho $U"
  else pass "K8 direct grant qua $c với mật khẩu và OTP đúng không cấp token"; fi
done

# ── K9 người dùng mới chưa có OTP ─────────────────────────────────────────────
PROBE="k9-probe-$(openssl rand -hex 4)" PROBE_PW="$(openssl rand -hex 16)"
kc_api POST "/realms/$REALM/users" --data-binary "$(jq -nc --arg u "$PROBE" --arg pw "$PROBE_PW" '{
  username: $u, enabled: true, email: ($u + "@devnet.werwa.local"), emailVerified: true, firstName: "probe", lastName: "k9",
  credentials: [{type: "password", value: $pw, temporary: false}]}')" >/dev/null
probe_id=$(kc_api GET "/realms/$REALM/users?exact=true&briefRepresentation=true" -G --data-urlencode "username=$PROBE" | jq -r '.[0].id')
login_start
login_password "$PROBE" "$PROBE_PW"
check "K9 người dùng chưa có OTP: sau mật khẩu là trang cài OTP" "$(page_id "$LOGIN_PAGE")" login-login-config-totp
check "K9 người dùng chưa có OTP: không có code" "$(login_code)" ""
kc_api DELETE "/realms/$REALM/users/$probe_id" >/dev/null

# ── K10 SSO idle ──────────────────────────────────────────────────────────────
IDLE=$(kc_api GET "/realms/$REALM" | jq .ssoSessionIdleTimeout)
if [ "$IDLE" -le 120 ]; then
  U=investor-a-checker
  tok=$(login_user "$U")
  refresh() { curl -s "$KC_URL/realms/$REALM/protocol/openid-connect/token" --data-urlencode grant_type=refresh_token \
    --data-urlencode client_id=werwa-web --data-urlencode refresh_token="$1"; }
  r1=$(refresh "$(jq -r .refresh_token <<<"$tok")")
  check "K10 trước idle: refresh được" "$(jq -r 'if .access_token then "có" else .error end' <<<"$r1")" "có"
  log "K10 chờ quá idle ($IDLE giây + 10)"
  sleep $((IDLE + 10))
  r2=$(refresh "$(jq -r .refresh_token <<<"$r1")")
  check "K10 quá idle $IDLE giây: refresh bị từ chối" "$(jq -r '.error // "có token"' <<<"$r2")" "invalid_grant"
else
  log "K10 bỏ qua: realm có SSO idle $IDLE giây; tạo realm với WERWA_KC_SSO_IDLE=60 để kiểm (CI làm vậy)"
fi

# ── K11 khởi động lại ─────────────────────────────────────────────────────────
kc restart keycloak >/dev/null
sleep 5
wait_keycloak
TOKEN=""
U=investor-b-maker
if tok=$(login_user "$U"); then
  check "K11 sau khởi động lại: $U đăng nhập bằng OTP cũ, org_id đúng" \
    "$(jwt_claims "$(jq -r .access_token <<<"$tok")" | jq -r .org_id)" "$(user_field "$U" orgId)"
else fail "K11 sau khởi động lại: $U không đăng nhập được"; fi

# ── K12 tệp bí mật ────────────────────────────────────────────────────────────
check "K12 out/users.json quyền 600" "$(stat -c '%a' "$USERS_FILE")" 600
if git -C "$INFRA" check-ignore -q "$USERS_FILE"; then pass "K12 out/users.json bị git bỏ qua"
else fail "K12 out/users.json không bị git bỏ qua"; fi

[ "$FAILED" = 0 ] || die "kiểm người dùng thử Keycloak trượt"
log "Keycloak, người dùng thử: tất cả đạt"
