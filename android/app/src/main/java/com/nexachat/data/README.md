# data — `package com.nexachat.data`

Planned contents (plan §12.1): repository implementations bridging
`core/network` + `core/database` into flows consumed by ViewModels.

- `AuthRepository` — login/register, token lifecycle
- `UserRepository` — user profile data
- `ContactRepository` — contacts sync, search, add-by-phone
- `ConversationRepository` — inbox conversation list
- `MessageRepository` — WS + REST + Room merge; send pipeline
  (`OutboxDao.insert(SENDING)` → STOMP SEND → ack via `clientMessageId` → SENT;
  REST fallback; retry on reconnect)
- `AttachmentRepository` — media upload/download via storage endpoints
