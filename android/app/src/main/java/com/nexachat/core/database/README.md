# core.database — `package com.nexachat.core.database`

Planned contents (plan §12.1):

- `AppDatabase` — Room database, the app's local source of truth (offline-first,
  plan §12.2: UI renders Room flows; restart loses nothing)
- `entities/` — Conversation, Message, Outbox, User entities
- `dao/` — `ConversationDao`, `MessageDao`, `OutboxDao` (send-pipeline SENT/SENDING/FAILED states)
