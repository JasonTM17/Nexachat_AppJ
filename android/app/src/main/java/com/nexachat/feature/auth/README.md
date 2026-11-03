# feature.auth — `package com.nexachat.feature.auth`

Planned contents (plan §12.1):

- `LoginScreen` — phone + password login (E.164 input via `PhoneNormalizer`)
- `RegisterScreen` — account creation
- `AuthViewModel` — auth state machine; on session expiry (`SessionManager.logout`)
  the nav host returns here
