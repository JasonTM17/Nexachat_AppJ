# Android APK release

The `Android APK Release` workflow builds on a `vMAJOR.MINOR.PATCH` tag and
publishes the signed APK plus its SHA-256 file to that GitHub Release. It can
also be started manually with an existing tag. The tag supplies Android's
`versionName` and `versionCode` through `tools/release-version.sh`.

## Prepare the signing key once

Create the upload keystore outside the repository. Keep an encrypted backup in
a separate secure location; losing the key prevents existing APK installs from
being upgraded with a build signed by a different key.

```powershell
$keyDir = Join-Path $HOME 'NexaChat\keystore'
New-Item -ItemType Directory -Force -Path $keyDir | Out-Null
$keystore = Join-Path $keyDir 'nexachat-upload.jks'
keytool -genkeypair -v -keystore $keystore -alias nexachat `
  -keyalg RSA -keysize 2048 -validity 10000 -storetype JKS
```

`keytool` prompts for the passwords and certificate details. Read the upload
certificate fingerprints with:

```powershell
keytool -list -v -keystore $keystore -alias nexachat
```

Use the SHA-1 fingerprint with the Android OAuth client for package
`com.nexachat`. Add both SHA-1 and SHA-256 fingerprints to the matching Android
app in Firebase Project settings. These fingerprints identify the signing key
used by distributed APKs.

## Google OAuth clients (sign-in)

NexaChat's **Sign in with Google** flow uses three OAuth client IDs in Google
Cloud (see [docs/google-signin-setup.md](google-signin-setup.md)):

| Credential type | Where it is used |
|---|---|
| **Web application** | Android `GOOGLE_WEB_CLIENT_ID` in `local.properties` or `-PgoogleWebClientId=`; server `NEXACHAT_GOOGLE_WEB_CLIENT_ID` — ID token `aud` |
| **Android (debug)** | Google Cloud Android client with the **debug keystore** SHA-1 (`~/.android/debug.keystore`, alias `androiddebugkey`) |
| **Android (release)** | Google Cloud Android client with the **upload keystore** SHA-1 from the keystore section above |

The Android app always passes the **web** client ID to Credential Manager. The
server trusts the **Android** client IDs as the token's `azp` via
`NEXACHAT_GOOGLE_ANDROID_CLIENT_IDS` (comma-separated list of debug and release
Android client ID strings). Do not put client IDs in the repository.

Release APK builds must pass the same web client ID as production, for example:

```bash
./gradlew assembleRelease -PapiBaseUrl=https://api.nexachat.io.vn \
  -PgoogleWebClientId=<your-web-client-id>.apps.googleusercontent.com
```

If sign-in works on debug but fails on a release APK, compare the APK signing
certificate SHA-1 with the fingerprints on your **release** Android OAuth client.

## Configure GitHub Actions secrets

In the repository, open **Settings → Secrets and variables → Actions** and add:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the `.jks` file |
| `RELEASE_STORE_FILE` | Filename only, for example `nexachat-upload.jks` |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | `nexachat` |
| `RELEASE_KEY_PASSWORD` | Key password |
| `RELEASE_API_BASE_URL` | Production API base URL using HTTPS |
| `RELEASE_GOOGLE_WEB_CLIENT_ID` | Google OAuth Web client ID passed as `-PgoogleWebClientId`; when unset the APK hides every Google entry point |

Before adding these secrets, protect the `v*` tag pattern so only release
maintainers can create tags, require review for release-workflow changes on
`main`, and configure the `android-release` environment with required reviewers
and allowed refs limited to `main` and protected `v*` tags. The release job
waits for that environment approval. Start manual runs from `main`; runs
dispatched from other refs are skipped.

In Git Bash, load the keystore secret without printing its contents:

```bash
base64 -w 0 "$HOME/NexaChat/keystore/nexachat-upload.jks" |
  gh secret set ANDROID_KEYSTORE_BASE64
```

The workflow decodes the keystore under the runner's temporary directory,
removes it after the job, and never writes signing inputs into the repository.

## Cut and verify a release

After the release commit is on `main` and its required CI checks pass, create
and push a semantic version tag:

```powershell
git checkout main
git pull --ff-only
git tag -a v1.2.3 -m 'NexaChat v1.2.3'
git push origin v1.2.3
```

The workflow runs for tags beginning with `v`. To rerun for an existing tag,
open **Actions → Android APK Release → Run workflow** and enter the tag.
Download `nexachat-v1.2.3.apk` and `nexachat-v1.2.3.apk.sha256` from the release.

On Windows, compare the downloaded APK with its checksum file:

```powershell
$apk = '.\nexachat-v1.2.3.apk'
$expected = (Get-Content "$apk.sha256" -Raw) -split '\s+' | Select-Object -First 1
$actual = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw 'APK SHA-256 mismatch' }
```
