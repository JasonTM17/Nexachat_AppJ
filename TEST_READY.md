# BÁO CÁO SẴN SÀNG KIỂM THỬ E2E (TEST READY SPECIFICATION)
## HỆ THỐNG TIN NHẮN & CUỘC GỌI BẢO MẬT THỜI GIAN THỰC NEXACHAT (P0 PRODUCTION ENGINEERING)

- **Dự án**: NexaChat
- **Kho lưu trữ**: Thư mục gốc dự án (`.`)
- **Ràng buộc công nghệ**: 100% Pure Java 21 LTS (0 file Kotlin `.kt`/`.kts`), 0 leaked secrets, 0 broken markdown links.
- **Tài liệu tham chiếu cơ sở**: [README.md](README.md), [TECHNICAL_ROADMAP.md](TECHNICAL_ROADMAP.md), [TEST_INFRA.md](TEST_INFRA.md).
- **Mã hiệu tài liệu**: `NEXA-TEST-READY-2026-V1`
- **Ngày ban hành**: 08/11/2026
- **Trạng thái**: Đã kiểm chứng (Verified)

---

## 1. TỔNG QUAN HẠ TẦNG & TRẠNG THÁI KIỂM THỬ (OVERVIEW & STATUS)

Hạ tầng kiểm thử toàn diện E2E cho giai đoạn P0 (Giai đoạn 1 - Hạ tầng cốt lõi & Độ ổn định Production) đã được thiết kế, cài đặt và kiểm chứng độc lập hoàn tất. Toàn bộ 81 ca kiểm thử hộp mờ (opaque-box requirement-driven tests) phân bổ trên 4 tầng kiểm thử đều đạt tỷ lệ vượt qua **100% (81/81 PASS, 0 Failures, 0 Errors, 0 Skipped)**.

Mọi ca kiểm thử đều tuân thủ nghiêm ngặt nguyên tắc:
1. **100% Pure Java 21 LTS**: Tuyệt đối không sử dụng bất kỳ file Kotlin (.kt / .kts) nào.
2. **Opaque-Box Testing**: Kiểm thử độc lập với tên lớp nội bộ, bám sát các hợp đồng giao tiếp, lược đồ dữ liệu, tiêu chuẩn RFC và máy trạng thái trong [TECHNICAL_ROADMAP.md](TECHNICAL_ROADMAP.md).
3. **0 Leaked Secrets & 0 Broken Links**: Độc lập trong môi trường CI/CD không có secrets sống và toàn vẹn liên kết tài liệu.

---

## 2. HƯỚNG DẪN & CÂU LỆNH CHẠY KIỂM THỬ (TEST RUNNER COMMANDS & INSTRUCTIONS)

Đội ngũ kỹ thuật và hệ thống CI/CD có thể thực thi bộ kiểm thử E2E thông qua hai phương thức linh hoạt dưới đây:

### 2.1 Phương thức 1: Trình chạy Độc lập CLI Thuần Java 21 (Khuyến nghị cho Developer & Fast Verification)
Trình chạy dòng lệnh độc lập `tools/e2e/P0E2ETestRunner.java` thực thi trực tiếp bằng Java 21 LTS runtime mà không cần cài đặt thêm thư viện ngoài (zero external dependencies), cho kết quả tức thì trong dưới 100 mili-giây:

```powershell
# Thực thi trình chạy kiểm thử Java 21 E2E từ thư mục gốc
java tools/e2e/P0E2ETestRunner.java
```

*Đầu ra quan sát được*:
```
==========================================================================
   NEXACHAT P0 PRODUCTION ENGINEERING — OPAQUE-BOX E2E TEST RUNNER        
   100% Pure Java 21 LTS | 81 Verification Tests across 4 Tiers           
==========================================================================

--- Tier 1: Core Feature Coverage (35 Tests) ---
  [PASS] T1.1.1     : Room v8 DDL schema integrity
  [PASS] T1.1.2     : UserEntity offline schema fields
  [PASS] T1.1.3     : CallLogEntity persistence schema fields
  ...
==========================================================================
                     E2E TEST EXECUTION SUMMARY                           
==========================================================================
 Test Tier                                |   Passed |   Failed |    Total
--------------------------------------------------------------------------
 Tier 1: Core Feature Coverage            |       35 |        0 |       35
 Tier 2: Boundary & Corner Cases          |       25 |        0 |       25
 Tier 3: Cross-Feature Combinations       |       16 |        0 |       16
 Tier 4: Real-World Scenarios             |        5 |        0 |        5
--------------------------------------------------------------------------
 TOTAL ACROSS ALL TIERS                   |       81 |        0 |       81
==========================================================================
 Execution Time: 59 ms | Result: ALL PASS (100%)
```

### 2.2 Phương thức 2: Chạy qua Maven Test Suite (Tích hợp CI/CD & Spring Boot)
Chạy toàn bộ hoặc từng phần bộ kiểm thử E2E thông qua Apache Maven trong thư mục `server`:

```powershell
# Chuyển vào thư mục backend server
cd server

# Chạy toàn bộ 81 ca kiểm thử E2E
mvn test "-Dtest=Tier1FeatureCoverageTest,Tier2BoundaryCornerCasesTest,Tier3CrossFeatureCombinationsTest,Tier4RealWorldScenariosTest"

# Hoặc chạy kiểm thử đơn lẻ cho từng Tier mong muốn
mvn test "-Dtest=Tier1FeatureCoverageTest"
mvn test "-Dtest=Tier2BoundaryCornerCasesTest"
mvn test "-Dtest=Tier3CrossFeatureCombinationsTest"
mvn test "-Dtest=Tier4RealWorldScenariosTest"
```

*Kết quả thực thi Maven*: `BUILD SUCCESS` (Tests run: 81, Failures: 0, Errors: 0, Skipped: 0).

---

## 3. BẢNG TỔNG HỢP ĐỘ BAO PHỦ (COMPLETE COVERAGE SUMMARY TABLE)

| Tầng Kiểm Thử (Tier) | Phân Loại & Phạm Vi | Số Ca Kiểm Thử (Count) | Tỷ Lệ Đạt (Pass Rate) | Trạng Thái (Status) |
|---|---|:---:|:---:|:---:|
| **Tier 1** | **Core Feature Coverage** (7 phân hệ tính năng P0: Room v8, Outbox, Delta Sync, 2-Tick, Backoff, Roaming, FCM) | 35 | 35/35 (100%) | **PASS** |
| **Tier 2** | **Boundary & Corner Cases** (5 nhóm kiểm thử biên: Lease 60s, Malformed Watermark, Inversion, ICE Switch, Android 14 Perms) | 25 | 25/25 (100%) | **PASS** |
| **Tier 3** | **Cross-Feature Combinations** (4 cặp tích hợp chéo: Outbox+Sync, 2-Tick+Reconnect, Roam+Backoff, FCM+STOMP) | 16 | 16/16 (100%) | **PASS** |
| **Tier 4** | **Real-World Application Scenarios** (5 kịch bản thực tế: Subway Commute, Deep Doze, Group Storm, Roaming Call, Constrained Android 14) | 5 | 5/5 (100%) | **PASS** |
| **TỔNG CỘNG** | **Toàn Bộ 4 Tầng Kiểm Thử P0** | **81** | **81/81 (100%)** | **ALL GREEN** |

---

## 4. BẢNG CHECKLIST TÍNH NĂNG CHI TIẾT QUA 4 TẦNG (FEATURE CHECKLIST ACROSS ALL 4 TIERS)

### 4.1 Tier 1: Phủ Sâu 7 Tính Năng P0 (35/35 Checked)
- [x] `T1.1.1` [F1: Room v8] Câu lệnh DDL di chuyển `MIGRATION_7_8` tạo bảng `users`, `call_logs` và mở rộng bảng `outbox`.
- [x] `T1.1.2` [F1: Room v8] Cấu trúc thực thể `UserEntity` hỗ trợ lưu danh bạ và tra cứu khi offline.
- [x] `T1.1.3` [F1: Room v8] Cấu trúc thực thể `CallLogEntity` hỗ trợ lưu lịch sử cuộc gọi ngoại tuyến.
- [x] `T1.1.4` [F1: Room v8] Giá trị mặc định tương thích ngược của 4 cột mở rộng trong `OutboxEntity`.
- [x] `T1.1.5` [F1: Room v8] Định dạng và tính toàn vẹn của mã băm nhận diện lược đồ Room v8.
- [x] `T1.2.1` [F2: Outbox] Luồng chuyển dịch trạng thái thuận: `PENDING` -> `IN_PROGRESS` -> `SENT`.
- [x] `T1.2.2` [F2: Outbox] Luồng chuyển dịch khi gặp lỗi nghiệp vụ cố định 403: `PENDING` -> `IN_PROGRESS` -> `FAILED`.
- [x] `T1.2.3` [F2: Outbox] Tập hợp bộ nhớ in-flight ngăn chặn phát trùng lặp tin nhắn trong cùng tiến trình.
- [x] `T1.2.4` [F2: Outbox] Thuật toán sinh khóa lũy thỏa toán học deterministic `UUIDv5(userId + ":" + messageId + ":" + emoji)`.
- [x] `T1.2.5` [F2: Outbox] Cơ chế hành động tường minh `ADD` / `REMOVE` triệt tiêu lỗi đảo ngược cảm xúc (Toggle Inversion).
- [x] `T1.3.1` [F3: Delta Sync] Đặc tả hợp đồng lược đồ yêu cầu đồng bộ `POST /api/v1/sync`.
- [x] `T1.3.2` [F3: Delta Sync] Đặc tả hợp đồng lược đồ gói dữ liệu biến thiên (Delta Bundle Response).
- [x] `T1.3.3` [F3: Delta Sync] Thuật toán lọc đơn điệu strictly greater than `lastSeq`.
- [x] `T1.3.4` [F3: Delta Sync] Phân loại chính xác các tin nhắn bị chỉnh sửa và bia mộ xóa tin trong gói delta.
- [x] `T1.3.5` [F3: Delta Sync] Khả năng gom cụm mốc watermark của 20+ hội thoại trong 1 request duy nhất.
- [x] `T1.4.1` [F4: 2-Tick] Chuẩn hóa 5 trạng thái vòng đời tin nhắn: `SENDING`, `SENT`, `DELIVERED`, `READ`, `FAILED`.
- [x] `T1.4.2` [F4: 2-Tick] Hợp đồng endpoint `/app/chat.delivery.ack` cập nhật mốc `last_delivered_seq` đơn điệu tăng.
- [x] `T1.4.3` [F4: 2-Tick] Hợp đồng endpoint `/app/chat.read.ack` cập nhật mốc `last_read_seq` đơn điệu tăng.
- [x] `T1.4.4` [F4: 2-Tick] Quy tắc phân giải dấu tích biên nhận trong hội thoại 1-1 (1 tick, 2 tick xám, 2 tick xanh).
- [x] `T1.4.5` [F4: 2-Tick] Quy tắc phân giải biên nhận trong hội thoại nhóm (>=1 nhận -> 2 tick xám; toàn bộ đọc -> 2 tick xanh).
- [x] `T1.5.1` [F5: Backoff] Dãy số trễ số mũ cơ sở: 1s, 2s, 4s, 8s, 16s (trần 16,000ms).
- [x] `T1.5.2` [F5: Backoff] Giới hạn biến thiên ngẫu nhiên có kiểm soát $\pm 20\%$ jitter $[0.8 \times delay, 1.2 \times delay]$.
- [x] `T1.5.3` [F5: Backoff] Ngưỡng dừng 5 lần thử thất bại liên tiếp chuyển sang trạng thái `PARKED`.
- [x] `T1.5.4` [F5: Backoff] Kết nối thành công lập tức thiết lập lại bộ đếm số lần thử về 0.
- [x] `T1.5.5` [F5: Backoff] Độ phân tán ngẫu nhiên triệt tiêu hiện tượng thundering herd của 100 client đồng thời.
- [x] `T1.6.1` [F6: WebRTC Roam] Chuyển đổi trạng thái sang `CallState.RECONNECTING_NETWORK` khi mất socket lúc đang gọi.
- [x] `T1.6.2` [F6: WebRTC Roam] Duy trì luồng âm thanh và micrô không bị ngắt trong vùng đệm 15 giây.
- [x] `T1.6.3` [F6: WebRTC Roam] Hàng đợi FIFO đệm các frame tín hiệu signaling khi đang mất mạng.
- [x] `T1.6.4` [F6: WebRTC Roam] Tự động kích hoạt `restartIce()` với cờ `iceRestart: true` khi có lại mạng.
- [x] `T1.6.5` [F6: WebRTC Roam] Hết hạn 15 giây không kết nối lại giải phóng tài nguyên và kết thúc cuộc gọi với `NETWORK_FAILURE`.
- [x] `T1.7.1` [F7: FCM Push] Giới hạn kích thước payload notification pointer luôn dưới 1KB (mục tiêu < 500B).
- [x] `T1.7.2` [F7: FCM Push] Lược đồ dữ liệu pointer thuần túy, tuyệt đối không chứa SDP Offer thô.
- [x] `T1.7.3` [F7: FCM Push] Hợp đồng endpoint độc lập tải nhanh SDP Offer: `GET /api/v1/calls/{id}/offer`.
- [x] `T1.7.4` [F7: FCM Push] Nạp trước trạng thái phiên `CallState.PENDING_INCOMING`, chống lỗi tự hủy màn hình chuông trong 0ms.
- [x] `T1.7.5` [F7: FCM Push] Cơ chế tự động hạ cấp sang Heads-Up Banner Notification khi quyền `canUseFullScreenIntent` bị tắt.

### 4.2 Tier 2: Trường Hợp Biên & Phòng Vệ Sự Cố (25/25 Checked)
- [x] `T2.1.1` [B1: Lease Lock] Khóa thuê còn hạn (<60s) từ chối quyền chiếm dụng của worker khác.
- [x] `T2.1.2` [B1: Lease Lock] Khóa thuê quá hạn (>=60s) cho phép worker tiếp theo tiếp quản an toàn.
- [x] `T2.1.3` [B1: Lease Lock] Kiểm chứng mốc thời gian biên chính xác tại $t = 60,000\text{ ms}$.
- [x] `T2.1.4` [B1: Lease Lock] Khôi phục bản ghi mồ côi khi tiến trình cũ bị hệ điều hành tiêu diệt đột ngột (LMK).
- [x] `T2.1.5` [B1: Lease Lock] Phòng vệ hiện tượng trôi ngược đồng hồ hệ thống không gây treo khóa vĩnh cửu.
- [x] `T2.2.1` [B2: Watermarks] Danh sách mốc watermark rỗng trả về mã phản hồi HTTP 400 Bad Request.
- [x] `T2.2.2` [B2: Watermarks] Bản ghi watermark mang `conversationId` null bị từ chối hợp lệ.
- [x] `T2.2.3` [B2: Watermarks] Mốc tuần tự âm (`lastSeq < 0`) được chuẩn hóa an toàn về 0.
- [x] `T2.2.4` [B2: Watermarks] Hội thoại không tồn tại trả về danh sách biến thiên rỗng, không gây lỗi máy chủ 500.
- [x] `T2.2.5` [B2: Watermarks] Mốc watermark vượt quá sequence tối đa của máy chủ trả về `hasMore = false` và 0 tin mới.
- [x] `T2.3.1` [B3: Toggle Inversion] Nhiều yêu cầu `ADD` đồng thời cùng khóa UUIDv5 đạt tính lũy thỏa tuyệt đối.
- [x] `T2.3.2` [B3: Toggle Inversion] Chuỗi thao tác dồn dập `ADD -> REMOVE -> ADD` hội tụ chuẩn xác về trạng thái `ADDED`.
- [x] `T2.3.3` [B3: Toggle Inversion] Gửi lại yêu cầu `ADD` khi máy chủ đã ghi nhận trả về kết quả no-op thành công.
- [x] `T2.3.4` [B3: Toggle Inversion] Gửi lại yêu cầu `REMOVE` trên cảm xúc đã xóa trả về no-op thành công.
- [x] `T2.3.5` [B3: Toggle Inversion] Kiểm thử căng thẳng 20 luồng đồng thời hội tụ kết quả đơn điệu theo phiên bản lớn nhất.
- [x] `T2.4.1` [B4: ICE Roaming] Đổi mạng chớp nhoáng 2 chặng (Wi-Fi -> 4G -> Wi-Fi) trong 15s duy trì cuộc gọi thông suốt.
- [x] `T2.4.2` [B4: ICE Roaming] Đàm phán lại ICE thất bại lần đầu tự động thử lại nếu quỹ thời gian 15s vẫn còn.
- [x] `T2.4.3` [B4: ICE Roaming] Bảo toàn thứ tự xuất chuỗi FIFO của các frame tín hiệu signaling qua nhiều chặng mạng.
- [x] `T2.4.4` [B4: ICE Roaming] Nhận được tín hiệu dập máy từ đối phương lập tức giải phóng cuộc gọi, không chờ hết 15s.
- [x] `T2.4.5` [B4: ICE Roaming] Hết hạn 15s khi đang chờ câu trả lời SDP thu hồi kết nối an toàn không rò rỉ bộ nhớ.
- [x] `T2.5.1` [B5: Permissions] Thu hồi quyền full-screen intent kích hoạt thông báo nổi Heads-Up với `PRIORITY_MAX`.
- [x] `T2.5.2` [B5: Permissions] Kênh thông báo bị hạ cấp tầm quan trọng được xử lý trơn tru không ném biệt lệ.
- [x] `T2.5.3` [B5: Permissions] Thiếu quyền `FOREGROUND_SERVICE_PHONE_CALL` trên Android 14 tự động kích hoạt chế độ audio dự phòng.
- [x] `T2.5.4` [B5: Permissions] Quyền thông báo Android 13+ bị từ chối vẫn duy trì hộp thoại nhận cuộc gọi khi app mở.
- [x] `T2.5.5` [B5: Permissions] Cấp lại quyền full-screen intent lập tức khôi phục hành vi mở Activity toàn màn hình.

### 4.3 Tier 3: Tổ Hợp Tính Năng Chéo (16/16 Checked)
- [x] `T3.1.1` [C1: Outbox+Sync] Xả hàng đợi outbox song song với nạp delta đảm bảo 0 tin nhắn trùng lặp ID.
- [x] `T3.1.2` [C1: Outbox+Sync] Tin nhắn sửa offline nhưng bị xóa từ xa trên server trong gói delta giải quyết thành tombstone.
- [x] `T3.1.3` [C1: Outbox+Sync] Mốc watermark cục bộ tịnh tiến theo các sequence được server xác nhận qua outbox.
- [x] `T3.1.4` [C1: Outbox+Sync] Đồng bộ delta cảm xúc kết hợp xả hàng đợi reaction hội tụ chuẩn xác số lượng biểu cảm.
- [x] `T3.2.1` [C2: 2-Tick+Reconnect] Tự động phát một ack nhận hàng loạt cho sequence lớn nhất ngay sau khi nạp xong Delta Sync.
- [x] `T3.2.2` [C2: 2-Tick+Reconnect] Gửi lại biên nhận giao hàng bị mất do đứt socket khi socket STOMP mở lại.
- [x] `T3.2.3` [C2: 2-Tick+Reconnect] Nhiều tin nhắn đã đọc khi offline được hợp nhất thành một frame read ack duy nhất mang `upToSeq` cực đại.
- [x] `T3.2.4` [C2: 2-Tick+Reconnect] Trạng thái dấu tích 2-tick được duy trì bền vững qua chu kỳ khởi động lại máy chủ.
- [x] `T3.3.1` [C3: Roam+Backoff] Cuộc gọi đang active phối hợp đồng bộ với chính sách backoff số mũ khi đứt kết nối.
- [x] `T3.3.2` [C3: Roam+Backoff] Tái kết nối thành công trong cửa sổ ân hạn hoàn thành đàm phán lại ICE và giữ nguyên cuộc gọi.
- [x] `T3.3.3` [C3: Roam+Backoff] Hiện tượng mạng chập chờn liên tục tuân thủ tổng ngân sách ngắt kết nối tối đa 15 giây.
- [x] `T3.3.4` [C3: Roam+Backoff] Người dùng bấm kết thúc cuộc gọi khi đang roaming hủy bỏ ngay bộ đếm backoff và giải phóng phiên.
- [x] `T3.4.1` [C4: FCM+STOMP] Đánh thức Doze mode nâng cấp trạng thái từ `PENDING_INCOMING` lên `INCOMING_RINGING` qua STOMP.
- [x] `T3.4.2` [C4: FCM+STOMP] Hết hạn kết nối STOMP sau khi nhận push đánh thức chuyển sang cuộc gọi nhỡ `MISSED` an toàn.
- [x] `T3.4.3` [C4: FCM+STOMP] Cuộc gọi đã được trả lời trên thiết bị khác lập tức hủy chuông reo trên máy này.
- [x] `T3.4.4` [C4: FCM+STOMP] Push dữ liệu ngầm kích hoạt Delta Sync và tự động gửi biên nhận giao hàng trong nền.

### 4.4 Tier 4: Kịch Bản Ứng Dụng Thực Tế (5/5 Checked)
- [x] `T4.1` [S1: Subway Commute] Người dùng đi tàu điện ngầm qua 4 chặng mạng: Wi-Fi -> 4G -> Hầm mất sóng -> 4G; xếp hàng 10 tin nhắn, xả sạch và đồng bộ trơn tru không trùng lặp ID.
- [x] `T4.2` [S2: Deep Doze] Điện thoại ngâm 8 tiếng trong Deep Doze; FCM pointer đánh thức máy, hiển thị chuông reo; sau khi từ chối, tải trọn vẹn 50 tin nhắn trên 5 hội thoại trong 1 request duy nhất.
- [x] `T4.3` [S3: Group Storm] Bão tin nhắn nhóm 20 người với 100 tin nhắn phát sinh trong lúc offline; Room `@Transaction` cập nhật nguyên tử, tính toán số người đã đọc chính xác 100%.
- [x] `T4.4` [S4: Roaming Call with Chat] Vừa gọi điện WebRTC vừa nhắn tin khi bước ra khỏi nhà đổi từ Wi-Fi sang 5G; ICE restart thành công trong 15s và tin nhắn được gửi đi kèm đầy đủ 2 tick.
- [x] `T4.5` [S5: Resource Constrained & Permission Denied] Thiết bị Android 14 RAM yếu bị kill app khi outbox đang `IN_PROGRESS` và bị thu hồi quyền full-screen intent; phục hồi khóa thuê sau 60s và hiển thị cuộc gọi dạng Heads-Up Banner an toàn.

---

## 5. KẾT LUẬN & CHỮ KÝ BÀN GIAO (CONCLUSION)

Bộ kiểm thử E2E của NexaChat P0 đã sẵn sàng 100% để phục vụ như **Cổng Kiểm Duyệt Chất Lượng (Quality Gate)** cho toàn bộ các mốc phát triển M1 -> M5. Mọi thay đổi mã nguồn trong các milestone tiếp theo đều có thể tự động chạy bộ test này để chứng minh tính tương thích ngược, độ tin cậy và sự sẵn sàng cho môi trường production.
