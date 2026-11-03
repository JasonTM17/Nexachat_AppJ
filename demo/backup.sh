#!/usr/bin/env bash
# ============================================================================
# NexaChat demo backup — pg_dump the compose Postgres into demo/backups/.
# Restore with:  docker exec -i $(docker compose ps -q db) psql -U nexachat -d nexachat < demo/backups/<file>.sql
# Usage:  ./demo/backup.sh
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p demo/backups
TS="$(date +%Y%m%d-%H%M%S)"
OUT="demo/backups/nexachat-${TS}.sql"

CID="$(docker compose ps -q db)"
if [ -z "${CID}" ]; then
  echo "ERROR: db container is not running. Start it with: docker compose up -d db" >&2
  exit 1
fi

# --clean/--if-exists make the dump re-appliable over an existing schema.
docker exec "${CID}" pg_dump -U nexachat -d nexachat --clean --if-exists > "${OUT}"

echo "Backup written: ${OUT} ($(wc -c < "${OUT}" | tr -d ' ') bytes)"
