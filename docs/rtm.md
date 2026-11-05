# Requirement Traceability Matrix (RTM)

> This is the living RTM for NexaChat (seeded from the master design, §20.2).
> **It is updated at the end of every phase**: each PR that closes a row must
> update the Test / Demo evidence columns. The matrix answers, in ≤30 seconds,
> the defense question "where is requirement X proven complete?"

Test IDs below point to source coverage for each row (the sample TC-* cases
are covered by these tests). Refer to the CI run for execution results and
counts for the exact revision under review.

| Rubric line | Feature (FR) | Module (module/package) | Test | Demo evidence |
|---|---|---|---|---|
| R2 — chat trực tiếp | FR-C1..C7 | `server/message`, `server/ws`; `app/feature/chat`, `core/network/stomp/`, `core/database` | `MessageFlowIT`, `MessageServiceTest`, `MessageRepositoryTest`, `ChatViewModelTest`, `ChatUiLogicTest` | Script bước 3–4; screenshots inbox/chat |
| R2 — sửa tin nhắn | F1 edit | `server/message`; `app/feature/chat`, `core/database` | `MessageMutationServiceTest`, `MessageBroadcastListenerTest`, `MessageRepositoryTest` | Demo sửa tin nhắn trong chat |
| R2 — xóa tin nhắn | F2 delete | `server/message`; `app/feature/chat`, `core/database` | `MessageMutationServiceTest`, `MessageBroadcastListenerTest`, `MessageRepositoryTest` | Demo tombstone và reply preview |
| R2 — tìm tin nhắn | F4 search | `server/message`; `app/feature/chat`, `core/ui` | `MessageSearchServiceTest`, `MessageRateLimitInterceptorTest`, `SearchHighlightHelperTest` | Demo tìm trong một conversation |
| R2 — presence riêng tư | F3 presence | `server/presence`, `server/user`; `app/data`, `app/core/ui` | `PresenceFlowIT`, `PresenceServiceTest`, `PresenceSessionRegistryTest`, `UserSettingsServiceTest`, `PresenceRepositoryTest` | Demo DIRECT peer và cài đặt ẩn presence |
| R2 — chat nhóm | FR-D1..D5 | `server/conversation`; `app/feature/group` | `GroupFlowIT`, `GroupServiceTest`, `GroupDetailViewModelTest` | Script bước 5 |
| R2 — tìm kiếm/thêm SĐT | FR-B1..B4 | `server/user`, `server/contact`; `app/feature/contacts` | `ContactServiceTest`, `ContactConversationIT`, `ContactsViewModelTest` | Script bước 2 |
| R2 — quản lý tài khoản | FR-A1..A6 | `server/auth`; `app/feature/auth`, `core/storage/TokenStore` | `AuthFlowIT`, `AuthServiceTest`, `TokenServiceTest`, `JwtServiceTest`, `AuthViewModelTest`, `TokenStoreTest` | Script bước 1 |
| R3 — đa ngôn ngữ | FR-F1..F2 | `app/core/common/LocaleManager`, `res/values*` | `AppLocaleManagerTest`, `StringParityTest` | Script bước 8 |
| R3 — multimedia/file | FR-E1..E4 | `server/attachment`, `StorageService`; `app/feature/media` | `AttachmentFlowIT`, `AttachmentServiceTest`, `LocalStorageServiceTest`, `MagicBytesTest`, `AttachmentRepositoryTest` | Script bước 6 |
| R3 — xoay màn hình | FR-G1 | `ChatViewModel`, `SavedStateHandle`, Room | `ChatViewModelTest`, `docs/rotation-audit.md` | Script bước 8 |
| R4 — sáng tạo | FR-H1..H3 | `server/message` (reactions/pins/reply); `app/feature/chat/ChatInteractions.kt` | `InteractionFlowIT`, `ReactionServiceTest`, `PinServiceTest`, `MessageRepositoryTest` | Script bước 7 |
| R4 — chuyển tiếp text | F5 forward | `app/feature/chat`, `app/core/ui` | `ForwardFilterTest`, `ChatViewModelTest` | Demo chuyển tiếp text; attachment không được chuyển tiếp |
| R3 — theme | F6 local theme | `app/core/ui`, `app/data` | `ThemeMapperTest`, `SettingsRepositoryTest` | Demo SYSTEM/LIGHT/DARK trên thiết bị |
| R5 — UI + test | NFR-4, NFR-6 | `feature/*/components`, CI workflow | `RoomMigrationTest`, `ProductionConfigurationTest`; use the current CI run for test outcome and counts | Screenshots bộ đủ + report test |
| R6 — trình bày | — | `docs/` báo cáo, sơ đồ §7/§9, Q&A bank §19 | Diễn tập | Slide + video fallback |
| R1 — bài nộp | — | repo, `docs/packaging-checklist` | Checklist C1 | Zip artifact |

**Governance:** every PR closing an RTM row updates the Test/Demo columns
(see the PR template checkbox); at the end of each milestone member D re-audits
the whole table — the RTM is the map for answering "em chứng minh yêu cầu X
hoàn thành ở đâu" within 30 seconds.
