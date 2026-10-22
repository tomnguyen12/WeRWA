#!/usr/bin/env bash
# Kiểm trạng thái trên chuỗi sau khi chạy infra/platform/deploy.sh (DeployPlatform lên devnet).
# Mong đợi lấy từ ADR-05, đặc tả hợp đồng (docs/design/04-hop-dong-thong-minh.md) và C3, C13, C15
# trong docs/open-questions.md. Đọc qua O1 (ADR-12).
#
#   D1  mọi hợp đồng dùng chung giao quyền sở hữu cho timelock; khóa triển khai không giữ gì
#   D2  timelock: chỉ Safe 3/3 lên lịch; Safe UBND, VSDC, Holco hủy lịch; ai cũng thực thi; chờ 1 giờ
#   D3  SeriesFactory sau Transparent proxy do ProxyAdmin (của timelock) quản lý; GOVERNANCE là Safe 3/3
#   D4  bên cấp claim Holco: khóa quản lý là Safe Holco, khóa ký claim là khóa ký của Holco; được tin cậy cho 2 chủ đề
#   D5  sổ định danh: agent là khóa vận hành Holco; sổ lưu trữ gắn với sổ định danh
#   D6  AuditAnchor: OPERATOR là khóa vận hành Holco, GOVERNANCE là Safe 3/3

set -euo pipefail
INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$INFRA/devnet/lib.sh"

need cast; need jq
trap 'annotate "kiểm thử dừng ở dòng $LINENO: $BASH_COMMAND"' ERR

P="$DEVNET_OUT/platform/devnet.json"
S="$DEVNET_OUT/safe/safes.json"
[ -f "$P" ] || die "chưa có $P; chạy platform/deploy.sh"

lower() { tr 'A-F' 'a-f'; }
c() { jq -r ".contracts.$1" "$P" | lower; }
safe() { jq -r ".safes.$1.address" "$S" | lower; }
call() { cast call --rpc-url "$O1A_RPC" "$@"; }
check() { local name="$1" got="$2" want="$3"; [ "$(lower <<<"$got")" = "$(lower <<<"$want")" ] && pass "$name" || fail "$name: $got, mong đợi $want"; }

TL=$(c timelock); DEPLOYER=$(jq -r .deployer "$P" | lower)
OPERATOR=$(jq -r .config.holcoOperator "$P" | lower); CLAIM_SIGNER=$(jq -r .config.holcoClaimSigner "$P" | lower)
GOV=$(safe governance3of3); UBND=$(safe ubnd); VSDC=$(safe vsdc); HOLCO=$(safe holco); BIDV=$(safe bidv)
TOPIC_KYC=$(cast to-dec "$(cast keccak WERWA.CLAIM.KYC_INSTITUTION)")
TOPIC_CLASS=$(cast to-dec "$(cast keccak WERWA.CLAIM.INVESTOR_CLASS)")

# D1
for k in implementationAuthority claimTopicsRegistry trustedIssuersRegistry identityRegistryStorage identityRegistry controllerBeacon proxyAdmin; do
  check "D1 $k thuộc timelock" "$(call "$(c "$k")" 'owner()(address)')" "$TL"
done

# D2
role() { cast keccak "$1"; }
has() { call "$TL" 'hasRole(bytes32,address)(bool)' "$(role "$1")" "$2"; }
check "D2 Safe 3/3 lên lịch được" "$(has PROPOSER_ROLE "$GOV")" true
check "D2 khóa triển khai không lên lịch được" "$(has PROPOSER_ROLE "$DEPLOYER")" false
for o in UBND VSDC HOLCO; do check "D2 Safe $o hủy lịch được" "$(has CANCELLER_ROLE "${!o}")" true; done
check "D2 Safe BIDV không hủy lịch" "$(has CANCELLER_ROLE "$BIDV")" false
check "D2 ai cũng thực thi được sau thời gian chờ (C15)" "$(has EXECUTOR_ROLE 0x0000000000000000000000000000000000000000)" true
check "D2 khóa triển khai không là quản trị timelock" "$(has TIMELOCK_ADMIN_ROLE "$DEPLOYER")" false
check "D2 thời gian chờ" "$(call --json "$TL" 'getMinDelay()(uint256)' | jq -r '.[0]')" "$(jq -r .config.timelockDelay "$P")"

# D3
F=$(c seriesFactory)
ADMIN_SLOT=0xb53127684a568b3173ae13b9f8a6016e243e63b6e8ee1178d6a717850b5d6103
check "D3 admin của proxy SeriesFactory là ProxyAdmin" "$(cast parse-bytes32-address "$(cast storage --rpc-url "$O1A_RPC" "$F" $ADMIN_SLOT)")" "$(c proxyAdmin)"
check "D3 SeriesFactory.governance là Safe 3/3" "$(call "$F" 'governance()(address)')" "$GOV"
check "D3 SeriesFactory.timelock" "$(call "$F" 'timelock()(address)')" "$TL"
check "D3 SeriesFactory.identityRegistry" "$(call "$F" 'identityRegistry()(address)')" "$(c identityRegistry)"
check "D3 SeriesFactory.controllerBeacon" "$(call "$F" 'controllerBeacon()(address)')" "$(c controllerBeacon)"
check "D3 chưa có series" "$(call "$F" 'seriesCount()(uint256)')" 0

# D4
CI=$(c holcoClaimIssuer)
key_purpose() { call "$CI" 'keyHasPurpose(bytes32,uint256)(bool)' "$(cast keccak "$(cast abi-encode 'f(address)' "$1")")" "$2"; }
check "D4 Safe Holco là khóa quản lý của bên cấp claim" "$(key_purpose "$HOLCO" 1)" true
check "D4 khóa ký claim Holco có quyền ký claim" "$(key_purpose "$CLAIM_SIGNER" 3)" true
check "D4 khóa triển khai đã bị gỡ khỏi bên cấp claim" "$(key_purpose "$DEPLOYER" 1)" false
check "D4 bên cấp claim Holco được tin cậy" "$(call "$(c trustedIssuersRegistry)" 'isTrustedIssuer(address)(bool)' "$CI")" true
uints() { call --json "$@" | jq -r '.[0] | join(",")'; }
topics=$(uints "$(c trustedIssuersRegistry)" 'getTrustedIssuerClaimTopics(address)(uint256[])' "$CI")
check "D4 chủ đề claim của bên cấp Holco" "$topics" "$TOPIC_KYC,$TOPIC_CLASS"
ctr=$(uints "$(c claimTopicsRegistry)" 'getClaimTopics()(uint256[])')
check "D4 chủ đề claim bắt buộc KYC_INSTITUTION, INVESTOR_CLASS" "$ctr" "$TOPIC_KYC,$TOPIC_CLASS"

# D5
IR=$(c identityRegistry)
check "D5 khóa vận hành Holco là agent sổ định danh" "$(call "$IR" 'isAgent(address)(bool)' "$OPERATOR")" true
check "D5 khóa triển khai không là agent" "$(call "$IR" 'isAgent(address)(bool)' "$DEPLOYER")" false
linked=$(call "$(c identityRegistryStorage)" 'linkedIdentityRegistries()(address[])' | tr -d '[] ')
check "D5 sổ lưu trữ gắn với sổ định danh" "$linked" "$IR"

# D6
A=$(c auditAnchor)
check "D6 AuditAnchor.operator là khóa vận hành Holco" "$(call "$A" 'operator()(address)')" "$OPERATOR"
check "D6 AuditAnchor.governance là Safe 3/3" "$(call "$A" 'governance()(address)')" "$GOV"

[ "$FAILED" = 0 ] || die "kiểm sau triển khai trượt"
log "kiểm sau triển khai DeployPlatform: tất cả đạt"
