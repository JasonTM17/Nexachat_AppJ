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
