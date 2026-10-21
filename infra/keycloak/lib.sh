#!/usr/bin/env bash
# Hàm dùng chung cho keycloak.sh và infra/test/keycloak.sh.

KC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$KC_DIR/../devnet/lib.sh"

OUT="$KC_DIR/out"
ENV_FILE="$OUT/.env"
USERS_FILE="$OUT/users.json"
SVC_DIR="$KC_DIR/../services"
SVC_ENV="$SVC_DIR/out/.env"
REALM=werwa
# Issuer cố định (I8): mọi URL phía trình duyệt dùng localhost:8180.
KC_URL="${KC_URL:-http://localhost:8180}"
KC_MGMT="http://127.0.0.1:9180"
KC_INTERNAL="http://keycloak.werwa.devnet:8080"

kc() { docker compose -p werwa-keycloak -f "$KC_DIR/compose.yaml" --env-file "$ENV_FILE" "$@"; }
svc() { docker compose -p werwa-services -f "$SVC_DIR/compose.yaml" --env-file "$SVC_ENV" "$@"; }
env_get() { grep -E "^$1=" "${2:-$ENV_FILE}" | cut -d= -f2-; }

wait_keycloak() {
  local t=0
  until curl -sf "$KC_MGMT/health/ready" >/dev/null 2>&1; do
    [ "$t" -ge 300 ] && { kc logs --no-color --tail 50 >&2 || true; die "Keycloak không sẵn sàng sau 300 giây"; }
    sleep 3; t=$((t + 3))
  done
}

# Token admin của realm master (direct grant chỉ mở ở realm master, không phải realm werwa).
admin_token() {
  curl -sSf "$KC_URL/realms/master/protocol/openid-connect/token" \
    --data-urlencode grant_type=password --data-urlencode client_id=admin-cli \
    --data-urlencode username=admin --data-urlencode password="$(env_get KEYCLOAK_ADMIN_PASSWORD)" \
    | jq -r .access_token
}
# Token admin sống 60 giây: lấy lại sau 45 giây.
TOKEN="" TOKEN_AT=0
fresh_token() {
  if [ -z "$TOKEN" ] || [ $(( $(date +%s) - TOKEN_AT )) -ge 45 ]; then
    TOKEN=$(admin_token); TOKEN_AT=$(date +%s)
  fi
}
# kc_api <method> <đường dẫn sau /admin> [tham số curl...] → thân phản hồi; HTTP lỗi thì trả mã khác 0.
kc_api() {
  local method="$1" path="$2"; shift 2
  fresh_token
  curl -sSf -X "$method" -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
    "$KC_URL/admin$path" "$@"
}
realm_exists() {
  local code
  fresh_token
  code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$KC_URL/admin/realms/$REALM")
  case "$code" in 200) return 0 ;; 404) return 1 ;; *) die "hỏi realm $REALM trả HTTP $code" ;; esac
}


# ── Đăng nhập browser flow bằng curl: authorization code + PKCE S256, form mật khẩu, form OTP ──
# Dùng cho login.sh và kiểm thử. Trạng thái giữ trong biến LOGIN_*; mỗi lần login_start là một phiên mới.
LOGIN_CLIENT=werwa-web
LOGIN_REDIRECT="http://localhost:5173/callback"
LOGIN_DIR=""

user_field() { jq -er --arg u "$1" --arg f "$2" '.[] | select(.username == $u) | .[$f]' "$USERS_FILE"; }

_form_action() { grep -oE 'action="[^"]+"' <<<"$1" | head -1 | sed 's/^action="//; s/"$//; s/&amp;/\&/g'; }
page_id() { grep -oE 'data-page-id="[^"]+"' <<<"$1" | head -1 | sed 's/^data-page-id="//; s/"$//'; }

# login_start: mở trang đăng nhập; LOGIN_PAGE là HTML trả về.
login_start() {
  [ -n "$LOGIN_DIR" ] && rm -rf "$LOGIN_DIR"
  LOGIN_DIR=$(mktemp -d)
  LOGIN_VERIFIER=$(openssl rand -hex 32)
  local challenge
  challenge=$(printf %s "$LOGIN_VERIFIER" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')
  LOGIN_PAGE=$(curl -sS -c "$LOGIN_DIR/jar" -b "$LOGIN_DIR/jar" -G "$KC_URL/realms/$REALM/protocol/openid-connect/auth" \
    --data-urlencode client_id="$LOGIN_CLIENT" --data-urlencode response_type=code \
    --data-urlencode scope=openid --data-urlencode redirect_uri="$LOGIN_REDIRECT" \
    --data-urlencode code_challenge="$challenge" --data-urlencode code_challenge_method=S256 \
    --data-urlencode state="$(openssl rand -hex 8)")
  LOGIN_LOCATION=""
}

# _login_post <tham số curl...>: gửi form đang mở; cập nhật LOGIN_PAGE và LOGIN_LOCATION (redirect, nếu có).
_login_post() {
  local action
  action=$(_form_action "$LOGIN_PAGE")
  [ -n "$action" ] || { LOGIN_LOCATION=""; return 1; }
  LOGIN_PAGE=$(curl -sS -c "$LOGIN_DIR/jar" -b "$LOGIN_DIR/jar" -D "$LOGIN_DIR/headers" "$action" "$@")
  LOGIN_LOCATION=$(grep -i '^location:' "$LOGIN_DIR/headers" | tr -d '\r' | cut -d' ' -f2- || true)
  # Chuyển hướng trong Keycloak (ví dụ sang bước bắt buộc cài OTP) thì đi tiếp; chỉ dừng ở redirect về client.
  while [ -n "$LOGIN_LOCATION" ] && [[ "$LOGIN_LOCATION" == "$KC_URL"/* ]]; do
    LOGIN_PAGE=$(curl -sS -c "$LOGIN_DIR/jar" -b "$LOGIN_DIR/jar" -D "$LOGIN_DIR/headers" "$LOGIN_LOCATION")
    LOGIN_LOCATION=$(grep -i '^location:' "$LOGIN_DIR/headers" | tr -d '\r' | cut -d' ' -f2- || true)
  done
}
login_password() { _login_post --data-urlencode username="$1" --data-urlencode password="$2" --data-urlencode credentialId=; }
login_otp() { _login_post --data-urlencode otp="$1"; }
login_code() { sed -n 's/.*[?&]code=\([^&]*\).*/\1/p' <<<"$LOGIN_LOCATION"; }

# login_exchange: đổi code lấy token; in phản hồi JSON của endpoint token.
login_exchange() {
  curl -sS "$KC_URL/realms/$REALM/protocol/openid-connect/token" \
    --data-urlencode grant_type=authorization_code --data-urlencode client_id="$LOGIN_CLIENT" \
    --data-urlencode code="$(login_code)" --data-urlencode redirect_uri="$LOGIN_REDIRECT" \
    --data-urlencode code_verifier="$LOGIN_VERIFIER"
}

# otp_now <username>: mã OTP hiện tại. Keycloak không nhận lại mã đã dùng, nên nếu bước thời gian này
# đã dùng cho người này (ghi ở out/.otp-used/) thì chờ sang bước kế tiếp.
otp_now() {
  local u="$1" step used dir="$OUT/.otp-used"
  mkdir -p "$dir"
  step=$(( $(date +%s) / 30 ))
  used=$(cat "$dir/$u" 2>/dev/null || echo 0)
  if [ "$step" -le "$used" ]; then
    sleep $(( (used + 1) * 30 - $(date +%s) + 1 )); step=$(( $(date +%s) / 30 ))
  fi
  echo "$step" >"$dir/$u"
  python3 "$KC_DIR/totp.py" "$(user_field "$u" otpSecret)"
}

# login_user <username>: đăng nhập trọn luồng bằng mật khẩu và OTP trong out/users.json; in phản hồi token.
login_user() {
  local u="$1"
  login_start
  login_password "$u" "$(user_field "$u" password)"
  [ "$(page_id "$LOGIN_PAGE")" = login-login-otp ] || { log "$u: không thấy form OTP (trang $(page_id "$LOGIN_PAGE"))"; return 2; }
  login_otp "$(otp_now "$u")"
  [ -n "$(login_code)" ] || { log "$u: OTP không được chấp nhận"; return 3; }
  login_exchange
}

# jwt_claims <jwt>: in phần payload đã giải mã.
jwt_claims() { python3 -c 'import base64,json,sys; p=sys.argv[1].split(".")[1]; p+="="*(-len(p)%4); print(json.dumps(json.loads(base64.urlsafe_b64decode(p))))' "$1"; }
