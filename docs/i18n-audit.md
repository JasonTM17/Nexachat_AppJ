# i18n Audit — NexaChat Android

Read-only audit. Sources: `android/app/src/main/res/values/strings.xml`,
`android/app/src/main/res/values-vi/strings.xml`,
`android/app/src/main/res/values/strings_call.xml`,
`android/app/src/main/res/values-vi/strings_call.xml`, and the Kotlin/Java
sources under `android/app/src/main/java/com/nexachat` (`feature/**`, `call/**`,
`core/ui/**`). No source or resource file was modified.
Counts re-verified against the tree on 2026-11-05.

## Counting command

Run from the repository root:

```bash
python - <<'PY'
import re, pathlib
files = [
    "android/app/src/main/res/values/strings.xml",
    "android/app/src/main/res/values-vi/strings.xml",
    "android/app/src/main/res/values/strings_call.xml",
    "android/app/src/main/res/values-vi/strings_call.xml",
]
sets = {}
for f in files:
    body = re.search(r"<resources[^>]*>([\s\S]*?)</resources>",
                     pathlib.Path(f).read_text(encoding="utf-8")).group(1)
    pairs = re.findall(r'<string\s+name="([^"]+)"([^>]*)>', body)
    names = [n for n, _ in pairs]
    translatable = [n for n, a in pairs if 'translatable="false"' not in a]
    sets[f] = names
    print(f"{f}: {len(names)} entries, {len(translatable)} translatable")
print("base parity:", sets[files[0]] == sets[files[1]])
print("call parity:", sets[files[2]] == sets[files[3]])
PY
```

## 1. Parity check (EN vs VI)

| Resource pair | EN entries | VI entries | Translatable EN/VI | Name sets |
| --- | ---: | ---: | ---: | --- |
| `values/strings.xml` ↔ `values-vi/strings.xml` | 142 | 142 | 140 / 140 | identical |
| `values/strings_call.xml` ↔ `values-vi/strings_call.xml` | 44 | 44 | 44 / 44 | identical |
| **Total per locale** | **186** | **186** | **184 / 184** | **identical** |

- Missing from VI: **none**.
- Missing from EN: **none**.
- Translatable keys: **184** of 186 (the 2 `language_name_*` endonyms are
  `translatable="false"` by design).
- `strings_call.xml` carries the call-specific labels (state names, permission
  rationales, call controls, settings section titles). It was added after the
  original 96-key audit and is now inside the audit scope; both locales carry the
  same 44 names.
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

## 3. Hardcoded user-visible strings in Kotlin/Java

Scan covered `feature/**`, `call/**`, `core/ui/**` for `Text("…")`,
`text = "…"`, `placeholder = "…"`, `label = "…"`, `contentDescription = "…"`,
`setContentDescription(…)`, `Toast.makeText(…)`, dialogs, and snackbars.
Imports, URLs, route names, tags, keys, SQL, and logging were excluded.

| Screen / area | Location | Literal | Classification |
| --- | --- | --- | --- |
| Attachments | `feature/media/AttachmentViews.kt:247` | `"!"` | Failed-upload placeholder; symbol, not localizable prose |
| Attachments | `feature/media/AttachmentViews.kt:239` | `"${(item.progress * 100).toInt()}%"` | Dynamic progress; numeric only |
| Media | `feature/media/MediaModels.kt:62` | `"$bytes B"` | Size template; unit only |
| Media | `feature/media/MediaModels.kt:64` | `"%.1f KB"` | Size template; `String.format(Locale.getDefault(), …)` |
| Media | `feature/media/MediaModels.kt:65` | `"%.1f MB"` | Size template; `String.format(Locale.getDefault(), …)` |

The `formatBytes()` locale behavior is correct: KB/MB use
`String.format(Locale.getDefault(), …)` (`MediaModels.kt:60-65`), so `vi_VN`
renders a comma decimal separator. Unit tokens (B/KB/MB) are SI-neutral.

The call UI (`call/ui/*.java`) resolves every user-visible string through
`R.string.call_*`; no hardcoded prose was found there. Every other user-visible
value in the scanned packages resolves through `stringResource(R.string.*)` or
`getString(R.string.*)`.

## 4. Verdict

- Resource key coverage: **186/186 = 100 %** (0 missing either direction).
- Translatable strings localized: **184/184 = 100 %** (2 endonyms are
  `translatable="false"` by design).
- Hardcoded prose in UI: **0 hits**; only 2 symbols and 3 numeric/size-format
  templates were found.
- Translation quality: **184/184 clean = 100 %** — the six previously flagged VI
  entries are all fixed, and the `formatBytes()` locale behavior is correct.

Overall: clean i18n pass. The remaining `SĐT` vs "số điện thoại" register split
is a consistency nit, not a correctness defect.
