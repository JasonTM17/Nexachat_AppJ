# core.database — `package com.nexachat.core.database`

Contents:

- `AppDatabase` — Room database, the app's local source of truth (offline-first:
  UI renders Room streams; restart loses nothing); `DatabaseModule` provides it through Hilt
- `DatabaseEncryption` / `DatabasePassphraseStore` — SQLCipher at-rest encryption (audit
  F-01): the DB is opened via `SupportFactory` with a random 32-byte raw key sealed by the
  existing AndroidKeyStore AES/GCM `TokenCipher`; a legacy plaintext `nexachat.db` is
  exported into an encrypted copy (row counts verified) before the plaintext file is
  replaced. Room schema version is unchanged — a cipher change is not a schema change.
- Entities — `ConversationEntity`, `MessageEntity`, `MessageReactionEntity`,
  `PinnedMessageEntity`, `UserEntity`, `CallLogEntity`, `OutboxEntity`,
  `OfflineOutboxEntity`, `SyncCursorEntity`
- DAOs — `ConversationDao`, `MessageDao`, `ReactionDao`, `PinDao`, `UserDao`, `CallLogDao`,
  `OutboxDao` (optimistic-send queue, `OutboxState` SENDING/FAILED), `OfflineOutboxDao`
  (leased offline queue), `SyncCursorDao` (delta-sync checkpoints)
