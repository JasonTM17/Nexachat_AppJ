# Paired backup and local restore

NexaChat stores conversation history in PostgreSQL and media in a filesystem or
S3-compatible bucket. The tools under `tools/backup/` capture **both** together so
a fixture can be rebuilt on an empty local stack with hash verification.

This is **not** a production backup scheduler. There is no `--production` flag.
Operators must name a protected destination and maintenance window before any hosted
run (see release checklist §demo backup scope).

## Storage kinds

The server supports:

| Kind | Server config | Tool env |
| --- | --- | --- |
| `local` | `NEXACHAT_STORAGE_PROVIDER=local`, `STORAGE_ROOT` / `nexachat.storage.root` | `NEXACHAT_STORAGE_ROOT` |
| `s3` | `NEXACHAT_STORAGE_PROVIDER=s3` plus endpoint, bucket, keys | Same names as `.env.example` |

Optional `boto3` is required only when capturing from S3.

## Reference discovery

Object keys are taken from migrated schema columns, not guessed:

- `message_attachments.storage_key` (V1)
- Avatar files referenced by `users.avatar_url` and `conversations.avatar_url`
  when the URL contains `/api/v1/files/` (same rule as `AvatarUrlPolicy` on the server)

## Backup

Credentials are read from environment variables or `*_FILE` paths only — never from argv.

```bash
export BACKUP_DB_URL=jdbc:postgresql://localhost:5433/nexachat_v12_backup_src
export BACKUP_DB_USER=nexachat
export BACKUP_DB_PASSWORD_FILE=/path/to/local-password.txt
export NEXACHAT_STORAGE_PROVIDER=local
export NEXACHAT_STORAGE_ROOT=./data/uploads
export BACKUP_PG_CONTAINER=nexachat-db-1   # optional: exec pg_dump/psql in compose db

python tools/backup/backup.py --target fixture --output /tmp/nexachat-backup --quiesced
```

Behavior:

- Requires `--quiesced` and zero active DB writers (fails closed on concurrent capture).
- Uses a lock file (`.capture.lock`) and writes `complete.marker` **after** manifest + objects.
- `pg_dump` runs from a client image whose major version is ≥ the source (default `postgres:18`).
- Manifest records format version, dump SHA-256, per-object SHA-256, and reference reconciliation.

## Restore

Restore is limited to an **empty** allowlisted localhost database whose name starts with
`nexachat_restore_`, plus an empty storage directory.

```bash
export RESTORE_DB_USER=nexachat
export RESTORE_DB_PASSWORD_FILE=/path/to/local-password.txt
export RESTORE_STORAGE_ROOT=/tmp/nexachat-restore-uploads
export BACKUP_PG_CONTAINER=nexachat-db-1

python tools/backup/restore.py \
  --manifest /tmp/nexachat-backup/manifest.json \
  --target nexachat_restore_v12 \
  --require-empty \
  --verify
```

Omit `--verify` only after a successful verify pass. There is no flag to target production.

## Local rehearsal

1. Create `nexachat_v12_backup_src` on the shared compose Postgres container and load `demo/seed.sql`.
2. Place a few files under the local storage root and bind them in `message_attachments` if needed.
3. Run backup with `--quiesced` while the app container is stopped.
4. Create empty `nexachat_restore_v12` and an empty storage directory; run restore.
5. Start the server JAR against the restored DB/storage on a private port; fetch message history and one media URL.

When the database came from `pg_dump`, set `SPRING_FLYWAY_ENABLED=false` and ensure the dump schema
matches the JAR (or run Flyway on an empty database before capture). A dump from an older running
instance may not match a newer worktree binary.

Record total backup bytes, wall duration, and the recovery point timestamp from `manifest.json`.

## Tests

```bash
python -m unittest discover -s tools/backup -p "test_*.py"
```

Negative coverage includes interrupted capture, corrupt/missing objects, non-empty targets,
traversal keys, active writers, manifest version mismatch, and log redaction of secrets.
