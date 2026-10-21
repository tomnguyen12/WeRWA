#!/usr/bin/env bash
# Đăng nhập một người dùng thử vào realm werwa qua đúng browser flow (code + PKCE S256, mật khẩu, OTP)
# và in phản hồi token (JSON) ra stdout. Mật khẩu và khóa OTP lấy từ out/users.json.
#
#   ./login.sh <username>              ví dụ ./login.sh holco-maker
#   ./login.sh <username> --claims     in claim của access token thay cho phản hồi token
#   ./login.sh --list                  liệt kê người dùng thử: username, org_id, vai
#
# Cần: curl, jq, openssl, python3. Chạy sau ./keycloak.sh up.

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
need curl; need jq; need openssl; need python3
[ -f "$USERS_FILE" ] || die "chưa có $USERS_FILE; chạy ./keycloak.sh up"
trap '[ -n "$LOGIN_DIR" ] && rm -rf "$LOGIN_DIR"' EXIT

case "${1:-}" in
  ""|-h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
  --list) jq -r '.[] | [.username, .orgId, (.roles | join(","))] | @tsv' "$USERS_FILE"; exit 0 ;;
esac
user_field "$1" username >/dev/null || die "không có người dùng thử $1 (xem ./login.sh --list)"
token=$(login_user "$1")
if [ "${2:-}" = --claims ]; then jwt_claims "$(jq -r .access_token <<<"$token")"; else printf '%s\n' "$token"; fi
