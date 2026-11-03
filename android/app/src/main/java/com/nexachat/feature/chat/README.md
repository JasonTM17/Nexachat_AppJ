# feature.chat — `package com.nexachat.feature.chat`

Planned contents (plan §12.1):

- `ChatScreen` — message list + composer, shared for direct and group conversations
- `ChatViewModel` — `ChatUiState(loading, messages, sending, input, replyTo, error)`;
  state = `combine(roomMessages, outbox, typingFlow, wsStatus).stateIn(WhileSubscribed)`;
  one-shot events via `Channel.receiveAsFlow` (plan §12.2)
