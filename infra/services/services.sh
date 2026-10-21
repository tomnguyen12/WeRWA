#!/usr/bin/env bash
# Dịch vụ dùng chung cho backend trên devnet: PostgreSQL và NATS JetStream.
#
#   ./services.sh up       sinh mật khẩu (lần đầu), chạy dịch vụ, tạo DB và stream; chạy lại an toàn
#   ./services.sh status   trạng thái container, DB, stream
#   ./services.sh down     dừng, giữ dữ liệu
#   ./services.sh reset    dừng và xóa dữ liệu (volume); giữ mật khẩu
#   ./services.sh clean    dừng, xóa dữ liệu và out/
#
# Biến môi trường: POSTGRES_IMAGE (mặc định postgres:18.6), NATS_IMAGE (mặc định nats:2.15.1).
# Cần: docker (Compose v2), curl, jq, openssl, nats (natscli 0.5.0).
#
# Kết quả trong out/ (gitignore):
#   .env          mật khẩu cho compose
#   backend.env   biến kết nối cho backend chạy trên máy (127.0.0.1); trong mạng
#                 werwa-devnet-app thì dùng postgres.werwa.devnet và nats.werwa.devnet

set -euo pipefail
SERVICES_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SERVICES_DIR/../devnet/lib.sh"

OUT="$SERVICES_DIR/out"
ENV_FILE="$OUT/.env"
STREAM_FILE="$SERVICES_DIR/nats/stream-werwa.json"
NATS_HTTP="http://127.0.0.1:8222"

sc() { docker compose -p werwa-services -f "$SERVICES_DIR/compose.yaml" --env-file "$ENV_FILE" "$@"; }
secret() { openssl rand -hex 24; }
env_get() { grep -E "^$1=" "$ENV_FILE" | cut -d= -f2-; }
nats_admin() { nats --server "nats://127.0.0.1:4222" --user werwa_admin --password "$(env_get NATS_ADMIN_PASSWORD)" "$@"; }

ensure_secrets() {
  mkdir -p "$OUT"
  if [ ! -f "$ENV_FILE" ]; then
    (umask 077; cat >"$ENV_FILE" <<EOF
# Sinh bởi services.sh — mật khẩu devnet, không dùng ở môi trường khác.
POSTGRES_PASSWORD=$(secret)
WERWA_APP_DB_PASSWORD=$(secret)
NATS_APP_PASSWORD=$(secret)
NATS_ADMIN_PASSWORD=$(secret)
EOF
    )
    log "đã sinh mật khẩu vào $ENV_FILE"
  fi
}

wait_postgres() {
  local t=0
  until sc exec -T postgres pg_isready -U postgres -q 2>/dev/null; do
    [ "$t" -ge 120 ] && die "PostgreSQL không sẵn sàng"
    sleep 2; t=$((t + 2))
  done
}

wait_nats() {
  local t=0
  until curl -sf "$NATS_HTTP/healthz?js-enabled-only=true" >/dev/null 2>&1; do
    [ "$t" -ge 60 ] && die "NATS JetStream không sẵn sàng"
    sleep 2; t=$((t + 2))
  done
}

# DB `werwa` thuộc role `werwa_app` (không phải superuser). Sáu schema do Flyway của backend tạo.
provision_postgres() {
  sc exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d postgres -q \
    -v pw="$(env_get WERWA_APP_DB_PASSWORD)" <<'SQL'
SELECT 'CREATE ROLE werwa_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE'
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'werwa_app') \gexec
ALTER ROLE werwa_app WITH LOGIN PASSWORD :'pw';
SELECT 'CREATE DATABASE werwa OWNER werwa_app ENCODING ''UTF8'' TEMPLATE template0'
 WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'werwa') \gexec
REVOKE ALL ON DATABASE werwa FROM PUBLIC;
GRANT CONNECT, CREATE, TEMPORARY ON DATABASE werwa TO werwa_app;
-- Mặc định mọi role vào được DB postgres; chỉ để superuser vào.
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
SQL
  log "PostgreSQL: DB werwa, role werwa_app"
}

# Stream WERWA theo stream-werwa.json (I2). Đã có thì kiểm cấu hình khớp, lệch thì dừng.
provision_stream() {
  local want have
  if ! nats_admin stream info WERWA --json >/dev/null 2>&1; then
    nats_admin stream add WERWA --config "$STREAM_FILE" >/dev/null
    log "NATS: đã tạo stream WERWA"
  fi
  want=$(jq -S '{subjects, retention, storage, max_age, duplicate_window, deny_delete, deny_purge}' "$STREAM_FILE")
  have=$(nats_admin stream info WERWA --json | jq -S '.config | {subjects, retention, storage, max_age, duplicate_window, deny_delete, deny_purge}')
  [ "$want" = "$have" ] || die "stream WERWA trên NATS lệch với $STREAM_FILE: $have"
}

write_backend_env() {
  (umask 077; cat >"$OUT/backend.env" <<EOF
# Sinh bởi services.sh — kết nối cho backend chạy trên máy. Trong mạng werwa-devnet-app thay
# 127.0.0.1 bằng postgres.werwa.devnet và nats.werwa.devnet.
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/werwa
SPRING_DATASOURCE_USERNAME=werwa_app
SPRING_DATASOURCE_PASSWORD=$(env_get WERWA_APP_DB_PASSWORD)
WERWA_NATS_URL=nats://127.0.0.1:4222
WERWA_NATS_USERNAME=werwa_app
WERWA_NATS_PASSWORD=$(env_get NATS_APP_PASSWORD)
WERWA_NATS_STREAM=WERWA
EOF
  )
}

cmd_up() {
  need docker; need curl; need jq; need openssl; need nats
  ensure_secrets
  sc up -d
  wait_postgres; provision_postgres
  wait_nats; provision_stream
  write_backend_env
  log "dịch vụ sẵn sàng; biến kết nối: $OUT/backend.env"
}

cmd_status() {
  sc ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'
  nats_admin stream info WERWA --json 2>/dev/null | jq '{stream: .config.name, messages: .state.messages, consumers: .state.consumer_count}' || true
}

case "${1:-}" in
  up) cmd_up ;;
  status) cmd_status ;;
  down) sc down ;;
  reset) sc down -v; log "đã xóa dữ liệu PostgreSQL và NATS" ;;
  clean) [ -f "$ENV_FILE" ] && sc down -v || true; rm -rf "$OUT"; log "đã xóa out/" ;;
  *) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
