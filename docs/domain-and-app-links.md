# Domain and Android App Links Configuration

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
  - Debug manifest has no HTTPS intent filter so local developers are not tied to the owned domain.
  - Share and copy actions default to `nexachat://join/<token>`.
  - Deep link resource `invite_https_deep_link_pattern` remains empty.

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
