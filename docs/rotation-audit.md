# Rotation Audit — NexaChat Android

Walks every destination for state loss on rotation (config change) and
process death. Facts: every screen is a Java fragment backed by a Hilt
`ViewModel` and `LiveData`, so anything held in the ViewModel survives
rotation. `SavedStateHandle`, plus `Fragment.onSaveInstanceState`, is what
also survives process death. Message lists are `RecyclerView` `ListAdapter`s
bound from `layout/` and `layout-land/` (chat uses
`layout/fragment_chat.xml` and `layout-land/fragment_chat.xml`). A
`RecyclerView` with an id saves its `LinearLayoutManager` anchor through the
view hierarchy, on rotation and on process death.

## Screen-by-screen

| Screen | Draft / scroll / list risk | Status |
| --- | --- | --- |
| Splash | No user state; one-shot nav events only | ✅ correct as-is |
| Login | `phone`/`password`/`displayName` in `AuthViewModel.UiState` (`LiveData`) → rotation-safe; password deliberately *not* persisted to the handle | ✅ correct as-is |
| Register | Same ViewModel fields, plus `confirm` / `confirmChecked` written in `RegisterFragment.onSaveInstanceState` (survives process death too) | ✅ correct as-is |
| Inbox | Room via RxJava plus one `refresh()` per ViewModel → no refetch on rotation; `RecyclerView` id state saves scroll; error/empty/loading are shared state views | ✅ correct as-is (UI polish only) |
| Contacts | `aliasInput` is in `ContactsViewModel` UiState → rotation-safe; the add-by-phone dialog is built in `ContactsFragment` and is not retained, so rotation and process death drop an open draft (minor, accepted) | ✅ correct as-is |
| UserSearch | `query` and results in the ViewModel; `query` is `SavedStateHandle` key `search_query` → survives process death; results re-fetch on cold start (server data, cheap) | ✅ **fixed** (query → handle) |
| Chat | Messages and outbox from Room → no refetch, no loss; composer text is `SavedStateHandle` key `chat_input` → survives process death; `messageList` `RecyclerView` saves scroll; media picker is a transient sheet | ✅ **fixed** (draft → handle) |
| CreateGroup | `title` / selected ids / contacts in the ViewModel → rotation-safe; process death resets the form (minor, accepted — transient form) | ✅ correct as-is |
| GroupDetail | Members, title, and `renameInput` in UiState → rotation-safe; the rename dialog is transient, so process death drops an in-flight dialog (accepted) | ✅ correct as-is |
| MediaViewer | `MediaViewerFragment` loads images with Glide into an `ImageView` (`android:scaleType="fitCenter"`); there is no pinch-zoom model to restore. A failed download shows `ErrorStateView` and calls `MediaViewerViewModel.reload()` | ✅ **fixed** (retry) |
| Profile | `nameInput` / `editingName` / `saving` in the ViewModel → rotation-safe; language row shows the current pick (recreated after apply → never stale) | ✅ correct as-is (+ new entry) |
| LanguageSettings (new) | Selection lives in the framework (API 33+) / SharedPreferences (below 33) → survives everything | ✅ new, correct |

## Gaps fixed

1. `ChatViewModel` composer text → `SavedStateHandle` key `chat_input`, read in
   the constructor and written from `onInputChange`. The screen observes
   `LiveData<UiState>` (rotation-safe in the ViewModel; process-death-safe
   because the handle persists the string).
2. `ContactsViewModel.onQueryChange` → `SavedStateHandle` key `search_query`,
   restored when the ViewModel is created.
3. `MediaViewerFragment` failed download → `ErrorStateView` retry calls
   `MediaViewerViewModel.reload()`. Images use Glide and `fitCenter`; zoom is
   not a saved field.

## Accepted (documented, not fixed)

- Process-death resets: create-group form, contact detail/add sheets, group
  rename/remove/leave dialogs, media-picker sheet — all transient flows where a
  restart re-opens cleanly from Room/network state.
- Search *results* are re-fetched after process death (only the query draft is
  restored); the rate-limited endpoint would burn a call to do better.
- Scroll position after process death restores the `LinearLayoutManager` anchor
  saved by the `RecyclerView`. The saved anchor includes that row's pixel
  offset; it is the view hierarchy state, not a separate list holder.

## Device rotation matrix (2026-11-05, emulator Android 15 / API 35)

Evidence: device verification report `deepseek-device.md` (final audit round),
item h. Reproduce: open a chat, then rotate during each state.

| State rotated | Result | Status |
| --- | --- | --- |
| In-chat search (query + results) | Query and results survive rotation | ✅ pass |
| Message action sheet | Sheet survives rotation (id-based saveable state) | ✅ pass — D5 fixed (`s3-02-sheet-rotated-landscape-dark-vi.png`) |
| Edit mode | Edit banner, snippet and draft survive rotation without crash | ✅ pass — D1 fixed (`s3-03-edit-rotated-portrait-dark-vi.png`) |

D1 and D5 were closed by the Round 3 device pass (`reports/agy-feat3.md`):
the action sheet survives rotation to landscape and edit mode no longer
crashes. The chat screen is `ChatFragment` (`feature/chat/ChatFragment.java`)
over `layout/fragment_chat.xml` and `layout-land/fragment_chat.xml`. Id-based
lookup for a message that should still be editable, or still open an action
sheet, is `MessageResolver.resolveForEdit` / `resolveForActionSheet`
(`core/ui/MessageResolver.java`), covered by `MessageResolverTest`. The device
report records the mutation proof.

## Typing indicator (verified, no changes)

- Outbound: `ChatViewModel.onInputChange` → `session.sendTyping` → STOMP SEND
  `/app/conversations/{id}/typing` `{}`, throttled to ≤1 per 3 s while input
  is non-blank — wired to input changes, not send.
- Inbound: typing frames → `MessageRepository.getTypingEvents()` →
  `ChatViewModel.onTypingEvent` → `peerTyping` on `LiveData` for a 5 s RxJava
  timer (self-echo and other-conversation events filtered) →
  `getString(R.string.chat_typing)` in `ChatFragment`. Covered by existing
  JUnit 4 tests.
