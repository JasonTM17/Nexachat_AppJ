# Release Checklist

Concrete steps to cut a NexaChat release from this repository. Commands assume
the repository root; replace `<...>` placeholders with the deployment's real
values. Items marked **owner** or **credential** cannot be completed from the
repository alone.

## 0. Preconditions

- [ ] JDK 21+ (`JAVA_HOME`), Android SDK (`ANDROID_HOME`), Docker, and a
  disposable PostgreSQL 16 database are available.
- [ ] The release commit is on `main`, CI is green, and no uncommitted changes
  remain (`git status --short`).
- [ ] **credential** Release signing inputs exist outside git:
  `release/keystore/signing.properties` (or `RELEASE_SIGNING_PROPERTIES`) with
  `storeFile`, `keyAlias`, `keystore.storePassword`, `keyPassword`
  (`tools/README-release.md` §1).
- [ ] **credential** Production environment values are prepared:
  `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `JWT_SECRET`
  (32+ bytes, not the repository development value), `STORAGE_ROOT`; optional
  FCM, summary LLM and RAG keys as decided by the owner (`.env.example`,
  `docs/operations.md` §Configuration).
- [ ] **owner** Decide the semver tag (`MAJOR.MINOR.PATCH`, optional `v`), the
  release notes, and whether this release goes to Google Play.

## 1. Verify the release tooling

```bash
bash tools/test-release-version.sh
bash tools/test-sign-release-url-guard.sh
```

Expected: both print `PASS`. They do not build or sign.

## 2. Run the repository gates

Server (private disposable database; do not point at the demo or production DB):

```bash
export IT_DB_URL='jdbc:postgresql://localhost:5433/nexachat_release'
export IT_DB_USER='nexachat'
export IT_DB_PASSWORD='<local test password>'
env -u FCM_ENABLED -u FCM_PROJECT_ID -u FCM_SERVICE_ACCOUNT_JSON \
  mvn -B -f server/pom.xml verify
```

Expected: unit tests via Surefire and `*IT` suites via Failsafe pass, Flyway
migrates from empty, and `ddl-auto: validate` holds. Create the private
database first if it does not exist; the test role needs permission to create
and drop its own scratch databases (`docs/testing.md`).

Android:

```bash
cd android
JAVA_HOME='<jdk-21-or-newer>' ANDROID_HOME='<android-sdk>' \
  ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
cd ..
```

Repository hygiene (core checks; the CI job also runs `actionlint` and
`shellcheck` when it is installed):

```bash
python tools/check-markdown-links.py
python site/check.py
python tools/check-android-strings.py --self-test
python tools/check-android-strings.py
find demo tools -name '*.sh' -type f -print0 | xargs -0 -n1 bash -n
```

CI also runs a committed-secret scan (`.github/workflows/ci.yml`). The server
and Android jobs are the required gates; do not release on a red or skipped job.
A green JVM unit test or an HTML screenshot is not a device pass. Capture
inbox, chat, settings, group detail, summary, and calls in English and
Vietnamese, light and dark, at 100% and 200% font before calling the visual
refresh verified on a phone. The launcher mark is the Note monogram. On a
phone, confirm the round and squircle masks, the API 33 themed icon, the splash
mark, and the notification icon.

## 3. Build, check and sign the Android artifact

```bash
# Optional: confirm the Firebase client file is present for this build
cd android
JAVA_HOME='<jdk>' ANDROID_HOME='<sdk>' ./gradlew :app:checkFirebaseConfig
cd ..

# Build a release APK, align, sign and verify it
tools/sign-release.sh v<MAJOR.MINOR.PATCH> -PapiBaseUrl='<https-api-base-url>'

# Prove R8 retained the reflective wire models
python tools/check-release-r8-mapping.py
```

- The signer rejects a non-HTTPS `API_BASE_URL`; `--allow-http-smoke` is only
  for a local smoke build and must not be used for a distributed APK
  (`tools/release-url-guard.sh`).
- The signer writes `release/nexachat-vX.Y.Z.apk` and runs
  `apksigner verify --print-certs`; both outputs are gitignored.
- `check-release-r8-mapping.py` reads
  `android/app/build/outputs/mapping/release/mapping.txt` and `usage.txt` and
  fails on renamed/removed reflective DTOs, Room migrations or required keep
  rules.
- `versionCode = MAJOR * 1,000,000 + MINOR * 1,000 + PATCH`, capped at
  `2,100,000,000` (`tools/release-version.sh`). Confirm it is greater than the
  version code of the last published release.
- If the Play Console requires an AAB, build it separately with the signing
  credentials and run the same mapping check. The repository's `sign-release.sh`
  produces an APK, not an AAB (**owner** decides whether an AAB is required).

## 4. Publish the server image

CI publishes only on a push to `main`, after all four jobs pass
(`.github/workflows/ci.yml`):

```bash
# What CI produces, for verification
docker pull ghcr.io/<owner>/<repo>/nexachat-server:sha-<7-char-sha>
docker image inspect ghcr.io/<owner>/<repo>/nexachat-server:sha-<7-char-sha>
```

Pin the `sha-<7>` tag or its digest in production; do not deploy `latest`
blindly. The image is a multi-stage build that runs as UID/GID `10001`, uses
`-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`, and has a liveness
health check (`server/Dockerfile`). The Dockerfile skips Maven tests: the
gates in step 2 are the test evidence.

## 5. Roll out the database migration and server

Flyway runs on application startup and is forward-only: `V1`–`V20`, `V22`, and
`V24`–`V30` are the current files (`server/src/main/resources/db/migration/`);
`V21` and `V23` are reserved and have no files. There is no down-migration.

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
2. Keep the current image tag recorded so it can be redeployed.
3. Start one instance of the new image with the production profile
   (`SPRING_PROFILES_ACTIVE=prod,json-logs`) and the production environment
   values.
4. Wait for `/actuator/health/liveness` and `/actuator/health/readiness` to
   report `UP`; readiness covers the database, Flyway and disk space.
5. Check the startup log for Flyway success and no `ProductionJwtSecretGuard`
   failure. Then roll the remaining instances.
6. Do not start an older image against a newer schema unless the migration was
   reviewed as backward-compatible. If a migration fails, keep the app stopped
   and restore the pre-upgrade backup (`docs/operations.md` §Upgrade).

Compose demo equivalent (create `.env` from `.env.example` first;
`docker-compose.yml` requires `DATABASE_USER`, `DATABASE_PASSWORD` and
`JWT_SECRET` and fails closed without them):

```bash
docker compose build app
docker compose up -d app
curl -fsS http://127.0.0.1:8080/actuator/health
```

Update the `image:` tag in `docker-compose.yml` if the compose deployment is
expected to run the new release rather than a locally built image.

## 6. Smoke tests

On a staging deployment or a disposable Compose stack:

- [ ] `GET /actuator/health` returns `UP`; liveness and readiness are `UP`.
- [ ] Register two accounts, sign in on the release APK, and exchange a direct
  message in both directions.
- [ ] Send and download an attachment; reload it from the media viewer.
- [ ] Background/foreground the app and confirm the STOMP session reconnects.
- [ ] Check server logs for unhandled exceptions or secret leakage; check the
  app for crashes/ANRs.
- [ ] With the release build, open `https://nexachat.io.vn/join/<token>` and
  confirm the app opens without a chooser; malformed tokens fall back safely.
- [ ] Run the live driver on a disposable stack when the change touches
  realtime or authorization surfaces:
  `python tools/e2e/final/final_live.py --help` (see `docs/testing.md` for the
  required container names and coordinates).
- [ ] Run `bash tools/domain/check-domain.sh` and
  `python site/check.py --url https://nexachat.io.vn` for the public site.

## 7. Rollback

- **Database:** restore the pre-upgrade backup from step 5; do not attempt a
  reverse migration. Stop the app first (`docs/operations.md` §Backup and
  restore). Note that `demo/backup.sh` applies solely to the local Compose demo;
  hosted Neon database recovery must be coordinated with Supabase S3 object
  storage to maintain database-to-attachment referential consistency.
- **Server:** redeploy the previous image tag/digest and keep the database
  compatible with it.
- **Android:** Play cannot un-publish a version; ship a higher `versionCode`
  with the fix. Keep the previous APK/AAB for direct-install users.
- **Site:** redeploy the previous Vercel deployment; the site has no database.

## 8. Close out

- [ ] Tag the release commit (`git tag -a vX.Y.Z -m 'NexaChat vX.Y.Z'`) and
  push the tag if the team publishes tags. CI does not create tags.
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
  (`server/src/main/resources/application.yml:153-208`).
- [ ] Record the image digest, APK/AAB checksum, migration version and smoke
  results in the release notes or deployment log.

## Steps that need credentials or owner decisions

| Step | Why |
|---|---|
| Android signing keystore and `signing.properties` | Secret; never committed (`tools/README-release.md`). |
| Production `DATABASE_*`, `JWT_SECRET`, `STORAGE_ROOT` | Secret/environment-specific. |
| FCM service account and `google-services.json` | External Firebase project; not in repository. |
| Summary LLM / RAG embedding API keys | Optional paid providers; disabled by default. |
| Play Console upload, App Signing fingerprint, data-safety form | Store account and legal/product decisions. |
| Vercel production deploy and DNS | Deployment account; see `docs/domain-and-app-links.md`. |
| Semver tag, release notes, enable/disable feature flags | Product decision. |
