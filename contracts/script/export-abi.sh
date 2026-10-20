#!/usr/bin/env bash
# Xuất ABI các hợp đồng trong script/abi-exports.txt vào api/abi/ và ghi manifest.json.
# Dùng: contracts/script/export-abi.sh            (ghi tệp)
#       contracts/script/export-abi.sh --check    (CI: báo lỗi nếu api/abi/ lệch với bản build)
set -euo pipefail

cd "$(dirname "$0")/.."
OUT_DIR="../api/abi"
LIST="script/abi-exports.txt"
MODE="${1:-write}"

forge build --skip test --skip script >/dev/null

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

entries=()
while IFS= read -r line; do
  line="${line%%#*}"
  line="$(echo "$line" | xargs)"
  [[ -z "$line" ]] && continue
  src="${line%%:*}"
  name="${line##*:}"
  artifact="out/$(basename "$src")/${name}.json"
  if [[ ! -f "$artifact" ]]; then
    echo "Không thấy artifact $artifact (kiểm lại $LIST)" >&2
    exit 1
  fi
  jq '.abi' "$artifact" > "$TMP/${name}.json"
  entries+=("$(jq -n --arg n "$name" --arg s "contracts/$src" '{name:$n, source:$s}')")
done < "$LIST"

solc="$(jq -r '.compiler.version // empty' "out/Token.sol/Token.json" 2>/dev/null || true)"
[[ -z "$solc" ]] && solc="$(grep -E '^solc_version' foundry.toml | cut -d'"' -f2)"

printf '%s\n' "${entries[@]}" | jq -s \
  --arg solc "$solc" \
  --arg trex "$(jq -r .version lib/trex/package.json)" \
  --arg oid "$(jq -r .version lib/onchain-id/package.json)" \
  '{generatedBy:"contracts/script/export-abi.sh", solc:$solc, trex:$trex, onchainId:$oid, contracts:.}' \
  > "$TMP/manifest.json"

if [[ "$MODE" == "--check" ]]; then
  status=0
  for f in "$TMP"/*.json; do
    target="$OUT_DIR/$(basename "$f")"
    if ! diff -q "$f" "$target" >/dev/null 2>&1; then
      echo "Lệch hoặc thiếu: api/abi/$(basename "$f")" >&2
      status=1
    fi
  done
  for f in "$OUT_DIR"/*.json; do
    [[ -e "$f" ]] || continue
    if [[ ! -f "$TMP/$(basename "$f")" ]]; then
      echo "Thừa (không có trong $LIST): api/abi/$(basename "$f")" >&2
      status=1
    fi
  done
  [[ $status -eq 0 ]] && echo "api/abi/ khớp bản build"
  exit $status
fi

find "$OUT_DIR" -maxdepth 1 -name '*.json' -delete
cp "$TMP"/*.json "$OUT_DIR"/
echo "Đã xuất $(( ${#entries[@]} )) ABI vào api/abi/"
