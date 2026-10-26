#!/usr/bin/env bash
# Phía client (WeHSM) của kênh NTLS giả lập Luna ở XD: tạo chứng chỉ client và gói client gắn vào
# /opt/hsm-client của container WeHSM.
#
# Chép từ tomnguyen12/WeHSM, scripts/hsm-client.sh (lệnh create-cert và package) ở commit 0b058a8, vì CI WeRWA
# không tải được repo WeHSM riêng tư. WeHSM đổi định dạng gói thì sửa theo; với Luna thật không dùng tệp này
# (thư viện Luna client tự lo NTLS).
#
#   hsm-client.sh create-cert <thư-mục> <CN>   sinh client.pem + client.key (như `vtl createCert`); không ghi đè
#   hsm-client.sh package <thư-mục> [điểm gắn] gói client, mặc định gắn vào /opt/hsm-client
#
# package cần: WEHSM_HSM_ADDRESS (host:port nhìn từ container WeHSM), WEHSM_HSM_SERVER_CERT, WEHSM_HSM_CLIENT_CERT,
#              WEHSM_HSM_CLIENT_KEY.

set -euo pipefail
die() { printf 'hsm-client: %s\n' "$*" >&2; exit 1; }

cmd_create_cert() {
  local dir="${1:-}" cn="${2:-}"
  [ -n "$dir" ] && [ -n "$cn" ] || die "dùng: hsm-client.sh create-cert <thư-mục> <CN>"
  umask 077
  mkdir -p "$dir"
  [ -f "$dir/client.pem" ] && die "$dir/client.pem đã có; không ghi đè"
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 825 \
    -subj "/CN=$cn" -keyout "$dir/client.key" -out "$dir/client.pem" 2>/dev/null
  chmod 600 "$dir/client.key"
  echo "$dir/client.pem"
}

cmd_package() {
  local dir="${1:-}" mount="${2:-/opt/hsm-client}"
  [ -n "$dir" ] || die "dùng: hsm-client.sh package <thư-mục> [điểm gắn, mặc định /opt/hsm-client]"
  [ -n "${WEHSM_HSM_ADDRESS:-}" ] || die "thiếu WEHSM_HSM_ADDRESS (host:port, nhìn từ container WeHSM)"
  [ -n "${WEHSM_HSM_SERVER_CERT:-}" ] || die "thiếu WEHSM_HSM_SERVER_CERT"
  [ -n "${WEHSM_HSM_CLIENT_CERT:-}" ] || die "thiếu WEHSM_HSM_CLIENT_CERT"
  [ -n "${WEHSM_HSM_CLIENT_KEY:-}" ] || die "thiếu WEHSM_HSM_CLIENT_KEY"
  umask 077
  mkdir -p "$dir/cert" "$dir/pkcs11-modules"
  cp "$WEHSM_HSM_CLIENT_CERT" "$dir/cert/client.pem"
  cp "$WEHSM_HSM_CLIENT_KEY" "$dir/cert/client.key"
  cp "$WEHSM_HSM_SERVER_CERT" "$dir/cert/server.pem"
  # Không có accept: stunnel chạy chế độ inetd, trao đổi với p11-kit qua stdin/stdout.
  {
    echo "syslog = no"
    echo "debug = warning"
    echo "client = yes"
    echo "connect = $WEHSM_HSM_ADDRESS"
    echo "cert = $mount/cert/client.pem"
    echo "key = $mount/cert/client.key"
    echo "verifyPeer = yes"
    echo "CAfile = $mount/cert/server.pem"
    echo "sslVersionMin = TLSv1.2"
  } >"$dir/ntls.conf"
  echo "remote: |/usr/bin/stunnel $mount/ntls.conf" >"$dir/pkcs11-modules/wehsm-ntls.module"
  chmod 0644 "$dir/ntls.conf" "$dir/cert/client.pem" "$dir/cert/server.pem" "$dir/pkcs11-modules/wehsm-ntls.module"
  chmod 0755 "$dir" "$dir/cert" "$dir/pkcs11-modules"
  echo "$dir"
}

case "${1:-}" in
  create-cert) shift; cmd_create_cert "$@" ;;
  package) shift; cmd_package "$@" ;;
  *) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
