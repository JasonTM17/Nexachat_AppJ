# core.network — `package com.nexachat.core.network`

Planned contents (plan §12.1):

- `RetrofitModule` — Hilt module providing Retrofit/OkHttp/Moshi
- `AuthInterceptor` — attaches `Authorization: Bearer <access>` headers
- `TokenAuthenticator` — OkHttp `Authenticator` handling 401 → mutex-guarded
  single-flight token refresh → retry (plan §12.2)
- `StompClient` — STOMP-over-WebSocket client on OkHttp WS + frame codec (~200 lines)
- `dto/` — network DTOs (Moshi `@JsonClass`)
- `mappers/` — DTO ↔ domain/entity mappers
