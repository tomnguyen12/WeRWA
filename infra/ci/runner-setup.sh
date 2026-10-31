#!/usr/bin/env bash
# Dựng runner GitHub Actions tự host cho WeRWA trên Ubuntu 24.04 (phương án A, Tommy duyệt 31/10/2026).
# Chạy bằng root trên VM CI. Token đăng ký lấy ở GitHub: Settings → Actions → Runners → New self-hosted runner;
# token chỉ sống 1 giờ, truyền qua biến môi trường, không ghi vào tệp, không commit.
#
#   sudo RUNNER_TOKEN=xxxx ./runner-setup.sh
#
# Biến tùy chọn: REPO_URL (mặc định https://github.com/tomnguyen12/WeRWA), RUNNER_NAME (mặc định hostname),
# RUNNER_LABELS (mặc định werwa-ci), RUNNER_USER (mặc định runner).
set -euo pipefail

: "${RUNNER_TOKEN:?Cần RUNNER_TOKEN (token đăng ký runner, sống 1 giờ)}"
REPO_URL=${REPO_URL:-https://github.com/tomnguyen12/WeRWA}
RUNNER_NAME=${RUNNER_NAME:-$(hostname)}
RUNNER_LABELS=${RUNNER_LABELS:-werwa-ci}
RUNNER_USER=${RUNNER_USER:-runner}
RUNNER_DIR=/opt/actions-runner
FOUNDRY_VERSION=v1.8.5

[ "$(id -u)" -eq 0 ] || { echo "Chạy bằng root (sudo)" >&2; exit 1; }

echo "== Gói hệ thống: Docker, JDK 21, Maven, jq, curl, openssl, git"
apt-get update -y
apt-get install -y ca-certificates curl gnupg jq openssl git unzip maven openjdk-21-jdk-headless
if ! command -v docker >/dev/null; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
fi
systemctl enable --now docker

echo "== Người dùng $RUNNER_USER (nhóm docker để Testcontainers dùng được Docker)"
id "$RUNNER_USER" >/dev/null 2>&1 || useradd -m -s /bin/bash "$RUNNER_USER"
usermod -aG docker "$RUNNER_USER"

echo "== Foundry $FOUNDRY_VERSION cho $RUNNER_USER (workflow cũng tự cài qua foundry-toolchain)"
sudo -u "$RUNNER_USER" bash -c "curl -fsSL https://foundry.paradigm.xyz | bash && ~/.foundry/bin/foundryup -i $FOUNDRY_VERSION"

echo "== Runner GitHub Actions bản mới nhất"
version=$(curl -fsSL https://api.github.com/repos/actions/runner/releases/latest | jq -r .tag_name | sed 's/^v//')
arch=$(dpkg --print-architecture); [ "$arch" = amd64 ] && arch=x64
mkdir -p "$RUNNER_DIR"
cd "$RUNNER_DIR"
if [ ! -f .runner ]; then
  curl -fsSL -o runner.tgz "https://github.com/actions/runner/releases/download/v${version}/actions-runner-linux-${arch}-${version}.tar.gz"
  tar xzf runner.tgz && rm runner.tgz
  chown -R "$RUNNER_USER" "$RUNNER_DIR"
  sudo -u "$RUNNER_USER" ./config.sh --unattended --url "$REPO_URL" --token "$RUNNER_TOKEN" \
    --name "$RUNNER_NAME" --labels "$RUNNER_LABELS" --work _work --replace
  ./svc.sh install "$RUNNER_USER"
fi
./svc.sh start
./svc.sh status | head -5

cat <<MSG

Xong. Bước cuối trên GitHub: Settings → Secrets and variables → Actions → Variables, tạo biến kho
  WERWA_BACKEND_RUNNER = ["self-hosted","linux","$RUNNER_LABELS"]
Bỏ biến này thì workflow backend quay về máy GitHub (ubuntu-latest).
MSG
