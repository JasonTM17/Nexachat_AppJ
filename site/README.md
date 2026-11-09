# NexaChat Static Site & App Links Configuration

This directory contains the minimal static site for `nexachat.io.vn`, deployed on Vercel.

## Digital Asset Links (`.well-known/assetlinks.json`)

Android uses Digital Asset Links to verify App Links ownership for opening HTTPS invite links (`https://nexachat.io.vn/join/<token>`) directly in the NexaChat Android app.

Currently, `site/.well-known/assetlinks.json` serves an empty JSON list (`[]`).

### How to configure release App Links

To enable Android App Links in production:

1. Locate the Android application ID from `android/app/build.gradle`:
   - `applicationId`: `com.nexachat`

2. Obtain the release signing certificate SHA-256 fingerprint:
   - **Local Release Keystore**:
     ```bash
     keytool -list -v -keystore <path-to-release.keystore> -alias <release-key-alias>
     ```
     Locate the line labeled `Certificate fingerprints:` -> `SHA256:` and copy the colon-delimited uppercase hex string.
   - **Google Play App Signing** (if using Google Play):
     Open Google Play Console -> select app -> **Setup** -> **App integrity** -> **App signing key certificate** -> copy the **SHA-256 certificate fingerprint**.

3. **IMPORTANT SECURITY NOTICE**:
   > **Do NOT use the debug key** (`android/debug.keystore`) for production releases or production `assetlinks.json`. The debug key is publicly known and insecure. Only the genuine production release signing certificate fingerprint must be included.

4. Update `site/.well-known/assetlinks.json` with the following structure:
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
           "XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX"
         ]
       }
     }
   ]
   ```

5. Redeploy the site to Vercel:
   ```bash
   vercel deploy --prod --yes
   ```
