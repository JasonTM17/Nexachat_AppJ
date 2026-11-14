# core.storage — `package com.nexachat.core.storage`

Contents:

- `TokenStore` — access/refresh token storage in DataStore, encrypted with an
  AndroidKeyStore AES-GCM key (`AesGcmTokenCipher` behind `TokenCipher`); tokens are
  never in plain SharedPreferences, and `allowBackup=false` in the manifest
- `Tokens`, `SessionSnapshot`, `PendingRefresh` — decrypted tokens, durable session
  revision/lifecycle, and the in-flight refresh rotation
- `RecallWindowConfig` — session-scoped message recall settings from the server
- `StorageModule` / `StorageBindingsModule` — Hilt providers for the token DataStore and cipher
