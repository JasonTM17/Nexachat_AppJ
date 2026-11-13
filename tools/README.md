# Repository tools

Index of every top-level script and directory under `tools/`. Nested packages have their
own README files where noted. Names are historical (kebab-case, snake_case, and mixed);
**do not rename** without grepping CI, docs, and release workflows.

Run `python tools/check-android-a11y.py` from the **repository root** (the scanner resolves
`tools/android-a11y-baseline.json` from the process cwd). The committed
`android-a11y-baseline.json` is an intentional allow-list: known legacy layout findings stay
suppressed while new violations fail the gate.

| Name | Purpose | CI | Windows / Bash |
| --- | --- | --- | --- |
| `android-a11y-baseline.json` | Committed allow-list for static Android a11y scan baselines | Indirect (used by `check-android-a11y.py` when that job runs) | N/A (data file) |
| `backup/` | Paired Postgres + upload backup/restore helpers for local fixtures | No | Python; see `backup/*.py` |
| `check-android-a11y.py` | Static Android layout a11y rules A1–A6 with baseline suppression | `repo-hygiene` (repo root) | Python |
| `check-android-strings.py` | Vi/En string resource parity and placeholder checks | `repo-hygiene` (`--self-test` + full run) | Python |
| `check-api-contract.py` | REST route inventory vs `docs/api-contract.md` | `repo-hygiene` (`--self-test` + full run) | Python |
| `check-markdown-links.py` | Validates relative Markdown links in the tree | `repo-hygiene` | Python |
| `check-release-r8-mapping.py` | Release APK ↔ R8 mapping sanity for Play/Hilt types | `android-release` workflow | Python |
| `domain/` | Domain, TLS, Asset Links, and Digital Asset Links smoke (`check-domain.sh`) | No | Bash only (Git Bash on Windows) |
| `e2e/` | Backend/device end-to-end smoke and perf scripts | No (`bash -n` syntax only in `repo-hygiene`) | Mixed; see `e2e/README.md` |
| `fcm-live-check/` | Optional live FCM delivery probe | No | Python; see `fcm-live-check/README.md` |
| `firebase-rules/` | Firebase RTDB rules + offline emulator tests | `firebase-rules` job (`npm test`) | Node; see `firebase-rules/README.md` |
| `gif/` | Screen recording helpers for docs media | No | Python; see `gif/README.md` |
| `observability/` | Sample Prometheus alert rules for operators | No | YAML reference |
| `perf/` | DB perf fixtures and JVM footprint scripts | No | Bash (`jvm-footprint.sh`, `recreate_nexachat_perf.sh`); SQL under `perf/` |
| `prod-smoke-self-test.py` | Offline assertions for `prod-smoke.sh` helpers | No | Python |
| `prod-smoke.sh` | Post-deploy HTTP smoke against a configured API base | No | Bash only |
| `rag-eval/` | Offline RAG benchmark harness for conversation search | No | Bash + PowerShell; see `rag-eval/README.md` |
| `README-release.md` | Local release APK signing and `release/` layout (not this index) | No | Documents `sign-release.sh` / `.ps1` |
| `release-url-guard.sh` | Validates release `API_BASE_URL` policy before signing | Sourced by `android-release` | Bash; tested in `repo-hygiene` |
| `release-version.sh` | Maps git tags to Android `versionName` / `versionCode` | Sourced by `android-release` | Bash; tested in `repo-hygiene` |
| `seed_demo_chat.py` | Repeat-safe demo API seeding (messages, reactions, groups) | No (unit-tested in `repo-hygiene`) | Python |
| `sign-release.ps1` | Windows release APK build + sign wrapper | No | PowerShell; pair with `sign-release.sh` |
| `sign-release.sh` | POSIX release APK build + sign wrapper | No | Bash; pair with `sign-release.ps1` |
| `site-tests/` | Node behaviour tests for the static site scripts | `site-js-test` (`npm test`) | Node; see `site-tests/README.md` |
| `summary-eval/` | Conversation summary quality benchmarks and live LLM smoke | No | Bash + PowerShell under `summary-eval/` |
| `test_check_android_a11y.py` | Offline unit tests for the a11y scanner | `repo-hygiene` (`python -m unittest`) | Python |
| `test_check_release_r8_mapping.py` | Offline unit tests for the R8 mapping checker | `repo-hygiene` | Python |
| `test_seed_demo_chat.py` | Offline unit tests for demo seeding helpers | `repo-hygiene` (`python -B` after a11y unittest) | Python |
| `test-release-version.sh` | Self-test for `release-version.sh` | `repo-hygiene` (`bash`) | Bash only |
| `test-sign-release-url-guard.sh` | Self-test for `release-url-guard.sh` | `repo-hygiene` (`bash`) | Bash only |
| `test-vercel-ignore-command.sh` | Self-test for the Vercel “skip build if site unchanged” git command | `repo-hygiene` (`bash`) | Bash only |

All `tools/**/*.sh` and `demo/**/*.sh` files are syntax-checked (`bash -n`, optional `shellcheck`) in `repo-hygiene`; that is not the same as executing the script’s runtime behaviour.
