#!/usr/bin/env bash
# Kiểm WeHSM và node HSM trên devnet (infra/signer). Chạy sau `signer/signer.sh up --mode deploy`.
# Cần: docker, curl, jq, cast.
#
#   H1  keys.json có đúng ba địa chỉ khác nhau (deployer, holcoOperator, holcoClaimSigner)
#   H2  eth_accounts của WeHSM trả đúng ba địa chỉ đó
#   H3  gọi WeHSM không có token bị từ chối (401)
#   H4  Besu cấp phép gửi giao dịch cho deployer và holcoOperator, không cho holcoClaimSigner
#   H5  node HSM không mở cổng ra máy chủ; WeHSM chỉ mở 127.0.0.1
#   H6  ký qua HSM: deployer tạo một hợp đồng qua eth_sendTransaction; giao dịch lên khối, đọc qua O1,
#       người gửi đúng deployer
#   H7  không có tệp khóa ký trong container WeHSM, gói client chỉ có khóa mTLS
#   H8  cắt phiên trên node HSM: lệnh ký kế tiếp trả -32011, WeHSM thoát mã 70, được khởi động lại
#       và /health xanh trở lại
#   H9  nhật ký ký nối mã băm toàn vẹn (`wehsm audit verify`)

set -euo pipefail
INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$INFRA/devnet/lib.sh"

need docker; need curl; need jq; need cast
trap 'annotate "kiểm thử dừng ở dòng $LINENO: $BASH_COMMAND"' ERR

SIGNER="$INFRA/signer"
KEYS="$DEVNET_OUT/keys.json"
TOKEN_FILE="$SIGNER/out/secrets/wehsm/token"
W=http://127.0.0.1:8745
[ -f "$KEYS" ] && [ -f "$TOKEN_FILE" ] || die "chưa dựng signer; chạy signer/signer.sh up --mode deploy"
[ "$(cat "$SIGNER/out/mode")" = deploy ] || die "kiểm thử cần chế độ deploy (signer/signer.sh mode deploy)"
TOKEN=$(cat "$TOKEN_FILE")

sc() { SIGNER_UID="$(id -u)" SIGNER_GID="$(id -g)" docker compose -p werwa-signer -f "$SIGNER/compose.yaml" "$@"; }
lower() { tr 'A-F' 'a-f'; }
wrpc() { # wrpc <method> <params JSON>: phản hồi JSON-RPC đầy đủ của WeHSM
  curl -s -u "wehsm:$TOKEN" -H 'content-type: application/json' "$W" \
    --data "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$1\",\"params\":$2}"
}
check() { local name="$1" got="$2" want="$3"; [ "$got" = "$want" ] && pass "$name" || fail "$name: $got, mong đợi $want"; }

DEPLOYER=$(jq -r .deployer "$KEYS" | lower)
OPERATOR=$(jq -r .holcoOperator "$KEYS" | lower)
CLAIM=$(jq -r .holcoClaimSigner "$KEYS" | lower)

# H1
check "H1 keys.json có ba địa chỉ khác nhau" \
  "$(printf '%s\n' "$DEPLOYER" "$OPERATOR" "$CLAIM" | grep -E '^0x[0-9a-f]{40}$' | sort -u | wc -l | tr -d ' ')" 3

# H2
accounts=$(wrpc eth_accounts '[]' | jq -r '.result[]' | lower | sort | xargs)
check "H2 eth_accounts khớp keys.json" "$accounts" "$(printf '%s\n' "$DEPLOYER" "$OPERATOR" "$CLAIM" | sort | xargs)"

# H3
code=$(curl -s -o /dev/null -w '%{http_code}' -H 'content-type: application/json' "$W" \
  --data '{"jsonrpc":"2.0","id":1,"method":"eth_accounts","params":[]}')
check "H3 không có token thì 401" "$code" 401

# H4
perm="$DEVNET_OUT/shared/permissions_config.toml"
allowed() { grep -qi "\"$1\"" "$perm" && echo có || echo không; }
check "H4 Besu cấp phép deployer" "$(allowed "$DEPLOYER")" có
check "H4 Besu cấp phép holcoOperator" "$(allowed "$OPERATOR")" có
check "H4 holcoClaimSigner không gửi giao dịch nên không được cấp phép" "$(allowed "$CLAIM")" không

# H5
check "H5 node HSM không mở cổng ra máy chủ" "$(docker port "$(sc ps -q hsm-node)" | wc -l | tr -d ' ')" 0
check "H5 WeHSM chỉ mở 127.0.0.1:8745" "$(docker port "$(sc ps -q wehsm)" | sort -u | xargs)" "8545/tcp -> 127.0.0.1:8745"

# H6 — hợp đồng tối thiểu: mã khởi tạo ghi 1 byte 0x00 (STOP) vào bộ nhớ rồi trả về làm mã chạy.
INIT=0x600060005360016000f3
resp=$(wrpc eth_sendTransaction "[{\"from\":\"$DEPLOYER\",\"data\":\"$INIT\"}]")
tx=$(jq -r '.result // empty' <<<"$resp")
if [[ "$tx" =~ ^0x[0-9a-f]{64}$ ]]; then
  receipt=""
  for _ in $(seq 1 20); do
    receipt=$(rpc "$O1A_RPC" eth_getTransactionReceipt "[\"$tx\"]" 2>/dev/null || true)
    [ -n "$receipt" ] && [ "$receipt" != null ] && break
    sleep 1
  done
  from=$(jq -r '.from // empty' <<<"${receipt:-null}" | lower)
  status=$(jq -r '.status // empty' <<<"${receipt:-null}")
  created=$(jq -r '.contractAddress // empty' <<<"${receipt:-null}")
  if [ "$status" = 0x1 ] && [ "$from" = "$DEPLOYER" ] && [ "$(cast code --rpc-url "$O1A_RPC" "$created")" != 0x ]; then
    pass "H6 deployer ký qua HSM, giao dịch lên khối (tx $tx, hợp đồng $created)"
  else fail "H6 giao dịch $tx: status=$status from=$from contract=$created"; fi
else fail "H6 eth_sendTransaction không trả mã giao dịch: $resp"; fi

# H7 — tìm trong các thư mục ghi được hoặc được gắn vào; phần còn lại của ảnh đã được CI của WeHSM kiểm sạch.
privkeys=$(sc exec -T wehsm sh -c 'grep -rl "PRIVATE KEY" /opt/hsm-client /var/lib/wehsm /run/secrets /tmp 2>/dev/null || true' | sort | xargs)
check "H7 tệp khóa riêng duy nhất trong container WeHSM là khóa mTLS của gói client" "$privkeys" "/opt/hsm-client/cert/client.key"
if openssl pkey -in "$SIGNER/out/hsm-client/cert/client.key" -noout -text 2>/dev/null | grep -q 'prime256v1\|P-256'; then
  pass "H7 khóa trong gói client là khóa mTLS P-256, không phải khóa secp256k1 của ví"
else fail "H7 khóa trong gói client không phải P-256"; fi

# H8
cid=$(sc ps -q wehsm)
since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
restarts=$(docker inspect -f '{{.RestartCount}}' "$cid")
sc exec -T hsm-node hsm-node disrupt >/dev/null || fail "H8 hsm-node disrupt không cắt được phiên nào"
# WeHSM phát hiện mất phiên ở lần gọi HSM kế tiếp (như e2e của WeHSM): trả -32011 rồi thoát mã 70.
HASH=0x$(printf '%064x' 1)
resp=$(wrpc personal_sign "[\"$HASH\",\"$CLAIM\"]" || true)
hsm_err=$(jq -r ".error.code // empty" <<<"$resp" 2>/dev/null || true)
exit70=""; t=0
while [ "$t" -lt 60 ]; do
  exit70=$(docker events --since "$since" --until "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --filter "container=$cid" --filter event=die --format '{{index .Actor.Attributes "exitCode"}}' | head -1)
  [ -n "$exit70" ] && break
  sleep 2; t=$((t + 2))
done
healthy=0
for _ in $(seq 1 60); do curl -sf "$W/health" >/dev/null 2>&1 && { healthy=1; break; }; sleep 2; done
now_restarts=$(docker inspect -f '{{.RestartCount}}' "$cid")
if [ "$hsm_err" = -32011 ] && [ "$exit70" = 70 ] && [ "$now_restarts" -gt "$restarts" ] && [ "$healthy" = 1 ]; then
  pass "H8 mất phiên HSM: lệnh ký trả -32011, WeHSM thoát mã 70, tự khởi động lại ($restarts → $now_restarts), /health xanh trở lại"
else fail "H8 mất phiên HSM: lỗi ký '$hsm_err', mã thoát '$exit70', khởi động lại $restarts → $now_restarts, health=$healthy"; fi

# H9
verify=$(sc exec -T wehsm wehsm audit verify /var/lib/wehsm/audit.jsonl 2>&1) && rc=0 || rc=$?
if [ "$rc" = 0 ]; then pass "H9 nhật ký ký toàn vẹn: $(tail -1 <<<"$verify")"
else fail "H9 audit verify lỗi ($rc): $verify"; fi

[ "$FAILED" = 0 ] || die "kiểm WeHSM trượt"
log "WeHSM trên devnet: tất cả đạt"
