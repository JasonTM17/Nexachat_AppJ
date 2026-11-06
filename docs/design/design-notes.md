# NexaChat Android Design System Notes

This document describes the design language, token definitions, component states, and Jetpack Compose mapping for NexaChat on Android, synthesized from the Stitch design system **Modern Material Messenger** (`projects/15384195107762462338`, asset `assets/a8ad41c7c78e4c359cfc3e40cc73dc08`).

---

## 1. Design Decisions & Review Justifications

### 1.1 Inbox Top Bar Icons (Contacts vs Profile)
- **Problem**: The top bar previously featured two near-identical person icons (one standard person icon and one inside a grey disc), causing visual ambiguity and confusion about their destinations.
- **Decision**: Replaced the contacts icon with `Icons.Filled.People` (representing the contacts directory / address book) and the profile icon with `Icons.Filled.AccountCircle` (representing user account/settings). Both icons use uniform 24dp sizing, standard top-bar tonal tinting, touch target padding >= 48dp, and explicit localized accessibility content descriptions (`R.string.contacts_title` and `R.string.profile_title`).

### 1.2 Group Detail Calm Role Chip & Destructive Actions
- **Problem**: An alarming red "Admin" pill and a red trash can icon appeared on every group member row, causing false danger affordances.
- **Decision**:
  - The "Admin" / "Quản trị viên" role badge was migrated to a calm Material 3 `Surface` chip using `MaterialTheme.colorScheme.secondaryContainer` background and `MaterialTheme.colorScheme.onSecondaryContainer` text, shaped with `RoundedCornerShape(6.dp)` and 2dp vertical padding.
  - Member deletion trash icons are tinted neutrally with `MaterialTheme.colorScheme.onSurfaceVariant`, reserving destructive red exclusively for "Leave group" / "Rời khỏi nhóm" and confirmation alert dialogs.

### 1.3 Deterministic Avatar Palette & Initials Fallback
- **Problem**: Image loading failures or missing profile pictures previously defaulted to blank solid blobs without initials, leading to unidentifiable contacts.
- **Decision**:
  - Authoritative `Avatar` component (`com.nexachat.core.ui.components.Avatar.kt`) implements deterministic 8-hue tonal palettes calculated via `name.hashCode().absoluteValue % 8`.
  - Hues are curated to guarantee contrast ratio >= 4.5:1 against initials text in both light and dark themes (unit tested in `DesignSystemUiRulesTest`).
  - Initials extraction (`extractInitials`) handles multi-word names (taking first letters of the first and last word, uppercase), single-word names, and defaults to "?" for blank strings.
  - Powered by Coil's `SubcomposeAsyncImage` with graceful fallback to deterministic initials on loading, error, or null URLs.

---

## 2. Design Tokens

### 2.1 Spacing (`NexaSpacing`)
Defined in `com.nexachat.core.ui.theme.Spacing.kt` based on a strict 8pt grid baseline:
- `xxs`: `2.dp` (hairline spacing, badge offsets)
- `xs`: `4.dp` (consecutive message bubble vertical spacing)
- `sm`: `8.dp` (icon paddings, standard list item vertical separation)
- `md`: `16.dp` (screen horizontal margins, standard content padding)
- `lg`: `24.dp` (section spacing, dialog padding)
- `xl`: `32.dp` (hero element offsets, empty state illustrations)
- `xxl`: `48.dp` (minimum touch target boundary)

### 2.2 Elevation (`NexaElevation`)
Defined in `com.nexachat.core.ui.theme.Elevation.kt`:
- `level0`: `0.dp` (flat surface canvas)
- `level1`: `1.dp` (cards, top bars, docked input containers)
- `level2`: `3.dp` (menus, dropdowns)
- `level3`: `6.dp` (floating action buttons, modal bottom sheets)
- `level4`: `8.dp` (dialogs)
- `level5`: `12.dp` (prominent overlays)

### 2.3 Shapes (`NexaShapes`)
Defined in `com.nexachat.core.ui.theme.Shape.kt`:
- `fabShape`: `RoundedCornerShape(16.dp)` (modern squircle for FABs)
- `composerPill`: `RoundedCornerShape(24.dp)` (pill text field container for chat composer)
- `searchBarPill`: `RoundedCornerShape(28.dp)` (search bar container)
- `avatar`: `CircleShape`
- `bubbleSingle`: `RoundedCornerShape(16.dp)`
- `bubbleFirstSent`: `RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)`
- `bubbleMiddleSent`: `RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 4.dp)`
- `bubbleLastSent`: `RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)`
- `bubbleFirstReceived`: `RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)`
- `bubbleMiddleReceived`: `RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)`
- `bubbleLastReceived`: `RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)`

---

## 3. Shared Presentation Components

| Component | File Path | Purpose & Key Features |
|---|---|---|
| `Avatar` | `com/nexachat/core/ui/components/Avatar.kt` | Deterministic 8-hue avatar with initials fallback, Coil async loading, online badge support, accessibility semantics. |
| `ChatBubble` | `com/nexachat/core/ui/components/ChatBubble.kt` | Asymmetric corner shapes per group position, embedded reply quotes, clickable links (`ACTION_VIEW`, strictly http/https), inline timestamp & receipt status. |
| `SectionHeader` | `com/nexachat/core/ui/components/SectionHeader.kt` | All-caps bold sub-headers for grouped lists (Profile, Settings, Members). |
| `SettingsRow` | `com/nexachat/core/ui/components/SettingsRow.kt` | Interactive preference row with leading icon, chevron/value trailing affordance, destructive styling mode, >= 48dp touch target. |
| `EmptyState` | `com/nexachat/core/ui/components/EmptyState.kt` | Calm centered empty state supporting vector illustrations (`ic_empty_chat.xml`), single concise helper line. |
| `NexaMotion` | `com/nexachat/core/ui/components/Motion.kt` | Restrained animation specs (150ms fade/slide), respecting `LocalReduceMotion` and system animator duration scale. |
| `LinkUtils` | `com/nexachat/core/ui/components/LinkUtils.java` | Safe URI parsing using `java.net.URI`, strictly allowing `http`/`https` protocols while rejecting `file`, `javascript`, and `intent`. |

---

## 4. Screen-to-File Implementation Map

| Screen | Composable & Implementation | Key Changes in Feature 1 |
|---|---|---|
| **Inbox** | `feature/inbox/InboxScreen.kt` | Distinct People vs Account icons, relative times (`resolveRelativeTimeLabel`), unread pill badge, vector empty state illustration `ic_empty_chat`, 640dp tablet centering. |
| **Chat** | `feature/chat/ChatScreen.kt`, `ChatInteractions.kt` | Consecutive message grouping, asymmetric bubble shapes (`resolveBubbleShape`), localized date separators ("Hôm nay", "Hôm qua", "dd/MM"), pill composer, animated send button, scroll-to-bottom FAB with arrow icon. |
| **Contacts** | `feature/contacts/ContactsScreen.kt`, `ContactsComponents.kt` | Distinct deterministic avatars, clean user rows, add-by-phone sheet, contact details sheet with alias editing. |
| **Group Detail** | `feature/group/GroupDetailScreen.kt` | Calm tonal Admin role chip (`secondaryContainer`), neutral member remove icon, red destructive leave action, 640dp centered width. |
| **Profile** | `feature/profile/ProfileScreen.kt` | Grouped settings sections ("Account", "Session"), 80dp avatar with edit badge, `SettingsRow` with leading icons and chevrons, destructive sign-out rows. |
| **Login & Register** | `feature/auth/LoginScreen.kt`, `RegisterScreen.kt`, `AuthFields.kt` | Brand vertical gradient background, password visibility eye toggle icon (`Icons.Filled.Visibility` / `VisibilityOff`), `imePadding()`, centered 440dp width. |
| **Search** | `feature/contacts/UserSearchScreen.kt` | Search bar with pill shape (`NexaShapes.searchBarPill`), instant debounce search results with deterministic avatars. |
| **Language Settings** | `feature/profile/LanguageSettingsScreen.kt` | Radio selection for System Default, Tiếng Việt, and English, instant locale updating. |

---

## 5. Before vs. After Screen Comparison

| Screen | Before Polish | After Polish |
|---|---|---|
| **Inbox** | Two identical person icons on top bar; raw timestamp format; unstyled list rows; missing empty illustration. | Distinct Contacts (`Icons.Filled.People`) vs Profile (`Icons.Filled.AccountCircle`) icons; relative timestamps ("Vừa xong", "18:51", "Hôm qua"); bold unread preview with pill badge; custom vector `ic_empty_chat`. |
| **Chat** | Monolithic identical rectangles for all bubbles; missing date headers; time overlapping bubble edge; square composer. | Grouped bubbles with position-dependent asymmetric corners (single/first/middle/last); localized date separators; inline receipts; pill composer (`composerPill`) with animated send button. |
| **Group Detail** | Alarming red "Admin" pill and red trash can icons on every row reading as constant danger. | Calm tonal `secondaryContainer` chip for Admin; subtle `onSurfaceVariant` trash icon; destructive red reserved for "Leave Group" and confirmation dialogs. |
| **Profile** | Unstyled flat card list; plain avatar; basic unaligned action buttons. | Grouped card sections ("Account", "Session") with `SectionHeader`; 80dp avatar with edit badge; unified `SettingsRow` items with icons and chevrons. |
| **Auth** | Flat white screen; text button labeled "Show" for password; keyboard occluding submit buttons. | Subtle vertical brand gradient; standard eye toggle icon with `Icons.Filled.Visibility` / `VisibilityOff`; `imePadding()` and IME actions Next/Done. |
| **Avatars** | Solid color circle with no initials on error/placeholder. | Deterministic 8-hue palette (WCAG contrast >= 4.5:1) with extracted 1-2 letter initials fallback on null/loading/error. |

---

## 6. Stage 2 feature decisions (F1–F6)

| Feature | Decision | Why |
|---|---|---|
| F1 Action sheet | Material 3 `ModalBottomSheet` on long-press; 6 quick reactions plus Reply / Copy / Forward / Pin / Edit / Delete; touch targets ≥ 48dp | Every interaction is one gesture away; Edit/Delete are gated by `MessageUiRules.canEdit/canDelete` |
| F2 Edit | Composer switches to edit mode with a banner showing the original snippet; checkmark submits; `editedAt` renders the "Đã chỉnh sửa" micro-label | Reuses the existing composer; the label is honest about mutation |
| F3 Delete | Confirmation dialog → soft delete → italic tombstone "Tin nhắn đã bị xoá"; all interactions suppressed | Soft delete preserves FK/reply integrity; the tombstone stays truthful in history |
| F4 In-chat search | Top bar morphs into an autofocus field; `SearchHighlightHelper` folds case and diacritics; tapping a result jumps with a 1.2 s pulse | Search is scoped to the open conversation; a jump needs a visible target |
| F5 Forward | Bottom-sheet conversation picker; `ForwardFilter` restricts the action to live `TEXT`; success snackbar "Đã chuyển tiếp" | Text-only keeps the action honest — attachments are not copied |
| F6 Presence & settings | Direct-chat header + inbox online dot; `PresenceFormatter`; Profile Appearance (SYSTEM/LIGHT/DARK) and Privacy (`showPresence`) switch | Presence is one-way and DIRECT-only; theme is local, presence is server-backed |

## 7. Stitch attribution (honest)

The Stitch design system **Modern Material Messenger**
(`projects/15384195107762462338`, asset `assets/a8ad41c7c78e4c359cfc3e40cc73dc08`)
supplied layout references and the Material 3 token direction. Stitch-generated
layouts were **converted by hand** to Jetpack Compose; nothing is an automatic
code export. Stitch screens referenced under `docs/design/stitch/screens/`:

| Stitch screen | Converted to | Notes |
|---|---|---|
| `active_chat` | `feature/chat/ChatScreen.kt` | Compose `ChatBubble` + asymmetric grouping |
| `message_action_sheet` | `feature/chat/ChatScreen.kt` | M3 `ModalBottomSheet` |
| `edit_message_mode` | `feature/chat/ChatScreen.kt` | Composer banner + checkmark submit |
| `in_chat_search` | `feature/chat/ChatScreen.kt` | Morphing top bar + `SearchHighlightHelper` |
| `forward_message_sheet` | `feature/chat/ChatScreen.kt` | `ForwardConversationSheet` + `ForwardFilter` |
| `theme_privacy_settings` | `feature/profile/ProfileScreen.kt` | Appearance + Privacy sections |
| `contacts_search` | `feature/contacts/UserSearchScreen.kt` | Debounced results |
| `group_detail` | `feature/group/GroupDetailScreen.kt` | Calm tonal role chip |
| `inbox` | `feature/inbox/InboxScreen.kt` | Distinct top-bar icons |
| `login_register` | `feature/auth/LoginScreen.kt`, `RegisterScreen.kt` | Brand gradient, eye toggle |
| `media_viewer` | `feature/media/MediaViewerScreen.kt` | Zoom/pan |
| `profile_settings` | `feature/profile/ProfileScreen.kt` | Grouped sections |

Pure-logic helpers added for these features are Java (project language rule):
`PresenceFormatter`, `SearchHighlightHelper`, `ThemeMapper`, `ForwardFilter`,
and `MessageUiRules`; the Composables that call them stay Kotlin.

---

## 8. Conversation Catch-up AI Summary (Phase 6)

Stitch project: `projects/15384195107762462338` (Modern Material Messenger)
Design system asset: `assets/a8ad41c7c78e4c359cfc3e40cc73dc08`

| Screen ID | Stitch Artifact | Hand-Coded Composable / Java Helper | Notes & Deliberate Deviations |
|---|---|---|---|
| `279a9f06271348349e14721dd8d021d1` | `summary_ready.{html,png}` | `feature/summary/SummaryBottomSheet.kt`, `UnreadBanner.kt`, `ScopeLabel.java`, `SummaryTextFormatter.java` | Unread banner with dismissal memory; M3 `ModalBottomSheet` with scope chips, overview, key points, decisions, action items, questions, and topics. Citation chips close sheet and jump to message with pulse highlight. Stitch mock used inline tags; Compose implementation uses accessible interactive chips with >=48dp touch targets and disabled state when `CitationResolver` returns unavailable. |
| `dd3d9a9a537f4870a8550866e9a06ebc` | `summary_loading_skeleton.{html,png}` | `feature/summary/SummarySkeleton.kt` | Animated shimmer skeleton placeholders for overview, list items, and action chips; reduced-motion safe via `NexaMotion` / `LocalReduceMotion`. |
| `86bfe206298d4deea5065280af5831c6` | `summary_error_states.{html,png}` | `feature/summary/SummaryErrorViews.kt`, `SummaryErrorMapper.java` | Typed error rendering: `TOO_FEW` ("Chưa đủ tin nhắn · Cần tối thiểu 5 tin nhắn để tạo tóm tắt"), `RATE_LIMITED` with countdown hint, `NETWORK` ("Không thể kết nối máy chủ" + "Thử lại" action), `NOT_MEMBER`, and `UNKNOWN`. |
| `f9918e0ec2a44d45b0a57d5a782d67f1` | `summary_settings_consent.{html,png}` | `feature/profile/ProfileScreen.kt`, `AiConsentRepository.java` | Settings Privacy row with switch bound to `AiConsentRepository` (optimistic update + rollback on error), required disclosure ("Tin nhắn được ẩn thông tin nhạy cảm rồi gửi tới dịch vụ AI"), and first-use confirmation dialog. |
