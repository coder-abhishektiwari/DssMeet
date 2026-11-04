#!/usr/bin/env bash
# Nightly pg_dump for the DssMeet Postgres container.
# Install (as root, RUNBOOK step 9): crontab -e →
#   0 3 * * * /opt/DssMeet/deploy/backup.sh >> /var/log/pc-backup.log 2>&1
# Keeps 14 daily dumps locally; Hostinger's weekly off-server backup is the
# second layer (hPanel → VPS → Backups).
set -euo pipefail

STAMP=$(date +%F-%H%M)
DIR=/var/backups/dssmeet
FILE="$DIR/pc-$STAMP.dump"

mkdir -p "$DIR"
docker exec dssmeet-postgres pg_dump -U dssmeet -Fc dssmeet > "$FILE"
find "$DIR" -name 'pc-*.dump' -mtime +14 -delete

echo "$(date -Is) backup ok: $FILE ($(du -h "$FILE" | cut -f1))"

# Restore (disaster recovery — see RUNBOOK step 10):
#   docker exec -i dssmeet-postgres pg_restore -U dssmeet \
#     -d dssmeet --clean --if-exists < /var/backups/dssmeet/pc-<date>.dump
