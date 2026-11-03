# core.storage — `package com.nexachat.core.storage`

Planned contents (plan §12.1):

- `TokenStore` — access/refresh token storage backed by Keystore-AES + DataStore
  (tokens never in plain SharedPreferences; `allowBackup=false` in manifest)
- `PrefsDataStore` — non-secret app preferences via `datastore-preferences`
