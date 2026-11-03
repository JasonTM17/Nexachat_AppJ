# BÁO CÁO ĐỒ ÁN — NexaChat
## Real-time Android Messaging Platform

| Trường thông tin | Giá trị |
|---|---|
| Nhóm / Group | `{{GROUP_NAME}}` |
| Môn học / Course | `{{COURSE_NAME}}` |
| Giảng viên / Instructor | `{{INSTRUCTOR}}` |
| Học kỳ / Semester | `{{SEMESTER}}` |
| Ngày nộp / Date | `{{DATE}}` |

| # | Thành viên / Member | MSSV | Vai trò / Role |
|---|---|---|---|
| 1 | `{{MEMBER_1}}` | `{{MSSV_1}}` | Backend Lead — auth & realtime |
| 2 | `{{MEMBER_2}}` | `{{MSSV_2}}` | Backend + DevOps — domain & infra |
| 3 | `{{MEMBER_3}}` | `{{MSSV_3}}` | Android Lead — chat core |
| 4 | `{{MEMBER_4}}` | `{{MSSV_4}}` | Android + QA/Docs — UX & quality |

> Báo cáo này được generate song song: `docs/report.md` (nguồn gốc markdown) →
> `tools/build-report.py` → `docs/report.docx` (bản nộp). Mọi số liệu kỹ thuật
> đều đối chiếu trực tiếp với mã nguồn trong repo tại commit nộp bài.

---

## Mục lục / Table of Contents

1. Giới thiệu & Phạm vi — Introduction & Scope
2. Tự đánh giá theo rubric — Rubric Self-Assessment (R1–R6)
3. Phân tích yêu cầu — Requirements Analysis
4. Kiến trúc hệ thống — System Architecture
5. Thiết kế cơ sở dữ liệu — Database Design (ERD)
6. REST API
7. WebSocket / STOMP Contract
8. Thiết kế bảo mật — Security Design
9. Offline-first Design (Android)
10. Đa ngôn ngữ & Xoay màn hình — i18n & Rotation
11. Tính năng sáng tạo — Creative Feature: Message Interactions
12. Ghi chú triển khai — Implementation Notes
13. Kiểm thử — Testing
14. Triển khai & Demo — Deployment & Demo
15. Hạn chế & hướng phát triển — Limitations & Future Work
16. Phân công công việc — Work Breakdown
17. Tuyên bố sử dụng AI — AI Usage Declaration
18. Kết luận — Conclusion

---

## 1. Giới thiệu & Phạm vi — Introduction & Scope

**NexaChat** là nền tảng nhắn tin thời gian thực trên Android: đăng ký bằng số
điện thoại (chuẩn hóa E.164), tìm và kết nối với người dùng khác qua SĐT, chat
cá nhân và chat nhóm realtime qua WebSocket/STOMP, gửi ảnh/video/audio/file,
giao diện Material 3 song ngữ Việt–Anh, chạy ổn định trong demo LAN nhiều
thiết bị không cần internet.

**Tech stack:** Android client — Kotlin + Jetpack Compose (MVVM + UDF, Hilt,
Room, OkHttp). Backend — Java 17 + Spring Boot 3.4.1 (modular monolith).
Database — PostgreSQL 16 (Flyway). Realtime — STOMP 1.2 over WebSocket.

**In-scope** (7 module theo đề + creative): Authentication & Account · User
Discovery & Phone Search · Private Chat · Group Chat · Multimedia & File ·
Multi-language · Rotation — cộng Creative Feature **Message Interactions**
(Reply/Quote + Emoji Reaction + Pin).

**Out-of-scope** (nói rõ để tránh overclaim): voice/video call (WebRTC),
end-to-end encryption, đồng bộ danh bạ máy, microservices/Kubernetes, admin
dashboard, FCM push notification. Mỗi mục loại đều có lý do và hướng phát
triển được ghi ở §15.

**Cấu trúc repo** (monorepo, một `.git` duy nhất):

```text
nexachat/
├─ android/   Kotlin + Jetpack Compose app (80 files, Hilt, Room, STOMP client)
├─ server/    Spring Boot modular monolith (134 files, REST /api/v1 + STOMP /ws)
├─ docs/      architecture, api-contract, websocket-protocol, security,
│             rtm, demo-runbook, known-limitations, packaging-checklist, report
└─ demo/      seed.sql + lan-up.sh / lan-up.ps1 + backup.sh
```

---

## 2. Tự đánh giá theo rubric — Rubric Self-Assessment (R1–R6)

| Mã | Tiêu chí | Điểm | Trạng thái | Bằng chứng trong repo |
|---|---|---:|---|---|
| R1 | Bài nộp đầy đủ, đúng cấu trúc | 5 | ✅ | `docs/packaging-checklist.md` (C1–C12), `.git` full history, `docs/report.docx`, APK release |
| R2 | Chat 1-1, chat nhóm, tìm kiếm, thêm bằng SĐT, quản lý tài khoản | 20 | ✅ | Module `auth`/`user`/`contact`/`conversation`/`message`; IT: `AuthFlowIT`, `ContactConversationIT`, `GroupFlowIT`, `MessageFlowIT` |
| R3 | Đa ngôn ngữ, multimedia/file, xoay màn hình | 15 | ✅ | `values/`+`values-vi/` 71 keys mỗi bên (`docs/i18n-audit.md`); `attachment` module + magic-byte sniff; `docs/rotation-audit.md` |
| R4 | Chức năng sáng tạo | 5 | ✅ | Message Interactions: reply/react/pin — migration `V3__interactions.sql`, `ReactionController`, `PinController`, `InteractionFlowIT` |
| R5 | Giao diện rõ ràng + toàn bộ unit test pass | 15 | ✅ | Server **284 tests green** (258 unit + 26 IT); Android **134 tests green**; CI gate `.github/workflows/ci.yml` |
| R6 | Thuyết trình, demo, vấn đáp | 40 | ✅ | `docs/demo-runbook.md` (kịch bản 8 phút + fallback), 18 câu vấn đáp trong thiết kế, mỗi quyết định kèm "vì sao" |

**Nguyên tắc xuyên suốt:** mọi claim trong bảng này trace được về file/test
cụ thể — không ghi "end-to-end encryption", không ghi "scalable triệu user".

---

## 3. Phân tích yêu cầu — Requirements Analysis

### 3.1. Functional requirements (FR theo module)

**Module A — Authentication & Account:** đăng ký bằng SĐT E.164 + mật khẩu +
tên hiển thị (trùng SĐT → 409 `PHONE_EXISTS`); login nhận access JWT 15' +
refresh token 14 ngày có rotation; refresh single-flight phía client; logout /
logout-all thu hồi refresh token; xem/sửa profile + avatar; đổi mật khẩu
(revoke mọi phiên khác).

**Module B — Discovery & Contacts:** tìm user chính xác theo SĐT hoặc theo
tên (prefix, ≤20 kết quả, rate-limited); thêm/xóa/sửa alias/xem contact
(idempotent); từ contact mở/tạo direct conversation không trùng
(`direct_key` unique).

**Module C — Private Chat:** gửi/nhận text realtime qua WebSocket; history
cursor pagination (mặc định 30/page, `beforeSeq`/`afterSeq`); inbox +
lastMessage + unreadCount; watermark `last_read_seq` → unread badge + read
receipt "Đã xem" 1-1; idempotent send bằng `clientMessageId`; offline send
queue (optimistic SENDING → SENT/FAILED, tap-to-retry); reconnect backoff
1→2→4→8→16s + jitter, tối đa 5 lần, resync bằng REST; typing indicator.

**Module D — Group Chat:** tạo nhóm ≥2 member (creator = ADMIN); thêm/xóa/xem
member, rời nhóm; chat nhóm realtime có tên người gửi; đổi tên/avatar nhóm
(ADMIN → broadcast `CONVERSATION_UPDATED`); system message
("A đã thêm B", `type=SYSTEM`).

**Module E — Multimedia & File:** Photo Picker (không cần permission) + SAF;
whitelist jpg/png/webp/mp4/mp3/m4a/ogg/pdf/doc/docx/txt; cap 25MB
(`spring.servlet.multipart.max-file-size`); upload multipart có progress;
server sniff magic bytes; download qua endpoint có authZ.

**Module F/G — i18n & Rotation:** Vi + En toàn bộ qua string resources, đổi
ngôn ngữ trong Settings và persist; format thời gian theo locale; rotation
không mất history/draft/scroll, không gọi lại API.

**Module H — Creative (Message Interactions):** reply/quote kể cả cross-type;
reaction emoji toggle (PK composite = idempotent); pin ≤3/conversation
(DIRECT: member nào cũng pin; GROUP: chỉ ADMIN).

### 3.2. Non-functional requirements (NFR)

| ID | Nhóm | Yêu cầu | Đạt bằng |
|---|---|---|---|
| NFR-1 | Performance | Tin nhắn tới đối phương <500ms LAN; inbox <1s; paginate 30 tin | `idx_msg_conv_seq`, denormalized `last_message_*`, inbox projection 1 query |
| NFR-2 | Reliability | Reconnect ≤5 lần backoff + jitter; send idempotent 100% | `clientMessageId` + `UNIQUE(sender_id, client_message_id)`; resync REST |
| NFR-3 | Security | bcrypt; JWT HS256 secret ≥256-bit từ env; mọi endpoint cần Bearer trừ whitelist; WSS ở prod | `SecurityConfig`, `JwtAuthFilter`, `application-prod.yml` fail-fast khi thiếu `JWT_SECRET` |
| NFR-4 | Usability | Material 3; mọi màn có Loading/Empty/Error/Retry; touch target ≥48dp | `core/ui/components` shared states; đợt a11y đã sửa vi phạm 48dp thật |
| NFR-5 | Maintainability | Package-by-feature; không trả JPA entity ra API; không business logic trong Controller/Composable | module `com.nexachat.<feature>` server; `feature/*` + `data/` + `core/` Android |
| NFR-6 | Testability | Toàn bộ unit test pass ở CI; mỗi service authZ có negative test | 284 + 134 tests; 21 negative tests ở quality phase |
| NFR-7 | Portability | minSdk 26, targetSdk 35; emulator + máy thật qua LAN | `build.gradle.kts`; `BuildConfig.API_BASE_URL` |
| NFR-8 | Observability | Log không chứa password/token/file body; `/actuator/health` | log scan sạch (chỉ id/event name) |
| NFR-9 | Constraints | 1 backend + 1 PostgreSQL; media ≤25MB; demo LAN offline | `docker-compose.yml` |

### 3.3. Use case đặc trưng — UC gửi tin 1-1 (main flow)

1. A nhập nội dung → app tạo `clientMessageId`, hiển thị tin SENDING (optimistic).
2. STOMP `SEND /app/conversations/{id}/messages` mang `{clientMessageId, type, content, replyToId?, attachmentIds[]}`.
3. Server xác thực JWT (CONNECT) + kiểm member (SEND interceptor) + validate.
4. Một transaction: `INSERT messages` (`seq` BIGSERIAL, `ON CONFLICT (sender_id, client_message_id)` trả row cũ) + `UPDATE conversations.last_message_*`.
5. Broadcast `MESSAGE_CREATED` tới `/topic/conversations.{id}` **sau commit**.
6. Máy A match `clientMessageId` → SENT; máy B render + unread++ (watermark).

*Alternate:* A offline → tin giữ SENDING trong outbox Room; WS sống lại → flush
theo thứ tự. *Exception:* không còn là member → `/user/queue/errors` → tin
chuyển FAILED + snackbar.

---

## 4. Kiến trúc hệ thống — System Architecture

### 4.1. Sơ đồ tổng (mermaid — xem bản render trong `docs/architecture.md`)

```mermaid
flowchart LR
    subgraph Device["Android (Kotlin + Compose)"]
        UI["Compose Screens<br/>(Material 3)"]
        VM["ViewModel + StateFlow<br/>(UDF)"]
        REPO["Repositories<br/>(offline-first)"]
        subgraph DS["Data Sources"]
            REST["Retrofit/OkHttp<br/>+ AuthInterceptor/Authenticator"]
            STOMP["StompClient<br/>(OkHttp WebSocket + STOMP frames)"]
            ROOM[("Room DB<br/>cache + outbox")]
            STORE["DataStore + TokenStore<br/>(Android Keystore AES)"]
        end
        UI --> VM --> REPO
        REPO --> REST & STOMP & ROOM & STORE
    end

    subgraph Server["Backend — Modular Monolith (Java + Spring Boot)"]
        SEC["Spring Security<br/>JWT filter · Rate limit"]
        subgraph Mod["Modules"]
            AUTH["auth"] --- USER["user · contact"]
            CONV["conversation"] --- MSG["message"]
            ATT["attachment"] --- COMMON["common"]
        end
        WS["WebSocket/STOMP layer<br/>@MessageMapping + ChannelInterceptor authZ"]
        BROKER["SimpleBroker<br/>(in-process)"]
    end

    PG[("PostgreSQL 16<br/>Flyway migrations")]
    VOL[("Volume /data/uploads<br/>StorageService → S3 swappable")]

    REST -- "HTTPS REST /api/v1" --> SEC
    STOMP -- "WS · STOMP frames" --> WS
    SEC --> Mod
    WS --> SEC
    WS --> MSG
    MSG --> BROKER --> STOMP
    Mod --> PG
    ATT --> VOL
```

### 4.2. Vì sao modular monolith, không phải microservices

Module phân tách bằng package + boundary rõ ràng (mỗi module có
Controller/Service/Repository riêng: `auth`, `user`, `contact`, `conversation`,
`message`, `attachment`, `common`, `ws`, `config`), deploy 1 đơn vị. Ở quy mô
đồ án, microservices chỉ thêm phức tạp mạng/transaction phân tán mà không tăng
điểm; SimpleBroker in-process đủ cho 1 instance (hướng nâng cấp: broker relay
RabbitMQ — §15).

### 4.3. Luồng gửi tin — persist-before-broadcast

```text
Máy A                Spring Backend              PostgreSQL          Máy B
  │  optimistic insert Room (SENDING, clientMessageId)                     
  │── STOMP SEND /app/conversations/{id}/messages ──▶                      
  │                       │ authN JWT + authZ member + validate            
  │                       │── TX: INSERT messages (seq BIGSERIAL;          
  │                       │    ON CONFLICT sender+clientMessageId → old)   
  │                       │    UPDATE conversations.last_message_* ──▶     
  │                       │◀─ message{seq, createdAt}                      
  │◀── MESSAGE /topic/conversations.{id} MESSAGE_CREATED ──▶               
  │  match clientMessageId → SENT                    render + unread++     
  │                       (khi B mở chat: POST …/read {upToSeq}            
  │                        → A nhận MESSAGE_READ "Đã xem")                 
```

Điểm then chốt: server **commit DB xong mới fan-out** → tin nhận được luôn đã
bền vững; reconnect chỉ cần `GET messages?afterSeq=` để resync.

### 4.4. Quyết định công nghệ chính (decision log rút gọn)

| # | Quyết định | Chọn | Vì sao |
|---|---|---|---|
| T1 | UI | Jetpack Compose + Material 3 | Theo đề; declarative khớp UDF |
| T2 | App arch | MVVM + UDF + Repository, offline-first | "Clean ở mức phù hợp" — 3 layer rõ ràng |
| T3 | DI | Hilt | Chuẩn Google, compile-time safety |
| T4 | HTTP | Retrofit + OkHttp + Moshi | Authenticator/Interceptor chuẩn cho JWT refresh |
| T5 | Local DB | Room | Query phức tạp (inbox, pagination, outbox) + Flow |
| T6 | Token | TokenStore tự viết: AES/GCM key trong Android Keystore + DataStore | ~100 dòng, không phụ thuộc lib deprecating |
| T8 | Picker | Photo Picker + SAF | Không cần runtime permission |
| T9 | Backend | Spring Boot 3.4.1 MVC, Java 17 | Blocking model đơn giản, khớp JPA |
| T10 | DB access | Spring Data JPA + Flyway | Migration versioned V1→V3 |
| T11 | DB | PostgreSQL 16 | JSONB/constraint mạnh; BIGSERIAL ordering |
| T12 | Realtime | **STOMP over WebSocket + SimpleBroker**, không SockJS | Frame semantics + authZ theo destination có sẵn; client native không cần browser fallback |
| T13 | Auth | **SĐT + password + JWT access/refresh rotation** | OTP cần SMS gateway trả phí, khó test LAN; rubric không đòi verify ownership |
| T14 | JWT | HS256, secret env ≥32 bytes | Một service vừa ký vừa verify |
| T15 | Rate limit | Bucket4j in-memory | Đủ 1 instance; giới hạn per-node ghi thành thật |
| T16 | Media | StorageService interface → LocalFileStorage (volume) | Không phình DB; đổi S3 không sửa caller |
| T17 | MIME | Magic-byte sniffing tự implement (`common/MagicBytes.java`) | Content-type client khai là không tin được |
| T18 | SĐT | libphonenumber E.164 cả client lẫn server | Server normalize lại vì client không đáng tin |

---

## 5. Thiết kế cơ sở dữ liệu — Database Design (ERD)

### 5.1. Quan hệ tổng quan

```text
users ──1:N── refresh_tokens          (phiên đăng nhập, rotation chain)
users ──1:N── contacts                (owner_id → contact_user_id)
users ──N:M── conversations           qua conversation_members (role, last_read_seq)
conversations ──1:N── messages        (seq BIGSERIAL toàn cục)
messages ──1:N── message_attachments  (upload-first, bind khi send — V2)
messages ──1:N── message_reactions    (V3, PK composite = toggle idempotent)
conversations ──1:N── pinned_messages qua message (V3, ≤3/conv enforce ở service)
messages ──1:N── messages             (reply_to_id — self FK, V1)
```

### 5.2. Bảng thực tế (theo migration V1/V2/V3 — `server/src/main/resources/db/migration/`)

**V1__init.sql** — 7 bảng lõi + extensions `pgcrypto`, `pg_trgm`:

| Bảng | Cột chính | Ràng buộc & index đáng chú ý |
|---|---|---|
| `users` | `id` UUID PK, `phone` VARCHAR(16), `password_hash` CHAR(60), `display_name` VARCHAR(60), `avatar_url`, `created_at` | `UNIQUE(phone)`; `CHECK phone ~ '^\+[1-9]\d{7,14}$'` (E.164 ở DB); trigram GIN index cho search theo tên |
| `refresh_tokens` | `id` UUID PK, `user_id` FK→users CASCADE, `token_hash` CHAR(64) UNIQUE, `device_label`, `created_at`, `expires_at`, `revoked_at`, `replaced_by` | Chỉ lưu **SHA-256** của token; partial index `(user_id) WHERE revoked_at IS NULL`; `replaced_by` = rotation chain |
| `contacts` | `owner_id`+`contact_user_id` **composite PK** FK→users CASCADE, `alias_name`, `created_at` | `CHECK owner_id <> contact_user_id` |
| `conversations` | `id` UUID PK, `type` CHECK(DIRECT/GROUP), `direct_key` VARCHAR(80) UNIQUE, `title`, `avatar_url`, `created_by` FK, `last_message_id`, `last_message_seq`, `created_at` | `CHECK ((type='DIRECT') = (direct_key IS NOT NULL))`; `direct_key = LEAST(u1,u2):GREATEST(u1,u2)` chống tạo trùng direct; index inbox `last_message_seq DESC NULLS LAST` |
| `conversation_members` | `conversation_id`+`user_id` **composite PK**, `role` CHECK(ADMIN/MEMBER), `joined_at`, `last_read_seq` | `last_read_seq` = watermark đã đọc → unread count + read receipt |
| `messages` | `id` UUID PK, `seq` **BIGSERIAL UNIQUE**, `conversation_id` FK CASCADE, `sender_id` FK, `client_message_id` UUID, `type` CHECK(TEXT/IMAGE/VIDEO/AUDIO/FILE/SYSTEM), `content`, `reply_to_id` FK→messages, `created_at`, `edited_at`, `deleted_at` | `UNIQUE(sender_id, client_message_id)` = idempotency ở tầng DB; index `(conversation_id, seq DESC)` cho cursor pagination |
| `message_attachments` | `id` UUID PK, `message_id` FK CASCADE, `storage_key` VARCHAR(120) UNIQUE, `original_name`, `mime_type`, `size_bytes`, `width`, `height`, `duration_ms`, `created_at` | `storage_key` do server sinh (uuid) — chống path traversal; `mime_type` là mime đã sniff |

**V2__attachments.sql** — chuyển attachment sang lifecycle
*upload-first, bind-at-send*: thêm `owner_id` (FK→users, NOT NULL, backfill
từ sender của message đã bind), `message_id` → nullable, index `idx_att_owner`.
Upload chèn row với `message_id=NULL`; `MessageService.send()` validate
attachmentId (tồn tại + thuộc sender + chưa bind) rồi set `message_id` trong
cùng transaction.

**V3__interactions.sql** — creative feature:

| Bảng | Cột | Thiết kế |
|---|---|---|
| `message_reactions` | `message_id`+`user_id`+`emoji` **composite PK**, `created_at` | PK composite chính là toggle-idempotency: react lại = DELETE row, insert trùng = `ON CONFLICT DO NOTHING`; CASCADE theo message & user |
| `pinned_messages` | `conversation_id`+`message_id` **composite PK**, `pinned_by` FK, `pinned_at` | "Pin 2 lần" là no-op by construction; trần ≤3/conv enforce ở `PinService` dưới row-lock của conversation; index `idx_pins_message` |

### 5.3. Các quyết định schema phải giải thích được

1. **`seq BIGSERIAL` thay timestamp ordering**: tăng đơn điệu toàn cục, độc lập
   clock; cursor pagination = `seq < ?` / `afterSeq` resync.
2. **`UNIQUE(sender_id, client_message_id)`**: chốt chặn cuối chống duplicate
   khi retry/reconnect — `INSERT … ON CONFLICT` race-safe, không
   check-then-insert.
3. **`direct_key` unique**: hai người bấm "chat" đồng thời chỉ tạo 1 DIRECT —
   constraint DB giải quyết race thay vì code phòng thủ.
4. **Watermark `last_read_seq`** thay bảng read-per-message: nhóm N người × M
   tin = N×M rows; watermark 1 bigint cho unread (`count(seq > last_read_seq)`)
   + "Đã xem" (`partner.last_read_seq >= msg.seq`). Trade-off: không biết
   chính xác từng member nhóm đọc tới đâu — chấp nhận, ghi hướng phát triển.
5. **Denormalized `last_message_*`**: inbox không cần `MAX(seq)` per
   conversation; cập nhật trong cùng transaction insert message.
6. **Soft delete `deleted_at`**: tin xóa vẫn giữ FK integrity (reply_to,
   reactions); `edited_at` đã plumb sẵn (endpoint chưa ship — §15).
7. **JPA**: mọi `@ManyToOne` LAZY (bẫy N+1 kinh điển); transaction boundary ở
   Service `@Transactional`; `ddl-auto=validate` — schema chỉ đổi qua Flyway.

---

## 6. REST API

**Quy ước:** base `/api/v1`; `Authorization: Bearer <accessToken>` cho mọi
endpoint trừ `auth/register`, `auth/login`, `auth/refresh`, `/actuator/health`
và upgrade `/ws`. Error envelope thống nhất
`{"error":{"code","message","details"}}` — client switch theo `code`.
Pagination cursor (`beforeSeq`/`afterSeq`/`limit`), không offset. Danh sách
dưới liệt kê đúng các controller trong `server/src/main/java/com/nexachat/`.

### 6.1. Auth — `AuthController` (`/api/v1/auth`)

| Method & path | Auth | Success | Errors chính |
|---|---|---|---|
| `POST /auth/register` | Public | 201 `AuthResponse` | 400 VALIDATION_FAILED · 409 PHONE_EXISTS · 429 |
| `POST /auth/login` | Public | 200 `AuthResponse` | 401 INVALID_CREDENTIALS · 429 |
| `POST /auth/refresh` | Public | 200 (cặp token mới, rotation) | 401 TOKEN_INVALID / TOKEN_EXPIRED / TOKEN_REUSED |
| `POST /auth/logout` | Bearer | 204 | 401 |
| `POST /auth/logout-all` | Bearer | 204 | 401 |
| `PATCH /auth/password` | Bearer | 204 (revoke các phiên khác) | 401 WRONG_PASSWORD |

`AuthResponse` = `{user, accessToken, accessTokenExpiresIn:900, refreshToken}`.
Rate limit: login/register 10 req/5 phút, refresh 30 req/5 phút (Bucket4j,
`RateLimitInterceptor`).

### 6.2. Users & Contacts — `UserController`, `AvatarController`, `ContactController`

| Method & path | Auth | Success | Errors chính |
|---|---|---|---|
| `GET /users/me` | Bearer | 200 `UserResponse` | 401 |
| `PATCH /users/me` | Bearer | 200 `UserResponse` | 400 |
| `GET /users/search?phone=` hoặc `?q=` | Bearer | 200 `UserResponse[]` (phone→0–1, q→≤20) | 400 · 429 (30/min/user) |
| `POST /users/me/avatar` (multipart) | Bearer | 200 `{avatarUrl}` | 413 · 415 MIME_NOT_ALLOWED |
| `GET /contacts` | Bearer | 200 `ContactResponse[]` | 401 |
| `POST /contacts {phone}` | Bearer | 201 | 404 USER_NOT_FOUND · 409 ALREADY_CONTACT · 429 (30/min) |
| `PATCH /contacts/{userId} {aliasName?}` | Bearer | 200 | 400 · 404 |
| `DELETE /contacts/{userId}` | Bearer | 204 (idempotent) | 401 |

### 6.3. Conversations & Messages — `ConversationController`, `ConversationInboxController`, `GroupController`, `MessageController`

| Method & path | Auth | Success | Errors chính |
|---|---|---|---|
| `POST /conversations/direct` `{phone}` hoặc `{userId}` | Bearer | 201 created / 200 existing (idempotent qua `direct_key`) | 400 · 404 |
| `GET /conversations` | Bearer | 200 `InboxEntry[]` sort `lastMessageSeq DESC` | 401 |
| `POST /conversations/group {title, memberIds[]}` | Bearer | 201 | 400 |
| `PATCH /conversations/{id}` | member ADMIN | 200 → broadcast CONVERSATION_UPDATED | 403 NOT_ADMIN |
| `POST /conversations/{id}/members {userIds[]}` | ADMIN | 200 | 403 · 404 |
| `DELETE /conversations/{id}/members/{userId}` | ADMIN | 204 | 403 |
| `POST /conversations/{id}/leave` | member | 204 | 403 |
| `GET /conversations/{id}/messages?beforeSeq=&afterSeq=&limit=` | member | 200 `MessagePageResponse{items,nextBeforeSeq,hasMore}` | 403 NOT_MEMBER · 404 |
| `POST /conversations/{id}/messages` | member | 201 / 200 dedup hit — REST fallback của đường WS | 403 · 404 · 400 |
| `POST /conversations/{id}/read {upToSeq}` | member | 204 → broadcast MESSAGE_READ | 403 · 400 |

### 6.4. Interactions — `ReactionController`, `PinController`

| Method & path | Auth | Success | Errors chính |
|---|---|---|---|
| `POST /messages/{id}/reactions {emoji}` | member | 204 (toggle: thêm/xóa) | 403 · 404 |
| `GET /conversations/{id}/pins` | member | 200 `PinnedMessageView[]` | 403 |
| `POST /conversations/{id}/pins {messageId}` | DIRECT: member; GROUP: ADMIN | 204 | 403 · 409 PIN_LIMIT (>3) |
| `DELETE /conversations/{id}/pins/{messageId}` | như trên | 204 | 403 |

### 6.5. Attachments & Files — `AttachmentController`, `FileController`

| Method & path | Auth | Success | Errors chính |
|---|---|---|---|
| `POST /attachments` (multipart `file`) | Bearer | 201 `AttachmentResponse` (upload-first, bind khi send) | 413 FILE_TOO_LARGE · 415 MIME_NOT_ALLOWED |
| `GET /attachments/{id}/content` | member của conv chứa file | 200 bytes | 403 · 404 |
| `GET /files/**` (storage_key path) | Bearer | 200 bytes + sniffed Content-Type | 404 FILE_NOT_FOUND |

**Tổng: 31 REST endpoint + `/actuator/health`.** Gửi tin có hai đường — WS
(chính) và REST POST (fallback) — cùng đi qua `MessageService.send()` → một nơi
enforce authZ + idempotency.

---

## 7. WebSocket / STOMP Contract

**Kết nối:** `ws://host/ws` (prod `wss://`); raw WebSocket nói STOMP 1.2, **không
SockJS** (client native, không cần browser fallback). JWT nằm trong
`Authorization` header của frame `CONNECT` — không query param (token trong URL
lọt vào access log). `heart-beat:10000,10000` → phát hiện kết nối chết ~20s.

### 7.1. Destinations

| Hướng | Destination | Body | Ghi chú |
|---|---|---|---|
| C→S SEND | `/app/conversations/{id}/messages` | `{clientMessageId,type,content,replyToId?,attachmentIds[]}` | `WsMessageController` → `MessageService.send()` |
| C→S SEND | `/app/conversations/{id}/typing` | `{}` | debounce client-side |
| C→S SEND | `/app/conversations/{id}/read` | `{upToSeq}` | watermark → MESSAGE_READ |
| S→C | `/topic/conversations.{id}` | event envelope | SUBSCRIBE bị chặn nếu không phải member |
| S→C | `/user/queue/inbox` | event envelope | inbox badge khi chưa subscribe conv |
| S→C | `/user/queue/errors` | `{code,clientMessageId?}` | lỗi SEND bị từ chối |

### 7.2. Event envelope & catalog

Mọi event cùng format — client parse một lần:

```json
{"eventType":"MESSAGE_CREATED","conversationId":"…","serverTs":"2026-11-04T10:31:05.123Z","payload":{…}}
```

| eventType | Payload | Phát khi |
|---|---|---|
| `MESSAGE_CREATED` | MessageResponse (seq, sender, content, attachments, replyTo, reactions) | tin mới persist xong |
| `MESSAGE_UPDATED` | message id + content/editedAt | (contract sẵn; endpoint edit chưa ship) |
| `MESSAGE_DELETED` | message id + deletedAt | (contract sẵn; endpoint delete chưa ship) |
| `MESSAGE_READ` | conversationId, readerId, upToSeq | member cập nhật watermark |
| `REACTION_UPDATED` | `{messageId, reactions:[{emoji,count,userIds[]}]}` | toggle reaction |
| `PIN_UPDATED` | `{conversationId, messageId, pinned}` | pin/unpin |
| `USER_TYPING` | conversationId, senderId | typing frame |
| `CONVERSATION_UPDATED` | title/avatar/members thay đổi | tạo nhóm, đổi nhóm, member add/remove |
| `USER_ONLINE` / `USER_OFFLINE` | (vocabulary tồn tại trong `WsEventType`, chưa emit — §15) | presence — future |

### 7.3. Điểm thiết kế then chốt

1. **Persist-before-broadcast** — commit xong mới fan-out (§4.3).
2. **AuthZ hai lớp** — CONNECT = authN (JWT); SUBSCRIBE/SEND = authZ qua
   `StompAuthChannelInterceptor` kiểm `conversation_members`; client không tự
   chọn được destination nhạy cảm.
3. **Dedup** — sender match `clientMessageId` → SENT; receiver insert theo
   message id (`OnConflictStrategy.IGNORE` trong Room).
4. **Ordering theo `seq`** — tin đến lệch chèn đúng vị trí, không append.
5. **Reconnect** — backoff 1·2·4·8·16s + jitter, tối đa 5 lần; sau reconnect
   resync REST (`GET …/messages?afterSeq=lastSeen`) → "WS là push, REST là
   source of truth".
6. **Giới hạn thành thật** — SimpleBroker in-process không fan-out xuyên node;
   STOMP SEND chưa có rate limit (§15).

---

## 8. Thiết kế bảo mật — Security Design

### 8.1. Credential & token model

- **Password:** bcrypt (hash CHAR(60)); register chuẩn hóa SĐT E.164 bằng
  libphonenumber ở server (client cũng normalize cho UX, server không tin
  client).
- **Access token:** JWT HS256, `sub`=userId, TTL 900s; `type=access` claim
  chặn replay; secret ≥32 bytes từ env — `application-prod.yml` fail-fast nếu
  thiếu `JWT_SECRET`. HS256 vì một service vừa ký vừa verify.
- **Refresh token:** opaque 48 bytes SecureRandom → 64 ký tự base64url; DB chỉ
  lưu `SHA-256(token)` (`token_hash CHAR(64) UNIQUE`) → lộ DB ≠ lộ token dùng
  được. TTL 14 ngày, single-use.
- **Rotation:** mỗi `/refresh` trả token mới, revoke token cũ, link
  `replaced_by` (chuỗi audit RT1→RT2→…). Rotation đọc row dưới
  `SELECT … FOR UPDATE` (PESSIMISTIC_WRITE) → 2 refresh song song không mint
  được 2 successor.
- **Reuse detection:** token đã rotate mà bị present → `401 TOKEN_REUSED` +
  revoke toàn bộ family của user (dấu hiệu theft). Logout idempotent + check
  ownership; đổi password revoke mọi phiên khác.

### 8.2. Transport & authorization

- REST stateless (CSRF off, không session). Chỉ `auth/register|login|refresh`,
  `/actuator/health`, `/error`, `/ws` là permitAll; mọi route còn lại bắt
  buộc Bearer (`SecurityConfig` + `JwtAuthFilter` + `AuthSecurityErrorHandler`
  → 401/403 theo envelope chuẩn).
- STOMP: `/ws` permitAll ở HTTP, JWT kiểm ở CONNECT; SUBSCRIBE/SEND kiểm member
  — authZ server-side, không tin client.
- Dev LAN chạy cleartext `http://`/`ws://` (nói rõ chỉ dev; debug build cho
  cleartext qua `network_security_config` debug-source-set); prod = TLS/WSS,
  `cleartextTraffic=false`.

### 8.3. Upload hardening

- Whitelist mime (jpg/png/webp/mp4/mp3/m4a/ogg/pdf/doc/docx/txt) kiểm bằng
  **magic bytes** tự implement (`common/MagicBytes.java`, 18 unit test) —
  đổi `.exe`→`.png` bị 415 `MIME_NOT_ALLOWED`.
- Cap 25MB cả client lẫn `spring.servlet.multipart` (413 `FILE_TOO_LARGE`).
- `storage_key` uuid server sinh — `original_name` chỉ để hiển thị → chống
  path traversal; download qua endpoint có authZ (member của conversation chứa
  file), không serve static trực tiếp.

### 8.4. Rate limiting & enumeration

| Endpoint | Bucket (Bucket4j in-memory, greedy refill) |
|---|---|
| `POST /auth/login`, `/register` | 10 req / 5 phút |
| `POST /auth/refresh` | 30 req / 5 phút |
| `GET /users/search` | 30 req / phút / user |
| `POST /contacts` | 30 adds / phút / user |

Login sai trả `INVALID_CREDENTIALS` thống nhất (không lộ user tồn tại). Search
chỉ trả public profile tối thiểu. Thừa nhận: contact-add theo SĐT cố hữu
200/404 — giảm rủi ro bằng rate limit, không giấu được hoàn toàn (§15).
Bucket là per-node (in-memory) — ghi thành thật, không claim distributed.

### 8.5. Không claim

Báo cáo **không** tuyên bố E2E encryption: mã hóa đường truyền TLS/WSS ở prod,
server thấy plaintext — E2E là hướng phát triển.

---

## 9. Offline-first Design (Android)

**Source of truth: Room.** WS event → ghi Room → UI recompose. Kill app mở
lại vẫn có history; rotation chỉ là recomposition, không re-fetch.

- **Entities:** `MessageEntity`, `ConversationEntity`, `OutboxEntity`,
  `MessageReactionEntity`, `PinnedMessageEntity` (`core/database/Entities.kt`).
- **Send pipeline (idempotent + queue):** `ChatViewModel.send()` →
  `OutboxDao.insert(SENDING, clientMessageId)` → flush STOMP SEND → ack bằng
  `MESSAGE_CREATED` khớp `clientMessageId` → SENT. WS down → REST fallback
  (`POST /conversations/{id}/messages` cùng `MessageService.send()`). Cả hai
  down → giữ SENDING; tap-to-retry / flush khi reconnect.
- **Token refresh single-flight:** OkHttp `Authenticator` bắt 401 →
  `TokenRepository.refresh()` bọc Mutex (n request 401 vẫn chỉ 1 call
  refresh) → retry request. Refresh fail → logout → navigate Login.
- **UDF:** `ChatUiState = combine(roomMessages, outbox, typingFlow, wsStatus)`
  → `stateIn(WhileSubscribed)`; one-shot event qua Channel + receiveAsFlow.
- **Permissions tối thiểu:** Photo Picker/SAF không cần runtime permission.

---

## 10. Đa ngôn ngữ & Xoay màn hình — i18n & Rotation

### 10.1. i18n

- `values/strings.xml` (EN mặc định) + `values-vi/strings.xml`: **71 keys mỗi
  bên, parity 100%** — kiểm chứng `docs/i18n-audit.md` (0 missing, 0 leak).
- Đổi ngôn ngữ trong Settings, persist: không có `androidx.appcompat` trong
  dependency graph → dùng **framework `android.app.LocaleManager` (API 33+,
  system-persisted)** và `SharedPreferences` + `wrapContext` cho API <33 —
  thoả ý đồ "setApplicationLocales + persist" (ghi chú lệch thiết kế trong
  ledger, có chủ đích).
- Timestamp format theo locale (`DateTimeFormatter` localized).

### 10.2. Rotation — audit từng màn (`docs/rotation-audit.md`)

Nguyên tắc: UI state trong ViewModel (survives config change); draft/scroll
trong `SavedStateHandle`/`rememberSaveable` (survives process death); history
trong Room. Kết quả audit + fix thật:

| Màn | Xử lý |
|---|---|
| Chat | `input` → `SavedStateHandle["chat_input"]`; `listState` = `rememberLazyListState` giữ scroll; messages/outbox từ Room → không mất gì |
| UserSearch | `query` → SavedStateHandle; results refetch (server data, rẻ) |
| MediaViewer | zoom `scale`/`offset` → `rememberSaveable` floats; download lỗi có Retry |
| Contacts/Group/Profile | draft trong UiState/rememberSaveable; dialog sống qua rotation |
| Chấp nhận (documented) | Process-death reset create-group form, contact sheet, rename dialog — transient flow, mở lại sạch từ Room/network |

**Rotation không gọi lại API** — Room Flows phát lại state, verify bằng log.

---

## 11. Tính năng sáng tạo — Creative Feature: Message Interactions

**Chọn: Reply/Quote + Emoji Reaction + Pin** (R4). Vì sao không chọn khác:
WebRTC = rủi ro ổn định cao nhất (signaling, STUN/TURN — demo fail mất R6);
voice message trùng phạm vi "gửi audio" bắt buộc của R3; smart search mơ hồ
khó demo trong 8 phút. Interactions tái dùng toàn bộ pipeline message sẵn có:
+2 bảng (V3), +1 cột dùng sẵn (`reply_to_id` đã có từ V1), +4 endpoint.

### 11.1. Server

- `POST /api/v1/messages/{id}/reactions {emoji}` → 204 toggle. PK composite
  `(message_id, user_id, emoji)` là idempotency: react lại = DELETE, insert
  trùng = `ON CONFLICT DO NOTHING` (`ReactionService`, 7 unit test).
- `GET|POST|DELETE /api/v1/conversations/{id}/pins` → DIRECT: member nào cũng
  pin; GROUP: chỉ ADMIN; trần **≤3/conv** enforce ở `PinService` dưới row-lock
  conversation (15 unit test). Vi phạm → 409 `PIN_LIMIT`.
- Reply: `SendMessageRequest.replyToId` validate cùng conversation;
  `MessageResponse.replyTo` = embedded preview `{senderDisplayName, content,
  deleted}` — reply ảnh bằng text (cross-type) hoạt động.
- Broadcast `REACTION_UPDATED` / `PIN_UPDATED` qua `/topic/conversations.{id}`.

### 11.2. Android

- `feature/interactions/`: ReplyPreview (swipe/long-press → quote strip),
  ReactionBar (emoji chips `count`/`byMe`), PinnedBanner (≤3 pin, tap →
  jumpToMessage trong loaded window).
- Data: DTO + Retrofit + Room (`MessageReactionEntity`, `PinnedMessageEntity`)
  + typed WS events; optimistic với **revert-on-failure** (compare-and-restore
  snapshot — sửa từ review MED-1); pin ngoài window decode từ
  `PinnedMessageView.message` nhúng sẵn (sửa MED-2).
- Tích hợp `ChatViewModel.toggleReaction/togglePin` — reaction bar chỉ hiện
  trên tin đã SENT (không react tin pending).

---

## 12. Ghi chú triển khai — Implementation Notes (khó khăn & cách giải)

Những vấn đề thật gặp phải trong build (từ execution ledger), không phải
lý thuyết:

1. **Testcontainers vỡ trên Docker Desktop 29** — docker-java 1.20.4 không
   negotiate được named pipe. *Giải:* viết lại ITs chạy Postgres thật qua
   env-JDBC (`IT_DB_URL`/`IT_DB_USER`/`IT_DB_PASSWORD`; local mặc định compose
   `jdbc:postgresql://localhost:5433/nexachat`, CI dùng postgres service
   `:5432`). Coverage giữ nguyên — vẫn Postgres thật, chỉ không quản lý
   container lifecycle.
2. **Cổng 5432 bị chiếm trên máy dev** → compose map `5433:5432`; app container
   vẫn nói `db:5432` nội bộ, host mapping chỉ cho tools/ITs.
3. **i18n không có appcompat** — dependency graph thiếu `androidx.appcompat`
   nên `AppCompatDelegate.setApplicationLocales` không khả dụng. *Giải:*
   framework `android.app.LocaleManager` (33+, system-persisted) +
   SharedPreferences/`wrapContext` phía dưới — giữ đúng intent "đổi ngôn ngữ
   persist".
4. **Wire mismatch server↔android** — server emit `byMe`, client mong
   `reactedByMe` → thêm field `byMe` + resolution 3 chiều; `POST /reactions`
   trả 204 → `Unit`-cast CCE khi đọc `.reactions` → guard `runCatching`.
5. **LAN demo phải đổi IP mà không sửa source** — `BuildConfig.API_BASE_URL`
   đọc từ `local.properties`/`-PapiBaseUrl`; debug-source-set
   `network_security_config` cho cleartext LAN (release giữ config chặt).
6. **Accessibility thật, không phải checkbox** — audit tìm ra vi phạm 48dp:
   nút remove-X 22dp, row quá nhỏ, nút "!" retry không label → sửa thành
   whole-bubble retry (additive UX change).
7. **Attachment lifecycle** — V1 buộc `message_id NOT NULL` nên không upload
   trước khi gửi được; V2 chuyển sang upload-first (`owner_id` +
   `message_id` nullable), `MessageService.send()` bind trong cùng
   transaction — đúng UC-09 (upload → nhận attachmentId → gửi kèm
   `attachmentIds`).
8. **Pin ≤3 không cần counter column** — serialize trên row-lock của
   conversation; race pin/unpin giải quyết bằng khoá, không bằng check-count.

---

## 13. Kiểm thử — Testing

### 13.1. Kết quả thật (đếm từ source + CI)

| Tầng | Công cụ | Số test | Trạng thái |
|---|---|---|---:|---|
| Server unit | JUnit5 + Mockito (`mvn test`, surefire) | **258** | ✅ green |
| Server integration | `@SpringBootTest` + Postgres thật qua env-JDBC (failsafe, `*IT`) | **26** | ✅ green |
| **Server tổng** | `mvn -B verify` | **284** | ✅ green |
| Android unit | JUnit + MockK + coroutines-test + Turbine (`testDebugUnitTest`) | **134** | ✅ green |

CI (`.github/workflows/ci.yml`): job `server-test` (postgres:16 service +
`mvn -B verify` với `IT_DB_*` env) + `android-test` + secret-scan — chạy trên
mọi PR và push `main`/`develop`; đỏ là không merge ("suite luôn xanh").

### 13.2. Integration test classes (26 test, Postgres thật)

| IT class | Tests | Cover |
|---|---:|---|
| `auth/AuthFlowIT` | 5 | register→login→refresh rotation→reuse→logout flow |
| `attachment/AttachmentFlowIT` | 5 | upload→bind→download authZ, mime/size reject |
| `contact/ContactConversationIT` | 4 | add contact → open direct, `direct_key` dedup |
| `conversation/GroupFlowIT` | 3 | tạo nhóm → member mgmt → non-member bị chặn |
| `message/MessageFlowIT` | 5 | send WS/REST, idempotency, history cursor, read |
| `message/InteractionFlowIT` | 4 | react toggle, pin limit/policy, reply (TC-INT-01..04) |

### 13.3. Unit test highlight

Server: `MessageServiceTest` (29 — authZ, idempotency, pagination),
`GroupServiceTest` (33), `MagicBytesTest` (18 — mime sniff),
`StompAuthChannelInterceptorTest` (18 — WS authN/Z), `AuthServiceTest` (17),
`AttachmentServiceTest` (16), `PinServiceTest` (15), `LocalStorageServiceTest`
(14), `ContactServiceTest` (12), `PhoneNormalizerTest` (11 — E.164),
`UserServiceTest` (11), `TokenServiceTest` (10 — rotation/reuse),
`ConversationServiceTest` (10), `ReactionServiceTest` (7)…

Android (134 test, 15 lớp): `ChatViewModelTest` (optimistic send, dedup theo
`clientMessageId`, retry), `TokenAuthenticatorTest` (single-flight 401→refresh),
`StompCodecTest` (frame parse/build), `TokenStoreTest`, `InboxViewModelTest`,
`MessageRepositoryTest`, `AttachmentRepositoryTest`, `AuthViewModelTest`,
`SplashViewModelTest`, `ContactsViewModelTest`, `CreateGroupViewModelTest`,
`GroupDetailViewModelTest`, `MediaViewerViewModelTest`, `ProfileViewModelTest`,
`AppLocaleManagerTest`.

### 13.4. Tái hiện / Reproduce

```bash
# Server: Postgres thật rồi verify (unit + IT)
docker compose up -d db          # postgres:16 @ localhost:5433
cd server && mvn -B verify       # 284 tests

# Android unit tests
cd android && ./gradlew testDebugUnitTest   # 134 tests
```

Manual E2E: `docs/demo-runbook.md` §5 — 10 bước demo ~8 phút (2 máy chat 1-1,
3 máy nhóm, offline send, media, interactions, i18n+rotation, CI xanh).

---

## 14. Triển khai & Demo — Deployment & Demo

### 14.1. Docker — một lệnh

```bash
docker compose up -d db app      # lần đầu build image ~3–5 phút
curl http://localhost:8080/actuator/health    # {"status":"UP"}
```

`docker-compose.yml`: `db` = postgres:16-alpine (healthcheck `pg_isready`,
volume `pgdata`, host `5433→5432`); `app` = multi-stage `server/Dockerfile`
(Maven build JDK17 → `eclipse-temurin:17-jre`, non-root user, curl cho
HEALTHCHECK), profile `prod`, Flyway migrate khi boot, volume `app-uploads`
cho `STORAGE_ROOT`, `JWT_SECRET` passthrough env (fallback dev để fresh clone
vẫn boot). Healthcheck `GET /actuator/health` (permitAll), `start_period: 60s`.

### 14.2. LAN demo (`demo/`)

- `./demo/lan-up.sh` (Git Bash) / `lan-up.ps1` (PowerShell): detect LAN IP →
  compose up → chờ health → seed → in URL + accounts.
- `./demo/backup.sh`: `pg_dump` → `demo/backups/nexachat-<timestamp>.sql`
  (gitignored); restore bằng psql.
- `demo/seed.sql`: 4 tài khoản demo — `+84900000001` **An**,
  `+84900000002` **Binh**, `+84900000003` **Chi**, `+84900000004` **Dung**;
  password `password123` (bcrypt cost 10). **Chỉ dùng cho demo.**
- App trỏ backend qua `BuildConfig.API_BASE_URL` — đặt
  `API_BASE_URL=http://<LAN-IP>:8080` trong `android/local.properties` rồi
  rebuild, không sửa source. Fallback: `adb reverse tcp:8080 tcp:8080` +
  `127.0.0.1`; hotspot dự phòng; emulator mặc định `10.0.2.2`.

### 14.3. Kịch bản demo ~8 phút (map rubric — `docs/demo-runbook.md` §5)

Splash → session restore (30s) → tìm theo SĐT + add contact + mở chat (60s) →
chat 1-1 realtime + **tắt wifi → tin xám → bật lại → tin tới** (90s) → read
receipt + unread badge (30s) → nhóm 3 người + member mgmt (90s) → media ảnh/
video/PDF + progress (60s) → creative reply/react/pin (45s) → đổi En→Vi + xoay
màn giữ draft (45s) → test suites xanh (60s) → kiến trúc + ERD + log (60s).

---

## 15. Hạn chế & hướng phát triển — Limitations & Future Work

Đầy đủ ở `docs/known-limitations.md`; tóm tắt trung thực:

### 15.1. Ngoài phạm vi theo thiết kế

| Hạn chế | Vì sao | Hướng phát triển |
|---|---|---|
| Không E2E encryption | Threat model chọn = JWT + TLS; E2EE không nằm trong MVP | Key exchange per-conversation + client crypto |
| Không WebRTC voice/video | Rủi ro ổn định cao nhất; đề tự khuyên loại nếu ảnh hưởng cốt lõi | Signaling + STUN/TURN đã thiết kế sẵn |
| Không FCM push | Optional tier; demo foreground đủ | Integration point đã vẽ sẵn |
| SimpleBroker 1 instance | Đúng cho demo/deploy 1 node | Broker relay RabbitMQ + rate-limit bucket ra Redis/JDBC |

### 15.2. Deferred findings (ghi thành thật)

- STOMP SEND chưa rate-limit (REST có); inbox `GET /conversations` chưa phân
  trang; client chưa proactive-refresh token trước WS reconnect (chờ 401/403);
  presence `USER_ONLINE/OFFLINE` chưa emit; `listPins` N+1 nhỏ (bounded ≤3);
  reactions/pins chưa vào offline outbox (optimistic + revert); pin order
  REST-vs-WS lệch cosmetic; emoji chưa NFC-normalize; **message edit/delete**
  đã plumb `editedAt`/`deletedAt` nhưng endpoint chưa ship; chưa có
  `androidTest`/instrumented suite.
- Env: Testcontainers không chạy được trên Docker Desktop 29 → IT qua
  env-JDBC; LAN demo cleartext (dev-only, prod WSS/TLS); dev JWT secret là
  placeholder (prod fail-fast).
- UX: pin menu hiện cho non-admin (server 403 vẫn chặn + snackbar); jump-to-
  quote/pin chỉ trong loaded window; reaction bar ẩn trên tin pending.

---

## 16. Phân công công việc — Work Breakdown

> Template — điền tên thật khi nộp. Nguyên tắc: mỗi thành viên sở hữu
> **vertical slice** (màn hình + API + test) → commit/PR mang tên thật, là
> bằng chứng cho hệ số đóng góp 0.9–1.1. `git shortlog -sn` phải cân đối.

| Thành viên | MSSV | Sở hữu backend | Sở hữu Android | Test & tài liệu | Tỉ lệ đóng góp |
|---|---|---|---|---|---|
| `{{MEMBER_1}}` | `{{MSSV_1}}` | auth/*, token rotation, rate limit, WS/STOMP layer, MessageService | — (pair review Auth UI) | Auth/message tests; báo cáo phần security | `{{PCT_1}}%` |
| `{{MEMBER_2}}` | `{{MSSV_2}}` | user, contact, conversation, attachment + storage, Flyway | — | Docker/compose/CI; báo cáo phần DB+API | `{{PCT_2}}%` |
| `{{MEMBER_3}}` | `{{MSSV_3}}` | — | Scaffold, auth UI, session, StompClient, Room, Chat/Inbox, offline outbox | Android unit tests chat; báo cáo Android arch | `{{PCT_3}}%` |
| `{{MEMBER_4}}` | `{{MSSV_4}}` | — (pair attachment API client) | Contacts/search/group UI, media UI, i18n, rotation, Settings, interactions UI | Test matrix, manual E2E, screenshots, báo cáo + RTM + slide | `{{PCT_4}}%` |

Review chéo: {{MEMBER_1}}↔{{MEMBER_2}}, {{MEMBER_3}}↔{{MEMBER_4}}; cross-pair
attachment ({{MEMBER_2}}↔{{MEMBER_4}}) và auth ({{MEMBER_1}}↔{{MEMBER_3}}).
WS layer + TokenAuthenticator pair-program — hai điểm khó nhất, ≥2 người hiểu.

---

## 17. Tuyên bố sử dụng AI — AI Usage Declaration

AI tools được dùng để brainstorm thiết kế, sinh boilerplate và gợi ý test
case. **Mọi dòng merge — AI hỗ trợ hay không — đều đã qua review và owner
giải thích được** (quy tắc "explain-back": không merge đoạn nào mà người merge
không giải thích được khi vấn đáp).

| Thành viên | Vai trò | Phần AI hỗ trợ | Xác nhận explain-back |
|---|---|---|---|
| `{{MEMBER_1}}` | Backend Lead (auth, realtime) | `{{AI_AREA_1}}` | ☐ |
| `{{MEMBER_2}}` | Backend + DevOps | `{{AI_AREA_2}}` | ☐ |
| `{{MEMBER_3}}` | Android Lead (chat core) | `{{AI_AREA_3}}` | ☐ |
| `{{MEMBER_4}}` | Android + QA/Docs | `{{AI_AREA_4}}` | ☐ |

Mỗi PR khai báo AI involvement trong description (`.github/pull_request_template.md`).

---

## 18. Kết luận — Conclusion

NexaChat ship đầy đủ phạm vi rubric: chat 1-1/nhóm realtime qua STOMP-over-
WebSocket, tìm & thêm bằng SĐT E.164, quản lý tài khoản với JWT refresh
rotation + reuse detection, media/file upload-first với magic-byte hardening,
i18n Vi/En 100% parity, rotation an toàn, và creative feature Message
Interactions trên nền schema V1→V3 có kiểm soát bằng Flyway.

Chất lượng được chứng minh bằng số liệu thật: **284 test server** (258 unit +
26 integration trên Postgres thật) và **134 test Android**, CI gate xanh trên
mọi PR; deploy một lệnh `docker compose up`; demo LAN có runbook + fallback.
Các hạn chế được khai báo trung thực (không E2E, SimpleBroker 1 node, presence
chưa emit, edit/delete plumb sẵn chưa ship) — mỗi hạn chế kèm hướng phát triển
rõ ràng, đúng nguyên tắc "không overclaim" của đồ án.
