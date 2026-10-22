#!/usr/bin/env bash
# ADR-0019, bước chung: mã WeRWA trong src/ chỉ dùng giao diện ERC-3643 khai báo lại (src/erc3643/, CC0).
# Chỉ src/adapters/trex/ (bộ tạo token của phương án A) được import mã T-REX hoặc ONCHAINID.
set -euo pipefail
cd "$(dirname "$0")/.."
bad=$(grep -rlE '"@trex/|"@onchain-id/' src --include='*.sol' | grep -v '^src/adapters/trex/' || true)
if [[ -n "$bad" ]]; then
  echo "Tệp ngoài src/adapters/trex/ import mã T-REX/ONCHAINID (vi phạm ADR-0019):" >&2
  echo "$bad" >&2
  exit 1
fi
echo "Ranh giới ADR-0019 giữ nguyên: chỉ src/adapters/trex/ dùng mã T-REX/ONCHAINID"
