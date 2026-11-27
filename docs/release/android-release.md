# Android APK release

The `Android APK Release` workflow builds and signs the APK and AAB from a
`vMAJOR.MINOR.PATCH` tag on `main`. It stores the signed files and SHA-256
checksums as a workflow artifact. The repository owner verifies the artifact
and publishes it to GitHub Releases. The tag supplies Android's `versionName`
and `versionCode` through `tools/release/release-version.sh`.

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

Google and Firebase match the certificate embedded in the **installed APK**, so
always confirm the fingerprints of what CI actually ships:

```bash
apksigner verify --print-certs nexachat-<version>.apk
```

Verified on the `v1.2.7` GitHub release APK — signer
`CN=Nguyen Tien Son, O=NexaChat, C=VN`, SHA-1
`94:AD:07:4D:52:AC:20:52:89:DE:00:93:68:62:12:97:68:D0:6B:D5`, SHA-256
`E7:41:9A:48:A4:9D:20:4E:55:70:60:6E:B8:33:96:8D:CD:A7:28:E2:AC:99:D7:24:3B:2C:FE:8C:15:8A:39:DF`.
This is the key material in the `ANDROID_KEYSTORE_BASE64` secret; if a local
upload keystore prints a different fingerprint, the APK's signer — not the local
keystore — is what to register.

Use the APK's SHA-1 fingerprint with the Android OAuth client for package
`com.nexachat`. Add both SHA-1 and SHA-256 fingerprints to the matching Android
app in Firebase Project settings. These fingerprints identify the signing key
used by distributed APKs.

## Google OAuth clients (sign-in)

NexaChat's **Sign in with Google** flow uses three OAuth client IDs in Google
Cloud (see [docs/operations/google-signin-setup.md](../operations/google-signin-setup.md)):

| Credential type | Where it is used |
|---|---|
| **Web application** | Android `GOOGLE_WEB_CLIENT_ID` in `local.properties` or `-PgoogleWebClientId=`; server `NEXACHAT_GOOGLE_WEB_CLIENT_ID` — ID token `aud` |
| **Android (debug)** | Google Cloud Android client with the **debug keystore** SHA-1 (`~/.android/debug.keystore`, alias `androiddebugkey`) |
| **Android (release)** | Google Cloud Android client with the SHA-1 of the key that signs the distributed APK — verified `94:AD:07:4D:52:AC:20:52:89:DE:00:93:68:62:12:97:68:D0:6B:D5` on `v1.2.7`; for Play installs, add the Play App Signing key certificate SHA-1 too |

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
`main`, and restrict the `android-release` environment to protected `v*` tags.
The release job waits for any approval configured on the environment. For a
manual run, select the release tag as the workflow ref and enter the same tag
in the input.

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

The workflow runs for tags beginning with `v`. A new manual run may use a tag
that already contains this artifact-upload workflow. Select that tag as the
workflow ref and supply the same case-sensitive tag in the `tag` input. Older
tags carry their original workflow and can publish Release assets directly.
Under the repository owner's GitHub login, verify that the successful signing
run's head SHA matches the commit the remote tag resolves to, then download its
signed artifact.

```powershell
$tag = 'v1.2.3'
$runId = '<successful Android APK Release run ID>'
gh run view $runId --json headSha,headBranch,status,conclusion,url
git ls-remote origin "refs/tags/$tag" "refs/tags/$tag^{}"
gh run download $runId --name "nexachat-$tag-signed" --dir '.\signed-release'
```

Verify the signed APK package, version and certificate using `aapt` and
`apksigner`. Verify the AAB using `jarsigner` and match its signer to the APK.
Check the supplied SHA-256 for each file. The stable APK alias must have the
same bytes as the versioned APK.

On Windows, compare the downloaded APK with its checksum file:

```powershell
$apk = '.\signed-release\nexachat-v1.2.3.apk'
$expected = (Get-Content "$apk.sha256" -Raw) -split '\s+' | Select-Object -First 1
$actual = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw 'APK SHA-256 mismatch' }
```

Complete the device publication gate in the [release checklist](release-checklist.md).
Publish the six verified files from the workflow artifact using the repository
owner's authenticated GitHub CLI and the release notes for the tag. Check the
remote tag commit again before publishing.

```powershell
$assetDir = '.\signed-release'
$assets = @(
    "$assetDir\nexachat-$tag.apk", "$assetDir\nexachat-$tag.apk.sha256",
    "$assetDir\nexachat-$tag.aab", "$assetDir\nexachat-$tag.aab.sha256",
    "$assetDir\nexachat-latest.apk", "$assetDir\nexachat-latest.apk.sha256"
)
gh release create $tag @assets --verify-tag --notes-file "docs/release-notes/$tag.md"
```
