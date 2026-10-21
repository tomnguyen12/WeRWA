#!/usr/bin/env bash
# Kiểm Keycloak realm werwa (infra/keycloak). Chạy sau `infra/keycloak/keycloak.sh up`.
# Cần: docker, curl, jq, yq (đọc enum vai từ api/openapi.yaml).
#
#   K1  realm có đúng 11 vai nghiệp vụ, trùng enum `roles` của api/openapi.yaml; werwa-web chỉ đưa
#       đúng 11 vai đó vào token (fullScopeAllowed tắt, scope mapping đủ 11 vai)
#   K2  vai mặc định rỗng; mapper `roles` (mảng phẳng) và `org_id` (I3), audience werwa-api;
#       thuộc tính org_id trong User Profile chỉ admin xem và sửa (I7)
#   K3  OTP bắt buộc (I4): browser flow mật khẩu + OTP đều REQUIRED, CONFIGURE_TOTP mặc định,
#       TOTP SHA-1 6 số 30 giây; werwa-web là client công khai, PKCE S256, không implicit, không
#       direct grant; direct grant của realm bị chặn với mọi client, kể cả admin-cli
#   K4  bảng của Keycloak ở DB `keycloak` trên PostgreSQL của infra/services, chủ là role `keycloak`
#       (không phải superuser); `keycloak` và `werwa_app` không vào được DB của nhau
#   K5  khởi động lại Keycloak: realm còn nguyên; `keycloak.sh up` lần nữa không tạo lại, không lệch
#   K6  issuer cố định http://localhost:8180/realms/werwa, cả từ máy lẫn từ trong mạng
#       werwa-devnet-app; JWKS trong mạng đi qua keycloak.werwa.devnet (I8)

set -euo pipefail
INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$INFRA/keycloak/lib.sh"

need docker; need curl; need jq; need yq
trap 'annotate "kiểm thử dừng ở dòng $LINENO: $BASH_COMMAND"' ERR

[ -f "$ENV_FILE" ] || die "chưa có $ENV_FILE; chạy keycloak/keycloak.sh up"
check() { local name="$1" got="$2" want="$3"; [ "$got" = "$want" ] && pass "$name" || fail "$name: $got, mong đợi $want"; }

export_realm() { kc_api POST "/realms/$REALM/partial-export?exportClients=true&exportGroupsAndRoles=true"; }
R=$(export_realm)
client() { jq -c --arg id "$1" '.clients[] | select(.clientId == $id)' <<<"$R"; }
WEB=$(client werwa-web)
mapper() { jq -c --arg n "$1" '.protocolMappers[] | select(.name == $n) | {protocolMapper, config}' <<<"$WEB"; }

# ── K1 vai ────────────────────────────────────────────────────────────────────
ENUM=$(yq -r '.components.schemas.Organization.properties.roles.items.enum[]' "$INFRA/../api/openapi.yaml" | sort | jq -Rsc 'split("\n") | map(select(length > 0))')
check "K1 enum vai trong OpenAPI có 11 vai" "$(jq length <<<"$ENUM")" 11
ROLES=$(jq -c '[.roles.realm[].name | select(IN("default-roles-werwa", "offline_access", "uma_authorization") | not)] | sort' <<<"$R")
check "K1 vai nghiệp vụ của realm trùng enum OpenAPI" "$ROLES" "$ENUM"
check "K1 werwa-web không đưa mọi vai vào token (fullScopeAllowed)" "$(jq -r .fullScopeAllowed <<<"$WEB")" false
check "K1 scope mapping của werwa-web đúng 11 vai" \
  "$(jq -c '[.scopeMappings[] | select(.client == "werwa-web") | .roles[]] | sort' <<<"$R")" "$ENUM"

# ── K2 vai mặc định, claim, User Profile ─────────────────────────────────────
check "K2 vai mặc định default-roles-werwa rỗng" \
  "$(kc_api GET "/realms/$REALM/roles/default-roles-$REALM/composites" | jq -c '[.[].name]')" '[]'
check "K2 mapper roles: realm role → claim roles, mảng phẳng, trong access và ID token" \
  "$(mapper roles | jq -c '{m: .protocolMapper, c: .config | {"claim.name", multivalued, "access.token.claim", "id.token.claim"}}')" \
  '{"m":"oidc-usermodel-realm-role-mapper","c":{"claim.name":"roles","multivalued":"true","access.token.claim":"true","id.token.claim":"true"}}'
check "K2 mapper org_id: thuộc tính org_id → claim org_id, một giá trị, trong access và ID token" \
  "$(mapper org_id | jq -c '{m: .protocolMapper, c: .config | {"user.attribute", "claim.name", multivalued, "access.token.claim", "id.token.claim"}}')" \
  '{"m":"oidc-usermodel-attribute-mapper","c":{"user.attribute":"org_id","claim.name":"org_id","multivalued":"false","access.token.claim":"true","id.token.claim":"true"}}'
check "K2 mapper audience: aud có werwa-api trong access token" \
  "$(mapper 'audience werwa-api' | jq -c '{m: .protocolMapper, a: .config."included.client.audience", t: .config."access.token.claim"}')" \
  '{"m":"oidc-audience-mapper","a":"werwa-api","t":"true"}'
check "K2 werwa-web không dùng client scope roles (realm_access) hay offline_access" \
  "$(jq -c '[(.defaultClientScopes + .optionalClientScopes)[] | select(IN("roles", "offline_access", "microprofile-jwt"))]' <<<"$WEB")" '[]'
check "K2 org_id trong User Profile chỉ admin xem và sửa" \
  "$(kc_api GET "/realms/$REALM/users/profile" | jq -c '.attributes[] | select(.name == "org_id") | .permissions')" \
  '{"view":["admin"],"edit":["admin"]}'

# ── K3 OTP bắt buộc, client, direct grant ─────────────────────────────────────
check "K3 browser flow của realm là werwa browser" "$(jq -r .browserFlow <<<"$R")" "werwa browser"
check "K3 form đăng nhập: mật khẩu và OTP đều REQUIRED" \
  "$(kc_api GET "/realms/$REALM/authentication/flows/werwa%20browser/executions" \
      | jq -c '[.[] | select(.level == 1) | {(.providerId): .requirement}] | add')" \
  '{"auth-username-password-form":"REQUIRED","auth-otp-form":"REQUIRED"}'
check "K3 CONFIGURE_TOTP bật và mặc định cho người dùng mới; delete_credential tắt" \
  "$(jq -c '[.requiredActions[] | select(.alias | IN("CONFIGURE_TOTP", "delete_credential")) | {(.alias): [.enabled, .defaultAction]}] | add' <<<"$R")" \
  '{"CONFIGURE_TOTP":[true,true],"delete_credential":[false,false]}'
check "K3 chính sách OTP: TOTP, SHA-1, 6 số, 30 giây" \
  "$(jq -c '[.otpPolicyType, .otpPolicyAlgorithm, .otpPolicyDigits, .otpPolicyPeriod]' <<<"$R")" '["totp","HmacSHA1",6,30]'
check "K3 werwa-web: công khai, code flow, PKCE S256, không implicit, không direct grant" \
  "$(jq -c '[.publicClient, .standardFlowEnabled, .attributes."pkce.code.challenge.method", .implicitFlowEnabled, .directAccessGrantsEnabled]' <<<"$WEB")" \
  '[true,true,"S256",false,false]'
check "K3 direct grant của realm dùng flow chặn" "$(jq -r .directGrantFlow <<<"$R")" "werwa direct grant"
for c in admin-cli werwa-web; do
  body=$(curl -s "$KC_URL/realms/$REALM/protocol/openid-connect/token" \
    --data-urlencode grant_type=password --data-urlencode client_id="$c" \
    --data-urlencode username=probe --data-urlencode password=probe-password || true)
  if jq -e '.access_token' <<<"$body" >/dev/null 2>&1; then fail "K3 direct grant qua $c cấp token"
  else pass "K3 direct grant qua $c không cấp token"; fi
done

# ── K4 PostgreSQL ─────────────────────────────────────────────────────────────
su_sql() { svc exec -T postgres psql -U postgres -d "$2" -tAX -v ON_ERROR_STOP=1 -c "$1"; }
as_sql() { svc exec -T -e PGPASSWORD="$3" postgres psql -h 127.0.0.1 -U "$1" -d "$2" -tAX -v ON_ERROR_STOP=1 -c 'select 1'; }
check "K4 DB keycloak thuộc role keycloak" \
  "$(su_sql "select pg_get_userbyid(datdba) from pg_database where datname = 'keycloak'" postgres | tr -d '\r')" keycloak
check "K4 role keycloak không phải superuser, không tạo DB, không tạo role" \
  "$(su_sql "select rolsuper::text || rolcreatedb::text || rolcreaterole::text from pg_roles where rolname = 'keycloak'" postgres | tr -d '\r')" falsefalsefalse
check "K4 realm werwa lưu trong DB keycloak" \
  "$(su_sql "select count(*) from public.realm where name = 'werwa'" keycloak | tr -d '\r')" 1
if as_sql keycloak werwa "$(env_get KEYCLOAK_DB_PASSWORD)" >/dev/null 2>&1; then fail "K4 keycloak vào được DB werwa"
else pass "K4 keycloak không vào được DB werwa"; fi
if as_sql werwa_app keycloak "$(env_get WERWA_APP_DB_PASSWORD "$SVC_ENV")" >/dev/null 2>&1; then fail "K4 werwa_app vào được DB keycloak"
else pass "K4 werwa_app không vào được DB keycloak"; fi

# ── K5 khởi động lại ──────────────────────────────────────────────────────────
kc restart keycloak >/dev/null
sleep 5
wait_keycloak
TOKEN=""
if realm_exists; then pass "K5 sau khi khởi động lại realm werwa còn"; else fail "K5 sau khi khởi động lại mất realm werwa"; fi
up_log=$("$INFRA/keycloak/keycloak.sh" up 2>&1) || { printf '%s\n' "$up_log" >&2; fail "K5 keycloak.sh up lần nữa thất bại"; }
if grep -q "đã có; kiểm lệch" <<<"$up_log" && ! grep -q "đã tạo realm" <<<"$up_log"; then
  pass "K5 keycloak.sh up lần nữa: không tạo lại realm, không lệch"
else fail "K5 keycloak.sh up lần nữa: $up_log"; fi

# ── K6 issuer ─────────────────────────────────────────────────────────────────
ISS="http://localhost:8180/realms/$REALM"
check "K6 issuer nhìn từ máy" \
  "$(curl -sf "$KC_URL/realms/$REALM/.well-known/openid-configuration" | jq -r .issuer)" "$ISS"
# Gọi từ trong mạng werwa-devnet-app bằng /dev/tcp của bash trong container Keycloak (ảnh không có curl).
inside=$(kc exec -T keycloak bash -c '
  exec 3<>/dev/tcp/keycloak.werwa.devnet/8080
  printf "GET /realms/werwa/.well-known/openid-configuration HTTP/1.0\r\nHost: keycloak.werwa.devnet:8080\r\n\r\n" >&3
  cat <&3' | tr -d '\r' | sed '1,/^$/d')
check "K6 issuer nhìn từ trong mạng werwa-devnet-app" "$(jq -r .issuer <<<"$inside")" "$ISS"
check "K6 JWKS trong mạng đi qua keycloak.werwa.devnet" \
  "$(jq -r .jwks_uri <<<"$inside")" "http://keycloak.werwa.devnet:8080/realms/$REALM/protocol/openid-connect/certs"

[ "$FAILED" = 0 ] || die "kiểm Keycloak trượt"
log "Keycloak: tất cả đạt"
