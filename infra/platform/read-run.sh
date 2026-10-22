#!/usr/bin/env bash
# Đọc kết quả `DeployPlatform.run()` theo TÊN trường, không theo vị trí.
#
#   read-run.sh <abi.json> <run-latest.json>   in object JSON {tênTrường: địa chỉ, ...} ra stdout
#
# Bản ghi broadcast của forge (`run-latest.json`) chỉ lưu giá trị của struct `Deployed` dạng tuple, không có tên.
# Tên lấy từ ABI do forge biên dịch (`contracts/out/DeployPlatform.s.sol/DeployPlatform.json`, phần output của
# `run()`), ghép với giá trị theo đúng thứ tự trong ABI. Nhờ vậy contracts thêm hoặc đổi thứ tự trường trong
# `Deployed` thì infra không hỏng; trường mới tự có trong devnet.json.
#
# Dừng (mã khác 0) khi: ABI không có `run()` trả về struct; số trường trong ABI khác số giá trị trong bản ghi
# (ABI cũ, chưa build lại); thiếu trường mà infra cần (REQUIRED); giá trị không phải địa chỉ.

set -euo pipefail

# Trường mà infra/platform và infra/test/deploy-platform.sh dùng. Trường khác trong ABI vẫn được ghi, không bắt buộc.
REQUIRED=(timelock implementationAuthority identityRegistry identityRegistryStorage claimTopicsRegistry
  trustedIssuersRegistry holcoClaimIssuer controllerBeacon moduleDeployer proxyAdmin seriesFactory auditAnchor)

err() { printf 'read-run: %s\n' "$*" >&2; exit 1; }
[ "$#" = 2 ] || err "cách dùng: read-run.sh <abi.json> <run-latest.json>"
abi="$1" run="$2"
[ -f "$abi" ] || err "không thấy ABI $abi"
[ -f "$run" ] || err "không thấy bản ghi $run"

names=$(jq -ce '[.abi[] | select(.type == "function" and .name == "run")][0].outputs[0].components
  | if type == "array" and length > 0 then map(.name) else error("không có struct") end' "$abi" 2>/dev/null) \
  || err "ABI $abi không có run() trả về struct"
values=$(jq -ce '.returns | to_entries[0].value.value | ltrimstr("(") | rtrimstr(")") | split(",") | map(gsub("\\s"; ""))' "$run" 2>/dev/null) \
  || err "bản ghi $run không có giá trị trả về của run()"

n_names=$(jq length <<<"$names"); n_values=$(jq length <<<"$values")
[ "$n_names" = "$n_values" ] \
  || err "ABI có $n_names trường nhưng bản ghi có $n_values giá trị; build lại contracts/ rồi chạy lại"

contracts=$(jq -cn --argjson k "$names" --argjson v "$values" '[range(0; $k | length) | {key: $k[.], value: $v[.]}] | from_entries')

bad=$(jq -r 'to_entries[] | select(.value | test("^0x[0-9a-fA-F]{40}$") | not) | .key' <<<"$contracts")
[ -z "$bad" ] || err "giá trị không phải địa chỉ ở trường: $(tr '\n' ' ' <<<"$bad")"

missing=()
for f in "${REQUIRED[@]}"; do jq -e --arg f "$f" 'has($f)' <<<"$contracts" >/dev/null || missing+=("$f"); done
[ "${#missing[@]}" = 0 ] || err "kết quả DeployPlatform thiếu trường infra cần: ${missing[*]}"

printf '%s\n' "$contracts"
