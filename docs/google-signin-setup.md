# Google sign-in setup (operators)

End-to-end checklist for enabling **Sign in with Google** on NexaChat. The
Android app uses Google's Credential Manager with a **Web application** OAuth
client ID; the server verifies ID tokens with Google's public JWKS (no client
secret). For REST contract details see [docs/api-contract.md](api-contract.md);
for security rules see [docs/security.md](security.md).

## Google Cloud Console

1. Open [Google Cloud Console](https://console.cloud.google.com/) and select or
   create a project for NexaChat.
2. **APIs & Services → Library** — enable **Google Identity Services** (and
   ensure the project can use OAuth).
3. **APIs & Services → OAuth consent screen** — configure the consent screen
   (External or Internal per your audience), add the app name and support email,
   and publish or verify as required for production testers.
4. **APIs & Services → Credentials → Create credentials → OAuth client ID**:
   - **Web application** — create one client. This ID is the expected `aud` of
     the ID token and is what the Android app passes to Credential Manager as
     `serverClientId`. Copy it into server `NEXACHAT_GOOGLE_WEB_CLIENT_ID` and
     Android `GOOGLE_WEB_CLIENT_ID` (see below). Do not commit real values.
   - **Android** — create **two** clients for package name `com.nexachat`:
     - **Debug** — register the SHA-1 of the **debug signing key** (see
       [Android OAuth fingerprints](#android-oauth-fingerprints)).
     - **Release** — register the SHA-1 of your **upload/release signing key**
       (see [docs/android-release.md](android-release.md)).
     Each Android OAuth client has its own client ID string. The server does not
     read those IDs from separate env vars; list both in one place (below).

## Server environment variables (authoritative names)

Spring binds `nexachat.google.signin.*` in
`server/src/main/resources/application.yml` from these names only:

| Environment variable | Role |
|---|---|
| `NEXACHAT_GOOGLE_SIGNIN_ENABLED` | Master switch (`false` = all `/api/v1/auth/google/*` return 404) |
| `NEXACHAT_GOOGLE_WEB_CLIENT_ID` | Required when enabled; expected token `aud` |
| `NEXACHAT_GOOGLE_ANDROID_CLIENT_IDS` | Required when enabled; **comma-separated** Android OAuth client IDs trusted as token `azp` |
| `NEXACHAT_GOOGLE_JWKS_URI` | Optional; default Google's JWKS URL (override only in tests) |
| `NEXACHAT_GOOGLE_NONCE_TTL_SECONDS` | Optional; single-use nonce lifetime (default `300`) |
| `NEXACHAT_GOOGLE_SIGNUP_ENABLED` | Planned for Google **account creation** (default `false`); server property wiring ships separately — set only after that release |

`GoogleSignInProperties` and `GoogleSignInConfiguration` require a non-blank web
client ID and at least one Android client ID when sign-in is enabled; startup
fails closed otherwise.

### Operator env name mismatch (debug + release IDs)

Some operator `.env` files use two separate variables such as
`NEXACHAT_GOOGLE_ANDROID_DEBUG_CLIENT_ID` and
`NEXACHAT_GOOGLE_ANDROID_RELEASE_CLIENT_ID`. **The server does not read those
names.** It reads exactly one variable,
`NEXACHAT_GOOGLE_ANDROID_CLIENT_IDS`, as a comma-separated list.

**Smallest fix (documentation only, no server change):** set

```properties
NEXACHAT_GOOGLE_ANDROID_CLIENT_IDS=<debug-android-client-id>,<release-android-client-id>
```

Use your real debug and release Android OAuth client ID strings (no spaces
required around commas). Order does not matter. There is no documented env
fallback for the two separate names unless a future compose helper merges them;
operators must use the comma list in `.env` and on Render.

## Android configuration

In `android/local.properties` (gitignored) or on the Gradle command line:

```properties
GOOGLE_WEB_CLIENT_ID=<your-web-client-id>.apps.googleusercontent.com
```

```bash
cd android
./gradlew assembleDebug -PgoogleWebClientId=<your-web-client-id>.apps.googleusercontent.com
```

Release builds:

```bash
./gradlew assembleRelease -PapiBaseUrl=https://api.nexachat.io.vn \
  -PgoogleWebClientId=<your-web-client-id>.apps.googleusercontent.com
```

When `GOOGLE_WEB_CLIENT_ID` is empty, `BuildConfig.GOOGLE_WEB_CLIENT_ID` is
empty and every Google entry point is hidden (`CredentialManagerGoogleSignIn`).

### Token shape (why web + Android IDs differ)

Credential Manager requests an ID token using the **Web** client ID as
`serverClientId`. Google sets token `aud` to that web client and often sets
`azp` to the **Android** OAuth client that matches the APK's signing
certificate. The server checks `aud` against `NEXACHAT_GOOGLE_WEB_CLIENT_ID` and
`azp` against the list in `NEXACHAT_GOOGLE_ANDROID_CLIENT_IDS`
(`GoogleIdTokenValidators`).

### Android OAuth fingerprints

**Debug** (local `assembleDebug` installs):

```powershell
keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
```

Use the **SHA-1** line on the **debug** Android OAuth client in Google Cloud.

**Release** (signed APK / Play-style builds): use the upload keystore from
[docs/android-release.md](android-release.md) (`keytool -list -v -keystore ...`).
Use that SHA-1 on the **release** Android OAuth client.

If Google sign-in fails only on one build type, the usual cause is a missing or
wrong SHA-1 on the matching Android OAuth client.

## Local testing

1. Copy `.env.example` to `.env` and set the Google variables (names only in
   the example; use your real IDs locally, never commit them).
2. Start PostgreSQL and the API with Google vars in the process environment.
   The stock `docker-compose.yml` app service does not forward every Google
   key; for a quick check, export the same variables and run the server from
   `server/` with Maven, or add the keys to your compose overlay.
3. Confirm the feature is on:
   `curl -fsS http://localhost:8080/actuator/health` (service up), then
   `curl -fsS -X POST http://localhost:8080/api/v1/auth/google/nonce` — expect
   `200` with a nonce when enabled, `404` when disabled.
4. Build and install a debug APK with `GOOGLE_WEB_CLIENT_ID` set. Sign in on an
   emulator or device whose Google account is allowed on your consent screen.
   The account must already be **linked** on NexaChat (current main behaviour)
   unless `NEXACHAT_GOOGLE_SIGNUP_ENABLED` is implemented and turned on.

Automated tests use a local JWKS and test client IDs (`GoogleTestKeys`); they do
not call Google.

## Production (Render)

Blueprint keys live in [render.yaml](../render.yaml). Private client IDs use
`sync: false`. After the first Blueprint apply, set production values in the
Render Dashboard (**Environment**) or with the Render API (**one PUT per key**).
See [docs/deploy-render.md](deploy-render.md) §5. Redeploy after changing
secrets.

Keep `NEXACHAT_GOOGLE_SIGNUP_ENABLED=false` on production until the signup
server feature ships; document the flag in `.env.example` so operators know the
name.

## Related docs

- [docs/android-release.md](android-release.md) — release keystore SHA-1 for the
  release Android OAuth client
- [docs/deploy-render.md](deploy-render.md) — Render env table and secrets
- [docs/operations.md](operations.md) — full environment variable matrix
