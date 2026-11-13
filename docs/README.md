# NexaChat documentation index

Start here to navigate engineering documentation by audience. The product overview and
quick start remain in [README.md](../README.md).

## Contributors

| Document | Summary |
|---|---|
| [architecture/architecture.md](architecture/architecture.md) | Modular monolith layout, Flyway migrations, sync cursors, and sequence diagrams. |
| [architecture/api-contract.md](architecture/api-contract.md) | REST `/api/v1` route inventory, feature matrix, and migration history. |
| [architecture/websocket-protocol.md](architecture/websocket-protocol.md) | STOMP destinations, envelopes, delivery guarantees, and rate limits. |
| [architecture/android-sync.md](architecture/android-sync.md) | Android sync v2 checkpoints, Room 13→14, and legacy sync boundary. |
| [architecture/calls.md](architecture/calls.md) | WebRTC call signaling, STOMP events, and Android call UI. |
| [architecture/firebase-realtime.md](architecture/firebase-realtime.md) | Optional Firebase RTDB presence/typing bridge (STOMP default). |
| [architecture/ai-summary.md](architecture/ai-summary.md) | Conversation catch-up summary engines, consent, and citations. |
| [architecture/rag.md](architecture/rag.md) | “Ask this chat” retrieval, hybrid search, and eval harness. |
| [quality/testing.md](quality/testing.md) | Server/Android test setup, CI gates, E2E scripts, and site checks. |
| [quality/known-limitations.md](quality/known-limitations.md) | Documented trade-offs, free-tier limits, and audit caveats. |
| [design/design-notes.md](design/design-notes.md) | Material 3 tokens and XML layout mapping. |
| [design/stitch/DESIGN.md](design/stitch/DESIGN.md) | Stitch design-system specification. |
| [media/README.md](media/README.md) | Dark/light UI showcase frame index. |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | JDK 21 build gates, branching, and PR checklist. |

## Operators

| Document | Summary |
|---|---|
| [operations/operations.md](operations/operations.md) | Docker Compose, environment variables, Mailpit, OAuth, and R8 checks. |
| [operations/production-runbook.md](operations/production-runbook.md) | Render/Neon/Supabase/Vercel production topology and incident playbooks. |
| [operations/deploy-render.md](operations/deploy-render.md) | First deploy on Render Free + Neon + object storage. |
| [operations/backup-recovery.md](operations/backup-recovery.md) | Local PostgreSQL and attachment backup/restore (`tools/backup/`). |
| [operations/free-tier-observability.md](operations/free-tier-observability.md) | Capacity meters, PromQL, and free-tier monitoring checks. |
| [operations/domain-and-app-links.md](operations/domain-and-app-links.md) | DNS, Vercel site, App Links, and signing fingerprints. |
| [operations/google-signin-setup.md](operations/google-signin-setup.md) | Google Cloud OAuth clients and Android client IDs. |
| [demo/seed.sql](../demo/seed.sql) | Re-runnable demo accounts and sample conversations. |
| [demo/lan-up.sh](../demo/lan-up.sh), [demo/lan-up.ps1](../demo/lan-up.ps1) | LAN stack bootstrap (Bash / PowerShell). |
| [site/README.md](../site/README.md) | Static site, web account deletion, and `site/check.py`. |

## Release managers

| Document | Summary |
|---|---|
| [release/release-checklist.md](release/release-checklist.md) | Preconditions, gates, signing, smoke tests, and rollback. |
| [release/android-release.md](release/android-release.md) | GitHub Actions signed APK workflow and keystore secrets. |
| [release/play-store-data-safety.md](release/play-store-data-safety.md) | Play Console Data safety form mapping. |
| [tools/release/README.md](../tools/release/README.md) | Upload keystore setup and `apksigner` verification. |
| [CHANGELOG.md](../CHANGELOG.md) | Keep a Changelog release history. |
| [release-notes/](release-notes/) | Per-tag operator notes (`docs/release-notes/<tag>.md`; tooling reads this path). |

## Security and quality

| Document | Summary |
|---|---|
| [quality/security.md](quality/security.md) | AuthN/Z, storage, rate limits, Google OIDC, OTP, and account deletion. |
| [quality/testing.md](quality/testing.md) | Test databases, Firebase rules tests, and hygiene scripts. |
| [quality/known-limitations.md](quality/known-limitations.md) | Engineering scope boundaries and residual risks. |
| [tools/site-tests/README.md](../tools/site-tests/README.md) | Headless tests for `site/delete-account.js`. |

## Archive (historical surveys)

| Document | Summary |
|---|---|
| [archive/TECHNICAL_ROADMAP.md](archive/TECHNICAL_ROADMAP.md) | 2026-11-08 publication-grade technical survey (superseded by live docs). |
| [archive/TEST_INFRA.md](archive/TEST_INFRA.md) | Historical test-infrastructure survey notes. |
| [archive/TEST_READY.md](archive/TEST_READY.md) | Historical test-readiness survey notes. |

## Where does X live?

| Question | Location |
|---|---|
| REST route list and `/api/v1` contract? | [architecture/api-contract.md](architecture/api-contract.md) |
| WebSocket/STOMP destinations? | [architecture/websocket-protocol.md](architecture/websocket-protocol.md) |
| Environment variables and Docker Compose? | [operations/operations.md](operations/operations.md) |
| Production incident runbook? | [operations/production-runbook.md](operations/production-runbook.md) |
| How do I run tests locally? | [quality/testing.md](quality/testing.md) |
| Security model and threat boundaries? | [quality/security.md](quality/security.md) |
| Release checklist before tagging? | [release/release-checklist.md](release/release-checklist.md) |
| Play Store / Data safety disclosures? | [release/play-store-data-safety.md](release/play-store-data-safety.md) |
