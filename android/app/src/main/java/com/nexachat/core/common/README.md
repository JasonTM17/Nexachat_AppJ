# core.common — `package com.nexachat.core.common`

Contents:

- `Result` / `ApiError` / `Unit` — typed repository outcome, the error vocabulary parsed
  from the server error envelope, and a non-null unit value for RxJava streams
- `PhoneNormalizer` — best-effort E.164 normalization via `libphonenumber` (default region VN)
- `AppLocaleManager` / `LanguageOption` — per-app en/vi language apply and persist
- `AppConfig` — compile-time configuration such as the API base URL
- `PersonContactSubtitle`, `WhitespaceTrim` — small text helpers for person rows and input trimming
