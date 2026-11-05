# NexaChat Android Design System Notes

This document describes the design language, token definitions, component states, and Jetpack Compose mapping for NexaChat on Android, synthesized from the Stitch design system **Modern Material Messenger** (`projects/15384195107762462338`).

---

## 1. Design Tokens

### 1.1 Color Palettes

#### Primary & Accent Brand Tokens
- **Primary Brand Indigo**: `#4F46E5` (Active actions, FAB background, outgoing bubble fill, unread badge, active tabs)
- **Primary Variant / Dark Indigo**: `#3525CD`
- **Primary Fixed Dim / Soft Lilac**: `#C3C0FF` / `#DAD7FF`
- **Secondary Slate**: `#505F76` (Default secondary text, inactive icons, system headers)
- **Secondary Variant / Light Slate**: `#64748B` / `#94A3B8`
- **Tertiary Cyan/Sky**: `#0EA5E9` (Link preview accents, read receipts)
- **Error Red**: `#BA1A1A` / `#DC2626`
- **Error Container**: `#FFDAD6`
- **Success Emerald**: `#10B981`

#### Light Theme M3 Surface & Background Hierarchy
- `background`: `#F8FAFC` (Canvas tone)
- `surface`: `#F8FAFC`
- `surfaceVariant`: `#E0E3E5`
- `surfaceContainerLowest`: `#FFFFFF` (Elevated cards, modal sheets, sticky composer docks)
- `surfaceContainerLow`: `#F2F4F6`
- `surfaceContainer`: `#ECEEF0`
- `surfaceContainerHigh`: `#E6E8EA`
- `onSurface`: `#0F172A` (WCAG AAA primary text)
- `onSurfaceVariant`: `#64748B` (Secondary text / timestamps)
- `outline`: `#CBD5E1` (Hairline borders & input borders)
- `outlineVariant`: `#E2E8F0` (List dividers, composer separators)

#### Dark Theme M3 Hierarchy
- `background`: `#0F172A` (Deep Slate Canvas)
- `surface`: `#0F172A`
- `surfaceContainerLowest`: `#0B1120`
- `surfaceContainerLow`: `#1E293B`
- `surfaceContainer`: `#1E293B` (Cards & dialogs)
- `surfaceContainerHigh`: `#334155` (Incoming message bubble in dark mode)
- `surfaceContainerHighest`: `#475569`
- `onSurface`: `#F8FAFC` (High-contrast text)
- `onSurfaceVariant`: `#94A3B8` (Muted timestamps, captions)
- `primary`: `#818CF8` (Lighter indigo for dark mode contrast)
- `onPrimary`: `#0F172A`
- `outline`: `#334155`
- `outlineVariant`: `#1E293B`

#### Chat Message Bubble Tokens
- **Outgoing (Mine)**:
  - Fill: Primary `#4F46E5` (Light) / `#6366F1` (Dark)
  - Text: High contrast `#FFFFFF`
  - Timestamp / Receipt: `#E0E7FF` / `rgba(255, 255, 255, 0.75)`
  - Outbox status icon (Clock for sending, Single/Double check for sent/delivered, Exclamation for failed)
- **Incoming (Peer)**:
  - Fill: `#F1F5F9` (Light) / `#1E293B` (Dark)
  - Stroke: 1px `#E2E8F0` (Light) / 1px `#334155` (Dark)
  - Text: `#0F172A` (Light) / `#F8FAFC` (Dark)
  - Sender name: `#4F46E5` (Group chat color accent)
  - Timestamp: `#64748B` (Light) / `#94A3B8` (Dark)

---

### 1.2 Typography Scale

| Token | Family | Weight | Size | Line Height | Letter Spacing | Compose Equivalent |
|---|---|---|---|---|---|---|
| `headline-lg` | Roboto Flex | Bold (700) | 32sp | 40sp | -0.02em | `Typography.headlineLarge` |
| `headline-md` | Roboto Flex | SemiBold (600) | 24sp | 32sp | -0.015em | `Typography.headlineMedium` |
| `headline-sm` | Roboto Flex | SemiBold (600) | 20sp | 28sp | 0 | `Typography.headlineSmall` |
| `title-lg` | Inter | SemiBold (600) | 18sp | 24sp | 0 | `Typography.titleLarge` |
| `title-md` | Inter | SemiBold (600) | 16sp | 24sp | 0.01em | `Typography.titleMedium` |
| `title-sm` | Inter | Medium (500) | 14sp | 20sp | 0.01em | `Typography.titleSmall` |
| `body-lg` | Inter | Regular (400) | 16sp | 24sp | 0.02em | `Typography.bodyLarge` |
| `body-md` | Inter | Regular (400) | 14sp | 20sp | 0.02em | `Typography.bodyMedium` |
| `body-sm` | Inter | Regular (400) | 12sp | 16sp | 0.02em | `Typography.bodySmall` |
| `label-lg` | Inter | Medium (500) | 14sp | 20sp | 0.01em | `Typography.labelLarge` |
| `label-md` | Inter | SemiBold (600) | 12sp | 16sp | 0.02em | `Typography.labelMedium` |
| `label-sm` | Inter | SemiBold (600) | 11sp | 14sp | 0.02em | `Typography.labelSmall` |

---

### 1.3 Shapes, Elevation, & Spacing

#### Corner Radii
- **Standard Controls / Inputs / Cards**: `8.dp` (`RoundedCornerShape(8.dp)`)
- **Action Buttons / Floating Action Button**: `16.dp` (`RoundedCornerShape(16.dp)`)
- **Pill Badges & Filter Chips**: `9999.dp` (`CircleShape`)
- **Avatars**: `9999.dp` (`CircleShape`)
- **Outgoing Message Bubble**: Asymmetric `RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)`
- **Incoming Message Bubble**: Asymmetric `RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)`

#### Spacing Baseline (8pt Grid)
- `space-xs`: `4.dp` (grouped messages from same sender, tight padding)
- `space-sm`: `8.dp` (badge padding, icon margins, list item vertical gaps)
- `space-md`: `16.dp` (screen margin, conversation sender separation, input horizontal padding)
- `space-lg`: `24.dp` (section spacing, dialog insets)
- `space-xl`: `32.dp` (hero margins, empty state graphic spacing)

#### Touch Targets
- Strictly enforce minimum **48dp** interactive touch hitbox across all buttons, icon buttons, list items, and tabs (`Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)` or `IconButton` default 48dp box).

---

## 2. Component List & States

| Component | Default | Pressed / Active | Focused | Disabled | Error |
|---|---|---|---|---|---|
| **Filled Button** | Bg `#4F46E5`, Text `#FFFFFF` | Bg `#3525CD`, scale down 98% | Outline 2dp `#C3C0FF` | Bg `#E2E8F0`, Text `#94A3B8` | - |
| **Outlined Button** | Border 1.5dp `#4F46E5`, Text `#4F46E5` | Bg `#EEF2FF` | Border 2dp `#3525CD` | Border 1dp `#E2E8F0`, Text `#CBD5E1` | - |
| **Danger Button** | Bg `#BA1A1A`, Text `#FFFFFF` | Bg `#991B1B` | Outline 2dp `#FFDAD6` | Bg `#E2E8F0`, Text `#94A3B8` | - |
| **Outbox Message** | Solid Indigo, tick mark | Indigo | - | - | Red container `#FFDAD6`, tap to retry |
| **Text Input** | Bg `#F1F5F9`, border none | - | Bg `#FFFFFF`, border 1.5dp `#4F46E5` | Bg `#E2E8F0`, Text `#94A3B8` | Border 1.5dp `#BA1A1A`, error text `#BA1A1A` |
| **Search Bar** | Bg `#F1F5F9`, search icon | - | Border 1.5dp `#4F46E5`, clear icon | - | - |
| **Avatar** | Initials on `#EEF2FF` or Coil Image | Ripple effect | - | - | Fallback letter |
| **Filter Chip** | Bg `#FFFFFF`, border 1dp `#CBD5E1` | Bg `#4F46E5`, Text `#FFFFFF` | - | Bg `#F1F5F9` | - |

---

## 3. Stitch Screen to Jetpack Compose Mapping

| Stitch Screen & File | Composable Path | Key UI Elements |
|---|---|---|
| **Inbox** (`inbox.html`, `inbox.png`) | `feature/inbox/InboxScreen.kt` | Material 3 Channel row (72dp height), unread pill badge, TopAppBar with search & profile action, FAB (16dp rounded squircle). |
| **Active Chat** (`active_chat.html`, `active_chat.png`) | `feature/chat/ChatScreen.kt`, `ChatInteractions.kt` | TopBar with peer avatar + connection status subtitle, Asymmetric message bubbles, pending clock icon, failed badge with retry affordance, reply quote preview block, reaction chips, pinned message banner, sticky bottom composer with IME padding. |
| **Contacts & Search** (`contacts_search.html`, `contacts_search.png`) | `feature/contacts/ContactsScreen.kt`, `feature/contacts/UserSearchScreen.kt` | Search bar with instant clear, tab bar (Contacts / Discover), contact item with start-chat action, Add by phone bottom sheet dialog, empty search state. |
| **Profile & Password** (`profile_password.html`, `profile_password.png`) | `feature/profile/ProfileScreen.kt`, `feature/settings/LanguageSettingsScreen.kt` | Circular avatar with camera edit badge, user metadata card, Change Password bottom sheet / dialog with validations, language selector radio items, logout button. |
| **Group Detail & Roles** (`group_detail.html`, `group_detail.png`) | `feature/group/GroupDetailScreen.kt`, `feature/group/CreateGroupScreen.kt` | Group hero avatar with edit camera button, group member roster with Owner/Admin/Member role badges, member remove action, danger zone leave group button. |
| **Login & Register** (`login_register.html`, `login_register.png`) | `feature/auth/LoginScreen.kt`, `feature/auth/RegisterScreen.kt` | Centered app hero icon & title, segmented tab or link to toggle Login/Register, styled text fields with icons, password toggle, soft error container banner. |
| **Media Viewer** (`media_viewer.html`, `media_viewer.png`) | `feature/chat/MediaViewerScreen.kt` | Dark surface (`#000000` / `#0F172A`), top transparent back action, zoomable media image, download/share action, caption bar. |
