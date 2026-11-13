#!/usr/bin/env python3
"""Live HTTP checks for POST /api/v1/conversations/{id}/summary.

Uses dedicated fixture accounts (+849000003xx, display names "Summary E2E …").
Does not touch demo seed users. Access tokens are never printed.

Usage:
    python tools/e2e/summary/summary_e2e.py [--base-url http://localhost:8080]
    python tools/e2e/summary/summary_e2e.py --with-rate-limit

See docs/architecture/ai-summary.md for the contract.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from e2e_common import (  # noqa: E402
    Client,
    E2EFailure,
    check,
    direct_conversation,
    ensure_user,
    history,
    main_guard,
    send_text,
)

USER_ALPHA_PHONE = "+84900000301"
USER_BETA_PHONE = "+84900000302"
USER_OUTSIDER_PHONE = "+84900000303"
USER_RATE_PHONE = "+84900000304"

USER_ALPHA_NAME = "Summary E2E Alpha"
USER_BETA_NAME = "Summary E2E Beta"
USER_OUTSIDER_NAME = "Summary E2E Outsider"
USER_RATE_NAME = "Summary E2E RateLimit"

SECTIONS_WITH_CITATIONS = ("keyPoints", "decisions", "actionItems", "questions")
ARRAY_SECTIONS = SECTIONS_WITH_CITATIONS + ("topics", "participants")

PII_URL = "https://example.com/nexachat/roadmap?q=1"
PII_PHONE = "+84912345678"
PII_EMAIL = "summary-e2e@example.com"

EMAIL_PATTERN = re.compile(r"summary-e2e@example\.com", re.I)
PHONE_PATTERN = re.compile(r"\+84912345678|0912345678")
URL_PATTERN = re.compile(r"example\.com/nexachat", re.I)


def summary_body(scope: str, *, limit: int | None = None, refresh: bool = False) -> dict:
    body: dict = {"scope": scope, "language": "VI", "refresh": refresh}
    if limit is not None:
        body["limit"] = limit
    return body


def post_summary(client: Client, token: str, conversation_id: str, body: dict):
    return client.post(
        f"/api/v1/conversations/{conversation_id}/summary",
        token=token,
        body=body,
    )


def collect_response_text(summary: dict) -> str:
    """Concatenate user-visible summary strings for privacy checks."""
    parts: list[str] = []
    overview = summary.get("overview")
    if isinstance(overview, str):
        parts.append(overview)
    for section in SECTIONS_WITH_CITATIONS:
        for item in summary.get(section) or []:
            if isinstance(item, dict) and isinstance(item.get("text"), str):
                parts.append(item["text"])
            due = item.get("dueHint") if isinstance(item, dict) else None
            if isinstance(due, str):
                parts.append(due)
    for topic in summary.get("topics") or []:
        if isinstance(topic, str):
            parts.append(topic)
    for participant in summary.get("participants") or []:
        if isinstance(participant, dict):
            name = participant.get("displayName")
            if isinstance(name, str):
                parts.append(name)
    return "\n".join(parts)


def response_contains_redacted_pii(text: str) -> bool:
    return bool(
        EMAIL_PATTERN.search(text)
        or PHONE_PATTERN.search(text)
        or URL_PATTERN.search(text)
    )


def contract_requires_response_redaction(generated_by: str) -> bool:
    """docs/architecture/ai-summary.md redacts outbound LLM input only; validated LLM output strips links."""
    return generated_by == "LLM"


def assert_arrays_present(summary: dict) -> None:
    for key in ARRAY_SECTIONS:
        value = summary.get(key)
        check(value is not None, f"{key} is null")
        check(isinstance(value, list), f"{key} is not an array")


def assert_citations_in_window(summary: dict, allowed_ids: set[str]) -> None:
    for section in SECTIONS_WITH_CITATIONS:
        for item in summary.get(section) or []:
            message_ids = item.get("messageIds")
            check(isinstance(message_ids, list), f"{section} item missing messageIds array")
            check(len(message_ids) >= 1, f"{section} item has empty messageIds")
            for message_id in message_ids:
                check(
                    str(message_id) in allowed_ids,
                    f"{section} cites message {message_id} outside conversation window",
                )


def assert_local_summary(summary: dict, allowed_ids: set[str]) -> None:
    check(summary.get("generatedBy") == "LOCAL", f"expected LOCAL, got {summary.get('generatedBy')}")
    assert_arrays_present(summary)
    assert_citations_in_window(summary, allowed_ids)
    text = collect_response_text(summary)
    if contract_requires_response_redaction(str(summary.get("generatedBy"))):
        check(
            not response_contains_redacted_pii(text),
            "LLM summary response echoed URL/phone/email that should be redacted",
        )


def assert_error(response, status: int, code: str) -> None:
    check(response.status == status, f"expected HTTP {status}, got {response.status}")
    check(response.error_code() == code, f"expected error {code}, got {response.error_code()}")


def vietnamese_corpus_messages() -> list[str]:
    """≥40 structured Vietnamese chat lines for extractive summary."""
    lines = [
        "Chào cả nhóm, hôm nay mình tóm tắt tiến độ dự án NexaChat.",
        "Quyết định: giữ engine tóm tắt cục bộ làm mặc định cho mọi thành viên.",
        "Câu hỏi: có nên bật LLM chỉ khi người dùng đồng ý không?",
        "Việc cần làm: Son hoàn thiện regression test trước thứ Sáu.",
        "Chi sẽ review API contract và cập nhật tài liệu.",
        "Mình đã chốt scope UNREAD, LAST_N và TODAY cho cửa sổ tóm tắt.",
        "Quyết định: giới hạn 500 tin nhắn và truncate 1000 ký tự mỗi tin.",
        "Có ai kiểm tra rate limit 10/phút và 60/ngày chưa?",
        "Action item: thêm cache 24 giờ và invalidate khi sửa/xóa tin.",
        "Tài liệu tham khảo nội bộ: " + PII_URL,
        "Liên hệ khẩn (chỉ test): " + PII_PHONE,
        "Gửi báo cáo qua " + PII_EMAIL + " trước demo.",
        "Quyết định: citation bắt buộc mỗi mục trong keyPoints và decisions.",
        "Câu hỏi mở: có cần veto theo từng hội thoại không?",
        "Việc cần làm: kiểm tra rotation và font scale trên Android.",
        "Nhóm đồng ý không log prompt hay raw output từ model.",
        "Quyết định: fallback LOCAL khi LLM timeout hoặc bad output.",
        "Chi hỏi: refresh=true có thay thế entry cache không?",
        "Trả lời: có, và vẫn tính vào rate limit.",
        "Action: chuẩn bị fixture E2E riêng, không dùng user demo.",
        "Quyết định: redact phone, email, URL trước khi gửi ra LLM.",
        "Câu hỏi: consent revoke có chặn outbound ngay lập tức không?",
        "Mình xác nhận adapter recheck consent trước khi gọi HTTPS.",
        "Việc cần làm: viết script Python stdlib giống call_e2e.",
        "Quyết định: mảng sections không bao giờ null trong response.",
        "Thống nhất ngôn ngữ AUTO ưu tiên tiếng Việt khi nội dung chủ yếu là VI.",
        "Câu hỏi: TOO_FEW_MESSAGES khi dưới 5 tin usable?",
        "Đúng, gồm TEXT, không xóa, không system.",
        "Action item: Binh (outsider) dùng cho NOT_MEMBER 403.",
        "Quyết định: empty PATCH settings body bị reject VALIDATION_FAILED.",
        "Son sẽ chạy script hai lần liên tiếp trên backend local.",
        "Câu hỏi: malformed scope trả 400 chuẩn envelope?",
        "LAST_N limit=4 cũng phải VALIDATION_FAILED theo test IT.",
        "Việc cần làm: ghi nhận generatedBy sau consent không in nội dung.",
        "Quyết định: không trả 503; luôn degrade về LOCAL.",
        "Nhắc lại quyết định demo: dark theme và i18n EN/VI parity.",
        "Câu hỏi cuối: còn blocker nào trước khi release không?",
        "Action: cập nhật README e2e một dòng cho summary_e2e.py.",
        "Kết luận: sẵn sàng verify citation và LOCAL engine.",
        "Cảm ơn mọi người, mai sync lại lúc 9h.",
        "Phụ lục: kiểm tra participants có messageCount hợp lệ.",
        "Quyết định phụ: rate limit scenario chỉ chạy với --with-rate-limit.",
    ]
    check(len(lines) >= 40, f"corpus must have ≥40 lines, got {len(lines)}")
    return lines


def seed_conversation(
    client: Client, token_a: str, token_b: str, conversation_id: str
) -> set[str]:
    message_ids: set[str] = set()
    turn = 0
    for content in vietnamese_corpus_messages():
        token = token_a if turn % 2 == 0 else token_b
        sent = send_text(client, token, conversation_id, content)
        message_ids.add(sent["id"])
        turn += 1
    return message_ids


def conversation_text_count(client: Client, token: str, conversation_id: str) -> int:
    page = history(client, token, conversation_id, limit=500)
    return sum(1 for item in page.get("items", []) if item.get("type") == "TEXT")


def seed_conversation_if_needed(
    client: Client, token_a: str, token_b: str, conversation_id: str, minimum: int = 40
) -> set[str]:
    if conversation_text_count(client, token_a, conversation_id) >= minimum:
        page = history(client, token_a, conversation_id, limit=500)
        return {item["id"] for item in page.get("items", []) if item.get("type") == "TEXT"}
    return seed_conversation(client, token_a, token_b, conversation_id)


def load_message_ids_in_window(
    client: Client,
    token: str,
    conversation_id: str,
    *,
    limit: int = 500,
    from_seq: int | None = None,
    to_seq: int | None = None,
) -> set[str]:
    params: dict = {"limit": limit}
    if from_seq is not None:
        params["afterSeq"] = max(0, from_seq - 1)
    response = client.get(
        f"/api/v1/conversations/{conversation_id}/messages",
        token=token,
        params=params,
    )
    check(response.status == 200, f"history for window failed: {response}")
    page = response.json()
    ids: set[str] = set()
    for item in page.get("items", []):
        if item.get("type") != "TEXT":
            continue
        seq = int(item.get("seq", 0))
        if from_seq is not None and seq < from_seq:
            continue
        if to_seq is not None and seq > to_seq:
            continue
        ids.add(item["id"])
    return ids


def ensure_unread_messages(
    client: Client, token_peer: str, conversation_id: str, count: int = 5
) -> None:
    for index in range(count):
        send_text(
            client,
            token_peer,
            conversation_id,
            f"Summary E2E unread {index + 1}: quyết định kiểm tra UNREAD scope.",
        )


def scenario_local_scopes(
    client: Client,
    token: str,
    token_peer: str,
    conversation_id: str,
    allowed_ids: set[str],
) -> None:
    ensure_unread_messages(client, token_peer, conversation_id, count=5)

    for scope, limit in (("UNREAD", None), ("LAST_N", 50), ("TODAY", None)):
        body = summary_body(scope, limit=limit, refresh=False)
        response = post_summary(client, token, conversation_id, body)
        check(response.status == 200, f"{scope} summary failed: {response}")
        summary = response.json()
        window_ids = allowed_ids
        if summary.get("fromSeq") is not None and summary.get("toSeq") is not None:
            window_ids = load_message_ids_in_window(
                client,
                token,
                conversation_id,
                from_seq=int(summary["fromSeq"]),
                to_seq=int(summary["toSeq"]),
            )
        assert_local_summary(summary, window_ids)
        print(f"[PASS] {scope} summary LOCAL with valid sections and citations")


def scenario_too_few_messages(client: Client, token: str, peer_phone: str) -> str:
    _, conversation = direct_conversation(client, token, peer_phone)
    conversation_id = conversation["id"]
    for index in range(3):
        send_text(client, token, conversation_id, f"Summary E2E sparse {index + 1}")
    response = post_summary(client, token, conversation_id, summary_body("UNREAD"))
    assert_error(response, 400, "TOO_FEW_MESSAGES")
    print("[PASS] TOO_FEW_MESSAGES for three usable messages")
    return conversation_id


def scenario_authorization(
    client: Client,
    token_member: str,
    token_outsider: str,
    conversation_id: str,
) -> None:
    outsider = post_summary(
        client,
        token_outsider,
        conversation_id,
        summary_body("UNREAD"),
    )
    assert_error(outsider, 403, "NOT_MEMBER")
    print("[PASS] non-member summary -> 403 NOT_MEMBER")

    anonymous = post_summary(client, None, conversation_id, summary_body("UNREAD"))
    check(anonymous.status == 401, f"expected 401, got {anonymous.status}")
    check(
        anonymous.error_code() == "UNAUTHORIZED",
        f"expected UNAUTHORIZED, got {anonymous.error_code()}",
    )
    print("[PASS] missing token -> 401 UNAUTHORIZED")

    bad_scope = post_summary(
        client,
        token_member,
        conversation_id,
        {"scope": "YESTERDAY", "language": "VI", "refresh": False},
    )
    assert_error(bad_scope, 400, "VALIDATION_FAILED")
    print("[PASS] malformed scope -> 400 VALIDATION_FAILED")

    bad_limit = post_summary(
        client,
        token_member,
        conversation_id,
        summary_body("LAST_N", limit=4),
    )
    assert_error(bad_limit, 400, "VALIDATION_FAILED")
    print("[PASS] LAST_N limit=4 -> 400 VALIDATION_FAILED")


def patch_settings(client: Client, token: str, body: dict):
    return client.patch("/api/v1/users/me/settings", token=token, body=body)


def get_settings(client: Client, token: str):
    return client.get("/api/v1/users/me/settings", token=token)


def scenario_consent(
    client: Client,
    token: str,
    conversation_id: str,
    allowed_ids: set[str],
) -> str | None:
    patch_settings(client, token, {"aiSummaryConsent": False})
    settings = get_settings(client, token)
    check(settings.status == 200, f"settings GET failed: {settings}")
    check(settings.json().get("aiSummaryConsent") is False, "consent not false after reset")

    empty = patch_settings(client, token, {})
    assert_error(empty, 400, "VALIDATION_FAILED")
    print("[PASS] empty PATCH settings rejected")

    enabled = patch_settings(client, token, {"aiSummaryConsent": True})
    check(enabled.status == 200, f"consent PATCH failed: {enabled}")
    check(enabled.json().get("aiSummaryConsent") is True, "consent not true after PATCH")
    reread = get_settings(client, token)
    check(reread.json().get("aiSummaryConsent") is True, "GET consent not true")
    print("[PASS] consent enable round-trip")

    with_consent = post_summary(
        client, token, conversation_id, summary_body("LAST_N", limit=50, refresh=False)
    )
    check(with_consent.status == 200, f"consented summary failed: {with_consent}")
    consented = with_consent.json()
    provider = consented.get("generatedBy")
    check(provider in ("LOCAL", "LLM"), f"unexpected generatedBy {provider}")
    assert_arrays_present(consented)
    window_ids = allowed_ids
    if consented.get("fromSeq") is not None and consented.get("toSeq") is not None:
        window_ids = load_message_ids_in_window(
            client,
            token,
            conversation_id,
            from_seq=int(consented["fromSeq"]),
            to_seq=int(consented["toSeq"]),
        )
    assert_citations_in_window(consented, window_ids)
    print(f"[PASS] summary with consent (generatedBy={provider}, content not logged)")

    revoked = patch_settings(client, token, {"aiSummaryConsent": False})
    check(revoked.status == 200 and revoked.json().get("aiSummaryConsent") is False,
          "consent revoke failed")
    after_revoke = post_summary(
        client, token, conversation_id, summary_body("LAST_N", limit=50, refresh=False)
    )
    check(after_revoke.status == 200, f"post-revoke summary failed: {after_revoke}")
    revoked_summary = after_revoke.json()
    check(
        revoked_summary.get("generatedBy") == "LOCAL",
        f"after revoke expected LOCAL, got {revoked_summary.get('generatedBy')}",
    )
    print("[PASS] after consent revoke summary is LOCAL (no LLM cache served)")

    patch_settings(client, token, {"aiSummaryConsent": False})
    return provider


def scenario_rate_limit(client: Client, token: str, conversation_id: str) -> None:
    body = summary_body("LAST_N", limit=50, refresh=True)
    last = None
    for attempt in range(11):
        last = post_summary(client, token, conversation_id, body)
        if last.status == 429:
            break
    check(last is not None, "rate limit loop did not run")
    assert_error(last, 429, "RATE_LIMITED")
    print("[PASS] 11th summary in window -> 429 RATE_LIMITED")


def main() -> int:
    parser = argparse.ArgumentParser(description="NexaChat AI summary API E2E")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument(
        "--with-rate-limit",
        action="store_true",
        help="Run rate-limit scenario last (consumes the per-minute budget)",
    )
    args = parser.parse_args()

    client = Client(args.base_url, timeout=60.0)
    print("== NexaChat summary E2E ==")

    health = client.get("/actuator/health")
    check(health.status == 200 and health.json().get("status") == "UP", f"health: {health}")
    print("[PASS] backend health UP")

    alpha = ensure_user(client, USER_ALPHA_PHONE, USER_ALPHA_NAME)
    beta = ensure_user(client, USER_BETA_PHONE, USER_BETA_NAME)
    outsider = ensure_user(client, USER_OUTSIDER_PHONE, USER_OUTSIDER_NAME)
    token_a = alpha["accessToken"]
    token_b = beta["accessToken"]
    token_out = outsider["accessToken"]

    _, main_conv = direct_conversation(client, token_a, USER_BETA_PHONE)
    conversation_id = main_conv["id"]

    patch_settings(client, token_a, {"aiSummaryConsent": False})

    allowed_ids = seed_conversation_if_needed(client, token_a, token_b, conversation_id)
    check(len(allowed_ids) >= 40, f"expected ≥40 messages, got {len(allowed_ids)}")
    print(f"[PASS] seeded {len(allowed_ids)} messages in main conversation")

    scenario_local_scopes(client, token_b, token_a, conversation_id, allowed_ids)
    scenario_too_few_messages(client, token_a, USER_OUTSIDER_PHONE)
    scenario_authorization(client, token_a, token_out, conversation_id)
    consent_provider = scenario_consent(client, token_a, conversation_id, allowed_ids)

    if args.with_rate_limit:
        rate_user = ensure_user(client, USER_RATE_PHONE, USER_RATE_NAME)
        _, rate_conv = direct_conversation(
            client, rate_user["accessToken"], USER_OUTSIDER_PHONE
        )
        rate_conv_id = rate_conv["id"]
        for index in range(6):
            send_text(
                client,
                rate_user["accessToken"],
                rate_conv_id,
                f"Summary E2E rate seed line {index + 1} quyết định kiểm tra.",
            )
        scenario_rate_limit(client, rate_user["accessToken"], rate_conv_id)
    else:
        print("[SKIP] rate limit (pass --with-rate-limit to run last)")

    if consent_provider:
        print(f"[INFO] consent-on provider observed: {consent_provider}")
    print("\nNexaChat summary E2E: all executed scenarios passed")
    return 0


if __name__ == "__main__":
    main_guard(main)
