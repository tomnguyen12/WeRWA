#!/usr/bin/env bash
# US-6.04-AC1 — Cho Safe tổ chức và Safe liên bên theo ADR-05 · Khi tạo · Thì ngưỡng đúng bảng ADR-05.
# Chạy sau `infra/safe/safe.sh up`. Đọc trạng thái trên chuỗi qua O1 (ADR-12); bảng mong đợi viết
# thẳng trong script theo ADR-05, không lấy từ safes.json (tệp đó chỉ dùng để biết địa chỉ).
#
# Bảng ADR-05 → Safe:
#   3/3 UBND + VSDC + Holco   triển khai/hủy series, nâng cấp, mở lại sau tạm dừng   → governance3of3
#   2/2 VSDC + Holco          phong tỏa, khôi phục ví, tạm dừng                      → enforcement2of2
#   UBND · 1, VSDC · 1, BIDV · 1, Holco                                              → Safe tổ chức
# Ngưỡng Safe tổ chức do tổ chức tự đặt; devnet dùng 2/3 (duyệt 21/10/2026).

set -Eeuo pipefail   # -E: trap ERR cả trong hàm, để lỗi có annotation
INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$INFRA/devnet/lib.sh"

need cast; need jq
trap 'annotate "kiểm thử dừng ở dòng $LINENO: $BASH_COMMAND"' ERR

REF="$INFRA/safe/safe-1.4.1.json"
SAFES="$DEVNET_OUT/safe/safes.json"
[ -f "$SAFES" ] || die "chưa có $SAFES; chạy safe/safe.sh up"

ORG_THRESHOLD=2
ORG_SIGNERS=3
FALLBACK_SLOT=0x6c9a6c4a39284e37ed1cf53d337577d14212a4870fb976a4366c693b939918d5   # keccak256("fallback_manager.handler.address")
GUARD_SLOT=0x4a204f620c8c5ccdca3fd54d003badd85ba500436a431f0cbda4f558c93c34c8      # keccak256("guard_manager.guard.address")
SENTINEL=0x0000000000000000000000000000000000000001

lower() { tr 'A-F' 'a-f'; }
addr_of() { jq -r ".safes.$1.address" "$SAFES"; }
call() { cast call --rpc-url "$O1A_RPC" "$@"; }
slot_addr() { cast parse-bytes32-address "$(cast storage --rpc-url "$O1A_RPC" "$1" "$2")" | lower; }
owners_sorted() { call "$1" "getOwners()(address[])" | tr -d '[] ' | tr ',' '\n' | lower | sort; }
sorted() { printf '%s\n' "$@" | lower | sort; }

SAFE_L2=$(jq -r .contracts.SafeL2.address "$REF" | lower)
HANDLER=$(jq -r .contracts.CompatibilityFallbackHandler.address "$REF" | lower)

# Hợp đồng Safe 1.4.1 trên devnet đúng bản chính thức.
for name in $(jq -r '.contracts | keys[]' "$REF"); do
  a=$(jq -r ".contracts.$name.address" "$REF"); want=$(jq -r ".contracts.$name.codeHash" "$REF")
  have=$(cast keccak "$(cast code --rpc-url "$O1A_RPC" "$a")")
  if [ "$have" = "$want" ]; then pass "US-6.04-AC1 $name 1.4.1 tại $a đúng mã băm bản chính thức"
  else fail "US-6.04-AC1 $name tại $a: mã băm $have, mong đợi $want"; fi
done

# Cấu hình chung cho mọi Safe: SafeL2 1.4.1, fallback handler, không guard, không module.
check_common() {
  local name="$1" a="$2" v sing fh guard mods
  v=$(call "$a" "VERSION()(string)" | tr -d '"')
  sing=$(slot_addr "$a" 0)
  fh=$(slot_addr "$a" "$FALLBACK_SLOT")
  guard=$(slot_addr "$a" "$GUARD_SLOT")
  # sed đọc hết đầu ra; `head -1` đóng ống sớm, cast nhận SIGPIPE và pipefail làm hàm dừng không báo lỗi.
  mods=$(call "$a" "getModulesPaginated(address,uint256)(address[],address)" "$SENTINEL" 10 | sed -n 1p)
  if [ "$v" = 1.4.1 ] && [ "$sing" = "$SAFE_L2" ] && [ "$fh" = "$HANDLER" ] \
     && [ "$guard" = 0x0000000000000000000000000000000000000000 ] && [ "$mods" = "[]" ]; then
    pass "US-6.04-AC1 $name: SafeL2 1.4.1, fallback handler đúng, không guard, không module"
  else
    fail "US-6.04-AC1 $name: version=$v singleton=$sing handler=$fh guard=$guard modules=$mods"
  fi
}

# check_safe <tên> <ngưỡng mong đợi> <owner mong đợi...>
check_safe() {
  local name="$1" want_t="$2"; shift 2
  local a t
  a=$(addr_of "$name")
  [ "$a" != null ] && [ "$(cast code --rpc-url "$O1A_RPC" "$a")" != 0x ] || { fail "US-6.04-AC1 $name: không có Safe trên chuỗi"; return; }
  t=$(call "$a" "getThreshold()(uint256)")
  if [ "$t" = "$want_t" ] && [ "$(owners_sorted "$a")" = "$(sorted "$@")" ]; then
    pass "US-6.04-AC1 $name: ngưỡng $t/$#, đúng owner"
  else
    fail "US-6.04-AC1 $name: ngưỡng $t (mong đợi $want_t), owner [$(owners_sorted "$a" | xargs)] (mong đợi [$(sorted "$@" | xargs)])"
  fi
  check_common "$name" "$a"
}

UBND=$(addr_of ubnd); VSDC=$(addr_of vsdc); BIDV=$(addr_of bidv); HOLCO=$(addr_of holco)

# Safe tổ chức: owner là người ký (EOA, không có code), khác nhau, đúng khóa người ký của tổ chức.
for org in ubnd vsdc bidv holco; do
  signers=()
  for i in $(seq 1 "$ORG_SIGNERS"); do signers+=("$(cat "$DEVNET_OUT/safe/signers/$org-$i/address")"); done
  check_safe "$org" "$ORG_THRESHOLD" "${signers[@]}"
  eoa=1
  for s in "${signers[@]}"; do [ "$(cast code --rpc-url "$O1A_RPC" "$s")" = 0x ] || eoa=0; done
  if [ "$eoa" = 1 ] && [ "$(sorted "${signers[@]}" | uniq | wc -l)" = "$ORG_SIGNERS" ]; then
    pass "US-6.04-AC1 $org: $ORG_SIGNERS người ký là EOA riêng biệt"
  else fail "US-6.04-AC1 $org: người ký không phải $ORG_SIGNERS EOA riêng biệt"; fi
done

# Safe liên bên: owner là Safe tổ chức (ADR-05, đặc tả hợp đồng: "multisig liên bên có thành viên là các Safe của tổ chức").
check_safe governance3of3 3 "$UBND" "$VSDC" "$HOLCO"
check_safe enforcement2of2 2 "$VSDC" "$HOLCO"

# Holco không tự quyết trên Safe liên bên nào; BIDV không là thành viên Safe liên bên nào.
for name in governance3of3 enforcement2of2; do
  a=$(addr_of "$name")
  n=$(owners_sorted "$a" | wc -l | tr -d ' '); t=$(call "$a" "getThreshold()(uint256)")
  if [ "$t" = "$n" ] && ! owners_sorted "$a" | grep -qx "$(lower <<<"$BIDV")"; then
    pass "US-6.04-AC1 $name: ngưỡng bằng số thành viên ($t/$n), không có BIDV"
  else fail "US-6.04-AC1 $name: ngưỡng $t/$n hoặc có BIDV"; fi
done

[ "$FAILED" = 0 ] || die "US-6.04-AC1 trượt"
log "US-6.04-AC1: đạt"
