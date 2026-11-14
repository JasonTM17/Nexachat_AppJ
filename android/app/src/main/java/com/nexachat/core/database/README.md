# core.database — `package com.nexachat.core.database`

Contents:

- `AppDatabase` — Room database, the app's local source of truth (offline-first:
  UI renders Room streams; restart loses nothing); `DatabaseModule` provides it through Hilt
- Entities — `ConversationEntity`, `MessageEntity`, `MessageReactionEntity`,
  `PinnedMessageEntity`, `UserEntity`, `CallLogEntity`, `OutboxEntity`,
  `OfflineOutboxEntity`, `SyncCursorEntity`
- DAOs — `ConversationDao`, `MessageDao`, `ReactionDao`, `PinDao`, `UserDao`, `CallLogDao`,
  `OutboxDao` (optimistic-send queue, `OutboxState` SENDING/FAILED), `OfflineOutboxDao`
  (leased offline queue), `SyncCursorDao` (delta-sync checkpoints)
