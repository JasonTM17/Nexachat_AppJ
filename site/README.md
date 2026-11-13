# NexaChat Static Site & App Links Configuration

This directory contains the minimal static site for `nexachat.io.vn`, deployed on Vercel.

## Digital Asset Links (`.well-known/assetlinks.json`)

Android uses Digital Asset Links to verify App Links ownership for opening HTTPS invite links (`https://nexachat.io.vn/join/<token>`) directly in the NexaChat Android app.

Currently, `site/.well-known/assetlinks.json` contains the production declaration for package `com.nexachat` with the upload key certificate fingerprint:
- SHA-256: `AA:A9:6B:96:7C:ED:C6:78:DE:9C:77:35:78:5C:87:8C:B2:5D:AA:43:52:9B:E0:DF:EF:0B:73:93:4D:BE:01:7A`

### Appending Play App Signing Key Fingerprint

When deploying the app to Google Play with Google Play App Signing enabled, Google Play signs the downloaded APKs with a separate app signing key. To ensure App Links verify on user devices installing from Google Play, append the Play App Signing key fingerprint:

1. Open the [Google Play Console](https://play.google.com/console).
2. Select your app (`com.nexachat`).
3. Navigate to **Setup** -> **App signing**.
4. Under **App signing key certificate**, locate the **SHA-256** certificate fingerprint and copy the colon-separated hex string.
5. Append this fingerprint to the `sha256_cert_fingerprints` array in `site/.well-known/assetlinks.json`:
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
           "PLAY_APP_SIGNING_SHA256_FINGERPRINT_HERE"
         ]
       }
     }
   ]
   ```
6. Redeploy the site to Vercel:
   ```bash
   vercel deploy --prod --yes
   ```

### Security Policy: Never Add Debug Key

> **CRITICAL**: The debug key (`android/debug.keystore`) must **never** be added to `assetlinks.json`.
> The debug keystore uses public, well-known credentials (`androiddebugkey` / `android`). Adding it would allow any rogue debug build installed on an Android device to claim URL handling for `nexachat.io.vn`. Only genuine release credentials (the upload key and the Google Play App Signing key) may be listed.

## Account Deletion Web Flow (`delete-account.html`)

For users who uninstalled the app or lost access to their device, `site/delete-account.html` provides a self-service web deletion path:
1. User enters their registered E.164 phone number.
2. The page calls `POST /api/v1/auth/recovery/delete-account/request` to issue a 6-digit OTP to the verified recovery email.
3. User enters the OTP code and confirms deletion via `POST /api/v1/auth/recovery/delete-account/confirm`.
4. Logic is encapsulated in `site/delete-account.js` with zero external dependencies and strict CSP (`connect-src` limited to configured API origin, default empty).

## Vercel Build Skip (`ignoreCommand`)

In `site/vercel.json`, the deployment ignore command is configured as:
```json
"ignoreCommand": "bash -c 'p=$VERCEL_GIT_PREVIOUS_SHA; [ -n \"$p\" ] && git cat-file -e \"$p^{commit}\" 2>/dev/null || exit 1; git diff --quiet \"$p\" \"${VERCEL_GIT_COMMIT_SHA:-HEAD}\" -- . 2>/dev/null && exit 0; exit 1'"
```
This ensures multi-commit pushes do not mistakenly skip builds when changes touch `site/`, while skipping builds safely when only non-site files change. See [docs/operations/domain-and-app-links.md](../docs/operations/domain-and-app-links.md#8-vercel-build-skip-ignorecommand) for details.

## Site Validation & Testing

- **Static site checks:** `python site/check.py` validates HTML validity, link resolution, digital asset links JSON/fingerprints, security headers in `vercel.json`, token formats, and ignore-command execution.
- **JavaScript unit tests:** `cd tools/site-tests && npm test` runs headless Node tests verifying `site/delete-account.js` form validation, OTP state transitions, rate-limit handling, and URL parameter safety. See [tools/site-tests/README.md](../tools/site-tests/README.md).
