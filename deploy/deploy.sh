#!/usr/bin/env bash
# Production deploy with health-gated auto-rollback.
#
# Usage (run ON the VPS, called by .github/workflows/deploy.yml over SSH):
#   bash deploy/deploy.sh [REPO_DIR] [TARGET_SHA]
#
# Flow:
#   1. remember the currently-running commit (PREV_SHA)
#   2. fetch + reset to TARGET_SHA
#   3. docker compose up -d --build
#   4. poll backend + signaling health endpoints
#   5. healthy  -> DEPLOY_OK, exit 0
#      unhealthy -> git reset back to PREV_SHA, rebuild, re-check, exit 1
set -euo pipefail

REPO_DIR="${1:-/opt/DssMeet}"
TARGET_SHA="${2:-}"
COMPOSE_FILE="docker-compose.prod.yml"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-90}"

cd "$REPO_DIR"
PREV_SHA="$(git rev-parse HEAD)"

echo "[deploy] current: $PREV_SHA"
if [ -n "$TARGET_SHA" ]; then
  echo "[deploy] target:  $TARGET_SHA"
  git fetch --quiet origin main
  git checkout --quiet main
  git reset --hard "$TARGET_SHA"
else
  git pull --quiet --ff-only
fi
NEW_SHA="$(git rev-parse HEAD)"
echo "[deploy] checked out: $NEW_SHA"

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

# Backend/signaling publish no host ports (Caddy-only), so probe INSIDE each
# container with node's fetch — node:22-alpine has no curl/wget beyond busybox.
probe_container() {
  local name="$1" url="$2"
  docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null | grep -q true || return 1
  docker exec "$name" node -e \
    "fetch('$url').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))" \
    >/dev/null 2>&1
}

wait_healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if probe_container dssmeet-backend   "http://127.0.0.1:3001/health" &&
       probe_container dssmeet-signaling "http://127.0.0.1:3002/api/health"; then
      echo "[deploy] health OK (backend + signaling)"
      return 0
    fi
    sleep 3
  done
  echo "[deploy] health check timed out after ${HEALTH_TIMEOUT}s"
  compose ps
  return 1
}

echo "[deploy] building + starting..."
compose up -d --build

if wait_healthy; then
  echo "DEPLOY_OK $NEW_SHA"
  exit 0
fi

echo "[deploy] HEALTH FAILED — rolling back to $PREV_SHA"
git reset --hard "$PREV_SHA"
compose up -d --build

if wait_healthy; then
  echo "ROLLBACK_OK $PREV_SHA (deploy of $NEW_SHA rejected)"
else
  echo "ROLLBACK_FAILED — manual intervention required"
fi
exit 1
