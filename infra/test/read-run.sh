#!/usr/bin/env bash
# Kiểm infra/platform/read-run.sh (đọc kết quả DeployPlatform.run() theo tên trường) bằng ABI và bản ghi mẫu,
# không cần chuỗi. Cần: jq.
#
#   R1  ABI và bản ghi khớp: đủ 12 trường, đúng địa chỉ từng trường
#   R2  contracts thêm một trường (ví dụ tokenDeployer): vẫn chạy, trường mới có trong kết quả
#   R3  contracts đổi thứ tự trường: tên vẫn ghép đúng địa chỉ
#   R4  thiếu một trường infra cần: dừng, báo đúng tên trường
#   R5  ABI cũ (số trường khác số giá trị trong bản ghi): dừng
#   R6  ABI không có run() trả về struct: dừng
#   R7  ABI thật của contracts/ (nếu đã build): run() trả về struct có đủ trường infra cần

set -euo pipefail
INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$INFRA/devnet/lib.sh"
need jq
trap 'annotate "kiểm thử dừng ở dòng $LINENO: $BASH_COMMAND"' ERR

READ="$INFRA/platform/read-run.sh"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
check() { local name="$1" got="$2" want="$3"; [ "$got" = "$want" ] && pass "$name" || fail "$name: $got, mong đợi $want"; }

BASE=(timelock implementationAuthority identityRegistry identityRegistryStorage claimTopicsRegistry
  trustedIssuersRegistry holcoClaimIssuer controllerBeacon moduleDeployer proxyAdmin seriesFactory auditAnchor)
addr() { printf '0x%040x' "$1"; }

# mk <tên tệp> <trường...>: ABI có run() trả về struct gồm các trường; bản ghi có giá trị addr(i+1) cho trường i.
mk() {
  local name="$1"; shift
  jq -n --args '{abi: [{type: "function", name: "run", inputs: [],
      outputs: [{name: "d", type: "tuple", internalType: "struct DeployPlatform.Deployed",
                 components: ($ARGS.positional | map({name: ., type: "address", internalType: "address"}))}]}]}' \
    "$@" >"$W/$name.abi.json"
  local vals=() i=1
  for _ in "$@"; do vals+=("$(addr $i)"); i=$((i + 1)); done
  jq -n --arg v "($(IFS=,; echo "${vals[*]}" | sed 's/,/, /g'))" \
    '{returns: {d: {internal_type: "struct DeployPlatform.Deployed", value: $v}}}' >"$W/$name.run.json"
}
# expect_map <out> <trường...>: mỗi trường thứ i phải mang addr(i+1).
expect_map() {
  local out="$1"; shift; local i=1 bad=""
  for f in "$@"; do [ "$(jq -r --arg f "$f" '.[$f]' <<<"$out")" = "$(addr $i)" ] || bad="$bad $f"; i=$((i + 1)); done
  echo "${bad:-ok}"
}

mk base "${BASE[@]}"
out=$("$READ" "$W/base.abi.json" "$W/base.run.json")
check "R1 đủ 12 trường" "$(jq length <<<"$out")" 12
check "R1 đúng địa chỉ từng trường" "$(expect_map "$out" "${BASE[@]}")" ok

EXTRA=("${BASE[@]:0:9}" tokenDeployer "${BASE[@]:9}")
mk extra "${EXTRA[@]}"
if out=$("$READ" "$W/extra.abi.json" "$W/extra.run.json"); then
  check "R2 thêm trường tokenDeployer: 13 trường" "$(jq length <<<"$out")" 13
  check "R2 thêm trường: mọi trường đúng địa chỉ" "$(expect_map "$out" "${EXTRA[@]}")" ok
else fail "R2 thêm trường: read-run dừng"; fi

REORDER=(auditAnchor seriesFactory "${BASE[@]:0:10}")
mk reorder "${REORDER[@]}"
if out=$("$READ" "$W/reorder.abi.json" "$W/reorder.run.json"); then
  check "R3 đổi thứ tự: mọi trường đúng địa chỉ" "$(expect_map "$out" "${REORDER[@]}")" ok
else fail "R3 đổi thứ tự: read-run dừng"; fi

MISSING=("${BASE[@]:0:9}" "${BASE[@]:10}")   # bỏ proxyAdmin
mk missing "${MISSING[@]}"
if msg=$("$READ" "$W/missing.abi.json" "$W/missing.run.json" 2>&1 >/dev/null); then fail "R4 thiếu proxyAdmin mà vẫn chạy"
elif grep -q 'thiếu trường infra cần: proxyAdmin' <<<"$msg"; then pass "R4 thiếu trường: dừng, báo proxyAdmin"
else fail "R4 thiếu trường: thông báo sai: $msg"; fi

if msg=$("$READ" "$W/extra.abi.json" "$W/base.run.json" 2>&1 >/dev/null); then fail "R5 ABI 13 trường, bản ghi 12 giá trị mà vẫn chạy"
elif grep -q 'ABI có 13 trường nhưng bản ghi có 12 giá trị' <<<"$msg"; then pass "R5 ABI lệch bản ghi: dừng"
else fail "R5 ABI lệch bản ghi: thông báo sai: $msg"; fi

echo '{"abi": [{"type": "function", "name": "deploy", "inputs": [], "outputs": []}]}' >"$W/norun.abi.json"
if "$READ" "$W/norun.abi.json" "$W/base.run.json" >/dev/null 2>&1; then fail "R6 ABI không có run() mà vẫn chạy"
else pass "R6 ABI không có run(): dừng"; fi

REAL="$INFRA/../contracts/out/DeployPlatform.s.sol/DeployPlatform.json"
if [ -f "$REAL" ]; then
  mapfile -t real < <(jq -r '[.abi[] | select(.type == "function" and .name == "run")][0].outputs[0].components[].name' "$REAL")
  mk real "${real[@]}"
  if out=$("$READ" "$REAL" "$W/real.run.json"); then
    pass "R7 ABI thật của DeployPlatform: run() có đủ trường infra cần (${#real[@]} trường)"
  else fail "R7 ABI thật của DeployPlatform thiếu trường infra cần"; fi
else
  log "R7 bỏ qua: chưa build contracts/ (chạy sau forge build hoặc platform/deploy.sh)"
fi

[ "$FAILED" = 0 ] || die "kiểm read-run trượt"
log "read-run: tất cả đạt"
