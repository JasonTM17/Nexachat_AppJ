#!/usr/bin/env bash
# Recreate the private query-performance database from scratch:
#   drop -> create -> apply Flyway V1..V16 -> seed.
#
# Usage:  bash tools/perf/recreate_nexachat_perf.sh
# Requires: psql on PATH, the nexachat-db container reachable on :5433.
set -euo pipefail

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5433}"
PGUSER="${PGUSER:-nexachat}"
PGPASSWORD="${PGPASSWORD:-nexachat}"
DB="nexachat_perf"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS="$ROOT/server/src/main/resources/db/migration"

export PGPASSWORD

psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres \
    -c "DROP DATABASE IF EXISTS $DB;" -c "CREATE DATABASE $DB;"

while IFS= read -r file; do
    [ -n "$file" ] || continue
    psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB" -v ON_ERROR_STOP=1 -q -f "$file"
done < <(find "$MIGRATIONS" -maxdepth 1 -type f -name 'V*.sql' | sort -V)

psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB" \
    -f "$ROOT/tools/perf/seed_nexachat_perf.sql"
