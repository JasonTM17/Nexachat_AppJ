# Domain Setup & Android App Links Runbook

This guide covers the DNS setup for `nexachat.io.vn` on Vercel, Android App Links verification, troubleshooting common failure modes, and Vercel token rotation.

---

## 1. Domain & Registrar Information

| Property | Value | Notes |
|---|---|---|
| **Domain** | `nexachat.io.vn` | Registered Vietnamese domain |
| **WWW Subdomain** | `www.nexachat.io.vn` | Aliased & redirected to apex |
| **Expiry Date** | `09/11/2027` | Ensure auto-renew is enabled at registrar |
| **Auto-Renew** | **Enabled** | Recommended to avoid service interruption |

---

## 2. Vercel DNS Records Configuration

Add the following DNS records in your domain registrar's control panel:

| Type | Host / Name | Target / Value | Purpose |
|---|---|---|---|
| **A** | `@` | `76.76.21.21` | Points apex domain `nexachat.io.vn` to Vercel |
| **CNAME** | `www` | `cname.vercel-dns.com.` | Subdomain; Vercel permanently redirects to apex |

---

## 3. DNS Propagation Checks

### Windows PowerShell

```powershell
# Check apex domain A record (expected: 76.76.21.21)
Resolve-DnsName -Name nexachat.io.vn -Type A

# Check www CNAME record (expected: cname.vercel-dns.com)
Resolve-DnsName -Name www.nexachat.io.vn -Type CNAME
```

### Git Bash / Linux

```bash
# Check apex domain A record
nslookup -type=A nexachat.io.vn

# Check www CNAME record
nslookup -type=CNAME www.nexachat.io.vn

# Or using dig if installed:
dig +short A nexachat.io.vn
dig +short CNAME www.nexachat.io.vn
```

### Automated Domain Verification Tool

```bash
# Run comprehensive automated verification:
bash tools/domain/check-domain.sh

# Run offline self-test of verification tooling:
bash tools/domain/check-domain.sh --self-test
```

---

## 4. Testing Android App Links on Device

Prerequisites: NexaChat release APK installed on a connected physical device or emulator with Google Play Services.

### 1. Inspect Verification Status

```bash
adb shell pm get-app-links com.nexachat
```

Expected output includes:
```text
com.nexachat:
    ID: ...
    Signatures: [...]
    Domain verification state:
      nexachat.io.vn: verified
```

### 2. Force Re-Verification

If DNS or assetlinks was updated after installation, trigger an immediate re-verify:

```bash
adb shell pm verify-app-links --re-verify com.nexachat
```

### 3. Test URL Intent Launch

Send an invite link intent to confirm that Android launches NexaChat directly without showing an app chooser:

```bash
adb shell am start -a android.intent.action.VIEW -d "https://nexachat.io.vn/join/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
```

---

## 5. App Links Failure Modes & Troubleshooting

| Failure Mode | Root Cause | Resolution |
|---|---|---|
| **Redirect on `assetlinks.json`** | Android OS requires HTTP 200 without any HTTP redirects for `/.well-known/assetlinks.json`. A 301/302 redirect causes immediate verification failure. | Ensure apex domain serves `assetlinks.json` directly. `site/vercel.json` ensures zero redirects for the apex assetlinks path. |
| **Wrong `Content-Type`** | Served as `text/plain` or `text/html` instead of `application/json`. | `site/vercel.json` explicitly headers `/.well-known/assetlinks.json` with `Content-Type: application/json`. |
| **WWW vs Apex Mismatch** | Manifest specifies `android:host="nexachat.io.vn"`. User visits `www.nexachat.io.vn`. | `www` domain redirects to apex `nexachat.io.vn`. Asset links verification strictly checks the host declared in `AndroidManifest.xml` (`nexachat.io.vn`). |
| **Fingerprint Mismatch (Play Store)** | Google Play App Signing re-signs the APK with a distinct key on store publishing. If only the upload key is present, store installs fail verification. | Add both the upload key and Play App Signing key fingerprints to `assetlinks.json`. |
| **Cached Failed Verification** | Android caches failed verification states indefinitely or across reboots. | Run `adb shell pm verify-app-links --re-verify com.nexachat` or uninstall and reinstall the app. |

---

## 6. Appending Google Play App Signing Fingerprint

When deploying the app to the Google Play Store with Play App Signing:

1. Open the [Google Play Console](https://play.google.com/console).
2. Select the **NexaChat** app (`com.nexachat`).
3. In the left navigation, go to **Setup** -> **App signing**.
4. In the **App signing key certificate** section, copy the **SHA-256** certificate fingerprint.
5. In `site/.well-known/assetlinks.json`, append the fingerprint to `sha256_cert_fingerprints`:
   ```json
   [
     {
       "relation": [
         "delegate_permission/common.handle_all_urls"
       ],
       "target": {
         "namespace": "android_app",
         "package_name": "com.nexachat",
         "sha256_cert_fingerprints": [
           "AA:A9:6B:96:7C:ED:C6:78:DE:9C:77:35:78:5C:87:8C:B2:5D:AA:43:52:9B:E0:DF:EF:0B:73:93:4D:BE:01:7A",
           "NEW_PLAY_APP_SIGNING_SHA256_FINGERPRINT_HERE"
         ]
       }
     }
   ]
   ```
6. Validate locally:
   ```bash
   python site/check.py
   ```
7. Redeploy to Vercel:
   ```bash
   vercel deploy --prod --yes
   ```
8. **Note**: Never add the debug keystore (`android/debug.keystore`) fingerprint to `assetlinks.json`.

---

## 7. Rotating the Vercel Deployment Token

To rotate an expiring or compromised Vercel token:

1. Open [Vercel Account Tokens](https://vercel.com/account/tokens) in your browser.
2. Click **Create** to generate a new token (e.g. named `nexachat-deploy-token`).
3. Replace the token in your local `.env` file (never commit this file):
   ```bash
   VERCEL_TOKEN=new_token_value_here
   ```
4. Verify authentication using the new token:
   ```bash
   vercel whoami
   ```
5. Return to the Vercel Tokens dashboard and **Delete / Revoke** the old token.

---

## 8. Vercel build skip (`ignoreCommand`)

Vercel project settings previously used `git diff --quiet HEAD^ HEAD -- .`, which only
compares the latest commit and can **skip deployments** when a push contains multiple
commits that touch `site/`.

The repository now owns this logic in `site/vercel.json` via `ignoreCommand`, using
`$VERCEL_GIT_PREVIOUS_SHA` with safe fallbacks:

- If `VERCEL_GIT_PREVIOUS_SHA` is empty or not present in the clone, the command **exits 1**
  and Vercel **builds** (no silent skip).
- Otherwise, Vercel skips the build only when `git diff` between the previous and current
  deployment SHAs is empty for files under the site root directory.

**Operator action:** In the Vercel project dashboard, **clear** any custom “Ignored Build Step”
command so the `ignoreCommand` from `site/vercel.json` is authoritative. The release lead
performs this dashboard change; do not store or read Vercel tokens from the repo.

Validate locally after edits:

```bash
python site/check.py
```

## Android build configuration for App Links

## Overview

NexaChat uses the verified project domain **`nexachat.io.vn`** for production Android App Links and invite sharing links:
- **HTTPS App Link format:** `https://nexachat.io.vn/join/<token>`
- **Custom scheme fallback:** `nexachat://join/<token>` (active across all build types)

## Build Type Invariant

- **Release builds:**
  - `BuildConfig.INVITE_LINK_HTTPS_HOST` is set to `nexachat.io.vn`.
  - Merged release manifest contains exactly one HTTPS intent filter with `android:autoVerify="true"`, scheme `https`, host `nexachat.io.vn`, and pathPrefix `/join/` on `com.nexachat.MainActivity`.
  - Share and copy actions in release builds produce `https://nexachat.io.vn/join/<token>`.
  - Deep link resource `invite_https_deep_link_pattern` resolves to `https://nexachat.io.vn/join/{token}` for Jetpack Navigation.
- **Debug / Local-Smoke builds:**
  - `BuildConfig.INVITE_LINK_HTTPS_HOST` is empty (`""`).
  - Debug and `localSmoke` manifests have no HTTPS intent filter so local developers are not tied to the owned domain. `localSmoke` is the local-smoke build type.
  - Share and copy actions default to `nexachat://join/<token>`.
  - Deep link resource `invite_https_deep_link_pattern` resolves to `nexachat://join/{token}`. The invite destination already declares that custom-scheme pattern, so the resource is a second identical navigation deep link. It is not an HTTPS registration, and `InviteLinkParser` still rejects `https` links while the host is empty.

## Procedure to Change the Host

If the verified domain ever changes, update the release host via either:

1. **Permanent change in `android/app/build.gradle` (one-line edit):**
   Change line 82 of `android/app/build.gradle`:
   ```groovy
   def releaseInviteLinkHttpsHost = inviteLinkHttpsHostProp.isEmpty() ? '<new-host>' : inviteLinkHttpsHostProp
   ```

2. **Temporary / CI build property override (one command flag):**
   Pass `-PinviteLinkHttpsHost=<new-host>` during the Gradle invocation:
   ```bash
   ./gradlew :app:assembleRelease -PinviteLinkHttpsHost=<new-host>
   ```

When updating the host, also ensure the Digital Asset Links file at `https://<new-host>/.well-known/assetlinks.json` serves the release keystore's SHA-256 certificate fingerprint for package `com.nexachat`.
