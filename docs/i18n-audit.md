# i18n Audit — NexaChat Android

Read-only audit. Sources: `android/app/src/main/res/values/strings.xml`,
`android/app/src/main/res/values-vi/strings.xml`, and all 82 Kotlin files under
`android/app/src/main/java`. No source or resource file was modified.
Counts re-verified against the final frozen tree (2026-11-05).

## 1. Parity check (EN vs VI)

| Section | EN keys | VI keys | Status |
| --- | ---: | ---: | --- |
| App name | 1 | 1 | identical by design |
| Splash | 0 | 0 | section removed with `splash_tagline` |
| Auth | 14 | 14 | ok |
| Auth field validation | 4 | 4 | ok |
| ApiError → user-facing messages | 5 | 5 | ok |
| Inbox | 2 | 2 | ok |
| Contacts & user search | 20 | 20 | ok |
| Contacts errors | 2 | 2 | ok |
| Profile | 2 | 2 | ok |
| Settings | 4 | 4 | 2 endonyms are `translatable="false"` |
| Chat placeholder | 2 | 2 | ok |
| Chat | 12 | 12 | ok |
| Message interactions | 10 | 10 | ok |
| Media / attachments | 5 | 5 | ok |
| Group | 13 | 13 | ok |
| **Total** | **96** | **96** | **100 %** |

- Missing from VI: **none**.
- Missing from EN: **none**.
- Translatable keys: **94** (the 2 `language_name_*` endonyms are
  `translatable="false"` by design).
- Present but identical in VI: `app_name` and `pinned_count` (`%1$d/%2$d`,
  numeric format) — both intentional.
- Untranslated English leaking through resources: **none**.

## 2. Suspicious VI strings (quality, not parity)

All six entries flagged in the previous audit pass have been fixed in
`values-vi/strings.xml`; the current values are the suggested ones:

| Key | EN | Current VI | Status |
| --- | --- | --- | --- |
| `contact_alias_label` | Alias | Biệt danh | ✅ fixed |
| `group_role_admin` | Admin | Quản trị viên | ✅ fixed |
| `chat_placeholder` | Chat %1$s | Trò chuyện với %1$s | ✅ fixed |
| `action_leave_group` | Leave group | Rời khỏi nhóm | ✅ fixed |
| `group_leave_confirm` | Leave this group? | Rời khỏi nhóm này? | ✅ fixed |
| `group_remove_member_confirm` | Remove %1$s from this group? | Xóa %1$s khỏi nhóm này? | ✅ fixed |

Consistency notes (not errors):

- `search_label` and `add_by_phone_title` abbreviate as "SĐT", while
  `auth_phone_label`, `error_user_not_found`, etc. spell out "số điện thoại".
  Pick one register.
- `error_phone_required` / `error_display_name_required` omit "Vui lòng",
  unlike the other validation and error strings. Add it for a consistent tone.
- `media_attachment` and `cd_attach` both render "Đính kèm". Fine as-is, but
  two contexts collapse to one label.
- No machine-translation artifacts with leftover English words were found;
  the issues above are grammar/word-choice, not garbled output.

## 3. Hardcoded user-visible strings in Kotlin

Scan covered `Text("…")`, `placeholder=`, `label=`, `contentDescription=`,
`setContentDescription`, dialogs, and snackbars/toasts across all 82 files.
Imports, URLs, route names, tags, keys, SQL, and logging were excluded.

| Screen / area | Location | Literal | Classification |
| --- | --- | --- | --- |
| Chat | `feature/chat/ChatScreen.kt:575` | `"!"` | Delivery-failed mark; symbol, not localizable prose |
| Attachments | `feature/media/AttachmentViews.kt:247` | `"!"` | Failed-upload placeholder; symbol |
| Attachments | `feature/media/AttachmentViews.kt:239` | `"${(item.progress * 100).toInt()}%"` | Dynamic progress; numeric only |
| Media | `feature/media/MediaModels.kt:62` | `"$bytes B"` | Size template hardcoded in code |
| Media | `feature/media/MediaModels.kt:64` | `"%.1f KB"` | Size template hardcoded in code |
| Media | `feature/media/MediaModels.kt:65` | `"%.1f MB"` | Size template hardcoded in code |

The previous `formatBytes()` locale defect is **fixed**: it now calls
`String.format(Locale.getDefault(), …)` (`MediaModels.kt:60-65`), so `vi_VN`
renders a comma decimal separator. Unit tokens (B/KB/MB) are SI-neutral.

Every other user-visible value resolves through
`stringResource(R.string.*)` (154 call sites across 22 files), including all
top bars, labels, placeholders, content descriptions, dialogs, and
error/snackbar text.

## 4. Verdict

- Resource key coverage: **96/96 = 100 %** (0 missing either direction).
- Translatable strings localized: **94/94 = 100 %** (2 endonyms are
  `translatable="false"` by design).
- Hardcoded prose in Compose UI: **0 hits**; only 2 symbols, 1 dynamic numeric,
  and 3 hardcoded size-format templates were found.
- Translation quality: **94/94 clean = 100 %** — the six previously flagged VI
  entries are all fixed, and the `formatBytes()` locale defect is fixed.

Overall: clean i18n pass. The remaining `SĐT` vs "số điện thoại" register split
is a consistency nit, not a correctness defect.
