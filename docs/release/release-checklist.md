# Release Checklist

Concrete steps to cut a NexaChat release from this repository. Commands assume
the repository root; replace `<...>` placeholders with the deployment's real
values. Items marked **owner** or **credential** cannot be completed from the
repository alone.

## 0. Preconditions

- [ ] JDK 21+ (`JAVA_HOME`), Android SDK (`ANDROID_HOME`), Docker, and a
  disposable PostgreSQL 16 database are available.
- [ ] Working tree is clean (`git status --short`).
- [ ] **credential** Release signing inputs exist outside git for local signing or
  GitHub Actions secrets for automated release (`tools/release/README.md` §1).
- [ ] **credential** Production environment values are prepared:
  `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `JWT_SECRET`
  (32+ bytes, not the repository development value), `STORAGE_ROOT`; optional
  FCM, summary LLM and RAG keys as decided by the owner (`.env.example`,
  `docs/operations/operations.md` §Configuration).
- [ ] **owner** Decide the semver tag (`v<MAJOR>.<MINOR>.<PATCH>`), update
  `CHANGELOG.md` and release notes (`docs/release-notes/v<MAJOR>.<MINOR>.<PATCH>.md`),
  and decide whether this release goes to Google Play.

## 1. Pre-push repository gates

Verify release tooling:
```bash
bash tools/release/test-release-version.sh
bash tools/release/test-sign-release-url-guard.sh
```

Server gates (private disposable database; do not point at demo or production):
```bash
export IT_DB_URL='jdbc:postgresql://localhost:5433/nexachat_release'
export IT_DB_USER='nexachat'
export IT_DB_PASSWORD='<local-test-password>'
env -u FCM_ENABLED -u FCM_PROJECT_ID -u FCM_SERVICE_ACCOUNT_JSON \
  mvn -B -f server/pom.xml verify
```

Android and repository hygiene checks:
```bash
cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug --no-daemon && cd ..
python tools/checks/check-markdown-links.py
python site/check.py
python tools/checks/check-android-strings.py --self-test
python tools/checks/check-android-strings.py
find demo tools -name '*.sh' -type f -print0 | xargs -0 -n1 bash -n
```

A green JVM unit test or an HTML screenshot is not a device pass. Capture
inbox, chat, settings, group detail, summary, and calls in English and
Vietnamese, light and dark, at 100% and 200% font before calling the visual
refresh verified on a phone. The launcher mark is the Note monogram. On a
phone, confirm the round and squircle masks, the API 33 themed icon, the splash
mark, and the notification icon.

## 2. Build, check and sign the Android artifact

Run this local build before §4 tags the release and the GitHub workflow
publishes the APK.

```bash
# Optional: confirm the Firebase client file is present for this build
cd android
JAVA_HOME='<jdk>' ANDROID_HOME='<sdk>' ./gradlew :app:checkFirebaseConfig
cd ..

# Build a release APK, align, sign and verify it
tools/release/sign-release.sh v<MAJOR.MINOR.PATCH> -PapiBaseUrl='<https-api-base-url>'

# Prove R8 retained the reflective wire models
python tools/checks/check-release-r8-mapping.py
```

- The signer rejects a non-HTTPS `API_BASE_URL`; `--allow-http-smoke` is only
  for a local smoke build and must not be used for a distributed APK
  (`tools/release/release-url-guard.sh`).
- The signer writes `release/nexachat-vX.Y.Z.apk` and runs
  `apksigner verify --print-certs`; both outputs are gitignored.
- `tools/checks/check-release-r8-mapping.py` reads
  `android/app/build/outputs/mapping/release/mapping.txt` and `usage.txt` and
  fails on renamed/removed reflective DTOs, Room migrations or required keep
  rules.
- `versionCode = MAJOR * 1,000,000 + MINOR * 1,000 + PATCH`, capped at
  `2,100,000,000` (`tools/release/release-version.sh`). Confirm it is greater than the
  version code of the last published release.
- If the Play Console requires an AAB, build it separately with the signing
  credentials and run the same mapping check. The repository's `tools/release/sign-release.sh`
  produces an APK, not an AAB (**owner** decides whether an AAB is required).

## 3. Roll out the database migration and server

Flyway runs on application startup and is forward-only: `V1`–`V20`, `V22`, and
`V24`–`V31` are the current files (`server/src/main/resources/db/migration/`);
`V21` and `V23` are reserved and have no files. There is no down-migration.
The manual Render deploy in §4 boots the API from `server/Dockerfile`; Flyway
applies pending migrations on that boot. There is no separate migration command.

1. **Backup first** (destructive restore is the only rollback):
   - **Local Compose demo only:** `bash demo/backup.sh` (or PowerShell
     `demo/backup.ps1`). This script inspects `docker compose ps -q db` and
     dumps only the local container database to `demo/backups/`. It does
     **not** connect to hosted Neon Postgres and does **not** back up Supabase
     S3 object storage.
   - **Hosted production (Neon + Supabase S3):** `demo/backup.sh` must **not**
     be used for production releases. Production requires an explicit target
     identity verification, a hosted database snapshot/dump (e.g. Neon branch
     or `pg_dump` targeting the Neon endpoint), and paired object storage
     inventory/backup (Supabase S3 `avatars/` and `attachments/`). Restoring a
     database snapshot without paired object storage recovery causes dangling
     references or unrecoverable attachment loss.
2. Record the commit SHA of the current live deploy so that SHA can be
   redeployed.
3. After §4 reports `live`, check the startup log for Flyway success and no
   `ProductionJwtSecretGuard` failure. Readiness covers the database, Flyway
   and disk space.
4. Do not deploy an older commit against a newer schema unless the migration
   was reviewed as backward-compatible. If a migration fails, restore the
   pre-upgrade backup (`docs/operations/operations.md` §Upgrade).

## 4. Production Release Sequence

The production service `nexachat-api` on Render was created through the Render API
with auto-deploy disabled. It is not managed by the `render.yaml` Blueprint; pushing
to `main` does not deploy. Every release is triggered manually per commit SHA.
Back up the database (§3) before step 4; Flyway applies pending migrations when
that deploy boots.

Follow the verified sequence:

- [ ] **1. Push** — Push the release commit to `main`:
  ```bash
  git push origin main
  ```
- [ ] **2. CI 7/7** — Verify all 7 GitHub Actions CI jobs pass green on `main`
  (`.github/workflows/ci.yml`): `server-test`, `android-test`, `repo-hygiene`,
  `site-js-test`, `firebase-rules`, `secret-scan`, and `publish-container`.
- [ ] **3. Env diff against `render.yaml`** — Compare the production service
  environment variables against `render.yaml`. Apply each added or changed value
  (for example `JAVA_OPTS`, `NEXACHAT_MULTIPART_TEMP_DIR`) with one call per key,
  or edit that key in the Dashboard, before deploying
  (see [docs/operations/deploy-render.md](../operations/deploy-render.md)):
  ```bash
  curl -fsS -X PUT -H "Authorization: Bearer <RENDER_API_KEY>" -H "Content-Type: application/json" \
    -d '{"value": "<VALUE>"}' https://api.render.com/v1/services/<service-id>/env-vars/<KEY>
  ```
  The bulk `PUT .../env-vars` replaces every variable, so never use it for a single change.
- [ ] **4. Manual deploy of exact SHA** — Trigger a manual deploy of the exact
  commit SHA via the Render API:
  ```bash
  curl -fsS -X POST \
    -H "Authorization: Bearer <RENDER_API_KEY>" \
    -H "Content-Type: application/json" \
    -d '{"commitId": "<commit-sha>"}' \
    https://api.render.com/v1/services/<service-id>/deploys
  ```
  (or via Render Dashboard → `nexachat-api` → **Manual Deploy** → **Deploy a specific commit**).
- [ ] **5. Wait for `live`** — Wait for the deployment to finish and status to report `live`.
- [ ] **6. Liveness and health 200** — Verify endpoints respond with HTTP 200:
  ```bash
  curl -fsS https://api.nexachat.io.vn/actuator/health/liveness
  curl -fsS https://api.nexachat.io.vn/actuator/health
  curl -fsS https://api.nexachat.io.vn/actuator/health/readiness
  ```
- [ ] **7. Smoke tests** — Run [tools/ops/prod-smoke.sh](../../tools/ops/prod-smoke.sh) read-only, then with write verification:
  ```bash
  bash tools/ops/prod-smoke.sh https://api.nexachat.io.vn
  bash tools/ops/prod-smoke.sh https://api.nexachat.io.vn --write
  ```
- [ ] **8. Scan logs** — Scan Render application logs for new `WARN` or `ERROR` entries.
- [ ] **9. Tag** — Tag the release commit and push the tag (the local APK and R8
  check are §2):
  ```bash
  git tag -a v<MAJOR>.<MINOR>.<PATCH> -m 'NexaChat v<MAJOR>.<MINOR>.<PATCH>'
  git push origin v<MAJOR>.<MINOR>.<PATCH>
  ```
- [ ] **10. Release workflow publishes APK** — Confirm the GitHub Actions release
  workflow (`.github/workflows/android-release.yml`) triggers on the tag push and
  publishes the signed APK and `.sha256` asset to GitHub Releases.
- [ ] **11. Verify APK** — Download the published release assets and verify:
  - Checksum: `sha256sum -c app-release.apk.sha256`
  - Signer: `apksigner verify --print-certs app-release.apk`
  - `versionCode` and `versionName`: `aapt dump badging app-release.apk | grep -E "versionCode|versionName"`
- [ ] **12. Device publication gate** — Verify direct install on a physical
  device (`adb install -r app-release.apk`) or proceed through the Google Play
  Console track release.

## 5. Server image

CI's `publish-container` job still pushes the image
(`ghcr.io/<owner>/<repo>/nexachat-server:latest` and `:sha-<7>`) after the
other CI jobs pass on a push to `main` (`.github/workflows/ci.yml`), but
production does not pull it. Render builds from `server/Dockerfile`.

## 6. Rollback

- **Server**: Redeploy the previous deploy's commit SHA via the Render API or
  Dashboard, and restore the previous environment variable values if any were
  changed. Use one env-var call per key, as in §4. The bulk
  `PUT .../env-vars` replaces every variable, so never use it for a single change.
- **Database**: Migrations are strictly forward-only (`validate-on-migrate: true`).
  Do not attempt a reverse Flyway migration. If a rolled-back commit cannot run
  on the migrated schema, deploy a forward fix commit. Hosted Neon Postgres backups
  must be paired with Supabase S3 object storage backups to preserve attachment
  consistency.
- **Android**: Google Play cannot unpublish a version; release a higher
  `versionCode` with the fix. Direct-install users can reinstall the previous APK.
- **Site**: Redeploy the previous Vercel deployment if static web assets changed.

## 7. Close out

- [ ] Confirm the §4 release tag is pushed
  (`git tag -a vX.Y.Z -m 'NexaChat vX.Y.Z'`). CI does not create tags.
- [ ] Publish release notes from `CHANGELOG.md` (factual, no internal plan
  labels).
- [ ] **owner** Upload the APK/AAB to Play, complete the Data safety and
  content-rating forms, and append the Play App Signing SHA-256 to
  `site/.well-known/assetlinks.json` when it differs from the upload key.
- [ ] **credential** Deploy the updated site (Vercel) and confirm
  `/.well-known/assetlinks.json` returns `200 application/json`.
- [ ] **owner** Decide whether retention jobs, external summary LLM, RAG
  embeddings or FCM are enabled in this environment; they are off or local by
  default. Note: data retention cleanup requires three explicit settings:
  (1) global toggle `NEXACHAT_RETENTION_ENABLED=true`, (2) disabling dry-run
  `NEXACHAT_RETENTION_DRY_RUN=false` (defaults to `true`, which counts candidates
  without deleting), and (3) per-job toggles `NEXACHAT_RETENTION_<JOB>_ENABLED=true`
  (`server/src/main/resources/application.yml:172-227`).
- [ ] Record the deployed commit SHA, APK/AAB checksum, migration version and
  smoke results in the release notes or deployment log.

## Steps that need credentials or owner decisions

| Step | Why |
|---|---|
| Android signing keystore and `signing.properties` | Secret; never committed (`tools/release/README.md`). |
| Production `DATABASE_*`, `JWT_SECRET`, `STORAGE_ROOT` | Secret/environment-specific. |
| FCM service account and `google-services.json` | External Firebase project; not in repository. |
| Summary LLM / RAG embedding API keys | Optional paid providers; disabled by default. |
| Play Console upload, App Signing fingerprint, data-safety form | Store account and legal/product decisions. |
| Vercel production deploy and DNS | Deployment account; see `docs/operations/domain-and-app-links.md`. |
| Semver tag, release notes, enable/disable feature flags | Product decision. |
