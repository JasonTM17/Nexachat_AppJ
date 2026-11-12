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
export BACKUP_PG_CONTAINER=nexachat-db-1   # optional: exec psql inventory in compose db

python tools/backup/backup.py --target fixture --output /tmp/nexachat-backup --quiesced
```

Behavior:

- Requires `--quiesced` and zero active DB writers (fails closed on concurrent capture).
- Uses a lock file (`.capture.lock`) and writes `complete.marker` **after** manifest + objects.
- `pg_dump` always runs from the Postgres client image `BACKUP_PGDOCKER_IMAGE` (default
  `postgres:18`), reaching the host database via `host.docker.internal` on Windows/macOS
  or the configured host. Client major must be ≥ server major; both are recorded in the manifest.
- Passwords are passed via subprocess/docker environment only (`-e PGPASSWORD` without a value
  on argv).
- Manifest records format version, dump SHA-256, per-object SHA-256, reference reconciliation,
  and PostgreSQL client/server majors.

## Restore

Restore is limited to an **empty** allowlisted localhost database whose name starts with
`nexachat_restore_`, plus an **empty** storage directory. Emptiness is always enforced
(`--require-empty` is deprecated and ignored).

```bash
export RESTORE_DB_USER=nexachat
export RESTORE_PG_CONTAINER=nexachat-db-1
export RESTORE_STORAGE_ROOT=/tmp/nexachat-restore-uploads

python tools/backup/restore.py \
  --manifest /tmp/nexachat-backup/manifest.json \
  --target nexachat_restore_v12 \
  --verify
```

Omit `--verify` only after a successful verify pass. There is no flag to target production.

## Local rehearsal

Strict fixture (matches JAR Flyway HEAD):

1. Create empty `nexachat_v12_backup_src` on the compose Postgres container.
2. Boot the worktree server JAR once against that database so Flyway migrates to HEAD, then stop it.
3. Load `demo/seed.sql`.
4. Upload one attachment through `POST /api/v1/attachments` and send a `FILE` message referencing it.
5. Run backup with `--quiesced` while the app is stopped.
6. Create empty `nexachat_restore_v12` and an empty storage directory; run restore.
7. Boot the same JAR on a private port (e.g. 18081) with restored DB/storage; log in as a seed user,
   fetch conversation history, and download the attachment. Compare its SHA-256 with the manifest.

Record total backup bytes, wall duration, and the recovery point timestamp from `manifest.json`.

## Tests

```bash
python -m unittest discover -s tools/backup -p "test_*.py"
```

Negative coverage includes interrupted capture, corrupt/missing objects, non-empty targets,
traversal keys, active writers, manifest version mismatch, log redaction of secrets, and
guards that database passwords never appear on subprocess argv.
