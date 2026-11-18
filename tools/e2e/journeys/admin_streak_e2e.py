#!/usr/bin/env python3
"""Live journey: admin console API + chat-streak same-day rules.

Fixture accounts live in +849000008xx (display names "Admin E2E ..."); the
admin fixture is +84900000890, which the isolated backend lists in
NEXACHAT_ADMIN_PHONES. Demo seed users are never touched and tokens are never
printed.

Contracts: docs/architecture/admin-console.md, docs/architecture/streaks.md,
server/src/main/java/com/nexachat/admin/**, streak/**, user/UserSettings*.

Usage:
    python tools/e2e/journeys/admin_streak_e2e.py [--base-url http://127.0.0.1:8094]
    python tools/e2e/journeys/admin_streak_e2e.py --list
    python tools/e2e/journeys/admin_streak_e2e.py --only admin_authz_matrix,streak_same_day
"""

from __future__ import annotations

import argparse
import itertools
import json
import sys
import time
import uuid
from pathlib import Path
from typing import Callable, Dict, List, Optional, Sequence, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from e2e_common import (  # noqa: E402
    PASSWORD,
    Client,
    E2EFailure,
    _cached_session,
    _store_session,
    check,
    direct_conversation,
    ensure_user,
    main_guard,
    send_text,
)

DEFAULT_BASE_URL = "http://127.0.0.1:8094"
TAG = "Admin E2E"

ADMIN_PHONE = "+84900000890"
PLAIN_PHONE = "+84900000801"   # ordinary account: non-admin probes + alert sender
TARGET_PHONE = "+84900000802"  # suspend/unsuspend target
STREAK_POOL = ("+84900000811", "+84900000812", "+84900000813", "+84900000814")

NAMES = {
    ADMIN_PHONE: TAG + " Admin",
    PLAIN_PHONE: TAG + " Plain",
    TARGET_PHONE: TAG + " Target",
    STREAK_POOL[0]: TAG + " Streak 1",
    STREAK_POOL[1]: TAG + " Streak 2",
    STREAK_POOL[2]: TAG + " Streak 3",
    STREAK_POOL[3]: TAG + " Streak 4",
}

# Every read-only admin route: path -> validator name (see validate_admin_get).
ADMIN_GET_ENDPOINTS = (
    "/api/v1/admin/me",
    "/api/v1/admin/users",
    "/api/v1/admin/users/deleted",
    "/api/v1/admin/alerts",
    "/api/v1/admin/audit",
    "/api/v1/admin/rules",
    "/api/v1/admin/overview",
    "/api/v1/admin/system/health",
)

# Keys that must never appear anywhere in an admin user payload.
FORBIDDEN_KEYS = frozenset({
    "password", "passwordhash", "password_hash", "hash", "accesstoken", "refreshtoken",
    "token", "tokenhash", "tokens", "secret", "otp", "tokensvalidafter",
})

# Substrings that must never appear in the system-health payload.
SECRET_MARKERS = ("password", "secret", "jdbc:", "jwt_", "bearer ", "apikey", "api_key")

ALERT_KEYWORD = "wire transfer"  # built-in SCAM_PHISHING rule (V36 seed)
REVEAL_CAP_ATTEMPTS = 11  # default nexachat.admin.reveal.per-minute is 10


class NotRun(Exception):
    """Scenario prerequisite missing on this stack."""


# --------------------------------------------------------------------------
# Pure helpers (unit-tested offline in test_admin_streak_e2e.py)
# --------------------------------------------------------------------------

def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Admin console + streak live journey")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--list", action="store_true", help="print scenario names and exit")
    parser.add_argument("--only", default="", help="comma-separated scenario names")
    return parser.parse_args(argv)


def select_scenarios(all_names: Sequence[str], only: str) -> List[str]:
    if not only or not only.strip():
        return list(all_names)
    wanted = [name.strip() for name in only.split(",") if name.strip()]
    unknown = [name for name in wanted if name not in all_names]
    if unknown:
        raise ValueError("unknown scenario(s): " + ", ".join(unknown))
    return [name for name in all_names if name in wanted]


def find_forbidden_keys(payload, forbidden=FORBIDDEN_KEYS, path: str = "") -> List[str]:
    """Returns json paths of keys whose normalized name is a credential field."""
    found: List[str] = []
    if isinstance(payload, dict):
        for key, value in payload.items():
            here = f"{path}.{key}" if path else str(key)
            if str(key).lower().replace("-", "") in forbidden:
                found.append(here)
            found.extend(find_forbidden_keys(value, forbidden, here))
    elif isinstance(payload, list):
        for index, value in enumerate(payload):
            found.extend(find_forbidden_keys(value, forbidden, f"{path}[{index}]"))
    return found


def is_page(payload) -> bool:
    """AdminAuditService.Page: {items[], page, size, total, hasMore}."""
    return (
        isinstance(payload, dict)
        and isinstance(payload.get("items"), list)
        and isinstance(payload.get("page"), int)
        and isinstance(payload.get("size"), int)
        and isinstance(payload.get("total"), int)
        and isinstance(payload.get("hasMore"), bool)
    )


def validate_admin_get(path: str, payload) -> Optional[str]:
    """Returns an error string when the 200 body does not match the documented shape."""
    if path == "/api/v1/admin/me":
        if not isinstance(payload, dict) or payload.get("role") not in ("ADMIN", "MODERATOR"):
            return "me.role is not a staff role"
        if not payload.get("userId"):
            return "me.userId missing"
        return None
    if path in ("/api/v1/admin/users", "/api/v1/admin/alerts", "/api/v1/admin/audit"):
        return None if is_page(payload) else "not a Page{items,page,size,total,hasMore}"
    if path in ("/api/v1/admin/users/deleted", "/api/v1/admin/rules"):
        return None if isinstance(payload, list) else "expected a JSON array"
    if path == "/api/v1/admin/overview":
        if not isinstance(payload, dict):
            return "overview not an object"
        for key in ("kpis", "days", "series", "generatedAt"):
            if key not in payload:
                return f"overview.{key} missing"
        if not isinstance(payload["series"], list):
            return "overview.series not a list"
        return None
    if path == "/api/v1/admin/system/health":
        return validate_system_health(payload)
    return "no validator for " + path


def validate_system_health(payload) -> Optional[str]:
    if not isinstance(payload, dict):
        return "health not an object"
    if payload.get("status") not in ("UP", "DEGRADED"):
        return f"health.status unexpected: {payload.get('status')!r}"
    if not isinstance(payload.get("uptimeSeconds"), int) or not payload.get("checkedAt"):
        return "health.uptimeSeconds/checkedAt missing"
    services = payload.get("services")
    if not isinstance(services, list) or not services:
        return "health.services empty"
    names = {svc.get("name") for svc in services if isinstance(svc, dict)}
    for required in ("database", "flyway", "storage", "calls", "alert-pipeline"):
        if required not in names:
            return f"health.services lacks {required}"
    for svc in services:
        if svc.get("status") not in ("UP", "DOWN"):
            return f"service {svc.get('name')} status {svc.get('status')!r}"
    lowered = json.dumps(payload).lower()
    for marker in SECRET_MARKERS:
        if marker in lowered:
            return f"health payload contains secret marker {marker!r}"
    return None


def streak_pairs(pool: Sequence[str]) -> List[Tuple[str, str]]:
    return list(itertools.combinations(pool, 2))


def streak_untouched_today(view: dict) -> bool:
    """A pair usable for the one-sided check: neither side sent on the streak day."""
    return view.get("mySentToday") is False and view.get("peerSentToday") is False


def expected_counts_after_completion(before: dict) -> Tuple[int, ...]:
    """Allowed `count` once today completes, given the view before any send today.

    A never-completed chain becomes 1. A chain whose lastDay is earlier either
    continues (+1, consecutive or grace day) or lapses and restarts at 1.
    """
    if before.get("lastDay") is None:
        return (1,)
    return tuple(sorted({before.get("count", 0) + 1, 1}))


def audit_has(entries: Sequence[dict], action: str, target_id: str,
              reason_contains: Optional[str] = None) -> bool:
    for entry in entries:
        if entry.get("action") != action or str(entry.get("targetId")) != target_id:
            continue
        if reason_contains is None or reason_contains in str(entry.get("reason") or ""):
            return True
    return False


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
        self.admin_ok: Optional[bool] = None
        self.alert_id: Optional[str] = None
        self.alert_message: Optional[str] = None

    def session(self, phone: str) -> dict:
        if phone not in self.sessions:
            cached = _cached_session(self.client, phone)
            # Cache first: register is limited to 10 / 5 min per IP as well.
            self.sessions[phone] = cached or ensure_user(self.client, phone, NAMES[phone])
        return self.sessions[phone]

    def token(self, phone: str) -> str:
        return self.session(phone)["accessToken"]

    def user_id(self, phone: str) -> str:
        return str(self.session(phone)["user"]["id"])

    def forget(self, phone: str) -> None:
        self.sessions.pop(phone, None)

    def admin_token(self) -> str:
        """ADMIN token; raises NotRun when the stack does not promote the admin phone."""
        if self.admin_ok is None:
            response = self.client.get("/api/v1/admin/me", token=self.token(ADMIN_PHONE))
            if response.status != 200:
                # Promotion happens at session issue; a fresh login re-runs it.
                login = self.client.post(
                    "/api/v1/auth/login", body={"phone": ADMIN_PHONE, "password": PASSWORD})
                if login.status == 200:
                    data = login.json()
                    _store_session(ADMIN_PHONE, data["accessToken"], data["user"]["id"])
                    self.sessions[ADMIN_PHONE] = data
                    response = self.client.get("/api/v1/admin/me",
                                               token=self.token(ADMIN_PHONE))
            self.admin_ok = response.status == 200 and (response.json() or {}).get(
                "role") == "ADMIN"
        if not self.admin_ok:
            raise NotRun(f"{ADMIN_PHONE} is not ADMIN on this stack "
                         "(NEXACHAT_ADMIN_PHONES not configured?)")
        return self.token(ADMIN_PHONE)


def admin_get(ctx: Context, path: str, **params):
    response = ctx.client.get(path, token=ctx.admin_token(), params=params or None)
    check(response.status == 200, f"GET {path} expected 200, got {response}")
    return response.json()


def audit_entries(ctx: Context, action: str, target_id: str) -> List[dict]:
    page = admin_get(ctx, "/api/v1/admin/audit", action=action, targetId=target_id, size=100)
    check(is_page(page), "audit response is not a Page")
    return page["items"]


# --------------------------------------------------------------------------
# Scenarios
# --------------------------------------------------------------------------

def scenario_admin_authz_matrix(ctx: Context) -> None:
    admin = ctx.admin_token()
    plain = ctx.token(PLAIN_PHONE)
    for path in ADMIN_GET_ENDPOINTS:
        assert_error(ctx.client.get(path), 401, "UNAUTHORIZED", f"anonymous GET {path}")
        assert_error(ctx.client.get(path, token="not-a-jwt"), 401, "TOKEN_INVALID",
                     f"garbage token GET {path}")
        assert_error(ctx.client.get(path, token=plain), 403, "FORBIDDEN",
                     f"non-admin GET {path}")
        response = ctx.client.get(path, token=admin)
        check(response.status == 200, f"admin GET {path} expected 200, got {response}")
        problem = validate_admin_get(path, response.json())
        check(problem is None, f"admin GET {path}: {problem}")
    me = admin_get(ctx, "/api/v1/admin/me")
    check(me["role"] == "ADMIN", f"admin/me role {me['role']}")
    check(str(me["userId"]) == ctx.user_id(ADMIN_PHONE), "admin/me userId mismatch")
    # Non-admin cannot reach mutations either (filter chain, before the body is read).
    assert_error(ctx.client.post(f"/api/v1/admin/users/{ctx.user_id(TARGET_PHONE)}/suspend",
                                 token=plain, body={"reason": "x"}),
                 403, "FORBIDDEN", "non-admin suspend")
    assert_error(ctx.client.post("/api/v1/admin/rules", token=plain, body={}),
                 403, "FORBIDDEN", "non-admin rule create")


def scenario_admin_users_suspend_audit(ctx: Context) -> None:
    admin = ctx.admin_token()
    target_id = ctx.user_id(TARGET_PHONE)
    target_token = ctx.token(TARGET_PHONE)
    base = f"/api/v1/admin/users/{target_id}"
    # Self-heal if an aborted earlier run left the target suspended.
    ctx.client.post(base + "/unsuspend", token=admin, body={"reason": "e2e reset"})

    page = admin_get(ctx, "/api/v1/admin/users", search=TARGET_PHONE)
    check(is_page(page), "users list is not a Page")
    ids = [str(item.get("id")) for item in page["items"]]
    check(target_id in ids, "search by phone did not find the target fixture")
    by_name = admin_get(ctx, "/api/v1/admin/users", search=NAMES[TARGET_PHONE])
    check(target_id in [str(i.get("id")) for i in by_name["items"]],
          "search by display name did not find the target fixture")
    assert_error(ctx.client.get("/api/v1/admin/users", token=admin,
                                params={"status": "bogus"}),
                 400, "VALIDATION_FAILED", "unknown status filter")
    check(not find_forbidden_keys(page), f"user list leaks {find_forbidden_keys(page)}")

    detail = admin_get(ctx, base)
    leaks = find_forbidden_keys(detail)
    check(not leaks, f"user detail leaks credential fields: {leaks}")
    user = detail.get("user") or {}
    check(user.get("phone") == TARGET_PHONE, "detail phone mismatch")
    check(user.get("role") == "USER", f"target role {user.get('role')}")
    check(user.get("suspendedAt") is None, "target unexpectedly suspended before test")
    for counter in ("deviceCount", "sessionCount", "conversationCount", "alertCount"):
        check(isinstance(detail.get(counter), int), f"detail.{counter} missing")
    assert_error(ctx.client.get(f"/api/v1/admin/users/{uuid.uuid4()}", token=admin),
                 404, "USER_NOT_FOUND", "unknown user detail")

    # Negative mutations.
    assert_error(ctx.client.post(base + "/suspend", token=admin, body={"reason": " "}),
                 400, "VALIDATION_FAILED", "blank suspend reason")
    assert_error(ctx.client.post(f"/api/v1/admin/users/{ctx.user_id(ADMIN_PHONE)}/suspend",
                                 token=admin, body={"reason": "self"}),
                 403, "FORBIDDEN", "self suspend")

    reason = f"{TAG} suspend {ctx.nonce}"
    response = ctx.client.post(base + "/suspend", token=admin, body={"reason": reason})
    check(response.status == 204, f"suspend expected 204, got {response}")
    # The access-token cutoff is stamped at second resolution; a fresh token
    # minted inside the same second correctly fails closed with TOKEN_INVALID,
    # so the post-unsuspend login must wait for the next second boundary.
    suspend_second = int(time.time())
    try:
        assert_error(ctx.client.get("/api/v1/users/me", token=target_token),
                     403, "ACCOUNT_SUSPENDED", "suspended user's token")
        suspended = admin_get(ctx, base)["user"]
        check(suspended.get("suspendedAt") is not None, "suspendedAt not set")
        check(suspended.get("suspendedReason") == reason, "suspendedReason mismatch")
        listed = admin_get(ctx, "/api/v1/admin/users", search=TARGET_PHONE, status="suspended")
        check(target_id in [str(i.get("id")) for i in listed["items"]],
              "status=suspended filter does not list the target")
    finally:
        undo = ctx.client.post(base + "/unsuspend", token=admin,
                               body={"reason": f"{TAG} unsuspend {ctx.nonce}"})
        ctx.forget(TARGET_PHONE)  # suspension revoked every token of the target
    check(undo.status == 204, f"unsuspend expected 204, got {undo}")
    check(admin_get(ctx, base)["user"].get("suspendedAt") is None, "unsuspend did not clear")
    # Suspension stamped the access-token cutoff: the pre-suspend token stays dead.
    stale = ctx.client.get("/api/v1/users/me", token=target_token)
    check(stale.status in (401, 403), f"pre-suspension token after unsuspend: {stale}")
    # Unsuspend restores access: a fresh login must work at once (no stale
    # 'suspended' validity served from the JWT cache).
    while int(time.time()) <= suspend_second:
        time.sleep(0.05)
    relogin = ctx.client.post("/api/v1/auth/login",
                              body={"phone": TARGET_PHONE, "password": PASSWORD})
    check(relogin.status == 200, f"login after unsuspend expected 200, got {relogin}")
    fresh = relogin.json()
    _store_session(TARGET_PHONE, fresh["accessToken"], fresh["user"]["id"])
    ctx.sessions[TARGET_PHONE] = fresh

    check(audit_has(audit_entries(ctx, "USER_SUSPEND", target_id), "USER_SUSPEND",
                    target_id, ctx.nonce), "audit lacks USER_SUSPEND with this run's reason")
    check(audit_has(audit_entries(ctx, "USER_UNSUSPEND", target_id), "USER_UNSUSPEND",
                    target_id, ctx.nonce), "audit lacks USER_UNSUSPEND with this run's reason")
    entry = audit_entries(ctx, "USER_SUSPEND", target_id)[0]
    check(str(entry.get("actorId")) == ctx.user_id(ADMIN_PHONE), "audit actorId mismatch")
    check(entry.get("actorRole") == "ADMIN", "audit actorRole mismatch")
    check(TARGET_PHONE not in json.dumps(entry.get("detail")), "audit detail has unmasked phone")
    me = ctx.client.get("/api/v1/users/me", token=fresh["accessToken"])
    check(me.status == 200,
          f"fresh token right after unsuspend expected 200, got {me.status} "
          f"{me.error_code()} (validity cache not evicted on unsuspend?)")


def ensure_alert(ctx: Context) -> str:
    """Plain user sends a built-in-rule keyword to the admin; waits for the async scan."""
    if ctx.alert_id:
        return ctx.alert_id
    admin = ctx.admin_token()
    _, conversation = direct_conversation(ctx.client, ctx.token(PLAIN_PHONE), ADMIN_PHONE)
    content = f"{TAG} {ctx.nonce}: please do a {ALERT_KEYWORD} today"
    message = send_text(ctx.client, ctx.token(PLAIN_PHONE), conversation["id"], content)
    message_id = str(message["id"])
    deadline = time.time() + 30
    while time.time() < deadline:
        page = ctx.client.get("/api/v1/admin/alerts", token=admin,
                              params={"status": "OPEN", "category": "SCAM_PHISHING",
                                      "size": 100}).json()
        for item in page.get("items", []):
            if str(item.get("messageId")) == message_id:
                ctx.alert_id = str(item["id"])
                ctx.alert_message = message_id
                return ctx.alert_id
        time.sleep(1)
    raise E2EFailure("no SCAM_PHISHING alert for the keyword message within 30s "
                     "(alert pipeline disabled or dry-run?)")


def reveal(ctx: Context, alert_id: str, reason: str, token: Optional[str] = None):
    return ctx.client.post(f"/api/v1/admin/alerts/{alert_id}/reveal",
                           token=token or ctx.admin_token(), body={"reason": reason})


def scenario_alerts_and_rules(ctx: Context) -> None:
    admin = ctx.admin_token()
    plain = ctx.token(PLAIN_PHONE)

    # --- alert produced by the built-in rule
    alert_id = ensure_alert(ctx)
    detail = admin_get(ctx, f"/api/v1/admin/alerts/{alert_id}")
    alert = detail["alert"]
    check(alert["category"] == "SCAM_PHISHING", f"alert category {alert['category']}")
    check(str(alert["senderId"]) == ctx.user_id(PLAIN_PHONE), "alert senderId mismatch")
    check(any("wire transfer" in name for name in alert.get("ruleNames", [])),
          f"alert ruleNames {alert.get('ruleNames')}")
    check(detail.get("senderDisplayName") == NAMES[PLAIN_PHONE], "senderDisplayName mismatch")
    assert_error(ctx.client.get(f"/api/v1/admin/alerts/{alert_id}", token=plain),
                 403, "FORBIDDEN", "non-admin alert detail")
    assert_error(ctx.client.get(f"/api/v1/admin/alerts/{uuid.uuid4()}", token=admin),
                 404, "ALERT_NOT_FOUND", "unknown alert")

    # --- reveal returns the plaintext window (one budget token)
    response = reveal(ctx, alert_id, f"{TAG} triage {ctx.nonce}")
    if response.status == 429:
        time.sleep(61)  # an earlier run drained this actor's sliding minute
        response = reveal(ctx, alert_id, f"{TAG} triage {ctx.nonce}")
    check(response.status == 200, f"reveal expected 200, got {response}")
    revealed = response.json()
    check(str(revealed["alertId"]) == alert_id, "reveal alertId mismatch")
    check(any(str(m["id"]) == ctx.alert_message and ctx.nonce in m["content"]
              for m in revealed["messages"]), "reveal window lacks the flagged message")

    # --- triage: note + dismiss, both audited
    note = ctx.client.post(f"/api/v1/admin/alerts/{alert_id}/notes", token=admin,
                           body={"body": f"{TAG} note {ctx.nonce}"})
    check(note.status == 201, f"note expected 201, got {note}")
    patched = ctx.client.patch(f"/api/v1/admin/alerts/{alert_id}", token=admin,
                               body={"status": "DISMISSED", "reason": f"{TAG} {ctx.nonce}"})
    check(patched.status == 200, f"dismiss expected 200, got {patched}")
    check(patched.json()["alert"]["status"] == "DISMISSED", "alert not DISMISSED")
    check(any(ctx.nonce in str(n.get("body")) for n in patched.json().get("notes", [])),
          "alert detail lacks the note")
    assert_error(ctx.client.patch(f"/api/v1/admin/alerts/{alert_id}", token=admin,
                                  body={"status": "BOGUS"}),
                 400, "VALIDATION_FAILED", "unknown alert status")
    check(audit_has(audit_entries(ctx, "ALERT_UPDATE", alert_id), "ALERT_UPDATE", alert_id),
          "audit lacks ALERT_UPDATE")
    check(audit_has(audit_entries(ctx, "ALERT_REVEAL", alert_id), "ALERT_REVEAL", alert_id,
                    ctx.nonce), "audit lacks ALERT_REVEAL")

    # --- rules CRUD on a custom rule (cleaned up)
    rules = admin_get(ctx, "/api/v1/admin/rules")
    built_in = [r for r in rules if r.get("builtIn")]
    check(built_in, "no built-in rules listed")
    keyword = f"adminE2E{ctx.nonce}"
    body = {"name": f"{TAG} rule {ctx.nonce}", "category": "CUSTOM", "severity": "LOW",
            "patternType": "KEYWORD", "pattern": keyword, "locale": "any",
            "description": "temporary journey rule"}
    assert_error(ctx.client.post("/api/v1/admin/rules", token=admin,
                                 body=dict(body, category="NOPE")),
                 400, "VALIDATION_FAILED", "unknown category")
    assert_error(ctx.client.post("/api/v1/admin/rules", token=admin,
                                 body=dict(body, patternType="GLOB")),
                 400, "VALIDATION_FAILED", "unknown pattern type")
    created = ctx.client.post("/api/v1/admin/rules", token=admin, body=body)
    check(created.status == 201, f"rule create expected 201, got {created}")
    rule = created.json()
    rule_id = str(rule["id"])
    try:
        check(rule["builtIn"] is False and rule["enabled"] is True, "new rule flags")
        check(rule["pattern"] == keyword and rule["category"] == "CUSTOM", "new rule fields")
        check(rule_id in [str(r["id"]) for r in admin_get(ctx, "/api/v1/admin/rules")],
              "created rule not listed")
        probe = ctx.client.post("/api/v1/admin/rules/test", token=admin,
                                body={"patternType": "KEYWORD", "pattern": keyword,
                                      "sampleText": f"hello {keyword} there"})
        check(probe.status == 200 and probe.json().get("matched") is True,
              f"rule test expected matched=true, got {probe}")
        miss = ctx.client.post("/api/v1/admin/rules/test", token=admin,
                               body={"patternType": "KEYWORD", "pattern": keyword,
                                     "sampleText": "nothing here"})
        check(miss.status == 200 and miss.json().get("matched") is False,
              f"rule test expected matched=false, got {miss}")
        updated = ctx.client.patch(f"/api/v1/admin/rules/{rule_id}", token=admin,
                                   body={"severity": "HIGH", "enabled": False})
        check(updated.status == 200, f"rule update expected 200, got {updated}")
        check(updated.json()["severity"] == "HIGH" and updated.json()["enabled"] is False,
              "rule update not applied")
        check(audit_has(audit_entries(ctx, "RULE_CREATE", rule_id), "RULE_CREATE", rule_id),
              "audit lacks RULE_CREATE")
        check(audit_has(audit_entries(ctx, "RULE_UPDATE", rule_id), "RULE_UPDATE", rule_id),
              "audit lacks RULE_UPDATE")
    finally:
        deleted = ctx.client.delete(f"/api/v1/admin/rules/{rule_id}", token=admin)
    check(deleted.status == 204, f"rule delete expected 204, got {deleted}")
    assert_error(ctx.client.delete(f"/api/v1/admin/rules/{rule_id}", token=admin),
                 404, "ALERT_RULE_NOT_FOUND", "second delete")
    check(rule_id not in [str(r["id"]) for r in admin_get(ctx, "/api/v1/admin/rules")],
          "deleted rule still listed")
    check(audit_has(audit_entries(ctx, "RULE_DELETE", rule_id), "RULE_DELETE", rule_id),
          "audit lacks RULE_DELETE")
    builtin_id = str(built_in[0]["id"])
    assert_error(ctx.client.delete(f"/api/v1/admin/rules/{builtin_id}", token=admin),
                 409, "RULE_IN_USE", "delete built-in rule")
    assert_error(ctx.client.patch(f"/api/v1/admin/rules/{builtin_id}", token=admin,
                                  body={"name": "renamed"}),
                 400, "VALIDATION_FAILED", "rename built-in rule")


def scenario_admin_reveal_cap(ctx: Context) -> None:
    alert_id = ensure_alert(ctx)
    assert_error(reveal(ctx, alert_id, " "), 400, "VALIDATION_FAILED", "blank reveal reason")
    assert_error(reveal(ctx, alert_id, "x", token=ctx.token(PLAIN_PHONE)),
                 403, "FORBIDDEN", "non-admin reveal")
    assert_error(reveal(ctx, str(uuid.uuid4()), "x"), 404, "ALERT_NOT_FOUND",
                 "reveal unknown alert")
    statuses: List[int] = []
    for attempt in range(REVEAL_CAP_ATTEMPTS):
        response = reveal(ctx, alert_id, f"{TAG} cap {ctx.nonce} #{attempt}")
        statuses.append(response.status)
        if response.status == 429:
            check(response.error_code() == "RATE_LIMITED",
                  f"cap error code {response.error_code()}")
            break
        check(response.status == 200, f"reveal #{attempt} expected 200/429, got {response}")
    check(429 in statuses, f"no 429 within {REVEAL_CAP_ATTEMPTS} reveals: {statuses}")
    check(statuses.count(200) <= 10, f"more than 10 reveals allowed per minute: {statuses}")
    denied = [e for e in audit_entries(ctx, "ALERT_REVEAL", alert_id)
              if (e.get("detail") or {}).get("denied") == "rate-limited"
              and ctx.nonce in str(e.get("reason") or "")]
    check(denied, "rate-limited reveal not audited")


def get_streak(ctx: Context, phone: str, conversation_id: str) -> dict:
    response = ctx.client.get(f"/api/v1/conversations/{conversation_id}/streak",
                              token=ctx.token(phone))
    check(response.status == 200, f"streak GET expected 200, got {response}")
    return response.json()


def pick_fresh_pair(ctx: Context) -> Tuple[str, str, str, dict]:
    for phone in STREAK_POOL:
        ctx.session(phone)  # peers must exist before a direct chat can be opened
    for first, second in streak_pairs(STREAK_POOL):
        _, conversation = direct_conversation(ctx.client, ctx.token(first), second)
        view = get_streak(ctx, first, conversation["id"])
        if streak_untouched_today(view):
            return first, second, conversation["id"], view
    raise NotRun("all streak fixture pairs already completed today "
                 "(more than %d same-day runs)" % len(streak_pairs(STREAK_POOL)))


def scenario_streak_same_day(ctx: Context) -> None:
    alice, bob, conversation_id, before = pick_fresh_pair(ctx)
    outsider = next(p for p in STREAK_POOL if p not in (alice, bob))
    check(before.get("openedToday") is None and "sentToday" not in before,
          "conversation streak carries check-in fields")
    check("lastDay" in before and "startedOn" in before, "lastDay/startedOn not always present")

    send_text(ctx.client, ctx.token(alice), conversation_id, f"{TAG} {ctx.nonce} hi")
    one_sided = get_streak(ctx, alice, conversation_id)
    check(one_sided["mySentToday"] is True and one_sided["peerSentToday"] is False,
          f"one-sided flags {one_sided}")
    check(one_sided["count"] == before["count"], "count moved before the peer sent")
    check(one_sided["lastDay"] == before["lastDay"], "lastDay moved before the peer sent")
    bob_view = get_streak(ctx, bob, conversation_id)
    check(bob_view["mySentToday"] is False and bob_view["peerSentToday"] is True,
          f"peer-side flags {bob_view}")

    send_text(ctx.client, ctx.token(bob), conversation_id, f"{TAG} {ctx.nonce} hey")
    done = get_streak(ctx, alice, conversation_id)
    allowed = expected_counts_after_completion(before)
    check(done["count"] in allowed, f"count {done['count']} not in {allowed}")
    check(done["lastDay"] == done["day"], "lastDay is not today after both sent")
    check(done["mySentToday"] is True and done["peerSentToday"] is True, "both flags")
    check(done["bestCount"] >= done["count"], "bestCount below count")
    check(done["atRisk"] is False, "completed streak reported atRisk")

    for index in range(3):
        sender = alice if index % 2 == 0 else bob
        send_text(ctx.client, ctx.token(sender), conversation_id, f"{TAG} {ctx.nonce} r{index}")
    again = get_streak(ctx, bob, conversation_id)
    check(again["count"] == done["count"], "same-day repeats changed the count")
    check(again["lastDay"] == done["lastDay"], "same-day repeats changed lastDay")

    assert_error(ctx.client.get(f"/api/v1/conversations/{conversation_id}/streak",
                                token=ctx.token(outsider)),
                 403, "NOT_MEMBER", "outsider streak view")
    assert_error(ctx.client.get(f"/api/v1/conversations/{conversation_id}/streak"),
                 401, "UNAUTHORIZED", "anonymous streak view")

    # Check-in streak is caller-scoped and idempotent.
    first = ctx.client.post("/api/v1/streaks/check-in", token=ctx.token(alice))
    check(first.status == 200, f"check-in expected 200, got {first}")
    second = ctx.client.post("/api/v1/streaks/check-in", token=ctx.token(alice))
    check(second.status == 200, f"repeat check-in expected 200, got {second}")
    check_in = second.json()["checkIn"]
    check(check_in["openedToday"] is True and check_in["sentToday"] is True,
          f"check-in flags {check_in}")
    check(check_in["count"] == first.json()["checkIn"]["count"], "repeat check-in moved count")
    check("mySentToday" not in check_in, "check-in streak carries conversation fields")
    assert_error(ctx.client.get("/api/v1/streaks/me"), 401, "UNAUTHORIZED", "anonymous /me")


def scenario_streak_reminder_setting(ctx: Context) -> None:
    phone = STREAK_POOL[0]
    token = ctx.token(phone)
    original = ctx.client.get("/api/v1/users/me/settings", token=token)
    check(original.status == 200, f"settings GET expected 200, got {original}")
    initial = original.json()["streakReminders"]
    try:
        for value in (not initial, initial, True, False):
            patched = ctx.client.patch("/api/v1/users/me/settings", token=token,
                                       body={"streakReminders": value})
            check(patched.status == 200, f"settings PATCH expected 200, got {patched}")
            check(patched.json()["streakReminders"] is value, f"PATCH echo != {value}")
            check(ctx.client.get("/api/v1/users/me/settings",
                                 token=token).json()["streakReminders"] is value,
                  f"settings GET != {value}")
            me = ctx.client.get("/api/v1/streaks/me", token=token)
            check(me.status == 200 and me.json()["reminderEnabled"] is value,
                  f"streaks/me reminderEnabled != {value}")
        others = ctx.client.get("/api/v1/users/me/settings", token=token).json()
        check(others["showPresence"] == original.json()["showPresence"],
              "streak toggle changed showPresence")
    finally:
        ctx.client.patch("/api/v1/users/me/settings", token=token,
                         body={"streakReminders": initial})
    assert_error(ctx.client.patch("/api/v1/users/me/settings", token=token, body={}),
                 400, "VALIDATION_FAILED", "empty settings patch")
    assert_error(ctx.client.patch("/api/v1/users/me/settings", body={"streakReminders": True}),
                 401, "UNAUTHORIZED", "anonymous settings patch")


def scenario_admin_system_health(ctx: Context) -> None:
    health = admin_get(ctx, "/api/v1/admin/system/health")
    problem = validate_system_health(health)
    check(problem is None, problem or "")
    db = next(s for s in health["services"] if s["name"] == "database")
    check(db["status"] == "UP", f"database service {db}")
    flyway = next(s for s in health["services"] if s["name"] == "flyway")
    check(flyway["status"] == "UP" and "latest=" in flyway["detail"],
          f"flyway service {flyway}")
    overview = admin_get(ctx, "/api/v1/admin/overview", days=30)
    check(overview["days"] == 30 and len(overview["series"]) == 30, "overview 30-day series")
    fallback = admin_get(ctx, "/api/v1/admin/overview", days=13)
    check(fallback["days"] == 7, "overview did not clamp unsupported days to 7")
    assert_error(ctx.client.get("/api/v1/admin/system/health", token=ctx.token(PLAIN_PHONE)),
                 403, "FORBIDDEN", "non-admin system health")


SCENARIOS: List[Tuple[str, Callable[[Context], None]]] = [
    ("admin_authz_matrix", scenario_admin_authz_matrix),
    ("admin_users_suspend_audit", scenario_admin_users_suspend_audit),
    ("admin_alerts_and_rules", scenario_alerts_and_rules),
    ("admin_reveal_cap", scenario_admin_reveal_cap),
    ("streak_same_day", scenario_streak_same_day),
    ("streak_reminder_setting", scenario_streak_reminder_setting),
    ("admin_system_health", scenario_admin_system_health),
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
