#!/usr/bin/env python3
"""Live journey: RAG ask + AI consent/opt-out + summary cache purge + translate.

Fixture accounts live in +849000006xx (display names "AI E2E ..."). Demo seed
users are never touched and tokens are never printed. The stack runs without
any external LLM / embedding / translate key, so the assertions target the
offline engines (lexical embeddings, EXTRACTIVE/CACHE ask modes, the LOCAL
summary engine, the translate same-language shortcut, the documented
TRANSLATION_UNAVAILABLE refusal, the clean typed translate budget refusal,
and the consent-revoke translate refusals) and switch to the provider-backed
contract only when the response shows a provider answered.

Contracts: docs/architecture/rag.md, ai-summary.md, ai-translation.md,
server/src/main/java/com/nexachat/rag/**, summary/**, translate/**, and the
ITs AskApiIT, ConversationAiOptOutIT, SummaryOptOutIT,
TranslateRequestLimitRepositoryIT, TranslateTxRollbackIT.

Usage:
    python tools/e2e/journeys/ai_privacy_e2e.py [--base-url http://127.0.0.1:8092]
    python tools/e2e/journeys/ai_privacy_e2e.py --list
    python tools/e2e/journeys/ai_privacy_e2e.py --only ask_cites_and_caches,translate_contract
"""

from __future__ import annotations

import argparse
import sys
import time
import uuid
from pathlib import Path
from typing import Callable, Dict, List, Optional, Sequence, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from e2e_common import (  # noqa: E402
    Client,
    E2EFailure,
    _cached_session,
    check,
    direct_conversation,
    ensure_user,
    main_guard,
    send_text,
)

DEFAULT_BASE_URL = "http://127.0.0.1:8092"
TAG = "AI E2E"

ASKER_PHONE = "+84900000601"     # A: consented member who asks / summarizes
PEER_PHONE = "+84900000602"      # B: non-consented member, authors facts, opts out
OUTSIDER_PHONE = "+84900000603"  # C: never a member of the A-B conversation
LIMIT_PHONE = "+84900000604"     # R: outsider that only burns its own ask bucket
TRANSLATE_PHONE = "+84900000605"  # T: consented burner that spends only its own translate budget
ADMIN_PHONE = "+84900000890"     # operator phone for /api/v1/rag/stats

NAMES = {
    ASKER_PHONE: TAG + " Asker",
    PEER_PHONE: TAG + " Peer",
    OUTSIDER_PHONE: TAG + " Outsider",
    LIMIT_PHONE: TAG + " Limiter",
    TRANSLATE_PHONE: TAG + " Translate",
    ADMIN_PHONE: TAG + " Admin",
}

ASK_MODES = ("CACHE", "NOT_FOUND", "EXTRACTIVE", "LLM")
ASK_RATE_PER_MINUTE = 20  # nexachat.rag.ask.rate-limit-per-minute default
TRANSLATE_RATE_PER_MINUTE = 30  # nexachat.translate.rate-limit-per-minute default
PURGE_NOT_RUN_REASON = ("backend predates #138 (opt-out did not purge message_summaries: "
                        "stale cached summary replayed)")


class NotRun(Exception):
    """Scenario prerequisite missing on this stack."""


# --------------------------------------------------------------------------
# Pure helpers (unit-tested offline in test_ai_privacy_e2e.py)
# --------------------------------------------------------------------------

def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="RAG / AI privacy / translate live journey")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--list", action="store_true", help="print scenario names and exit")
    parser.add_argument("--only", default="", help="comma-separated scenario names")
    parser.add_argument("--assume-pre-138", action="store_true",
                        help="backend predates #138: report the summary-purge check as NOT_RUN "
                             "instead of FAIL when opt-out leaves the cached summary")
    return parser.parse_args(argv)


def select_scenarios(all_names: Sequence[str], only: str) -> List[str]:
    if not only or not only.strip():
        return list(all_names)
    wanted = [name.strip() for name in only.split(",") if name.strip()]
    unknown = [name for name in wanted if name not in all_names]
    if unknown:
        raise ValueError("unknown scenario(s): " + ", ".join(unknown))
    return [name for name in all_names if name in wanted]


def codename(nonce: str, salt: str = "") -> str:
    """Letters-only distinctive token derived from the run nonce (no digits to redact)."""
    alphabet = "bcdfghjklmnpqrstvwxz"
    seed = uuid.uuid5(uuid.NAMESPACE_URL, nonce + "|" + salt).hex
    letters = "".join(alphabet[int(ch, 16) % len(alphabet)] for ch in seed[:9])
    return "zq" + letters


def ask_body(question: str, language: Optional[str] = None) -> dict:
    body = {"question": question}
    if language:
        body["language"] = language
    return body


def summary_body(limit: int = 5, refresh: bool = False, scope: str = "LAST_N") -> dict:
    body = {"scope": scope, "refresh": refresh}
    if scope == "LAST_N":
        body["limit"] = limit
    return body


def cited_ids(payload) -> List[str]:
    if not isinstance(payload, dict):
        return []
    return [str(c.get("messageId")) for c in payload.get("citations") or []
            if isinstance(c, dict)]


def validate_ask_response(payload) -> Optional[str]:
    """AskResponse{answer, citations[{messageId, seq, snippet}], mode, contextOmitted, usage}."""
    if not isinstance(payload, dict):
        return "ask body is not an object"
    mode = payload.get("mode")
    if mode not in ASK_MODES:
        return f"ask.mode unexpected: {mode!r}"
    if not isinstance(payload.get("citations"), list):
        return "ask.citations not a list"
    if not isinstance(payload.get("contextOmitted"), bool):
        return "ask.contextOmitted not a bool"
    usage = payload.get("usage")
    if not isinstance(usage, dict) or not isinstance(usage.get("cached"), bool) \
            or not isinstance(usage.get("llmCalls"), int):
        return "ask.usage{cached,llmCalls} missing"
    for citation in payload["citations"]:
        if not isinstance(citation, dict) or not citation.get("messageId") \
                or not isinstance(citation.get("seq"), int) or "snippet" not in citation:
            return f"ask citation malformed: {citation!r}"
    if mode == "NOT_FOUND":
        if payload.get("answer") is not None or payload["citations"]:
            return "NOT_FOUND carries an answer or citations"
        return None
    if not isinstance(payload.get("answer"), str) or not payload["answer"]:
        return f"{mode} without an answer"
    if not payload["citations"]:
        return f"{mode} without citations"
    if mode == "CACHE":
        if usage["cached"] is not True or usage["llmCalls"] != 0:
            return "CACHE must report usage.cached=true and llmCalls=0"
    else:
        if usage["cached"] is not False:
            return f"{mode} must report usage.cached=false"
    if mode == "EXTRACTIVE" and usage["llmCalls"] != 0:
        return "EXTRACTIVE must report llmCalls=0"
    if "vector" in str(payload).lower():
        return "ask response leaks a vector field"
    return None


def validate_summary_response(payload) -> Optional[str]:
    if not isinstance(payload, dict):
        return "summary body is not an object"
    for key in ("summaryId", "conversationId", "generatedBy", "createdAt"):
        if not payload.get(key):
            return f"summary.{key} missing"
    if payload["generatedBy"] not in ("LOCAL", "LLM"):
        return f"summary.generatedBy unexpected: {payload['generatedBy']!r}"
    for key in ("cached", "contextOmitted"):
        if not isinstance(payload.get(key), bool):
            return f"summary.{key} not a bool"
    if not isinstance(payload.get("messageCount"), int) or payload["messageCount"] < 1:
        return "summary.messageCount invalid"
    for key in ("keyPoints", "decisions", "actionItems", "questions", "topics", "participants"):
        if not isinstance(payload.get(key), list):
            return f"summary.{key} not a list"
    return None


def validate_translate_response(payload) -> Optional[str]:
    """TranslateResponse{text, sourceLang, targetLang, cached, model}."""
    if not isinstance(payload, dict):
        return "translate body is not an object"
    if not isinstance(payload.get("text"), str) or not payload["text"]:
        return "translate.text missing"
    if not isinstance(payload.get("targetLang"), str) or not payload["targetLang"]:
        return "translate.targetLang missing"
    if not isinstance(payload.get("cached"), bool):
        return "translate.cached not a bool"
    return None


def classify_summary_purge(cached_after_opt_out: bool,
                           assume_pre_138: bool = False) -> Tuple[str, str]:
    """PASS when opt-out purged the summary cache; a stale replay FAILs unless
    the operator declared a pre-#138 backend."""
    if not cached_after_opt_out:
        return "PASS", "opt-out purged the cached summary (cached=false)"
    if assume_pre_138:
        return "NOT_RUN", PURGE_NOT_RUN_REASON
    return "FAIL", "opt-out left the cached summary replayable (cached=true)"


def seconds_until_next_minute(now: float) -> float:
    return 60.0 - (now % 60.0) + 1.0


class TranslateBudget:
    """Offline model of the per-user translate token accounting (#242).

    The minute window is consumed first; when the day window refuses, the
    minute token is refunded (a refusal spends nothing); a minute-window
    refusal never touches the day; cache hits and same-language shortcuts
    are free. Provider attempts are charged, not just successes
    (ai-translation.md: "tokens are charged per attempted generation").
    """

    def __init__(self, per_minute: int = 30, per_day: int = 300) -> None:
        self.per_minute = per_minute
        self.per_day = per_day
        self.minute_used = 0
        self.day_used = 0

    def attempt(self, charge: bool = True) -> str:
        """One translate attempt: 'free' | 'allowed' | 'minute_refused' | 'day_refused'."""
        if not charge:
            return "free"
        if self.minute_used >= self.per_minute:
            return "minute_refused"
        if self.day_used >= self.per_day:
            # The minute token was consumed and then refunded: net spend zero.
            return "day_refused"
        self.minute_used += 1
        self.day_used += 1
        return "allowed"


def simulate_revoke_mid_batch(items: Sequence[str], revoke_after: int) -> List[str]:
    """Expected per-item outcomes for one translate-batch whose caller's AI
    consent flips to false after ``revoke_after`` provider-bound items have
    completed (0 = the revoke predates the batch).

    ``items`` holds one entry per requested id: 'charged' for a provider-bound
    message, 'cached' for a cache hit, 'shortcut' for a same-language message.
    Free lanes ignore the revoke; a charged item whose generation finished
    after the revoke is refused post-generation (AI_CONSENT_REQUIRED) and is
    never cached; a revoke that predates the batch refuses every charged item
    before the provider is called, so no tokens are spent.
    """
    outcomes: List[str] = []
    entry_consented: Optional[bool] = None
    charged_done = 0
    for kind in items:
        if kind == "cached":
            outcomes.append("cached")
            continue
        if kind == "shortcut":
            outcomes.append("shortcut")
            continue
        if entry_consented is None:
            entry_consented = revoke_after > 0
        if not entry_consented:
            outcomes.append("AI_CONSENT_REQUIRED")
            continue
        # The entry check saw consent, so the provider runs; the post-generation
        # re-check re-reads consent and a revoke that landed meanwhile wins.
        charged_done += 1
        outcomes.append("AI_CONSENT_REQUIRED" if charged_done > revoke_after
                        else "translated")
    return outcomes


def batch_item_outcome(item) -> str:
    """'translated' | 'refused:<code>' | 'malformed' for one translate-batch item."""
    if not isinstance(item, dict) or not item.get("messageId"):
        return "malformed"
    if item.get("error") is not None and item.get("text") is None:
        return "refused:" + str(item["error"])
    if item.get("error") is None and isinstance(item.get("text"), str) and item["text"]:
        return "translated"
    return "malformed"


# --------------------------------------------------------------------------
# Live context
# --------------------------------------------------------------------------

def assert_error(response, status: int, code: Optional[str], what: str) -> None:
    check(response.status == status,
          f"{what}: expected HTTP {status}, got {response.status} {response.text[:200]!r}")
    if code is not None:
        check(response.error_code() == code,
              f"{what}: expected error {code}, got {response.error_code()}")


class Context:
    def __init__(self, client: Client):
        self.client = client
        self.nonce = uuid.uuid4().hex[:10]
        self.sessions: Dict[str, dict] = {}
        self._conversation: Optional[dict] = None

    def session(self, phone: str) -> dict:
        if phone not in self.sessions:
            cached = _cached_session(self.client, phone)
            self.sessions[phone] = cached or ensure_user(self.client, phone, NAMES[phone])
        return self.sessions[phone]

    def token(self, phone: str) -> str:
        return self.session(phone)["accessToken"]

    def user_id(self, phone: str) -> str:
        return str(self.session(phone)["user"]["id"])

    def conversation_id(self) -> str:
        if self._conversation is None:
            self.session(PEER_PHONE)
            _, conversation = direct_conversation(self.client, self.token(ASKER_PHONE), PEER_PHONE)
            self._conversation = conversation
            self.set_consent(ASKER_PHONE, True)
            self.set_consent(PEER_PHONE, False)
            # Self-heal a peer opt-out left behind by an aborted earlier run.
            self.opt_out(PEER_PHONE, False)
            self.opt_out(ASKER_PHONE, False)
        return str(self._conversation["id"])

    def set_consent(self, phone: str, value: bool) -> None:
        response = self.client.patch("/api/v1/users/me/settings", token=self.token(phone),
                                     body={"aiSummaryConsent": value})
        check(response.status == 200, f"settings PATCH aiSummaryConsent expected 200, got {response}")
        check(response.json().get("aiSummaryConsent") is value, "aiSummaryConsent not echoed")

    def opt_out(self, phone: str, value: bool) -> dict:
        path = f"/api/v1/conversations/{self.conversation_id()}/ai-opt-out"
        response = (self.client.put(path, token=self.token(phone)) if value
                    else self.client.delete(path, token=self.token(phone)))
        check(response.status == 200, f"ai-opt-out {'PUT' if value else 'DELETE'} "
                                      f"expected 200, got {response}")
        body = response.json()
        check(body.get("aiOptOut") is value and body.get("conversationId") == self.conversation_id(),
              f"ai-opt-out response {body}")
        return body

    def read_opt_out(self, phone: str) -> bool:
        response = self.client.get(f"/api/v1/conversations/{self.conversation_id()}/ai-opt-out",
                                   token=self.token(phone))
        check(response.status == 200, f"ai-opt-out GET expected 200, got {response}")
        return response.json()["aiOptOut"]

    def send(self, phone: str, content: str) -> dict:
        return send_text(self.client, self.token(phone), self.conversation_id(), content)

    def _with_minute_retry(self, call):
        response = call()
        if response.status == 429 and response.error_code() == "RATE_LIMITED":
            wait = seconds_until_next_minute(time.time())
            print(f"[wait] per-minute bucket exhausted, retrying in {wait:.0f}s")
            time.sleep(wait)
            response = call()
        return response

    def ask(self, phone: str, question: str, conversation_id: Optional[str] = None) -> dict:
        cid = conversation_id or self.conversation_id()
        response = self._with_minute_retry(lambda: self.client.post(
            f"/api/v1/conversations/{cid}/ask", token=self.token(phone), body=ask_body(question)))
        check(response.status == 200, f"ask expected 200, got {response}")
        payload = response.json()
        problem = validate_ask_response(payload)
        check(problem is None, f"ask response: {problem} ({payload})")
        return payload

    def summary(self, phone: str, limit: int = 5, refresh: bool = False) -> dict:
        response = self._with_minute_retry(lambda: self.client.post(
            f"/api/v1/conversations/{self.conversation_id()}/summary", token=self.token(phone),
            body=summary_body(limit, refresh)))
        check(response.status == 200, f"summary expected 200, got {response}")
        payload = response.json()
        problem = validate_summary_response(payload)
        check(problem is None, f"summary response: {problem}")
        check(payload["conversationId"] == self.conversation_id(), "summary conversationId mismatch")
        return payload

    def edit(self, phone: str, message_id: str, content: str):
        return self.client.patch(
            f"/api/v1/conversations/{self.conversation_id()}/messages/{message_id}",
            token=self.token(phone), body={"content": content})

    def recall(self, phone: str, message_id: str):
        return self.client.delete(
            f"/api/v1/conversations/{self.conversation_id()}/messages/{message_id}",
            token=self.token(phone))


def seed_fact(ctx: Context, phone: str, salt: str) -> Tuple[str, str, str, dict]:
    """Sends a distinctive lookup fact plus filler; returns (code, secret, question, message)."""
    code = codename(ctx.nonce, salt)
    secret = codename(ctx.nonce, salt + "-secret")
    fact = ctx.send(phone, f"The {code} locker passphrase is {secret} for the north warehouse.")
    other = ASKER_PHONE if phone == PEER_PHONE else PEER_PHONE
    ctx.send(other, f"Thanks, noted for {salt} run {ctx.nonce}.")
    question = f"What is the {code} locker passphrase?"
    return code, secret, question, fact


# --------------------------------------------------------------------------
# Scenarios
# --------------------------------------------------------------------------

def scenario_ask_cites_and_caches(ctx: Context) -> None:
    _, secret, question, fact = seed_fact(ctx, PEER_PHONE, "ask")
    ctx.send(ASKER_PHONE, f"Unrelated: lunch is at noon ({ctx.nonce}).")
    first = ctx.ask(ASKER_PHONE, question)
    check(first["mode"] in ("EXTRACTIVE", "LLM"),
          f"first ask expected EXTRACTIVE/LLM, got {first['mode']}")
    check(fact["id"] in cited_ids(first),
          f"first ask did not cite the fact message (cited {cited_ids(first)})")
    check(first["contextOmitted"] is False, "contextOmitted true with nobody opted out")
    if first["mode"] == "EXTRACTIVE":
        check(secret in first["answer"], "extractive answer does not quote the fact")
        check(first["usage"]["llmCalls"] == 0, "extractive answer spent an LLM call")
    citation = next(c for c in first["citations"] if c["messageId"] == fact["id"])
    check(citation["seq"] == fact["seq"], "citation seq differs from the message seq")
    # Identical question by the consented asker replays from the exact cache.
    second = ctx.ask(ASKER_PHONE, question)
    check(second["mode"] == "CACHE", f"second identical ask expected CACHE, got {second['mode']}")
    check(second["usage"]["cached"] is True and second["usage"]["llmCalls"] == 0,
          "cache replay usage")
    check(cited_ids(second) == cited_ids(first), "cache replay changed the citations")
    # A non-consented member never reads the shared cache (rag.md consent rules).
    peer = ctx.ask(PEER_PHONE, question)
    check(peer["mode"] != "CACHE" and peer["usage"]["cached"] is False,
          f"non-consented member replayed the shared cache ({peer['mode']})")
    check(fact["id"] in cited_ids(peer), "non-consented lexical ask lost the fact citation")
    # A new text message invalidates the conversation's cache.
    ctx.send(PEER_PHONE, f"New message after the cached answer {ctx.nonce}.")
    third = ctx.ask(ASKER_PHONE, question)
    check(third["mode"] != "CACHE" and third["usage"]["cached"] is False,
          "ask after a new message still replayed the cache")


def scenario_rag_stats_counters(ctx: Context) -> None:
    stats = lambda: ctx.client.get("/api/v1/rag/stats", token=ctx.token(ADMIN_PHONE))
    first = stats()
    if first.status in (401, 403):
        raise NotRun(f"{ADMIN_PHONE} is not ADMIN on this stack "
                     "(NEXACHAT_ADMIN_PHONES not configured?)")
    check(first.status == 200, f"rag/stats expected 200, got {first}")
    before = first.json()
    for key in ("requests", "cacheHits", "notFound", "extractive", "llmCalls"):
        check(isinstance(before.get(key), int) and before[key] >= 0,
              f"rag/stats.{key} missing or negative: {before.get(key)!r}")

    _, _, question, _ = seed_fact(ctx, ASKER_PHONE, "stats")
    ctx.ask(ASKER_PHONE, question)
    mid = stats().json()
    # Counters are stack-global; concurrent traffic may add extra increments.
    check(mid["requests"] >= before["requests"] + 1,
          f"ask did not increment requests ({before['requests']} -> {mid['requests']})")
    ctx.ask(ASKER_PHONE, question)  # identical ask replays from the exact cache
    after = stats().json()
    check(after["requests"] >= mid["requests"] + 1,
          f"repeat ask did not increment requests ({mid['requests']} -> {after['requests']})")
    check(after["cacheHits"] >= mid["cacheHits"] + 1,
          f"cached ask did not increment cacheHits ({mid['cacheHits']} -> {after['cacheHits']})")


def scenario_ask_authz_validation(ctx: Context) -> None:
    cid = ctx.conversation_id()
    path = f"/api/v1/conversations/{cid}/ask"
    assert_error(ctx.client.post(path, token=ctx.token(OUTSIDER_PHONE),
                                 body=ask_body("What did we decide?")),
                 403, "NOT_MEMBER", "outsider ask")
    assert_error(ctx.client.post(f"/api/v1/conversations/{uuid.uuid4()}/ask",
                                 token=ctx.token(ASKER_PHONE), body=ask_body("What did we decide?")),
                 404, "CONVERSATION_NOT_FOUND", "ask unknown conversation")
    assert_error(ctx.client.post(path, body=ask_body("What did we decide?")),
                 401, "UNAUTHORIZED", "anonymous ask")
    token = ctx.token(ASKER_PHONE)
    assert_error(ctx.client.post(path, token=token, body={"question": ""}),
                 400, "VALIDATION_FAILED", "empty question")
    assert_error(ctx.client.post(path, token=token, body={"question": "   "}),
                 400, "VALIDATION_FAILED", "blank question")
    assert_error(ctx.client.post(path, token=token, body={}),
                 400, "VALIDATION_FAILED", "missing question")
    assert_error(ctx.client.post(path, token=token, body={"question": "a" * 501}),
                 400, "VALIDATION_FAILED", "501-char question")
    # Summary and opt-out share the same membership gate.
    assert_error(ctx.client.post(f"/api/v1/conversations/{cid}/summary",
                                 token=ctx.token(OUTSIDER_PHONE), body=summary_body()),
                 403, "NOT_MEMBER", "outsider summary")
    assert_error(ctx.client.post(f"/api/v1/conversations/{cid}/summary",
                                 token=token, body=summary_body(limit=4)),
                 400, "VALIDATION_FAILED", "summary LAST_N limit 4")
    assert_error(ctx.client.post(f"/api/v1/conversations/{cid}/summary",
                                 token=token, body={"limit": 5}),
                 400, "VALIDATION_FAILED", "summary without scope")


def scenario_ai_opt_out_excludes_peer(ctx: Context) -> None:
    cid = ctx.conversation_id()
    _, secret, question, fact = seed_fact(ctx, PEER_PHONE, "optout")
    for index in range(3):
        ctx.send(ASKER_PHONE, f"Asker filler {index} about the weekend plan {ctx.nonce}.")
    before = ctx.ask(ASKER_PHONE, question)
    check(fact["id"] in cited_ids(before), "pre-opt-out ask did not cite the peer fact")
    check(ctx.read_opt_out(PEER_PHONE) is False, "peer opt-out GET not false before PUT")

    try:
        ctx.opt_out(PEER_PHONE, True)
        ctx.opt_out(PEER_PHONE, True)  # idempotent
        check(ctx.read_opt_out(PEER_PHONE) is True, "peer opt-out GET not true after PUT")
        # Caller-scoped: the asker's own state is unaffected and never exposes the peer's.
        check(ctx.read_opt_out(ASKER_PHONE) is False, "asker opt-out state leaked the peer's")
        _, convo = direct_conversation(ctx.client, ctx.token(PEER_PHONE), ASKER_PHONE)
        check(convo.get("aiOptOut") is True, "peer direct conversation aiOptOut not true")
        _, convo = direct_conversation(ctx.client, ctx.token(ASKER_PHONE), PEER_PHONE)
        check(convo.get("aiOptOut") is False, "asker direct conversation exposes peer opt-out")
        # Outsider / unknown conversation.
        path = f"/api/v1/conversations/{cid}/ai-opt-out"
        assert_error(ctx.client.put(path, token=ctx.token(OUTSIDER_PHONE)),
                     403, "NOT_MEMBER", "outsider opt-out PUT")
        assert_error(ctx.client.get(path, token=ctx.token(OUTSIDER_PHONE)),
                     403, "NOT_MEMBER", "outsider opt-out GET")
        assert_error(ctx.client.put(f"/api/v1/conversations/{uuid.uuid4()}/ai-opt-out",
                                    token=ctx.token(PEER_PHONE)),
                     404, "CONVERSATION_NOT_FOUND", "opt-out unknown conversation")

        after = ctx.ask(ASKER_PHONE, question)
        check(after["mode"] != "CACHE", "opted-out author's cached answer was replayed")
        check(fact["id"] not in cited_ids(after), "ask cites the opted-out peer's message")
        check(secret not in (after.get("answer") or ""), "ask answer leaks the opted-out text")
        check(secret not in str(after["citations"]), "ask snippet leaks the opted-out text")
        check(after["contextOmitted"] is True, "contextOmitted not true while peer opted out")

        translate = ctx.client.post(f"/api/v1/messages/{fact['id']}/translate",
                                    token=ctx.token(ASKER_PHONE), body={"targetLang": "fr"})
        assert_error(translate, 403, "AI_OPTED_OUT", "translate opted-out author's message")

        summary = ctx.summary(ASKER_PHONE, limit=5)
        if summary["generatedBy"] == "LLM":
            check(secret not in str(summary), "LLM summary carries the opted-out text")
            check(summary["contextOmitted"] is True, "LLM summary contextOmitted not true")
        else:
            # ai-summary.md: only the built-in local engine may still use opted-out
            # messages (they never leave the server); it reports contextOmitted=false.
            check(summary["contextOmitted"] is False, "LOCAL summary contextOmitted not false")
    finally:
        ctx.opt_out(PEER_PHONE, False)

    check(ctx.read_opt_out(PEER_PHONE) is False, "peer opt-out GET not false after DELETE")
    ctx.opt_out(PEER_PHONE, False)  # idempotent
    restored = ctx.ask(ASKER_PHONE, question)
    check(fact["id"] in cited_ids(restored), "opt back in did not restore the peer fact citation")
    check(restored["contextOmitted"] is False, "contextOmitted still true after opt back in")


def scenario_summary_purge_on_opt_out(ctx: Context) -> None:
    ctx.conversation_id()
    for index in range(3):
        ctx.send(PEER_PHONE, f"Peer status update {index}: deploy checklist item done {ctx.nonce}.")
        ctx.send(ASKER_PHONE, f"Asker ack {index}: will review the checklist {ctx.nonce}.")
    first = ctx.summary(ASKER_PHONE, limit=5)
    check(first["cached"] is False, "first summary of a fresh window reported cached=true")
    check(first["messageCount"] == 5, f"LAST_N 5 summarized {first['messageCount']} messages")
    second = ctx.summary(ASKER_PHONE, limit=5)
    check(second["cached"] is True, "identical second summary was not replayed from cache")
    check(second["summaryId"] == first["summaryId"], "cache replay changed summaryId")
    try:
        ctx.opt_out(PEER_PHONE, True)
        third = ctx.summary(ASKER_PHONE, limit=5)
    finally:
        ctx.opt_out(PEER_PHONE, False)
    verdict, reason = classify_summary_purge(third["cached"], ctx.assume_pre_138)
    if verdict == "NOT_RUN":
        raise NotRun(reason)
    check(verdict == "PASS", reason)
    check(third["summaryId"] != first["summaryId"], "purged summary kept the old summaryId")


def scenario_edit_recall_invalidates_cache(ctx: Context) -> None:
    code, secret, question, fact = seed_fact(ctx, ASKER_PHONE, "edit")
    first = ctx.ask(ASKER_PHONE, question)
    check(fact["id"] in cited_ids(first), "ask did not cite the fact before edit")
    cached = ctx.ask(ASKER_PHONE, question)
    check(cached["mode"] == "CACHE", f"repeat ask expected CACHE, got {cached['mode']}")

    new_secret = codename(ctx.nonce, "edit-new-secret")
    edited = ctx.edit(ASKER_PHONE, fact["id"],
                      f"The {code} locker passphrase is {new_secret} for the north warehouse.")
    check(edited.status == 200, f"edit expected 200, got {edited}")
    assert_error(ctx.edit(PEER_PHONE, fact["id"], "hijack"), 403, "NOT_MESSAGE_AUTHOR",
                 "peer edits asker's message")
    after_edit = ctx.ask(ASKER_PHONE, question)
    check(after_edit["mode"] != "CACHE" and after_edit["usage"]["cached"] is False,
          "ask after edit replayed the stale cached answer")
    check(secret not in str(after_edit), "ask after edit still returns the pre-edit text")
    check(fact["id"] in cited_ids(after_edit), "ask after edit lost the edited message citation")
    if after_edit["mode"] == "EXTRACTIVE":
        check(new_secret in after_edit["answer"], "extractive answer does not quote the edit")

    again = ctx.ask(ASKER_PHONE, question)
    check(again["mode"] == "CACHE", "edited answer was not cached again")
    recalled = ctx.recall(ASKER_PHONE, fact["id"])
    check(recalled.status == 204, f"recall expected 204, got {recalled}")
    after_recall = ctx.ask(ASKER_PHONE, question)
    check(after_recall["mode"] != "CACHE" and after_recall["usage"]["cached"] is False,
          "ask after recall replayed the stale cached answer")
    check(fact["id"] not in cited_ids(after_recall), "ask cites a recalled message")
    check(new_secret not in str(after_recall), "ask after recall returns the recalled text")


def scenario_translate_contract(ctx: Context) -> None:
    cid = ctx.conversation_id()
    vietnamese = ctx.send(PEER_PHONE, f"Chào bạn, hẹn gặp lúc tám giờ nhé ({ctx.nonce}).")
    english = ctx.send(PEER_PHONE, f"Please bring the blue folder tomorrow ({ctx.nonce}).")
    no_letters = ctx.send(ASKER_PHONE, "2026-11-19 10:30 #42 !!!")

    def translate(phone: str, message_id: str, target):
        return ctx.client.post(f"/api/v1/messages/{message_id}/translate",
                               token=ctx.token(phone), body={"targetLang": target})

    # Same-language shortcut: served without consent or provider (non-consented peer).
    shortcut = translate(PEER_PHONE, vietnamese["id"], "vi")
    check(shortcut.status == 200, f"vi shortcut expected 200, got {shortcut}")
    body = shortcut.json()
    check(validate_translate_response(body) is None, f"shortcut body {body}")
    check(body["text"] == vietnamese["content"] and body["targetLang"] == "vi"
          and body["sourceLang"] == "vi" and body["cached"] is False and body["model"] is None,
          f"vi shortcut response {body}")
    digits = translate(ASKER_PHONE, no_letters["id"], "fr")
    check(digits.status == 200 and digits.json()["text"] == no_letters["content"],
          f"no-letters shortcut {digits}")

    # Provider path: no consent -> 403; consented -> provider answer or documented 503.
    assert_error(translate(PEER_PHONE, english["id"], "fr"), 403, "AI_CONSENT_REQUIRED",
                 "translate without consent")
    provider = translate(ASKER_PHONE, english["id"], "fr")
    if provider.status == 503:
        assert_error(provider, 503, "TRANSLATION_UNAVAILABLE", "translate without provider")
    else:
        check(provider.status == 200, f"translate expected 200/503, got {provider}")
        problem = validate_translate_response(provider.json())
        check(problem is None and provider.json()["targetLang"] == "fr", f"translate: {problem}")

    # Authz + validation.
    assert_error(translate(OUTSIDER_PHONE, english["id"], "fr"), 403, "NOT_MEMBER",
                 "outsider translate")
    assert_error(translate(ASKER_PHONE, str(uuid.uuid4()), "fr"), 404, "MESSAGE_NOT_FOUND",
                 "translate unknown message")
    assert_error(translate(ASKER_PHONE, english["id"], "xx"), 400, "VALIDATION_FAILED",
                 "unsupported target language")
    assert_error(translate(ASKER_PHONE, english["id"], ""), 400, "VALIDATION_FAILED",
                 "blank target language")
    assert_error(ctx.client.post(f"/api/v1/messages/{english['id']}/translate",
                                 body={"targetLang": "fr"}), 401, "UNAUTHORIZED",
                 "anonymous translate")

    # Batch: per-item typed results.
    batch_path = f"/api/v1/conversations/{cid}/translate-batch"
    missing = str(uuid.uuid4())
    batch = ctx.client.post(batch_path, token=ctx.token(PEER_PHONE), body={
        "messageIds": [vietnamese["id"], english["id"], missing], "targetLang": "vi"})
    check(batch.status == 200, f"translate-batch expected 200, got {batch}")
    results = {item["messageId"]: item for item in batch.json()["results"]}
    check(results[vietnamese["id"]]["text"] == vietnamese["content"]
          and results[vietnamese["id"]]["error"] is None, "batch shortcut item")
    check(results[english["id"]]["error"] == "AI_CONSENT_REQUIRED"
          and results[english["id"]]["text"] is None, "batch no-consent item")
    check(results[missing]["error"] == "MESSAGE_NOT_FOUND", "batch unknown item")
    assert_error(ctx.client.post(batch_path, token=ctx.token(OUTSIDER_PHONE), body={
        "messageIds": [english["id"]], "targetLang": "vi"}), 403, "NOT_MEMBER", "outsider batch")
    assert_error(ctx.client.post(batch_path, token=ctx.token(PEER_PHONE), body={
        "messageIds": [], "targetLang": "vi"}), 400, "VALIDATION_FAILED", "empty batch")


def scenario_translate_refund_on_provider_failure(ctx: Context) -> None:
    """Keyless stack: every generation fails as a typed 503 that is charged
    per attempt and never cached; burning those charged attempts reaches the
    per-minute refusal, which must stay a clean typed 429 (the #242 refund
    accounting turned refusals into HTTP 500s when the refunded row broke the
    schema CHECK); the batch reports the refusal per item and keeps serving
    free lanes while the budget is dry. The day-window refund itself (a daily
    refusal spends nothing) needs 300+ attempts and is modelled offline by
    TranslateBudget and proven by TranslateRequestLimitRepositoryIT and
    TranslateTxRollbackIT."""
    ctx.session(PEER_PHONE)
    ctx.set_consent(TRANSLATE_PHONE, True)
    _, conversation = direct_conversation(ctx.client, ctx.token(TRANSLATE_PHONE), PEER_PHONE)
    cid = str(conversation["id"])
    token = ctx.token(TRANSLATE_PHONE)
    # The burner owns its conversation and budget: no other scenario is affected.
    english = send_text(ctx.client, token, cid,
                        f"Please bring the blue folder tomorrow ({ctx.nonce}).")
    vietnamese = send_text(ctx.client, token, cid,
                           f"Chào bạn, hẹn gặp lúc tám giờ nhé ({ctx.nonce}).")

    def translate(message_id: str, target: str = "fr"):
        return ctx.client.post(f"/api/v1/messages/{message_id}/translate",
                               token=token, body={"targetLang": target})

    def batch(ids: List[str], target: str = "fr"):
        return ctx.client.post(f"/api/v1/conversations/{cid}/translate-batch",
                               token=token, body={"messageIds": ids, "targetLang": target})

    # A provider-backed stack cannot exercise failure accounting without
    # burning real LLM calls; the keyless stack fails instantly and free.
    probe = translate(english["id"])
    if probe.status == 200:
        raise NotRun("provider answered: failure/refund accounting needs the keyless stack")
    assert_error(probe, 503, "TRANSLATION_UNAVAILABLE", "keyless provider failure")
    repeat = translate(english["id"])
    assert_error(repeat, 503, "TRANSLATION_UNAVAILABLE",
                 "a provider failure was cached instead of retried")

    # Per-item typed failures; the batch survives them.
    failed = batch([english["id"], vietnamese["id"]])
    check(failed.status == 200, f"translate-batch expected 200, got {failed}")
    items = {item["messageId"]: item for item in failed.json()["results"]}
    check(batch_item_outcome(items[english["id"]]) == "refused:TRANSLATION_UNAVAILABLE",
          f"batch provider-failure item {items[english['id']]}")
    check(batch_item_outcome(items[vietnamese["id"]]) == "translated"
          and items[vietnamese["id"]]["text"] == vietnamese["content"],
          f"batch shortcut item must stay free and served: {items[vietnamese['id']]}")

    # Charge-per-attempt: failing generations still count, so burning them
    # must reach the per-minute refusal — a clean typed 429, never a 500.
    # The bound absorbs a minute rollover mid-burn plus any tokens an
    # earlier run left in this burner's current window.
    charged = 0
    limited = None
    for _ in range(2 * TRANSLATE_RATE_PER_MINUTE + 5):
        response = translate(english["id"])
        charged += 1
        if response.status == 429:
            limited = response
            break
        assert_error(response, 503, "TRANSLATION_UNAVAILABLE", "charged attempt inside the budget")
    check(limited is not None,
          f"{charged} charged attempts never hit the per-minute limit "
          f"({TRANSLATE_RATE_PER_MINUTE}/min default)")
    assert_error(limited, 429, "RATE_LIMITED", "budget refusal must be a clean typed 429")
    # The refusal state stays valid: the next charged attempt refuses again,
    # still typed — a corrupted refund row would surface as HTTP 500.
    assert_error(translate(english["id"]), 429, "RATE_LIMITED",
                 "refusal must repeat cleanly after the first one")

    # The batch latches to the refusal per item; the free lane keeps serving.
    refused = batch([english["id"], vietnamese["id"]])
    check(refused.status == 200, f"translate-batch under the limit expected 200, got {refused}")
    items = {item["messageId"]: item for item in refused.json()["results"]}
    check(batch_item_outcome(items[english["id"]]) == "refused:RATE_LIMITED",
          f"batch rate-limited item {items[english['id']]}")
    check(batch_item_outcome(items[vietnamese["id"]]) == "translated"
          and items[vietnamese["id"]]["text"] == vietnamese["content"],
          f"shortcut item must ignore the dry budget: {items[vietnamese['id']]}")
    shortcut = translate(vietnamese["id"], "vi")
    check(shortcut.status == 200 and shortcut.json()["text"] == vietnamese["content"],
          f"single shortcut under a dry budget: {shortcut}")


def scenario_revoke_consent_mid_batch(ctx: Context) -> None:
    """A consent revoke between batch rounds wins for every provider-bound
    item: typed AI_CONSENT_REQUIRED per item (HTTP 200 batch, nothing cached
    from the revoked round), free lanes keep serving, and re-granting
    restores the provider lane. The mid-flight variant — the revoke landing
    while one batch's provider calls are in flight, refused post-generation
    and never cached — is modelled offline by simulate_revoke_mid_batch and
    proven by the server's TranslateServiceTest re-check tests."""
    ctx.conversation_id()
    token = ctx.token(ASKER_PHONE)
    english = [ctx.send(ASKER_PHONE,
                        f"Revoke probe {i}: ship the blue crate on Friday ({ctx.nonce}).")
               for i in range(3)]
    vietnamese = ctx.send(ASKER_PHONE, f"Chào bạn, hẹn gặp lúc tám giờ nhé ({ctx.nonce}).")
    batch_messages = english + [vietnamese]

    def translate(message: dict, target: str = "fr"):
        return ctx.client.post(f"/api/v1/messages/{message['id']}/translate",
                               token=token, body={"targetLang": target})

    def batch():
        return ctx.client.post(
            f"/api/v1/conversations/{ctx.conversation_id()}/translate-batch",
            token=token, body={"messageIds": [m["id"] for m in batch_messages],
                               "targetLang": "fr"})

    def check_batch(response, expected: List[str], what: str) -> dict:
        check(response.status == 200, f"{what}: batch expected 200, got {response}")
        items = {item["messageId"]: item for item in response.json()["results"]}
        for message, want in zip(batch_messages, expected):
            got = batch_item_outcome(items[message["id"]])
            check(got == want, f"{what}: item {message['id']} expected {want}, "
                               f"got {got} ({items[message['id']]})")
        return items

    def as_live(model_outcomes: List[str]) -> List[str]:
        return [("translated" if outcome in ("translated", "shortcut", "cached")
                 else "refused:" + outcome) for outcome in model_outcomes]

    probe = translate(english[0])
    if probe.status == 503:
        assert_error(probe, 503, "TRANSLATION_UNAVAILABLE", "keyless probe")
        provider_backed = False
    else:
        check(probe.status == 200, f"probe expected 200/503, got {probe}")
        check(probe.json()["cached"] is False, "probe must be a fresh generation")
        provider_backed = True

    # revoke_after=0: the revoke predates the batch, so every charged item is
    # refused before the provider is called and the shortcut ignores the revoke.
    revoked_expected = as_live(
        simulate_revoke_mid_batch(["charged"] * 3 + ["shortcut"], revoke_after=0))
    try:
        ctx.set_consent(ASKER_PHONE, False)
        check_batch(batch(), revoked_expected, "revoked batch")
        assert_error(translate(english[1]), 403, "AI_CONSENT_REQUIRED",
                     "single translate under a revoked consent")
        if provider_backed:
            # Pipeline order (ai-translation.md): the cache read precedes the
            # consent gate, so the pre-revoke generation stays replayable
            # without any provider call.
            replay = translate(english[0])
            check(replay.status == 200 and replay.json()["cached"] is True,
                  f"cached row must not need consent: {replay}")
    finally:
        ctx.set_consent(ASKER_PHONE, True)

    # Re-grant: the provider lane is back and the refused round left no rows.
    if provider_backed:
        restored_expected = as_live(
            simulate_revoke_mid_batch(["charged"] * 3 + ["shortcut"], revoke_after=3))
        items = check_batch(batch(), restored_expected, "re-granted batch")
        check(items[english[0]["id"]]["cached"] is True,
              "re-granted replay of the pre-revoke generation")
        check(items[english[1]["id"]]["cached"] is False,
              "the revoked round leaked a cache row into the re-granted batch")
    else:
        restored_expected = ["refused:TRANSLATION_UNAVAILABLE"] * 3 + ["translated"]
        check_batch(batch(), restored_expected, "re-granted keyless batch")


def scenario_ask_rate_limit(ctx: Context) -> None:
    """A dedicated non-member burns only its own bucket: the limiter runs before membership."""
    path = f"/api/v1/conversations/{ctx.conversation_id()}/ask"
    token = ctx.token(LIMIT_PHONE)
    statuses: List[int] = []
    limited = None
    for _ in range(ASK_RATE_PER_MINUTE + 1):
        response = ctx.client.post(path, token=token, body=ask_body("Anything new?"))
        statuses.append(response.status)
        if response.status == 429:
            limited = response
            break
        assert_error(response, 403, "NOT_MEMBER", "non-member ask before the limit")
    check(limited is not None,
          f"{len(statuses)} asks in a burst never returned 429 (limit {ASK_RATE_PER_MINUTE}/min)")
    assert_error(limited, 429, "RATE_LIMITED", "ask over the per-minute limit")
    # The asker's own bucket is unaffected.
    ok = ctx.client.post(path, token=ctx.token(ASKER_PHONE), body=ask_body("Anything new?"))
    check(ok.status in (200, 429), f"asker ask after another user's limit: {ok}")
    check(ok.status == 200 or ok.error_code() == "RATE_LIMITED", "asker ask error code")


SCENARIOS: List[Tuple[str, Callable[[Context], None]]] = [
    ("ask_cites_and_caches", scenario_ask_cites_and_caches),
    ("rag_stats_counters", scenario_rag_stats_counters),
    ("ask_authz_validation", scenario_ask_authz_validation),
    ("ai_opt_out_excludes_peer", scenario_ai_opt_out_excludes_peer),
    ("summary_purge_on_opt_out", scenario_summary_purge_on_opt_out),
    ("edit_recall_invalidates_cache", scenario_edit_recall_invalidates_cache),
    ("translate_contract", scenario_translate_contract),
    ("translate_refund_on_provider_failure", scenario_translate_refund_on_provider_failure),
    ("revoke_consent_mid_batch", scenario_revoke_consent_mid_batch),
    ("ask_rate_limit", scenario_ask_rate_limit),
]


def main(argv: Optional[Sequence[str]] = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    args = parse_args(argv)
    names = [name for name, _ in SCENARIOS]
    if args.list:
        print("\n".join(names))
        return 0
    try:
        selected = select_scenarios(names, args.only)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2
    ctx = Context(Client(args.base_url))
    ctx.assume_pre_138 = args.assume_pre_138
    failed = 0
    for name, fn in SCENARIOS:
        if name not in selected:
            continue
        try:
            fn(ctx)
            print(f"PASS {name}")
        except NotRun as reason:
            print(f"NOT_RUN {name}: {reason}")
        except E2EFailure as failure:
            failed += 1
            print(f"FAIL {name}: {failure}")
        except Exception as error:  # noqa: BLE001 - report, keep running the rest
            failed += 1
            print(f"FAIL {name}: {type(error).__name__}: {error}")
    return 1 if failed else 0


if __name__ == "__main__":
    main_guard(main)
