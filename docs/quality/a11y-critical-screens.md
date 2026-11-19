# A11y critical-screen inventory (TalkBack expectations + oracle seams)

Status: **PROPOSED** — pending product-owner acceptance, like the tiering
proposal in [accessibility-inventory.md](accessibility-inventory.md). The
`accessibility-sweep` device journey stays `BLOCKED_CAPABILITY` until the
inventory is accepted and a TalkBack / clipped-text oracle exists; this doc is
the inventory half of that gap. It is deeper than the tiering doc: it pins
per-screen focus-order expectations, real view/resource ids, expected
accessible names, contrast targets, and what a resource-id-based on-device
oracle can assert.

Everything below was verified by reading the source at
`android/app/src/main/res/navigation/nav_main.xml`,
`android/app/src/main/AndroidManifest.xml` and the cited layouts/values/Java
on the branch this doc was written on. No id or string key is cited unless it
was found in the source; string values are quoted from
`values/strings*.xml` (EN) or `values-vi/` (VI) where verified.

## 1. Screen enumeration (verified)

`nav_main.xml` is a flat Navigation graph rooted at `splashFragment`; calls
are separate activities, not nav destinations. Verified destinations:

| Where verified | Screens |
| --- | --- |
| `nav_main.xml` fragments | Splash, Login, Register, CompleteProfile, ForgotPassword, Inbox, Contacts, UserSearch, Profile, LanguageSettings, RecoveryEmailManage/Form/Otp, DeleteAccount, Chat, Archive, BlockedUsers, StarredMessages, PublicFeed, PostCompose, PostDetail, GroupBoard, Encryption, GroupCreate, GroupDetail, InviteManage, InviteJoin, MediaViewer |
| `AndroidManifest.xml` activities | MainActivity (launcher), NexaHostActivity, CallActivity, IncomingCallActivity, NotificationOpenActivity (translucent) |
| Bottom sheets and action sheets (not nav destinations) | Summary sheet (`bottom_sheet_summary.xml`, `SummaryBottomSheetFragment`), Ask sheet (`bottom_sheet_ask_chat.xml`, `AskBottomSheetFragment`), message action sheet (`sheet_message_actions.xml`), attachment picker, reminders, forward/language/report dialogs |

Splash and the translucent `NotificationOpenActivity` are transient
(no interactive surface) and out of scope here; the full tier list lives in
[accessibility-inventory.md](accessibility-inventory.md). The eleven screens
below are the swipe/TalkBack-critical core: the four journey gates (login,
inbox, chat conversation, incoming call) plus contacts, the two group
surfaces, settings, the two conversation AI sheets, and the call screen.

## 2. Shared expectations (apply to every screen)

- **Focus order** — TalkBack's default swipe order approximates the layout
  tree top-to-bottom, left-to-right. Each screen section lists the expected
  traversal in that order; ids are quoted from the named layout.
- **Decoration** — purely visual views must carry
  `android:importantForAccessibility="no"`. Today this holds for logos, rings,
  avatars, label texts duplicated by a sibling `contentDescription`, and both
  call-scene video renderers (see each table).
- **Merged bubbles** — message/inbox rows are clickable containers whose
  non-actionable children merge into one focus target announcing the row
  content (e.g. `item_inbox_conversation.xml`, `item_message_text_*.xml`).
- **Touch targets** — interactive controls declare
  `@dimen/min_touch_target` = **48dp** (`values/dimens_nexachat.xml:10`); the
  static rule A2 (`tools/checks/check-android-a11y.py`) enforces the declared
  floor.
- **Live regions** — async status text uses
  `android:accessibilityLiveRegion="polite"` (login `errorBanner`,
  inbox `inboxRefreshErrorMessage`, chat `chatError`, profile error rows,
  call `call_status`/`call_timer`, incoming `incoming_status`, summary/ask
  status lines).
- **Headings** — screen titles use `android:accessibilityHeading="true"`
  (login `authTitle`, inbox `inboxBrandTitle`) so callers can navigate by
  heading; the expected TalkBack text is the same as the visual title.

## 3. Contrast targets

Text on themed surfaces is expressed as Material role pairs, all of which
route through the app theme (Tonal Plum light/dark). Non-text contrast uses
the WCAG 2.2 §1.4.11 gate.

| Element (id → pair) | Target | Measured |
| --- | --- | --- |
| Body/secondary text: `?attr/colorOnSurface` / `?attr/colorOnSurfaceVariant` on `?attr/colorSurface*` backgrounds | ≥ 4.5:1 (text) | theme-borne; re-measure on any token change |
| Disabled + error text: `?attr/colorOnErrorContainer` on error containers (`errorBanner`, `chatError`, `profileError`, delete-account row) | ≥ 4.5:1 | theme-borne |
| Icon glyphs: `?attr/colorOnSurfaceVariant` on `colorSurface` | ≥ 3:1 (non-text) | theme-borne |
| Inbox FAB: `brand_on_fill` `#FFFCFF` on `stitch_fab_fill` `#FF733F68` (light) / `#FF67355D` (dark) — `colors_brand_fill.xml`, `colors_nexachat.xml:41` | ≥ 3:1 | **7.81:1** light, **9.19:1** dark |
| Unread badge text `brand_on_fill` on `bg_unread_badge` drawable | ≥ 4.5:1 (small text) | verify via oracle on-device |

The **call scene has a fixed dark palette in both themes**
(`colors_call.xml`, header comment + `values-night/colors_call.xml`
identical), over the `nexa_call_surface` `#FF1D1920` →
`nexa_call_surface_end` `#FF302333` gradient (`drawable/bg_call_gradient.xml`).
Measured with the WCAG relative-luminance formula:

| Pair | Target | Measured |
| --- | --- | --- |
| `call_text_primary` on gradient start / end (peer name, timer) | ≥ 4.5:1 | 17.01 / 14.58 |
| `call_text_secondary` on gradient start / end (status, muted control labels) | ≥ 4.5:1 | 11.16 / 9.57 |
| `call_icon_on_control` on `call_control_background` `#514655` | ≥ 3:1 | 8.75 |
| `call_icon_on_control` on `call_decline_background` / `call_end_background` `#B3261E` | ≥ 3:1 | 6.42 |
| `call_icon_on_control` on `call_accept_background` `#246B57` | ≥ 3:1 | 6.22 |
| `call_action_decline_text` / `call_action_accept_text` labels on gradient end | ≥ 4.5:1 | 8.75 / 8.78 |
| `call_avatar_foreground` on `call_avatar_background` (initials) | ≥ 3:1 (large text) | 7.81 |

The decorative under-button labels
(`incoming_decline_label`, `incoming_accept_label`, `call_mute_label`,
`call_speaker_label`, `call_hang_up_label`, `call_camera_label`,
`call_camera_switch_label`) are all
`android:importantForAccessibility="no"`; the button's
`android:contentDescription` is the announced name.

## 4. Critical screens

### 4.1 Login — `LoginFragment` (`fragment_login.xml`, nav `loginFragment`)

Expected TalkBack order: `authTitle` (heading "NexaChat") →
`authModeLogin` ("Sign in", checked) / `authModeRegister` ("Create account",
not checked — both `android:checkable="true"`, and the container
`authModeSwitch` is the segmented control) → `errorBanner` / `noticeBanner`
(live region, announced when shown, focused when visible) → `googleSignIn`
("Continue with Google", hidden entirely when no web client id is compiled
in) → `phoneInput` → `passwordInput` + the password-visibility end icon →
`forgotPasswordAction` → `primaryAction`.

| id | Type | Expected accessible name (source) |
| --- | --- | --- |
| `authTitle` | TextView | app name (text `@string/app_name`); heading role |
| `authModeLogin` / `authModeRegister` | MaterialButton (checkable) | "Sign in" / "Create account" (`action_sign_in` / `action_create_account`, `strings.xml:27-28`) + checked state |
| `errorBanner` | TextView, live region | error text at runtime; announced automatically |
| `phoneLayout` / `phoneInput` | TextInputLayout + EditText | "Email or phone number" (hint `auth_login_identifier_label`, `strings.xml:8`); autofillHints `username` |
| `passwordLayout` / `passwordInput` | TextInputLayout + EditText | "Password" (hint `auth_password_label`) |
| *(end icon of `passwordLayout`)* | TextInputLayout end icon | "Toggle password visibility" (`app:endIconContentDescription=@string/cd_password_visibility`, `strings.xml:304`) |
| `forgotPasswordAction` | MaterialButton | "Forgot password?" (`action_forgot_password`) |
| `googleSignIn` | MaterialButton | "Continue with Google" (`cd_continue_with_google`, `strings.xml:724`) |
| `primaryAction` | MaterialButton | "Sign in" (+ trailing arrow icon; icon inherits the button's name) |
| `authLogo`, or-divider art | decoration | nothing (`importantForAccessibility="no"`) |
| `loadingState` | fullscreen overlay | covers content while submitting (blocks traversal) |

Sign-in-mode state must be carried by the checkable state of
`authModeLogin`/`authModeRegister`, not color alone.

### 4.2 Inbox — `InboxFragment` (`fragment_inbox.xml` + `item_inbox_conversation.xml`)

Expected order: `inboxBrandTitle` heading → `inboxPublicButton` →
`inboxContactsButton` → `inboxUserAvatar` → `moreButton` → `inboxSearchPill`
→ "Active now" header + `activeNowViewAll` + peer chips on
`activeNowStrip` → filter chips (`filterAllChip`…) → label chips → error
banner row (when shown) → `conversationList` rows → `createGroupFab` as the
natural next node after the last row (bottom-end FAB).

| id | Type | Expected accessible name (source) |
| --- | --- | --- |
| `inboxPublicButton` | ImageButton | "Public feed" (`feed_entry_cd`, `strings_feed.xml:4`) |
| `inboxContactsButton` | ImageButton | "Contacts" (`contacts_title`) |
| `inboxUserAvatar` | AvatarView (clickable) | "Profile" (`profile_title`) |
| `moreButton` | ImageButton | "More options" (`inbox_more_options`) |
| `inboxSearchPill` | clickable MaterialCardView | whole pill = "Find people" (`inbox_search_hint`); inner icon/text are `importantForAccessibility="no"` so the pill is a single focus target |
| `activeNowViewAll` | TextView (clickable) | "View all" (`inbox_view_all`) |
| `filterAllChip` / `filterUnreadChip` / `filterGroupsChip` / `filterArchivedChip` | Chip (single-selection, `selectionRequired`) | "All" / "Unread" / "Groups" / "Archived" (chip texts) + selected state; active chip label is bolded |
| `conversationList` row | ConstraintLayout (clickable, minHeight 72dp) | merged row: title, time, preview, unread count, and conditional icon descriptions below |
| row `pinIndicator` | ImageView (gone unless pinned) | "Pinned conversation" (`cd_pinned_conversation`) |
| row `muteIcon` | ImageView (gone unless muted) | "Notifications muted" (`inbox_muted`) |
| `inboxRefreshErrorMessage` | TextView, live region | refresh-failure text |
| `inboxRefreshRetry` | MaterialButton | "Retry" (`action_retry`) |
| `createGroupFab` | FloatingActionButton | "New chat" (`inbox_new_chat`) |
| `inboxBrandMark`, row `avatar`, `previewTypeIcon`, `letterBubble`-style glyphs | decoration | nothing (`importantForAccessibility="no"`) |

### 4.3 Chat conversation — `ChatFragment` (`fragment_chat.xml`, `include_chat_composer_bar.xml`, message-row items)

Expected order: toolbar back navigation ("Back", `cd_back`) → toolbar title
(peer/group name) → `e2eeLock` / `disappearingChip` (when applicable) →
`callButton` → `videoCallButton` → `moreButton` → pinned banner include (when
shown) → `messageList` bubbles (recent first at the bottom in reading order;
each bubble row is one merged target, sender/body/meta/reactions together) →
`scrollDownFab` (only when scrolled up) → unread banner (when present) →
search bar (only open) → `replyEditBanner` include (only while
replying/editing) → `attachButton` → `messageInput` → `micButton`
*or* `sendButton` (they swap in the same `composerActionSlot`) → recording
overlay replaces the input while recording.

| id | Type | Expected accessible name (source) |
| --- | --- | --- |
| toolbar (`chatToolbar`) | MaterialToolbar | "Back" (`app:navigationContentDescription=@string/cd_back`) + conversation title |
| `e2eeLock` | ImageView (gone unless E2EE) | "End-to-end encrypted" (`cd_e2ee_lock`) |
| `disappearingChip` | ImageView (gone unless timer) | "Disappearing message" (`cd_disappearing_bubble`) |
| `callButton` / `videoCallButton` | ImageButton | "Call" / "Video call" (`cd_call` / `cd_video_call`) |
| `moreButton` | ImageButton | "Message actions" (`a11y_open_chat_actions`) |
| `scrollDownFab` | FloatingActionButton | "Scroll to bottom" (`cd_scroll_to_bottom`) |
| `chatSearchInput` | TextInputEditText | "Search in conversation…" (hint `search_in_chat_placeholder`) |
| `closeChatSearch` | ImageButton | "Clear search" (`cd_clear_search`) |
| `unblockPeerButton` | MaterialButton | "Unblock" (`unblock_user`) — banner text *You blocked this contact* (`blocked_composer_banner`) is a sibling plain text |
| `chatErrorMessage` | TextView (in live-region `chatError`) | send-failure text |
| `chatErrorRetry` | MaterialButton | "Retry" (`action_retry`) |
| `attachButton` | ImageButton | "Attach" (`cd_attach`) |
| `messageInput` | TextInputEditText | "Message" (hint `chat_input_hint`); **runtime swap to "Encrypted message"** (`chat_input_hint_encrypted`) in E2EE conversations (`ChatFragment.java:1342`) |
| `micButton` | Send-style image button | "Record voice message" (`cd_record_voice`); second tap sends the recording |
| `sendButton` | Send-style image button | "Send" (`cd_send`) — **runtime swap to "Save changes" (`action_save_changes`) while editing** (`ChatComposerEditPresentation.java:19-25`); disabled with an empty draft |
| `recordingOverlay` | pill replacing the field while recording | elapsed duration (`voice_recording_duration_zero` at start) + "Slide to cancel" (`voice_slide_to_cancel`); fragment announces recording state changes |

Message rows: the text bubble (`item_message_text_incoming.xml`,
`item_message_text_outgoing.xml`) merges `senderName`, `messageBody`, reply
quote, translation block, meta row and reaction tiles into one target. Several
bubble parts get names **only at bind time** — the oracle must assert runtime
values, not static XML (see §6): attachment images and media play buttons use
`cd_open_attachment`, "Open attachment" (`cd_open_attachment`); voice players
(`voicePlayButton`, waveform) name play/pause and waveform state at bind time
(`VoiceBubbleBinder.java:302-303`); delivery-state icons and failed-row
retries set their descriptions in `MessageBubbleBinder.java`. Long-press opens
the action sheet `sheet_message_actions.xml`: title "Message actions"
(`messageActionsTitle`), reaction tile strip, then rows
(`item_message_action_row.xml`) announced as `actionLabel` +
`actionCaption` (the icon is decoration).

### 4.4 Incoming call — `IncomingCallActivity` (`activity_incoming_call.xml`)

Manifest-launched (full-screen intent, `excludeFromRecents`), never in the
nav graph. Expected order: `incoming_peer_name` → `incoming_status` (live
region; announces "Incoming call" / "Incoming video call" as it changes) →
`incoming_decline` → `incoming_accept` (two-column bottom row; Decline left,
Accept right).

| id | Type | Expected accessible name (source) |
| --- | --- | --- |
| `incoming_peer_name` | TextView | caller display name (string set at runtime) |
| `incoming_status` | TextView, live region | default text "Incoming call" (`call_state_incoming`); updates announced |
| `incoming_decline` | MaterialButton (icon-only, `call_end_size`) | "Decline" (`contentDescription=@string/call_decline`) |
| `incoming_accept` | MaterialButton (icon-only, `call_end_size`) | "Accept" (`contentDescription=@string/call_accept`) |
| `incoming_pulse_ring`, `incoming_initials`, both `*_label` texts | decoration | nothing (`importantForAccessibility="no"`) |

TalkBack users must still be able to answer: the two buttons are the only
focus targets below the name, and neither has a duplicate sibling stealing
focus.

### 4.5 Contacts — `ContactsFragment` (`fragment_contacts.xml`, `view_settings_row.xml`)

Expected order: toolbar back ("Back") → `searchPill` → `addContactEntry` →
`createGroupEntry` → contact rows (section headers announce section letters;
the letter rail and bubble are decoration).

| id | Type | Expected accessible name (source) |
| --- | --- | --- |
| `searchPill` | clickable LinearLayout | "Find people" (`inbox_search_hint`; same key as the inbox pill) |
| `addContactEntry` | `SettingsRowView` (clickable band) | row title bound in code; children collapse to title (+subtitle) because `rowIcon`/`rowChevron` are `importantForAccessibility="no"` (`view_settings_row.xml:26,71`) |
| `createGroupEntry` | `SettingsRowView` | row title bound in code (e.g. "Create group" route label) |
| `contactList` row | item layout (clickable) | contact display name + alias/subtitle, merged |
| `letterRail`, `letterBubble` | custom rail + bubble | decoration (`importantForAccessibility="no"` on the bubble); the rail's own announcement is **unverified — flag for the TalkBack pass** |
| `errorState` / `emptyState` | state views | fill per `view_error_state.xml`/`view_empty_state.xml` (contract-tested) |

Exact entry-row titles are set in `ContactsFragment` (not from the layout);
the oracle should assert by id + non-empty content-describe merged text, not
by literal, until those bindings are pinned.

### 4.6 Group create — `CreateGroupFragment` (`fragment_create_group.xml`)

Expected order: toolbar back → `groupNameInput` → `membersHeader` →
`memberPickerList` rows (toggle to include a contact) → `createButton` last.
State overlays (`loadingState`/`errorState`/`emptyState`) block content
while active.

| id | Type | Expected accessible name (source) |
| --- | --- | --- |
| `groupNameLayout` / `groupNameInput` | TextInputLayout + EditText | "Group name" (hint `group_name_label`) |
| `membersHeader` | SectionHeaderView | bound section title ("Selected members" area label; set in code) |
| `memberPickerList` row | contact picker item | contact name + selected state (place picker rows carry their own toggle/click semantics) |
| `createButton` | MaterialButton | "Create group" (`action_create_group`) |
| toolbar | MaterialToolbar | "Back" (`cd_back`) |

### 4.7 Group detail — `GroupDetailFragment` (`fragment_group_detail.xml`)

Expected order: toolbar back → `groupAvatar` (+ `avatarEditBadge`, which is
decoration and not independently focusable — **flag: the avatar edit affordance
must be reachable by TalkBack when it is active for admins**) → `groupTitle` →
`groupSubtitle` → quick-action row: `addMemberButton` (when member) and
`muteTile` → member card: `membersHeader`, `memberList` rows, `inviteLinkButton`
(admin only) → `leaveGroupButton`.

| id | Type | Expected accessible name (source) |
| --- | --- | --- |
| `addMemberButton` | MaterialCardView (clickable) | "Add member" (`contentDescription=@string/action_add_member`; the visible tile text is the same string, and the tile icon is decoration) |
| `muteTile` | MaterialCardView (clickable) | visible text "Mute notifications" (child `muteText`=text `mute_menu_title`); **no explicit state announcement** — flag: muted/unmuted must be conveyed (icon swap alone is not enough) |
| `memberList` row | member item | member name + role caption (admin chip), merged |
| `inviteLinkButton` | Tonal MaterialButton (gone for non-admins) | "Invite link" (`invite_manage_action`) |
| `leaveGroupButton` | Destructive MaterialButton | "Leave group" (`action_leave_group`) — destructive color must not drop below error-container contrast |
| toolbar | MaterialToolbar | "Back" (`cd_back`) |

### 4.8 Settings — `ProfileFragment` (`fragment_profile.xml`, `view_settings_row.xml`)

This screen hosts all settings: profile card, appearance, privacy, streak,
notifications, account, session, and the destructive delete row. Expected
order: toolbar back → avatar card: `profileAvatar` / `cameraBadge` →
`profileName` → `profilePhone` → `editName` (or the edit-mode
`nameInput` + `cancelName`/`saveName` pair) → appearance card: `languageRow` →
`themeRow` → the three `themeChip*` chips → `chatColorRow` → eight color
swatches → privacy card: `aiConsentRow` w/ `aiSummaryConsent`,
`showPresenceRow` w/ `showPresence`, `showSeenRow` w/ `showSeen`,
`starredMessagesRow`, `blockedUsersRow` → streak: `streakRow` →
notifications: `notificationRow`, `streakRemindersRow` w/ `streakReminders`
→ account: `recoveryEmailRow`, `googleAccountRow` (conditional),
`signInMethodPhoneRow`, `changePassword` (+ `signInMethodsError` live
region) → session: `logoutRow`, `logoutAllRow` → delete row last:
`deleteAccountRow` → `profileError` (live region).

| id | Type | Expected accessible name (source) |
| --- | --- | --- |
| `cameraBadge` | ImageButton (48dp) | "Change avatar" (`profile_change_avatar`) |
| `editName` | Text button | "Edit name" (`cd_edit_name`) |
| `nameInput` | TextInputEditText (edit mode) | "Name" — hint `auth_display_name_label` |
| `languageRow` / `themeRow` / `chatColorRow` | `SettingsRowView` | row titles bound in code; `rowValue` (e.g. current language, theme name) is part of the merged announcement |
| `themeChipSystem` / `themeChipLight` / `themeChipDark` | Chip (selected) | "System" / "Light" / "Dark" (`theme_system`/`theme_light`/`theme_dark`) + selected state |
| `chatColorSwatchPlum`…`chatColorSwatchSlate` | clickable FrameLayout ×8 | the color word as contentDescription (`chat_color_plum`, `chat_color_indigo`, `chat_color_blue`, `chat_color_teal`, `chat_color_green`, `chat_color_amber`, `chat_color_rose`, `chat_color_slate`); inner images are decoration — **flag: selected state is drawn, not announced; assert/fix selection announcement in the oracle pass** |
| `aiSummaryConsent` | MaterialSwitch | switch contentDescription "External AI summaries" (`settings_ai_summary_consent_title`) + on/off state; the sibling full-row `aiConsentRow` clicks the switch (`ProfileFragment.java:204`) |
| `showPresence` | MaterialSwitch | switch contentDescription using the row's title string ("Show activity status", `privacy_show_presence`) |
| `showSeen` | MaterialSwitch | "Show seen status" contentDescription (`cd_show_seen`) |
| `streakReminders` | MaterialSwitch | "Streak reminders switch" (`cd_streak_reminders`) |
| `starredMessagesRow`, `blockedUsersRow`, `recoveryEmailRow`, `signInMethodPhoneRow`, `changePassword`, `logoutRow`, `logoutAllRow` | `SettingsRowView` | row title (+optional `rowValue`), merged; titles bound in code |
| `deleteAccountRow` | `SettingsRowView` in an error-styled card | row title bound in code; card background is `colorErrorContainer` (contrast target: `onErrorContainer` ≥ 4.5:1) |
| `profileLoadError`, `signInMethodsError`, `profileError` | TextViews, live regions | error text; announced when shown |
| `profileLoadRetry` | Outlined button | "Retry" (`action_retry`) |

### 4.9 Summary sheet — `SummaryBottomSheetFragment` (`bottom_sheet_summary.xml`)

Not a nav destination; opens from the chat overflow. Expected order:
`summaryTitle` → `summaryClose` → `summaryModeChips` → consent card (only
before consent) → `summaryScopeChips` → `summaryLimitChips` (when a picker
applies) → result area → bottom action row.

| id | Type | Expected accessible name (source) |
| --- | --- | --- |
| `summaryTitle` | TextView | "Conversation summary" (`summary_title`, `strings_summary.xml:8`) |
| `summaryClose` | ImageButton | "Close summary" (`cd_summary_close`) |
| `summaryModeLocal` / `summaryModeAi` | Chip | "Local" / "AI" (`summary_badge_local` / `summary_badge_ai`) + selected state |
| `summaryConsentDismiss` / `summaryConsentConfirm` | MaterialButton | "Cancel" / "Agree" (`dialog_ai_consent_dismiss` / `dialog_ai_consent_confirm`) after the consent title+message text |
| `summaryScopeChips`, `summaryLimitChips` | dynamic ChipGroups | chips are added in code; assert non-empty merged text per chip |
| `summaryContent` (+ bullet/item includes) | result body | bullet rows and citation rows; citation chips announce their citation label |
| `summaryRegenerate` | icon-only MaterialButton (48dp) | "Regenerate" (`contentDescription=@string/summary_action_regenerate`) |
| `summaryCopy` / `summaryShare` | MaterialButton | "Copy" / "Share into chat" (`summary_action_copy` / `summary_action_share`) |
| `summaryError` include | error card | error text + "Retry" (`action_retry`) |

### 4.10 Ask sheet — `AskBottomSheetFragment` (`bottom_sheet_ask_chat.xml`)

Expected order: `askTitle` → `askClose` → status area (skeleton + live
`askLoadingLabel`, or `askStatusMessage` live region, or `askRetry`) →
`askAnswerCard` (`askModeBadge` then selectable `askAnswer` text) →
`askContextOmitted` (when trimmed) → `askCitationsLabel` → `askCitations`
citation rows → composer: `askQuestion` + `askSend` → `askCounter` (when the
500-char budget is shown) → `askCancel` (when offered).

| id | Type | Expected accessible name (source) |
| --- | --- | --- |
| `askTitle` | TextView | "Ask this chat" (`ask_title`) |
| `askClose` | ImageButton | "Close ask this chat" (`ask_close_accessibility`) |
| `askLoadingLabel` / `askStatusMessage` | TextViews, live regions | loading/status text (announced when changing) |
| `askRetry` | Text button | "Retry" (`ask_retry`) |
| `askAnswer` | selectable TextView | answer body (text-selectable semantics) |
| `askQuestionLayout` / `askQuestion` | TextInputLayout + EditText | "Question" (hint `ask_question_hint`; VI "Câu hỏi"; `maxLength=500`) |
| `askSend` | MaterialButton | "Ask" (`ask_send`) — disabled while loading (`AskBottomSheetFragment.java:138`) |
| `askCitations` rows | citation list | citation chips made of sender + excerpt, merged per row |

### 4.11 Call screen — `CallActivity` (`activity_call.xml`, `layout-land/activity_call.xml`)

Manifest-launched for in-call control. Expected order: `call_peer_name` →
`call_status` (live region; also re-announced via
`announceForAccessibility` on state transitions, `CallActivity.java:451`) →
`call_timer` (live region, once the call connects) → control row:
`call_mute`, `call_speaker`, `call_camera` + `call_camera_switch` (video
calls only), `call_hang_up` — labels below each button are decoration.

| id | Type | Expected accessible name (source) |
| --- | --- | --- |
| `call_peer_name` | TextView | peer display name (set at runtime) |
| `call_status` | TextView, live region | state title (bound from `CallUiModel`); announced on change |
| `call_timer` | TextView, live region | elapsed time, latest value announced |
| `call_mute` | icon-only MaterialButton | "Mute" (`call_mute`, `strings_call.xml:16`) — **flag: the label string is singular; a mute→unmute cd swap at runtime is not visible in the layout, verify on-device** |
| `call_speaker` | icon-only MaterialButton | "Speaker" (`call_speaker`) — same flag as mute |
| `call_camera` | icon-only MaterialButton (video calls) | "Camera off" (`call_camera_off`) — same flag; the toggled-on name must still be honest |
| `call_camera_switch` | icon-only MaterialButton (video calls) | "Switch camera" (`call_switch_camera`) |
| `call_hang_up` | icon-only MaterialButton (72dp, red) | "Hang up" / VI "Kết thúc" (`call_hang_up`, `strings_call.xml:20`; `values-vi/strings_call.xml:20`) |
| `call_remote_video`, `call_local_video`, `call_local_preview`, `call_pulse_ring`, `call_peer_initials`, all `*_label` texts | decoration | nothing (`importantForAccessibility="no"`) |

## 5. Focus-order risks pinned by this inventory

These are the existing behaviors an on-device pass must check first, because
the layouts alone cannot prove them:

1. failure live regions (login `errorBanner`, inbox refresh, chat
   `chatError`, profile rows) must be announced before the user retries;
2. the composer's mic/send swap must expose exactly one actionable control in
   the reading order;
3. editing a message must switch the send button's name to "Save changes"
   while the input still reads as the same field;
4. swipe-independent reachability for long-press-only chat actions: the
   overflow button (`a11y_open_chat_actions` → action sheet) must expose the
   same reactions/actions with captions;
5. switches that are toggled by a whole-row click (`aiConsentRow`) must not
   double-focus the row and the switch in a confusing order;
6. chat color swatches and mute/camera toggles must announce selection/state,
   since their visuals are non-textual.

## 6. Oracle seam: what a resource-id-based driver asserts on-device

Resource ids in this app resolve as `com.nexachat:id/<name>` in UI dumps
(e.g. `com.nexachat:id/sendButton` in a UiAutomator dump for
`@+id/sendButton`), which makes every field above addressable from a
UiAutomator/Appium driver without text matching. A uiautomator dump exposes
`text`, `content-desc`, `class`, `clickable`, `enabled`, `bounds` and the
not-accessible flag (`NAF`) per node — enough for all of the assertions
below. For each screen, assert:

- **Existence / visibility** per state: present-when-shown elements
  (`e2eeLock` gone in plaintext chats; `incoming_accept`/`incoming_decline`
  always present while ringing; overlays `loadingState`/`errorState` cover
  the screen when active).
- **Runtime contentDescription equality** for the dynamic names:
  - `sendButton` = "Send" normally, "Save changes" while editing
    (`ChatComposerEditPresentation.java:19-25`);
  - `messageInput` hint = "Message" vs "Encrypted message"
    (`ChatFragment.java:1342-1343`);
  - bubble parts named at bind time (`MessageBubbleBinder.java`, e.g.
    attachment/play/action rows, delivery icons, error rows;
    `VoiceBubbleBinder.java:302-303` for voice play/waveform);
  - call status/title re-announcement (`CallActivity.java:451`).
- **State carrying**: chips' selected state (inbox filters, theme chips,
  summary mode/scope), switches' on/off state (`aiSummaryConsent`,
  `showPresence`, `showSeen`, `streakReminders`), `messageInput` empty-draft
  disable state on `sendButton`.
- **Touch-target floors** from bounds: ≥ 48dp (`min_touch_target`) on
  icon buttons; `call_control_size`/`call_end_size` control buttons; 72dp
  inbox rows.
- **Focus-order traversal**: the dumped traversal sequence must match the
  per-screen expected order in §4 — this is the TalkBack-order oracle.
- **Live-region behavior** is a semantic property: assert the notification
  path instead (e.g. assert the fragment announces, or that the banner view
  is present and its text equals the failure string after forcing an error
  state).

Existing static seams the on-device driver complements (never replaces):

- `tools/checks/check-android-a11y.py` — static rules A1 (image buttons named
  or hidden), A2 (48dp floor), A3 (sp text sizes), WARN rules A4–A6, against
  the baseline in `tools/checks/android-a11y-baseline.json`;
- `tools/checks/check-android-strings.py` — EN/VI string parity, so a cited
  contentDescription exists in both locales;
- Android unit contract tests such as `A11yStringsParityUnitTest`,
  `LayoutAccessibilityUnitTest`, `MessageBubbleAccessibilityContractTest`,
  `ViewLayerAccessibilityContractTest`, `DesignV2LayoutContractTest`
  (pinned under `android/app/src/test/java/com/nexachat/res/`) and the
  layout-id parity tests that keep every id present in each qualified layout
  variant (e.g. `layout-land/activity_call.xml` keeps `call_hang_up`).

The oracle still needs the clipped-text detector and a focus-order capture
mechanism; those are tracked as separate open work in
[accessibility-inventory.md](accessibility-inventory.md#oracle-requirements-still-open).
