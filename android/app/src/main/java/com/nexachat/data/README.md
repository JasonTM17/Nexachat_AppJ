# data — `package com.nexachat.data`

Repository implementations bridging `core/network` + `core/database` for ViewModels.
Room is the render source; network results are written through it.

- `AuthRepository` — login/register, Google sign-in, token lifecycle via `TokenStore`
- `ContactsRepository` — contacts, user search, add-by-phone, direct conversations
- `ConversationRepository` — inbox conversation list and group management
- `MessageRepository` — WS + REST + Room merge; send pipeline
  (outbox row as SENDING → STOMP SEND → ack via `clientMessageId`; REST fallback uses the
  same ack path; rejections become FAILED, transport failures stay SENDING for retry)
- `AttachmentRepository` — multipart upload with progress and authenticated downloads
- `SyncEngine` / `SyncV2Consumer` / `SyncPageStore` / `MessageStateApplier` — delta sync
  from conversation watermarks, applied in Room transactions
- `OutboxSyncWorker` / `OfflineOutboxScheduler` / `OfflineOutboxModule` — WorkManager
  retries for queued sends and offline actions
- `PresenceRepository`, `SettingsRepository`, `RecoveryEmailRepository`,
  `PasswordResetRepository` — presence, theme and privacy settings, recovery-email and
  password-reset OTP APIs
- `AccountDataCleaner`, `LocalConversationCleaner`, `SessionOperationTracker` — local data
  cleanup and cancellation of account-scoped work at session boundaries
- Models such as `ChatMessage`, `ConversationSummary`, `Contact`, `GroupDetail`
