> **Historical survey (2026-11-08).** Current contracts: [`docs/architecture.md`](docs/architecture.md), [`docs/api-contract.md`](docs/api-contract.md), [`docs/testing.md`](docs/testing.md).

# KIẾN TRÚC & PHƯƠNG PHÁP LUẬN KIỂM THỬ E2E TOÀN DIỆN (TEST INFRASTRUCTURE)
## HỆ THỐNG TIN NHẮN & CUỘC GỌI BẢO MẬT THỜI GIAN THỰC NEXACHAT (P0 PRODUCTION ENGINEERING)

- **Dự án**: NexaChat
- **Kho lưu trữ**: Thư mục gốc dự án (`.`)
- **Ràng buộc công nghệ**: 100% Pure Java 21 LTS (0 file Kotlin `.kt`/`.kts`), 0 leaked secrets, 0 broken markdown links.
- **Tài liệu tham chiếu cơ sở**: [README.md](README.md), [TECHNICAL_ROADMAP.md](TECHNICAL_ROADMAP.md), [docs/api-contract.md](docs/api-contract.md), [docs/websocket-protocol.md](docs/websocket-protocol.md), [docs/calls.md](docs/calls.md).
- **Mã hiệu tài liệu**: `NEXA-TEST-INFRA-2026-V1`
- **Ngày ban hành**: 08/11/2026

---

## 1. TRIẾT LÝ KIỂM THỬ HỘP MỜ (OPAQUE-BOX TESTING PHILOSOPHY)

### 1.1 Nguyên lý Kiểm thử Hộp mờ Dựa trên Yêu cầu (Requirement-Driven Opaque-Box Principle)
Hạ tầng kiểm thử E2E của NexaChat được xây dựng dựa trên triết lý **Opaque-Box (Kiểm thử Hộp mờ / Hộp đen đặc tả)**. Khác với kiểm thử đơn vị nội bộ (white-box unit testing) vốn phụ thuộc chặt chẽ vào cấu trúc lớp, tên biến private hay phương thức cục bộ, kiểm thử hộp mờ tuân thủ các nguyên tắc cốt lõi:

1. **Độc lập hoàn toàn với mã thực thi nội bộ (Implementation-Agnostic)**:
   - Mọi ca kiểm thử được dẫn xuất trực tiếp từ tài liệu yêu cầu sản phẩm [TECHNICAL_ROADMAP.md](TECHNICAL_ROADMAP.md) và hợp đồng giao tiếp (REST API JSON schemas, STOMP wire protocols, SQLite/Room DDL specifications, và các máy trạng thái chuẩn).
   - Kiểm thử không bẫy lỗi hay ràng buộc vào tên lớp cụ thể của bên triển khai, nhờ đó ngăn ngừa hiện tượng "kiểm thử dễ vỡ" (brittle tests) khi tái cấu trúc mã nguồn.
2. **Nguồn chân lý đầu ra có thẩm quyền (Authoritative Oracles)**:
   - Đầu ra kỳ vọng của mỗi ca kiểm thử được tính toán từ các chuẩn toán học và giao thức tiêu chuẩn:
     * Thuật toán **RFC 4122 UUIDv5** (băm SHA-1 trên không gian tên định danh) để kiểm chứng tính lũy thỏa `action_idempotency_key`.
     * Phân phối trễ số mũ $delay = \min(1000 \times 2^{attempt}, 16000)$ kèm dải biến thiên đồng đều $\pm 20\%$ jitter.
     * Cấu trúc bảng và chỉ mục SQLite Room v8 theo đúng chuẩn SQL ANSI và Room hash schema.
     * Quy tắc phân giải trạng thái 2-Tick và máy trạng thái WebRTC 15s Grace Period.
3. **Tính tiến triển theo mốc (Progressive Testability & Isolation)**:
   - Bộ kiểm thử tự bao bọc toàn bộ dữ liệu giả lập (self-contained fixtures), không phụ thuộc vào thứ tự thực thi giữa các bài kiểm thử, và dọn dẹp sạch sẽ tài nguyên sau khi chạy.
   - Hoạt động độc lập 100% trong môi trường CI/CD không có kết nối Internet ngoài và không yêu cầu bí mật (0 secrets).

---

## 2. PHƯƠNG PHÁP LUẬN THIẾT KẾ 4 TẦNG KIỂM THỬ (4-TIER TEST ARCHITECTURE)

Hạ tầng kiểm thử được phân tầng theo mô hình 4 cấp độ kiểm thử hình chóp, đảm bảo bao phủ từ tính năng đơn lẻ tới kịch bản thực tế phức tạp:

```
+-----------------------------------------------------------------------------------+
|                        TIER 4: REAL-WORLD APPLICATION SCENARIOS                   |
|           5 kịch bản đa tính năng quy mô lớn (Subway, Doze, Chat Storm...)        |
+-----------------------------------------------------------------------------------+
|                     TIER 3: CROSS-FEATURE COMBINATIONS (PAIRWISE)                 |
|       16 ca kiểm thử kết hợp chéo (Outbox + Sync, 2-Tick + Reconnect, Roam...)    |
+-----------------------------------------------------------------------------------+
|                     TIER 2: BOUNDARY & CORNER CASES (STRESS & FAULTS)             |
|       25 ca kiểm thử biên (Lease 60s, Malformed Watermarks, Inversion, Perms...)  |
+-----------------------------------------------------------------------------------+
|                     TIER 1: CORE FEATURE COVERAGE (P0 REQUIREMENTS)               |
|       35 ca kiểm thử bao phủ toàn diện 7 phân hệ tính năng P0 (5 tests/feature)   |
+-----------------------------------------------------------------------------------+
```

---

## 3. CHI TIẾT CÁC TẦNG KIỂM THỬ (DETAILED TEST SPECIFICATIONS)

### 3.1 TIER 1: TÍNH NĂNG CỐT LÕI (CORE FEATURE COVERAGE — 35 TESTS)

Bao phủ 7 phân hệ tính năng P0 đã được phê duyệt, mỗi phân hệ tối thiểu 5 ca kiểm thử độc lập:

#### Phân hệ 1: Room Database v8 Migration & Thực thể Ngoại tuyến (`room_v8_`)
- `T1.1.1`: **MIGRATION_7_8 DDL Schema Verification** — Kiểm chứng câu lệnh SQL di chuyển tạo bảng `users`, `call_logs` và mở rộng bảng `outbox` với 4 cột mới.
- `T1.1.2`: **UserEntity Schema Contract** — Xác thực các cột `id` (PK), `username`, `displayName`, `phoneNumber`, `avatarUrl`, `bio`, `lastSeenAt`, `updatedAt` và chỉ mục `index_users_displayName`.
- `T1.1.3`: **CallLogEntity Schema Contract** — Xác thực các cột `callId` (PK), `peerId`, `peerDisplayName`, `peerAvatarUrl`, `direction`, `callType`, `startedAt`, `durationSeconds`, `endReason` và chỉ mục `startedAt`.
- `T1.1.4`: **OutboxEntity 4-Column Extension** — Xác thực các cột `actionType`, `payloadJson`, `retryCount`, `lastAttemptEpochMs` mang giá trị mặc định chuẩn xác khi di chuyển từ v7.
- `T1.1.5`: **Room Identity Hash Verification** — Kiểm chứng thuật toán mã băm nhận diện schema của Room không bị sai lệch, ngăn ngừa lỗi `IllegalStateException: Room cannot verify the data integrity`.

#### Phân hệ 2: Máy trạng thái Outbox & Khóa Lũy Thỏa UUIDv5 (`outbox_`)
- `T1.2.1`: **Outbox State Machine Happy Path** — Chuyển dịch tuần tự `PENDING` -> `IN_PROGRESS` -> `SENT` khi gửi tin nhắn thành công.
- `T1.2.2`: **Outbox Terminal Error Transition** — Chuyển dịch `PENDING` -> `IN_PROGRESS` -> `FAILED` khi gặp lỗi cố định HTTP 403 `NOT_MEMBER`, đình chỉ retry vô tận.
- `T1.2.3`: **Atomic Lease Lock Acquisition** — Kiểm chứng cập nhật nguyên tử chuyển sang `IN_PROGRESS` gán `lastAttemptEpochMs = now`, chặn luồng khác chiếm quyền trong bộ nhớ.
- `T1.2.4`: **UUIDv5 Deterministic Generation** — Kiểm chứng thuật toán sinh `UUIDv5(userId + ":" + messageId + ":" + emoji)` khớp 100% với đặc tả RFC 4122.
- `T1.2.5`: **Explicit ADD/REMOVE Action Semantics** — Kiểm chứng hành động gửi cảm xúc tường minh loại bỏ hoàn toàn cơ chế toggle mù quáng.

#### Phân hệ 3: Đồng bộ Delta Sync Phía Server & Client (`delta_sync_`)
- `T1.3.1`: **Batch Delta Sync Request Schema** — Xác thực cấu trúc payload JSON `POST /api/v1/sync` chứa danh sách watermarks `{conversationId, lastSeq}` và `limitPerConv`.
- `T1.3.2`: **Delta Bundle Response Schema** — Xác thực gói trả về chứa đầy đủ danh mục: `newMessages`, `editedMessages`, `deletedMessageIds`, `reactionUpdates`, `maxSeq`, `hasMore`.
- `T1.3.3`: **Monotonic Sequence Filtering** — Đảm bảo tin nhắn mới trả về có số tuần tự `seq > lastSeq` một cách nghiêm ngặt.
- `T1.3.4`: **Tombstone & Edit Partitioning** — Đảm bảo tin nhắn có `edited_at > 0` và tin nhắn bị xóa được phân loại chính xác vào các mảng riêng biệt trong bundle.
- `T1.3.5`: **Multi-Conversation Vector Batching** — Kiểm chứng khả năng tổng hợp mốc watermark của 20+ cuộc hội thoại trong một request duy nhất.

#### Phân hệ 4: Biên nhận Chuyển phát & Đã đọc 2-Tick (`two_tick_`)
- `T1.4.1`: **DeliveryStatus Enum Lifecycle** — Xác thực 5 trạng thái: `SENDING`, `SENT` (1 tick), `DELIVERED` (2 tick xám), `READ` (2 tick xanh), `FAILED`.
- `T1.4.2`: **STOMP Delivery Ack Endpoint Contract** — Kiểm chứng đích đến `/app/chat.delivery.ack` cập nhật `last_delivered_seq = GREATEST(...)` và phát sự kiện `MESSAGE_DELIVERED`.
- `T1.4.3`: **STOMP Read Ack Endpoint Contract** — Kiểm chứng đích đến `/app/chat.read.ack` cập nhật `last_read_seq = GREATEST(...)` và phát sự kiện `MESSAGE_READ`.
- `T1.4.4`: **1-1 Chat Receipt Evaluation** — Kiểm chứng quy tắc: `seq <= peerDeliveredSeq` -> DELIVERED (2 vạch xám); `seq <= peerReadSeq` -> READ (2 vạch xanh).
- `T1.4.5`: **Group Chat Delivery Evaluation** — Kiểm chứng quy tắc nhóm: >=1 thành viên nhận được -> hiển thị 2 vạch xám; toàn bộ thành viên đọc -> hiển thị 2 vạch xanh kèm đếm số lượng người đọc.

#### Phân hệ 5: WebSocket Backoff số mũ kèm Jitter (`ws_backoff_`)
- `T1.5.1`: **Exponential Delay Progression** — Kiểm chứng dãy thời gian chờ cơ sở: 1000ms, 2000ms, 4000ms, 8000ms, trần 16000ms.
- `T1.5.2`: **Random Jitter Bounds** — Đảm bảo thời gian chờ thực tế luôn nằm nghiêm ngặt trong khoảng dao động $[0.8 \times delay, 1.2 \times delay]$ ($\pm 20\%$).
- `T1.5.3`: **Max Attempts Threshold** — Sau 5 lần retry thất bại liên tiếp, máy trạng thái chuyển sang `PARKED`, dừng gửi request dồn dập.
- `T1.5.4`: **Success Counter Reset** — Bất kỳ kết nối thành công nào đều lập tức thiết lập lại số lần thử về 0.
- `T1.5.5`: **Thundering Herd Dispersion** — Giả lập 100 client đồng thời kết nối lại, phân tán đều các mốc thời gian, loại bỏ hiện tượng bão yêu cầu.

#### Phân hệ 6: WebRTC Roaming & Ân hạn 15 Giây (`webrtc_roam_`)
- `T1.6.1`: **CallState RECONNECTING_NETWORK Transition** — Khi đứt socket STOMP lúc cuộc gọi đang `ACTIVE`, chuyển sang `RECONNECTING_NETWORK` thay vì ngắt cuộc gọi ngay.
- `T1.6.2`: **Media Track Preservation** — Luồng âm thanh và micrô được giữ nguyên vẹn trong suốt vùng đệm 15 giây.
- `T1.6.3`: **Outbound Signaling Buffer** — Hàng đợi tín hiệu ngoại tuyến đệm các frame signaling theo thứ tự FIFO trong khi chờ mạng.
- `T1.6.4`: **ICE Restart Trigger on Reconnect** — Khi STOMP có lại mạng, kích hoạt `restartIce()` sinh SDP offer mang cờ `iceRestart: true`.
- `T1.6.5`: **15-Second Grace Terminal Hangup** — Nếu quá 15,000ms không thể kết nối lại, cuộc gọi kết thúc chuẩn xác với lý do `CallError.Code.NETWORK`.

#### Phân hệ 7: FCM Push Pointer & Android 14 Full-Screen Intent (`fcm_push_`)
- `T1.7.1`: **Pointer Payload Size Under 1KB** — Xác thực kích thước payload FCM luôn dưới 1024 bytes (mục tiêu < 500B), tuyệt đối không chứa SDP thô.
- `T1.7.2`: **Data-Only Pointer Schema** — Xác thực cấu trúc payload `{type: "CALL_INVITE", callId, conversationId, callerId, callerName, callType, timestamp}`.
- `T1.7.3`: **Fast-Path SDP Offer Endpoint Contract** — Xác thực endpoint `GET /api/v1/calls/{id}/offer` trả về SDP Offer độc lập.
- `T1.7.4`: **CallState PENDING_INCOMING Pre-hydration** — Khởi tạo phiên `PENDING_INCOMING` khi nhận push, ngăn ngừa lỗi tự hủy Activity trong 0ms.
- `T1.7.5`: **Android 14 Full-Screen Intent Fallback** — Khi `canUseFullScreenIntent()` trả về `false`, tự động hạ cấp sang Heads-Up Banner Notification độ ưu tiên `PRIORITY_MAX`.

---

### 3.2 TIER 2: TRƯỜNG HỢP BIÊN & PHÒNG VỆ SỰ CỐ (BOUNDARY & CORNER CASES — 25 TESTS)

Bao phủ 5 nhóm trường hợp biên và điều kiện bất thường nghiêm ngặt (5 tests/nhóm):

#### Nhóm 1: Giới hạn Thời gian Khóa Thuê 60s (`lease_lock_`)
- `T2.1.1`: **Active Lease (<60s) Rejection** — Khóa thuê còn hiệu lực (< 60,000ms) kiên quyết từ chối quyền tiếp quản của tiến trình/luồng khác.
- `T2.1.2`: **Expired Lease (>=60s) Recovery** — Khóa thuê quá hạn (>= 60,000ms) tự động cho phép worker tiếp theo chiếm quyền và xử lý tiếp.
- `T2.1.3`: **Exact 60,000ms Boundary Edge** — Kiểm chứng chính xác tại mốc biên $t = 60,000\text{ ms}$.
- `T2.1.4`: **Process Crash & Kill Recovery** — Giả lập tiến trình bị Android LMK tiêu diệt giữa chừng khi đang `IN_PROGRESS`; worker mới giải phóng khóa thành công sau 60s.
- `T2.1.5`: **Clock Drift & Skew Protection** — Đảm bảo hiện tượng trôi đồng hồ hệ thống không gây treo khóa vĩnh viễn.

#### Nhóm 2: Mốc Watermark Rỗng & Dị Thường (`watermark_`)
- `T2.2.1`: **Empty Watermarks Array Rejection** — Gửi `watermarks: []` tới `POST /api/v1/sync` lập tức nhận mã lỗi HTTP 400 Bad Request.
- `T2.2.2`: **Null ConversationId Validation** — Bản ghi watermark chứa `conversationId = null` bị chặn bởi validation layer.
- `T2.2.3`: **Negative Sequence Normalization** — Watermark mang `lastSeq < 0` được chuẩn hóa an toàn về 0 mà không làm crash máy chủ.
- `T2.2.4`: **Non-Existent Conversation Handling** — Watermark trỏ tới cuộc trò chuyện không tồn tại trả về delta rỗng, không làm phát sinh lỗi 500.
- `T2.2.5`: **Sequence Beyond Server MaxSeq** — Watermark mang `lastSeq > serverMaxSeq` trả về `hasMore = false` và mảng rỗng.

#### Nhóm 3: Triệt tiêu Đảo ngược Cảm xúc Đa luồng (`toggle_inversion_`)
- `T2.3.1`: **Concurrent Duplicate ADD Idempotency** — Nhiều luồng cùng gửi yêu cầu `ADD` với chung một `action_idempotency_key` chỉ tạo ra duy nhất một bản ghi.
- `T2.3.2`: **Rapid ADD -> REMOVE -> ADD Sequence** — Chuỗi thao tác dồn dập hội tụ đúng về trạng thái cuối cùng `ADDED`, không bị race condition.
- `T2.3.3`: **Replayed ADD After Server Processing** — Gửi lại yêu cầu `ADD` từ outbox khi server đã xử lý xong trả về kết quả thành công (no-op).
- `T2.3.4`: **Replayed REMOVE on Already Removed Reaction** — Gửi lại yêu cầu `REMOVE` khi reaction đã bị xóa không gây lỗi 404/500.
- `T2.3.5`: **High-Concurrency Multi-Thread Stress** — 20 luồng đồng thời thao tác trên cùng một tin nhắn duy trì tính toàn vẹn trạng thái tuyệt đối.

#### Nhóm 4: Chuyển mạng trong khi ICE Restart (`ice_switch_`)
- `T2.4.1`: **Rapid Double Hop Network Switch** — Đổi mạng nhanh Wi-Fi -> 4G -> Wi-Fi trong vòng 15s tái vũ trang ICE restart mà không sinh bộ đếm trùng lặp.
- `T2.4.2`: **ICE Negotiation Failure Retry** — Đàm phán ICE thất bại lần 1 tự động thử lại nếu quỹ thời gian 15s vẫn còn.
- `T2.4.3`: **FIFO Signaling Ordering Across Hops** — Các frame tín hiệu đệm qua nhiều chặng mạng được xả ra theo đúng thứ tự thời gian.
- `T2.4.4`: **Remote Hangup During Local Roaming** — Đối phương dập máy trong khi bên này đang roaming lập tức đóng cuộc gọi ngay, không chờ hết 15s.
- `T2.4.5`: **Grace Timer Expiration During Answer Pending** — Hết 15s khi đang chờ SDP answer giải phóng tài nguyên peer connection triệt để.

#### Nhóm 5: Phòng vệ Thu hồi Quyền Android 14 (`perm_denial_`)
- `T2.5.1`: **FullScreenIntent Revoked Uses Heads-Up** — Quyền mở màn hình khóa bị tắt -> tự động dựng Heads-Up Notification với độ ưu tiên `PRIORITY_MAX`.
- `T2.5.2`: **Notification Channel Blocked Handling** — Kênh thông báo bị người dùng tắt không làm sập ứng dụng (zero unhandled exceptions).
- `T2.5.3`: **PhoneCall Foreground Service Denied** — Thiếu quyền `FOREGROUND_SERVICE_PHONE_CALL` trên Android 14 được bắt và xử lý an toàn.
- `T2.5.4`: **PostNotifications Denied Keeps In-App Call** — Quyền thông báo Android 13+ bị từ chối vẫn duy trì giao diện chuông gọi khi app đang mở.
- `T2.5.5`: **Dynamic Permission Re-grant Recovery** — Cấp lại quyền toàn màn hình lập tức phục hồi hành vi hiển thị Activity toàn màn hình.

---

### 3.3 TIER 3: TỔ HỢP TÍNH NĂNG CHÉO (CROSS-FEATURE COMBINATIONS — 16 TESTS)

Kiểm chứng tương tác giữa các phân hệ khi vận hành đồng thời (Pairwise Integration):

#### Cặp 1: Outbox + Delta Sync (`cross_outbox_sync_`)
- `T3.1.1`: **Simultaneous Drain & Delta Fetch** — Xả hàng đợi outbox song song với tải gói delta bù trừ: danh sách tin nhắn không có ID trùng lặp và đơn điệu tăng.
- `T3.1.2`: **Outbox Edit on Remotely Deleted Message** — Sửa tin nhắn offline nhưng tin đó đã bị xóa trên server trong gói delta -> giải quyết xung đột thành bia mộ (tombstone).
- `T3.1.3`: **Watermark Advance on Outbox Confirmation** — Mốc watermark cục bộ tự động tịnh tiến khi các tin trong outbox được cấp `seq` chính thức.
- `T3.1.4`: **Concurrent Reaction Deltas and Local Flush** — Đồng bộ delta phản hồi cảm xúc kết hợp xả hàng đợi reaction không làm sai lệch số lượng biểu cảm.

#### Cặp 2: 2-Tick Receipts + Tái kết nối WebSocket (`cross_twotick_reconnect_`)
- `T3.2.1`: **Bulk Delivery Ack Dispatch After Delta Sync** — Ngay khi nạp xong gói delta sau offline, client tự động gửi một ack nhận hàng loạt cho `maxSeq` cao nhất.
- `T3.2.2`: **Delivery Ack Retransmission on Mid-Flight Drop** — Rớt socket đúng lúc đang gửi delivery ack -> gửi lại mốc mới nhất khi socket mở lại.
- `T3.2.3`: **Bulk Read Ack Consolidation** — Đọc nhiều tin nhắn trong lúc offline được gộp thành duy nhất một frame read ack mang `upToSeq` lớn nhất.
- `T3.2.4`: **2-Tick State Consistency Across Server Restart** — Khởi động lại máy chủ không làm mất trạng thái 2 tick xám / 2 tick xanh của các tin nhắn trước đó.

#### Cặp 3: WebRTC Roaming + STOMP Exponential Backoff (`cross_roam_backoff_`)
- `T3.3.1`: **Call Active Coordinates with Reconnect Policy** — Đứt socket khi đang gọi: `CallManager` kích hoạt vùng đệm 15s phối hợp nhịp nhàng với bộ tính trễ backoff.
- `T3.3.2`: **Reconnect Within Window Completes ICE Restart** — Socket kết nối lại ở lần thử thứ 2 (khoảng 2.4s) và hoàn tất đàm phán lại ICE ở giây thứ 5, bảo vệ cuộc gọi thông suốt.
- `T3.3.3`: **Repeated Flapping Enforces 15s Terminal Budget** — Mạng chập chờn liên tục nhiều lần: tổng thời gian offline vượt 15s sẽ giải phóng cuộc gọi, tránh treo cuộc gọi ma.
- `T3.3.4`: **Manual Call Termination Cancels Roaming State** — Người dùng chủ động bấm kết thúc cuộc gọi khi đang roaming hủy bỏ bộ đếm và giải phóng tài nguyên ngay lập tức.

#### Cặp 4: FCM Wake-up + Bắt tay STOMP (`cross_fcm_stomp_`)
- `T3.4.1`: **Doze Wake-up to STOMP Handshake** — FCM pointer đánh thức máy từ Doze mode -> nạp `PENDING_INCOMING` -> kết nối STOMP trong 15s -> nâng cấp lên `INCOMING_RINGING`.
- `T3.4.2`: **STOMP Connection Timeout Grace Expiration** — Push đánh thức máy nhưng không thể bắt tay STOMP sau 15s -> chuyển sang cuộc gọi nhỡ (`MISSED`), đóng WakeLock an toàn.
- `T3.4.3`: **Call Already Answered Elsewhere Dismissal** — Push đến máy, nhưng REST query phát hiện cuộc gọi đã được nghe trên thiết bị khác -> tự động hủy chuông reo.
- `T3.4.4`: **Silent FCM Triggers Background Sync & Ack** — Push dữ liệu tin nhắn kích hoạt ngầm Delta Sync và tự động gửi xác nhận đã nhận (`delivery.ack`).

---

### 3.4 TIER 4: KỊCH BẢN ỨNG DỤNG THỰC TẾ (REAL-WORLD WORKLOADS — 5 TESTS)

Mô phỏng 5 luồng nghiệp vụ phức hợp thực tế trong môi trường sản xuất:

- `T4.1`: **Kịch bản "Chuyến tàu điện ngầm" (Subway Commute Multi-Hop)**:
  - *Mô tả*: Người dùng đi tàu điện ngầm trải qua 4 chặng mạng: Wi-Fi ga tàu -> Sóng 4G -> Mất sóng trong hầm -> Sóng 4G ga tiếp theo.
  - *Hành vi*: Soạn 10 tin nhắn, thả 3 cảm xúc, nhận cuộc gọi WebRTC khi sóng chập chờn.
  - *Kỳ vọng nghiệm thu*: Outbox xếp hàng và xả sạch khi có mạng, Delta Sync bù đủ tin mới không thiếu không thừa, không bị duplicate ID, và cuộc gọi duy trì thành công qua chặng đổi mạng ngắn.
- `T4.2`: **Kịch bản "Thức dậy sau Deep Doze 8 Giờ" (Deep Doze Overnight Catchup)**:
  - *Mô tả*: Điện thoại để qua đêm 8 tiếng ở chế độ Deep Doze sâu. Có 50 tin nhắn gửi đến trên 5 hội thoại và 1 cuộc gọi nhỡ.
  - *Hành vi*: FCM Push pointer đánh thức CPU, hiển thị thông báo cuộc gọi; người dùng mở máy.
  - *Kỳ vọng nghiệm thu*: Batch Delta Sync lấy toàn bộ 50 tin nhắn trong 1 request duy nhất, gửi 1 bulk delivery ack cho mỗi hội thoại, Room DB cập nhật trơn tru không giật lag giao diện (zero UI flicker).
- `T4.3`: **Kịch bản "Bão Tin Nhắn Nhóm 20 Người" (High-Concurrency Group Chat Storm)**:
  - *Mô tả*: 20 thành viên cùng chat dồn dập 100 tin nhắn trong một nhóm khi người dùng đang mất mạng tạm thời với 5 tin trong outbox.
  - *Hành vi*: Người dùng có mạng lại giữa cơn bão tin nhắn.
  - *Kỳ vọng nghiệm thu*: Thuật toán backoff jitter phân tán tải, Room `@Transaction` áp dụng biến thiên nguyên tử, Outbox hoàn thành xả tin, và bộ đếm "Đã đọc bởi N thành viên" chính xác 100%.
- `T4.4`: **Kịch bản "Vừa Gọi Điện Vừa Nhắn Tin Khi Roaming" (Roaming Call with Concurrent Chat)**:
  - *Mô tả*: Người dùng đang gọi thoại WebRTC thì bước ra khỏi nhà, mạng chuyển từ Wi-Fi gia đình sang 5G viễn thông, đồng thời gửi và nhận tin nhắn văn bản.
  - *Hành vi*: Hệ thống kích hoạt đồng thời ân hạn 15s WebRTC ICE restart, STOMP reconnecting và outbox message delivery.
  - *Kỳ vọng nghiệm thu*: Cuộc gọi thoại không bị gián đoạn, âm thanh duy trì thông suốt sau ICE restart, và tin nhắn văn bản được gửi đi thành công với đầy đủ 2 tick biên nhận.
- `T4.5`: **Kịch bản "Thiết bị Yếu & Bị Thu Hồi Quyền trên Android 14" (Resource-Constrained & Permission-Denied)**:
  - *Mô tả*: Thiết bị Android 14 bộ nhớ RAM thấp, bị OS kill app khi outbox đang `IN_PROGRESS`, quyền mở màn hình khóa bị người dùng thu hồi trong Settings.
  - *Hành vi*: Khởi động lại ứng dụng khi có cuộc gọi và tin nhắn đến.
  - *Kỳ vọng nghiệm thu*: Khóa thuê 60s tự động phục hồi bản ghi outbox, cuộc gọi đến hiển thị dưới dạng Heads-Up Banner Notification với độ ưu tiên tối đa, không có ngoại lệ unhandled exception nào xảy ra.

---

## 4. MA TRẬN PHÂN BỔ & ĐỘ BAO PHỦ (TEST COVERAGE MATRIX)

| Tầng Kiểm thử (Tier) | Danh mục / Phân hệ | Số lượng ca kiểm thử (Tests) | Phương thức xác thực |
|---|---|:---:|---|
| **Tier 1** | Room v8 Migration & Offline Entities | 5 | DDL, Schema Hash & Entity Contracts |
| **Tier 1** | Outbox State Machine & UUIDv5 Idempotency | 5 | Deterministic RFC 4122 & State Transitions |
| **Tier 1** | Batch Delta Sync (Server & Client) | 5 | Wire Schemas & Sequence Math |
| **Tier 1** | 2-Tick Delivery & Read Receipts | 5 | STOMP Wire Protocols & UI Rules |
| **Tier 1** | WebSocket Backoff with Jitter | 5 | Probability Bounds & Exponential Math |
| **Tier 1** | WebRTC Roaming 15s Grace Period | 5 | State Machine & Timer Bounds |
| **Tier 1** | FCM Push Pointer & Android 14 Intent | 5 | Byte Budget & Intent Fallback |
| **Tier 2** | Lease Lock Timeout at 60s | 5 | Boundary Threshold & Expiration |
| **Tier 2** | Malformed & Boundary Watermarks | 5 | Error Codes & Edge Sequences |
| **Tier 2** | Toggle Inversion Under Concurrency | 5 | Mathematical Idempotency Invariant |
| **Tier 2** | Network Switch During ICE Restart | 5 | State Recovery & Multi-Hop FIFO |
| **Tier 2** | Permission Denial Fallback | 5 | Android 14 Graceful Degradation |
| **Tier 3** | Outbox + Delta Sync Combinations | 4 | Conflict Resolution & Monotonic Seq |
| **Tier 3** | 2-Tick + Reconnection Combinations | 4 | Bulk Acks & State Preservation |
| **Tier 3** | Roaming + STOMP Backoff Combinations | 4 | Coordinated Grace & Flap Suppression |
| **Tier 3** | FCM Wake-up + STOMP Handshake Combinations | 4 | Pre-hydration & Expiration Cleanup |
| **Tier 4** | Real-World Application Scenarios | 5 | End-to-End Multi-Feature Workloads |
| **TỔNG CỘNG** | **Toàn bộ 4 Tầng Kiểm thử P0** | **81** | **100% Khảo sát Đặc tả Kỹ thuật P0** |

---

## 5. HẠ TẦNG THỰC THI & CHẠY TỰ ĐỘNG (EXECUTION INFRASTRUCTURE)

Bộ kiểm thử được thiết kế để chạy trơn tru thông qua hai cơ chế thực thi:

### 5.1 Thực thi qua Maven Test Suite (Spring Boot & CI/CD Pipeline)
Toàn bộ 81 bài kiểm thử được đóng gói thành các lớp kiểm thử JUnit 5 tiêu chuẩn trong `server/src/test/java/com/nexachat/e2e/`:
- `Tier1FeatureCoverageTest.java` (35 tests)
- `Tier2BoundaryCornerCasesTest.java` (25 tests)
- `Tier3CrossFeatureCombinationsTest.java` (16 tests)
- `Tier4RealWorldScenariosTest.java` (5 tests)
- `NexaChatP0E2ETestSuite.java` (Suite tổng hợp 81 tests)

**Lệnh chạy kiểm thử Maven**:
```powershell
cd server
mvn test -Dtest=NexaChatP0E2ETestSuite
```

### 5.2 Thực thi qua Trình chạy Độc lập CLI Thuần Java 21 (Standalone CLI Runner)
File thực thi Java 21 độc lập `tools/e2e/P0E2ETestRunner.java` cho phép chạy kiểm thử trực tiếp trên dòng lệnh mà không yêu cầu build công kềnh:
```powershell
java tools/e2e/P0E2ETestRunner.java
```

Trình chạy cung cấp báo cáo ANSI màu trực quan, tính toán thời gian thực thi mili-giây, thống kê tỷ lệ đạt/hỏng của từng tầng, và trả về mã thoát 0 khi tất cả kiểm thử đều xanh.
