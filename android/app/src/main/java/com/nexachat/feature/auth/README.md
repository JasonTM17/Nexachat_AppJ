# feature.auth — `package com.nexachat.feature.auth`

Contents:

- `LoginFragment` — phone + password login (E.164 input via `PhoneNormalizer`) and
  Google sign-in when the build has a web client ID
- `RegisterFragment` — account creation, with `AuthFieldValidators` and `PasswordPolicyValidator`
- `ForgotPasswordFragment` / `ForgotPasswordViewModel` — password reset by email OTP
- `CompleteProfileFragment` / `CompleteProfileViewModel` — one-shot display name after
  Google account creation
- `AuthViewModel` — shared auth state; `AuthNav` replaces the auth root after success
- `SplashViewModel` — session restore at launch; routes to the inbox or back to login
  (with a session-expired notice when needed)
