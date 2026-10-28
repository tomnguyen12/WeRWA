#!/usr/bin/env bash
# Kiểm dịch vụ dùng chung cho backend (infra/services): PostgreSQL, NATS JetStream và Mailpit.
# Chạy sau `infra/services/services.sh up`. Cần: docker, curl, jq, nats (natscli).
#
#   P1  werwa_app đăng nhập DB werwa bằng mật khẩu
#   P2  werwa_app tạo được 6 schema của 6 mô-đun (thử trong giao dịch rồi hủy)
#   P3  werwa_app không phải superuser, không tạo được DB, không vào được DB postgres
#   P4  PostgreSQL 18
#   N1  stream WERWA đúng cấu hình đã chốt (I2): werwa.>, lưu đĩa, 30 ngày, chống trùng 2 phút
#   N2  gửi hai lần cùng Nats-Msg-Id (id CloudEvent) thì stream chỉ giữ một bản
#   N3  werwa_app chỉ phát được vào werwa.>
#   N4  kết nối ẩn danh bị từ chối
#   N5  consumer bền và sự kiện còn nguyên sau khi NATS khởi động lại
#   M1  Mailpit nhận thư gửi qua SMTP theo SPRING_MAIL_HOST/PORT của backend.env: đúng người nhận, tiêu đề (Q62)
#   M2  cổng SMTP và giao diện xem thư của Mailpit chỉ mở ở 127.0.0.1
#   M3  thư còn sau khi Mailpit khởi động lại

set -euo pipefail
INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$INFRA/devnet/lib.sh"

need docker; need jq; need nats; need curl
trap 'annotate "kiểm thử dừng ở dòng $LINENO: $BASH_COMMAND"' ERR

SVC="$INFRA/services"
ENV_FILE="$SVC/out/.env"
[ -f "$ENV_FILE" ] || die "chưa có $ENV_FILE; chạy services/services.sh up"
env_get() { grep -E "^$1=" "$ENV_FILE" | cut -d= -f2-; }
sc() { docker compose -p werwa-services -f "$SVC/compose.yaml" --env-file "$ENV_FILE" "$@"; }
check() { local name="$1" got="$2" want="$3"; [ "$got" = "$want" ] && pass "$name" || fail "$name: $got, mong đợi $want"; }

APP_PW=$(env_get WERWA_APP_DB_PASSWORD)
# Chạy psql trong container, kết nối TCP như ứng dụng (không qua socket của superuser).
app_sql() { sc exec -T -e PGPASSWORD="$APP_PW" postgres psql -h 127.0.0.1 -U werwa_app -d "${2:-werwa}" -tAX -v ON_ERROR_STOP=1 -c "$1"; }

# ── PostgreSQL ────────────────────────────────────────────────────────────────
check "P1 werwa_app đăng nhập DB werwa" "$(app_sql "select current_user || '@' || current_database()" 2>&1 | tr -d '\r' || true)" "werwa_app@werwa"

schemas=$(sc exec -T -e PGPASSWORD="$APP_PW" postgres psql -h 127.0.0.1 -U werwa_app -d werwa -tAX -v ON_ERROR_STOP=1 <<'SQL' 2>&1 || true
BEGIN;
CREATE SCHEMA onboarding; CREATE SCHEMA issuance; CREATE SCHEMA distribution;
CREATE SCHEMA governance; CREATE SCHEMA integration; CREATE SCHEMA onchain;
SELECT count(*) FROM information_schema.schemata
 WHERE schema_name IN ('onboarding','issuance','distribution','governance','integration','onchain');
ROLLBACK;
SQL
)
check "P2 werwa_app tạo được 6 schema" "$(grep -E '^[0-9]+$' <<<"$schemas" | tail -1)" 6

check "P3 werwa_app không phải superuser" "$(app_sql 'select rolsuper::text from pg_roles where rolname = current_user')" false
if app_sql 'create database werwa_probe' >/dev/null 2>&1; then
  fail "P3 werwa_app tạo được DB"; app_sql 'drop database werwa_probe' >/dev/null 2>&1 || true
else pass "P3 werwa_app không tạo được DB"; fi
if app_sql 'select 1' postgres >/dev/null 2>&1; then fail "P3 werwa_app vào được DB postgres"
else pass "P3 werwa_app không vào được DB postgres"; fi

check "P4 PostgreSQL 18" "$(app_sql 'show server_version_num' | cut -c1-2)" 18

# ── NATS JetStream ────────────────────────────────────────────────────────────
admin() { nats --server nats://127.0.0.1:4222 --user werwa_admin --password "$(env_get NATS_ADMIN_PASSWORD)" "$@"; }
app() { nats --server nats://127.0.0.1:4222 --user werwa_app --password "$(env_get NATS_APP_PASSWORD)" "$@"; }

cfg=$(admin stream info WERWA --json | jq -c '.config | {subjects, storage, retention, max_age, duplicate_window, deny_delete, deny_purge}')
check "N1 stream WERWA đúng cấu hình I2" "$cfg" \
  '{"subjects":["werwa.>"],"storage":"file","retention":"limits","max_age":2592000000000000,"duplicate_window":120000000000,"deny_delete":true,"deny_purge":true}'

SUBJ=werwa.infra.smoke.checked.v1
ID="smoke-$(date +%s%N)"
before=$(admin stream info WERWA --json | jq '.state.messages')
app pub --jetstream "$SUBJ" '{"specversion":"1.0","id":"'"$ID"'"}' -H "Nats-Msg-Id:$ID" >/dev/null
app pub --jetstream "$SUBJ" '{"specversion":"1.0","id":"'"$ID"'"}' -H "Nats-Msg-Id:$ID" >/dev/null
after=$(admin stream info WERWA --json | jq '.state.messages')
check "N2 hai lần cùng Nats-Msg-Id chỉ giữ một bản" "$((after - before))" 1

# Máy chủ trả "Permissions Violation for Publish" khi gửi ra ngoài quyền; trong werwa.> thì không.
perm_out=$(app req other.subject.v1 x --timeout 2s 2>&1 || true)
perm_in=$(app req werwa.infra.smoke.probe.v1 x --timeout 2s 2>&1 || true)
if grep -q 'Permissions Violation for Publish' <<<"$perm_out" && ! grep -q 'Permissions Violation' <<<"$perm_in"; then
  pass "N3 werwa_app chỉ phát được vào werwa.>"
else fail "N3 quyền phát của werwa_app sai: ngoài [$perm_out] / trong [$perm_in]"; fi

if nats --server nats://127.0.0.1:4222 --timeout 3s pub werwa.infra.smoke.checked.v1 'x' >/dev/null 2>&1; then
  fail "N4 kết nối ẩn danh được chấp nhận"
else pass "N4 kết nối ẩn danh bị từ chối"; fi

CONSUMER=infra-smoke
admin consumer rm WERWA "$CONSUMER" -f >/dev/null 2>&1 || true
admin consumer add WERWA "$CONSUMER" --pull --filter "$SUBJ" --deliver all --ack explicit --replay instant \
  --max-deliver=-1 --max-pending=100 --no-headers-only --backoff=none --defaults >/dev/null
sc restart nats >/dev/null
t=0; until curl -sf 'http://127.0.0.1:8222/healthz?js-enabled-only=true' >/dev/null 2>&1; do
  [ "$t" -ge 60 ] && die "NATS không lên lại"; sleep 2; t=$((t + 2)); done
after_restart=$(admin stream info WERWA --json | jq '.state.messages')
pending=$(admin consumer info WERWA "$CONSUMER" --json | jq '.num_pending')
if [ "$after_restart" -ge "$after" ] && [ "$pending" -ge 1 ]; then
  pass "N5 sau khi khởi động lại: stream còn $after_restart sự kiện, consumer bền còn $pending chờ đọc"
else fail "N5 sau khi khởi động lại: stream $after_restart (trước $after), consumer chờ $pending"; fi
admin consumer rm WERWA "$CONSUMER" -f >/dev/null 2>&1 || true

# ── Mailpit (Q62) ─────────────────────────────────────────────────────────────
MP=http://127.0.0.1:8025
benv() { grep -E "^$1=" "$SVC/out/backend.env" | cut -d= -f2-; }
MAIL_HOST=$(benv SPRING_MAIL_HOST); MAIL_PORT=$(benv SPRING_MAIL_PORT)
TAG="m1-$(date +%s%N)"
msg=$(mktemp)
printf 'From: WeRWA <no-reply@werwa.local>\r\nTo: org@test.werwa.local\r\nSubject: %s\r\n\r\nKiem thu M1.\r\n' "$TAG" >"$msg"
if curl -s --max-time 10 --url "smtp://$MAIL_HOST:$MAIL_PORT" --mail-from no-reply@werwa.local \
     --mail-rcpt org@test.werwa.local --upload-file "$msg"; then :; else fail "M1 gửi SMTP tới $MAIL_HOST:$MAIL_PORT thất bại"; fi
rm -f "$msg"
found() { curl -sf "$MP/api/v1/search" --get --data-urlencode "query=subject:\"$1\"" | jq -c '[.messages[] | {to: [.To[].Address], subject: .Subject}]'; }
check "M1 Mailpit nhận thư qua SMTP ($MAIL_HOST:$MAIL_PORT), đúng người nhận và tiêu đề" \
  "$(found "$TAG")" '[{"to":["org@test.werwa.local"],"subject":"'"$TAG"'"}]'

check "M2 SMTP Mailpit chỉ mở ở 127.0.0.1" "$(sc port mailpit 1025)" "127.0.0.1:1025"
check "M2 giao diện xem thư Mailpit chỉ mở ở 127.0.0.1" "$(sc port mailpit 8025)" "127.0.0.1:8025"

sc restart mailpit >/dev/null
t=0; until curl -sf "$MP/readyz" >/dev/null 2>&1; do
  [ "$t" -ge 60 ] && die "Mailpit không lên lại"; sleep 2; t=$((t + 2)); done
check "M3 thư còn sau khi Mailpit khởi động lại" "$(found "$TAG" | jq length)" 1

[ "$FAILED" = 0 ] || die "kiểm dịch vụ backend trượt"
log "dịch vụ backend: tất cả đạt"
