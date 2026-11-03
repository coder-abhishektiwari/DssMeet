#!/usr/bin/env bash
# PrisonConnect VPS bootstrap — Ubuntu 24.04, run as root.
# Installs Docker, log rotation, ufw, fail2ban, sysctl hardening.
# Idempotent: safe to re-run.
set -euo pipefail

echo "[1/6] base packages..."
apt-get update -y
apt-get install -y ca-certificates curl git ufw fail2ban

echo "[2/6] Docker Engine + Compose plugin..."
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
fi
systemctl enable --now docker

echo "[3/6] Docker log rotation (no disk-fill from container logs)..."
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" },
  "live-restore": true
}
EOF
systemctl restart docker

echo "[4/6] ufw firewall..."
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp    # SSH — restrict to your IP later (see RUNBOOK step 2.4)
ufw allow 80/tcp    # Caddy / Let's Encrypt
ufw allow 443/tcp   # Caddy HTTPS
ufw allow 443/udp   # HTTP/3 (optional)
ufw allow 3478/tcp  # TURN
ufw allow 3478/udp  # TURN
ufw allow 41000:41040/udp  # TURN relay range
ufw --force enable

echo "[5/6] fail2ban (SSH brute-force protection)..."
systemctl enable --now fail2ban

echo "[6/6] kernel hardening..."
cat > /etc/sysctl.d/99-prisonconnect.conf <<'EOF'
net.ipv4.tcp_syncookies = 1
net.ipv4.icmp_echo_ignore_broadcasts = 1
kernel.kptr_restrict = 1
dmesg_restrict = 1
EOF
sysctl --system >/dev/null

echo ""
echo "DONE. Next: fill .env and start the stack (deploy/RUNBOOK.md steps 3-4)."
echo "Reminder: confirm SSH key auth works BEFORE disabling password login."
