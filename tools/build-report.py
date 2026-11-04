#!/usr/bin/env python3
"""Build docs/report.docx — the NexaChat final report.

The docx is generated DIRECTLY from the structured content in this script
(headers / paragraphs / tables / code boxes) — it mirrors docs/report.md
section-for-section, which remains the canonical source. python-docx cannot
render Mermaid, so the architecture and ERD diagrams are expressed as monospace
pseudo-diagrams inside bordered code boxes plus formatted tables.

Usage (from repo root or anywhere):
    python tools/build-report.py

Output: docs/report.docx
Requires: python-docx >= 1.1
"""
from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "report.docx"

MONO = "Consolas"


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------
def h1(doc, text):
    return doc.add_heading(text, level=1)


def h2(doc, text):
    return doc.add_heading(text, level=2)


def h3(doc, text):
    return doc.add_heading(text, level=3)


def para(doc, text="", bold=False, italic=False, size=11, align=None):
    p = doc.add_paragraph()
    run = p.add_run(text)
    run.bold = bold
    run.italic = italic
    run.font.size = Pt(size)
    if align is not None:
        p.alignment = align
    return p


def rich(doc, segments, align=None):
    """segments: list of (text, bold, italic)."""
    p = doc.add_paragraph()
    for text, bold, italic in segments:
        run = p.add_run(text)
        run.bold = bold
        run.italic = italic
    if align is not None:
        p.alignment = align
    return p


def bullet(doc, text, bold_prefix=None):
    p = doc.add_paragraph(style="List Bullet")
    if bold_prefix:
        run = p.add_run(bold_prefix)
        run.bold = True
    p.add_run(text)
    return p


def numbered(doc, text):
    return doc.add_paragraph(text, style="List Number")


def _shade(cell, hex_fill):
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:fill"), hex_fill)
    tc_pr.append(shd)


def code_box(doc, text):
    """Monospace code inside a bordered, shaded single-cell table."""
    tbl = doc.add_table(rows=1, cols=1)
    tbl.style = "Table Grid"
    cell = tbl.cell(0, 0)
    _shade(cell, "F2F2F2")
    cp = cell.paragraphs[0]
    for i, line in enumerate(text.split("\n")):
        if i:
            cp = cell.add_paragraph()
        run = cp.add_run(line if line else " ")
        run.font.name = MONO
        run.font.size = Pt(8.5)
        cp.paragraph_format.space_after = Pt(0)
        cp.paragraph_format.space_before = Pt(0)
    doc.add_paragraph().paragraph_format.space_after = Pt(2)
    return tbl


def table(doc, headers, rows, widths=None, font_size=9):
    tbl = doc.add_table(rows=1, cols=len(headers))
    tbl.style = "Table Grid"
    hdr = tbl.rows[0].cells
    for i, htext in enumerate(headers):
        hdr[i].text = ""
        run = hdr[i].paragraphs[0].add_run(htext)
        run.bold = True
        run.font.size = Pt(font_size)
        _shade(hdr[i], "D9E2F3")
    for row in rows:
        cells = tbl.add_row().cells
        for i, val in enumerate(row):
            cells[i].text = ""
            run = cells[i].paragraphs[0].add_run(str(val))
            run.font.size = Pt(font_size)
    if widths:
        for i, w in enumerate(widths):
            for row in tbl.rows:
                row.cells[i].width = Cm(w)
    doc.add_paragraph().paragraph_format.space_after = Pt(2)
    return tbl


def note(doc, text):
    p = doc.add_paragraph()
    run = p.add_run(text)
    run.italic = True
    run.font.size = Pt(9.5)
    run.font.color.rgb = RGBColor(0x44, 0x44, 0x44)
    return p


def page_break(doc):
    doc.add_page_break()


# --------------------------------------------------------------------------
# document
# --------------------------------------------------------------------------
def build():
    doc = Document()

    # base style
    normal = doc.styles["Normal"]
    normal.font.name = "Calibri"
    normal.font.size = Pt(11)

    core = doc.core_properties
    core.title = "NexaChat — Báo cáo đồ án"
    core.subject = "Real-time Android Messaging Platform"
    core.author = "{{GROUP_NAME}}"

    # ------------------------------------------------------------- cover --
    for _ in range(4):
        doc.add_paragraph()
    para(doc, "BÁO CÁO ĐỒ ÁN", bold=True, size=26, align=WD_ALIGN_PARAGRAPH.CENTER)
    para(doc, "NexaChat", bold=True, size=34, align=WD_ALIGN_PARAGRAPH.CENTER)
    para(doc, "Real-time Android Messaging Platform", italic=True, size=14,
         align=WD_ALIGN_PARAGRAPH.CENTER)
    doc.add_paragraph()
    para(doc, "Kotlin + Jetpack Compose · Java + Spring Boot · PostgreSQL · WebSocket/STOMP",
         size=11, align=WD_ALIGN_PARAGRAPH.CENTER)
    for _ in range(3):
        doc.add_paragraph()

    table(doc,
          ["Trường thông tin", "Giá trị"],
          [["Nhóm / Group", "{{GROUP_NAME}}"],
           ["Môn học / Course", "{{COURSE_NAME}}"],
           ["Giảng viên / Instructor", "{{INSTRUCTOR}}"],
           ["Học kỳ / Semester", "{{SEMESTER}}"],
           ["Ngày nộp / Date", "{{DATE}}"]],
          widths=[6, 9], font_size=11)

    doc.add_paragraph()
    table(doc,
          ["#", "Thành viên / Member", "MSSV", "Vai trò / Role"],
          [["1", "{{MEMBER_1}}", "{{MSSV_1}}", "Backend Lead — auth & realtime"],
           ["2", "{{MEMBER_2}}", "{{MSSV_2}}", "Backend + DevOps — domain & infra"],
           ["3", "{{MEMBER_3}}", "{{MSSV_3}}", "Android Lead — chat core"],
           ["4", "{{MEMBER_4}}", "{{MSSV_4}}", "Android + QA/Docs — UX & quality"]],
          widths=[1, 4.5, 3, 6.5], font_size=10)

    note(doc, "Báo cáo này được generate song song: docs/report.md (nguồn gốc "
              "markdown) → tools/build-report.py → docs/report.docx (bản nộp). "
              "Mọi số liệu kỹ thuật đều đối chiếu trực tiếp với mã nguồn trong "
              "repo tại commit nộp bài.")
    page_break(doc)

    # --------------------------------------------------------------- TOC --
    h1(doc, "Mục lục / Table of Contents")
    toc = [
        "1. Giới thiệu & Phạm vi — Introduction & Scope",
        "2. Tự đánh giá theo rubric — Rubric Self-Assessment (R1–R6)",
        "3. Phân tích yêu cầu — Requirements Analysis",
        "4. Kiến trúc hệ thống — System Architecture",
        "5. Thiết kế cơ sở dữ liệu — Database Design (ERD)",
        "6. REST API",
        "7. WebSocket / STOMP Contract",
        "8. Thiết kế bảo mật — Security Design",
        "9. Offline-first Design (Android)",
        "10. Đa ngôn ngữ & Xoay màn hình — i18n & Rotation",
        "11. Tính năng sáng tạo — Creative Feature: Message Interactions",
        "12. Ghi chú triển khai — Implementation Notes",
        "13. Kiểm thử — Testing",
        "14. Triển khai & Demo — Deployment & Demo",
        "15. Hạn chế & hướng phát triển — Limitations & Future Work",
        "16. Phân công công việc — Work Breakdown",
        "17. Tuyên bố sử dụng AI — AI Usage Declaration",
        "18. Kết luận — Conclusion",
    ]
    for line in toc:
        doc.add_paragraph(line)
    note(doc, "Trong Word: References → Table of Contents để chèn mục lục tự "
              "động có số trang.")
    page_break(doc)

    # ------------------------------------------------------------- sec 1 --
    h1(doc, "1. Giới thiệu & Phạm vi — Introduction & Scope")
    rich(doc, [
        ("NexaChat", True, False),
        (" là nền tảng nhắn tin thời gian thực trên Android: đăng ký bằng số "
         "điện thoại (chuẩn hóa E.164), tìm và kết nối với người dùng khác qua "
         "SĐT, chat cá nhân và chat nhóm realtime qua WebSocket/STOMP, gửi "
         "ảnh/video/audio/file, giao diện Material 3 song ngữ Việt–Anh, chạy "
         "ổn định trong demo LAN nhiều thiết bị không cần internet.", False, False),
    ])
    rich(doc, [
        ("Tech stack: ", True, False),
        ("Android client — Kotlin + Jetpack Compose (MVVM + UDF, Hilt, Room, "
         "OkHttp). Backend — Java 17 + Spring Boot 3.4.1 (modular monolith). "
         "Database — PostgreSQL 16 (Flyway). Realtime — STOMP 1.2 over "
         "WebSocket.", False, False),
    ])
    rich(doc, [
        ("In-scope", True, False),
        (" (7 module theo đề + creative): Authentication & Account · User "
         "Discovery & Phone Search · Private Chat · Group Chat · Multimedia & "
         "File · Multi-language · Rotation — cộng Creative Feature ", False, False),
        ("Message Interactions", True, False),
        (" (Reply/Quote + Emoji Reaction + Pin).", False, False),
    ])
    rich(doc, [
        ("Out-of-scope", True, False),
        (" (nói rõ để tránh overclaim): voice/video call (WebRTC), end-to-end "
         "encryption, đồng bộ danh bạ máy, microservices/Kubernetes, admin "
         "dashboard, FCM push notification. Mỗi mục loại đều có lý do và hướng "
         "phát triển ở §15.", False, False),
    ])
    para(doc, "Cấu trúc repo (monorepo, một .git duy nhất):", bold=True)
    code_box(doc,
        "nexachat/\n"
        "+-- android/   Kotlin + Jetpack Compose app (80 files, Hilt, Room, STOMP client)\n"
        "+-- server/    Spring Boot modular monolith (134 files, REST /api/v1 + STOMP /ws)\n"
        "+-- docs/      architecture, api-contract, websocket-protocol, security,\n"
        "|              rtm, demo-runbook, known-limitations, packaging-checklist, report\n"
        "+-- demo/      seed.sql + lan-up.sh / lan-up.ps1 + backup.sh")

    # ------------------------------------------------------------- sec 2 --
    h1(doc, "2. Tự đánh giá theo rubric — Rubric Self-Assessment (R1–R6)")
    table(doc,
          ["Mã", "Tiêu chí", "Điểm", "TT", "Bằng chứng trong repo"],
          [["R1", "Bài nộp đầy đủ, đúng cấu trúc", "5", "OK",
            "docs/packaging-checklist.md (C1–C12), .git full history, report.docx, APK release"],
           ["R2", "Chat 1-1, chat nhóm, tìm kiếm, thêm bằng SĐT, quản lý tài khoản", "20", "OK",
            "Module auth/user/contact/conversation/message; IT: AuthFlowIT, ContactConversationIT, GroupFlowIT, MessageFlowIT"],
           ["R3", "Đa ngôn ngữ, multimedia/file, xoay màn hình", "15", "OK",
            "values/ + values-vi/ 71 keys mỗi bên (i18n-audit.md); attachment + magic-byte sniff; rotation-audit.md"],
           ["R4", "Chức năng sáng tạo", "5", "OK",
            "Message Interactions: reply/react/pin — V3__interactions.sql, ReactionController, PinController, InteractionFlowIT"],
           ["R5", "Giao diện rõ ràng + toàn bộ unit test pass", "15", "OK",
            "Server 284 tests green (258 unit + 26 IT); Android 158 tests green; CI gate .github/workflows/ci.yml"],
           ["R6", "Thuyết trình, demo, vấn đáp", "40", "OK",
            "docs/demo-runbook.md (kịch bản 8 phút + fallback), 18 câu vấn đáp, mỗi quyết định kèm \"vì sao\""]],
          widths=[1, 5.5, 1, 1, 7], font_size=8.5)
    note(doc, "Nguyên tắc xuyên suốt: mọi claim trong bảng này trace được về "
              "file/test cụ thể — không ghi \"end-to-end encryption\", không "
              "ghi \"scalable triệu user\".")

    # ------------------------------------------------------------- sec 3 --
    h1(doc, "3. Phân tích yêu cầu — Requirements Analysis")
    h2(doc, "3.1. Functional requirements (FR theo module)")
    for prefix, text in [
        ("Module A — Authentication & Account: ",
         "đăng ký bằng SĐT E.164 + mật khẩu + tên hiển thị (trùng SĐT → 409 "
         "PHONE_EXISTS); login nhận access JWT 15' + refresh token 14 ngày có "
         "rotation; refresh single-flight phía client; logout / logout-all thu "
         "hồi refresh token; xem/sửa profile + avatar; đổi mật khẩu (revoke "
         "mọi phiên khác)."),
        ("Module B — Discovery & Contacts: ",
         "tìm user chính xác theo SĐT hoặc theo tên (prefix, ≤20 kết quả, "
         "rate-limited); thêm/xóa/sửa alias/xem contact (idempotent); từ "
         "contact mở/tạo direct conversation không trùng (direct_key unique)."),
        ("Module C — Private Chat: ",
         "gửi/nhận text realtime qua WebSocket; history cursor pagination "
         "(mặc định 30/page, beforeSeq/afterSeq); inbox + lastMessage + "
         "unreadCount; watermark last_read_seq → unread badge + read receipt "
         "\"Đã xem\" 1-1; idempotent send bằng clientMessageId; offline send "
         "queue (optimistic SENDING → SENT/FAILED, tap-to-retry); reconnect "
         "backoff 1→2→4→8→16s + jitter, tối đa 5 lần, resync bằng REST; "
         "typing indicator."),
        ("Module D — Group Chat: ",
         "tạo nhóm ≥2 member (creator = ADMIN); thêm/xóa/xem member, rời "
         "nhóm; chat nhóm realtime có tên người gửi; đổi tên/avatar nhóm "
         "(ADMIN → broadcast CONVERSATION_UPDATED); system message "
         "(\"A đã thêm B\", type=SYSTEM)."),
        ("Module E — Multimedia & File: ",
         "Photo Picker (không cần permission) + SAF; whitelist "
         "jpg/png/webp/mp4/mp3/m4a/ogg/pdf/doc/docx/txt; cap 25MB; upload "
         "multipart có progress; server sniff magic bytes; download qua "
         "endpoint có authZ."),
        ("Module F/G — i18n & Rotation: ",
         "Vi + En toàn bộ qua string resources, đổi ngôn ngữ trong Settings "
         "và persist; format thời gian theo locale; rotation không mất "
         "history/draft/scroll, không gọi lại API."),
        ("Module H — Creative (Message Interactions): ",
         "reply/quote kể cả cross-type; reaction emoji toggle (PK composite = "
         "idempotent); pin ≤3/conversation (DIRECT: member nào cũng pin; "
         "GROUP: chỉ ADMIN)."),
    ]:
        bullet(doc, text, bold_prefix=prefix)

    h2(doc, "3.2. Non-functional requirements (NFR)")
    table(doc,
          ["ID", "Nhóm", "Yêu cầu", "Đạt bằng"],
          [["NFR-1", "Performance", "Tin <500ms LAN; inbox <1s; paginate 30 tin",
            "idx_msg_conv_seq, denormalized last_message_*, inbox projection 1 query"],
           ["NFR-2", "Reliability", "Reconnect ≤5 lần backoff + jitter; send idempotent 100%",
            "clientMessageId + UNIQUE(sender_id, client_message_id); resync REST"],
           ["NFR-3", "Security", "bcrypt; JWT HS256 secret ≥256-bit từ env; Bearer mọi endpoint; WSS prod",
            "SecurityConfig, JwtAuthFilter, application-prod.yml fail-fast"],
           ["NFR-4", "Usability", "Material 3; Loading/Empty/Error/Retry; touch ≥48dp",
            "core/ui/components shared states; a11y pass đã sửa vi phạm 48dp thật"],
           ["NFR-5", "Maintainability", "Package-by-feature; không trả entity ra API; không logic trong Controller/Composable",
            "com.nexachat.<feature> server; feature/* + data/ + core/ Android"],
           ["NFR-6", "Testability", "Toàn bộ unit test pass ở CI; mỗi service authZ có negative test",
            "284 + 158 tests; 21 negative tests ở quality phase"],
           ["NFR-7", "Portability", "minSdk 26, targetSdk 35; emulator + máy thật LAN",
            "build.gradle.kts; BuildConfig.API_BASE_URL"],
           ["NFR-8", "Observability", "Log không chứa password/token/file body; /actuator/health",
            "log scan sạch (chỉ id/event name)"],
           ["NFR-9", "Constraints", "1 backend + 1 PostgreSQL; media ≤25MB; demo LAN offline",
            "docker-compose.yml"]],
          widths=[1.4, 2.4, 5.6, 6], font_size=8.5)

    h2(doc, "3.3. Use case đặc trưng — UC gửi tin 1-1")
    for s in [
        "A nhập nội dung → app tạo clientMessageId, hiển thị tin SENDING (optimistic).",
        "STOMP SEND /app/conversations/{id}/messages mang {clientMessageId, type, content, replyToId?, attachmentIds[]}.",
        "Server xác thực JWT (CONNECT) + kiểm member (SEND interceptor) + validate.",
        "Một transaction: INSERT messages (seq BIGSERIAL, ON CONFLICT (sender_id, client_message_id) trả row cũ) + UPDATE conversations.last_message_*.",
        "Broadcast MESSAGE_CREATED tới /topic/conversations.{id} sau commit.",
        "Máy A match clientMessageId → SENT; máy B render + unread++ (watermark).",
    ]:
        numbered(doc, s)
    note(doc, "Alternate: A offline → tin giữ SENDING trong outbox Room; WS "
              "sống lại → flush theo thứ tự. Exception: không còn là member → "
              "/user/queue/errors → tin chuyển FAILED + snackbar.")

    # ------------------------------------------------------------- sec 4 --
    h1(doc, "4. Kiến trúc hệ thống — System Architecture")
    h2(doc, "4.1. Sơ đồ tổng")
    note(doc, "Bản Mermaid render đầy đủ: docs/architecture.md §1–2. Docx "
              "không render được Mermaid nên dùng sơ đồ text:")
    code_box(doc,
        "Android (Kotlin + Compose)\n"
        "  Compose Screens (Material 3)\n"
        "    -> ViewModel + StateFlow (UDF)\n"
        "    -> Repositories (offline-first)\n"
        "         +-> Retrofit/OkHttp + AuthInterceptor/Authenticator --HTTPS REST /api/v1--+\n"
        "         +-> StompClient (OkHttp WebSocket + STOMP frames) -----WS STOMP---------+|\n"
        "         +-> Room DB (cache + outbox)                                             ||\n"
        "         +-> DataStore + TokenStore (Android Keystore AES)                        ||\n"
        "                                                                                 vv\n"
        "        Backend — Modular Monolith (Java 17 + Spring Boot 3.4.1)\n"
        "          Spring Security (JWT filter · Rate limit)\n"
        "            -> Modules: auth | user·contact | conversation | message | attachment | common\n"
        "          WebSocket/STOMP layer (@MessageMapping + ChannelInterceptor authZ)\n"
        "            -> SimpleBroker (in-process) --fan-out--> STOMP MESSAGE -> clients\n"
        "             |\n"
        "             +-> PostgreSQL 16 (Flyway V1..V3)\n"
        "             +-> Volume /data/uploads (StorageService interface -> S3 swappable)")

    h2(doc, "4.2. Vì sao modular monolith, không phải microservices")
    para(doc, "Module phân tách bằng package + boundary rõ ràng (mỗi module có "
              "Controller/Service/Repository riêng: auth, user, contact, "
              "conversation, message, attachment, common, ws, config), deploy "
              "1 đơn vị. Ở quy mô đồ án, microservices chỉ thêm phức tạp "
              "mạng/transaction phân tán mà không tăng điểm; SimpleBroker "
              "in-process đủ cho 1 instance (hướng nâng cấp: broker relay "
              "RabbitMQ — §15).")

    h2(doc, "4.3. Luồng gửi tin — persist-before-broadcast")
    code_box(doc,
        "May A                 Spring Backend               PostgreSQL          May B\n"
        " |  optimistic insert Room (SENDING, clientMessageId)\n"
        " |-- STOMP SEND /app/conversations/{id}/messages -->|\n"
        " |                     | authN JWT + authZ member + validate\n"
        " |                     |-- TX: INSERT messages (seq BIGSERIAL;\n"
        " |                     |    ON CONFLICT sender+clientMessageId -> old)\n"
        " |                     |    UPDATE conversations.last_message_* ----->|\n"
        " |                     |<- message{seq, createdAt}\n"
        " |<-- MESSAGE /topic/conversations.{id} MESSAGE_CREATED -------------->|\n"
        " |  match clientMessageId -> SENT                      render + unread++\n"
        " |                     (B mo chat: POST .../read {upToSeq}\n"
        " |                      -> A nhan MESSAGE_READ \"Da xem\")")
    para(doc, "Điểm then chốt: server commit DB xong mới fan-out → tin nhận "
              "được luôn đã bền vững; reconnect chỉ cần GET messages?afterSeq= "
              "để resync.")

    h2(doc, "4.4. Quyết định công nghệ chính (decision log rút gọn)")
    table(doc,
          ["#", "Quyết định", "Chọn", "Vì sao"],
          [["T1", "UI", "Jetpack Compose + Material 3", "Theo đề; declarative khớp UDF"],
           ["T2", "App arch", "MVVM + UDF + Repository, offline-first", "\"Clean ở mức phù hợp\" — 3 layer rõ ràng"],
           ["T3", "DI", "Hilt", "Chuẩn Google, compile-time safety"],
           ["T4", "HTTP", "Retrofit + OkHttp + Moshi", "Authenticator/Interceptor chuẩn cho JWT refresh"],
           ["T5", "Local DB", "Room", "Query phức tạp (inbox, pagination, outbox) + Flow"],
           ["T6", "Token", "TokenStore tự viết: AES/GCM key trong Keystore + DataStore", "~100 dòng, không phụ thuộc lib deprecating"],
           ["T8", "Picker", "Photo Picker + SAF", "Không cần runtime permission"],
           ["T9", "Backend", "Spring Boot 3.4.1 MVC, Java 17", "Blocking model đơn giản, khớp JPA"],
           ["T10", "DB access", "Spring Data JPA + Flyway", "Migration versioned V1→V3"],
           ["T11", "DB", "PostgreSQL 16", "JSONB/constraint mạnh; BIGSERIAL ordering"],
           ["T12", "Realtime", "STOMP over WebSocket + SimpleBroker, không SockJS", "Frame semantics + authZ theo destination có sẵn; client native không cần fallback"],
           ["T13", "Auth", "SĐT + password + JWT access/refresh rotation", "OTP cần SMS gateway trả phí, khó test LAN; rubric không đòi verify ownership"],
           ["T14", "JWT", "HS256, secret env ≥32 bytes", "Một service vừa ký vừa verify"],
           ["T15", "Rate limit", "Bucket4j in-memory", "Đủ 1 instance; giới hạn per-node ghi thành thật"],
           ["T16", "Media", "StorageService interface → LocalFileStorage (volume)", "Không phình DB; đổi S3 không sửa caller"],
           ["T17", "MIME", "Magic-byte sniffing tự implement (common/MagicBytes.java)", "Content-type client khai là không tin được"],
           ["T18", "SĐT", "libphonenumber E.164 cả client lẫn server", "Server normalize lại vì client không đáng tin"]],
          widths=[1, 2.2, 5.5, 6.8], font_size=8.5)

    # ------------------------------------------------------------- sec 5 --
    page_break(doc)
    h1(doc, "5. Thiết kế cơ sở dữ liệu — Database Design (ERD)")
    h2(doc, "5.1. Quan hệ tổng quan")
    code_box(doc,
        "users --1:N-- refresh_tokens          (phien dang nhap, rotation chain)\n"
        "users --1:N-- contacts                (owner_id -> contact_user_id)\n"
        "users --N:M-- conversations           qua conversation_members (role, last_read_seq)\n"
        "conversations --1:N-- messages        (seq BIGSERIAL toan cuc)\n"
        "messages --1:N-- message_attachments  (upload-first, bind khi send — V2)\n"
        "messages --1:N-- message_reactions    (V3, PK composite = toggle idempotent)\n"
        "conversations --1:N-- pinned_messages qua message (V3, <=3/conv o service)\n"
        "messages --1:N-- messages             (reply_to_id — self FK, V1)")

    h2(doc, "5.2. Bảng thực tế (migration V1/V2/V3)")
    para(doc, "V1__init.sql — 7 bảng lõi + extensions pgcrypto, pg_trgm:", bold=True)
    table(doc,
          ["Bảng", "Cột chính", "Ràng buộc & index đáng chú ý"],
          [["users", "id UUID PK · phone VARCHAR(16) · password_hash CHAR(60) · display_name VARCHAR(60) · avatar_url · created_at",
            "UNIQUE(phone); CHECK phone ~ '^\\+[1-9]\\d{7,14}$' (E.164 ở DB); trigram GIN index cho search theo tên"],
           ["refresh_tokens", "id UUID PK · user_id FK→users CASCADE · token_hash CHAR(64) UNIQUE · device_label · created_at · expires_at · revoked_at · replaced_by",
            "Chỉ lưu SHA-256 của token; partial index (user_id) WHERE revoked_at IS NULL; replaced_by = rotation chain"],
           ["contacts", "owner_id + contact_user_id composite PK FK→users CASCADE · alias_name · created_at",
            "CHECK owner_id <> contact_user_id"],
           ["conversations", "id UUID PK · type CHECK(DIRECT/GROUP) · direct_key VARCHAR(80) UNIQUE · title · avatar_url · created_by FK · last_message_id · last_message_seq · created_at",
            "CHECK ((type='DIRECT') = (direct_key IS NOT NULL)); direct_key = LEAST(u1,u2):GREATEST(u1,u2) chống tạo trùng; index inbox last_message_seq DESC NULLS LAST"],
           ["conversation_members", "conversation_id + user_id composite PK · role CHECK(ADMIN/MEMBER) · joined_at · last_read_seq",
            "last_read_seq = watermark đã đọc → unread count + read receipt"],
           ["messages", "id UUID PK · seq BIGSERIAL UNIQUE · conversation_id FK CASCADE · sender_id FK · client_message_id UUID · type CHECK(TEXT/IMAGE/VIDEO/AUDIO/FILE/SYSTEM) · content · reply_to_id FK→messages · created_at · edited_at · deleted_at",
            "UNIQUE(sender_id, client_message_id) = idempotency tầng DB; index (conversation_id, seq DESC) cursor pagination"],
           ["message_attachments", "id UUID PK · message_id FK CASCADE · storage_key VARCHAR(120) UNIQUE · original_name · mime_type · size_bytes · width · height · duration_ms · created_at",
            "storage_key do server sinh (uuid) — chống path traversal; mime_type là mime đã sniff"]],
          widths=[3, 6.5, 6], font_size=8)
    para(doc, "V2__attachments.sql — lifecycle upload-first, bind-at-send:", bold=True)
    para(doc, "Thêm owner_id (FK→users, NOT NULL, backfill từ sender của "
              "message đã bind), message_id → nullable, index idx_att_owner. "
              "Upload chèn row với message_id=NULL; MessageService.send() "
              "validate attachmentId (tồn tại + thuộc sender + chưa bind) rồi "
              "set message_id trong cùng transaction.")
    para(doc, "V3__interactions.sql — creative feature:", bold=True)
    table(doc,
          ["Bảng", "Cột", "Thiết kế"],
          [["message_reactions", "message_id + user_id + emoji composite PK · created_at",
            "PK composite = toggle-idempotency: react lại = DELETE, insert trùng = ON CONFLICT DO NOTHING; CASCADE theo message & user"],
           ["pinned_messages", "conversation_id + message_id composite PK · pinned_by FK · pinned_at",
            "\"Pin 2 lần\" là no-op by construction; trần ≤3/conv ở PinService dưới row-lock conversation; index idx_pins_message"]],
          widths=[3.2, 5.8, 6.5], font_size=8.5)

    h2(doc, "5.3. Quyết định schema phải giải thích được")
    for prefix, text in [
        ("seq BIGSERIAL thay timestamp ordering: ",
         "tăng đơn điệu toàn cục, độc lập clock; cursor pagination = seq < ? / afterSeq resync."),
        ("UNIQUE(sender_id, client_message_id): ",
         "chốt chặn cuối chống duplicate khi retry/reconnect — INSERT … ON CONFLICT race-safe, không check-then-insert."),
        ("direct_key unique: ",
         "hai người bấm \"chat\" đồng thời chỉ tạo 1 DIRECT — constraint DB giải quyết race thay vì code phòng thủ."),
        ("Watermark last_read_seq thay bảng read-per-message: ",
         "nhóm N người × M tin = N×M rows; watermark 1 bigint cho unread (count(seq > last_read_seq)) + \"Đã xem\" (partner.last_read_seq >= msg.seq). Trade-off: không biết chính xác từng member nhóm đọc tới đâu — chấp nhận, ghi hướng phát triển."),
        ("Denormalized last_message_*: ",
         "inbox không cần MAX(seq) per conversation; cập nhật trong cùng transaction insert message."),
        ("Soft delete deleted_at: ",
         "tin xóa vẫn giữ FK integrity (reply_to, reactions); edited_at đã plumb sẵn (endpoint chưa ship — §15)."),
        ("JPA: ",
         "mọi @ManyToOne LAZY (bẫy N+1 kinh điển); transaction boundary ở Service @Transactional; ddl-auto=validate — schema chỉ đổi qua Flyway."),
    ]:
        p = doc.add_paragraph(style="List Number")
        run = p.add_run(prefix)
        run.bold = True
        p.add_run(text)

    # ------------------------------------------------------------- sec 6 --
    page_break(doc)
    h1(doc, "6. REST API")
    para(doc, "Quy ước: base /api/v1; Authorization: Bearer <accessToken> cho "
              "mọi endpoint trừ auth/register, auth/login, auth/refresh, "
              "/actuator/health và upgrade /ws. Error envelope thống nhất "
              "{\"error\":{\"code\",\"message\",\"details\"}}. Pagination "
              "cursor (beforeSeq/afterSeq/limit), không offset. Danh sách dưới "
              "liệt kê đúng các controller trong "
              "server/src/main/java/com/nexachat/.")

    h2(doc, "6.1. Auth — AuthController (/api/v1/auth)")
    table(doc,
          ["Method & path", "Auth", "Success", "Errors chính"],
          [["POST /auth/register", "Public", "201 AuthResponse", "400 VALIDATION_FAILED · 409 PHONE_EXISTS · 429"],
           ["POST /auth/login", "Public", "200 AuthResponse", "401 INVALID_CREDENTIALS · 429"],
           ["POST /auth/refresh", "Public", "200 (cặp token mới, rotation)", "401 TOKEN_INVALID / TOKEN_EXPIRED / TOKEN_REUSED"],
           ["POST /auth/logout", "Bearer", "204", "401"],
           ["POST /auth/logout-all", "Bearer", "204", "401"],
           ["PATCH /auth/password", "Bearer", "204 (revoke các phiên khác)", "401 WRONG_PASSWORD"]],
          widths=[5, 2, 4.5, 4], font_size=8.5)
    note(doc, "AuthResponse = {user, accessToken, accessTokenExpiresIn:900, "
              "refreshToken}. Rate limit: login/register 10 req/5 phút, "
              "refresh 30 req/5 phút (Bucket4j, RateLimitInterceptor).")

    h2(doc, "6.2. Users & Contacts — UserController, AvatarController, ContactController")
    table(doc,
          ["Method & path", "Auth", "Success", "Errors chính"],
          [["GET /users/me", "Bearer", "200 UserResponse", "401"],
           ["PATCH /users/me", "Bearer", "200 UserResponse", "400"],
           ["GET /users/search?phone= hoặc ?q=", "Bearer", "200 UserResponse[] (phone→0–1, q→≤20)", "400 · 429 (30/min/user)"],
           ["POST /users/me/avatar (multipart)", "Bearer", "200 {avatarUrl}", "413 · 415 MIME_NOT_ALLOWED"],
           ["GET /contacts", "Bearer", "200 ContactResponse[]", "401"],
           ["POST /contacts {phone}", "Bearer", "201", "404 USER_NOT_FOUND · 409 ALREADY_CONTACT · 429 (30/min)"],
           ["PATCH /contacts/{userId} {aliasName?}", "Bearer", "200", "400 · 404"],
           ["DELETE /contacts/{userId}", "Bearer", "204 (idempotent)", "401"]],
          widths=[5.2, 1.8, 4.5, 4], font_size=8.5)

    h2(doc, "6.3. Conversations & Messages — ConversationController, ConversationInboxController, GroupController, MessageController")
    table(doc,
          ["Method & path", "Auth", "Success", "Errors chính"],
          [["POST /conversations/direct {phone} hoặc {userId}", "Bearer", "201 created / 200 existing (idempotent qua direct_key)", "400 · 404"],
           ["GET /conversations", "Bearer", "200 InboxEntry[] sort lastMessageSeq DESC", "401"],
           ["POST /conversations/group {title, memberIds[]}", "Bearer", "201", "400"],
           ["PATCH /conversations/{id}", "ADMIN", "200 → broadcast CONVERSATION_UPDATED", "403 NOT_ADMIN"],
           ["POST /conversations/{id}/members {userIds[]}", "ADMIN", "200", "403 · 404"],
           ["DELETE /conversations/{id}/members/{userId}", "ADMIN", "204", "403"],
           ["POST /conversations/{id}/leave", "member", "204", "403"],
           ["POST /conversations/{id}/avatar (multipart)", "ADMIN", "200 {avatarUrl} → broadcast CONVERSATION_UPDATED", "403 · 413 · 415"],
           ["GET /conversations/{id}/messages?beforeSeq=&afterSeq=&limit=", "member", "200 MessagePageResponse{items,nextBeforeSeq,hasMore}", "403 NOT_MEMBER · 404"],
           ["POST /conversations/{id}/messages", "member", "201 / 200 dedup hit — REST fallback của đường WS", "403 · 404 · 400"],
           ["POST /conversations/{id}/read {upToSeq}", "member", "204 → broadcast MESSAGE_READ", "403 · 400"]],
          widths=[6, 1.6, 5, 3.4], font_size=8)

    h2(doc, "6.4. Interactions — ReactionController, PinController")
    table(doc,
          ["Method & path", "Auth", "Success", "Errors chính"],
          [["POST /messages/{id}/reactions {emoji}", "member", "204 (toggle: thêm/xóa)", "403 · 404"],
           ["GET /conversations/{id}/pins", "member", "200 PinnedMessageView[]", "403"],
           ["POST /conversations/{id}/pins {messageId}", "DIRECT: member; GROUP: ADMIN", "204", "403 · 409 PIN_LIMIT (>3)"],
           ["DELETE /conversations/{id}/pins/{messageId}", "như trên", "204", "403"]],
          widths=[6, 3.4, 2.6, 3.6], font_size=8.5)

    h2(doc, "6.5. Attachments & Files — AttachmentController, FileController")
    table(doc,
          ["Method & path", "Auth", "Success", "Errors chính"],
          [["POST /attachments (multipart file)", "Bearer", "201 AttachmentResponse (upload-first, bind khi send)", "413 FILE_TOO_LARGE · 415 MIME_NOT_ALLOWED"],
           ["GET /attachments/{id}/content", "member của conv chứa file", "200 bytes", "403 · 404"],
           ["GET /files/** (storage_key path)", "Bearer", "200 bytes + sniffed Content-Type", "404 FILE_NOT_FOUND"]],
          widths=[5.4, 3.4, 4.4, 3], font_size=8.5)
    para(doc, "Tổng: 31 REST endpoint + /actuator/health. Gửi tin có hai "
              "đường — WS (chính) và REST POST (fallback) — cùng đi qua "
              "MessageService.send() → một nơi enforce authZ + idempotency.",
         bold=True)

    # ------------------------------------------------------------- sec 7 --
    page_break(doc)
    h1(doc, "7. WebSocket / STOMP Contract")
    para(doc, "Kết nối: ws://host/ws (prod wss://); raw WebSocket nói STOMP "
              "1.2, không SockJS (client native, không cần browser fallback). "
              "JWT nằm trong Authorization header của frame CONNECT — không "
              "query param (token trong URL lọt vào access log). "
              "heart-beat:10000,10000 → phát hiện kết nối chết ~20s.")
    h2(doc, "7.1. Destinations")
    table(doc,
          ["Hướng", "Destination", "Body", "Ghi chú"],
          [["C→S SEND", "/app/conversations/{id}/messages", "{clientMessageId,type,content,replyToId?,attachmentIds[]}", "WsMessageController → MessageService.send()"],
           ["C→S SEND", "/app/conversations/{id}/typing", "{}", "debounce client-side"],
           ["C→S SEND", "/app/conversations/{id}/read", "{upToSeq}", "watermark → MESSAGE_READ"],
           ["S→C", "/topic/conversations.{id}", "event envelope", "SUBSCRIBE bị chặn nếu không phải member"],
           ["S→C", "/user/queue/inbox", "event envelope", "inbox badge khi chưa subscribe conv"],
           ["S→C", "/user/queue/errors", "{code,clientMessageId?}", "lỗi SEND bị từ chối"]],
          widths=[1.8, 5.2, 5, 3.5], font_size=8.5)
    h2(doc, "7.2. Event envelope & catalog")
    para(doc, "Mọi event cùng format — client parse một lần:")
    code_box(doc,
        '{"eventType":"MESSAGE_CREATED","conversationId":"…",'
        '"serverTs":"2026-11-04T10:31:05.123Z","payload":{…}}')
    table(doc,
          ["eventType", "Payload", "Phát khi"],
          [["MESSAGE_CREATED", "MessageResponse (seq, sender, content, attachments, replyTo, reactions)", "tin mới persist xong"],
           ["MESSAGE_UPDATED", "message id + content/editedAt", "(contract sẵn; endpoint edit chưa ship)"],
           ["MESSAGE_DELETED", "message id + deletedAt", "(contract sẵn; endpoint delete chưa ship)"],
           ["MESSAGE_READ", "conversationId, readerId, upToSeq", "member cập nhật watermark"],
           ["REACTION_UPDATED", "{messageId, reactions:[{emoji,count,userIds[]}]}", "toggle reaction"],
           ["PIN_UPDATED", "{conversationId, messageId, pinned}", "pin/unpin"],
           ["USER_TYPING", "conversationId, senderId", "typing frame"],
           ["CONVERSATION_UPDATED", "title/avatar/members thay đổi", "tạo nhóm, đổi nhóm, member add/remove"],
           ["USER_ONLINE / USER_OFFLINE", "(vocabulary tồn tại trong WsEventType, chưa emit — §15)", "presence — future"]],
          widths=[3.8, 7, 4.5], font_size=8.5)
    h2(doc, "7.3. Điểm thiết kế then chốt")
    for prefix, text in [
        ("Persist-before-broadcast — ", "commit xong mới fan-out (§4.3)."),
        ("AuthZ hai lớp — ", "CONNECT = authN (JWT); SUBSCRIBE/SEND = authZ qua StompAuthChannelInterceptor kiểm conversation_members; client không tự chọn được destination nhạy cảm."),
        ("Dedup — ", "sender match clientMessageId → SENT; receiver insert theo message id (OnConflictStrategy.IGNORE trong Room)."),
        ("Ordering theo seq — ", "tin đến lệch chèn đúng vị trí, không append."),
        ("Reconnect — ", "backoff 1·2·4·8·16s + jitter, tối đa 5 lần; sau reconnect resync REST (GET …/messages?afterSeq=lastSeen) → \"WS là push, REST là source of truth\"."),
        ("Giới hạn thành thật — ", "SimpleBroker in-process không fan-out xuyên node; STOMP SEND chưa có rate limit (§15)."),
    ]:
        p = doc.add_paragraph(style="List Number")
        run = p.add_run(prefix)
        run.bold = True
        p.add_run(text)

    # ------------------------------------------------------------- sec 8 --
    h1(doc, "8. Thiết kế bảo mật — Security Design")
    h2(doc, "8.1. Credential & token model")
    for prefix, text in [
        ("Password: ", "bcrypt (hash CHAR(60)); register chuẩn hóa SĐT E.164 bằng libphonenumber ở server (client cũng normalize cho UX, server không tin client)."),
        ("Access token: ", "JWT HS256, sub=userId, TTL 900s; type=access claim chặn replay; secret ≥32 bytes từ env — application-prod.yml fail-fast nếu thiếu JWT_SECRET. HS256 vì một service vừa ký vừa verify."),
        ("Refresh token: ", "opaque 48 bytes SecureRandom → 64 ký tự base64url; DB chỉ lưu SHA-256(token) (token_hash CHAR(64) UNIQUE) → lộ DB ≠ lộ token dùng được. TTL 14 ngày, single-use."),
        ("Rotation: ", "mỗi /refresh trả token mới, revoke token cũ, link replaced_by (chuỗi audit RT1→RT2→…). Rotation đọc row dưới SELECT … FOR UPDATE (PESSIMISTIC_WRITE) → 2 refresh song song không mint được 2 successor."),
        ("Reuse detection: ", "token đã rotate mà bị present → 401 TOKEN_REUSED + revoke toàn bộ family của user (dấu hiệu theft). Logout idempotent + check ownership; đổi password revoke mọi phiên khác."),
    ]:
        bullet(doc, text, bold_prefix=prefix)
    h2(doc, "8.2. Transport & authorization")
    bullet(doc, "REST stateless (CSRF off, không session). Chỉ "
                "auth/register|login|refresh, /actuator/health, /error, /ws "
                "là permitAll; mọi route còn lại bắt buộc Bearer "
                "(SecurityConfig + JwtAuthFilter + AuthSecurityErrorHandler → "
                "401/403 theo envelope chuẩn).")
    bullet(doc, "STOMP: /ws permitAll ở HTTP, JWT kiểm ở CONNECT; "
                "SUBSCRIBE/SEND kiểm member — authZ server-side, không tin "
                "client.")
    bullet(doc, "Dev LAN chạy cleartext http:// / ws:// (nói rõ chỉ dev; debug "
                "build cho cleartext qua network_security_config "
                "debug-source-set); prod = TLS/WSS, cleartextTraffic=false.")
    h2(doc, "8.3. Upload hardening")
    bullet(doc, "Whitelist mime (jpg/png/webp/mp4/mp3/m4a/ogg/pdf/doc/docx/"
                "txt) kiểm bằng magic bytes tự implement "
                "(common/MagicBytes.java, 18 unit test) — đổi .exe→.png bị "
                "415 MIME_NOT_ALLOWED.")
    bullet(doc, "Cap 25MB cả client lẫn spring.servlet.multipart "
                "(413 FILE_TOO_LARGE).")
    bullet(doc, "storage_key uuid server sinh — original_name chỉ để hiển "
                "thị → chống path traversal; download qua endpoint có authZ "
                "(member của conversation chứa file), không serve static trực "
                "tiếp.")
    h2(doc, "8.4. Rate limiting & enumeration")
    table(doc,
          ["Endpoint", "Bucket (Bucket4j in-memory, greedy refill)"],
          [["POST /auth/login, /register", "10 req / 5 phút"],
           ["POST /auth/refresh", "30 req / 5 phút"],
           ["GET /users/search", "30 req / phút / user"],
           ["POST /contacts", "30 adds / phút / user"]],
          widths=[6, 8], font_size=9)
    para(doc, "Login sai trả INVALID_CREDENTIALS thống nhất (không lộ user "
              "tồn tại). Search chỉ trả public profile tối thiểu. Thừa nhận: "
              "contact-add theo SĐT cố hữu 200/404 — giảm rủi ro bằng rate "
              "limit, không giấu được hoàn toàn (§15). Bucket là per-node "
              "(in-memory) — ghi thành thật, không claim distributed.")
    h2(doc, "8.5. Không claim")
    para(doc, "Báo cáo không tuyên bố E2E encryption: mã hóa đường truyền "
              "TLS/WSS ở prod, server thấy plaintext — E2E là hướng phát "
              "triển.")

    # ------------------------------------------------------------- sec 9 --
    page_break(doc)
    h1(doc, "9. Offline-first Design (Android)")
    rich(doc, [
        ("Source of truth: Room.", True, False),
        (" WS event → ghi Room → UI recompose. Kill app mở lại vẫn có history; "
         "rotation chỉ là recomposition, không re-fetch.", False, False),
    ])
    bullet(doc, "MessageEntity, ConversationEntity, OutboxEntity, "
                "MessageReactionEntity, PinnedMessageEntity "
                "(core/database/Entities.kt).", bold_prefix="Entities: ")
    bullet(doc, "ChatViewModel.send() → OutboxDao.insert(SENDING, "
                "clientMessageId) → flush STOMP SEND → ack bằng "
                "MESSAGE_CREATED khớp clientMessageId → SENT. WS down → REST "
                "fallback (POST /conversations/{id}/messages cùng "
                "MessageService.send()). Cả hai down → giữ SENDING; "
                "tap-to-retry / flush khi reconnect.",
           bold_prefix="Send pipeline (idempotent + queue): ")
    bullet(doc, "OkHttp Authenticator bắt 401 → TokenRepository.refresh() bọc "
                "Mutex (n request 401 vẫn chỉ 1 call refresh) → retry "
                "request. Refresh fail → logout → navigate Login.",
           bold_prefix="Token refresh single-flight: ")
    bullet(doc, "ChatUiState = combine(roomMessages, outbox, typingFlow, "
                "wsStatus) → stateIn(WhileSubscribed); one-shot event qua "
                "Channel + receiveAsFlow.", bold_prefix="UDF: ")
    bullet(doc, "Photo Picker/SAF không cần runtime permission.",
           bold_prefix="Permissions tối thiểu: ")

    # ------------------------------------------------------------ sec 10 --
    h1(doc, "10. Đa ngôn ngữ & Xoay màn hình — i18n & Rotation")
    h2(doc, "10.1. i18n")
    bullet(doc, "values/strings.xml (EN mặc định) + values-vi/strings.xml: 71 "
                "keys mỗi bên, parity 100% — kiểm chứng docs/i18n-audit.md "
                "(0 missing, 0 leak).")
    bullet(doc, "Đổi ngôn ngữ trong Settings, persist: không có "
                "androidx.appcompat trong dependency graph → dùng framework "
                "android.app.LocaleManager (API 33+, system-persisted) và "
                "SharedPreferences + wrapContext cho API <33 — thoả intent "
                "\"đổi ngôn ngữ persist\" (lệch thiết kế có chủ đích, ghi "
                "trong ledger).")
    bullet(doc, "Timestamp format theo locale (DateTimeFormatter localized).")
    h2(doc, "10.2. Rotation — audit từng màn (docs/rotation-audit.md)")
    para(doc, "Nguyên tắc: UI state trong ViewModel (survives config change); "
              "draft/scroll trong SavedStateHandle/rememberSaveable (survives "
              "process death); history trong Room.")
    table(doc,
          ["Màn", "Xử lý"],
          [["Chat", "input → SavedStateHandle[\"chat_input\"]; listState = rememberLazyListState giữ scroll; messages/outbox từ Room → không mất gì"],
           ["UserSearch", "query → SavedStateHandle; results refetch (server data, rẻ)"],
           ["MediaViewer", "zoom scale/offset → rememberSaveable floats; download lỗi có Retry"],
           ["Contacts/Group/Profile", "draft trong UiState/rememberSaveable; dialog sống qua rotation"],
           ["Chấp nhận (documented)", "Process-death reset create-group form, contact sheet, rename dialog — transient flow, mở lại sạch từ Room/network"]],
          widths=[4, 11.5], font_size=9)
    para(doc, "Rotation không gọi lại API — Room Flows phát lại state, verify "
              "bằng log.", bold=True)

    # ------------------------------------------------------------ sec 11 --
    h1(doc, "11. Tính năng sáng tạo — Creative Feature: Message Interactions")
    rich(doc, [
        ("Chọn: Reply/Quote + Emoji Reaction + Pin", True, False),
        (" (R4). Vì sao không chọn khác: WebRTC = rủi ro ổn định cao nhất "
         "(signaling, STUN/TURN — demo fail mất R6); voice message trùng "
         "phạm vi \"gửi audio\" bắt buộc của R3; smart search mơ hồ khó demo "
         "trong 8 phút. Interactions tái dùng toàn bộ pipeline message sẵn "
         "có: +2 bảng (V3), +1 cột dùng sẵn (reply_to_id đã có từ V1), "
         "+4 endpoint.", False, False),
    ])
    h2(doc, "11.1. Server")
    bullet(doc, "POST /api/v1/messages/{id}/reactions {emoji} → 204 toggle. "
                "PK composite (message_id, user_id, emoji) là idempotency: "
                "react lại = DELETE, insert trùng = ON CONFLICT DO NOTHING "
                "(ReactionService, 7 unit test).")
    bullet(doc, "GET|POST|DELETE /api/v1/conversations/{id}/pins → DIRECT: "
                "member nào cũng pin; GROUP: chỉ ADMIN; trần ≤3/conv enforce "
                "ở PinService dưới row-lock conversation (15 unit test). Vi "
                "phạm → 409 PIN_LIMIT.")
    bullet(doc, "Reply: SendMessageRequest.replyToId validate cùng "
                "conversation; MessageResponse.replyTo = embedded preview "
                "{senderDisplayName, content, deleted} — reply ảnh bằng text "
                "(cross-type) hoạt động.")
    bullet(doc, "Broadcast REACTION_UPDATED / PIN_UPDATED qua "
                "/topic/conversations.{id}.")
    h2(doc, "11.2. Android")
    bullet(doc, "feature/interactions/: ReplyPreview (swipe/long-press → "
                "quote strip), ReactionBar (emoji chips count/byMe), "
                "PinnedBanner (≤3 pin, tap → jumpToMessage trong loaded "
                "window).")
    bullet(doc, "Data: DTO + Retrofit + Room (MessageReactionEntity, "
                "PinnedMessageEntity) + typed WS events; optimistic với "
                "revert-on-failure (compare-and-restore snapshot — sửa từ "
                "review MED-1); pin ngoài window decode từ "
                "PinnedMessageView.message nhúng sẵn (sửa MED-2).")
    bullet(doc, "Tích hợp ChatViewModel.toggleReaction/togglePin — reaction "
                "bar chỉ hiện trên tin đã SENT (không react tin pending).")

    # ------------------------------------------------------------ sec 12 --
    page_break(doc)
    h1(doc, "12. Ghi chú triển khai — Implementation Notes")
    para(doc, "Những vấn đề thật gặp phải trong build (từ execution ledger), "
              "không phải lý thuyết:")
    for prefix, text in [
        ("Testcontainers vỡ trên Docker Desktop 29 — ",
         "docker-java 1.20.4 không negotiate được named pipe. Giải: viết lại "
         "ITs chạy Postgres thật qua env-JDBC (IT_DB_URL/IT_DB_USER/"
         "IT_DB_PASSWORD; local mặc định compose "
         "jdbc:postgresql://localhost:5433/nexachat, CI dùng postgres service "
         ":5432). Coverage giữ nguyên — vẫn Postgres thật, chỉ không quản lý "
         "container lifecycle."),
        ("Cổng 5432 bị chiếm trên máy dev — ",
         "compose map 5433:5432; app container vẫn nói db:5432 nội bộ, host "
         "mapping chỉ cho tools/ITs."),
        ("i18n không có appcompat — ",
         "dependency graph thiếu androidx.appcompat nên "
         "AppCompatDelegate.setApplicationLocales không khả dụng. Giải: "
         "framework android.app.LocaleManager (33+, system-persisted) + "
         "SharedPreferences/wrapContext phía dưới."),
        ("Wire mismatch server↔android — ",
         "server emit byMe, client mong reactedByMe → thêm field byMe + "
         "resolution 3 chiều; POST /reactions trả 204 → Unit-cast CCE khi đọc "
         ".reactions → guard runCatching."),
        ("LAN demo phải đổi IP mà không sửa source — ",
         "BuildConfig.API_BASE_URL đọc từ local.properties/-PapiBaseUrl; "
         "debug-source-set network_security_config cho cleartext LAN "
         "(release giữ config chặt)."),
        ("Accessibility thật, không phải checkbox — ",
         "audit tìm ra vi phạm 48dp: nút remove-X 22dp, row quá nhỏ, nút "
         "\"!\" retry không label → sửa thành whole-bubble retry (additive UX "
         "change)."),
        ("Attachment lifecycle — ",
         "V1 buộc message_id NOT NULL nên không upload trước khi gửi được; "
         "V2 chuyển sang upload-first (owner_id + message_id nullable), "
         "MessageService.send() bind trong cùng transaction — đúng UC-09."),
        ("Pin ≤3 không cần counter column — ",
         "serialize trên row-lock của conversation; race pin/unpin giải "
         "quyết bằng khoá, không bằng check-count."),
    ]:
        p = doc.add_paragraph(style="List Number")
        run = p.add_run(prefix)
        run.bold = True
        p.add_run(text)

    # ------------------------------------------------------------ sec 13 --
    h1(doc, "13. Kiểm thử — Testing")
    h2(doc, "13.1. Kết quả thật (đếm từ source + CI)")
    table(doc,
          ["Tầng", "Công cụ", "Số test", "Trạng thái"],
          [["Server unit", "JUnit5 + Mockito (mvn test, surefire)", "258", "green"],
           ["Server integration", "@SpringBootTest + Postgres thật qua env-JDBC (failsafe, *IT)", "26", "green"],
           ["Server tổng", "mvn -B verify", "284", "green"],
           ["Android unit", "JUnit + MockK + coroutines-test + Turbine (testDebugUnitTest)", "158", "green"]],
          widths=[3.2, 7.5, 1.8, 2.5], font_size=9)
    para(doc, "CI (.github/workflows/ci.yml): job server-test (postgres:16 "
              "service + mvn -B verify với IT_DB_* env) + android-test + "
              "secret-scan — chạy trên mọi PR và push main/develop; đỏ là "
              "không merge (\"suite luôn xanh\").")
    h2(doc, "13.2. Integration test classes (26 test, Postgres thật)")
    table(doc,
          ["IT class", "Tests", "Cover"],
          [["auth/AuthFlowIT", "5", "register→login→refresh rotation→reuse→logout flow"],
           ["attachment/AttachmentFlowIT", "5", "upload→bind→download authZ, mime/size reject"],
           ["contact/ContactConversationIT", "4", "add contact → open direct, direct_key dedup"],
           ["conversation/GroupFlowIT", "3", "tạo nhóm → member mgmt → non-member bị chặn"],
           ["message/MessageFlowIT", "5", "send WS/REST, idempotency, history cursor, read"],
           ["message/InteractionFlowIT", "4", "react toggle, pin limit/policy, reply (TC-INT-01..04)"]],
          widths=[6, 1.5, 8], font_size=9)
    h2(doc, "13.3. Unit test highlight")
    para(doc, "Server: MessageServiceTest (29 — authZ, idempotency, "
              "pagination), GroupServiceTest (33), MagicBytesTest (18 — mime "
              "sniff), StompAuthChannelInterceptorTest (18 — WS authN/Z), "
              "AuthServiceTest (17), AttachmentServiceTest (16), "
              "PinServiceTest (15), LocalStorageServiceTest (14), "
              "ContactServiceTest (12), PhoneNormalizerTest (11 — E.164), "
              "UserServiceTest (11), TokenServiceTest (10 — rotation/reuse), "
              "ConversationServiceTest (10), ReactionServiceTest (7)…")
    para(doc, "Android (158 test, 17 lớp): ChatViewModelTest (optimistic "
              "send, dedup theo clientMessageId, retry), "
              "TokenAuthenticatorTest (single-flight 401→refresh), "
              "StompCodecTest (frame parse/build), TokenStoreTest, "
              "InboxViewModelTest, MessageRepositoryTest, "
              "AttachmentRepositoryTest, AuthViewModelTest, "
              "SplashViewModelTest, ContactsViewModelTest, "
              "CreateGroupViewModelTest, GroupDetailViewModelTest, "
              "MediaViewerViewModelTest, ProfileViewModelTest, "
              "AppLocaleManagerTest, WsEndpointUrlTest (wsUrl derivation + "
              "StompClient constructor crash guard), "
              "RealtimeSessionReArmTest (parked-loop re-arm: network-regain, "
              "ensureConnected + foreground wake, logged-out no-ops, "
              "concurrent single-loop).")
    h2(doc, "13.4. Live E2E & on-device verification")
    para(doc, "Unit/IT coverage không đủ để chứng minh hệ thống chạy thật — "
              "phần này kiểm chứng artifact đã đóng gói trên stack "
              "production-profile.")
    para(doc, "(a) Live stack (Docker compose, profile prod): register → tạo "
              "direct conversation → gửi message → idempotency (gửi lại cùng "
              "clientMessageId → server trả đúng message id cũ, không nhân "
              "đôi) → đọc history → reaction toggle on/off → pin → GET /pins "
              "→ reply kèm embedded preview → mark-read → inbox unread count "
              "→ non-member bị 403. STOMP raw WebSocket: CONNECT + Bearer → "
              "CONNECTED → REST gửi tin → nhận broadcast MESSAGE_CREATED → "
              "stranger CONNECTED nhưng SUBSCRIBE bị drop (authZ "
              "deny-by-default). Full event catalog kiểm chứng live: "
              "MESSAGE_CREATED, MESSAGE_READ, USER_TYPING, REACTION_UPDATED, "
              "PIN_UPDATED.")
    para(doc, "(b) On-device (emulator Android 15 / API 35, signed debug + "
              "release APK): cài đặt → launch → login tài khoản seed "
              "+84900000001 → inbox render từ Room → mở direct chat → gửi "
              "tin → nhận tin realtime qua WS push → Contacts → Profile → "
              "đổi ngôn ngữ EN→VI ngay lập tức (framework LocaleManager API "
              "33+) → group chat với sender labels → xoay ngang màn hình "
              "state giữ nguyên → logout → session restore sau reinstall.")
    para(doc, "Các luồng tương tác cũng được lái qua chính UI của release "
              "APK (không chỉ REST): long-press bubble → react 👍 (server "
              "trả byMe:true, pill hiển thị) → reply (replyToId + preview "
              "hydrated) → pin (banner + Unpin) → attach ảnh qua Photo "
              "Picker (IMAGE/png byte-identical) → tạo group 3 người qua "
              "FAB (SYSTEM event render) → search SĐT + add contact → "
              "message user mới tạo direct conv.")
    para(doc, "(c) Mười hai lỗi thật chỉ phát hiện được trên device / "
              "trong spec-gap review (một lỗi bằng adversarial review + "
              "probe, không qua UI):")
    table(doc,
          ["Lỗi", "Nguyên nhân gốc", "Fix"],
          [["Crash unexpected scheme: ws ~2s sau cold start",
            "HttpUrl.Builder.scheme(\"ws\") — OkHttp chỉ chấp nhận http/https; "
            "không test nào khởi tạo StompClient",
            "Giữ scheme http(s) — OkHttp tự upgrade; thêm WsEndpointUrlTest + "
            "constructor regression guard (dd83442)"],
           ["Login nền xanh đậm, chữ gần như vô hình",
            "Không có root Surface → windowBackground (màu splash) lộ ra các "
            "màn không có Scaffold",
            "Surface(colorScheme.background) ở app root (dd83442)"],
           ["Tin pending mãi không gửi sau khi có mạng lại",
            "runConnectionLoop thoát vĩnh viễn sau MAX_ATTEMPTS; chỉ "
            "openConversation gọi ensureConnected → không ai re-arm khi mạng "
            "về",
            "NetworkMonitor (VALIDATED) → observeNetworkRegain re-arm loop "
            "khi socket chết và còn refresh token (cb2c5ee)"],
           ["Tin mới không hiện khi user rời đáy danh sách",
            "reverseLayout neo viewport vào item đang thấy; mở bàn phím "
            "(imePadding) làm mất neo cạnh đáy",
            "ChatScreen auto-scroll về 0 khi firstVisibleItemIndex ≤ 1, "
            "luôn scroll cho tin mình gửi (cb2c5ee)"],
           ["Contact vừa add qua search không hiện trong danh bạ",
            "Mỗi nav destination có ContactsViewModel riêng; list là "
            "snapshot REST một lần — add từ màn search chỉ cập nhật "
            "instance của search",
            "ContactsRepository.contactsFlow — StateFlow cache dùng chung "
            "cho mọi fetch/add/alias/remove (a066e56)"],
           ["Socket chết lặng khi app ở background → Offline vĩnh viễn",
            "Retry loop thoát hẳn sau MAX_ATTEMPTS; regain listener chỉ "
            "bắn khi network toggle — socket chết giữa chừng (server "
            "restart, idle TCP) khi mạng vẫn tốt thì không ai re-arm",
            "Loop park chứ không exit: chờ merge(regained, rearmRequests); "
            "ensureConnected (user action + send path) + "
            "ProcessLifecycleOwner.onStart → onAppForeground đánh thức "
            "(gate !isOpen && hasValidRefresh). Verify: docker restart khi "
            "background → foreground → tự reconnect → push + typing sống"],
           ["Loop park nhưng thực ra SPIN khi mạng vẫn tốt",
            "regained là cold callbackFlow — mỗi collector "
            "registerNetworkCallback riêng và Android bắn onAvailable "
            "NGAY cho network đã kết nối → parked-merge nhận regain giả "
            "ngay khi subscribe → wake→fail→park vô hạn (~25s/chu kỳ, "
            "quan trắc trên device bằng collector-hash probe)",
            "regained → SharedFlow qua shareIn(scope, Lazily, replay=0) — "
            "1 registration cả đời app, collector trễ chỉ thấy regain "
            "thật. Verify: docker stop→park→docker start→60s vẫn Offline "
            "(không spin) → mở chat → wake → resync đúng"],
           ["Read receipt \"Đã xem\" (FR-C4, P0) có watermark + wire nhưng "
            "không render trên bubble người gửi",
            "_readEvents route MESSAGE_READ vào SharedFlow không ai "
            "collect — UI không có consumer; DeliveryStatus.SENT -> Unit",
            "Room v4: conversations.peerReadSeq (bump đơn điệu theo "
            "MESSAGE_READ, bỏ qua echo của mình; refresh REST "
            "merge-preserve) → ChatViewModel → bubble mình gửi hiển thị "
            "\"Đã xem\"/\"Seen\" khi seq <= peerReadSeq, chỉ DIRECT. "
            "Verify 2-device: A gửi→B đọc→\"Seen\" trên A; sống qua "
            "force-stop; chiều ngược lại trên B cũng \"Seen\""],
           ["Admin không thấy nút Remove/Add member/Rename — quản lý "
            "member không dùng được từ UI",
            "Server InboxEntry.Member không trả role; client "
            "ConversationMemberDto.role nullable chấp nhận thiếu field → "
            "iAmAdmin luôn false → affordance admin ẩn vĩnh viễn",
            "Server thêm role vào InboxMemberView + InboxEntry.Member "
            "(client không đổi — DTO đã tolerate). Verify release APK: "
            "badge \"Admin\", Remove trên từng member, Add member, "
            "Rename hiện; remove Chi qua confirm → server mất row + "
            "SYSTEM \"An removed Chi\"; add lại qua picker"],
        ["\"Change password\" (FR-A6, P0) chỉ là text mờ không bấm "
            "được — tính năng không tồn tại trên UI",
            "ProfileScreen render row stub alpha(0.5f) với comment "
            "\"no change-password screen exists yet\" dù server PATCH "
            "/auth/password + AuthRepository.changePassword + DTO đã đủ",
            "Profile thêm dialog 3 field (current/new/confirm — tái dùng "
            "PasswordField + error flags chuẩn auth); VM validate từng "
            "field, gọi repo kèm refresh token hiện tại để giữ session "
            "thiết bị này (FR-A6). Verify release APK 2 lượt đổi thật: "
            "password123→np456789→password123, login xác nhận cả hai "
            "chiều; wrong-old hiện INVALID_CREDENTIALS; +4 VM tests"],
        ["Avatar (FR-A5) chỉ có nửa server — client không có flow "
            "upload; 3 điểm nối render cũng gãy: ContactResponse bỏ sót "
            "avatarUrl, resolvedOtherAvatar không đọc members[], không "
            "chỗ nào render AsyncImage cho avatar",
            "ProfileScreen chỉ có letter-circle; server POST "
            "/users/me/avatar đã hoàn chỉnh nhưng UserApi thiếu hẳn "
            "method; payload inbox bỏ avatar vào members[] chứ không "
            "phải otherUser*",
            "Client end-to-end: UserApi.uploadAvatar multipart → repo "
            "(cap 25MB, re-fetch getMe) → VM → picker "
            "PickVisualMedia.ImageOnly → InitialAvatar(name, avatarUrl) "
            "qua authed Coil cho mọi row (profile, inbox DIRECT, member, "
            "contacts, search, group-create); resolvedOtherAvatar(myId) "
            "fallback members[!=self] gate DIRECT-only (bug group mượn "
            "avatar member bị bắt on-device trước commit); server thêm "
            "avatarUrl vào ContactResponse + JPQL. Verify: pick ảnh → "
            "users.avatar_url persist → GET authed 200 đúng byte, "
            "unauthed 401 → B thấy avatar ở inbox + member row; +3 VM "
            "tests"],
        ["Group avatar (FR-D4) gãy toàn chuỗi: client không có DTO "
            "field/Room column/upload UI, và InboxEntry bỏ sót avatarUrl "
            "conversation-level — silent-degradation lần 4 cùng lớp "
            "role/contacts (InboxConversationRow đã SELECT nhưng service "
            "không forward vào DTO)",
            "Server conversations.avatar_url + PATCH + "
            "CONVERSATION_UPDATED đã đủ nhưng không có endpoint upload; "
            "client DTO/entity/summary thiếu hẳn field",
            "Server: POST /conversations/{id}/avatar ADMIN-gated — sniff "
            "MagicBytes + StorageService, broadcast CONVERSATION_UPDATED; "
            "InboxEntry thêm avatarUrl forward từ InboxConversationRow. "
            "Client: Room v5 MIGRATION_4_5, GroupApi.uploadAvatar, "
            "setGroupAvatar patch Room, displayAvatar = group ?: peer, "
            "GroupDetail header 72dp + picker gate iAmAdmin. Verify "
            "release APK: REST upload → inbox trả field → B render ảnh ở "
            "group row; authed GET 200/1903B, anon 401; +3 "
            "GroupServiceTest"]])
    para(doc, "(d) Offline outbox + auto-reconnect (airplane-mode E2E): bật "
              "airplane mode → gửi tin → bubble hiện SENDING ngay → tắt "
              "airplane mode → ~15s sau WS tự reconnect và outbox tự flush, "
              "tin chuyển SENT đúng thứ tự seq, không nhân đôi. Kịch bản "
              "được chạy lại trên release APK đã ký — re-arm sống qua "
              "R8/minify. Review sau fix phát hiện thêm check-then-launch "
              "của launchConnectionLoop không atomic (token/regain/user "
              "action đua nhau) → bọc synchronized + regression test 50 "
              "luồng chỉ mở 1 loop (18b32ca). Bài học ghi vào release "
              "checklist: gate \"install + launch signed APK trên device\" "
              "là bắt buộc trước khi tag.")
    para(doc, "(e) Multi-device E2E (2 emulator, cả hai chạy signed release "
              "APK): campuscore-p07-native login An ↔ campuscore-demo login "
              "Binh, cùng mở direct chat. A→B gửi tin → hiện trên B qua WS "
              "push; B→A reply → hiện trên A; B gõ phím → A hiện "
              "typing… (STOMP SEND → broker fan-out USER_TYPING → conv "
              "topic → indicator). A nhận cả REACTION_UPDATED/PIN_UPDATED "
              "(topic-only) — subscription đầy đủ, không chỉ inbox queue. "
              "Read receipt hai chiều trên artifact đã ký: B mở chat → "
              "watermark POST → MESSAGE_READ → bubble A hiển thị \"Seen\" "
              "ngay, sống qua force-stop + reopen; chiều ngược lại trên B "
              "cũng \"Seen\" (watermark hai phía đồng loạt). Unread badge "
              "lifecycle: push tới inbox tăng badge, mở chat badge về 0 và "
              "watermark server tiến. History paging: scroll qua ~680 "
              "messages tới seq 1 mượt. Rotation giữ draft + conversation "
              "state. Group admin qua UI: remove Chi (confirm) + re-add "
              "qua picker; SYSTEM \"An removed Chi\" sau resync. Media "
              "matrix du 4 loai tren release APK: VIDEO video/mp4 (seq "
              "751), FILE text/plain (752), AUDIO audio/mpeg (753), IMAGE "
              "image/png (670) — inbox B tang badge live, B resync render "
              "ca 3 bubble, \"Open attachment\" phat video tren may nhan "
              "(download qua auth; khong token tra 401 dung spec). Vong "
              "doi tai khoan lai qua UI release: doi display name \"An "
              "Nguyen\" (hien tren inbox B); doi ten nhom \"NexaChat Group\" "
              "(SYSTEM + title tren B doi live qua CONVERSATION_UPDATED); "
              "register +84900000077 qua form → auto-login inbox rong → "
              "search SDT + add contact tu account fresh; doi mat khau 2 "
              "luot tren dialog (wrong-old → INVALID_CREDENTIALS, success "
              "giu session nho keepToken, login xac nhan ca hai chieu; "
              "demo state khoi phuc nguyen).")
    h2(doc, "13.5. Tái hiện / Reproduce")
    code_box(doc,
        "# Server: Postgres that roi verify (unit + IT)\n"
        "docker compose up -d db          # postgres:16 @ localhost:5433\n"
        "cd server && mvn -B verify       # 284 tests\n"
        "\n"
        "# Android unit tests\n"
        "cd android && ./gradlew testDebugUnitTest   # 158 tests")
    para(doc, "Manual E2E: docs/demo-runbook.md §5 — 10 bước demo ~8 phút "
              "(2 máy chat 1-1, 3 máy nhóm, offline send, media, "
              "interactions, i18n+rotation, CI xanh).")

    # ------------------------------------------------------------ sec 14 --
    page_break(doc)
    h1(doc, "14. Triển khai & Demo — Deployment & Demo")
    h2(doc, "14.1. Docker — một lệnh")
    code_box(doc,
        "docker compose up -d db app      # lan dau build image ~3-5 phut\n"
        "curl http://localhost:8080/actuator/health    # {\"status\":\"UP\"}")
    para(doc, "docker-compose.yml: db = postgres:16-alpine (healthcheck "
              "pg_isready, volume pgdata, host 5433→5432); app = multi-stage "
              "server/Dockerfile (Maven build JDK17 → eclipse-temurin:17-jre, "
              "non-root user, curl cho HEALTHCHECK), profile prod, Flyway "
              "migrate khi boot, volume app-uploads cho STORAGE_ROOT, "
              "JWT_SECRET passthrough env (fallback dev để fresh clone vẫn "
              "boot). Healthcheck GET /actuator/health (permitAll), "
              "start_period 60s.")
    h2(doc, "14.2. LAN demo (demo/)")
    bullet(doc, "./demo/lan-up.sh (Git Bash) / lan-up.ps1 (PowerShell): "
                "detect LAN IP → compose up → chờ health → seed → in URL + "
                "accounts.")
    bullet(doc, "./demo/backup.sh: pg_dump → "
                "demo/backups/nexachat-<timestamp>.sql (gitignored); restore "
                "bằng psql.")
    bullet(doc, "demo/seed.sql: 4 tài khoản demo — +84900000001 An, "
                "+84900000002 Binh, +84900000003 Chi, +84900000004 Dung; "
                "password password123 (bcrypt cost 10). Chỉ dùng cho demo.")
    bullet(doc, "App trỏ backend qua BuildConfig.API_BASE_URL — đặt "
                "API_BASE_URL=http://<LAN-IP>:8080 trong "
                "android/local.properties rồi rebuild, không sửa source. "
                "Fallback: adb reverse tcp:8080 tcp:8080 + 127.0.0.1; "
                "hotspot dự phòng; emulator mặc định 10.0.2.2.")
    h2(doc, "14.3. Kịch bản demo ~8 phút (map rubric — docs/demo-runbook.md §5)")
    para(doc, "Splash → session restore (30s) → tìm theo SĐT + add contact + "
              "mở chat (60s) → chat 1-1 realtime + tắt wifi → tin xám → bật "
              "lại → tin tới (90s) → read receipt + unread badge (30s) → "
              "nhóm 3 người + member mgmt (90s) → media ảnh/video/PDF + "
              "progress (60s) → creative reply/react/pin (45s) → đổi En→Vi + "
              "xoay màn giữ draft (45s) → test suites xanh (60s) → kiến "
              "trúc + ERD + log (60s).")

    # ------------------------------------------------------------ sec 15 --
    h1(doc, "15. Hạn chế & hướng phát triển — Limitations & Future Work")
    para(doc, "Đầy đủ ở docs/known-limitations.md; tóm tắt trung thực:")
    h2(doc, "15.1. Ngoài phạm vi theo thiết kế")
    table(doc,
          ["Hạn chế", "Vì sao", "Hướng phát triển"],
          [["Không E2E encryption", "Threat model chọn = JWT + TLS; E2EE không nằm trong MVP", "Key exchange per-conversation + client crypto"],
           ["Không WebRTC voice/video", "Rủi ro ổn định cao nhất; đề tự khuyên loại nếu ảnh hưởng cốt lõi", "Signaling + STUN/TURN đã thiết kế sẵn"],
           ["Không FCM push", "Optional tier; demo foreground đủ", "Integration point đã vẽ sẵn"],
           ["SimpleBroker 1 instance", "Đúng cho demo/deploy 1 node", "Broker relay RabbitMQ + rate-limit bucket ra Redis/JDBC"]],
          widths=[3.6, 6.4, 5.5], font_size=8.5)
    h2(doc, "15.2. Deferred findings (ghi thành thật)")
    bullet(doc, "STOMP SEND chưa rate-limit (REST có); inbox "
                "GET /conversations chưa phân trang; client chưa "
                "proactive-refresh token trước WS reconnect (chờ 401/403); "
                "presence USER_ONLINE/OFFLINE chưa emit; listPins N+1 nhỏ "
                "(bounded ≤3); reactions/pins chưa vào offline outbox "
                "(optimistic + revert); pin order REST-vs-WS lệch cosmetic; "
                "emoji chưa NFC-normalize; message edit/delete đã plumb "
                "editedAt/deletedAt nhưng endpoint chưa ship; chưa có "
                "androidTest/instrumented suite.")
    bullet(doc, "Env: Testcontainers không chạy được trên Docker Desktop 29 → "
                "IT qua env-JDBC; LAN demo cleartext (dev-only, prod "
                "WSS/TLS); dev JWT secret là placeholder (prod fail-fast).")
    bullet(doc, "UX: pin menu hiện cho non-admin (server 403 vẫn chặn + "
                "snackbar); jump-to-quote/pin chỉ trong loaded window; "
                "reaction bar ẩn trên tin pending.")

    # ------------------------------------------------------------ sec 16 --
    page_break(doc)
    h1(doc, "16. Phân công công việc — Work Breakdown")
    note(doc, "Template — điền tên thật khi nộp. Nguyên tắc: mỗi thành viên "
              "sở hữu vertical slice (màn hình + API + test) → commit/PR mang "
              "tên thật, là bằng chứng cho hệ số đóng góp 0.9–1.1. "
              "git shortlog -sn phải cân đối.")
    table(doc,
          ["Thành viên", "MSSV", "Sở hữu backend", "Sở hữu Android", "Test & tài liệu", "Tỉ lệ"],
          [["{{MEMBER_1}}", "{{MSSV_1}}", "auth/*, token rotation, rate limit, WS/STOMP layer, MessageService", "— (pair review Auth UI)", "Auth/message tests; báo cáo phần security", "{{PCT_1}}%"],
           ["{{MEMBER_2}}", "{{MSSV_2}}", "user, contact, conversation, attachment + storage, Flyway", "—", "Docker/compose/CI; báo cáo phần DB+API", "{{PCT_2}}%"],
           ["{{MEMBER_3}}", "{{MSSV_3}}", "—", "Scaffold, auth UI, session, StompClient, Room, Chat/Inbox, offline outbox", "Android unit tests chat; báo cáo Android arch", "{{PCT_3}}%"],
           ["{{MEMBER_4}}", "{{MSSV_4}}", "— (pair attachment API client)", "Contacts/search/group UI, media UI, i18n, rotation, Settings, interactions UI", "Test matrix, manual E2E, screenshots, báo cáo + RTM + slide", "{{PCT_4}}%"]],
          widths=[2.4, 1.8, 4, 4, 3.2, 1.2], font_size=8)
    para(doc, "Review chéo: {{MEMBER_1}}↔{{MEMBER_2}}, "
              "{{MEMBER_3}}↔{{MEMBER_4}}; cross-pair attachment "
              "({{MEMBER_2}}↔{{MEMBER_4}}) và auth "
              "({{MEMBER_1}}↔{{MEMBER_3}}). WS layer + TokenAuthenticator "
              "pair-program — hai điểm khó nhất, ≥2 người hiểu.")

    # ------------------------------------------------------------ sec 17 --
    h1(doc, "17. Tuyên bố sử dụng AI — AI Usage Declaration")
    para(doc, "AI tools được dùng để brainstorm thiết kế, sinh boilerplate "
              "và gợi ý test case. Mọi dòng merge — AI hỗ trợ hay không — "
              "đều đã qua review và owner giải thích được (quy tắc "
              "\"explain-back\": không merge đoạn nào mà người merge không "
              "giải thích được khi vấn đáp).")
    table(doc,
          ["Thành viên", "Vai trò", "Phần AI hỗ trợ", "Explain-back"],
          [["{{MEMBER_1}}", "Backend Lead (auth, realtime)", "{{AI_AREA_1}}", "☐"],
           ["{{MEMBER_2}}", "Backend + DevOps", "{{AI_AREA_2}}", "☐"],
           ["{{MEMBER_3}}", "Android Lead (chat core)", "{{AI_AREA_3}}", "☐"],
           ["{{MEMBER_4}}", "Android + QA/Docs", "{{AI_AREA_4}}", "☐"]],
          widths=[3, 4.5, 5.5, 2.5], font_size=9)
    para(doc, "Mỗi PR khai báo AI involvement trong description "
              "(.github/pull_request_template.md).")

    # ------------------------------------------------------------ sec 18 --
    h1(doc, "18. Kết luận — Conclusion")
    para(doc, "NexaChat ship đầy đủ phạm vi rubric: chat 1-1/nhóm realtime "
              "qua STOMP-over-WebSocket, tìm & thêm bằng SĐT E.164, quản lý "
              "tài khoản với JWT refresh rotation + reuse detection, "
              "media/file upload-first với magic-byte hardening, i18n Vi/En "
              "100% parity, rotation an toàn, và creative feature Message "
              "Interactions trên nền schema V1→V3 có kiểm soát bằng Flyway.")
    para(doc, "Chất lượng được chứng minh bằng số liệu thật: 284 test server "
              "(258 unit + 26 integration trên Postgres thật) và 158 test "
              "Android, CI gate xanh trên mọi PR; deploy một lệnh "
              "docker compose up; demo LAN có runbook + fallback. Các hạn "
              "chế được khai báo trung thực (không E2E, SimpleBroker 1 node, "
              "presence chưa emit, edit/delete plumb sẵn chưa ship) — mỗi "
              "hạn chế kèm hướng phát triển rõ ràng, đúng nguyên tắc "
              "\"không overclaim\" của đồ án.")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    doc.save(OUT)
    return OUT


def main():
    out = build()
    size = out.stat().st_size
    print(f"wrote {out} ({size:,} bytes)")

    # verify: reopen and count
    chk = Document(out)
    heads = [p.text for p in chk.paragraphs if p.style.name.startswith("Heading")]
    print(f"re-opened OK: {len(chk.paragraphs)} paragraphs, "
          f"{len(chk.tables)} tables, {len(heads)} headings")
    if size < 20_000:
        raise SystemExit("FAIL: docx < 20KB")
    h1s = [h for h in heads if h.strip() and h[0].isdigit() or h.startswith("Mục lục")]
    print(f"top-level sections: {len([h for h in heads if h and (h[0].isdigit() or h.startswith('Mục'))])}")


if __name__ == "__main__":
    main()
