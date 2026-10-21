#!/usr/bin/env bash
# Hàm dùng chung cho keycloak.sh và infra/test/keycloak.sh.

KC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$KC_DIR/../devnet/lib.sh"

OUT="$KC_DIR/out"
ENV_FILE="$OUT/.env"
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

