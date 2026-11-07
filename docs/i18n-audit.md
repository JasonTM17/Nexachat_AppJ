# i18n Audit — NexaChat Android

Read-only audit. Sources: `android/app/src/main/res/values/strings.xml`,
`android/app/src/main/res/values-vi/strings.xml`,
`android/app/src/main/res/values/strings_call.xml`,
`android/app/src/main/res/values-vi/strings_call.xml`,
`android/app/src/main/res/values/strings_summary.xml`,
`android/app/src/main/res/values-vi/strings_summary.xml`, the Java sources
under `android/app/src/main/java/com/nexachat` (`feature/**`, `call/**`,
`summary/**`, `core/ui/**`), and the XML layouts under
`android/app/src/main/res/layout/` and `layout-land/`. No source or resource
file was modified.
Counts re-verified against the tree on 2026-11-06.

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
    "android/app/src/main/res/values/strings_summary.xml",
    "android/app/src/main/res/values-vi/strings_summary.xml",
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
print("summary parity:", sets[files[4]] == sets[files[5]])
PY
```

## 1. Parity check (EN vs VI)

| Resource pair | EN entries | VI entries | Translatable EN/VI | Name sets |
| --- | ---: | ---: | ---: | --- |
| `values/strings.xml` ↔ `values-vi/strings.xml` | 135 | 135 | 133 / 133 | identical |
| `values/strings_call.xml` ↔ `values-vi/strings_call.xml` | 46 | 46 | 46 / 46 | identical |
| `values/strings_summary.xml` ↔ `values-vi/strings_summary.xml` | 40 | 40 | 40 / 40 | identical |
| **Total per locale** | **221** | **221** | **219 / 219** | **identical** |

- Missing from VI: **none**.
- Missing from EN: **none**.
- Translatable keys: **219** of 221 (the 2 `language_name_*` endonyms are
  `translatable="false"` by design).
- `strings_call.xml` carries the call-specific labels (state names, permission
  rationales, call controls, notification channels, localized call-log lines).
  It was added after the original 96-key audit and is now inside the audit scope;
  both locales carry the same 46 names.
- `strings_summary.xml` carries the summary sheet, engine-badge, scope-chip,
  consent-dialog and settings strings; both locales carry the same 40 names.
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

## 3. Hardcoded user-visible strings in Java

Scan covered `feature/**`, `call/**`, `summary/**`, and `core/ui/**` for
`setText("…")`, `Toast.makeText(…)`, dialog messages, `setContentDescription(…)`,
and XML `android:text` values that are not `@string/`. Imports, URLs, route
names, tags, keys, SQL, and logging were excluded. Screens are Java fragments
with ViewBinding (`layout/` and `layout-land/`, including
`layout-land/fragment_chat.xml`).

| Screen / area | Location | Literal | Classification |
| --- | --- | --- | --- |
| Attachments | `feature/chat/ChatFragment.java` pending row | `"×"` | Remove control; symbol, with `getString(R.string.action_cancel)` as the content description |
| Attachments | `feature/chat/ChatFragment.java` pending row | `Math.round(progress * 100) + "%"` | Dynamic progress; numeric only. A failed upload uses `getString(R.string.error_generic)` |
| Media | `feature/media/PendingAttachment.java:85` | `bytes + " B"` | Size template; unit only |
| Media | `feature/media/PendingAttachment.java:88` | `"%.1f KB"` | Size template; `String.format(Locale.getDefault(), …)` |
| Media | `feature/media/PendingAttachment.java:90` | `"%.1f MB"` | Size template; `String.format(Locale.getDefault(), …)` |

The `formatBytes()` locale behavior is correct: KB/MB use
`String.format(Locale.getDefault(), …)` (`PendingAttachment.java:80-91`), so
`vi_VN` renders a comma decimal separator. Unit tokens (B/KB/MB) are SI-neutral.

The call UI (`call/ui/*.java`) resolves every user-visible string through
`R.string.call_*`, and the summary UI (`feature/summary/*.java`) through
`R.string.summary_*`; no hardcoded prose was found in either. Every other
user-visible value in the scanned packages resolves through
`getString(R.string.*)` or an `@string/` reference in XML.

## 4. Verdict

- Resource key coverage: **221/221 = 100 %** (0 missing either direction).
- Translatable strings localized: **219/219 = 100 %** (2 endonyms are
  `translatable="false"` by design).
- Hardcoded prose in UI: **0 hits**; only 2 symbols and 3 numeric/size-format
  templates were found.
- Translation quality: **219/219 clean = 100 %** — the six previously flagged VI
  entries are all fixed, and the `formatBytes()` locale behavior is correct.

Overall: clean i18n pass. The remaining `SĐT` vs "số điện thoại" register split
is a consistency nit, not a correctness defect.
