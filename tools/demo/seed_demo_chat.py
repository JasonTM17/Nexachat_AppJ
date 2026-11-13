#!/usr/bin/env python3
import argparse
import json
import urllib.request
import urllib.error
import uuid
import time
import sys

BASE_URL = "http://localhost:8080/api/v1"

def request_json(url, method="GET", data=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    body = json.dumps(data).encode("utf-8") if data is not None else None

    for attempt in range(5):
        req = urllib.request.Request(url, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req) as resp:
                content = resp.read().decode("utf-8")
                return json.loads(content) if content else {}
        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8")
            if e.code == 429:
                wait_sec = 6
                try:
                    err_json = json.loads(err_body)
                    wait_sec = err_json.get("details", {}).get("retryAfterSeconds", 5) + 1
                except Exception:
                    pass
                print(f"Rate limited on {url}, waiting {wait_sec}s...")
                time.sleep(wait_sec)
                continue
            # Let caller handle 409 or other expected codes
            raise e

def auth_user(phone, password, display_name):
    # Try register first
    try:
        res = request_json(
            f"{BASE_URL}/auth/register",
            method="POST",
            data={"phone": phone, "password": password, "displayName": display_name}
        )
        return res["accessToken"], res["user"]
    except urllib.error.HTTPError as e:
        if e.code in (400, 409):
            # Already exists, login
            res = request_json(
                f"{BASE_URL}/auth/login",
                method="POST",
                data={"phone": phone, "password": password}
            )
            # Fetch me
            me = request_json(f"{BASE_URL}/users/me", method="GET", token=res["accessToken"])
            return res["accessToken"], me
        raise

def add_contact(token, phone, alias=None):
    try:
        data = {"phone": phone}
        if alias:
            data["alias"] = alias
        request_json(f"{BASE_URL}/contacts", method="POST", data=data, token=token)
    except urllib.error.HTTPError as e:
        if e.code in (400, 409):
            pass # Already contact
        else:
            print(f"Add contact {phone} note: {e}")

def create_or_get_direct(token, peer_id):
    try:
        conv = request_json(
            f"{BASE_URL}/conversations/direct",
            method="POST",
            data={"userId": peer_id},
            token=token
        )
        return conv["id"]
    except urllib.error.HTTPError as e:
        # A failed create may mean the idempotent direct already exists. Match
        # the requested peer instead of returning an unrelated direct chat.
        inbox = request_json(f"{BASE_URL}/conversations", method="GET", token=token)
        items = inbox if isinstance(inbox, list) else inbox.get("items", [])
        for item in items:
            if item.get("type") == "DIRECT" and any(
                member.get("userId") == peer_id for member in item.get("members", [])
            ):
                return item["id"]
        raise

def create_or_get_group(token, title, member_ids):
    inbox = request_json(f"{BASE_URL}/conversations", method="GET", token=token)
    items = inbox if isinstance(inbox, list) else inbox.get("items", [])
    for item in items:
        if item.get("type") == "GROUP" and item.get("title") == title:
            return item["id"]
    try:
        conv = request_json(
            f"{BASE_URL}/conversations/group",
            method="POST",
            data={"title": title, "memberIds": member_ids},
            token=token
        )
        return conv["id"]
    except urllib.error.HTTPError:
        inbox = request_json(f"{BASE_URL}/conversations", method="GET", token=token)
        items = inbox if isinstance(inbox, list) else inbox.get("items", [])
        for item in items:
            if item.get("type") == "GROUP" and item.get("title") == title:
                return item["id"]
        raise

def send_seed_msg(token, conv_id, seed_key, content, reply_to_id=None):
    client_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"nexachat-demo:{conv_id}:{seed_key}"))
    payload = {"type": "TEXT", "content": content, "clientMessageId": client_id}
    if reply_to_id:
        payload["replyToId"] = reply_to_id
    return request_json(f"{BASE_URL}/conversations/{conv_id}/messages", method="POST", data=payload, token=token)

def edit_msg(token, conv_id, msg_id, new_content):
    try:
        return request_json(
            f"{BASE_URL}/conversations/{conv_id}/messages/{msg_id}",
            method="PATCH",
            data={"content": new_content},
            token=token
        )
    except Exception as e:
        print(f"Edit msg error: {e}")
        return None

def delete_msg(token, conv_id, msg_id):
    try:
        return request_json(
            f"{BASE_URL}/conversations/{conv_id}/messages/{msg_id}",
            method="DELETE",
            token=token
        )
    except Exception as e:
        print(f"Delete msg error: {e}")
        return None

def pin_msg(token, conv_id, msg_id):
    try:
        request_json(f"{BASE_URL}/conversations/{conv_id}/pins", method="POST", data={"messageId": msg_id}, token=token)
    except Exception as e:
        print(f"Pin error: {e}")

def react_msg(token, conv_id, msg_id, emoji):
    page = request_json(
        f"{BASE_URL}/conversations/{conv_id}/messages?limit=100",
        method="GET",
        token=token,
    )
    items = page if isinstance(page, list) else page.get("items", [])
    message = next((item for item in items if item.get("id") == msg_id), None)
    if message is None:
        raise RuntimeError("message not found in recent history; will not toggle blindly")
    if any(
        reaction.get("emoji") == emoji and reaction.get("byMe")
        for reaction in message.get("reactions", [])
    ):
        return
    request_json(f"{BASE_URL}/messages/{msg_id}/reactions", method="POST", data={"emoji": emoji}, token=token)

def main(argv=None):
    parser = argparse.ArgumentParser(description="Idempotently seed the local NexaChat demo conversations.")
    parser.parse_args(argv)
    print("Seeding clean demo users (+8490000020x)...")
    token_lan, user_lan = auth_user("+84900000201", "password123", "Lan Anh")
    time.sleep(0.2)
    token_khoa, user_khoa = auth_user("+84900000202", "password123", "Minh Khoa")
    time.sleep(0.2)
    token_chau, user_chau = auth_user("+84900000203", "password123", "Bao Chau")
    time.sleep(0.2)

    print("Adding contacts between demo users...")
    add_contact(token_lan, "+84900000202", "Khoa")
    add_contact(token_lan, "+84900000203", "Châu")
    add_contact(token_khoa, "+84900000201", "Lan Anh")
    add_contact(token_khoa, "+84900000203", "Châu")
    add_contact(token_chau, "+84900000201", "Lan Anh")
    add_contact(token_chau, "+84900000202", "Khoa")

    print("Creating clean direct chat Lan Anh <-> Minh Khoa...")
    direct_id = create_or_get_direct(token_lan, user_khoa["id"])

    print("Sending clean messages in Direct Chat...")
    m1 = send_seed_msg(token_khoa, direct_id, "direct-1", "Chào Lan Anh! Cậu đã hoàn thiện tài liệu thuyết trình chưa?")
    time.sleep(0.1)
    m2 = send_seed_msg(token_lan, direct_id, "direct-2", "Chào Khoa! Mình vừa bổ sung xong phần biểu đồ và phân tích dữ liệu rồi.")
    time.sleep(0.1)
    m3 = send_seed_msg(token_lan, direct_id, "direct-3", "Giao diện mới nhìn rất trực quan và thao tác mượt mà.")
    time.sleep(0.1)
    m4 = send_seed_msg(token_khoa, direct_id, "direct-4", "Tuyệt vời, sáng mai nhóm mình sẽ kiểm tra lại lần cuối trước giờ báo cáo nhé.")
    time.sleep(0.1)
    m5 = send_seed_msg(token_lan, direct_id, "direct-5", "Nhất trí! Hẹn gặp Khoa lúc 8h30 tại giảng đường A1.", reply_to_id=m4.get("id"))
    time.sleep(0.1)

    if m5.get("id"):
        pin_msg(token_lan, direct_id, m5["id"])
        react_msg(token_khoa, direct_id, m5["id"], "👍")
        react_msg(token_lan, direct_id, m5["id"], "❤️")

    # Clean demo group chat
    print("Creating clean group chat 'Nhom Nghien Cuu'...")
    group_id = create_or_get_group(token_lan, "Nhom Nghien Cuu", [user_khoa["id"], user_chau["id"]])

    print("Sending clean messages in Group Chat...")
    gm1 = send_seed_msg(token_khoa, group_id, "group-1", "Chào cả nhóm! Mọi người chuẩn bị cho buổi báo cáo tiến độ tuần này nhé.")
    time.sleep(0.1)
    gm2 = send_seed_msg(token_chau, group_id, "group-2", "Mình đã tổng hợp đầy đủ số liệu khảo sát rồi.")
    time.sleep(0.1)
    gm3 = send_seed_msg(token_lan, group_id, "group-3", "Phần thiết kế giao diện và trải nghiệm người dùng cũng đã hoàn thiện 100%.", reply_to_id=gm1.get("id"))
    time.sleep(0.1)
    gm4 = send_seed_msg(token_lan, group_id, "group-4", "Hẹn mọi người 9h sáng mai tại phòng thảo luận 302 nhé!")
    time.sleep(0.1)

    if gm4.get("id"):
        pin_msg(token_lan, group_id, gm4["id"])
        react_msg(token_khoa, group_id, gm4["id"], "👍")
        react_msg(token_chau, group_id, gm4["id"], "🎉")

    # Demonstrate Edit and Delete (Tombstone)
    print("Demonstrating Edit and Delete in Direct Chat...")
    to_edit = send_seed_msg(token_lan, direct_id, "direct-edit", "Mai nhớ mang tài liệu nha")
    time.sleep(0.1)
    if to_edit.get("id"):
        edit_msg(token_lan, direct_id, to_edit["id"], "Mai nhớ mang đầy đủ tài liệu và laptop nha!")

    to_delete = send_seed_msg(token_lan, direct_id, "direct-delete", "Tin nhắn nháp này sẽ bị xoá ngay")
    time.sleep(0.1)
    if to_delete.get("id"):
        delete_msg(token_lan, direct_id, to_delete["id"])

    print("Demo data seeded successfully with clean users and messages!")

if __name__ == "__main__":
    main()
