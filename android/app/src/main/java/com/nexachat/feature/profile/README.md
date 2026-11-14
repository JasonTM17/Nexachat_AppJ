# feature.profile — `package com.nexachat.feature.profile`

Contents:

- `ProfileFragment` / `ProfileViewModel` — display name, avatar, phone and settings
  switches; `AppSettingsViewModel` holds the theme mode
- `LanguageSettingsFragment` — en/vi switch via `AppLocaleManager`
- `LinkedAccountsViewModel` — link/unlink Google sign-in (hidden without a web client ID)
- `RecoveryEmail*` — add, verify by OTP and manage the recovery email
- `DeleteAccountFragment` / `DeleteAccountViewModel` / `AccountDeletionCoordinator` —
  account deletion and the local session wipe that follows
