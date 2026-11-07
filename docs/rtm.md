# Requirement Traceability Matrix (RTM)

> Traceability matrix mapping every shipped product capability to its owning
> backend/Android packages, automated verification suites, and live showcase
> evidence. Every PR that modifies or adds a capability updates the
> corresponding module, test, and verification evidence columns.

Test IDs below point to source coverage for each capability row. Refer to the
CI run for execution results and counts for the exact revision under review.

| Domain / Capability | Feature ID | Module (module/package) | Verification Suite | Live & Visual Evidence |
|---|---|---|---|---|
| Direct Chat & Offline Outbox | FR-C1..C7 | `server/message`, `server/ws`; `app/feature/chat`, `core/network/stomp/`, `core/database` | `MessageFlowIT`, `MessageServiceTest`, `MessageRepositoryTest`, `ChatViewModelTest`, `ChatUiLogicTest` | Runbook steps 3–4; Stitch inbox/chat frames (`docs/media/README.md`) |
| Message Editing | F1 edit | `server/message`; `app/feature/chat`, `core/database` | `MessageMutationServiceTest`, `MessageBroadcastListenerTest`, `MessageRepositoryTest`, `MessageFeatureFlowIT`, `MessageResolverTest` | In-chat message editing workflow |
| Message Deletion | F2 delete | `server/message`; `app/feature/chat`, `core/database` | `MessageMutationServiceTest`, `MessageBroadcastListenerTest`, `MessageRepositoryTest`, `MessageFeatureFlowIT` | Tombstone rendering & reply preview |
| Conversation Search | F4 search | `server/message`; `app/feature/chat`, `core/ui` | `MessageSearchServiceTest`, `MessageRateLimitInterceptorTest`, `SearchHighlightHelperTest`, `SearchUiRulesTest`, `MessageFeatureFlowIT` | `stitch-05-in-chat-search.png` (`docs/media/README.md`) |
| Privacy & Presence | F3 presence | `server/presence`, `server/user`; `app/data`, `app/core/ui` | `PresenceFlowIT`, `PresenceServiceTest`, `PresenceSessionRegistryTest`, `PresenceBroadcastListenerTest`, `UserSettingsServiceTest`, `UserSettingsFlowIT`, `PresenceRepositoryTest` | Direct-peer Active Now strip & presence toggle (`stitch-01`, `stitch-07`) |
| Group Chat & Admin | FR-D1..D5 | `server/conversation`; `app/feature/group` | `GroupFlowIT`, `GroupServiceTest`, `GroupDetailViewModelTest` | Runbook step 5; `stitch-10-create-group.png` |
| Contacts & Discovery | FR-B1..B4 | `server/user`, `server/contact`; `app/feature/contacts` | `ContactServiceTest`, `ContactConversationIT`, `ContactsViewModelTest` | Runbook step 2; `stitch-09-contacts-cards.png` |
| Account & Auth | FR-A1..A6 | `server/auth`; `app/feature/auth`, `core/storage/TokenStore` | `AuthFlowIT`, `AuthServiceTest`, `TokenServiceTest`, `JwtServiceTest`, `AuthViewModelTest`, `TokenStoreTest` | Runbook step 1; `01-login-dark-en.png`, `02-register-dark-en.png` |
| Bilingual Localization (Vi/En) | FR-F1..F2 | `app/core/common/AppLocaleManager.java`, `res/values*` | `AppLocaleManagerTest`, `StringParityTest` | Runbook step 8; `docs/i18n-audit.md` |
| Multimedia & File Sharing | FR-E1..E4 | `server/attachment`, `StorageService`; `app/feature/media` | `AttachmentFlowIT`, `AttachmentServiceTest`, `LocalStorageServiceTest`, `MagicBytesTest`, `AttachmentRepositoryTest` | Runbook step 6 |
| State & Lifecycle Resilience | FR-G1 | `ChatViewModel`, `SavedStateHandle`, Room | `ChatViewModelTest`, `docs/rotation-audit.md` | Runbook step 8; `16-chat-landscape-dark-en.png` |
| Rich Interactions (Reply, Reactions, Pins) | FR-H1..H3 | `server/message` (reactions/pins/reply); `app/feature/chat/ChatInteractionsViews.java` | `InteractionFlowIT`, `ReactionServiceTest`, `PinServiceTest`, `MessageRepositoryTest` | Runbook step 7; `stitch-04-chat-bubbles-reaction.png` |
| Message Forwarding | F5 forward | `app/feature/chat`, `app/core/ui` | `ForwardFilterTest`, `ChatViewModelTest` | Text-only message forwarding (attachments excluded by `ForwardFilter`) |
| Theme Preferences | F6 local theme | `app/core/ui`, `app/data` | `ThemeMapperTest`, `SettingsRepositoryTest` | `SYSTEM` / `LIGHT` / `DARK` (`stitch-01..02`, `stitch-07..08`) |
| 1-to-1 Voice Calls (WebRTC) | Call signaling + Android call UI | `server/call`; `app/call` | `CallFlowIT`, `CallServiceTest`, `CallRegistryTest`, `CallStateMachineTest`, `CallTimeoutSchedulerTest`, `CallControllerTest`, `CallManagerTest`, `CallFrameCodecTest`, `CallUiModelMapperTest`, `CallLogFormatterTest` | Live E2E `tools/e2e/calls/call_e2e.py`; `09-outgoing-call-dark-en.png` |
| AI Conversation Summary | Summary engines + consent + Android UI | `server/summary`; `app/summary`, `app/feature/summary` | `SummaryApiIT`, `SummaryServiceTest`, `SummaryProviderSelectorTest`, `CitationGuardTest`, `RedactorTest`, `PromptBuilderTest`, `LlmSummaryProviderTest`, `SummaryViewModelTest`, `SummaryRepositoryTest`, `AiConsentRepositoryTest` | `stitch-06-ai-summary-sheet.png` (`docs/media/README.md`) & `s4-*`/`s5-*` (`docs/screenshots-manifest.md`) |
| Design System & Quality Gates | NFR-4, NFR-6 | `core/ui`, `feature/*`, `.github/workflows/ci.yml` | `RoomMigrationTest`, `ProductionConfigurationTest`; CI workflow gates | Stitch Dark/Light Mode showcase (`docs/media/README.md`) + test reports |
| Documentation & Architecture Q&A | Architecture & operations docs | `docs/` | `tools/check-markdown-links.py`, `tools/build-report.py` | `docs/README.md`, `docs/architecture.md`, `docs/viva-questions.md` |
| Release & Packaging Gates | Release verification | `tools/`, `docs/packaging-checklist.md` | `tools/sign-release.sh`, `tools/package-submission.sh` | Signed release APK & fresh-clone checklist |
