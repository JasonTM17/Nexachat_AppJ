# NexaChat Documentation Index

Every engineering document in the repository, grouped by domain. Start with
[README.md](../README.md) for the product overview and quick start.

## Overview & architecture

| Doc | Contents |
|---|---|
| [README.md](../README.md) | Product overview, Modern Dark/Light UI showcase, capability map, quick start, repo layout |
| [docs/architecture.md](architecture.md) | Container, module and send-message sequence diagrams; modular monolith rationale |
| [docs/known-limitations.md](known-limitations.md) | Architectural scope and documented engineering trade-offs |

## API, realtime & AI contracts

| Doc | Contents |
|---|---|
| [docs/api-contract.md](api-contract.md) | REST API contract (`/api/v1`) with a feature matrix, migration history (V1–V20 + V22 + V24) and operational notes, covering auth, conversations, messages, reminders, polls, labels, invites, attachments, calls and summary routes |
| [docs/websocket-protocol.md](websocket-protocol.md) | STOMP over WebSocket (`/ws`) destinations, event envelope, delivery guarantees and rate limits |
| [docs/calls.md](calls.md) | 1-to-1 WebRTC voice call signaling, call events, ICE configuration, call history and Android UI |
| [docs/ai-summary.md](ai-summary.md) | Conversation catch-up summary contract, local extractive & OpenAI-compatible (Gemini-tested) LLM engines, citation guard and consent model |
| [docs/rag.md](rag.md) | Conversation RAG QA contract ("Ask this chat"), hybrid BM25 + vector retrieval, MMR diversification, citation validation, eval benchmark |

## Security & operations

| Doc | Contents |
|---|---|
| [docs/security.md](security.md) | Security architecture — authentication, authorization, storage, rate limits and hardening |
| [docs/operations.md](operations.md) | Operator setup, Docker Compose runtime configuration, backup and recovery notes |
| [docs/domain-and-app-links.md](domain-and-app-links.md) | Domain, DNS, Vercel and Android App Links runbook: propagation checks, device testing, signing fingerprint, build-skip and token rotation |
| [docs/testing.md](testing.md) | Server test setup, PostgreSQL requirements, environment isolation and concurrent runs |
| [tools/README-release.md](../tools/README-release.md) | Upload keystore setup, signed release APK build and `apksigner` verification |
| [demo/seed.sql](../demo/seed.sql) | Re-runnable demo accounts, contacts and sample conversations |
| [demo/lan-up.sh](../demo/lan-up.sh), [demo/lan-up.ps1](../demo/lan-up.ps1) | Local LAN stack bootstrap (Git Bash / PowerShell) |
| [demo/backup.sh](../demo/backup.sh) | PostgreSQL database backup utility |

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
| [docs/release-checklist.md](release-checklist.md) | Release preconditions, server and Android gates, signing, image publishing, rollout, smoke tests and rollback |
| [CHANGELOG.md](../CHANGELOG.md) | Release history and unreleased changes |
