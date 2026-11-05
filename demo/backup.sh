#!/usr/bin/env bash
# ============================================================================
# NexaChat demo backup — pg_dump the compose Postgres into demo/backups/.
# Restore with:  docker exec -i $(docker compose ps -q db) psql -U nexachat -d nexachat < demo/backups/<file>.sql
# Usage:  ./demo/backup.sh
# ============================================================================
set -euo pipefail
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  cat <<'USAGE'
Usage: demo/backup.sh

Write a plain SQL snapshot of the running Compose database to
demo/backups/nexachat-<timestamp>.sql. Existing backups are never replaced.
PowerShell twin: demo/backup.ps1.
USAGE
  exit 0
fi
if [[ $# -ne 0 ]]; then
  echo "ERROR: unexpected argument '$1' (use --help)." >&2
  exit 2
fi
cd "$(dirname "$0")/.."

CID="$(docker compose ps -q db)"
if [ -z "${CID}" ]; then
  echo "ERROR: db container is not running. Start it with: docker compose up -d db" >&2
  exit 1
fi
DB_USER="$(docker exec "${CID}" printenv POSTGRES_USER)"
DB_NAME="$(docker exec "${CID}" printenv POSTGRES_DB)"
if [ -z "${DB_USER}" ] || [ -z "${DB_NAME}" ]; then
  echo "ERROR: could not read the database name and user from the running container." >&2
  exit 1
fi

mkdir -p demo/backups
TS="$(date +%Y%m%d-%H%M%S)"
OUT="demo/backups/nexachat-${TS}.sql"
suffix=1
while [ -e "${OUT}" ]; do
  OUT="demo/backups/nexachat-${TS}-${suffix}.sql"
  suffix=$((suffix + 1))
done
TMP="$(mktemp "demo/backups/.nexachat-${TS}.XXXXXX")"
trap 'rm -f "${TMP}"' EXIT

# --clean/--if-exists make the dump re-appliable over an existing schema.
docker exec "${CID}" pg_dump -U "${DB_USER}" -d "${DB_NAME}" --clean --if-exists > "${TMP}"
mv -- "${TMP}" "${OUT}"
trap - EXIT

echo "Backup written: ${OUT} ($(wc -c < "${OUT}" | tr -d ' ') bytes)"
