#!/usr/bin/env bash
# US-6.01-AC3 (phần hạ tầng) — Cho chỉ còn 3 validator · Khi theo dõi · Thì có cảnh báo.
# Phần "trên màn hình vận hành" thuộc backend và frontend: hạ tầng giao cảnh báo qua Alertmanager
# (API và webhook WERWA_ALERT_WEBHOOK); kiểm thử này dừng ở Alertmanager.
# Chạy sau devnet/devnet.sh up và monitoring/monitoring.sh up. Cần: docker, curl, jq.
#
#   A1  đủ 4 validator và 2 O1: Prometheus thấy cả 6 node, không có cảnh báo mạng chuỗi
#   A2  tắt V3: trong 60 giây Alertmanager có WerwaValidatorDown (v3) và WerwaOnlyThreeValidators
#   A3  bật lại V3: trong 90 giây hai cảnh báo trên hết

set -euo pipefail
INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$INFRA/devnet/lib.sh"

need docker; need curl; need jq
trap 'annotate "kiểm thử dừng ở dòng $LINENO: $BASH_COMMAND"' ERR
trap 'dc start v3 >/dev/null 2>&1 || true' EXIT

PROM=http://127.0.0.1:9090
AM=http://127.0.0.1:9093

targets_up() { curl -s "$PROM/api/v1/targets" | jq '[.data.activeTargets[] | select(.labels.job == "besu" and .health == "up")] | length'; }
# active_alerts → danh sách "tên/node" của cảnh báo đang bật trên Alertmanager, mỗi dòng một mục.
active_alerts() { curl -s "$AM/api/v2/alerts?active=true&silenced=false&inhibited=false" | jq -r '.[] | select(.labels.alertname | startswith("Werwa")) | "\(.labels.alertname)/\(.labels.node // "-")"' | sort; }
has_alert() { active_alerts | grep -qx "$1"; }

wait_for() { # wait_for <mô tả> <timeout-giây> <lệnh...>
  local what="$1" timeout="$2" t=0; shift 2
  until "$@"; do
    [ "$t" -ge "$timeout" ] && return 1
    sleep 3; t=$((t + 3))
  done
  log "$what sau ${t}s"
}

all_targets_up() { [ "$(targets_up)" = 6 ]; }
no_chain_alerts() { [ -z "$(active_alerts)" ]; }
v3_alerts_fired() { has_alert "WerwaOnlyThreeValidators/-" && has_alert "WerwaValidatorDown/v3"; }
v3_alerts_cleared() { ! active_alerts | grep -qE '^(WerwaValidatorDown|WerwaOnlyThreeValidators)/'; }

# A1
wait_peers "$O1A_RPC" 5 180 || die "devnet chưa đủ node"
if wait_for "Prometheus thấy đủ 6 node" 90 all_targets_up && wait_for "không còn cảnh báo mạng chuỗi" 120 no_chain_alerts; then
  pass "US-6.01-AC3 A1 đủ 6 node, không có cảnh báo"
else
  fail "US-6.01-AC3 A1: $(targets_up)/6 node, cảnh báo: [$(active_alerts | xargs)]"
fi

# A2
dc stop v3 >/dev/null; log "đã tắt v3"
if wait_for "có cảnh báo" 60 v3_alerts_fired; then
  pass "US-6.01-AC3 A2 tắt V3: Alertmanager có WerwaValidatorDown (v3) và WerwaOnlyThreeValidators"
else
  fail "US-6.01-AC3 A2 tắt V3: sau 60 giây cảnh báo đang bật [$(active_alerts | xargs)]"
fi
if has_alert "WerwaConsensusLost/-"; then fail "US-6.01-AC3 A2: báo mất đồng thuận khi còn 3 validator"
else pass "US-6.01-AC3 A2 không báo nhầm mất đồng thuận"; fi

# A3
dc start v3 >/dev/null; log "đã bật v3"
if wait_for "hết cảnh báo" 90 v3_alerts_cleared; then
  pass "US-6.01-AC3 A3 bật lại V3: cảnh báo hết"
else
  fail "US-6.01-AC3 A3 bật lại V3: sau 90 giây vẫn còn [$(active_alerts | xargs)]"
fi

[ "$FAILED" = 0 ] || die "US-6.01-AC3 (phần hạ tầng) trượt"
log "US-6.01-AC3 (phần hạ tầng): đạt"
