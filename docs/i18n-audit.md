# i18n Audit — NexaChat Android

Read-only audit. Sources: `android/app/src/main/res/values/strings.xml`,
`android/app/src/main/res/values-vi/strings.xml`, and all 74 Kotlin files under
`android/app/src/main/java`. No source or resource file was modified.

## 1. Parity check (EN vs VI)

| Section | EN keys | VI keys | Status |
| --- | ---: | ---: | --- |
| App name | 1 | 1 | identical by design |
| Splash | 1 | 1 | ok |
| Auth labels & actions | 8 | 8 | ok |
| Auth field validation | 4 | 4 | ok |
| API/user-facing errors | 5 | 5 | ok |
| Inbox | 2 | 2 | ok |
| Contacts & user search | 17 | 17 | ok |
| Contacts errors | 2 | 2 | ok |
| Profile | 2 | 2 | ok |
| Chat placeholder | 2 | 2 | ok |
| Chat | 9 | 9 | ok |
| Media / attachments | 5 | 5 | ok |
| Group | 13 | 13 | ok |
| **Total** | **71** | **71** | **100 %** |

- Missing from VI: **none**.
- Missing from EN: **none**.
- Present but identical in VI: **none** (only `app_name`, which is intentional).
- Untranslated English leaking through resources: **none**.

## 2. Suspicious VI strings (quality, not parity)

| Key | EN | Current VI | Issue | Suggested VI |
| --- | --- | --- | --- | --- |
| `contact_alias_label` | Alias | Tên gợi nhớ | Wrong semantics; "Tên gợi nhớ" is not the word for an alias/nickname. | Biệt danh |
| `group_role_admin` | Admin | Quản trị | "Quản trị" means administration, not the role. | Quản trị viên |
| `chat_placeholder` | Chat %1$s | Trò chuyện %1$s | Missing preposition; reads as a fragment. | Trò chuyện với %1$s |
| `action_leave_group` | Leave group | Rời nhóm | Missing "khỏi"; ungrammatical. | Rời khỏi nhóm |
| `group_leave_confirm` | Leave this group? | Rời nhóm này? | Missing "khỏi". | Rời khỏi nhóm này? |
| `group_remove_member_confirm` | Remove %1$s from this group? | Xóa %1$s khỏi nhóm? | Understandable but drops "this". | Xóa %1$s khỏi nhóm này? |

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
`setContentDescription`, dialogs, and snackbars/toasts across all 74 files.
Imports, URLs, route names, tags, keys, SQL, and logging were excluded.

| Screen / area | Location | Literal | Classification |
| --- | --- | --- | --- |
| Chat | `feature/chat/ChatScreen.kt:339` | `"!"` | Delivery-failed mark; symbol, not localizable prose |
| Attachments | `feature/media/AttachmentViews.kt:245` | `"!"` | Failed-upload placeholder; symbol |
| Attachments | `feature/media/AttachmentViews.kt:237` | `"${(item.progress * 100).toInt()}%"` | Dynamic progress; numeric only |
| Media | `feature/media/MediaModels.kt:58` | `"$bytes B"` | Size template hardcoded in code |
| Media | `feature/media/MediaModels.kt:60` | `"%.1f KB"` | Size template hardcoded in code |
| Media | `feature/media/MediaModels.kt:61` | `"%.1f MB"` | Size template hardcoded in code |

Adjacent finding: `formatBytes()` at `MediaModels.kt:56-61` forces
`String.format(Locale.US, …)`, so Vietnamese users see a dot decimal separator
("640.0 KB") where `vi_VN` convention is a comma. Unit tokens (B/KB/MB) are
SI-neutral, so this is a formatting defect rather than missing copy.

Every other user-visible value resolves through
`stringResource(R.string.*)` (153 references), including all top bars, labels,
placeholders, content descriptions, dialogs, and error/snackbar text.

## 4. Verdict

- Resource key coverage: **71/71 = 100 %** (0 missing either direction).
- Translatable strings localized: **70/70 = 100 %**.
- Hardcoded prose in Compose UI: **0 hits**; only 2 symbols, 1 dynamic numeric,
  and 3 hardcoded size-format templates were found.
- Translation quality: **65/70 clean ≈ 93 %**; 5 keys need retranslation and
  1 minor wording tweak.
- Effective localized coverage: **~93 %** after quality adjustment; the single
  functional locale bug is the forced `Locale.US` in `formatBytes()`.

Overall: strong i18n hygiene. Fix the five flagged VI entries and the
`Locale.US` size formatting to reach a clean pass.
