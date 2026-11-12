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
  GitHub Actions secrets for automated release (`tools/README-release.md` §1).
- [ ] **credential** Production environment values are prepared:
  `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `JWT_SECRET`
  (32+ bytes, not the repository development value), `STORAGE_ROOT`; optional
  FCM, summary LLM and RAG keys as decided by the owner (`.env.example`,
  `docs/operations.md` §Configuration).
- [ ] **owner** Decide the semver tag (`v<MAJOR>.<MINOR>.<PATCH>`), update
  `CHANGELOG.md` and release notes (`docs/release-notes/v<MAJOR>.<MINOR>.<PATCH>.md`),
  and decide whether this release goes to Google Play.

## 1. Pre-push repository gates

Verify release tooling:
```bash
bash tools/test-release-version.sh
bash tools/test-sign-release-url-guard.sh
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
python tools/check-markdown-links.py
python site/check.py
python tools/check-android-strings.py --self-test
python tools/check-android-strings.py
find demo tools -name '*.sh' -type f -print0 | xargs -0 -n1 bash -n
```

## 2. Production Release Sequence

The production service `nexachat-api` on Render was created through the Render API
with auto-deploy disabled. It is not managed by the `render.yaml` Blueprint; pushing
to `main` does not deploy. Every release is triggered manually per commit SHA.

Follow the verified sequence:

- [ ] **1. Push** — Push the release commit to `main`:
  ```bash
  git push origin main
  ```
- [ ] **2. CI 7/7** — Verify all 7 GitHub Actions CI jobs pass green on `main`
  (`.github/workflows/ci.yml`): `server-test`, `android-test`, `repo-hygiene`,
  `site-js-test`, `firebase-rules`, `secret-scan`, and `publish-container`.
- [ ] **3. Env diff against `render.yaml`** — Compare the production service
  environment variables against `render.yaml`. Apply any added or changed values
  (e.g. `JAVA_OPTS`, `NEXACHAT_MULTIPART_TEMP_DIR`) through the Render API or
  Dashboard before deploying (see [docs/deploy-render.md](deploy-render.md)):
  ```bash
  curl -fsS -X PUT \
    -H "Authorization: Bearer <RENDER_API_KEY>" \
    -H "Content-Type: application/json" \
    -d '[{"key": "<KEY>", "value": "<VALUE>"}]' \
    https://api.render.com/v1/services/<service-id>/env-vars
  ```
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
- [ ] **7. Smoke tests** — Run [tools/prod-smoke.sh](../tools/prod-smoke.sh) read-only, then with write verification:
  ```bash
  bash tools/prod-smoke.sh https://api.nexachat.io.vn
  bash tools/prod-smoke.sh https://api.nexachat.io.vn --write
  ```
- [ ] **8. Scan logs** — Scan Render application logs for new `WARN` or `ERROR` entries.
- [ ] **9. Tag** — Tag the release commit and push the tag:
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

## 3. Rollback

- **Server**: Redeploy the previous deploy's commit SHA via the Render API or
  Dashboard, and restore the previous environment variable values if any were
  changed.
- **Database**: Migrations are strictly forward-only (`validate-on-migrate: true`).
  Do not attempt a reverse Flyway migration. If a rolled-back commit cannot run
  on the migrated schema, deploy a forward fix commit. Hosted Neon Postgres backups
  must be paired with Supabase S3 object storage backups to preserve attachment
  consistency.
- **Android**: Google Play cannot unpublish a version; release a higher
  `versionCode` with the fix. Direct-install users can reinstall the previous APK.
- **Site**: Redeploy the previous Vercel deployment if static web assets changed.

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
