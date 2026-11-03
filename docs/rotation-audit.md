# Rotation Audit — NexaChat Android (phase-10 P8)

Walks every destination for state loss on rotation (config change) and
process death. Facts: every screen renders a Hilt ViewModel, so anything in a
VM survives rotation for free; `SavedStateHandle`/`rememberSaveable` is the
extra mile for process death. `rememberLazyListState` (explicit or the
LazyColumn default) saves first-visible-index + offset through both —
verified, no hoisting gaps.

## Screen-by-screen

| Screen | Draft / scroll / list risk | Status |
| --- | --- | --- |
| Splash | No user state; one-shot nav events only | ✅ correct as-is |
| Login | `phone`/`password`/`displayName` in `AuthViewModel.UiState` → rotation-safe; password deliberately *not* persisted to the handle | ✅ correct as-is |
| Register | Same + `confirmPassword`/`confirmMismatch` via `rememberSaveable` (survives process death too) | ✅ correct as-is |
| Inbox | Room `inboxFlow` + one `refresh()` per VM → no refetch on rotation; LazyColumn default state saves scroll; error/empty/loading now shared states | ✅ correct as-is (UI polish only) |
| Contacts | `addPhone`/`aliasInput`/detail sheets in UiState → rotation-safe; `showAddSheet` is `rememberSaveable`; process-death drops an open sheet's draft (minor, accepted) | ✅ correct as-is |
| UserSearch | `query`+results in VM; `query` now SavedStateHandle → survives process death; results re-fetch on cold start (server data, cheap) | ✅ **fixed** (query → handle) |
| Chat | Messages/outbox from Room → no refetch, no loss; `input` was VM-only → now `SavedStateHandle["chat_input"]` → survives process death; `listState` hoisted via `rememberLazyListState` → scroll saved; `showMediaPicker` saveable | ✅ **fixed** (draft → handle) |
| CreateGroup | `title`/`selectedIds`/contacts in VM → rotation-safe; process-death resets the form (minor, accepted — transient form) | ✅ correct as-is |
| GroupDetail | Members/title/`renameInput`/pending dialogs all in UiState → even dialogs survive rotation; process-death drops in-flight dialog/draft (accepted) | ✅ correct as-is |
| MediaViewer | Zoom `scale`/`offset` were `remember` → lost on rotation → now `rememberSaveable` (two floats — `Offset` isn't Bundle-able); failed download now offers Retry instead of a dead end | ✅ **fixed** |
| Profile | `nameInput`/`editingName`/`saving` in VM → rotation-safe; language row shows current pick (recreated after apply → never stale) | ✅ correct as-is (+ new entry) |
| LanguageSettings (new) | Selection lives in framework (33+) / SharedPreferences (<33) → survives everything | ✅ new, correct |

## Gaps fixed

1. `ChatViewModel.input` → `savedStateHandle.getStateFlow("chat_input", "")`
   (was plain MutableStateFlow — rotation-safe but process-death-lost).
2. `ContactsViewModel` query → `SavedStateHandle["search_query"]` write-through
   with restore at init.
3. `MediaViewerScreen` zoom/pan → `rememberSaveable` floats.

## Accepted (documented, not fixed)

- Process-death resets: create-group form, contact detail/add sheets, group
  rename/remove/leave dialogs, media-picker sheet — all transient flows where a
  restart re-opens cleanly from Room/network state.
- Search *results* are re-fetched after process death (only the query draft is
  restored); the rate-limited endpoint would burn a call to do better.
- Scroll position after process death restores first-item index/offset — the
  pixel-perfect offset inside a tall bubble is not kept (Compose limitation).

## Typing indicator (verified, no changes)

- Outbound: `ChatViewModel.onInputChange` → `session.sendTyping` → STOMP SEND
  `/app/conversations/{id}/typing` `{}`, throttled to ≤1 per 3 s while input
  is non-blank — wired to input changes, not send.
- Inbound: `USER_TYPING` → `MessageRepository.typingEvents` → `observeTyping`
  → `peerTyping` for a 5 s TTL (self-echo and other-conversation events
  filtered) → `chat_typing` line in ChatScreen. Covered by existing tests.
