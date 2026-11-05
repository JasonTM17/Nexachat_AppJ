#!/usr/bin/env python3
"""Seed 35 realistic Vietnamese messages from Minh Khoa and Bao Chau into Nhóm Nghiên Cứu.
Total messages will reach >= 40, and Lan Anh's unread count will be >= 20.
"""

import time
import uuid
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from e2e_common import Client, login

CONV_ID = "c6a9d4d0-bd62-4e3f-8a49-fbe582da6fd1"

MESSAGES = [
    ("+84900000202", "Chào mọi người! Chúng ta bắt đầu phiên họp trực tuyến tổng kết tiến độ tuần này nhé."),
    ("+84900000203", "Chào Khoa! Mình vừa tổng hợp xong báo cáo kiểm thử tính năng của tuần rồi."),
    ("+84900000202", "Cảm ơn Châu. Về phần cơ sở dữ liệu, nhóm đã tối ưu xong các chỉ mục truy vấn chưa?"),
    ("+84900000203", "Đã tối ưu hoàn chỉnh rồi nhé. Thời gian phản hồi API trung bình hiện chỉ còn dưới 40ms."),
    ("+84900000202", "Rất tốt! Còn module tóm tắt cuộc trò chuyện bằng AI đã sẵn sàng chưa?"),
    ("+84900000203", "Phía backend đã hoàn thành cả hai engine: Local heuristic và generative LLM."),
    ("+84900000202", "Quyết định 1: Nhóm thống nhất mặc định sử dụng Local engine để bảo vệ quyền riêng tư người dùng."),
    ("+84900000203", "Hoàn toàn đồng ý. Dịch vụ AI bên ngoài chỉ kích hoạt khi người dùng tự nguyện bật trong Cài đặt."),
    ("+84900000202", "Mọi tin nhắn gửi ra ngoài đều phải được ẩn danh hóa và khử thông tin nhạy cảm trước."),
    ("+84900000203", "Quyết định 2: Thời gian lưu bộ nhớ đệm (cache) phía client là 5 phút, server là 24 giờ."),
    ("+84900000202", "Bây giờ chúng ta chốt danh sách việc cần làm cho đợt phát hành sắp tới."),
    ("+84900000202", "Việc 1: Minh Khoa hoàn thiện tài liệu hướng dẫn kỹ thuật API trước thứ Sáu tới."),
    ("+84900000203", "Việc 2: Bảo Châu thực hiện kiểm thử tự động giao diện và kiểm tra độ tương phản màu sắc."),
    ("+84900000202", "Việc 3: Lan Anh hoàn thiện trải nghiệm Material Design 3 và hỗ trợ TalkBack trợ năng."),
    ("+84900000203", "Châu có câu hỏi: Kích thước phông chữ tối đa trên Android app hỗ trợ đến mức nào?"),
    ("+84900000202", "Ứng dụng hỗ trợ tốt đến mức font scale 2.0 (200%), giao diện tự động bật cuộn khi tràn."),
    ("+84900000203", "Câu hỏi thứ hai: Khi người dùng xoay ngang màn hình thì bottom sheet có giữ nguyên trạng thái không?"),
    ("+84900000202", "Có nhé, toàn bộ trạng thái ID được lưu qua rememberSaveable nên không bị đóng khi xoay."),
    ("+84900000203", "Trường hợp cuộc trò chuyện có dưới 5 tin nhắn thì giao diện hiển thị thế nào?"),
    ("+84900000202", "Giao diện sẽ hiển thị thông báo thân thiện: Cuộc trò chuyện chưa đủ tin nhắn để tóm tắt."),
    ("+84900000203", "Khi người dùng bấm vào một trích dẫn trong bản tóm tắt thì điều gì xảy ra?"),
    ("+84900000202", "Bottom sheet sẽ đóng lại và khung chat tự động cuộn đến tin nhắn gốc kèm hiệu ứng nhấp nháy."),
    ("+84900000203", "Tính năng chia sẻ bản tóm tắt vào khung soạn thảo hoạt động rất mượt mà."),
    ("+84900000202", "Đúng vậy, nội dung được điền sẵn vào ô nhập liệu để người dùng chủ động xem lại trước khi gửi."),
    ("+84900000203", "Bảo Châu đã thử nghiệm tính năng sao chép vào bộ nhớ tạm (clipboard), hoạt động hoàn hảo."),
    ("+84900000202", "Chế độ nền tối (Dark Theme) đã được kiểm tra trên toàn bộ các thành phần chưa?"),
    ("+84900000203", "Đã kiểm tra kỹ trên thanh điều hướng, bottom sheet, banner và các chip trích dẫn rồi."),
    ("+84900000202", "Ngôn ngữ tiếng Anh và tiếng Việt có đầy đủ 100% tài nguyên chuỗi chưa?"),
    ("+84900000203", "Unit test StringParityTest đã xác nhận khớp 40/40 chuỗi tóm tắt mới."),
    ("+84900000202", "Các chip phạm vi tóm tắt gồm những mục nào?"),
    ("+84900000203", "Gồm 3 mục: Tin chưa đọc, 50 tin gần nhất, và Hôm nay."),
    ("+84900000202", "Huy hiệu hiển thị nguồn gốc tóm tắt gồm hai nhãn: 'Cục bộ' và 'AI'."),
    ("+84900000203", "Tất cả các thành phần đều tuân thủ chặt chẽ tài liệu hướng dẫn thiết kế."),
    ("+84900000202", "Cảm ơn Bảo Châu và cả nhóm! Chúng ta tiếp tục hoàn thiện các bước cuối cùng nhé."),
    ("+84900000203", "Chúc mọi người một buổi tối làm việc hiệu quả và thành công!")
]

def main():
    client = Client()
    print("Signing in Minh Khoa and Bao Chau...")
    s_khoa = login(client, "+84900000202")
    s_chau = login(client, "+84900000203")
    t_khoa = s_khoa["accessToken"]
    t_chau = s_chau["accessToken"]

    print(f"Seeding {len(MESSAGES)} messages into conversation {CONV_ID}...")
    for idx, (phone, content) in enumerate(MESSAGES):
        token = t_khoa if phone == "+84900000202" else t_chau
        sender_name = "Khoa" if phone == "+84900000202" else "Chau"
        client_msg_id = str(uuid.uuid4())
        resp = client.post(
            f"/api/v1/conversations/{CONV_ID}/messages",
            body={"type": "TEXT", "content": content, "clientMessageId": client_msg_id},
            token=token
        )
        if resp.status not in (200, 201):
            print(f"[{idx+1}/{len(MESSAGES)}] Error sending message as {sender_name}: {resp.status} {resp.text}")
        else:
            if (idx + 1) % 5 == 0 or idx == len(MESSAGES) - 1:
                print(f"[{idx+1}/{len(MESSAGES)}] Sent message as {sender_name}: {content[:40]}...")
        time.sleep(0.08)

    print("Seeding complete!")

if __name__ == "__main__":
    main()
