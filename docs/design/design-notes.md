# NexaChat Android Design System Notes

This document describes the design language, token definitions, component states, and XML View / Fragment mapping for NexaChat on Android (Java 21, ViewBinding, Material 3), synthesized from the design system **Modern Material Messenger**.

---

## 1. Design Decisions & Review Justifications

### 1.1 Inbox Top Bar Icons (Contacts vs Profile)
- **Problem**: The top bar previously featured two near-identical person icons (one standard person icon and one inside a grey disc), causing visual ambiguity and confusion about their destinations.
- **Decision**: Replaced the contacts action with `@drawable/ic_people` (contacts directory / address book) and the profile action with `@drawable/ic_account_circle` (account/settings). Both use uniform 24dp sizing, `?attr/colorControlNormal` tinting, `@dimen/min_touch_target` padding, and explicit localized accessibility content descriptions (`R.string.contacts_title` and `R.string.profile_title`).

### 1.2 Group Detail Calm Role Chip & Destructive Actions
- **Problem**: An alarming red "Admin" pill and a red trash can icon appeared on every group member row, causing false danger affordances.
- **Decision**:
  - The "Admin" / "Quản trị viên" role badge uses a calm Material 3 chip with `@color/m3_secondary_container` background and `@color/m3_on_secondary_container` text, shaped with `@dimen/corner_sm` corners and modest vertical padding.
  - Member removal icons are tinted neutrally with `@color/m3_on_surface_variant`, reserving destructive red exclusively for "Leave group" / "Rời khỏi nhóm" and confirmation alert dialogs.

### 1.3 Deterministic Avatar Palette & Initials Fallback
- **Problem**: Image loading failures or missing profile pictures previously defaulted to blank solid blobs without initials, leading to unidentifiable contacts.
- **Decision**:
  - Authoritative `AvatarView` (`com.nexachat.core.ui.components.AvatarView.java`) implements deterministic 8-hue tonal palettes calculated via `Math.abs(name.hashCode()) % 8`.
  - Hues are curated to guarantee contrast ratio >= 4.5:1 against initials text in both light and dark themes (unit tested in `DesignSystemUiRulesTest`).
  - Initials extraction (`MessageUiRules.extractInitials`) handles multi-word names (taking first letters of the first and last word, uppercase), single-word names, and defaults to "?" for blank strings.
  - Glide loads remote URLs with graceful fallback to deterministic initials on loading, error, or null URLs.

---

## 2. Design Tokens

Token values live in `res/values/colors_nexachat.xml`, `res/values/themes.xml`, and `res/values/dimens_nexachat.xml`, aligned with Material 3 roles from the design palette.

### 2.1 Spacing (`dimens_nexachat.xml`)
Strict 8pt grid baseline:
- `space_xs`: `4dp` (grouped message rows use `2dp` at each adjoining edge; separate turns use `4dp` at each edge)
- `space_sm`: `8dp` (icon paddings, standard list item vertical separation)
- `space_md`: `16dp` (screen horizontal margins, standard content padding)
- `space_lg`: `24dp` (section spacing, dialog padding)
- `space_xl`: `32dp` (hero element offsets, empty state illustrations)
- `min_touch_target`: `48dp` (minimum touch target boundary)

### 2.2 Elevation (`dimens_nexachat.xml`)
- `elevation_level1`: `1dp` (cards, top bars, docked input containers)
- `elevation_level2`: `3dp` (menus, dropdowns)
- `elevation_level3`: `6dp` (floating action buttons, modal bottom sheets)
- `elevation_level4`: `12dp` (dialogs, prominent overlays)

### 2.3 Shapes (dimens + bubble drawables)
- FAB / cards: `corner_lg` (`16dp`)
- Chat input pill: `corner_xl` (`24dp`)
- Search bar pill: `28dp` in layout styles where used
- Avatars: circular (`GradientDrawable.OVAL` in `AvatarView`)
- Message bubbles: `18dp` main corners with a `4dp` tail on single/last rows; the tail follows sender direction and layout RTL.

---

## 3. Shared Presentation Components

| Component | File Path | Purpose & Key Features |
|---|---|---|
| `AvatarView` | `com/nexachat/core/ui/components/AvatarView.java` | Deterministic 8-hue avatar with initials fallback, Glide async loading, online badge support, accessibility content descriptions. |
| Message rows | `feature/chat/adapter/MessageBubbleBinder.java`, `res/layout/item_message_*.xml` | Sender/date/system-aware grouping, 78% maximum row width, RTL-aware bubble tails, embedded reply quotes, media timestamp scrims, reactions, clickable links (`ACTION_VIEW`, strictly http/https via `LinkUtils`), inline receipts. |
| `SectionHeaderView` | `com/nexachat/core/ui/components/SectionHeaderView.java` | All-caps bold sub-headers for grouped lists (Profile, Settings, Members). |
| `SettingsRowView` | `com/nexachat/core/ui/components/SettingsRowView.java` | Interactive preference row with leading icon, chevron/value trailing affordance, destructive styling mode, >= 48dp touch target. |
| `EmptyStateView` | `com/nexachat/core/ui/components/EmptyStateView.java` | Calm centered empty state supporting vector illustrations (`ic_empty_chat.xml`), single concise helper line. |
| `LoadingStateView` / `ErrorStateView` | `com/nexachat/core/ui/components/LoadingStateView.java`, `ErrorStateView.java` | Reusable loading and error placeholders for feature screens and sheets. |
| `LinkUtils` | `com/nexachat/core/ui/components/LinkUtils.java` | Safe URI parsing using `java.net.URI`, strictly allowing `http`/`https` protocols while rejecting `file`, `javascript`, and `intent`. |

Animations stay restrained (roughly 150ms fades/slides) and respect the system animator duration scale / reduce-motion accessibility settings.

---

## 4. Screen-to-File Implementation Map

| Screen | Fragment & Implementation | Key Changes in Feature 1 |
|---|---|---|
| **Inbox** | `feature/inbox/InboxFragment.java` | Distinct People vs Account icons, relative times (`ChatUiRules.resolveRelativeTimeLabel`), unread pill badge, vector empty state illustration `ic_empty_chat`, `@dimen/max_content_width` tablet centering. |
| **Chat** | `feature/chat/ChatFragment.java`, `ChatInteractionsViews.java` | Consecutive message grouping, asymmetric bubble shapes (`ChatUiRules` / `ChatBubbleRules`), localized date separators ("Hôm nay", "Hôm qua", "dd/MM"), pill-shaped input field, animated send affordance, scroll-to-bottom FAB. |
| **Contacts** | `feature/contacts/ContactsFragment.java` | Distinct deterministic avatars, clean user rows, add-by-phone bottom sheet, contact details sheet with alias editing. |
| **Group Detail** | `feature/group/GroupDetailFragment.java` | Calm tonal Admin role chip (`m3_secondary_container`), neutral member remove icon, red destructive leave action, `@dimen/max_content_width` centered width. |
| **Profile** | `feature/profile/ProfileFragment.java` | Grouped settings sections ("Account", "Session"), 80dp avatar with edit badge, `SettingsRowView` with leading icons and chevrons, destructive sign-out rows. |
| **Login & Register** | `feature/auth/LoginFragment.java`, `RegisterFragment.java` | Brand vertical gradient background, password visibility toggle (`@drawable/ic_visibility` / `ic_visibility_off`), `android:windowSoftInputMode` / IME-friendly layout, `@dimen/auth_form_max_width` centering. |
| **Search** | `feature/contacts/UserSearchFragment.java` | Search bar with pill shape, RxJava-debounced search results with deterministic avatars. |
| **Language Settings** | `feature/profile/LanguageSettingsFragment.java` | Radio selection for System Default, Tiếng Việt, and English, instant locale updating via `LocaleManager`. |

---

## 5. Before vs. After Screen Comparison

| Screen | Before Polish | After Polish |
|---|---|---|
| **Inbox** | Two identical person icons on top bar; raw timestamp format; unstyled list rows; missing empty illustration. | Distinct Contacts (`ic_people`) vs Profile (`ic_account_circle`) icons; relative timestamps ("Vừa xong", "18:51", "Hôm qua"); bold unread preview with pill badge; custom vector `ic_empty_chat`. |
| **Chat** | Monolithic identical rectangles for all bubbles; missing date headers; time overlapping bubble edge; square input field. | 78%-width message rows with sender/date/system-aware grouping, 18dp corners and a 4dp tail, localized date separators, legible inline receipts, image/video time scrims, and a floating rounded composer with a 150ms send/mic cross-fade. |
| **Group Detail** | Alarming red "Admin" pill and red trash can icons on every row reading as constant danger. | Calm tonal `m3_secondary_container` chip for Admin; subtle `m3_on_surface_variant` trash icon; destructive red reserved for "Leave Group" and confirmation dialogs. |
| **Profile** | Unstyled flat card list; plain avatar; basic unaligned action buttons. | Grouped card sections ("Account", "Session") with `SectionHeaderView`; 80dp avatar with edit badge; unified `SettingsRowView` items with icons and chevrons. |
| **Auth** | Flat white screen; text button labeled "Show" for password; keyboard occluding submit buttons. | Subtle vertical brand gradient; standard eye toggle drawables; IME actions Next/Done and soft-input resize. |
| **Avatars** | Solid color circle with no initials on error/placeholder. | Deterministic 8-hue palette (WCAG contrast >= 4.5:1) with extracted 1-2 letter initials fallback on null/loading/error. |

---

## 6. Stage 2 feature decisions (F1–F6)

| Feature | Decision | Why |
|---|---|---|
| F1 Action sheet | Material `BottomSheetDialog` on long-press; 6 quick reactions plus Reply / Copy / Forward / Pin / Edit / Delete; touch targets ≥ 48dp | Every interaction is one gesture away; Edit/Delete are gated by `MessageUiRules.canEdit/canDelete` |
| F2 Edit | Chat input bar switches to edit mode with a banner showing the original snippet; checkmark submits; `editedAt` renders the "Đã chỉnh sửa" micro-label | Reuses the existing input bar; the label is honest about mutation |
| F3 Delete | Confirmation dialog → soft delete → italic tombstone "Tin nhắn đã bị xoá"; all interactions suppressed | Soft delete preserves FK/reply integrity; the tombstone stays truthful in history |
| F4 In-chat search | Top bar morphs into an autofocus field; `SearchHighlightHelper` folds case and diacritics; tapping a result jumps with a 1.2 s pulse | Search is scoped to the open conversation; a jump needs a visible target |
| F5 Forward | Bottom-sheet conversation picker; `ForwardFilter` restricts the action to live `TEXT`; success snackbar "Đã chuyển tiếp" | Text-only keeps the action honest — attachments are not copied |
| F6 Presence & settings | Direct-chat header + inbox online dot; `PresenceFormatter`; Profile Appearance (SYSTEM/LIGHT/DARK) and Privacy (`showPresence`) switch | Presence is one-way and DIRECT-only; theme is local, presence is server-backed |

## 7. Design System Attribution

The design system **Modern Material Messenger**
supplied layout references and the Material 3 token direction. Reference
layouts were **converted by hand** to XML layouts, custom Views, and Fragments; nothing is an automatic
code export. Reference screens:

| Design screen | Converted to | Notes |
|---|---|---|
| `active_chat` | `feature/chat/ChatFragment.java` | `MessageBubbleBinder` + asymmetric grouping |
| `message_action_sheet` | `feature/chat/ChatFragment.java`, `ChatInteractionsViews.java` | Material `BottomSheetDialog` |
| `edit_message_mode` | `feature/chat/ChatFragment.java` | Input-bar edit banner + checkmark submit |
| `in_chat_search` | `feature/chat/ChatFragment.java` | Morphing top bar + `SearchHighlightHelper` |
| `forward_message_sheet` | `feature/chat/ChatFragment.java` | Forward conversation sheet + `ForwardFilter` |
| `theme_privacy_settings` | `feature/profile/ProfileFragment.java` | Appearance + Privacy sections |
| `contacts_search` | `feature/contacts/UserSearchFragment.java` | Debounced results (RxJava 3) |
| `group_detail` | `feature/group/GroupDetailFragment.java` | Calm tonal role chip |
| `inbox` | `feature/inbox/InboxFragment.java` | Distinct top-bar icons |
| `login_register` | `feature/auth/LoginFragment.java`, `RegisterFragment.java` | Brand gradient, eye toggle |
| `media_viewer` | `feature/media/MediaViewerFragment.java` | Zoom/pan |
| `profile_settings` | `feature/profile/ProfileFragment.java` | Grouped sections |

Presentation rules and helpers are pure Java throughout: `PresenceFormatter`, `SearchHighlightHelper`, `ThemeMapper`, `ForwardFilter`, `MessageUiRules`, `ChatUiRules`, and `ChatBubbleRules`; Fragments and ViewBinders consume them via LiveData + RxJava 3.

## 10. Chat Screen Refresh Wave

The refreshed chat screen uses the v2 semantic roles and the bundled Manrope/Inter type styles. Normal bubbles are capped at `min(280dp, 80% of the row)`; at font scale 1.5 or larger the cap becomes `min(480dp, row width - 32dp)`. Bubbles use 16dp corners, with a 4dp lower-directional tail only on a single message or the last message of a run. Grouped row edges contribute 2dp each (4dp between messages); separate turns contribute 4dp each. Media timestamps use the opaque media-meta pair. Reply quotes sit on the primary container. The composer field uses the 24dp extra-large radius and a six-line input. Send and microphone actions cross-fade over 150ms with the standard easing and follow the system reduced-motion setting. Search, group info, and summary stay in the existing overflow menu; call stays on the bar for direct chats.

---

## 8. Conversation Catch-up AI Summary (Phase 6)

| Screen ID | Reference Artifact | Hand-Coded Fragment / Java Helper | Notes & Deliberate Deviations |
|---|---|---|---|
| `279a9f06271348349e14721dd8d021d1` | `summary_ready.{html,png}` | `feature/summary/SummaryBottomSheetFragment.java`, `UnreadBannerView.java`, `ScopeLabel.java`, `SummaryTextFormatter.java` | Unread banner with dismissal memory; Material bottom sheet with scope chips, overview, key points, decisions, action items, questions, and topics. Citation chips close sheet and jump to message with pulse highlight. Mock used inline tags; Java implementation uses accessible interactive chips with >=48dp touch targets and disabled state when `CitationResolver` returns unavailable. |
| `dd3d9a9a537f4870a8550866e9a06ebc` | `summary_loading_skeleton.{html,png}` | `res/layout/include_summary_skeleton.xml` (in `bottom_sheet_summary.xml`) | Shimmer skeleton placeholders for overview, list items, and action chips; reduced-motion safe via system animator / accessibility settings. |
| `86bfe206298d4deea5065280af5831c6` | `summary_error_states.{html,png}` | `SummaryErrorViewHelper.java`, `ErrorStateView.java`, `SummaryErrorMapper.java` | Typed error rendering: `TOO_FEW` ("Chưa đủ tin nhắn · Cần tối thiểu 5 tin nhắn để tạo tóm tắt"), `RATE_LIMITED` with countdown hint, `NETWORK` ("Không thể kết nối máy chủ" + "Thử lại" action), `NOT_MEMBER`, and `UNKNOWN`. |
| `f9918e0ec2a44d45b0a57d5a782d67f1` | `summary_settings_consent.{html,png}` | `feature/profile/ProfileFragment.java`, `AiConsentRepository.java` | Settings Privacy row with switch bound to `AiConsentRepository` (optimistic update + rollback on error), required disclosure ("Tin nhắn được ẩn thông tin nhạy cảm rồi gửi tới dịch vụ AI"), and first-use confirmation dialog. |

---

## 9. Current visual system

The shipped Android palette is the v2 semantic system in `android/app/src/main/res/values/colors_nexachat.xml` and `values-night/colors_nexachat.xml`. Night mode is a color overlay on `Theme.NexaChat.Views`; there is no separate night theme, type, or shape file. Historical Midnight Obsidian / Electric Violet hex values are not the current palette.

Inbox keeps its filter chips, the active-now strip, and the new-chat action. There is no four-tab bottom navigation bar. Directory search stays “Find people” / “Tìm người”. In-chat search, conversation info, and summary are overflow actions on the chat toolbar. The chat header avatar is 36dp and hides at font scale 1.5 or when the window is narrower than 360dp.

Copy, Share, and Regenerate on the summary sheet sit in a footer outside the scrolling body. Settings rows and group member names grow with large type. The add-member sheet adds `max(IME, system bars)` once on top of its base padding.

P1 surfaces that received layout changes are inbox, chat and composer, settings rows, group detail and add-member, and the summary sheet. Other screens inherit the tokens. The launcher and in-app mark are the Note monogram: `#733F68` in light and `#DDA7CC` in dark. The adaptive plate stays `#733F68`.

Manrope and Inter are already bundled. API 26–27 type styles use the AppCompat line height and a bold fallback for weights 600 and 700. API 28 and newer set `android:lineHeight` and `android:textFontWeight` from `values-v28/type.xml`. Contrast pairs are checked on the JVM from `design-v2-contrast-pairs.json` against the resource XML. That check does not measure a device, TalkBack, or a font cmap.

Maintained notes for this system live in this file. Token names and roles are the Android resources above, not an external research checkout.
