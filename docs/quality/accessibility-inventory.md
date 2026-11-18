# Accessibility critical-screen inventory

Status: **PROPOSED** — pending product-owner acceptance. The
`accessibility-sweep` device journey stays `BLOCKED_CAPABILITY` until this
inventory is accepted and a TalkBack / clipped-text oracle exists.

Source of truth: `android/app/src/main/res/navigation/nav_main.xml`. Screens
are grouped by reachability and user-criticality for the sweep matrix
(EN/VI × light/dark × font scale 100%/200%).

## Tier 1 — required for sweep (core journeys)

Every release-blocking user journey touches these. A clipped label or
inaccessible action here fails the row.

| Screen | Fragment | Critical actions |
| --- | --- | --- |
| Login | `LoginFragment` | phone field, OTP request, submit |
| Inbox | `InboxFragment` | conversation list, search, overflow menu |
| Chat | `ChatFragment` | composer, message actions (long-press), jump/citation, send |
| Chat settings sheet | (chat overflow) | mute/archive/pin/label entries |
| Archive browser | `ArchiveFragment` | media/files/links tabs, row actions |
| Incoming call | `IncomingCallActivity` | Accept / Decline |
| Profile | `ProfileFragment` | language, recovery email, delete account |
| Create group | `CreateGroupFragment` | member picker, confirm |
| Group detail | `GroupDetailFragment` | member list, leave/admin actions |

## Tier 2 — include when reachable with fixtures

| Screen | Fragment | Critical actions |
| --- | --- | --- |
| Register | `RegisterFragment` | fields, submit |
| Complete profile | `CompleteProfileFragment` | name/avatar, continue |
| Forgot password | `ForgotPasswordFragment` | OTP request/confirm |
| Contacts | `ContactsFragment` | contact list, invite action |
| User search | `UserSearchFragment` | results, add contact |
| Language settings | `LanguageSettingsFragment` | EN/VI toggles |
| Recovery email flow | `RecoveryEmail*Fragment` | form, OTP |
| Delete account | `DeleteAccountFragment` | confirm gate, OTP |
| Blocked users | `BlockedUsersFragment` | list, unblock |
| Starred messages | `StarredMessagesFragment` | list, jump-to-message |
| Public feed | `PublicFeedFragment` | post cards, compose entry |
| Post compose | `PostComposeFragment` | composer, publish |
| Post detail | `PostDetailFragment` | reactions, comments |
| Group board | `GroupBoardFragment` | poll/reminder cards |
| Encryption | `EncryptionFragment` | E2EE status/actions |
| Invite manage | `InviteManageFragment` | link create/revoke |
| Invite join | `InviteJoinFragment` | preview, join |
| Media viewer | `MediaViewerFragment` | media actions, save/share |
| Summary/Ask sheets | bottom sheets (not nav destinations) | scope chips, citation chips, ask composer |

## Explicitly out of the sweep

- `SplashFragment` — transient, no user action.
- Debug/push-injection surfaces — not user-facing.

## Oracle requirements still open

This inventory alone does not unblock the row. Still needed:

- a clipped-text detection oracle (compare measured text bounds vs view bounds
  in the UI tree dump — heuristic proposals welcome);
- a TalkBack focus-order/content-description pass plan;
- reduced-motion verification definition.
