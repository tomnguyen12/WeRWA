#!/usr/bin/env bash
# Triển khai nền tảng qua WeHSM và vận hành (S7b). Chạy sau `signer/signer.sh deploy-platform`
# (WeHSM đang ở chế độ operate). Cần: docker, curl, jq, cast.
#
#   E1  devnet.json ở chế độ pkcs11; deployer, holcoOperator, holcoClaimSigner trùng keys.json (khóa trong HSM)
#   E2  US-6.04-AC4: holcoOperator ký qua WeHSM → PKCS#11 → node HSM; AuditAnchor.anchor lên khối, người gửi đúng
#       holcoOperator, sự kiện AuditAnchored đọc được qua O1
#   E3  US-6.04-AC4: khóa ký claim trong HSM ký được (personal_sign 32 byte), chữ ký phục hồi ra holcoClaimSigner,
#       đúng khóa ClaimIssuer của Holco tin cậy (mục đích 3)
#   E4  chế độ operate: deployer bị từ chối với KEY_DISABLED, không giao dịch nào tới V1
#   E5  chính sách: holcoOperator gọi hàm ngoài chính sách (AuditAnchor.setOperator) bị từ chối FUNCTION_NOT_ALLOWED
#   E6  nhật ký ký có policy_loaded của cả hai giai đoạn (deploy rồi operate) và vẫn toàn vẹn
#
# Phần "không có khóa ở dạng tệp trên máy ứng dụng" của US-6.04-AC4: H7 trong test/signer.sh.

set -euo pipefail
INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$INFRA/devnet/lib.sh"

need docker; need curl; need jq; need cast
trap 'annotate "kiểm thử dừng ở dòng $LINENO: $BASH_COMMAND"' ERR

SIGNER="$INFRA/signer"
KEYS="$DEVNET_OUT/keys.json"
P="$DEVNET_OUT/platform/devnet.json"
W=http://127.0.0.1:8745
[ -f "$P" ] || die "chưa có $P; chạy signer/signer.sh deploy-platform"
[ "$(cat "$SIGNER/out/mode")" = operate ] || die "WeHSM phải ở chế độ operate"
TOKEN=$(cat "$SIGNER/out/secrets/wehsm/token")

sc() { SIGNER_UID="$(id -u)" SIGNER_GID="$(id -g)" docker compose -p werwa-signer -f "$SIGNER/compose.yaml" "$@"; }
lower() { tr 'A-F' 'a-f'; }
wrpc() { curl -s -u "wehsm:$TOKEN" -H 'content-type: application/json' "$W" \
  --data "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$1\",\"params\":$2}"; }
check() { local name="$1" got="$2" want="$3"; [ "$got" = "$want" ] && pass "$name" || fail "$name: $got, mong đợi $want"; }
receipt_of() { # receipt_of <tx>: biên nhận đọc qua O1, chờ tối đa 20 giây
  local r
  for _ in $(seq 1 20); do
    r=$(rpc "$O1A_RPC" eth_getTransactionReceipt "[\"$1\"]" 2>/dev/null || true)
    [ -n "$r" ] && [ "$r" != null ] && { echo "$r"; return 0; }
    sleep 1
  done
  echo null
}

DEPLOYER=$(jq -r .deployer "$KEYS" | lower)
OPERATOR=$(jq -r .holcoOperator "$KEYS" | lower)
CLAIM=$(jq -r .holcoClaimSigner "$KEYS" | lower)
ANCHOR=$(jq -r .contracts.auditAnchor "$P" | lower)
ISSUER=$(jq -r .contracts.holcoClaimIssuer "$P" | lower)

# E1
got=$(jq -r '[.signingMode, .deployer, .config.holcoOperator, .config.holcoClaimSigner] | join(" ")' "$P" | lower)
check "E1 devnet.json: chế độ pkcs11, ba khóa trùng keys.json" "$got" "pkcs11 $DEPLOYER $OPERATOR $CLAIM"

# E2 — ngày nghiệp vụ kế tiếp để chạy lại được (AlreadyAnchored nếu trùng ngày).
latest=$(cast call --rpc-url "$O1A_RPC" "$ANCHOR" 'latestBusinessDate()(uint32)' | awk '{print $1}')
day=$(( latest > 0 ? latest + 1 : 20261024 ))
entry=$(cast keccak "werwa-infra-s7b-$day")
data=$(cast calldata 'anchor(uint32,uint64,bytes32)' "$day" 1 "$entry")
resp=$(wrpc eth_sendTransaction "[{\"from\":\"$OPERATOR\",\"to\":\"$ANCHOR\",\"data\":\"$data\"}]")
tx=$(jq -r '.result // empty' <<<"$resp")
if [[ "$tx" =~ ^0x[0-9a-f]{64}$ ]]; then
  r=$(receipt_of "$tx")
  status=$(jq -r '.status // empty' <<<"$r"); from=$(jq -r '.from // empty' <<<"$r" | lower)
  anchored=$(cast call --rpc-url "$O1A_RPC" "$ANCHOR" 'isAnchored(uint32)(bool)' "$day")
  if [ "$status" = 0x1 ] && [ "$from" = "$OPERATOR" ] && [ "$anchored" = true ]; then
    pass "US-6.04-AC4 E2 holcoOperator ký qua PKCS#11 tới node HSM: AuditAnchor.anchor($day) lên khối (tx $tx)"
  else fail "US-6.04-AC4 E2 tx $tx: status=$status from=$from isAnchored=$anchored"; fi
else fail "US-6.04-AC4 E2 WeHSM không ký anchor: $resp"; fi

# E3
msg=$(cast keccak "werwa-infra-s7b-claim")
resp=$(wrpc personal_sign "[\"$msg\",\"$CLAIM\"]")
sig=$(jq -r '.result // empty' <<<"$resp")
if [ -n "$sig" ] && cast wallet verify --address "$CLAIM" "$msg" "$sig" >/dev/null 2>&1; then
  pass "US-6.04-AC4 E3 khóa ký claim trong HSM ký được, chữ ký phục hồi ra holcoClaimSigner"
else fail "US-6.04-AC4 E3 personal_sign: $resp"; fi
check "US-6.04-AC4 E3 ClaimIssuer của Holco tin khóa ký claim trong HSM (mục đích 3)" \
  "$(cast call --rpc-url "$O1A_RPC" "$ISSUER" 'keyHasPurpose(bytes32,uint256)(bool)' "$(cast keccak "$(cast abi-encode 'f(address)' "$CLAIM")")" 3)" true

# E4
nonce_before=$(cast nonce --rpc-url "$O1A_RPC" "$DEPLOYER")
resp=$(wrpc eth_sendTransaction "[{\"from\":\"$DEPLOYER\",\"data\":\"0x600060005360016000f3\"}]")
sleep 4
check "E4 chế độ operate: deployer bị từ chối" "$(jq -r '.error.data.reason // empty' <<<"$resp")" KEY_DISABLED
check "E4 không giao dịch nào của deployer tới chuỗi" "$(cast nonce --rpc-url "$O1A_RPC" "$DEPLOYER")" "$nonce_before"

# E5
data=$(cast calldata 'setOperator(address)' "$OPERATOR")
resp=$(wrpc eth_sendTransaction "[{\"from\":\"$OPERATOR\",\"to\":\"$ANCHOR\",\"data\":\"$data\"}]")
check "E5 holcoOperator gọi hàm ngoài chính sách bị từ chối" "$(jq -r '.error.data.reason // empty' <<<"$resp")" FUNCTION_NOT_ALLOWED

# E6
phases=$(sc exec -T wehsm sh -c 'cat /var/lib/wehsm/audit.jsonl' | jq -rs '[.[] | select(.event == "policy_loaded") | .deployerEnabled] | map(tostring) | join(",")')
case "$phases" in
  *true,false*) pass "E6 nhật ký có policy_loaded của giai đoạn deploy rồi operate ($phases)" ;;
  *) fail "E6 nhật ký thiếu hai giai đoạn: [$phases]" ;;
esac
if sc exec -T wehsm wehsm audit verify /var/lib/wehsm/audit.jsonl >/dev/null 2>&1; then pass "E6 nhật ký ký vẫn toàn vẹn"
else fail "E6 audit verify lỗi"; fi

[ "$FAILED" = 0 ] || die "triển khai và vận hành qua WeHSM trượt"
log "triển khai và vận hành qua WeHSM: tất cả đạt"
