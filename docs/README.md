# NexaChat Documentation Index

Every document in the repository, grouped by purpose. Start with
[README.md](../README.md) for the product overview and quick start.

## Overview & architecture

| Doc | Contents |
|---|---|
| [README.md](../README.md) | Product overview, capability map, quick start, repo layout |
| [docs/architecture.md](architecture.md) | Container, module and send-message diagrams; monolith rationale |

## API & realtime contracts

| Doc | Contents |
|---|---|
| [docs/api-contract.md](api-contract.md) | Current REST contract, including the summary and call routes |
| [docs/websocket-protocol.md](websocket-protocol.md) | STOMP destinations, envelope, delivery guarantees, limits |
| [docs/calls.md](calls.md) | Call signaling, call events, REST configuration, history and Android UI |

## Security & privacy

| Doc | Contents |
|---|---|
| [docs/security.md](security.md) | Security narrative — auth, authZ, storage, rate limits, known limits |
| [docs/security-checklist.md](security-checklist.md) | Per-endpoint audit table with file:line proof |
| [docs/security-review.md](security-review.md) | Current security review and release findings |

## Quality & test evidence

| Doc | Contents |
|---|---|
| [docs/rtm.md](rtm.md) | Requirement → module → test → demo traceability matrix |
| [docs/i18n-audit.md](i18n-audit.md) | EN/VI string parity and hardcoded-literal sweep |
| [docs/rotation-audit.md](rotation-audit.md) | Per-screen state-survival audit |
| [docs/interactions-demo.md](interactions-demo.md) | Reply/reaction/pin walkthrough script and feature playbook |
| [docs/media/README.md](media/README.md) | Stitch Dark (`#0B0E17`) & Light (`#F8FAFC`) Mode live showcase and core feature frame index |
| [docs/media/screens/](media/screens/) | Captured Stitch Dark & Light Mode showcase (`stitch-01..10`) and core workflow PNGs (`01..17`) |
| [docs/screenshots-manifest.md](screenshots-manifest.md) | Screenshot capture plan and routes/preconditions |
| [docs/screenshots/](screenshots/) | Captured feature screenshots |
| [docs/design/screens/](design/screens/) | Live emulator screenshots (EN/VI + dark) |
| `docs/evidence/` | Local-only interaction evidence (gitignored — not committed) |

## Operations

| Doc | Contents |
|---|---|
| [docs/demo-runbook.md](demo-runbook.md) | LAN demo preflight, ~8-minute script, fallbacks |
| [docs/operations.md](operations.md) | Operator setup, runtime configuration, backup and recovery notes |
| [docs/packaging-checklist.md](packaging-checklist.md) | Release acceptance checklist and fresh-clone verification steps |
| [tools/README-release.md](../tools/README-release.md) | Keystore, signed APK and release archive procedure |
| [demo/seed.sql](../demo/seed.sql) | Re-runnable demo accounts, contacts and chats |
| [demo/lan-up.sh](../demo/lan-up.sh), [demo/lan-up.ps1](../demo/lan-up.ps1) | LAN demo bootstrap (Git Bash / PowerShell) |
| [demo/backup.sh](../demo/backup.sh) | PostgreSQL demo backup |

## Product & architecture Q&A

| Doc | Contents |
|---|---|
| [docs/report.md](report.md) | System design and engineering report (canonical source) |
| [docs/report.docx](report.docx) | Generated DOCX engineering report (regenerate with `tools/build-report.py`) |
| [docs/viva-questions.md](viva-questions.md) | Architecture & engineering Q&A reference with code citations |
| [docs/known-limitations.md](known-limitations.md) | Architectural scope and documented trade-offs |
| [docs/ai-summary.md](ai-summary.md) | Conversation summary contract, engines and consent model |

## Design

| Doc | Contents |
|---|---|
| [docs/design/design-notes.md](design/design-notes.md) | Design tokens, component states, Stitch→XML layout mapping |
| [docs/design/stitch/DESIGN.md](design/stitch/DESIGN.md) | Stitch design-system specification |
| [docs/design/stitch/screens/](design/stitch/screens/) | Stitch HTML references and exported screenshots |
| [docs/media/README.md](media/README.md) | Live emulator Stitch Dark & Light Mode UI showcase (`docs/media/screens/stitch-*.png`) |

## Contributing

| Doc | Contents |
|---|---|
| [CONTRIBUTING.md](../CONTRIBUTING.md) | Setup, test gates, layout, branching and PR rules |
| [CHANGELOG.md](../CHANGELOG.md) | Release history and unreleased changes |
