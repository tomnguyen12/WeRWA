#!/usr/bin/env bash
# Keycloak cho devnet: realm werwa trên PostgreSQL của infra/services (ADR-15, I3, I4).
#
#   ./keycloak.sh up       sinh mật khẩu (lần đầu), tạo DB keycloak, chạy Keycloak, tạo realm werwa;
#                          realm đã có thì so với realm/werwa-realm.json, lệch thì dừng; tạo người dùng
#                          thử theo seed/orgs.json, mỗi người có khóa OTP sinh sẵn (I4, I6). Chạy lại an toàn
#   ./keycloak.sh status   trạng thái container, realm, issuer
#   ./keycloak.sh down     dừng, giữ dữ liệu
#   ./keycloak.sh reset    dừng và xóa DB keycloak (realm, người dùng); giữ mật khẩu
#   ./keycloak.sh clean    như reset, xóa cả out/
#
# Cần chạy infra/services/services.sh up trước. Cần: docker (Compose v2), curl, jq, openssl, base32.
# Biến môi trường: KEYCLOAK_IMAGE (mặc định quay.io/keycloak/keycloak:26.8.1);
# tham số phiên (I9), đọc khi tạo realm: WERWA_KC_ACCESS_TOKEN_LIFESPAN (giây, mặc định 300),
# WERWA_KC_SSO_IDLE (mặc định 900), WERWA_KC_SSO_MAX (mặc định 28800).
#
# Kết quả trong out/ (gitignore):
#   .env                 mật khẩu DB keycloak và admin realm master
#   werwa-realm.json     realm đã dựng từ realm/ (không có bí mật), bản đem so khi kiểm lệch
#   backend.env          issuer, JWKS, audience cho backend chạy trên máy
#   users.json           người dùng thử: username, org_id, vai, mật khẩu, khóa OTP, URI otpauth
#                        (chỉ cho devnet; đăng nhập bằng ./login.sh <username>)

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

secret() { openssl rand -hex 24; }

ensure_secrets() {
  mkdir -p "$OUT"
  if [ ! -f "$ENV_FILE" ]; then
    (umask 077; cat >"$ENV_FILE" <<EOF
# Sinh bởi keycloak.sh — mật khẩu devnet, không dùng ở môi trường khác.
KEYCLOAK_DB_PASSWORD=$(secret)
KEYCLOAK_ADMIN_PASSWORD=$(secret)
EOF
    )
    log "đã sinh mật khẩu vào $ENV_FILE"
  fi
}

need_services() {
  [ -f "$SVC_ENV" ] || die "chưa có $SVC_ENV; chạy infra/services/services.sh up trước"
  svc exec -T postgres pg_isready -U postgres -q 2>/dev/null \
    || die "PostgreSQL của infra/services chưa chạy; chạy infra/services/services.sh up trước"
}

# DB `keycloak` thuộc role `keycloak` (không phải superuser), tách khỏi DB `werwa` của backend.
provision_db() {
  svc exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d postgres -q \
    -v pw="$(env_get KEYCLOAK_DB_PASSWORD)" <<'SQL'
SELECT 'CREATE ROLE keycloak LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE'
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'keycloak') \gexec
ALTER ROLE keycloak WITH LOGIN PASSWORD :'pw';
SELECT 'CREATE DATABASE keycloak OWNER keycloak ENCODING ''UTF8'' TEMPLATE template0'
 WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak') \gexec
REVOKE ALL ON DATABASE keycloak FROM PUBLIC;
GRANT CONNECT, CREATE, TEMPORARY ON DATABASE keycloak TO keycloak;
SQL
  log "PostgreSQL: DB keycloak, role keycloak"
}

# Dựng realm từ realm/werwa-realm.json: tham số phiên (I9) và User Profile (org_id, I7).
render_realm() {
  jq --argjson at "${WERWA_KC_ACCESS_TOKEN_LIFESPAN:-300}" \
     --argjson idle "${WERWA_KC_SSO_IDLE:-900}" \
     --argjson max "${WERWA_KC_SSO_MAX:-28800}" \
     --arg up "$(jq -c . "$KC_DIR/realm/user-profile.json")" '
    .accessTokenLifespan = $at | .ssoSessionIdleTimeout = $idle | .ssoSessionMaxLifespan = $max
    | .components["org.keycloak.userprofile.UserProfileProvider"] =
        [{providerId: "declarative-user-profile", subComponents: {}, config: {"kc.user.profile.config": [$up]}}]
  ' "$KC_DIR/realm/werwa-realm.json" >"$OUT/werwa-realm.json"
}

create_realm() {
  kc_api POST /realms --data-binary @"$OUT/werwa-realm.json" >/dev/null
  # Keycloak luôn gắn offline_access, uma_authorization và quyền tài khoản vào vai mặc định khi tạo
  # realm; bỏ hết để người dùng chỉ có đúng vai nghiệp vụ được giao.
  local composites
  composites=$(kc_api GET "/realms/$REALM/roles/default-roles-$REALM/composites")
  [ "$(jq length <<<"$composites")" = 0 ] \
    || kc_api DELETE "/realms/$REALM/roles/default-roles-$REALM/composites" --data-binary "$composites" >/dev/null
  log "đã tạo realm $REALM"
}

# So phần cấu hình cố định (realm/drift.jq) giữa bản dựng và máy chủ.
check_drift() {
  local want have
  want=$(jq -S -f "$KC_DIR/realm/drift.jq" "$OUT/werwa-realm.json")
  have=$(kc_api POST "/realms/$REALM/partial-export?exportClients=true&exportGroupsAndRoles=true" | jq -S -f "$KC_DIR/realm/drift.jq")
  if [ "$want" != "$have" ]; then
    diff <(echo "$want") <(echo "$have") >&2 || true
    die "realm $REALM trên Keycloak lệch realm/werwa-realm.json (dòng < là tệp, > là máy chủ); đổi có chủ ý thì chạy ./keycloak.sh reset rồi up"
  fi
}

# Danh sách người dùng thử: mỗi tổ chức trong seed/orgs.json có <code>-maker và <code>-checker.
# Khóa OTP là chuỗi Keycloak lưu nguyên trong secretData; khóa HMAC là byte UTF-8 của chuỗi đó,
# nên URI otpauth mang base32 của các byte này.
generate_users() {
  local list="[]" org code id roles kind username pw otp b32
  while IFS= read -r org; do
    code=$(jq -r .code <<<"$org"); id=$(jq -r .id <<<"$org"); roles=$(jq -c .roles <<<"$org")
    for kind in maker checker; do
      username="$code-$kind"; pw=$(openssl rand -hex 16); otp=$(openssl rand -hex 20)
      b32=$(printf %s "$otp" | base32 -w0 | tr -d '=')
      list=$(jq -c --arg u "$username" --arg c "$code" --arg id "$id" --argjson r "$roles" --arg k "$kind" \
        --arg pw "$pw" --arg otp "$otp" --arg b32 "$b32" '. + [{
          username: $u, orgCode: $c, orgId: $id, roles: $r, kind: $k, password: $pw, otpSecret: $otp,
          otpauthUri: ("otpauth://totp/WeRWA:" + $u + "?secret=" + $b32 + "&issuer=WeRWA&algorithm=SHA1&digits=6&period=30")
        }]' <<<"$list")
    done
  done < <(jq -c '.organizations[]' "$KC_DIR/seed/orgs.json")
  (umask 077; jq . <<<"$list" >"$1")
}

user_exists() {
  [ "$(kc_api GET "/realms/$REALM/users?exact=true&briefRepresentation=true" --data-urlencode "username=$1" -G | jq length)" -gt 0 ]
}

seed_users() {
  local want have missing import u
  if [ ! -f "$USERS_FILE" ]; then
    # Sinh vào tệp tạm; chỉ giữ khi chưa người dùng nào có trên Keycloak, để khóa OTP không lệch.
    generate_users "$USERS_FILE.new"
    while IFS= read -r u; do
      user_exists "$u" && { rm -f "$USERS_FILE.new"; die "người dùng $u đã có trên Keycloak nhưng mất $USERS_FILE; chạy ./keycloak.sh reset rồi up"; }
    done < <(jq -r '.[].username' "$USERS_FILE.new")
    mv "$USERS_FILE.new" "$USERS_FILE"
    log "đã sinh mật khẩu và khóa OTP người dùng thử vào $USERS_FILE"
  fi
  want=$(jq -c '[.organizations[] | .code as $c | ($c + "-maker"), ($c + "-checker")] | sort' "$KC_DIR/seed/orgs.json")
  have=$(jq -c '[.[].username] | sort' "$USERS_FILE")
  [ "$want" = "$have" ] || die "$USERS_FILE không khớp seed/orgs.json; chạy ./keycloak.sh clean rồi up"
  missing=()
  while IFS= read -r u; do user_exists "$u" || missing+=("$u"); done < <(jq -r '.[].username' "$USERS_FILE")
  [ "${#missing[@]}" -gt 0 ] || { log "người dùng thử: đủ $(jq length "$USERS_FILE")"; return 0; }
  import=$(jq -c '{ifResourceExists: "FAIL", users: [.[] | select(.username | IN($ARGS.positional[])) | {
      username, enabled: true, email: (.username + "@devnet.werwa.local"), emailVerified: true,
      firstName: .orgCode, lastName: .kind,
      attributes: {org_id: [.orgId]}, realmRoles: .roles,
      credentials: [
        {type: "password", value: .password, temporary: false},
        {type: "otp", userLabel: "devnet",
         secretData: ({value: .otpSecret} | tojson),
         credentialData: ({subType: "totp", digits: 6, counter: 0, period: 30, algorithm: "HmacSHA1"} | tojson)}
      ]}]}' "$USERS_FILE" --args "${missing[@]}")
  (umask 077; printf '%s' "$import" >"$OUT/.users-import.json")
  kc_api POST "/realms/$REALM/partialImport" --data-binary @"$OUT/.users-import.json" >/dev/null
  rm -f "$OUT/.users-import.json"
  log "người dùng thử: đã tạo ${#missing[@]}, tổng $(jq length "$USERS_FILE")"
}

write_backend_env() {
  (umask 077; cat >"$OUT/backend.env" <<EOF
# Sinh bởi keycloak.sh — xác thực token OIDC cho backend chạy trên máy (I3, I8). Tên biến theo relaxed
# binding của Spring (I11): bỏ dấu gạch ngang, ví dụ issuer-uri ↔ ISSUERURI.
# Issuer cố định; trong mạng werwa-devnet-app lấy JWKS qua $KC_INTERNAL thay cho 127.0.0.1:8180.
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI=$KC_URL/realms/$REALM
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI=http://127.0.0.1:8180/realms/$REALM/protocol/openid-connect/certs
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=werwa-api
WERWA_OIDC_CLIENTID=werwa-web
EOF
  )
}

cmd_up() {
  need docker; need curl; need jq; need openssl; need base32
  need_services
  ensure_secrets
  provision_db
  ensure_app_network
  kc up -d
  wait_keycloak
  render_realm
  if realm_exists; then log "realm $REALM đã có; kiểm lệch"; else create_realm; fi
  check_drift
  seed_users
  write_backend_env
  log "Keycloak sẵn sàng: $KC_URL/realms/$REALM; biến cho backend: $OUT/backend.env"
}

cmd_status() {
  kc ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'
  curl -sf "$KC_URL/realms/$REALM/.well-known/openid-configuration" | jq '{issuer, jwks_uri}' \
    || log "realm $REALM chưa trả lời"
}

drop_db() {
  [ -f "$SVC_ENV" ] && svc exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d postgres -q \
    -c 'DROP DATABASE IF EXISTS keycloak WITH (FORCE)' 2>/dev/null \
    && log "đã xóa DB keycloak" || log "không xóa được DB keycloak (PostgreSQL của infra/services không chạy?)"
}

case "${1:-}" in
  up) cmd_up ;;
  status) cmd_status ;;
  down) kc down ;;
  reset) [ -f "$ENV_FILE" ] && kc down || true; drop_db ;;
  clean) [ -f "$ENV_FILE" ] && kc down || true; drop_db; rm -rf "$OUT"; log "đã xóa out/" ;;
  *) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
