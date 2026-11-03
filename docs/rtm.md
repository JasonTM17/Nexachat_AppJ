# Requirement Traceability Matrix (RTM)

> This is the living RTM for NexaChat (seeded from the master design, §20.2).
> **It is updated at the end of every phase**: each PR that closes a row must
> update the Test / Demo evidence columns. The matrix answers, in ≤30 seconds,
> the defense question "where is requirement X proven complete?"

| Rubric line | Feature (FR) | Module (module/package) | Test | Demo evidence |
|---|---|---|---|---|
| R2 — chat trực tiếp | FR-C1..C7 | `server/message`, `server/ws`; `app/feature/chat`, `core/network/StompClient`, `core/database` | TC-MSG-01..07, WebSocketFlowIT | Script bước 3–4; screenshots inbox/chat |
| R2 — chat nhóm | FR-D1..D5 | `server/conversation`; `app/feature/group` | TC-GRP-01..06, AuthZIT | Script bước 5 |
| R2 — tìm kiếm/thêm SĐT | FR-B1..B4 | `server/user`, `server/contact`; `app/feature/contacts` | TC-CONTACT-01..05 | Script bước 2 |
| R2 — quản lý tài khoản | FR-A1..A6 | `server/auth`; `app/feature/auth`, `core/storage/TokenStore` | TC-AUTH-01..08, RefreshRotationIT | Script bước 1 |
| R3 — đa ngôn ngữ | FR-F1..F2 | `app/core/common/LocaleManager`, `res/values*` | TC-I18N-01..03 | Script bước 8 |
| R3 — multimedia/file | FR-E1..E4 | `server/attachment`, `StorageService`; `app/feature/media` | TC-FILE-01..05, UploadIT | Script bước 6 |
| R3 — xoay màn hình | FR-G1 | `ChatViewModel`, `SavedStateHandle`, Room | TC-UI-ROT-01 | Script bước 8 |
| R4 — sáng tạo | FR-H1..H3 | `server/message` (reactions/pins/reply); `app/feature/interactions` | TC-INT-01..04 | Script bước 7 |
| R5 — UI + test | NFR-4, NFR-6 | `feature/*/components`, CI workflow | CI run, UI tests | Screenshots bộ đủ + report test |
| R6 — trình bày | — | `docs/` báo cáo, sơ đồ §7/§9, Q&A bank §19 | Diễn tập | Slide + video fallback |
| R1 — bài nộp | — | repo, `docs/packaging-checklist` | Checklist C1 | Zip artifact |

**Governance:** every PR closing an RTM row updates the Test/Demo columns
(see the PR template checkbox); at the end of each milestone member D re-audits
the whole table — the RTM is the map for answering "em chứng minh yêu cầu X
hoàn thành ở đâu" within 30 seconds.
