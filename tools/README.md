# Repository tools

Run commands from the repository root unless a package README says otherwise. This index covers the stand-alone entry points and links each maintained package to its detailed guide. CI names are the workflow/job that runs the check; “No” means it is an operator or local tool.

## Checks

| Script or file | Purpose and how to run | CI | Windows pair |
| --- | --- | --- | --- |
| `checks/android-a11y-baseline.json` | Legacy finding allow-list consumed by `python tools/checks/check-android-a11y.py`. | `repo-hygiene` (through checker) | N/A |
| `checks/check-android-a11y.py` | Scan Android layouts: `python tools/checks/check-android-a11y.py`. | `repo-hygiene` | Python command works in PowerShell |
| `checks/check-android-strings.py` | Check Vi/En string parity: `python tools/checks/check-android-strings.py`; `--self-test` runs embedded checks. | `repo-hygiene` | Python command works in PowerShell |
| `checks/check-api-contract.py` | Compare server routes with `docs/api-contract.md`: run with no arguments; `--self-test` tests the parser. | `repo-hygiene` (both modes) | Python command works in PowerShell |
| `checks/check-markdown-links.py` | Validate relative Markdown links: `python tools/checks/check-markdown-links.py [PATH ...]`. | `repo-hygiene` | Python command works in PowerShell |
| `checks/check-release-r8-mapping.py` | Check release APK mapping after a release build: `python tools/checks/check-release-r8-mapping.py`. | `android-release` | Python command works in PowerShell |
| `checks/test_check_android_a11y.py` | Unit tests for the Android accessibility checker: `python -m unittest tools/checks/test_check_android_a11y.py`. | `repo-hygiene` | Python command works in PowerShell |
| `checks/test_check_release_r8_mapping.py` | Unit tests for the R8 checker: `python -m unittest tools/checks/test_check_release_r8_mapping.py`. | `repo-hygiene` | Python command works in PowerShell |
| `checks/test_compose_env_contract.py` | Verify documented environment settings are forwarded by Compose: `python -m unittest tools/checks/test_compose_env_contract.py`. | No | Python command works in PowerShell |

## Release

| Script | Purpose and how to run | CI | Windows pair |
| --- | --- | --- | --- |
| `release/release-version.sh` | Shared version tag parser; source from Bash or run `bash tools/release/test-release-version.sh`. | `repo-hygiene` test; `android-release` sources it | Git Bash |
| `release/release-url-guard.sh` | Shared release API URL guard; source from Bash or run `bash tools/release/test-sign-release-url-guard.sh`. | `repo-hygiene` test; `android-release` sources it | Git Bash |
| `release/sign-release.sh` | Build and sign a local APK: `bash tools/release/sign-release.sh [version] [options]`. See [`release/README.md`](release/README.md). | No | `release/sign-release.ps1` |
| `release/sign-release.ps1` | PowerShell entry point for local signing: `pwsh -File tools/release/sign-release.ps1 [version] [options]`. | No | `release/sign-release.sh` |
| `release/test-release-version.sh` | Test accepted and rejected version tags: `bash tools/release/test-release-version.sh`. | `repo-hygiene` | Git Bash |
| `release/test-sign-release-url-guard.sh` | Test the release URL policy: `bash tools/release/test-sign-release-url-guard.sh`. | `repo-hygiene` | Git Bash |
| `release/test-vercel-ignore-command.sh` | Test Vercel build-skip behavior in temporary Git repositories: `bash tools/release/test-vercel-ignore-command.sh`. | `repo-hygiene` (also called by `site/check.py`) | Git Bash |
| `release/README.md` | Local keystore setup, signing and APK verification instructions. | No | PowerShell examples included |

## Operations and demo

| Script | Purpose and how to run | CI | Windows pair |
| --- | --- | --- | --- |
| `ops/prod-smoke.sh` | Read-only API deployment smoke; `bash tools/ops/prod-smoke.sh https://<api-host>`. Use `--write` only for an explicitly intended disposable-account check; `--self-test` is offline. | No | Git Bash |
| `ops/prod-smoke-self-test.py` | Offline stub-server scenarios: `python tools/ops/prod-smoke-self-test.py` or `bash tools/ops/prod-smoke.sh --self-test`. | No | Python command works in PowerShell |
| `demo/seed_demo_chat.py` | Seed repeat-safe demo chat data against the configured local API. | No | Python command works in PowerShell |
| `demo/test_seed_demo_chat.py` | Offline tests for demo seeding helpers: `python -B tools/demo/test_seed_demo_chat.py`. | `repo-hygiene` | Python command works in PowerShell |

## Maintained tool packages

| Directory | Contents and entry point | CI | Windows notes |
| --- | --- | --- | --- |
| `backup/` | PostgreSQL and attachment backup/restore; see [`backup/`](backup/). | No | Python |
| `domain/` | DNS, TLS and App Links checker: `bash tools/domain/check-domain.sh`; self-test with `--self-test`. | No | Git Bash |
| `e2e/` | Backend and device journeys; see [`e2e/README.md`](e2e/README.md). | Some unit tests; live journeys are operator-run | Mixed; Bash scripts use Git Bash, PowerShell pair `smoke-api.ps1` |
| `fcm-live-check/` | Optional FCM delivery probe; see [`fcm-live-check/README.md`](fcm-live-check/README.md). | No | Python |
| `firebase-rules/` | Firebase RTDB rules and emulator contract suite; see [`firebase-rules/README.md`](firebase-rules/README.md). | `firebase-rules` job | Node |
| `gif/` | Screen recording helpers; see [`gif/README.md`](gif/README.md). | No | Python |
| `observability/` | Operator Prometheus alert rule files. | No | N/A |
| `perf/` | Database fixtures and JVM footprint scripts. | No | Bash requires Git Bash |
| `rag-eval/` | Offline RAG evaluation; see [`rag-eval/README.md`](rag-eval/README.md). | No | Bash and PowerShell variants |
| `site-tests/` | JavaScript tests for the static site: `cd tools/site-tests; npm test`. | `site-js-test` job | Node |
| `summary-eval/` | Conversation summary evaluation and optional live-model smoke scripts. | No | Bash and PowerShell variants |

CI syntax-checks every `demo/**/*.sh` and `tools/**/*.sh` with `bash -n`. A syntax pass does not run each script's external or live behavior.
