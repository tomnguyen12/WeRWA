#!/usr/bin/env bash
# Kịch bản CI backend dùng chung: GitHub Actions (máy GitHub hoặc runner tự host) và chạy tay trên máy có đủ công cụ.
# Chạy từ thư mục backend/. Kết quả ghi vào build.log; mã thoát khác 0 khi build hoặc kiểm thử lỗi.
set -euo pipefail
cd "$(dirname "$0")/.."

need=(java mvn docker forge cast jq curl openssl)
missing=()
for c in "${need[@]}"; do command -v "$c" >/dev/null 2>&1 || missing+=("$c"); done
if [ ${#missing[@]} -gt 0 ]; then
  echo "Thiếu công cụ: ${missing[*]} (xem infra/ci/README.md)" >&2
  exit 2
fi
java -version 2>&1 | head -1
mvn -v | head -1
docker info --format 'Docker {{.ServerVersion}}, {{.NCPU}} CPU, {{.MemTotal}} byte RAM'
forge --version | head -1

# Kiểm thử chuỗi dùng submodule của contracts/ (T-REX, ONCHAINID, Safe)
git -C .. submodule update --init --recursive

set -o pipefail
mvn -B -ntp verify 2>&1 | tee build.log
# Giả lập BIDV (Q55), mô-đun Maven riêng
mvn -B -ntp -f bidv-simulator/pom.xml verify 2>&1 | tee -a build.log
