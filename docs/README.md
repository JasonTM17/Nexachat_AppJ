# NexaChat Documentation Index

Every engineering document in the repository, grouped by domain. Start with
[README.md](../README.md) for the product overview and quick start.

## Overview & architecture

| Doc | Contents |
|---|---|
| [README.md](../README.md) | Product overview, Modern Dark/Light UI showcase, capability map, quick start, repo layout |
| [docs/architecture.md](architecture.md) | Container, module and send-message sequence diagrams; modular monolith rationale, Flyway V1–V20 + V22 + V24–V28, account deletion, recovery email OTP, Google sign-in, and ephemeral state routing |
| [docs/known-limitations.md](known-limitations.md) | Architectural scope and documented engineering trade-offs |
| [docs/play-store-data-safety.md](play-store-data-safety.md) | Google Play Data safety form disclosures, privacy policy declarations, collection scopes, and account deletion handling |

## API, realtime & AI contracts

| Doc | Contents |
|---|---|
| [docs/api-contract.md](api-contract.md) | REST API contract (`/api/v1`) with a feature matrix, migration history and operational notes, covering auth, conversations, messages, reminders, polls, labels, invites, attachments, calls and summary routes |
| [docs/websocket-protocol.md](websocket-protocol.md) | STOMP over WebSocket (`/ws`) destinations, event envelope, delivery guarantees and rate limits |
| [docs/calls.md](calls.md) | 1-to-1 WebRTC voice call signaling, call events, ICE configuration, call history and Android UI |
| [docs/firebase-realtime.md](firebase-realtime.md) | Opt-in Firebase RTDB presence/typing bridge (disabled by default, STOMP default): data model, rules install, offline emulator contract matrix, CI job and known rule gaps |
| [docs/ai-summary.md](ai-summary.md) | Conversation catch-up summary contract, local extractive & OpenAI-compatible (Gemini-tested) LLM engines, citation guard and consent model |
| [docs/rag.md](rag.md) | Conversation RAG QA contract ("Ask this chat"), hybrid BM25 + vector retrieval, MMR diversification, citation validation, eval benchmark |

## Security & operations

| Doc | Contents |
|---|---|
| [docs/security.md](security.md) | Security architecture — authentication, authorization, storage, rate limits, Google sign-in (OpenID Connect), recovery email OTPs, and account deletion / tombstoning |
| [docs/operations.md](operations.md) | Operator setup, Docker Compose runtime configuration, environment variables, Mailpit dev mail sink, Google OAuth setup, Firebase rollout gates, and R8 keep rules check |
| [docs/free-tier-observability.md](free-tier-observability.md) | Storage capacity meters, PromQL, alert rules, and manual Render/Neon/Supabase free-tier checks |
| [docs/deploy-render.md](deploy-render.md) | First public release on Render Free + Neon + Cloudflare R2: Blueprint, env vars, custom domain, cold starts |
| [docs/domain-and-app-links.md](domain-and-app-links.md) | Domain, DNS, Vercel and Android App Links runbook: propagation checks, device testing, signing fingerprint, Vercel build-skip (`ignoreCommand`), and token rotation |
| [docs/production-runbook.md](production-runbook.md) | Production topology, Render/Neon/Supabase/Firebase/Vercel operations, cold-start handling, secret rotation checklist, and free-tier limits |
| [docs/testing.md](testing.md) | Server test setup, PostgreSQL requirements, Firebase rules tests, E2E scripts, site validation, R8 release mapping check, and Mailpit dev setup |
| [tools/README-release.md](../tools/README-release.md) | Upload keystore setup, signed release APK build and `apksigner` verification |
| [demo/seed.sql](../demo/seed.sql) | Re-runnable demo accounts, contacts and sample conversations |
| [demo/lan-up.sh](../demo/lan-up.sh), [demo/lan-up.ps1](../demo/lan-up.ps1) | Local LAN stack bootstrap (Git Bash / PowerShell) |
| [demo/backup.sh](../demo/backup.sh) | PostgreSQL database backup utility |

## Site & web verification

| Doc | Contents |
|---|---|
| [site/README.md](../site/README.md) | Static site (`nexachat.io.vn`), Digital Asset Links (`.well-known/assetlinks.json`), web account deletion flow (`delete-account.html`), security headers, and `check.py` |
| [tools/site-tests/README.md](../tools/site-tests/README.md) | Headless DOM-shim unit tests for `site/delete-account.js` (validation, OTP transitions, rate limits, URL credential hygiene) |

## Design & UI showcase

| Doc | Contents |
|---|---|
| [docs/design/design-notes.md](design/design-notes.md) | Design tokens, component states and XML layout mapping |
| [docs/design/stitch/DESIGN.md](design/stitch/DESIGN.md) | Design system specification and guidelines |
| [docs/design/stitch/screens/](design/stitch/screens/) | HTML design references and exported design comps |
| [docs/media/README.md](media/README.md) | Live emulator Dark (`#0B0E17`) & Light (`#F8FAFC`) Mode UI showcase index |
| [docs/media/screens/](media/screens/) | Captured Dark & Light Mode showcase and core workflow PNGs (`01..17`) |

## Contributing & release history

| Doc | Contents |
|---|---|
| [CONTRIBUTING.md](../CONTRIBUTING.md) | Setup, JDK 21 LTS build & test gates, repository layout, branching and PR rules |
| [docs/release-checklist.md](release-checklist.md) | Release preconditions, server and Android gates, R8 mapping verification (`tools/check-release-r8-mapping.py`), signing, rollout, smoke tests and rollback |
| [docs/android-release.md](android-release.md) | GitHub Actions signed APK release, signing secrets, certificate fingerprints, tags and checksum verification |
| [CHANGELOG.md](../CHANGELOG.md) | Release history and unreleased changes |
