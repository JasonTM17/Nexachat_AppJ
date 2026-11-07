# luna9 B3 report

## 1) Changed files

- `android/app/src/main/java/com/nexachat/feature/chat/ChatFragment.java` — Java RecyclerView chat surface, toolbar actions, Room-backed message rendering through `ChatViewModel` flows, paging, search, draft/send/edit, reaction/pin/reply/delete/forward interactions, upload progress, summary sheet view, and navigation callbacks.
- `android/app/src/main/java/com/nexachat/feature/chat/ChatInteractionsViews.java` — native reaction/action/forward/attachment dialogs and reply/pin banners.
- `android/app/src/main/java/com/nexachat/feature/chat/ChatErrorText.java` — Java resource mapper for `ApiError`.
- `android/app/src/main/res/layout/fragment_chat.xml` and `android/app/src/main/res/layout-land/fragment_chat.xml` — search bar, unread banner, error slot, and removable attachment progress rows.

The required report is kept inside this worktree at `worker-reports/luna9-B3.md` because the worker instruction restricts writes to this worktree; the shared `D:\ChatFaceHappy\plans\...` directory was not modified.

## 2) Public API contract produced (signatures, Rx types)

- `public final class ChatFragment extends androidx.fragment.app.Fragment implements MessageActionListener`.
- `public static ChatFragment newInstance(String conversationId)`.
- `public final class ChatErrorText` with `public static int messageId(ApiError error)`.
- `public final class ChatInteractionsViews`; helper entry points are static UI operations. No new Rx API is exported. Flow collection uses `kotlinx.coroutines.rx3.RxConvertKt.asFlowable(flow, EmptyCoroutineContext.INSTANCE)` and is disposed with a fragment-scoped `CompositeDisposable`.

## 3) Commands run + exact results

- `.\gradlew.bat :app:compileDebugKotlin :app:compileDebugJavaWithJavac` from `android` — **PASS**, `BUILD SUCCESSFUL in 18s`; one existing Hilt processor-options warning and Java deprecated-API note. This pass is with `@AndroidEntryPoint` omitted from `ChatFragment`.
- Same Gradle command with `@AndroidEntryPoint` on the Java Fragment — **FAIL** during `:app:kspDebugKotlin`: `[Hilt] @AndroidEntryPoint base class must extend ComponentActivity, (support) Fragment, View, Service, or BroadcastReceiver.` The annotation was removed so the slice can compile; without it, Hilt ViewModel construction in this Fragment is not established.
- `git diff --check` — **PASS** (no whitespace errors).
- `rg -n 'action_react|unread_summary_banner|include_chat_reaction_picker|dialog_forward_message' ...` — no matches; those two XML resources and localized labels were absent in the assigned base. No unit/UI tests or device smoke were run.

## 4) Cross-owner requests

- Resolve Java Fragment Hilt/KSP processing so the required `@AndroidEntryPoint` can be restored and `ChatViewModel`/`SummaryViewModel` can be created with Hilt. This is a runtime blocker.
- Wire this new fragment into the app's navigation/host path in the integration worktree; current host remains the existing Compose destination and is outside luna9 ownership.
- Add localized reaction and unread-summary banner labels in both `values/strings.xml` and `values-vi/strings.xml`. The reaction action currently uses an emoji label; the unread banner uses the existing summary label plus count.
- Provide a member-count field in chat/inbox state for the group toolbar subtitle. Current `ChatViewModel.UiState` does not expose it.
- The Compose `SummaryBottomSheet` has no Java Fragment counterpart. The new view surface requests the existing Java `SummaryViewModel` and displays overview/key points/action items in a native bottom sheet, but does not yet provide the Compose sheet's citation jump/share/consent controls.
- The adapter callback for file attachments currently shows the localized open failure; the `ChatViewModel.attachmentFile` API is suspend-only and needs a Kotlin/Rx bridge or Java-facing repository API to implement `ACTION_VIEW`.

## 5) Not done / risks

- `@AndroidEntryPoint` and working Hilt ViewModel construction are blocked by the observed KSP failure; compile PASS must not be treated as runtime PASS.
- The new fragment is not reachable from the current Compose navigation host until integration wires it.
- Search results are shown and selectable, and search pagination is wired to bottom scrolling; search-result text highlighting and explicit loading/empty-state rows remain absent.
- The summary sheet is a reduced native view; group member count and file opening are not implemented.
- Worker branch has not been pushed or merged. No CI, tests, emulator run, or app smoke was observed.
