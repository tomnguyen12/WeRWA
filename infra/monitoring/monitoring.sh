#!/usr/bin/env bash
# Giám sát devnet WeRWA: Prometheus + Alertmanager (docs/nfr.md mục Vận hành, US-6.01-AC3).
#
#   ./monitoring.sh up       sinh cấu hình Alertmanager, chạy giám sát; cần devnet đang chạy
#   ./monitoring.sh status   mục tiêu Prometheus và cảnh báo đang bật
#   ./monitoring.sh down     dừng, giữ dữ liệu
#   ./monitoring.sh clean    dừng, xóa dữ liệu và out/
#
# Biến môi trường:
#   WERWA_ALERT_WEBHOOK   URL nhận cảnh báo (định dạng webhook của Alertmanager), ví dụ endpoint của
#                         backend để đưa lên màn hình vận hành. Không đặt thì chỉ xem trên Alertmanager.
#   PROMETHEUS_IMAGE, ALERTMANAGER_IMAGE   mặc định prom/prometheus:v3.15.0, prom/alertmanager:v0.34.1
#
# Prometheus: http://127.0.0.1:9090 · Alertmanager: http://127.0.0.1:9093

set -euo pipefail
MON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$MON_DIR/../devnet/lib.sh"

OUT="$MON_DIR/out"
PROM=http://127.0.0.1:9090
AM=http://127.0.0.1:9093

mc() { docker compose -p werwa-monitoring -f "$MON_DIR/compose.yaml" "$@"; }

render_alertmanager() {
  local hooks=""
  mkdir -p "$OUT"
  if [ -n "${WERWA_ALERT_WEBHOOK:-}" ]; then
    hooks=$(printf '    webhook_configs:\n      - url: "%s"\n        send_resolved: true' "$WERWA_ALERT_WEBHOOK")
  fi
  awk -v hooks="$hooks" '{ if ($0 == "__WEBHOOK_CONFIGS__") { if (hooks != "") print hooks } else print }' \
    "$MON_DIR/alertmanager.template.yml" >"$OUT/alertmanager.yml"
}

wait_http() { local t=0; until curl -sf "$1" >/dev/null 2>&1; do [ "$t" -ge "${2:-60}" ] && return 1; sleep 2; t=$((t + 2)); done; }

cmd_up() {
  need docker; need curl; need jq
  docker network inspect werwa-devnet-p2p >/dev/null 2>&1 || die "chưa có mạng werwa-devnet-p2p; chạy devnet/devnet.sh up trước"
  render_alertmanager
  ensure_app_network
  mc up -d
  wait_http "$PROM/-/ready" 90 || die "Prometheus không sẵn sàng"
  wait_http "$AM/-/ready" 90 || die "Alertmanager không sẵn sàng"
  log "giám sát chạy: Prometheus $PROM, Alertmanager $AM${WERWA_ALERT_WEBHOOK:+, webhook $WERWA_ALERT_WEBHOOK}"
}

cmd_status() {
  curl -s "$PROM/api/v1/targets" | jq -r '.data.activeTargets[] | "\(.labels.node)\t\(.labels.role)\t\(.health)"'
  echo "cảnh báo đang bật:"
  curl -s "$AM/api/v2/alerts?active=true" | jq -r '.[] | "  \(.labels.alertname)\t\(.labels.node // "-")\t\(.labels.severity)"'
}

case "${1:-}" in
  up) cmd_up ;;
  status) cmd_status ;;
  down) mc down ;;
  clean) mc down -v 2>/dev/null || true; rm -rf "$OUT"; log "đã xóa dữ liệu giám sát" ;;
  *) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
