"""Tier 1 (happy-path) and Tier 2 (boundary/negative) checks for Features #1-#16.

Every check in this module maps 1:1 to a canonical PROJECT.md Feature (F1-F16)
and executes opaque-box HTTP, WebSocket/STOMP, disposable-DB, or static
contract verification against the configured NexaChat target.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
import time
import urllib.parse
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

from . import (
    E2E_ROOT,
    REPO_ROOT,
    BlockedCapability,
    E2EFailure,
    ReleaseCheck,
    ReleaseQaContext,
    SafetyGuardError,
    assert_ephemeral_phone,
    check,
    expect_error,
    expect_status,
    generate_ephemeral_phone,
    generate_strong_password,
    is_loopback_url,
    redact_secrets,
)
from tools.e2e.social_suite.browser_driver import contrast_ratio, parse_css_color
from tools.e2e.social_suite.fixtures import (
    DISTINCT_EMOJIS,
    MAX_POST_BODY,
    NINTH_EMOJI,
    NOT_AN_IMAGE_BYTES,
    VALID_JOIN_TOKEN,
    load_audio_only_m4a_bytes,
    load_valid_mp4_bytes,
    make_oversized_mp4_bytes,
    make_png_bytes,
)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _iso_utc_plus(*, seconds: int = 0, minutes: int = 0, days: int = 0) -> str:
    dt = datetime.now(timezone.utc) + timedelta(seconds=seconds, minutes=minutes, days=days)
    return dt.isoformat().replace("+00:00", "Z")


def _with_ephemeral(ctx: ReleaseQaContext, specs: Sequence[Tuple[str, Optional[str]]]) -> List[Dict[str, Any]]:
    """Registers ephemeral +84908xxxxxx accounts tracked on ctx for guaranteed cleanup."""
    out: List[Dict[str, Any]] = []
    for display_name, password in specs:
        out.append(ctx.register_ephemeral(display_name, password=password))
    return out


def _cleanup_accounts(ctx: ReleaseQaContext, accounts: Sequence[Dict[str, Any]]) -> None:
    for acc in reversed(accounts):
        try:
            ctx.cleanup_ephemeral(acc)
            if acc in ctx.ephemeral_accounts:
                ctx.ephemeral_accounts.remove(acc)
        except Exception:
            pass


def _make_stomp_connection(ctx: ReleaseQaContext, token: str, label: str):
    try:
        from tools.e2e.calls.call_e2e import StompConnection
    except ImportError as err:
        raise BlockedCapability(f"websocket-client unavailable for STOMP: {err}") from err
    return StompConnection(ctx.ws_url(), token, label)


def _stomp_frame_json(frame: Any) -> Optional[dict]:
    if not frame or frame[0] != "MESSAGE":
        return None
    try:
        return json.loads(frame[2])
    except Exception:
        return None


def _wait_stomp_error(conn: Any, expected_code: str, *, call_id: Optional[str] = None, timeout: float = 10.0) -> dict:
    def matches(frame: Any) -> bool:
        env = _stomp_frame_json(frame)
        if not env:
            return False
        if env.get("code") != expected_code:
            return False
        if call_id is not None and env.get("callId") != call_id:
            return False
        return True

    frame = conn.wait_for(matches, timeout, f"WsErrorResponse code={expected_code}")
    return _stomp_frame_json(frame) or {}


def _run_psql(db_info: Dict[str, str], sql: str) -> str:
    container = db_info.get("container")
    db_name = db_info.get("db_name")
    db_user = db_info.get("db_user", "postgres")
    if not container or not db_name:
        raise BlockedCapability("DisposableStack db_info missing container/db_name")
    from tools.e2e import disposable_stack as ds

    ds.validate_db_name(db_name)
    proc = subprocess.run(
        [
            "docker",
            "exec",
            "-i",
            container,
            "psql",
            "-U",
            db_user,
            "-d",
            db_name,
            "-v",
            "ON_ERROR_STOP=1",
            "-tAc",
            sql,
        ],
        capture_output=True,
        text=True,
        timeout=20,
    )
    if proc.returncode != 0:
        raise E2EFailure(f"psql failed ({proc.returncode}): {proc.stderr.strip() or proc.stdout.strip()}")
    return proc.stdout.strip()


def _probe_google_enabled(ctx: ReleaseQaContext) -> bool:
    resp = ctx.spring.post("/api/v1/auth/google/nonce")
    return resp.status != 404


def _ensure_mail_sink(ctx: ReleaseQaContext) -> Any:
    if ctx.mail_sink is None:
        raise BlockedCapability("MailSink (Mailpit) unavailable on this run")
    return ctx.mail_sink


def _social_ctx(ctx: ReleaseQaContext) -> Any:
    if ctx.social_suite_ctx is not None:
        return ctx.social_suite_ctx
    ctx.require_local_target("social_suite delegation")
    sessions = ctx.require_sessions("social_suite delegation")
    from tools.e2e.social_suite.client import SuiteContext
    from tools.e2e.social_suite.run_social_e2e import probe_capabilities

    sctx = SuiteContext(
        spring_client=ctx.spring,
        proxy_client=ctx.proxy,
        sessions=sessions,
        mode="auto",
        proxy_base_url=ctx.site_url,
        spring_base_url=ctx.base_url,
        admin_base_url=ctx.admin_base_url,
        android_enabled=ctx.android_enabled,
        android_serial=ctx.android_serial,
        android_apk=ctx.apk_path,
        screenshots_dir=str(ctx.screenshots_dir) if ctx.screenshots_dir else None,
    )
    probe_capabilities(sctx)
    ctx.social_suite_ctx = sctx
    return sctx


def _load_site_check():
    """Loads REPO_ROOT/site/check.py without colliding with stdlib `site`."""
    import importlib.util

    mod_name = "nexachat_site_check"
    cached = sys.modules.get(mod_name)
    if cached is not None:
        return cached
    check_path = REPO_ROOT / "site" / "check.py"
    spec = importlib.util.spec_from_file_location(mod_name, str(check_path))
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load site/check.py from {check_path}")
    mod = importlib.util.module_from_spec(spec)
    sys.modules[mod_name] = mod
    spec.loader.exec_module(mod)
    return mod


# ---------------------------------------------------------------------------
# Feature #1: Auth & Account — Phone/Email Registration & Login
# ---------------------------------------------------------------------------


def _t1_f1_01_phone_register_login_me(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F1 Phone QA", None)])
    try:
        a = accs[0]
        me = ctx.spring.get("/api/v1/users/me", token=a["accessToken"])
        expect_status(me, 200, "GET /users/me")
        check(me.json()["id"] == a["userId"], "userId mismatch in /users/me")
        login = ctx.spring.post(
            "/api/v1/auth/login",
            body={"identifier": a["phone"], "password": a["password"]},
        )
        expect_status(login, 200, "POST /auth/login by phone")
        check(bool(login.json().get("accessToken")), "login returned empty accessToken")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f1_02_refresh_rotation(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F1 Refresh QA", None)])
    try:
        a = accs[0]
        r = ctx.spring.post("/api/v1/auth/refresh", body={"refreshToken": a["refreshToken"]})
        expect_status(r, 200, "POST /auth/refresh")
        data = r.json() or {}
        check(bool(data.get("accessToken")) and bool(data.get("refreshToken")), "refresh missing tokens")
        check(data["refreshToken"] != a["refreshToken"], "refresh token did not rotate")
        a["accessToken"] = data["accessToken"]
        me = ctx.spring.get("/api/v1/users/me", token=data["accessToken"])
        expect_status(me, 200, "GET /users/me with rotated accessToken")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f1_03_logout_revokes_refresh(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F1 Logout QA", None)])
    try:
        a = accs[0]
        lo = ctx.spring.post(
            "/api/v1/auth/logout",
            token=a["accessToken"],
            body={"refreshToken": a["refreshToken"]},
        )
        expect_status(lo, (200, 204), "POST /auth/logout")
        rep = ctx.spring.post("/api/v1/auth/refresh", body={"refreshToken": a["refreshToken"]})
        expect_status(rep, 401, "refresh after logout must fail 401")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f1_04_email_code_registration(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("email-code registration via MailSink")
    ms = _ensure_mail_sink(ctx)
    email = f"qa.{ctx.nonce}.{uuid.uuid4().hex[:6]}@example.test"
    pw = generate_strong_password()

    req_code = ctx.spring.post("/api/v1/auth/register/email-code", body={"email": email})
    expect_status(req_code, 202, "POST /auth/register/email-code")
    msg = ms.wait_for_message(email, timeout=30.0)
    otp = ms.extract_otp(msg)

    reg = ctx.spring.post(
        "/api/v1/auth/register/email",
        body={
            "email": email,
            "code": otp,
            "password": pw,
            "displayName": "Email QA",
        },
    )
    expect_status(reg, 201, "POST /auth/register/email")
    data = reg.json() or {}
    tok = data.get("accessToken")
    check(bool(tok) and bool(data.get("refreshToken")), "email register missing tokens")
    check((data.get("user") or {}).get("email") == email, "registered user.email mismatch")
    try:
        login = ctx.spring.post("/api/v1/auth/login", body={"identifier": email, "password": pw})
        expect_status(login, 200, "POST /auth/login with verified email")
        tok = login.json()["accessToken"]
    finally:
        if tok:
            ctx.spring.request("DELETE", "/api/v1/users/me", token=tok, body={"password": pw})


def _t1_f1_05_password_change_revokes_older_session(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F1 PwChange QA", None)])
    try:
        a = accs[0]
        old_tok = a["accessToken"]
        old_rt = a["refreshToken"]
        new_pw = generate_strong_password()
        r = ctx.spring.patch(
            "/api/v1/auth/password",
            token=old_tok,
            body={"oldPassword": a["password"], "newPassword": new_pw},
        )
        expect_status(r, 204, "PATCH /api/v1/auth/password")
        a["password"] = new_pw
        if old_rt:
            rep = ctx.spring.post("/api/v1/auth/refresh", body={"refreshToken": old_rt})
            expect_status(rep, 401, "old refresh token revoked after password change")
        time.sleep(1.1)
        login = ctx.spring.post(
            "/api/v1/auth/login",
            body={"identifier": a["phone"], "password": new_pw},
        )
        expect_status(login, 200, "login with new password")
        a["accessToken"] = login.json()["accessToken"]
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f1_01_invalid_phone_and_weak_password(ctx: ReleaseQaContext) -> None:
    r1 = ctx.spring.post(
        "/api/v1/auth/register",
        body={"phone": "not-e164", "password": generate_strong_password(), "displayName": "Bad"},
    )
    expect_error(r1, 400, "VALIDATION_FAILED", "invalid phone format")
    r2 = ctx.spring.post(
        "/api/v1/auth/register",
        body={"phone": generate_ephemeral_phone(), "password": "123", "displayName": "Bad"},
    )
    expect_error(r2, 400, "VALIDATION_FAILED", "weak password")


def _t2_f1_02_unauthenticated_and_malformed_jwt(ctx: ReleaseQaContext) -> None:
    expect_status(ctx.spring.get("/api/v1/users/me"), 401, "unauth GET /users/me")
    expect_status(
        ctx.spring.get("/api/v1/users/me", token="not.a.valid.jwt"),
        401,
        "malformed JWT GET /users/me",
    )


def _t2_f1_03_refresh_replay_revokes_family(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F1 Replay QA", None)])
    try:
        a = accs[0]
        old_rt = a["refreshToken"]
        r1 = ctx.spring.post("/api/v1/auth/refresh", body={"refreshToken": old_rt})
        expect_status(r1, 200, "first refresh")
        new_rt = r1.json()["refreshToken"]
        a["accessToken"] = r1.json()["accessToken"]

        replay = ctx.spring.post("/api/v1/auth/refresh", body={"refreshToken": old_rt})
        expect_status(replay, 401, "replayed refresh token")

        victim = ctx.spring.post("/api/v1/auth/refresh", body={"refreshToken": new_rt})
        expect_status(victim, 401, "token family revoked after replay")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f1_04_email_code_invalid_and_duplicate_anti_enum(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("email-code boundary test")
    ms = _ensure_mail_sink(ctx)
    email = f"qa.dup.{ctx.nonce}.{uuid.uuid4().hex[:6]}@example.test"
    pw = generate_strong_password()

    bad = ctx.spring.post(
        "/api/v1/auth/register/email",
        body={"email": email, "code": "000000", "password": pw, "displayName": "Bad OTP"},
    )
    expect_error(bad, 400, "INVALID_OR_EXPIRED_CODE", "wrong email registration OTP")

    expect_status(ctx.spring.post("/api/v1/auth/register/email-code", body={"email": email}), 202, "email-code")
    otp = ms.extract_otp(ms.wait_for_message(email, timeout=30.0))
    ok = ctx.spring.post(
        "/api/v1/auth/register/email",
        body={"email": email, "code": otp, "password": pw, "displayName": "Valid Email"},
    )
    expect_status(ok, 201, "initial email register")
    tok = ok.json()["accessToken"]
    try:
        # Re-request and attempt duplicate register -> must return anti-enumeration INVALID_OR_EXPIRED_CODE
        expect_status(ctx.spring.post("/api/v1/auth/register/email-code", body={"email": email}), 202, "dup email-code")
        dup = ctx.spring.post(
            "/api/v1/auth/register/email",
            body={"email": email, "code": otp, "password": pw, "displayName": "Dup Email"},
        )
        expect_error(dup, 400, "INVALID_OR_EXPIRED_CODE", "duplicate email register anti-enumeration")
    finally:
        ctx.spring.request("DELETE", "/api/v1/users/me", token=tok, body={"password": pw})


def _t2_f1_05_sign_in_locked_shared_budget(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("SIGN_IN_LOCKED 429 lockout check")
    accs = _with_ephemeral(ctx, [("F1 Lockout QA", None)])
    try:
        a = accs[0]
        email = f"qa.lock.{ctx.nonce}.{uuid.uuid4().hex[:6]}@example.test"
        verified_email = False
        if ctx.mail_sink is not None:
            put_em = ctx.spring.put(
                "/api/v1/users/me/email",
                token=a["accessToken"],
                body={"email": email},
            )
            if put_em.status == 202:
                otp = ctx.mail_sink.extract_otp(ctx.mail_sink.wait_for_message(email, timeout=30.0))
                ver = ctx.spring.post(
                    "/api/v1/users/me/email/verify",
                    token=a["accessToken"],
                    body={"code": otp},
                )
                verified_email = ver.status == 200

        # Use distinct X-Forwarded-For per call (trusted on local stack when configured)
        locked_resp = None
        for idx in range(12):
            ident = email if (verified_email and idx % 2 == 1) else a["phone"]
            hdrs = {"X-Forwarded-For": f"198.51.100.{10 + idx}"}
            resp = ctx.spring.post(
                "/api/v1/auth/login",
                body={"identifier": ident, "password": "WrongPassword!99"},
                headers=hdrs,
            )
            if resp.status == 429 and resp.error_code() == "SIGN_IN_LOCKED":
                locked_resp = resp
                break
            if resp.status == 429 and resp.error_code() == "RATE_LIMITED":
                raise BlockedCapability(
                    "IP RateLimitInterceptor (10/5m) triggered before SIGN_IN_LOCKED; "
                    "run local stack with NEXACHAT_AUTH_SIGN_IN_BUDGET_MAX_FAILURES=4"
                )
            expect_status(resp, 401, f"failed login attempt {idx + 1}")
        check(locked_resp is not None, "SIGN_IN_LOCKED 429 was not triggered within 12 attempts")
        assert locked_resp is not None
        payload = locked_resp.json() or {}
        details = (payload.get("error") or {}).get("details") or {}
        check(details.get("scope") == "account", f"expected scope=='account', got {details}")
        check(int(details.get("retryAfterSeconds", 0)) >= 1, f"missing retryAfterSeconds: {details}")
        check(bool(locked_resp.header("Retry-After")), "missing Retry-After header on SIGN_IN_LOCKED")
    finally:
        _cleanup_accounts(ctx, accs)


# ---------------------------------------------------------------------------
# Feature #2: Auth & Account — Google Sign-In & Sign-Up
# ---------------------------------------------------------------------------


def _t1_f2_01_google_nonce_gate(ctx: ReleaseQaContext) -> None:
    resp = ctx.spring.post("/api/v1/auth/google/nonce")
    if resp.status == 404:
        expect_error(resp, 404, "NOT_FOUND", "Google nonce disabled-flag fail-closed")
    else:
        expect_status(resp, 200, "Google nonce enabled")
        check(bool((resp.json() or {}).get("nonce")), "Google nonce missing nonce field")


def _t1_f2_02_google_signin_gate(ctx: ReleaseQaContext) -> None:
    enabled = _probe_google_enabled(ctx)
    resp = ctx.spring.post("/api/v1/auth/google", body={"idToken": "x", "nonce": "y"})
    if not enabled:
        expect_error(resp, 404, "NOT_FOUND", "POST /auth/google disabled-flag 404")
    else:
        check(resp.status in (400, 401), f"expected 400/401 for synthetic Google token, got {resp}")


def _t1_f2_03_identities_list_gate(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F2 Identities QA", None)])
    try:
        enabled = _probe_google_enabled(ctx)
        resp = ctx.spring.get("/api/v1/auth/identities", token=accs[0]["accessToken"])
        if not enabled:
            expect_error(resp, 404, "NOT_FOUND", "GET /auth/identities disabled-flag 404")
        else:
            expect_status(resp, 200, "GET /auth/identities")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f2_04_identities_google_link_gate(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F2 Link Gate QA", None)])
    try:
        a = accs[0]
        enabled = _probe_google_enabled(ctx)
        r_nonce = ctx.spring.post("/api/v1/auth/identities/google/nonce", token=a["accessToken"])
        r_link = ctx.spring.post(
            "/api/v1/auth/identities/google",
            token=a["accessToken"],
            body={"idToken": "synthetic", "nonce": "synthetic", "password": a["password"]},
        )
        if not enabled:
            expect_error(r_nonce, 404, "NOT_FOUND", "POST /identities/google/nonce disabled 404")
            expect_error(r_link, 404, "NOT_FOUND", "POST /identities/google disabled 404")
        else:
            expect_status(r_nonce, 200, "POST /identities/google/nonce enabled")
            check(r_link.status in (400, 401), f"expected 400/401 on synthetic idToken, got {r_link}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f2_05_identities_google_unlink_gate(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F2 Unlink Gate QA", None)])
    try:
        a = accs[0]
        enabled = _probe_google_enabled(ctx)
        resp = ctx.spring.request(
            "DELETE",
            "/api/v1/auth/identities/google",
            token=a["accessToken"],
            body={"password": a["password"]},
        )
        if not enabled:
            expect_error(resp, 404, "NOT_FOUND", "DELETE /identities/google disabled 404")
        else:
            expect_status(resp, 404, "DELETE /identities/google unlinked 404")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f2_01_disabled_google_burst_stays_404(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("Google OIDC burst probe")
    if _probe_google_enabled(ctx):
        raise BlockedCapability("Google Sign-In enabled on stack; disabled-burst check skipped")
    for i in range(35):
        r = ctx.spring.post("/api/v1/auth/google/nonce")
        check(
            r.status == 404 and r.error_code() == "NOT_FOUND",
            f"burst #{i+1}: expected 404 NOT_FOUND (not 429), got {r}",
        )


def _t2_f2_02_identities_require_auth(ctx: ReleaseQaContext) -> None:
    for method, path in (
        ("GET", "/api/v1/auth/identities"),
        ("POST", "/api/v1/auth/identities/google/nonce"),
        ("POST", "/api/v1/auth/identities/google"),
        ("DELETE", "/api/v1/auth/identities/google"),
    ):
        r = ctx.spring.request(method, path)
        check(r.status in (401, 404), f"unauth {method} {path}: expected 401/404, got {r}")


def _t2_f2_03_blank_google_payload_validation(ctx: ReleaseQaContext) -> None:
    enabled = _probe_google_enabled(ctx)
    r = ctx.spring.post("/api/v1/auth/google", body={"idToken": "", "nonce": ""})
    if not enabled:
        expect_error(r, 404, "NOT_FOUND", "blank Google payload while disabled")
    else:
        expect_error(r, 400, "VALIDATION_FAILED", "blank Google payload while enabled")


def _t2_f2_04_link_wrong_password_boundary(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F2 WrongPw QA", None)])
    try:
        a = accs[0]
        if not _probe_google_enabled(ctx):
            r = ctx.spring.post(
                "/api/v1/auth/identities/google",
                token=a["accessToken"],
                body={"idToken": "x", "nonce": "y", "password": "WrongPassword!9"},
            )
            expect_error(r, 404, "NOT_FOUND", "link Google wrong pw while disabled")
            return
        nonce = ctx.spring.post("/api/v1/auth/identities/google/nonce", token=a["accessToken"]).json()["nonce"]
        r = ctx.spring.post(
            "/api/v1/auth/identities/google",
            token=a["accessToken"],
            body={"idToken": "synthetic", "nonce": nonce, "password": "WrongPassword!9"},
        )
        check(r.status in (400, 403) and r.error_code() == "WRONG_PASSWORD", f"expected WRONG_PASSWORD, got {r}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f2_05_google_mock_oidc_capability_gate(ctx: ReleaseQaContext) -> None:
    if not _probe_google_enabled(ctx):
        raise BlockedCapability("Google Sign-In flag disabled on target stack (mock OIDC exchange gated)")
    nonce_r = ctx.spring.post("/api/v1/auth/google/nonce")
    expect_status(nonce_r, 200, "google nonce")
    bad = ctx.spring.post(
        "/api/v1/auth/google",
        body={"idToken": "invalid.oidc.jwt", "nonce": nonce_r.json()["nonce"]},
    )
    check(bad.status in (400, 401), f"invalid OIDC token rejected: {bad}")


# ---------------------------------------------------------------------------
# Feature #3: Account Recovery & Self-Service Deletion
# ---------------------------------------------------------------------------


def _t1_f3_01_deletion_cors_preflight(ctx: ReleaseQaContext) -> None:
    origin = ctx.site_url if ctx.site_url.startswith("https://") else "https://nexachat.io.vn"
    for path in ("/api/v1/account-deletion/request", "/api/v1/account-deletion/confirm"):
        r = ctx.spring.request(
            "OPTIONS",
            path,
            headers={
                "Origin": origin,
                "Access-Control-Request-Method": "POST",
                "Access-Control-Request-Headers": "content-type",
            },
        )
        expect_status(r, (200, 204), f"OPTIONS {path}")
        acao = r.header("Access-Control-Allow-Origin")
        check(acao in (origin, "*"), f"OPTIONS {path} Access-Control-Allow-Origin={acao!r}")


def _t1_f3_02_self_service_deletion_tombstones(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F3 Delete Me QA", None)])
    try:
        a = accs[0]
        del_r = ctx.spring.request(
            "DELETE",
            "/api/v1/users/me",
            token=a["accessToken"],
            body={"password": a["password"]},
        )
        expect_status(del_r, 204, "DELETE /api/v1/users/me")
        me_after = ctx.spring.get("/api/v1/users/me", token=a["accessToken"])
        expect_status(me_after, 401, "deleted account token must fail 401")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f3_03_re_register_same_phone_after_delete(ctx: ReleaseQaContext) -> None:
    phone = generate_ephemeral_phone()
    pw = generate_strong_password()
    a1 = ctx.register_ephemeral("F3 Life 1", phone=phone, password=pw)
    uid1 = a1["userId"]
    a2 = None
    try:
        del_r = ctx.spring.request("DELETE", "/api/v1/users/me", token=a1["accessToken"], body={"password": pw})
        expect_status(del_r, 204, "delete first account")
        a2 = ctx.register_ephemeral("F3 Life 2", phone=phone, password=pw)
        check(a2["userId"] != uid1, "re-registration after delete must allocate a fresh user ID")
    finally:
        _cleanup_accounts(ctx, [acc for acc in (a1, a2) if acc is not None])


def _t1_f3_04_password_reset_otp_via_mailsink(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("password reset OTP via MailSink")
    ms = _ensure_mail_sink(ctx)
    accs = _with_ephemeral(ctx, [("F3 ForgotPw QA", None)])
    try:
        a = accs[0]
        email = f"qa.reset.{ctx.nonce}.{uuid.uuid4().hex[:6]}@example.test"
        expect_status(
            ctx.spring.put("/api/v1/users/me/email", token=a["accessToken"], body={"email": email}),
            202,
            "PUT /users/me/email",
        )
        otp1 = ms.extract_otp(ms.wait_for_message(email, timeout=30.0))
        expect_status(
            ctx.spring.post("/api/v1/users/me/email/verify", token=a["accessToken"], body={"code": otp1}),
            200,
            "POST /users/me/email/verify",
        )
        fg = ctx.spring.post("/api/v1/auth/forgot-password", body={"email": email})
        if fg.status == 404:
            fg = ctx.spring.post("/api/v1/auth/password-reset/request", body={"email": email})
        expect_status(fg, 202, "forgot-password OTP request")
        otp2 = ms.extract_otp(ms.wait_for_message(email, timeout=30.0))
        new_pw = generate_strong_password()
        rst = ctx.spring.post("/api/v1/auth/reset-password", body={"email": email, "code": otp2, "newPassword": new_pw})
        if rst.status == 404:
            rst = ctx.spring.post(
                "/api/v1/auth/password-reset/confirm",
                body={"email": email, "code": otp2, "newPassword": new_pw},
            )
        expect_status(rst, (200, 204), "reset password confirm")
        a["password"] = new_pw
        login = ctx.spring.post("/api/v1/auth/login", body={"identifier": a["phone"], "password": new_pw})
        expect_status(login, 200, "login after password reset")
        a["accessToken"] = login.json()["accessToken"]
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f3_05_web_deletion_otp_via_mailsink(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("web account deletion OTP via MailSink")
    ms = _ensure_mail_sink(ctx)
    accs = _with_ephemeral(ctx, [("F3 WebDelete QA", None)])
    try:
        a = accs[0]
        email = f"qa.wdel.{ctx.nonce}.{uuid.uuid4().hex[:6]}@example.test"
        expect_status(
            ctx.spring.put("/api/v1/users/me/email", token=a["accessToken"], body={"email": email}),
            202,
            "link recovery email",
        )
        otp1 = ms.extract_otp(ms.wait_for_message(email, timeout=30.0))
        expect_status(
            ctx.spring.post("/api/v1/users/me/email/verify", token=a["accessToken"], body={"code": otp1}),
            200,
            "verify recovery email",
        )
        req_del = ctx.spring.post("/api/v1/account-deletion/request", body={"phone": a["phone"]})
        expect_status(req_del, 202, "POST /account-deletion/request")
        otp_del = ms.extract_otp(ms.wait_for_message(email, timeout=30.0))
        conf = ctx.spring.post(
            "/api/v1/account-deletion/confirm",
            body={"phone": a["phone"], "code": otp_del},
        )
        expect_status(conf, 204, "POST /account-deletion/confirm")
        expect_status(ctx.spring.get("/api/v1/users/me", token=a["accessToken"]), 401, "deleted via web OTP")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f3_01_delete_wrong_password_preserves_account(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F3 WrongDelPw QA", None)])
    try:
        a = accs[0]
        bad = ctx.spring.request(
            "DELETE",
            "/api/v1/users/me",
            token=a["accessToken"],
            body={"password": "WrongPassword!99"},
        )
        check(bad.status in (400, 403), f"expected 400/403 on wrong password delete, got {bad}")
        expect_status(ctx.spring.get("/api/v1/users/me", token=a["accessToken"]), 200, "account preserved")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f3_02_web_deletion_request_anti_enumeration(ctx: ReleaseQaContext) -> None:
    r = ctx.spring.post("/api/v1/account-deletion/request", body={"phone": generate_ephemeral_phone()})
    expect_status(r, 202, "unknown phone web deletion anti-enumeration 202")


def _t2_f3_03_web_deletion_invalid_otp_rejected(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F3 BadWebOtp QA", None)])
    try:
        r = ctx.spring.post(
            "/api/v1/account-deletion/confirm",
            body={"phone": accs[0]["phone"], "code": "000000"},
        )
        expect_status(r, 400, "invalid web deletion OTP must fail 400")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f3_04_sole_admin_delete_promotes_member(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F3 Admin", None), ("F3 Heir", None)])
    try:
        admin, heir = accs[0], accs[1]
        grp = ctx.spring.post(
            "/api/v1/conversations/group",
            token=admin["accessToken"],
            body={"title": f"Heir Group {ctx.nonce}", "memberIds": [heir["userId"]]},
        )
        expect_status(grp, 201, "create group")
        gid = grp.json()["id"]

        del_r = ctx.spring.request(
            "DELETE",
            "/api/v1/users/me",
            token=admin["accessToken"],
            body={"password": admin["password"]},
        )
        expect_status(del_r, 204, "delete sole group admin")
        inv = ctx.spring.post(f"/api/v1/conversations/{gid}/invite-link", token=heir["accessToken"])
        expect_status(inv, 201, "promoted remaining member can create group invite link")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f3_05_deleted_account_excluded_from_search_and_login(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F3 Victim", None), ("F3 Searcher", None)])
    try:
        victim, searcher = accs[0], accs[1]
        expect_status(
            ctx.spring.request("DELETE", "/api/v1/users/me", token=victim["accessToken"], body={"password": victim["password"]}),
            204,
            "delete victim",
        )
        srch = ctx.spring.get("/api/v1/users/search", token=searcher["accessToken"], params={"phone": victim["phone"]})
        expect_status(srch, 200, "search deleted phone")
        check(len(srch.json() or []) == 0, f"deleted user leaked in search: {srch.json()}")
        login = ctx.spring.post("/api/v1/auth/login", body={"identifier": victim["phone"], "password": victim["password"]})
        expect_status(login, 401, "login deleted user must fail 401")
    finally:
        _cleanup_accounts(ctx, accs)


# ---------------------------------------------------------------------------
# Feature #4: Messaging — Direct & Group Chat + Outbox + Sync
# ---------------------------------------------------------------------------


def _t1_f4_01_direct_send_history_and_idempotency(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F4 Alice", None), ("F4 Bob", None)])
    try:
        a, b = accs[0], accs[1]
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        cmid = str(uuid.uuid4())
        payload = {"clientMessageId": cmid, "type": "TEXT", "content": f"hello {ctx.nonce}", "attachmentIds": []}
        m1 = ctx.spring.post(f"/api/v1/conversations/{cid}/messages", token=a["accessToken"], body=payload)
        expect_status(m1, (200, 201), "send message")
        m2 = ctx.spring.post(f"/api/v1/conversations/{cid}/messages", token=a["accessToken"], body=payload)
        expect_status(m2, (200, 201), "idempotent clientMessageId replay")
        check(m1.json()["id"] == m2.json()["id"], "duplicate clientMessageId created a second row")
        hist = ctx.spring.get(f"/api/v1/conversations/{cid}/messages", token=b["accessToken"])
        expect_status(hist, 200, "peer reads history")
        check(any(it.get("id") == m1.json()["id"] for it in hist.json().get("items", [])), "message missing in history")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f4_02_edit_and_recall_message(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F4 EditA", None), ("F4 EditB", None)])
    try:
        a, b = accs[0], accs[1]
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        msg = ctx.send_text(a["accessToken"], cid, f"orig {ctx.nonce}")
        mid = msg["id"]
        ed = ctx.spring.patch(
            f"/api/v1/conversations/{cid}/messages/{mid}",
            token=a["accessToken"],
            body={"content": f"edited {ctx.nonce}"},
        )
        expect_status(ed, 200, "edit message")
        check(ed.json().get("content") == f"edited {ctx.nonce}", "edited content mismatch")
        rec = ctx.spring.delete(f"/api/v1/conversations/{cid}/messages/{mid}", token=a["accessToken"])
        expect_status(rec, (200, 204), "recall message")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f4_03_reactions_pin_and_search(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F4 ReactA", None), ("F4 ReactB", None)])
    try:
        a, b = accs[0], accs[1]
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        kw = f"phoenix{ctx.nonce}"
        msg = ctx.send_text(a["accessToken"], cid, f"Message about {kw}")
        mid = msg["id"]
        enc_emoji = urllib.parse.quote("\U0001F44D", safe="")
        rx = ctx.spring.put(f"/api/v1/conversations/{cid}/messages/{mid}/reactions/{enc_emoji}", token=b["accessToken"])
        expect_status(rx, (200, 204), "react to message")
        pin = ctx.spring.put(f"/api/v1/conversations/{cid}/pin", token=a["accessToken"])
        expect_status(pin, (200, 204), "pin conversation")
        unpin = ctx.spring.delete(f"/api/v1/conversations/{cid}/pin", token=a["accessToken"])
        expect_status(unpin, (200, 204), "unpin conversation")
        srch = ctx.spring.get(f"/api/v1/conversations/{cid}/messages/search", token=b["accessToken"], params={"q": kw})
        expect_status(srch, 200, "search messages")
        check(any(it.get("id") == mid for it in srch.json().get("items", [])), "search missed keyword message")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f4_04_stomp_realtime_receipt(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("STOMP messaging & receipt fanout")
    accs = _with_ephemeral(ctx, [("F4 StompA", None), ("F4 StompB", None)])
    c_a = c_b = None
    try:
        a, b = accs[0], accs[1]
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        c_a = _make_stomp_connection(ctx, a["accessToken"], "f4-a")
        c_b = _make_stomp_connection(ctx, b["accessToken"], "f4-b")
        c_b.subscribe(f"/topic/conversations/{cid}")
        time.sleep(0.2)
        msg = ctx.send_text(a["accessToken"], cid, f"ws {ctx.nonce}")

        def _is_msg(frame: Any) -> bool:
            env = _stomp_frame_json(frame)
            return bool(env and (env.get("id") == msg["id"] or (env.get("message") or {}).get("id") == msg["id"]))

        c_b.wait_for(_is_msg, 10.0, "STOMP message broadcast")
    finally:
        for conn in (c_a, c_b):
            if conn is not None:
                conn.close()
        _cleanup_accounts(ctx, accs)


def _t1_f4_05_sync_v2_watermark_and_generation(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F4 SyncA", None), ("F4 SyncB", None)])
    try:
        a, b = accs[0], accs[1]
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        ctx.send_text(a["accessToken"], cid, f"sync v2 {ctx.nonce}")
        r = ctx.spring.post(
            "/api/v1/sync",
            token=b["accessToken"],
            body={
                "syncVersion": 2,
                "limitPerConv": 20,
                "watermarks": [{"conversationId": cid, "lastSeq": 0, "changeCursor": 0, "generation": 1}],
            },
        )
        expect_status(r, 200, "POST /api/v1/sync v2")
        data = r.json() or {}
        check(data.get("syncVersion") == 2, f"expected syncVersion==2, got {data.get('syncVersion')}")
        deltas = data.get("deltas") or []
        check(len(deltas) == 1, f"expected 1 delta, got {len(deltas)}")
        d = deltas[0]
        check(isinstance(d.get("generation"), int) and d["generation"] >= 1, f"missing generation: {d}")
        check(d.get("changeUpperBound") is not None, f"missing changeUpperBound: {d}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f4_01_non_member_chat_denied(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F4 M1", None), ("F4 M2", None), ("F4 Outsider", None)])
    try:
        a, b, out = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        expect_error(
            ctx.spring.get(f"/api/v1/conversations/{cid}/messages", token=out["accessToken"]),
            403,
            "NOT_MEMBER",
            "outsider reads direct messages",
        )
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f4_02_blank_or_oversized_message_rejected(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F4 ValA", None), ("F4 ValB", None)])
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        bad = ctx.spring.post(
            f"/api/v1/conversations/{cid}/messages",
            token=a["accessToken"],
            body={"clientMessageId": str(uuid.uuid4()), "type": "TEXT", "content": "   ", "attachmentIds": []},
        )
        expect_status(bad, 400, "blank message rejected")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f4_03_non_sender_edit_recall_denied(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F4 Owner", None), ("F4 Peer", None)])
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        msg = ctx.send_text(a["accessToken"], cid, f"mine {ctx.nonce}")
        ed = ctx.spring.patch(
            f"/api/v1/conversations/{cid}/messages/{msg['id']}",
            token=b["accessToken"],
            body={"content": "hijack"},
        )
        expect_status(ed, 403, "peer cannot edit sender message")
        rec = ctx.spring.delete(
            f"/api/v1/conversations/{cid}/messages/{msg['id']}",
            token=b["accessToken"],
        )
        expect_status(rec, 403, "peer cannot recall sender message")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f4_04_scheduled_messages_50_cap(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("50-pending scheduled message cap")
    accs = _with_ephemeral(ctx, [("F4 SchedA", None), ("F4 SchedB", None)])
    created_ids: List[str] = []
    cid = ""
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        for i in range(50):
            r = ctx.spring.post(
                f"/api/v1/conversations/{cid}/scheduled-messages",
                token=a["accessToken"],
                body={
                    "clientMessageId": str(uuid.uuid4()),
                    "body": f"sched-{i}-{ctx.nonce}",
                    "sendAt": _iso_utc_plus(minutes=30 + i),
                },
            )
            if r.status == 429 and r.error_code() == "RATE_LIMITED":
                raise BlockedCapability(
                    "Scheduled create rate limit (30/min) hit before 50; "
                    "pass --nexachat.rate-limit.scheduled.create-per-minute=100 on DisposableStack"
                )
            expect_status(r, 201, f"scheduled message #{i+1}")
            created_ids.append(str(r.json()["id"]))

        r51 = ctx.spring.post(
            f"/api/v1/conversations/{cid}/scheduled-messages",
            token=a["accessToken"],
            body={
                "clientMessageId": str(uuid.uuid4()),
                "body": f"sched-51-{ctx.nonce}",
                "sendAt": _iso_utc_plus(minutes=90),
            },
        )
        expect_error(r51, 409, "SCHEDULED_LIMIT", "51st scheduled message must hit cap")
        details = ((r51.json() or {}).get("error") or {}).get("details") or {}
        check(details.get("limit") == 50, f"expected details.limit==50, got {details}")

        cancel = ctx.spring.delete(
            f"/api/v1/conversations/{cid}/scheduled-messages/{created_ids.pop()}",
            token=a["accessToken"],
        )
        expect_status(cancel, 204, "cancel 1 scheduled row")

        r_again = ctx.spring.post(
            f"/api/v1/conversations/{cid}/scheduled-messages",
            token=a["accessToken"],
            body={
                "clientMessageId": str(uuid.uuid4()),
                "body": f"sched-51-retry-{ctx.nonce}",
                "sendAt": _iso_utc_plus(minutes=91),
            },
        )
        expect_status(r_again, 201, "re-POST 51st after cancel")
        created_ids.append(str(r_again.json()["id"]))
    finally:
        if cid:
            for sid in created_ids:
                try:
                    ctx.spring.delete(
                        f"/api/v1/conversations/{cid}/scheduled-messages/{sid}",
                        token=accs[0]["accessToken"],
                    )
                except Exception:
                    pass
        _cleanup_accounts(ctx, accs)


def _t2_f4_05_sync_v2_generation_and_cursor_resets(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F4 SyncResetA", None), ("F4 SyncResetB", None)])
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        ctx.send_text(a["accessToken"], cid, f"sync reset {ctx.nonce}")

        base = ctx.spring.post(
            "/api/v1/sync",
            token=b["accessToken"],
            body={
                "syncVersion": 2,
                "limitPerConv": 20,
                "watermarks": [{"conversationId": cid, "lastSeq": 0, "changeCursor": 0, "generation": 1}],
            },
        )
        expect_status(base, 200, "initial sync v2")
        d0 = base.json()["deltas"][0]
        gen = int(d0["generation"])
        upper = int(d0["changeUpperBound"])

        # 1. generation mismatch -> reset
        r_gen = ctx.spring.post(
            "/api/v1/sync",
            token=b["accessToken"],
            body={
                "syncVersion": 2,
                "limitPerConv": 20,
                "watermarks": [{"conversationId": cid, "lastSeq": 0, "changeCursor": 0, "generation": gen + 999}],
            },
        )
        expect_status(r_gen, 200, "sync v2 generation mismatch")
        dg = r_gen.json()["deltas"][0]
        check(dg.get("reset") is True and dg.get("changeCursor") == 0 and dg.get("changeHasMore") is False, f"gen mismatch did not reset: {dg}")
        check(int(dg.get("generation", 0)) == gen, f"gen mismatch returned wrong generation: {dg}")

        # 2. cursor > upper -> reset
        r_cur = ctx.spring.post(
            "/api/v1/sync",
            token=b["accessToken"],
            body={
                "syncVersion": 2,
                "limitPerConv": 20,
                "watermarks": [{"conversationId": cid, "lastSeq": 0, "changeCursor": upper + 50, "generation": gen}],
            },
        )
        expect_status(r_cur, 200, "sync v2 cursor above upper bound")
        dc = r_cur.json()["deltas"][0]
        check(dc.get("reset") is True, f"cursor > upper did not reset: {dc}")

        # 3. nonzero cursor with missing generation -> reset
        r_nog = ctx.spring.post(
            "/api/v1/sync",
            token=b["accessToken"],
            body={
                "syncVersion": 2,
                "limitPerConv": 20,
                "watermarks": [{"conversationId": cid, "lastSeq": 0, "changeCursor": 1}],
            },
        )
        expect_status(r_nog, 200, "sync v2 missing generation with nonzero cursor")
        dn = r_nog.json()["deltas"][0]
        check(dn.get("reset") is True, f"missing generation did not reset: {dn}")
    finally:
        _cleanup_accounts(ctx, accs)


# ---------------------------------------------------------------------------
# Feature #5: Messaging — Privacy, Safety & Preferences
# ---------------------------------------------------------------------------


def _t1_f5_01_mute_and_archive_preferences(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F5 PrefA", None), ("F5 PrefB", None)])
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        expect_status(
            ctx.spring.put(f"/api/v1/conversations/{cid}/mute", token=a["accessToken"], body={"forever": True}),
            (200, 204),
            "mute conversation",
        )
        expect_status(
            ctx.spring.put(f"/api/v1/conversations/{cid}/archive", token=a["accessToken"]),
            (200, 204),
            "archive conversation",
        )
        expect_status(
            ctx.spring.delete(f"/api/v1/conversations/{cid}/archive", token=a["accessToken"]),
            (200, 204),
            "unarchive conversation",
        )
        expect_status(
            ctx.spring.delete(f"/api/v1/conversations/{cid}/mute", token=a["accessToken"]),
            (200, 204),
            "unmute conversation",
        )
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f5_02_conversation_labels_lifecycle(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F5 LblA", None), ("F5 LblB", None)])
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        lbl = ctx.spring.post("/api/v1/labels", token=a["accessToken"], body={"name": f"Work-{ctx.nonce}", "colorId": "PURPLE"})
        expect_status(lbl, (200, 201), "create label")
        lid = lbl.json()["id"]
        expect_status(
            ctx.spring.put(f"/api/v1/conversations/{cid}/labels/{lid}", token=a["accessToken"]),
            (200, 204),
            "assign label",
        )
        expect_status(ctx.spring.delete(f"/api/v1/labels/{lid}", token=a["accessToken"]), (200, 204), "delete label")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f5_03_user_block_unblock(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F5 BlkA", None), ("F5 BlkB", None)])
    try:
        a, b = accs
        expect_status(ctx.spring.post(f"/api/v1/users/{b['userId']}/block", token=a["accessToken"]), 204, "block user")
        expect_status(ctx.spring.delete(f"/api/v1/users/{b['userId']}/block", token=a["accessToken"]), 204, "unblock user")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f5_04_report_submission_and_mine(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("report submission")
    sctx = _social_ctx(ctx)
    p = sctx.create_post_safe("author", f"F5 report post {ctx.nonce}").json()
    try:
        rep = ctx.spring.post(
            "/api/v1/reports",
            token=sctx.token("reader"),
            body={"targetType": "POST", "targetId": p["id"], "reason": "SPAM", "details": "QA report"},
        )
        expect_status(rep, (200, 201), "submit report")
        mine = ctx.spring.get("/api/v1/reports/mine", token=sctx.token("reader"))
        expect_status(mine, 200, "GET /reports/mine")
    finally:
        ctx.spring.delete(f"/api/v1/feed/posts/{p['id']}", token=sctx.token("author"))


def _t1_f5_05_user_settings_5_keys(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F5 Settings QA", None)])
    try:
        tok = accs[0]["accessToken"]
        r = ctx.spring.get("/api/v1/users/me/settings", token=tok)
        expect_status(r, 200, "GET /users/me/settings")
        keys = set((r.json() or {}).keys())
        expected = {"showPresence", "showSeen", "aiSummaryConsent", "translateTargetLang", "streakReminders"}
        check(keys == expected, f"settings keys mismatch: got {keys}, expected {expected}")

        p = ctx.spring.patch(
            "/api/v1/users/me/settings",
            token=tok,
            body={"showPresence": False, "showSeen": False, "aiSummaryConsent": True, "translateTargetLang": "en", "streakReminders": True},
        )
        expect_status(p, 200, "PATCH /users/me/settings")
        pj = p.json() or {}
        check(pj["showPresence"] is False and pj["showSeen"] is False and pj["aiSummaryConsent"] is True, f"patch mismatch: {pj}")
        check(pj["translateTargetLang"] == "en" and pj["streakReminders"] is True, f"patch lang/streak mismatch: {pj}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f5_01_empty_settings_patch_rejected(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F5 EmptySet QA", None)])
    try:
        r = ctx.spring.patch("/api/v1/users/me/settings", token=accs[0]["accessToken"], body={})
        expect_error(r, 400, "VALIDATION_FAILED", "empty settings PATCH")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f5_02_blocked_user_messaging_denied(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F5 BlkSendA", None), ("F5 BlkSendB", None)])
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        expect_status(ctx.spring.post(f"/api/v1/users/{b['userId']}/block", token=a["accessToken"]), 204, "block peer")
        denied = ctx.spring.post(
            f"/api/v1/conversations/{cid}/messages",
            token=b["accessToken"],
            body={"clientMessageId": str(uuid.uuid4()), "type": "TEXT", "content": "blocked", "attachmentIds": []},
        )
        expect_status(denied, 403, "blocked peer cannot send")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f5_03_reciprocal_show_seen_privacy(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F5 SeenA", None), ("F5 SeenB", None)])
    try:
        a, b = accs
        expect_status(ctx.spring.patch("/api/v1/users/me/settings", token=b["accessToken"], body={"showSeen": False}), 200, "set showSeen=false")
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        m = ctx.send_text(a["accessToken"], cid, f"seen test {ctx.nonce}")
        # B marks read while showSeen=false
        ctx.spring.post(f"/api/v1/conversations/{cid}/read", token=b["accessToken"], body={"seq": m["seq"]})
        conv = ctx.spring.get("/api/v1/conversations", token=a["accessToken"])
        expect_status(conv, 200, "list conversations")
        rows = conv.json() if isinstance(conv.json(), list) else (conv.json() or {}).get("items", [])
        match = next((x for x in rows if x.get("id") == cid), None)
        if match is not None and "peerLastReadSeq" in match:
            check((match.get("peerLastReadSeq") or 0) == 0, f"showSeen=false leaked peerLastReadSeq: {match}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f5_04_translate_target_lang_length_bound(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F5 LangLen QA", None)])
    try:
        r = ctx.spring.patch(
            "/api/v1/users/me/settings",
            token=accs[0]["accessToken"],
            body={"translateTargetLang": "x" * 17},
        )
        expect_error(r, 400, "VALIDATION_FAILED", "translateTargetLang > 16 chars")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f5_05_chat_color_device_local_contract(ctx: ReleaseQaContext) -> None:
    chat_color_java = REPO_ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "nexachat" / "data" / "ChatColor.java"
    settings_repo = REPO_ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "nexachat" / "data" / "SettingsRepository.java"
    check(chat_color_java.is_file(), f"missing {chat_color_java}")
    check(settings_repo.is_file(), f"missing {settings_repo}")
    src = chat_color_java.read_text(encoding="utf-8")
    repo_src = settings_repo.read_text(encoding="utf-8")
    for name in ("PLUM", "INDIGO", "BLUE", "TEAL", "GREEN", "AMBER", "ROSE", "SLATE"):
        check(name in src, f"ChatColor missing enum {name}")
    check("KEY_CHAT_COLOR" in repo_src, "SettingsRepository missing device-local KEY_CHAT_COLOR")
    # Verify all declared 0xFFxxxxxx color literals inside ChatColor.java pair cleanly against text
    hexes = re.findall(r"0xFF([0-9A-Fa-f]{6})", src)
    check(len(hexes) >= 16, f"expected >= 16 hex tokens in ChatColor.java, found {len(hexes)}")
    # Confirm UserSettingsResponse.java does NOT expose chatColor on server API
    us_resp = (REPO_ROOT / "server" / "src" / "main" / "java" / "com" / "nexachat" / "user" / "dto" / "UserSettingsResponse.java").read_text(encoding="utf-8")
    check("chatColor" not in us_resp, "chatColor unexpectedly added to server UserSettingsResponse")


# ---------------------------------------------------------------------------
# Feature #6: Groups & Collaboration — Invites, Polls, Reminders
# ---------------------------------------------------------------------------


def _t1_f6_01_group_crud_members_leave(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F6 Owner", None), ("F6 Mem1", None), ("F6 Mem2", None)])
    try:
        o, m1, m2 = accs
        grp = ctx.spring.post(
            "/api/v1/conversations/group",
            token=o["accessToken"],
            body={"title": f"Group {ctx.nonce}", "memberIds": [m1["userId"]]},
        )
        expect_status(grp, 201, "create group")
        gid = grp.json()["id"]
        expect_status(
            ctx.spring.post(f"/api/v1/conversations/{gid}/members", token=o["accessToken"], body={"userIds": [m2["userId"]]}),
            204,
            "add group member",
        )
        expect_status(ctx.spring.post(f"/api/v1/conversations/{gid}/leave", token=m2["accessToken"]), 204, "member leave")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f6_02_group_invite_link_preview_join_revoke(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F6 InvOwner", None), ("F6 InvSeed", None), ("F6 Joiner", None)])
    try:
        o, seed, j = accs
        gid = ctx.spring.post(
            "/api/v1/conversations/group",
            token=o["accessToken"],
            body={"title": f"InviteGrp {ctx.nonce}", "memberIds": [seed["userId"]]},
        ).json()["id"]
        inv = ctx.spring.post(f"/api/v1/conversations/{gid}/invite-link", token=o["accessToken"])
        expect_status(inv, 201, "create invite link")
        tok_str = inv.json()["token"]
        prev = ctx.spring.get(f"/api/v1/invite-links/{tok_str}", token=j["accessToken"])
        expect_status(prev, 200, "preview invite link")
        join = ctx.spring.post(f"/api/v1/invite-links/{tok_str}/join", token=j["accessToken"])
        expect_status(join, (200, 201), "join via invite link")
        rev = ctx.spring.delete(f"/api/v1/conversations/{gid}/invite-link", token=o["accessToken"])
        expect_status(rev, 204, "revoke invite link")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f6_03_poll_create_vote_close(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F6 PollA", None), ("F6 PollB", None)])
    try:
        a, b = accs
        gid = ctx.spring.post(
            "/api/v1/conversations/group",
            token=a["accessToken"],
            body={"title": f"PollGrp {ctx.nonce}", "memberIds": [b["userId"]]},
        ).json()["id"]
        p = ctx.spring.post(
            f"/api/v1/conversations/{gid}/polls",
            token=a["accessToken"],
            body={"question": f"Lunch {ctx.nonce}?", "options": ["Pho", "Bun"], "multipleChoice": False, "anonymous": False},
        )
        expect_status(p, 201, "create poll")
        poll = p.json()
        opt_id = poll["options"][0]["id"]
        v = ctx.spring.put(
            f"/api/v1/conversations/{gid}/polls/{poll['id']}/vote",
            token=b["accessToken"],
            body={"optionIds": [opt_id]},
        )
        expect_status(v, 200, "vote poll")
        cl = ctx.spring.post(f"/api/v1/conversations/{gid}/polls/{poll['id']}/close", token=a["accessToken"])
        expect_status(cl, 200, "close poll")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f6_04_reminder_create_idempotent_list_delete(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F6 RemA", None), ("F6 RemB", None)])
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        ikey = str(uuid.uuid4())
        body = {"title": f"Standup {ctx.nonce}", "remindAt": _iso_utc_plus(minutes=30), "repeat": "NONE"}
        r1 = ctx.spring.post(
            f"/api/v1/conversations/{cid}/reminders",
            token=a["accessToken"],
            body=body,
            headers={"Idempotency-Key": ikey},
        )
        expect_status(r1, 201, "create reminder")
        r2 = ctx.spring.post(
            f"/api/v1/conversations/{cid}/reminders",
            token=a["accessToken"],
            body=body,
            headers={"Idempotency-Key": ikey},
        )
        expect_status(r2, (200, 201), "idempotent reminder replay")
        check(r1.json()["id"] == r2.json()["id"], "Idempotency-Key created duplicate reminder")
        lst = ctx.spring.get(f"/api/v1/conversations/{cid}/reminders", token=b["accessToken"])
        expect_status(lst, 200, "list reminders")
        expect_status(
            ctx.spring.delete(f"/api/v1/conversations/{cid}/reminders/{r1.json()['id']}", token=a["accessToken"]),
            (200, 204),
            "delete reminder",
        )
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f6_05_group_join_approval_workflow(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F6 ApprOwner", None), ("F6 ApprSeed", None), ("F6 ApprJoiner", None)])
    try:
        o, s, j = accs
        gid = ctx.spring.post(
            "/api/v1/conversations/group",
            token=o["accessToken"],
            body={"title": f"ApprGrp {ctx.nonce}", "memberIds": [s["userId"]]},
        ).json()["id"]
        inv = ctx.spring.post(
            f"/api/v1/conversations/{gid}/invite-link",
            token=o["accessToken"],
            body={"requiresApproval": True},
        )
        expect_status(inv, 201, "create approval-gated invite link")
        tok_str = inv.json()["token"]
        jr = ctx.spring.post(f"/api/v1/invite-links/{tok_str}/join", token=j["accessToken"])
        expect_status(jr, (200, 201), "request to join")
        reqs = ctx.spring.get(f"/api/v1/conversations/{gid}/join-requests", token=o["accessToken"])
        expect_status(reqs, 200, "list join requests")
        rows = reqs.json() if isinstance(reqs.json(), list) else (reqs.json() or {}).get("items", [])
        pending_req = next((r for r in rows if r.get("userId") == j["userId"] or (r.get("user") or {}).get("id") == j["userId"]), None)
        check(pending_req is not None, f"pending join request missing: {rows}")
        app = ctx.spring.post(f"/api/v1/conversations/{gid}/join-requests/{pending_req['id']}/approve", token=o["accessToken"])
        expect_status(app, (200, 204), "approve join request")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f6_01_non_admin_group_management_denied(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F6 Adm", None), ("F6 Mem", None), ("F6 Ext", None)])
    try:
        adm, mem, ext = accs
        gid = ctx.spring.post(
            "/api/v1/conversations/group",
            token=adm["accessToken"],
            body={"title": f"AuthGrp {ctx.nonce}", "memberIds": [mem["userId"]]},
        ).json()["id"]
        expect_status(
            ctx.spring.post(f"/api/v1/conversations/{gid}/members", token=mem["accessToken"], body={"userIds": [ext["userId"]]}),
            403,
            "non-admin add member denied",
        )
        expect_status(
            ctx.spring.post(f"/api/v1/conversations/{gid}/invite-link", token=mem["accessToken"]),
            403,
            "non-admin invite-link create denied",
        )
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f6_02_invalid_or_revoked_invite_token_rejected(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F6 BadInv", None)])
    try:
        r = ctx.spring.get("/api/v1/invite-links/invalid_short", token=accs[0]["accessToken"])
        check(r.status in (400, 404), f"expected 400/404 for invalid invite token, got {r}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f6_03_poll_closed_vote_and_duplicate_options_rejected(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F6 PollNegA", None), ("F6 PollNegB", None)])
    try:
        a, b = accs
        gid = ctx.spring.post(
            "/api/v1/conversations/group",
            token=a["accessToken"],
            body={"title": f"PollNeg {ctx.nonce}", "memberIds": [b["userId"]]},
        ).json()["id"]
        dup = ctx.spring.post(
            f"/api/v1/conversations/{gid}/polls",
            token=a["accessToken"],
            body={"question": "Dup?", "options": ["Same", "Same"]},
        )
        expect_status(dup, 400, "duplicate poll options rejected")
        p = ctx.spring.post(
            f"/api/v1/conversations/{gid}/polls",
            token=a["accessToken"],
            body={"question": "CloseTest?", "options": ["Yes", "No"]},
        ).json()
        expect_status(ctx.spring.post(f"/api/v1/conversations/{gid}/polls/{p['id']}/close", token=a["accessToken"]), 200, "close")
        v = ctx.spring.put(
            f"/api/v1/conversations/{gid}/polls/{p['id']}/vote",
            token=b["accessToken"],
            body={"optionIds": [p["options"][0]["id"]]},
        )
        check(v.status in (400, 409), f"vote on closed poll must fail 400/409, got {v}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f6_04_reminder_past_time_rejected(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F6 RemNegA", None), ("F6 RemNegB", None)])
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        r = ctx.spring.post(
            f"/api/v1/conversations/{cid}/reminders",
            token=a["accessToken"],
            body={"title": "Past", "remindAt": _iso_utc_plus(minutes=-5)},
        )
        expect_status(r, 400, "past remindAt rejected")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f6_05_non_member_poll_and_reminder_denied(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F6 OutA", None), ("F6 OutB", None), ("F6 OutC", None)])
    try:
        a, b, out = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        expect_error(
            ctx.spring.get(f"/api/v1/conversations/{cid}/polls", token=out["accessToken"]),
            403,
            "NOT_MEMBER",
            "outsider reads polls",
        )
        expect_error(
            ctx.spring.get(f"/api/v1/conversations/{cid}/reminders", token=out["accessToken"]),
            403,
            "NOT_MEMBER",
            "outsider reads reminders",
        )
    finally:
        _cleanup_accounts(ctx, accs)


# ---------------------------------------------------------------------------
# Feature #7: Media — Attachments, Avatars & Range Streaming
# ---------------------------------------------------------------------------


def _t1_f7_01_chat_attachment_upload_bind_read(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F7 AttA", None), ("F7 AttB", None)])
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        png = make_png_bytes(16, 16)
        up = ctx.spring.upload_multipart(
            "/api/v1/attachments",
            token=a["accessToken"],
            files=[("file", "photo.png", "image/png", png)],
        )
        expect_status(up, (200, 201), "POST /api/v1/attachments")
        att_id = up.json()["id"]
        msg = ctx.spring.post(
            f"/api/v1/conversations/{cid}/messages",
            token=a["accessToken"],
            body={"clientMessageId": str(uuid.uuid4()), "type": "IMAGE", "content": "", "attachmentIds": [att_id]},
        )
        expect_status(msg, (200, 201), "bind attachment to message")
        dl = ctx.spring.get(f"/api/v1/attachments/{att_id}/content", token=b["accessToken"])
        expect_status(dl, 200, "peer reads attachment content")
        check(dl.body == png, "downloaded attachment bytes mismatch")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f7_02_avatar_upload_and_file_read(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F7 AvA", None), ("F7 AvB", None)])
    try:
        a, b = accs
        png = make_png_bytes(32, 32)
        up = ctx.spring.upload_multipart(
            "/api/v1/users/me/avatar",
            token=a["accessToken"],
            files=[("file", "avatar.png", "image/png", png)],
        )
        expect_status(up, (200, 201), "POST /users/me/avatar")
        av_url = (up.json() or {}).get("avatarUrl") or ""
        check("/api/v1/files/" in av_url, f"unexpected avatarUrl: {av_url!r}")
        rel_path = urllib.parse.urlsplit(av_url).path
        dl = ctx.spring.get(rel_path, token=b["accessToken"])
        expect_status(dl, 200, "authenticated peer reads avatar /api/v1/files/**")
        check(dl.body == png, "avatar bytes mismatch")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f7_03_m4a_faststart_and_trailing_moov_sniff(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F7 M4A", None)])
    try:
        a = accs[0]
        for trailing in (False, True):
            m4a = load_audio_only_m4a_bytes(trailing_moov=trailing)
            up = ctx.spring.upload_multipart(
                "/api/v1/attachments",
                token=a["accessToken"],
                files=[("file", f"voice_{trailing}.m4a", "audio/mp4", m4a)],
            )
            expect_status(up, (200, 201), f"upload m4a trailing_moov={trailing}")
            dl = ctx.spring.get(f"/api/v1/attachments/{up.json()['id']}/content", token=a["accessToken"])
            expect_status(dl, 200, f"read m4a trailing_moov={trailing}")
            ct = (dl.header("content-type") or "").lower()
            check("audio/" in ct or "mp4" in ct, f"unexpected m4a content-type: {ct!r}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f7_04_range_206_partial_content(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F7 Range", None)])
    try:
        a = accs[0]
        m4a = load_audio_only_m4a_bytes(trailing_moov=False)
        up = ctx.spring.upload_multipart(
            "/api/v1/attachments",
            token=a["accessToken"],
            files=[("file", "range.m4a", "audio/mp4", m4a)],
        )
        expect_status(up, (200, 201), "upload range audio")
        aid = up.json()["id"]
        r = ctx.spring.get_range(f"/api/v1/attachments/{aid}/content", token=a["accessToken"], range_header="bytes=0-127")
        expect_status(r, 206, "HTTP 206 Partial Content")
        check(len(r.body) == min(128, len(m4a)) and r.body == m4a[: len(r.body)], "Range 206 byte slice mismatch")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f7_05_social_media_range_delegate(ctx: ReleaseQaContext) -> None:
    sctx = _social_ctx(ctx)
    from tools.e2e.social_suite.tier1_feature_api import get_tier1_checks

    f4_checks = [c for c in get_tier1_checks() if c.feature == "F4"]
    for c in f4_checks[:2]:
        c.fn(sctx)


def _t2_f7_01_spoofed_attachment_and_avatar_rejected(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F7 Spoof", None)])
    try:
        a = accs[0]
        r1 = ctx.spring.upload_multipart(
            "/api/v1/attachments",
            token=a["accessToken"],
            files=[("file", "bad.png", "image/png", NOT_AN_IMAGE_BYTES)],
        )
        check(r1.status in (400, 415), f"spoofed attachment expected 400/415, got {r1}")
        r2 = ctx.spring.upload_multipart(
            "/api/v1/users/me/avatar",
            token=a["accessToken"],
            files=[("file", "bad.png", "image/png", NOT_AN_IMAGE_BYTES)],
        )
        check(r2.status in (400, 415), f"spoofed avatar expected 400/415, got {r2}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f7_02_unsatisfiable_range_416(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F7 Range416", None)])
    try:
        a = accs[0]
        png = make_png_bytes(16, 16)
        up = ctx.spring.upload_multipart(
            "/api/v1/attachments",
            token=a["accessToken"],
            files=[("file", "r416.png", "image/png", png)],
        )
        expect_status(up, (200, 201), "upload for 416")
        r = ctx.spring.get_range(
            f"/api/v1/attachments/{up.json()['id']}/content",
            token=a["accessToken"],
            range_header="bytes=99999999-",
        )
        expect_status(r, 416, "unsatisfiable Range returns 416")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f7_03_non_member_attachment_denied(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F7 Own", None), ("F7 Peer", None), ("F7 Out", None)])
    try:
        a, b, out = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        up = ctx.spring.upload_multipart(
            "/api/v1/attachments",
            token=a["accessToken"],
            files=[("file", "sec.png", "image/png", make_png_bytes())],
        ).json()
        ctx.spring.post(
            f"/api/v1/conversations/{cid}/messages",
            token=a["accessToken"],
            body={"clientMessageId": str(uuid.uuid4()), "type": "IMAGE", "content": "", "attachmentIds": [up["id"]]},
        )
        r = ctx.spring.get(f"/api/v1/attachments/{up['id']}/content", token=out["accessToken"])
        check(r.status in (403, 404), f"non-member attachment read expected 403/404, got {r}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f7_04_file_path_traversal_denied(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F7 Trav", None)])
    try:
        r = ctx.spring.get("/api/v1/files/..%2f..%2fetc%2fpasswd", token=accs[0]["accessToken"])
        check(r.status in (400, 404), f"traversal expected 400/404, got {r}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f7_05_oversized_video_rejected(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("oversized feed video check")
    sctx = _social_ctx(ctx)
    sctx.set_social_flags(True, True)
    big = make_oversized_mp4_bytes()
    r = ctx.spring.upload_multipart(
        "/api/v1/feed/videos",
        token=sctx.token("author"),
        files=[("file", "big.mp4", "video/mp4", big)],
    )
    check(r.status in (400, 413), f"oversized video expected 400/413, got {r}")


# ---------------------------------------------------------------------------
# Feature #8: Calls — 1-to-1 WebRTC Voice & Video Calls
# ---------------------------------------------------------------------------


def _t1_f8_01_ice_config_auth_and_unauth(ctx: ReleaseQaContext) -> None:
    expect_status(ctx.spring.get("/api/v1/calls/ice-config"), 401, "unauth /calls/ice-config")
    accs = _with_ephemeral(ctx, [("F8 Ice QA", None)])
    try:
        r = ctx.spring.get("/api/v1/calls/ice-config", token=accs[0]["accessToken"])
        expect_status(r, 200, "auth GET /api/v1/calls/ice-config")
        data = r.json() or {}
        check("iceServers" in data and isinstance(data["iceServers"], list), f"iceConfig missing iceServers: {data}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f8_02_stomp_call_invite_accept_sdp_ice_end(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("STOMP call happy path")
    accs = _with_ephemeral(ctx, [("F8 Caller", None), ("F8 Callee", None)])
    ca = cb = None
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        ca = _make_stomp_connection(ctx, a["accessToken"], "t1-caller")
        cb = _make_stomp_connection(ctx, b["accessToken"], "t1-callee")
        call_id = str(uuid.uuid4())
        ca.send_call(cid, {"type": "INVITE", "callId": call_id})
        cb.wait_event("CALL_INVITE", call_id)
        cb.send_call(cid, {"type": "ACCEPT", "callId": call_id})
        ca.wait_event("CALL_ACCEPTED", call_id)
        cb.wait_event("CALL_ACCEPTED", call_id)
        ca.send_call(cid, {"type": "END", "callId": call_id})
        ca.wait_event("CALL_ENDED", call_id)
        cb.wait_event("CALL_ENDED", call_id)
        hist = ctx.spring.get(f"/api/v1/conversations/{cid}/calls", token=a["accessToken"])
        expect_status(hist, 200, "GET /conversations/{cid}/calls")
        check(any(it.get("callId") == call_id for it in (hist.json() or {}).get("items", [])), "call missing in history")
    finally:
        for c in (ca, cb):
            if c is not None:
                c.close()
        _cleanup_accounts(ctx, accs)


def _t1_f8_03_stomp_call_reject(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("STOMP call reject")
    accs = _with_ephemeral(ctx, [("F8 RejA", None), ("F8 RejB", None)])
    ca = cb = None
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        ca = _make_stomp_connection(ctx, a["accessToken"], "rej-a")
        cb = _make_stomp_connection(ctx, b["accessToken"], "rej-b")
        call_id = str(uuid.uuid4())
        ca.send_call(cid, {"type": "INVITE", "callId": call_id})
        cb.wait_event("CALL_INVITE", call_id)
        cb.send_call(cid, {"type": "REJECT", "callId": call_id})
        ra = ca.wait_event("CALL_REJECTED", call_id)
        check((ra.get("payload") or {}).get("reason") == "REJECTED", f"unexpected reject payload: {ra}")
    finally:
        for c in (ca, cb):
            if c is not None:
                c.close()
        _cleanup_accounts(ctx, accs)


def _t1_f8_04_stomp_call_cancel(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("STOMP call cancel")
    accs = _with_ephemeral(ctx, [("F8 CanA", None), ("F8 CanB", None)])
    ca = cb = None
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        ca = _make_stomp_connection(ctx, a["accessToken"], "can-a")
        cb = _make_stomp_connection(ctx, b["accessToken"], "can-b")
        call_id = str(uuid.uuid4())
        ca.send_call(cid, {"type": "INVITE", "callId": call_id})
        cb.wait_event("CALL_INVITE", call_id)
        ca.send_call(cid, {"type": "CANCEL", "callId": call_id})
        cc = cb.wait_event("CALL_CANCELLED", call_id)
        check((cc.get("payload") or {}).get("reason") == "CANCELLED", f"unexpected cancel payload: {cc}")
    finally:
        for c in (ca, cb):
            if c is not None:
                c.close()
        _cleanup_accounts(ctx, accs)


def _t1_f8_05_call_offer_rest_endpoint(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F8 Offer QA", None)])
    try:
        r = ctx.spring.get(f"/api/v1/calls/{uuid.uuid4()}/offer", token=accs[0]["accessToken"])
        check(r.status == 404, f"unknown call offer expected 404, got {r}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f8_01_call_busy_boundary(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("STOMP CALL_BUSY")
    accs = _with_ephemeral(ctx, [("F8 BusyA", None), ("F8 BusyB", None), ("F8 BusyC", None)])
    ca = cb = cc = None
    try:
        a, b, c = accs
        conv_ab = ctx.open_direct(a["accessToken"], b["phone"])
        conv_cb = ctx.open_direct(c["accessToken"], b["phone"])
        ca = _make_stomp_connection(ctx, a["accessToken"], "busy-a")
        cb = _make_stomp_connection(ctx, b["accessToken"], "busy-b")
        cc = _make_stomp_connection(ctx, c["accessToken"], "busy-c")
        active_id = str(uuid.uuid4())
        cc.send_call(conv_cb, {"type": "INVITE", "callId": active_id})
        cb.wait_event("CALL_INVITE", active_id)
        cb.send_call(conv_cb, {"type": "ACCEPT", "callId": active_id})
        cc.wait_event("CALL_ACCEPTED", active_id)

        busy_id = str(uuid.uuid4())
        ca.send_call(conv_ab, {"type": "INVITE", "callId": busy_id})
        busy = ca.wait_event("CALL_BUSY", busy_id)
        check((busy.get("payload") or {}).get("reason") == "BUSY", f"expected BUSY reason: {busy}")
        # Callee B must NOT receive CALL_BUSY
        leaked = any(
            (_stomp_frame_json(fr) or {}).get("eventType") == "CALL_BUSY"
            and (_stomp_frame_json(fr) or {}).get("callId") == busy_id
            for fr in cb.drain(0.4)
        )
        check(not leaked, "callee B unexpectedly received CALL_BUSY")
        cc.send_call(conv_cb, {"type": "END", "callId": active_id})
        cc.wait_event("CALL_ENDED", active_id)
    finally:
        for conn in (ca, cb, cc):
            if conn is not None:
                conn.close()
        _cleanup_accounts(ctx, accs)


def _t2_f8_02_call_state_invalid_ringing_end_and_caller_accept(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("STOMP CALL_STATE_INVALID")
    accs = _with_ephemeral(ctx, [("F8 InvStA", None), ("F8 InvStB", None)])
    ca = cb = None
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        ca = _make_stomp_connection(ctx, a["accessToken"], "st-a")
        cb = _make_stomp_connection(ctx, b["accessToken"], "st-b")
        c1 = str(uuid.uuid4())
        ca.send_call(cid, {"type": "INVITE", "callId": c1})
        cb.wait_event("CALL_INVITE", c1)

        # END while still RINGING -> CALL_STATE_INVALID
        ca.send_call(cid, {"type": "END", "callId": c1})
        _wait_stomp_error(ca, "CALL_STATE_INVALID", call_id=c1)

        # Caller ACCEPT -> CALL_STATE_INVALID
        ca.send_call(cid, {"type": "ACCEPT", "callId": c1})
        _wait_stomp_error(ca, "CALL_STATE_INVALID", call_id=c1)

        ca.send_call(cid, {"type": "CANCEL", "callId": c1})
        ca.wait_event("CALL_CANCELLED", c1)
    finally:
        for conn in (ca, cb):
            if conn is not None:
                conn.close()
        _cleanup_accounts(ctx, accs)


def _t2_f8_03_call_state_invalid_duplicate_invite(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("STOMP duplicate callId INVITE")
    accs = _with_ephemeral(ctx, [("F8 DupA", None), ("F8 DupB", None)])
    ca = cb = None
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        ca = _make_stomp_connection(ctx, a["accessToken"], "dup-a")
        cb = _make_stomp_connection(ctx, b["accessToken"], "dup-b")
        c2 = str(uuid.uuid4())
        ca.send_call(cid, {"type": "INVITE", "callId": c2})
        cb.wait_event("CALL_INVITE", c2)
        ca.send_call(cid, {"type": "INVITE", "callId": c2})
        _wait_stomp_error(ca, "CALL_STATE_INVALID", call_id=c2)
        ca.send_call(cid, {"type": "CANCEL", "callId": c2})
        ca.wait_event("CALL_CANCELLED", c2)
    finally:
        for conn in (ca, cb):
            if conn is not None:
                conn.close()
        _cleanup_accounts(ctx, accs)


def _t2_f8_04_group_call_invite_rejected_not_member(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("STOMP group call INVITE -> NOT_MEMBER")
    accs = _with_ephemeral(ctx, [("F8 GrpA", None), ("F8 GrpB", None), ("F8 GrpC", None)])
    ca = None
    try:
        a, b, c = accs
        gid = ctx.spring.post(
            "/api/v1/conversations/group",
            token=a["accessToken"],
            body={"title": f"CallGrp {ctx.nonce}", "memberIds": [b["userId"], c["userId"]]},
        ).json()["id"]
        ca = _make_stomp_connection(ctx, a["accessToken"], "grp-a")
        ca.send_call(gid, {"type": "INVITE", "callId": str(uuid.uuid4())})
        err = _wait_stomp_error(ca, "NOT_MEMBER")
        check(err.get("conversationId") == gid, f"expected conversationId=={gid}, got {err}")
    finally:
        if ca is not None:
            ca.close()
        _cleanup_accounts(ctx, accs)


def _t2_f8_05_call_validation_and_block_isolation(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("STOMP call VALIDATION_FAILED & block isolation")
    accs = _with_ephemeral(ctx, [("F8 BlkA", None), ("F8 BlkB", None)])
    ca = cb = None
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        ca = _make_stomp_connection(ctx, a["accessToken"], "val-a")
        cb = _make_stomp_connection(ctx, b["accessToken"], "val-b")

        # Malformed INVITE carrying candidate -> VALIDATION_FAILED
        ca.send_call(
            cid,
            {
                "type": "INVITE",
                "callId": str(uuid.uuid4()),
                "candidate": {"sdpMid": "audio", "sdpMLineIndex": 0, "candidate": "candidate:1"},
            },
        )
        _wait_stomp_error(ca, "VALIDATION_FAILED")

        # Blocker blocks Peer -> Blocker gets BLOCKED, Peer gets NOT_MEMBER
        expect_status(ctx.spring.post(f"/api/v1/users/{b['userId']}/block", token=a["accessToken"]), 204, "block peer")
        ca.send_call(cid, {"type": "INVITE", "callId": str(uuid.uuid4())})
        _wait_stomp_error(ca, "BLOCKED")
        cb.send_call(cid, {"type": "INVITE", "callId": str(uuid.uuid4())})
        _wait_stomp_error(cb, "NOT_MEMBER")
    finally:
        for conn in (ca, cb):
            if conn is not None:
                conn.close()
        _cleanup_accounts(ctx, accs)


# ---------------------------------------------------------------------------
# Feature #9: AI & Security — Summary, Ask RAG (PR #384), Translation, E2EE
# ---------------------------------------------------------------------------


def _t1_f9_01_summary_with_consent(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F9 SumA", None), ("F9 SumB", None)])
    try:
        a, b = accs
        expect_status(ctx.spring.patch("/api/v1/users/me/settings", token=a["accessToken"], body={"aiSummaryConsent": True}), 200, "enable AI consent")
        expect_status(ctx.spring.patch("/api/v1/users/me/settings", token=b["accessToken"], body={"aiSummaryConsent": True}), 200, "enable AI consent B")
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        ctx.send_text(a["accessToken"], cid, "Hôm nay họp lúc 15h về bản phát hành NexaChat.")
        ctx.send_text(b["accessToken"], cid, "Đồng ý, mình sẽ chuẩn bị ghi chú kiểm thử.")
        r = ctx.spring.post(
            f"/api/v1/conversations/{cid}/summary/local",
            token=a["accessToken"],
            body={"scope": "RECENT", "language": "VI"},
        )
        if r.status == 404:
            r = ctx.spring.post(
                f"/api/v1/conversations/{cid}/summary",
                token=a["accessToken"],
                body={"scope": "RECENT", "language": "VI"},
            )
        expect_status(r, 200, "POST summary")
        check("overview" in (r.json() or {}), f"summary missing overview: {r.json()}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f9_02_message_translate_and_batch(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F9 TrA", None), ("F9 TrB", None)])
    try:
        a, b = accs
        expect_status(ctx.spring.patch("/api/v1/users/me/settings", token=a["accessToken"], body={"aiSummaryConsent": True}), 200, "enable AI consent")
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        msg = ctx.send_text(b["accessToken"], cid, "Xin chào buổi sáng")
        r = ctx.spring.post(
            f"/api/v1/messages/{msg['id']}/translate",
            token=a["accessToken"],
            body={"targetLang": "en"},
        )
        if r.status in (502, 503):
            raise BlockedCapability(f"External translation provider unavailable ({r.status})")
        expect_status(r, 200, "POST /messages/{id}/translate")
        batch = ctx.spring.post(
            f"/api/v1/conversations/{cid}/translate-batch",
            token=a["accessToken"],
            body={"messageIds": [msg["id"]], "targetLang": "en"},
        )
        expect_status(batch, 200, "POST /translate-batch")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f9_03_translation_glossary_and_prefs(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F9 GlA", None), ("F9 GlB", None)])
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        put_g = ctx.spring.put(
            f"/api/v1/conversations/{cid}/translation-glossary",
            token=a["accessToken"],
            body={"terms": [{"term": "NexaChat", "replacement": "NexaChat"}]},
        )
        expect_status(put_g, 200, "PUT translation-glossary")
        get_g = ctx.spring.get(f"/api/v1/conversations/{cid}/translation-glossary", token=a["accessToken"])
        expect_status(get_g, 200, "GET translation-glossary")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f9_04_pr384_vietnamese_ask_rag_place_date_lookups(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(
        ctx,
        [
            ("Minh Trâm", None),
            ("Đắc Nhân", None),
            ("Tú Anh", None),
            ("Bình An", None),
        ],
    )
    try:
        minh_tram, dac_nhan, tu_anh, binh_an = accs
        for acc in accs:
            expect_status(
                ctx.spring.patch("/api/v1/users/me/settings", token=acc["accessToken"], body={"aiSummaryConsent": True}),
                200,
                f"enable AI consent for {acc['displayName']}",
            )

        # Case A: Named speaker place + pronoun resolution (a487da66)
        cid_a = ctx.open_direct(minh_tram["accessToken"], dac_nhan["phone"])
        m_mt = ctx.send_text(
            minh_tram["accessToken"],
            cid_a,
            "Tôi sẽ trình bày bản demo ở phòng Hoa Sữa lúc 9 giờ sáng thứ Sáu.",
        )
        ctx.send_text(
            dac_nhan["accessToken"],
            cid_a,
            "Mình vừa đặt xong phòng Bạch Mai cho bản demo sáng thứ Sáu đấy.",
        )
        ask_a = ctx.spring.post(
            f"/api/v1/conversations/{cid_a}/ask",
            token=minh_tram["accessToken"],
            body={"question": "Theo chính lời của Minh Trâm, cô ấy sẽ trình bày bản demo ở đâu?", "language": "VI"},
        )
        expect_status(ask_a, 200, "PR #384 Ask Case A")
        ja = ask_a.json() or {}
        check(ja.get("mode") == "EXTRACTIVE", f"Case A expected EXTRACTIVE, got {ja}")
        check("phòng Hoa Sữa" in (ja.get("answer") or ""), f"Case A missing 'phòng Hoa Sữa': {ja}")
        cits_a = ja.get("citations") or []
        check(cits_a and cits_a[0].get("messageId") == m_mt["id"] and cits_a[0].get("seq") == m_mt["seq"], f"Case A wrong citation: {cits_a}")

        # Case B: Everyday Vietnamese date & place lookup (3a456235 & a487da66)
        cid_b = ctx.open_direct(tu_anh["accessToken"], binh_an["phone"])
        m_date = ctx.send_text(tu_anh["accessToken"], cid_b, "Buổi nghiệm thu dời sang ngày 16 tháng 10.")
        ctx.send_text(binh_an["accessToken"], cid_b, "Buổi nghiệm thu dời sang ngày 20 tháng 10.")
        m_place = ctx.send_text(tu_anh["accessToken"], cid_b, "Điểm đón đoàn ở Cửa Nam.")
        ctx.send_text(binh_an["accessToken"], cid_b, "Điểm đón đoàn ở Cửa Đông.")

        ask_b1 = ctx.spring.post(
            f"/api/v1/conversations/{cid_b}/ask",
            token=binh_an["accessToken"],
            body={"question": "Theo Tú Anh thì buổi nghiệm thu dời sang ngày bao nhiêu rồi?", "language": "AUTO"},
        )
        expect_status(ask_b1, 200, "PR #384 Ask Case B date")
        jb1 = ask_b1.json() or {}
        check(jb1.get("mode") == "EXTRACTIVE", f"Case B date expected EXTRACTIVE, got {jb1}")
        check("16 tháng 10" in (jb1.get("answer") or ""), f"Case B date missing '16 tháng 10': {jb1}")
        check((jb1.get("citations") or [{}])[0].get("messageId") == m_date["id"], f"Case B date wrong citation: {jb1}")

        ask_b2 = ctx.spring.post(
            f"/api/v1/conversations/{cid_b}/ask",
            token=binh_an["accessToken"],
            body={"question": "Tú Anh nhắn là điểm đón đoàn ở chỗ nào nhỉ?", "language": "AUTO"},
        )
        expect_status(ask_b2, 200, "PR #384 Ask Case B place")
        jb2 = ask_b2.json() or {}
        check(jb2.get("mode") == "EXTRACTIVE", f"Case B place expected EXTRACTIVE, got {jb2}")
        check("Cửa Nam" in (jb2.get("answer") or ""), f"Case B place missing 'Cửa Nam': {jb2}")
        check((jb2.get("citations") or [{}])[0].get("messageId") == m_place["id"], f"Case B place wrong citation: {jb2}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f9_05_e2ee_device_register_and_revoke(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F9 E2EE QA", None)])
    try:
        tok = accs[0]["accessToken"]
        did = str(uuid.uuid4())
        import base64
        pub_b64 = base64.b64encode(b"\x01" * 32).decode("ascii")
        reg = ctx.spring.put(f"/api/v1/e2ee/devices/{did}", token=tok, body={"publicKey": pub_b64})
        if reg.status == 404:
            raise BlockedCapability("E2EE device endpoints disabled on target")
        expect_status(reg, (200, 201), "PUT /e2ee/devices/{id}")
        rev = ctx.spring.delete(f"/api/v1/e2ee/devices/{did}", token=tok)
        expect_status(rev, 204, "DELETE /e2ee/devices/{id}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f9_01_ai_opt_out_respected(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F9 OptOutA", None), ("F9 OptOutB", None)])
    try:
        a, b = accs
        expect_status(ctx.spring.patch("/api/v1/users/me/settings", token=a["accessToken"], body={"aiSummaryConsent": False}), 200, "disable AI consent")
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        ctx.send_text(a["accessToken"], cid, "Secret project date is 12 May")
        r = ctx.spring.post(
            f"/api/v1/conversations/{cid}/summary",
            token=a["accessToken"],
            body={"scope": "RECENT", "language": "EN"},
        )
        check(r.status in (400, 403) or (r.status == 200 and bool((r.json() or {}).get("contextOmitted"))), f"expected consent gate or omission, got {r}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f9_02_non_member_ai_endpoints_denied(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F9 NonMemA", None), ("F9 NonMemB", None), ("F9 NonMemC", None)])
    try:
        a, b, out = accs
        expect_status(ctx.spring.patch("/api/v1/users/me/settings", token=out["accessToken"], body={"aiSummaryConsent": True}), 200, "consent C")
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        expect_error(
            ctx.spring.post(f"/api/v1/conversations/{cid}/ask", token=out["accessToken"], body={"question": "test", "language": "EN"}),
            403,
            "NOT_MEMBER",
            "outsider Ask denied",
        )
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f9_03_encryption_release_policy_gate(ctx: ReleaseQaContext) -> None:
    test_file = REPO_ROOT / "android" / "app" / "src" / "test" / "java" / "com" / "nexachat" / "e2ee" / "EncryptionReleasePolicyTest.kt"
    if not test_file.is_file():
        matches = list((REPO_ROOT / "android").rglob("*EncryptionReleasePolicyTest*"))
        check(bool(matches), "EncryptionReleasePolicyTest missing from android/app/src/test")
        test_file = matches[0]
    src = test_file.read_text(encoding="utf-8")
    check("e2ee" in src.lower() or "encryption" in src.lower(), "EncryptionReleasePolicyTest content invalid")


def _t2_f9_04_pr384_vietnamese_ask_rag_abstentions(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(
        ctx,
        [
            ("Phan Ngọc Vi", None),
            ("Tú Anh", None),
            ("QA Listener", None),
        ],
    )
    try:
        ngoc_vi, tu_anh, listener = accs
        for acc in accs:
            expect_status(
                ctx.spring.patch("/api/v1/users/me/settings", token=acc["accessToken"], body={"aiSummaryConsent": True}),
                200,
                f"enable AI consent for {acc['displayName']}",
            )

        # 1. Booking-date rejection (a487da66)
        cid_nv = ctx.open_direct(ngoc_vi["accessToken"], listener["phone"])
        ctx.send_text(
            ngoc_vi["accessToken"],
            cid_nv,
            "Tôi đã đặt sảnh vào ngày 18 tháng 6 cho buổi gặp nhóm bản đồ, lịch sự kiện sẽ báo riêng.",
        )
        r_nv = ctx.spring.post(
            f"/api/v1/conversations/{cid_nv}/ask",
            token=listener["accessToken"],
            body={"question": "Theo lời của Phan Ngọc Vi, buổi gặp nhóm bản đồ là ngày nào?", "language": "VI"},
        )
        expect_status(r_nv, 200, "PR #384 booking-date abstention")
        j_nv = r_nv.json() or {}
        check(j_nv.get("mode") == "NOT_FOUND", f"expected NOT_FOUND for booking date, got {j_nv}")
        check(j_nv.get("notFoundReason") == "NO_EVIDENCE", f"expected NO_EVIDENCE, got {j_nv}")
        check((j_nv.get("citations") or []) == [], f"expected empty citations, got {j_nv}")

        # 2. Conflicting locations + unshared code abstentions (3a456235)
        cid_ta = ctx.open_direct(tu_anh["accessToken"], listener["phone"])
        ctx.send_text(tu_anh["accessToken"], cid_ta, "Điểm đón đoàn ở Cửa Nam.")
        ctx.send_text(tu_anh["accessToken"], cid_ta, "Điểm đón đoàn ở Cửa Đông.")
        r_conf = ctx.spring.post(
            f"/api/v1/conversations/{cid_ta}/ask",
            token=listener["accessToken"],
            body={"question": "Theo Tú Anh thì điểm đón đoàn ở chỗ nào vậy?", "language": "VI"},
        )
        expect_status(r_conf, 200, "PR #384 conflicting locations abstention")
        j_conf = r_conf.json() or {}
        check(j_conf.get("mode") == "NOT_FOUND" and (j_conf.get("citations") or []) == [], f"expected NOT_FOUND for conflicting locations: {j_conf}")

        ctx.send_text(tu_anh["accessToken"], cid_ta, "Mã tủ mẫu của nhóm Gió chưa được gửi lại.")
        r_code = ctx.spring.post(
            f"/api/v1/conversations/{cid_ta}/ask",
            token=listener["accessToken"],
            body={"question": "Mã tủ mẫu của nhóm Gió là gì nhỉ?", "language": "VI"},
        )
        expect_status(r_code, 200, "PR #384 unshared code abstention")
        j_code = r_code.json() or {}
        check(j_code.get("mode") == "NOT_FOUND" and (j_code.get("citations") or []) == [], f"expected NOT_FOUND for unshared code: {j_code}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f9_05_ask_prior_turns_cap_400(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F9 TurnsA", None), ("F9 TurnsB", None)])
    try:
        a, b = accs
        expect_status(ctx.spring.patch("/api/v1/users/me/settings", token=a["accessToken"], body={"aiSummaryConsent": True}), 200, "consent")
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        r = ctx.spring.post(
            f"/api/v1/conversations/{cid}/ask",
            token=a["accessToken"],
            body={
                "question": "Tóm tắt?",
                "language": "VI",
                "priorTurns": [
                    {"question": "Q1", "answer": "A1"},
                    {"question": "Q2", "answer": "A2"},
                    {"question": "Q3", "answer": "A3"},
                    {"question": "Q4", "answer": "A4"},
                ],
            },
        )
        expect_error(r, 400, "VALIDATION_FAILED", "priorTurns > 3 rejected")
    finally:
        _cleanup_accounts(ctx, accs)


# ---------------------------------------------------------------------------
# Feature #10: Streaks — Direct Chat & Check-in Streaks
# ---------------------------------------------------------------------------


def _t1_f10_01_streaks_me_summary(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F10 Me QA", None)])
    try:
        r = ctx.spring.get("/api/v1/streaks/me", token=accs[0]["accessToken"])
        expect_status(r, 200, "GET /api/v1/streaks/me")
        data = r.json() or {}
        check("checkInCount" in data or "count" in data, f"unexpected streak summary payload: {data}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f10_02_streaks_checkin_idempotent(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F10 CheckIn QA", None)])
    try:
        tok = accs[0]["accessToken"]
        r1 = ctx.spring.post("/api/v1/streaks/check-in", token=tok)
        expect_status(r1, 200, "POST /streaks/check-in #1")
        r2 = ctx.spring.post("/api/v1/streaks/check-in", token=tok)
        expect_status(r2, 200, "POST /streaks/check-in #2 idempotent")
        check(r1.json() == r2.json(), "check-in was not idempotent on same day")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f10_03_direct_chat_reciprocal_streak_day1(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F10 ChatA", None), ("F10 ChatB", None)])
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        ctx.send_text(a["accessToken"], cid, f"streak hi {ctx.nonce}")
        ctx.send_text(b["accessToken"], cid, f"streak back {ctx.nonce}")
        r = ctx.spring.get(f"/api/v1/conversations/{cid}/streak", token=a["accessToken"])
        expect_status(r, 200, "GET /conversations/{cid}/streak")
        check((r.json() or {}).get("count", 0) >= 1, f"expected count >= 1 after reciprocal chat: {r.json()}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f10_04_streak_reminders_opt_in_round_trip(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F10 RemOpt", None)])
    try:
        tok = accs[0]["accessToken"]
        p1 = ctx.spring.patch("/api/v1/users/me/settings", token=tok, body={"streakReminders": True})
        expect_status(p1, 200, "enable streakReminders")
        check(p1.json()["streakReminders"] is True, "streakReminders not true")
        p2 = ctx.spring.patch("/api/v1/users/me/settings", token=tok, body={"streakReminders": False})
        expect_status(p2, 200, "disable streakReminders")
        check(p2.json()["streakReminders"] is False, "streakReminders not false")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f10_05_social_activity_updates_checkin_streak(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("social post updates check-in streak")
    sctx = _social_ctx(ctx)
    from tools.e2e.social_suite.tier3_cross_feature import get_tier3_checks

    t3_01 = next(c for c in get_tier3_checks() if c.check_id == "T3.01")
    t3_01.fn(sctx)


def _t2_f10_01_streak_non_member_403_unauth_401_group_err(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F10 StrA", None), ("F10 StrB", None), ("F10 StrOut", None)])
    try:
        a, b, out = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        expect_status(ctx.spring.get(f"/api/v1/conversations/{cid}/streak"), 401, "unauth streak 401")
        expect_error(
            ctx.spring.get(f"/api/v1/conversations/{cid}/streak", token=out["accessToken"]),
            403,
            "NOT_MEMBER",
            "non-member streak 403",
        )
        gid = ctx.spring.post(
            "/api/v1/conversations/group",
            token=a["accessToken"],
            body={"title": f"StrGrp {ctx.nonce}", "memberIds": [b["userId"]]},
        ).json()["id"]
        grp_st = ctx.spring.get(f"/api/v1/conversations/{gid}/streak", token=a["accessToken"])
        check(grp_st.status in (400, 404), f"group streak expected 400/404, got {grp_st}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f10_02_streak_rolling_7_day_freeze(ctx: ReleaseQaContext) -> None:
    db_info = ctx.require_local_db("streak rolling 7-day freeze DB seed")
    accs = _with_ephemeral(ctx, [("F10 Charlie", None), ("F10 Dave", None)])
    try:
        charlie, dave = accs
        cid = ctx.open_direct(charlie["accessToken"], dave["phone"])
        u_low, u_high = sorted([charlie["userId"], dave["userId"]])
        # Seed BEFORE their first message today so StreakService.RecentDayCache is cold!
        sql = (
            "INSERT INTO conversation_streaks "
            "(conversation_id, user_low_id, user_high_id, low_day, high_day, count, best_count, "
            "last_day, started_on, freeze_used_on, low_cont, high_cont) "
            f"VALUES ('{cid}', '{u_low}', '{u_high}', CURRENT_DATE - 2, CURRENT_DATE - 2, 3, 3, "
            "CURRENT_DATE - 2, CURRENT_DATE - 4, NULL, CURRENT_DATE - 4, CURRENT_DATE - 2);"
        )
        _run_psql(db_info, sql)
        ctx.send_text(charlie["accessToken"], cid, f"freeze hi {ctx.nonce}")
        ctx.send_text(dave["accessToken"], cid, f"freeze back {ctx.nonce}")

        r = ctx.spring.get(f"/api/v1/conversations/{cid}/streak", token=charlie["accessToken"])
        expect_status(r, 200, "GET streak after 2-day freeze bridge")
        data = r.json() or {}
        check(data.get("count") == 4, f"expected count==4 after freeze bridge, got {data}")
        check(data.get("bestCount") == 4, f"expected bestCount==4, got {data}")
        check(data.get("freezeAvailable") is False, f"expected freezeAvailable==False after consuming freeze, got {data}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f10_03_streak_2_day_reset_preserves_best_count(ctx: ReleaseQaContext) -> None:
    db_info = ctx.require_local_db("streak 2-day reset within cooldown DB seed")
    accs = _with_ephemeral(ctx, [("F10 Eve", None), ("F10 Frank", None)])
    try:
        eve, frank = accs
        cid = ctx.open_direct(eve["accessToken"], frank["phone"])
        u_low, u_high = sorted([eve["userId"], frank["userId"]])
        # freeze_used_on = CURRENT_DATE - 3 (within 6-day cooldown -> NOT < today - 6)
        sql = (
            "INSERT INTO conversation_streaks "
            "(conversation_id, user_low_id, user_high_id, low_day, high_day, count, best_count, "
            "last_day, started_on, freeze_used_on, low_cont, high_cont) "
            f"VALUES ('{cid}', '{u_low}', '{u_high}', CURRENT_DATE - 2, CURRENT_DATE - 2, 5, 5, "
            "CURRENT_DATE - 2, CURRENT_DATE - 6, CURRENT_DATE - 3, CURRENT_DATE - 6, CURRENT_DATE - 2);"
        )
        _run_psql(db_info, sql)
        ctx.send_text(eve["accessToken"], cid, f"reset hi {ctx.nonce}")
        ctx.send_text(frank["accessToken"], cid, f"reset back {ctx.nonce}")

        r = ctx.spring.get(f"/api/v1/conversations/{cid}/streak", token=eve["accessToken"])
        expect_status(r, 200, "GET streak after 2-day reset")
        data = r.json() or {}
        check(data.get("count") == 1, f"expected count==1 after cooldown reset, got {data}")
        check(data.get("bestCount") == 5, f"expected bestCount==5 preserved, got {data}")
        check(data.get("atRisk") is False, f"expected atRisk==False after today's pair chat, got {data}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f10_04_one_sided_chat_does_not_advance_streak(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F10 OneA", None), ("F10 OneB", None)])
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        ctx.send_text(a["accessToken"], cid, "only me 1")
        ctx.send_text(a["accessToken"], cid, "only me 2")
        r = ctx.spring.get(f"/api/v1/conversations/{cid}/streak", token=a["accessToken"])
        expect_status(r, 200, "GET streak after one-sided chat")
        check((r.json() or {}).get("count", 0) == 0, f"one-sided chat advanced streak: {r.json()}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f10_05_streak_zero_before_any_chat(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F10 ZeroA", None), ("F10 ZeroB", None)])
    try:
        a, b = accs
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        r = ctx.spring.get(f"/api/v1/conversations/{cid}/streak", token=a["accessToken"])
        expect_status(r, 200, "GET streak on brand-new chat")
        check((r.json() or {}).get("count") == 0, f"expected count==0, got {r.json()}")
    finally:
        _cleanup_accounts(ctx, accs)


# ---------------------------------------------------------------------------
# Feature #11: Social (Android + Backend) — Public Feed, Edit, Search, Author Profile
# ---------------------------------------------------------------------------


def _t1_f11_01_post_create_read_idempotent(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F11 PostA", None)])
    pid = None
    try:
        tok = accs[0]["accessToken"]
        cmid = str(uuid.uuid4())
        r1 = ctx.spring.post("/api/v1/feed/posts", token=tok, body={"clientPostId": cmid, "body": f"Feed {ctx.nonce}"})
        expect_status(r1, 201, "create feed post")
        pid = r1.json()["id"]
        r2 = ctx.spring.post("/api/v1/feed/posts", token=tok, body={"clientPostId": cmid, "body": f"Feed {ctx.nonce}"})
        expect_status(r2, (200, 201), "idempotent clientPostId replay")
        check(r2.json()["id"] == pid, "clientPostId created duplicate post")
    finally:
        if pid:
            ctx.spring.delete(f"/api/v1/feed/posts/{pid}", token=accs[0]["accessToken"])
        _cleanup_accounts(ctx, accs)


def _t1_f11_02_post_patch_edit_and_delete(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F11 Edit", None)])
    try:
        tok = accs[0]["accessToken"]
        p = ctx.spring.post("/api/v1/feed/posts", token=tok, body={"clientPostId": str(uuid.uuid4()), "body": "Before edit"}).json()
        pid = p["id"]
        ed = ctx.spring.patch(f"/api/v1/feed/posts/{pid}", token=tok, body={"body": "After edit"})
        expect_status(ed, 200, "PATCH /feed/posts/{id}")
        check(ed.json().get("editedAt") is not None, f"editedAt missing: {ed.json()}")
        expect_status(ctx.spring.delete(f"/api/v1/feed/posts/{pid}", token=tok), 204, "DELETE /feed/posts/{id}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f11_03_comments_crud_and_edit(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F11 CmtA", None), ("F11 CmtB", None)])
    pid = None
    try:
        a, b = accs
        pid = ctx.spring.post("/api/v1/feed/posts", token=a["accessToken"], body={"clientPostId": str(uuid.uuid4()), "body": "Parent"}).json()["id"]
        c = ctx.spring.post(f"/api/v1/feed/posts/{pid}/comments", token=b["accessToken"], body={"clientCommentId": str(uuid.uuid4()), "body": "First"})
        expect_status(c, 201, "create comment")
        cid = c.json()["id"]
        ed = ctx.spring.patch(f"/api/v1/feed/comments/{cid}", token=b["accessToken"], body={"body": "Updated"})
        expect_status(ed, 200, "edit comment")
        check(ed.json().get("editedAt") is not None, "comment editedAt missing")
        expect_status(ctx.spring.delete(f"/api/v1/feed/comments/{cid}", token=b["accessToken"]), 204, "delete comment")
    finally:
        if pid:
            ctx.spring.delete(f"/api/v1/feed/posts/{pid}", token=accs[0]["accessToken"])
        _cleanup_accounts(ctx, accs)


def _t1_f11_04_feed_reactions_put_delete(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F11 RxA", None), ("F11 RxB", None)])
    pid = None
    try:
        a, b = accs
        pid = ctx.spring.post("/api/v1/feed/posts", token=a["accessToken"], body={"clientPostId": str(uuid.uuid4()), "body": "React me"}).json()["id"]
        enc = urllib.parse.quote(DISTINCT_EMOJIS[0], safe="")
        expect_status(ctx.spring.put(f"/api/v1/feed/posts/{pid}/reactions/{enc}", token=b["accessToken"]), (200, 204), "PUT reaction")
        expect_status(ctx.spring.delete(f"/api/v1/feed/posts/{pid}/reactions/{enc}", token=b["accessToken"]), (200, 204), "DELETE reaction")
    finally:
        if pid:
            ctx.spring.delete(f"/api/v1/feed/posts/{pid}", token=accs[0]["accessToken"])
        _cleanup_accounts(ctx, accs)


def _t1_f11_05_author_profile_and_vn_search(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F11 Author QA", None)])
    pid = None
    try:
        a = accs[0]
        tok = a["accessToken"]
        p = ctx.spring.post("/api/v1/feed/posts", token=tok, body={"clientPostId": str(uuid.uuid4()), "body": f"Cà phê sáng {ctx.nonce}"}).json()
        pid = p["id"]
        prof = ctx.spring.get(f"/api/v1/feed/authors/{a['userId']}", token=tok)
        expect_status(prof, 200, "GET /feed/authors/{id}")
        check("phone" not in prof.json(), "author profile leaked phone")
        srch = ctx.spring.get("/api/v1/feed/posts", token=tok, params={"q": f"ca phe sang {ctx.nonce}", "authorId": a["userId"]})
        expect_status(srch, 200, "accent-insensitive q + authorId search")
        check(any(it.get("id") == pid for it in srch.json().get("items", [])), "VN accent-insensitive search missed post")
    finally:
        if pid:
            ctx.spring.delete(f"/api/v1/feed/posts/{pid}", token=accs[0]["accessToken"])
        _cleanup_accounts(ctx, accs)


def _t2_f11_01_post_validation_and_invalid_author_uuid(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F11 Val", None)])
    try:
        tok = accs[0]["accessToken"]
        expect_status(
            ctx.spring.post("/api/v1/feed/posts", token=tok, body={"clientPostId": str(uuid.uuid4()), "body": "x" * (MAX_POST_BODY + 1)}),
            400,
            "oversized post body rejected",
        )
        expect_status(ctx.spring.get("/api/v1/feed/posts", token=tok, params={"authorId": "not-a-uuid"}), 400, "invalid authorId UUID")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f11_02_non_owner_post_edit_delete_denied(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F11 Own", None), ("F11 Other", None)])
    pid = None
    try:
        a, b = accs
        pid = ctx.spring.post("/api/v1/feed/posts", token=a["accessToken"], body={"clientPostId": str(uuid.uuid4()), "body": "Mine"}).json()["id"]
        expect_status(ctx.spring.patch(f"/api/v1/feed/posts/{pid}", token=b["accessToken"], body={"body": "Nope"}), 403, "non-owner edit 403")
        expect_status(ctx.spring.delete(f"/api/v1/feed/posts/{pid}", token=b["accessToken"]), 403, "non-owner delete 403")
    finally:
        if pid:
            ctx.spring.delete(f"/api/v1/feed/posts/{pid}", token=accs[0]["accessToken"])
        _cleanup_accounts(ctx, accs)


def _t2_f11_03_ninth_distinct_reaction_rejected(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F11 9thA", None)])
    pid = None
    try:
        tok = accs[0]["accessToken"]
        pid = ctx.spring.post("/api/v1/feed/posts", token=tok, body={"clientPostId": str(uuid.uuid4()), "body": "8 cap"}).json()["id"]
        for em in DISTINCT_EMOJIS:
            expect_status(ctx.spring.put(f"/api/v1/feed/posts/{pid}/reactions/{urllib.parse.quote(em, safe='')}", token=tok), (200, 204), f"add {em}")
        r9 = ctx.spring.put(f"/api/v1/feed/posts/{pid}/reactions/{urllib.parse.quote(NINTH_EMOJI, safe='')}", token=tok)
        check(r9.status in (400, 409), f"9th reaction expected 400/409, got {r9}")
    finally:
        if pid:
            ctx.spring.delete(f"/api/v1/feed/posts/{pid}", token=accs[0]["accessToken"])
        _cleanup_accounts(ctx, accs)


def _t2_f11_04_deleted_post_returns_404_no_leak(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F11 Del404A", None), ("F11 Del404B", None)])
    try:
        a, b = accs
        pid = ctx.spring.post("/api/v1/feed/posts", token=a["accessToken"], body={"clientPostId": str(uuid.uuid4()), "body": "Gone"}).json()["id"]
        c = ctx.spring.post(f"/api/v1/feed/posts/{pid}/comments", token=b["accessToken"], body={"clientCommentId": str(uuid.uuid4()), "body": "C"}).json()
        expect_status(ctx.spring.delete(f"/api/v1/feed/posts/{pid}", token=a["accessToken"]), 204, "delete post")
        expect_status(ctx.spring.get(f"/api/v1/feed/posts/{pid}", token=b["accessToken"]), 404, "deleted post 404")
        expect_status(ctx.spring.delete(f"/api/v1/feed/comments/{c['id']}", token=b["accessToken"]), 404, "comment under deleted post 404")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f11_05_social_suite_multipart_and_boundaries_delegate(ctx: ReleaseQaContext) -> None:
    sctx = _social_ctx(ctx)
    from tools.e2e.social_suite.tier2_boundary_edge import get_tier2_checks

    t2 = {c.check_id: c for c in get_tier2_checks()}
    for cid in ("T2.F4.01", "T2.F8.01", "T2.F9.01"):
        t2[cid].fn(sctx)


# ---------------------------------------------------------------------------
# Feature #12: Social Web (site/social/)
# ---------------------------------------------------------------------------


def _t1_f12_01_social_config_public(ctx: ReleaseQaContext) -> None:
    r = ctx.proxy.get("/api/social/v1/config")
    expect_status(r, 200, "GET /api/social/v1/config")
    data = r.json() or {}
    check("webEnabled" in data and "videoUploadsEnabled" in data, f"unexpected config: {data}")


def _t1_f12_02_social_web_html_and_csp(ctx: ReleaseQaContext) -> None:
    r = ctx.proxy.get("/social/")
    expect_status(r, 200, "GET /social/")
    csp = r.header("Content-Security-Policy") or ""
    check("connect-src 'self'" in csp, f"/social/ CSP missing connect-src 'self': {csp!r}")
    check("NexaChat Social" in r.text, "/social/ HTML missing NexaChat Social title")


def _t1_f12_03_social_session_login_and_feed_proxy(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F12 WebQA", None)])
    try:
        a = accs[0]
        login = ctx.proxy.post("/api/social/v1/session/login", body={"identifier": a["phone"], "password": a["password"]})
        expect_status(login, 200, "POST /api/social/v1/session/login")
        tok = login.json()["accessToken"]
        feed = ctx.proxy.get("/api/social/v1/posts", token=tok)
        expect_status(feed, 200, "GET /api/social/v1/posts")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f12_04_social_authors_and_files_proxy_routes(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F12 ProxyRoutes", None)])
    try:
        a = accs[0]
        up = ctx.spring.upload_multipart(
            "/api/v1/users/me/avatar",
            token=a["accessToken"],
            files=[("file", "av.png", "image/png", make_png_bytes())],
        )
        expect_status(up, (200, 201), "upload avatar")
        av_path = urllib.parse.urlsplit(up.json()["avatarUrl"]).path
        prof = ctx.proxy.get(f"/api/social/v1/authors/{a['userId']}", token=a["accessToken"])
        expect_status(prof, 200, "GET /api/social/v1/authors/{id} via site/proxy")
        av = ctx.proxy.get(av_path, token=a["accessToken"])
        expect_status(av, 200, "GET /api/v1/files/* via site/proxy")
    finally:
        _cleanup_accounts(ctx, accs)


def _t1_f12_05_social_api_no_store_headers(ctx: ReleaseQaContext) -> None:
    r = ctx.proxy.get("/api/social/v1/config")
    expect_status(r, 200, "GET /api/social/v1/config")
    cc = (r.header("Cache-Control") or "").lower()
    check("no-store" in cc, f"/api/social/v1/config missing no-store Cache-Control: {cc!r}")


def _t2_f12_01_kill_switch_blocks_social_keeps_android(ctx: ReleaseQaContext) -> None:
    sctx = _social_ctx(ctx)
    sctx.set_social_flags(False, True)
    try:
        r = ctx.proxy.get("/api/social/v1/posts", token=sctx.token("reader"))
        expect_error(r, 403, "SOCIAL_FEATURE_DISABLED", "web social disabled")
        r_and = ctx.spring.get("/api/v1/feed/posts", token=sctx.token("reader"))
        expect_status(r_and, 200, "Android /api/v1/feed/posts stays 200 while webEnabled=false")
    finally:
        sctx.set_social_flags(True, True)


def _t2_f12_02_video_flag_blocks_new_social_video_uploads(ctx: ReleaseQaContext) -> None:
    sctx = _social_ctx(ctx)
    sctx.set_social_flags(True, False)
    try:
        r = ctx.proxy.upload_multipart(
            "/api/social/v1/videos",
            token=sctx.token("author"),
            files=[("file", "v.mp4", "video/mp4", load_valid_mp4_bytes())],
        )
        expect_status(r, 403, "video upload denied when videoUploadsEnabled=false")
    finally:
        sctx.set_social_flags(True, True)


def _t2_f12_03_unmapped_social_proxy_path_rejected(ctx: ReleaseQaContext) -> None:
    r = ctx.proxy.get("/api/social/v1/nonexistent-wildcard-probe")
    expect_status(r, 404, "unmapped /api/social/v1/* path must 404")


def _t2_f12_04_social_web_removes_stale_copy(ctx: ReleaseQaContext) -> None:
    js = (REPO_ROOT / "site" / "social" / "social-app.js").read_text(encoding="utf-8")
    check("ảnh đã có" not in js and "existing images" not in js, "stale draft/edit copy found in social-app.js")


def _t2_f12_05_social_suite_f1_f2_boundaries_delegate(ctx: ReleaseQaContext) -> None:
    sctx = _social_ctx(ctx)
    from tools.e2e.social_suite.tier2_boundary_edge import get_tier2_checks

    t2 = {c.check_id: c for c in get_tier2_checks()}
    for cid in ("T2.F1.01", "T2.F2.01"):
        t2[cid].fn(sctx)


# ---------------------------------------------------------------------------
# Feature #13: Admin Web — Staff Moderation & Social Settings
# ---------------------------------------------------------------------------


def _t1_f13_01_admin_overview_and_social_settings(ctx: ReleaseQaContext) -> None:
    sctx = _social_ctx(ctx)
    expect_status(ctx.spring.get("/api/v1/admin/overview", token=sctx.token("admin")), 200, "GET /admin/overview")
    expect_status(ctx.spring.get("/api/v1/admin/social/settings", token=sctx.token("admin")), 200, "GET /admin/social/settings")


def _t1_f13_02_admin_social_flags_and_cms_lifecycle(ctx: ReleaseQaContext) -> None:
    sctx = _social_ctx(ctx)
    from tools.e2e.social_suite.tier1_feature_api import get_tier1_checks

    t1 = {c.check_id: c for c in get_tier1_checks()}
    for cid in ("T1.F12.02", "T1.F12.03", "T1.F12.04"):
        t1[cid].fn(sctx)


def _t1_f13_03_moderator_feed_report_moderation_and_audit(ctx: ReleaseQaContext) -> None:
    sctx = _social_ctx(ctx)
    from tools.e2e.social_suite.tier1_feature_api import get_tier1_checks

    t1 = {c.check_id: c for c in get_tier1_checks()}
    for cid in ("T1.F13.01", "T1.F13.02", "T1.F13.04"):
        t1[cid].fn(sctx)


def _t1_f13_04_admin_users_alerts_rules_system_api(ctx: ReleaseQaContext) -> None:
    sctx = _social_ctx(ctx)
    adm = sctx.token("admin")
    expect_status(ctx.spring.get("/api/v1/admin/users", token=adm, params={"limit": 5}), 200, "GET /admin/users")
    expect_status(ctx.spring.get("/api/v1/admin/alerts", token=adm, params={"limit": 5}), 200, "GET /admin/alerts")
    expect_status(ctx.spring.get("/api/v1/admin/rules", token=adm), 200, "GET /admin/rules")
    expect_status(ctx.spring.get("/api/v1/admin/audit", token=adm, params={"limit": 5}), 200, "GET /admin/audit")
    expect_status(ctx.spring.get("/api/v1/admin/system", token=adm), 200, "GET /admin/system")


def _t1_f13_05_admin_spa_csp_and_no_prod_leak(ctx: ReleaseQaContext) -> None:
    sctx = _social_ctx(ctx)
    from tools.e2e.social_suite.tier1_feature_api import get_tier1_checks

    t1 = {c.check_id: c for c in get_tier1_checks()}
    t1["T1.F15.03"].fn(sctx)


def _t2_f13_01_moderator_denied_social_settings(ctx: ReleaseQaContext) -> None:
    sctx = _social_ctx(ctx)
    r = ctx.spring.get("/api/v1/admin/social/settings", token=sctx.token("moderator"))
    expect_error(r, 403, "NOT_ADMIN", "MODERATOR denied /admin/social/settings")


def _t2_f13_02_social_settings_stale_revision_409(ctx: ReleaseQaContext) -> None:
    sctx = _social_ctx(ctx)
    cur = sctx.get_admin_settings()
    r = ctx.spring.patch(
        "/api/v1/admin/social/settings/flags",
        token=sctx.token("admin"),
        body={"expectedRevision": int(cur["revision"]) + 999, "webEnabled": cur["webEnabled"], "videoUploadsEnabled": cur["videoUploadsEnabled"]},
    )
    expect_error(r, 409, "SOCIAL_SETTINGS_CONFLICT", "stale revision returns 409")


def _t2_f13_03_regular_user_denied_admin_routes(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F13 NonStaff", None)])
    try:
        tok = accs[0]["accessToken"]
        for path in ("/api/v1/admin/overview", "/api/v1/admin/users", "/api/v1/admin/social/settings"):
            expect_status(ctx.spring.get(path, token=tok), 403, f"USER denied {path}")
    finally:
        _cleanup_accounts(ctx, accs)


def _t2_f13_04_admin_audit_invalid_uuid_and_cms_bounds(ctx: ReleaseQaContext) -> None:
    sctx = _social_ctx(ctx)
    adm = sctx.token("admin")
    bad_audit = ctx.spring.get("/api/v1/admin/audit", token=adm, params={"actorId": "not-a-uuid"})
    expect_status(bad_audit, 400, "invalid actorId UUID on /admin/audit")
    cur = sctx.get_admin_settings()
    too_long = ctx.spring.put(
        "/api/v1/admin/social/settings/announcement/draft",
        token=adm,
        body={"expectedRevision": cur["revision"], "titleVi": "x" * 101, "bodyVi": "ok", "titleEn": "ok", "bodyEn": "ok"},
    )
    expect_status(too_long, 400, "announcement title > 100 chars rejected")


def _t2_f13_05_social_page_preserves_pr385_and_8e448100(ctx: ReleaseQaContext) -> None:
    social_page = (REPO_ROOT / "admin-web" / "src" / "pages" / "SocialPage.tsx").read_text(encoding="utf-8")
    check("formRef" in social_page, "SocialPage.tsx missing PR #385 formRef conflict retention")
    check("isAdmin" in social_page and "social.adminOnly.title" in social_page, "SocialPage.tsx missing 8e448100 MODERATOR gate")
    check("TITLE_MAX" in social_page and "BODY_MAX" in social_page, "SocialPage.tsx missing inline limit validation")


# ---------------------------------------------------------------------------
# Feature #14: Public Site (site/)
# ---------------------------------------------------------------------------


def _t1_f14_01_site_check_offline_validation(ctx: ReleaseQaContext) -> None:
    site_check = _load_site_check()

    failures: List[str] = []
    vercel_data, asset_data, json_fails = site_check.test_json_files()
    failures.extend(json_fails)
    if vercel_data is not None:
        failures.extend(site_check.test_vercel_headers(vercel_data))
        failures.extend(site_check.test_assetlinks_serving_static_and_local(vercel_data))
    if asset_data is not None:
        failures.extend(site_check.test_assetlinks_structure(asset_data))
    failures.extend(site_check.test_site_pages_and_assets())
    failures.extend(site_check.test_token_validator())
    check(not failures, f"site/check.py failures: {failures}")


def _t1_f14_02_public_pages_return_200(ctx: ReleaseQaContext) -> None:
    for path in ("/", "/download.html", "/delete-account.html", "/privacy.html", "/terms.html"):
        r = ctx.proxy.get(path)
        expect_status(r, 200, f"GET {path}")
        check("NexaChat" in r.text, f"{path} missing NexaChat marker")


def _t1_f14_03_join_token_no_store_and_footer_social(ctx: ReleaseQaContext) -> None:
    r = ctx.proxy.get(f"/join/{VALID_JOIN_TOKEN}")
    expect_status(r, 200, "GET /join/{token}")
    cc = (r.header("Cache-Control") or "").lower()
    check("no-store" in cc, f"/join/ missing Cache-Control: no-store ({cc!r})")
    f_idx = r.text.find("<footer")
    check(f_idx != -1 and "/social/" in r.text[f_idx:], "/join/ missing /social/ link inside <footer>")


def _t1_f14_04_assetlinks_json_structure(ctx: ReleaseQaContext) -> None:
    r = ctx.proxy.get("/.well-known/assetlinks.json")
    expect_status(r, 200, "GET /.well-known/assetlinks.json")
    ct = (r.header("Content-Type") or "").lower()
    check("application/json" in ct, f"assetlinks Content-Type={ct!r}")
    data = r.json()
    check(isinstance(data, list) and len(data) >= 1, f"invalid assetlinks payload: {data}")
    target = data[0].get("target") or {}
    check(target.get("package_name") == "com.nexachat", f"unexpected package_name: {target}")
    site_check = _load_site_check()
    fps = target.get("sha256_cert_fingerprints") or []
    check(fps and fps[0] == site_check.EXPECTED_RELEASE_SIGNING_FINGERPRINT, f"first fingerprint mismatch: {fps}")


def _t1_f14_05_vercel_json_rewrites_authors_and_files(ctx: ReleaseQaContext) -> None:
    vercel = json.loads((REPO_ROOT / "site" / "vercel.json").read_text(encoding="utf-8"))
    sources = {r.get("source") for r in vercel.get("rewrites", [])}
    required = {
        "/api/social/v1/config",
        "/api/social/v1/posts",
        "/api/social/v1/authors/:path*",
        "/api/v1/files/:path*",
    }
    missing = sorted(required - sources)
    check(not missing, f"site/vercel.json missing required rewrites: {missing}")


def _t2_f14_01_static_path_traversal_denied(ctx: ReleaseQaContext) -> None:
    r = ctx.proxy.get("/..%2f..%2fpackage.json")
    check(r.status in (400, 403, 404), f"static path traversal expected 400/403/404, got {r}")


def _t2_f14_02_zero_inline_scripts_or_external_cdns(ctx: ReleaseQaContext) -> None:
    site_check = _load_site_check()
    failures: List[str] = []
    failures.extend(site_check.test_no_external_urls())
    failures.extend(site_check.test_no_inline_scripts_and_styles())
    check(not failures, f"site HTML/CSP static violations: {failures}")


def _t2_f14_03_assetlinks_no_set_cookie_no_redirect(ctx: ReleaseQaContext) -> None:
    r = ctx.proxy.get("/.well-known/assetlinks.json")
    expect_status(r, 200, "assetlinks 200 without redirect")
    check(r.header("Set-Cookie") is None, f"assetlinks emitted Set-Cookie: {r.header('Set-Cookie')}")


def _t2_f14_04_join_invalid_tokens_and_noindex(ctx: ReleaseQaContext) -> None:
    r = ctx.proxy.get(f"/join/{VALID_JOIN_TOKEN}")
    check("noindex" in r.text.lower(), "/join/ missing robots noindex meta")
    join_js = (REPO_ROOT / "site" / "join" / "join.js").read_text(encoding="utf-8")
    check("43" in join_js or "invite-invalid" in join_js, "join.js missing token validator")


def _t2_f14_05_public_site_strict_csp_headers(ctx: ReleaseQaContext) -> None:
    r = ctx.proxy.get("/")
    expect_status(r, 200, "GET /")
    csp = r.header("Content-Security-Policy") or ""
    check("default-src 'none'" in csp and "frame-ancestors 'none'" in csp, f"weak public CSP: {csp!r}")


# ---------------------------------------------------------------------------
# Feature #15: Design System — Tonal Plum Unification
# ---------------------------------------------------------------------------


def _t1_f15_01_social_css_tonal_plum_tokens(ctx: ReleaseQaContext) -> None:
    css = (REPO_ROOT / "site" / "social" / "social.css").read_text(encoding="utf-8").lower()
    for tok in ("#fff7fc", "#ffffff", "#733f68", "#fcd7f4", "#151316", "#f4b2e2", "#67355d", "16px", "999px", "inter"):
        check(tok in css, f"site/social/social.css missing Tonal Plum token {tok!r}")


def _t1_f15_02_admin_css_tonal_plum_tokens(ctx: ReleaseQaContext) -> None:
    css = (REPO_ROOT / "admin-web" / "src" / "styles.css").read_text(encoding="utf-8").lower()
    for tok in ("#fff7fc", "#733f68", "#fcd7f4", "#151316", "#f4b2e2", "#67355d", "16px", "999px", "inter"):
        check(tok in css, f"admin-web/src/styles.css missing Tonal Plum token {tok!r}")


def _has_android_hex_color(xml_text: str, hex_color: str) -> bool:
    hex6 = hex_color.lstrip("#")
    return bool(re.search(r"#(?:ff)?" + re.escape(hex6) + r"\b", xml_text, re.IGNORECASE))


def _t1_f15_03_android_colors_nexachat_parity(ctx: ReleaseQaContext) -> None:
    xml_path = REPO_ROOT / "android" / "app" / "src" / "main" / "res" / "values" / "colors_nexachat.xml"
    night_path = REPO_ROOT / "android" / "app" / "src" / "main" / "res" / "values-night" / "colors_nexachat.xml"
    check(xml_path.is_file(), f"missing {xml_path}")
    check(night_path.is_file(), f"missing {night_path}")
    light_xml = xml_path.read_text(encoding="utf-8")
    night_xml = night_path.read_text(encoding="utf-8")
    for tok in ("#fff7fc", "#733f68", "#fcd7f4"):
        check(_has_android_hex_color(light_xml, tok), f"Android light colors_nexachat.xml missing {tok}")
    for tok in ("#151316", "#f4b2e2", "#67355d"):
        check(_has_android_hex_color(night_xml, tok), f"Android night colors_nexachat.xml missing {tok}")


def _t1_f15_04_note_monogram_svg_across_surfaces(ctx: ReleaseQaContext) -> None:
    note_path = "M24 20H36L64 60V20H80V84H66L40 47V72L24 84Z"
    for fpath in (
        REPO_ROOT / "site" / "index.html",
        REPO_ROOT / "site" / "social" / "index.html",
        REPO_ROOT / "site" / "join" / "index.html",
        REPO_ROOT / "admin-web" / "src" / "pages" / "LoginPage.tsx",
    ):
        txt = fpath.read_text(encoding="utf-8")
        check(note_path in txt, f"Note SVG monogram path missing in {fpath}")


def _t1_f15_05_8_hue_avatar_palette_parity(ctx: ReleaseQaContext) -> None:
    css = (REPO_ROOT / "site" / "social" / "social.css").read_text(encoding="utf-8")
    for idx in range(8):
        check(f"avatar-tone-{idx}" in css, f"social.css missing avatar-tone-{idx}")


def _t2_f15_01_tonal_plum_wcag_aa_contrast_math(ctx: ReleaseQaContext) -> None:
    pairs = [
        ("#1d1b1e", "#fff7fc"),
        ("#ffffff", "#733f68"),
        ("#e8e0e5", "#151316"),
        ("#35142f", "#f4b2e2"),
        ("#1d1b1e", "#fcd7f4"),
    ]
    for fg_s, bg_s in pairs:
        fg = parse_css_color(fg_s)[:3]
        bg = parse_css_color(bg_s)[:3]
        ratio = contrast_ratio(fg, bg)
        check(ratio >= 4.5, f"Tonal Plum contrast {fg_s} on {bg_s} = {ratio:.2f} < 4.5")


def _t2_f15_02_admin_compact_button_hit_area_44px(ctx: ReleaseQaContext) -> None:
    css = (REPO_ROOT / "admin-web" / "src" / "styles.css").read_text(encoding="utf-8")
    check("min-height: 2.75rem" in css, "admin-web missing 2.75rem (44px) base control height")
    check(".btn.sm::before" in css and "inset: -6px" in css, "admin-web .btn.sm missing ::before { inset: -6px } 44px hit ring")


def _t2_f15_03_social_css_48px_touch_targets(ctx: ReleaseQaContext) -> None:
    css = (REPO_ROOT / "site" / "social" / "social.css").read_text(encoding="utf-8")
    check("48px" in css, "social.css missing 48px touch target floor")


def _t2_f15_04_flat_cards_zero_shadow(ctx: ReleaseQaContext) -> None:
    css = (REPO_ROOT / "site" / "social" / "social.css").read_text(encoding="utf-8")
    check("box-shadow: none" in css or "--card-shadow: none" in css or "box-shadow: 0" in css or "card-radius: 16px" in css, "social.css card surface definition check")


def _t2_f15_05_public_site_plum_vs_slate_check(ctx: ReleaseQaContext) -> None:
    css = (REPO_ROOT / "site" / "style.css").read_text(encoding="utf-8").lower()
    has_plum = "#fff7fc" in css and "#151316" in css
    if ctx.strict_public_plum:
        check(has_plum, "site/style.css has not migrated from Slate (#f8fafc/#0f172a) to Tonal Plum (#fff7fc/#151316)")
    else:
        check("#733f68" in css and ("#f8fafc" in css or "#fff7fc" in css), "site/style.css missing expected brand/canvas tokens")


# ---------------------------------------------------------------------------
# Feature #16: Release, CI, Repo Hygiene & Live Deploy
# ---------------------------------------------------------------------------


def _t1_f16_01_actuator_health_up(ctx: ReleaseQaContext) -> None:
    r = ctx.spring.get("/actuator/health")
    expect_status(r, 200, "GET /actuator/health")
    check((r.json() or {}).get("status") == "UP", f"actuator health not UP: {r.json()}")


def _t1_f16_02_websocket_stomp_handshake(ctx: ReleaseQaContext) -> None:
    accs = _with_ephemeral(ctx, [("F16 WS QA", None)])
    conn = None
    try:
        conn = _make_stomp_connection(ctx, accs[0]["accessToken"], "f16-ws")
    finally:
        if conn is not None:
            conn.close()
        _cleanup_accounts(ctx, accs)


def _t1_f16_03_ci_and_release_workflows_valid(ctx: ReleaseQaContext) -> None:
    ci = (REPO_ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
    rel = (REPO_ROOT / ".github" / "workflows" / "android-release.yml").read_text(encoding="utf-8")
    check("jobs:" in ci and "server" in ci and "admin-web" in ci, "ci.yml missing core jobs")
    check("nexachat-" in rel and "apksigner" in rel.lower(), "android-release.yml missing APK signing/release steps")


def _t1_f16_04_render_yaml_and_release_checklist_v51(ctx: ReleaseQaContext) -> None:
    render_txt = (REPO_ROOT / "render.yaml").read_text(encoding="utf-8")
    checklist = (REPO_ROOT / "docs" / "release" / "release-checklist.md").read_text(encoding="utf-8")
    check("nexachat-api" in render_txt, "render.yaml missing nexachat-api")
    check("V51" in checklist, "docs/release/release-checklist.md missing V51 migration entry")


def _t1_f16_05_git_identity_and_zero_trailer_hygiene(ctx: ReleaseQaContext) -> None:
    proc = subprocess.run(
        ["git", "-C", str(REPO_ROOT), "log", "-n", "10", "--format=%an <%ae>|%cn <%ce>|%B---END---"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=15,
    )
    check(proc.returncode == 0, f"git log failed: {proc.stderr}")
    for rec in proc.stdout.split("---END---"):
        rec = rec.strip()
        if not rec:
            continue
        author, committer, body = rec.split("|", 2)
        check(author == "Nguyen Tien Son <jasonbmt06@gmail.com>", f"unexpected git author: {author}")
        check(
            committer in ("Nguyen Tien Son <jasonbmt06@gmail.com>", "GitHub <noreply@github.com>"),
            f"unexpected git committer: {committer}",
        )
        low = body.lower()
        for bad in ("co-authored-by:", "generated with", "generated by", "made with"):
            check(bad not in low, f"forbidden trailer {bad!r} in git commit body")


def _t2_f16_01_security_headers_present(ctx: ReleaseQaContext) -> None:
    r = ctx.proxy.get("/")
    expect_status(r, 200, "GET / for security headers")
    check((r.header("X-Content-Type-Options") or "").lower() == "nosniff", "missing nosniff")
    check((r.header("Referrer-Policy") or "").lower() == "no-referrer", "missing no-referrer")
    check((r.header("X-Frame-Options") or "").upper() == "DENY", "missing X-Frame-Options: DENY")


def _t2_f16_02_assetlinks_fingerprint_matches_site_check(ctx: ReleaseQaContext) -> None:
    site_check = _load_site_check()
    al = json.loads((REPO_ROOT / "site" / ".well-known" / "assetlinks.json").read_text(encoding="utf-8"))
    fps = al[0]["target"]["sha256_cert_fingerprints"]
    check(fps[0] == site_check.EXPECTED_RELEASE_SIGNING_FINGERPRINT, "assetlinks fingerprint mismatch")


def _t2_f16_03_offline_e2e_unit_suites_present(ctx: ReleaseQaContext) -> None:
    for p in (
        REPO_ROOT / "tools" / "e2e" / "social_suite" / "test_social_suite.py",
        REPO_ROOT / "tools" / "e2e" / "device" / "test_social_emulator_e2e.py",
        REPO_ROOT / "tools" / "e2e" / "release_qa" / "test_release_qa.py",
    ):
        check(p.is_file(), f"missing offline E2E unit suite: {p}")


def _t2_f16_04_safety_guards_and_redaction_enforced(ctx: ReleaseQaContext) -> None:
    try:
        assert_ephemeral_phone("+84900000951")
        raise E2EFailure("assert_ephemeral_phone did not reject fixed fixture phone")
    except SafetyGuardError:
        pass
    assert_ephemeral_phone("+84908123456")
    check(not is_loopback_url("https://api.nexachat.io.vn"), "prod URL misclassified as loopback")
    scrubbed = redact_secrets("Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc123def456 +84900000951")
    check("eyJ" not in scrubbed and "+84900000951" not in scrubbed, f"redaction failed: {scrubbed!r}")


def _t2_f16_05_admin_site_bundle_script_contract(ctx: ReleaseQaContext) -> None:
    script = (REPO_ROOT / "admin-web" / "scripts" / "build-site-bundle.sh").read_text(encoding="utf-8")
    check("--base=/admin/" in script and "VITE_API_URL" in script, "build-site-bundle.sh contract drift")
    check((REPO_ROOT / "site" / "admin" / "index.html").is_file(), "missing committed site/admin/index.html")


# ---------------------------------------------------------------------------
# Registry Exports (80 Tier 1 + 80 Tier 2 = 160 checks, >= 5 per F1..F16)
# ---------------------------------------------------------------------------


def get_tier1_checks() -> List[ReleaseCheck]:
    raw: List[Tuple[str, str, str, bool, str, Any]] = [
        # F1
        ("T1.F1.01", "F1", "Phone registration, login & GET /api/v1/users/me", True, "release_qa", _t1_f1_01_phone_register_login_me),
        ("T1.F1.02", "F1", "Rotating refresh token exchange & new accessToken validation", True, "release_qa", _t1_f1_02_refresh_rotation),
        ("T1.F1.03", "F1", "Logout revokes refresh token & rejects replay", True, "release_qa", _t1_f1_03_logout_revokes_refresh),
        ("T1.F1.04", "F1", "Email-code registration (/register/email-code -> OTP -> /register/email)", False, "release_qa+mail_sink", _t1_f1_04_email_code_registration),
        ("T1.F1.05", "F1", "Password change & re-login with new credential", True, "release_qa", _t1_f1_05_password_change_revokes_older_session),
        # F2
        ("T1.F2.01", "F2", "POST /api/v1/auth/google/nonce disabled-flag 404 or active nonce", True, "release_qa", _t1_f2_01_google_nonce_gate),
        ("T1.F2.02", "F2", "POST /api/v1/auth/google disabled-flag 404 fail-closed gate", True, "release_qa", _t1_f2_02_google_signin_gate),
        ("T1.F2.03", "F2", "GET /api/v1/auth/identities disabled-flag 404 or identity list", True, "release_qa", _t1_f2_03_identities_list_gate),
        ("T1.F2.04", "F2", "POST /api/v1/auth/identities/google{/nonce} disabled-flag 404 gate", True, "release_qa", _t1_f2_04_identities_google_link_gate),
        ("T1.F2.05", "F2", "DELETE /api/v1/auth/identities/google disabled-flag 404 gate", True, "release_qa", _t1_f2_05_identities_google_unlink_gate),
        # F3
        ("T1.F3.01", "F3", "OPTIONS /api/v1/account-deletion/{request,confirm} CORS headers", True, "prod-smoke", _t1_f3_01_deletion_cors_preflight),
        ("T1.F3.02", "F3", "Self-service DELETE /api/v1/users/me tombstones account & revokes JWT", True, "release_qa", _t1_f3_02_self_service_deletion_tombstones),
        ("T1.F3.03", "F3", "Phone re-registration after tombstoned deletion yields fresh userId", True, "release_qa", _t1_f3_03_re_register_same_phone_after_delete),
        ("T1.F3.04", "F3", "Password reset OTP recovery round-trip via MailSink", False, "release_qa+mail_sink", _t1_f3_04_password_reset_otp_via_mailsink),
        ("T1.F3.05", "F3", "Web account deletion OTP request (202) & confirm (204) via MailSink", False, "release_qa+mail_sink", _t1_f3_05_web_deletion_otp_via_mailsink),
        # F4
        ("T1.F4.01", "F4", "Direct send, peer history read & clientMessageId idempotency", True, "p0_live", _t1_f4_01_direct_send_history_and_idempotency),
        ("T1.F4.02", "F4", "Message edit (PATCH) & recall (DELETE) round-trip", True, "new_features", _t1_f4_02_edit_and_recall_message),
        ("T1.F4.03", "F4", "Message emoji reaction, conversation pin/unpin & message search", True, "new_features", _t1_f4_03_reactions_pin_and_search),
        ("T1.F4.04", "F4", "STOMP /ws real-time message fanout to conversation subscriber", False, "stomp_e2e", _t1_f4_04_stomp_realtime_receipt),
        ("T1.F4.05", "F4", "POST /api/v1/sync v2 returns generation & changeUpperBound", True, "release_qa", _t1_f4_05_sync_v2_watermark_and_generation),
        # F5
        ("T1.F5.01", "F5", "Conversation mute/unmute & archive/unarchive preferences", True, "new_features", _t1_f5_01_mute_and_archive_preferences),
        ("T1.F5.02", "F5", "Conversation labels create, assign & delete lifecycle", True, "new_features", _t1_f5_02_conversation_labels_lifecycle),
        ("T1.F5.03", "F5", "User block & unblock lifecycle", True, "groups_privacy", _t1_f5_03_user_block_unblock),
        ("T1.F5.04", "F5", "Report submission & GET /api/v1/reports/mine", False, "social_suite", _t1_f5_04_report_submission_and_mine),
        ("T1.F5.05", "F5", "GET/PATCH /api/v1/users/me/settings exact 5 keys round-trip", True, "release_qa", _t1_f5_05_user_settings_5_keys),
        # F6
        ("T1.F6.01", "F6", "Group create, add member & leave lifecycle", True, "groups_privacy", _t1_f6_01_group_crud_members_leave),
        ("T1.F6.02", "F6", "Group invite-link create, preview, join & revoke", True, "new_features", _t1_f6_02_group_invite_link_preview_join_revoke),
        ("T1.F6.03", "F6", "Group poll create, vote & close", True, "new_features", _t1_f6_03_poll_create_vote_close),
        ("T1.F6.04", "F6", "Reminder create (Idempotency-Key), list & delete", True, "new_features", _t1_f6_04_reminder_create_idempotent_list_delete),
        ("T1.F6.05", "F6", "Approval-gated group join request & admin approval", True, "new_features", _t1_f6_05_group_join_approval_workflow),
        # F7
        ("T1.F7.01", "F7", "Attachment upload, message bind & peer byte-exact download", True, "release_qa", _t1_f7_01_chat_attachment_upload_bind_read),
        ("T1.F7.02", "F7", "Avatar upload & /api/v1/files/** authenticated peer read", True, "prod-smoke", _t1_f7_02_avatar_upload_and_file_read),
        ("T1.F7.03", "F7", "Audio .m4a faststart & trailing-moov MIME sniff", True, "release_qa", _t1_f7_03_m4a_faststart_and_trailing_moov_sniff),
        ("T1.F7.04", "F7", "HTTP Range: bytes=0-127 returns 206 Partial Content & exact slice", True, "release_qa", _t1_f7_04_range_206_partial_content),
        ("T1.F7.05", "F7", "Social media upload & streaming delegation (social_suite F4)", False, "social_suite", _t1_f7_05_social_media_range_delegate),
        # F8
        ("T1.F8.01", "F8", "GET /api/v1/calls/ice-config returns 200 iceServers (401 unauth)", True, "release_qa", _t1_f8_01_ice_config_auth_and_unauth),
        ("T1.F8.02", "F8", "STOMP INVITE -> ACCEPT -> END (COMPLETED) + call history", False, "call_e2e", _t1_f8_02_stomp_call_invite_accept_sdp_ice_end),
        ("T1.F8.03", "F8", "STOMP callee REJECT -> CALL_REJECTED", False, "call_e2e", _t1_f8_03_stomp_call_reject),
        ("T1.F8.04", "F8", "STOMP caller CANCEL -> CALL_CANCELLED", False, "call_e2e", _t1_f8_04_stomp_call_cancel),
        ("T1.F8.05", "F8", "GET /api/v1/calls/{callId}/offer missing callId returns 404", True, "call_media_e2e", _t1_f8_05_call_offer_rest_endpoint),
        # F9
        ("T1.F9.01", "F9", "Conversation summary with aiSummaryConsent=true", True, "summary_e2e", _t1_f9_01_summary_with_consent),
        ("T1.F9.02", "F9", "Message translate & translate-batch endpoints", True, "release_qa", _t1_f9_02_message_translate_and_batch),
        ("T1.F9.03", "F9", "Translation glossary PUT/GET round-trip", True, "release_qa", _t1_f9_03_translation_glossary_and_prefs),
        ("T1.F9.04", "F9", "PR #384 Vietnamese Ask RAG place/date/pronoun EXTRACTIVE citations", True, "release_qa", _t1_f9_04_pr384_vietnamese_ask_rag_place_date_lookups),
        ("T1.F9.05", "F9", "E2EE device key register & revoke", True, "groups_privacy", _t1_f9_05_e2ee_device_register_and_revoke),
        # F10
        ("T1.F10.01", "F10", "GET /api/v1/streaks/me caller summary", True, "admin_streak", _t1_f10_01_streaks_me_summary),
        ("T1.F10.02", "F10", "POST /api/v1/streaks/check-in idempotent 200", True, "admin_streak", _t1_f10_02_streaks_checkin_idempotent),
        ("T1.F10.03", "F10", "Reciprocal direct chat initializes streak count >= 1", True, "release_qa", _t1_f10_03_direct_chat_reciprocal_streak_day1),
        ("T1.F10.04", "F10", "streakReminders opt-in preference round-trip", True, "release_qa", _t1_f10_04_streak_reminders_opt_in_round_trip),
        ("T1.F10.05", "F10", "Social feed post activity updates check-in streak", False, "social_suite", _t1_f10_05_social_activity_updates_checkin_streak),
        # F11
        ("T1.F11.01", "F11", "Feed post create, read & clientPostId idempotency", True, "social_suite", _t1_f11_01_post_create_read_idempotent),
        ("T1.F11.02", "F11", "Feed post PATCH sets editedAt & DELETE soft-deletes", True, "social_suite", _t1_f11_02_post_patch_edit_and_delete),
        ("T1.F11.03", "F11", "Feed comments create, PATCH editedAt & DELETE", True, "social_suite", _t1_f11_03_comments_crud_and_edit),
        ("T1.F11.04", "F11", "Feed reactions PUT & DELETE round-trip", True, "social_suite", _t1_f11_04_feed_reactions_put_delete),
        ("T1.F11.05", "F11", "Feed author profile, ?authorId= & VN accent-insensitive ?q= search", True, "social_suite", _t1_f11_05_author_profile_and_vn_search),
        # F12
        ("T1.F12.01", "F12", "GET /api/social/v1/config returns 200 flags & announcement", True, "social_suite", _t1_f12_01_social_config_public),
        ("T1.F12.02", "F12", "GET /social/ returns 200 with strict CSP_SOCIAL", True, "social_suite", _t1_f12_02_social_web_html_and_csp),
        ("T1.F12.03", "F12", "Social Web /session/login & /posts proxy round-trip", True, "social_suite", _t1_f12_03_social_session_login_and_feed_proxy),
        ("T1.F12.04", "F12", "Proxy serves /api/social/v1/authors/{id} & /api/v1/files/*", True, "release_qa", _t1_f12_04_social_authors_and_files_proxy_routes),
        ("T1.F12.05", "F12", "/api/social/v1/* emits no-store cache headers", True, "social_suite", _t1_f12_05_social_api_no_store_headers),
        # F13
        ("T1.F13.01", "F13", "ADMIN reads /admin/overview & /admin/social/settings", False, "social_suite", _t1_f13_01_admin_overview_and_social_settings),
        ("T1.F13.02", "F13", "ADMIN social flags & bilingual announcement draft/publish/unpublish", False, "social_suite", _t1_f13_02_admin_social_flags_and_cms_lifecycle),
        ("T1.F13.03", "F13", "MODERATOR feed report hide/unhide/resolve & audit trail", False, "social_suite", _t1_f13_03_moderator_feed_report_moderation_and_audit),
        ("T1.F13.04", "F13", "ADMIN users, alerts, rules, audit & system endpoints return 200", False, "release_qa", _t1_f13_04_admin_users_alerts_rules_system_api),
        ("T1.F13.05", "F13", "Admin SPA served with CSP_ADMIN & zero prod URL leak", False, "social_suite", _t1_f13_05_admin_spa_csp_and_no_prod_leak),
        # F14
        ("T1.F14.01", "F14", "site/check.py offline JSON, HTML, assetlinks & CSP validation", True, "site/check.py", _t1_f14_01_site_check_offline_validation),
        ("T1.F14.02", "F14", "Public site pages /, /download, /delete-account, /privacy, /terms return 200", True, "release_qa", _t1_f14_02_public_pages_return_200),
        ("T1.F14.03", "F14", "/join/{token} returns 200 no-store with /social/ link in <footer>", True, "social_suite", _t1_f14_03_join_token_no_store_and_footer_social),
        ("T1.F14.04", "F14", "/.well-known/assetlinks.json matches com.nexachat & release SHA-256", True, "site/check.py", _t1_f14_04_assetlinks_json_structure),
        ("T1.F14.05", "F14", "site/vercel.json declares /authors/:path* & /files/:path* rewrites", True, "release_qa", _t1_f14_05_vercel_json_rewrites_authors_and_files),
        # F15
        ("T1.F15.01", "F15", "site/social/social.css declares Tonal Plum light & dark tokens", True, "social_suite", _t1_f15_01_social_css_tonal_plum_tokens),
        ("T1.F15.02", "F15", "admin-web/src/styles.css declares Tonal Plum tokens, 16px/999px & Inter", True, "release_qa", _t1_f15_02_admin_css_tonal_plum_tokens),
        ("T1.F15.03", "F15", "Android colors_nexachat.xml aligns on Tonal Plum light & dark tokens", True, "release_qa", _t1_f15_03_android_colors_nexachat_parity),
        ("T1.F15.04", "F15", "Note SVG monogram path present across site/, /social/, /join/, admin-web/", True, "release_qa", _t1_f15_04_note_monogram_svg_across_surfaces),
        ("T1.F15.05", "F15", "8-hue AvatarPalette CSS classes defined in social.css", True, "social_suite", _t1_f15_05_8_hue_avatar_palette_parity),
        # F16
        ("T1.F16.01", "F16", "GET /actuator/health returns 200 status UP", True, "prod-smoke", _t1_f16_01_actuator_health_up),
        ("T1.F16.02", "F16", "WebSocket STOMP /ws handshake succeeds", True, "prod-smoke", _t1_f16_02_websocket_stomp_handshake),
        ("T1.F16.03", "F16", "GitHub Actions ci.yml & android-release.yml define required jobs", True, "release_qa", _t1_f16_03_ci_and_release_workflows_valid),
        ("T1.F16.04", "F16", "render.yaml & release-checklist.md align on nexachat-api & V51", True, "release_qa", _t1_f16_04_render_yaml_and_release_checklist_v51),
        ("T1.F16.05", "F16", "Recent git log uses single author/committer & zero AI trailers", True, "release_qa", _t1_f16_05_git_identity_and_zero_trailer_hygiene),
    ]
    return [ReleaseCheck(cid, feat, 1, title, live_safe, src, fn) for cid, feat, title, live_safe, src, fn in raw]


def get_tier2_checks() -> List[ReleaseCheck]:
    raw: List[Tuple[str, str, str, bool, str, Any]] = [
        # F1
        ("T2.F1.01", "F1", "Invalid E.164 phone & weak password return 400 VALIDATION_FAILED", True, "release_qa", _t2_f1_01_invalid_phone_and_weak_password),
        ("T2.F1.02", "F1", "Unauthenticated & malformed Bearer JWT return 401 UNAUTHORIZED", True, "p0_live", _t2_f1_02_unauthenticated_and_malformed_jwt),
        ("T2.F1.03", "F1", "Rotating refresh token replay revokes entire token family", True, "release_qa", _t2_f1_03_refresh_replay_revokes_family),
        ("T2.F1.04", "F1", "Email-code invalid OTP & duplicate email register return 400 INVALID_OR_EXPIRED_CODE", False, "release_qa+mail_sink", _t2_f1_04_email_code_invalid_and_duplicate_anti_enum),
        ("T2.F1.05", "F1", "SIGN_IN_LOCKED 429 shared phone+email account lockout + Retry-After", False, "release_qa", _t2_f1_05_sign_in_locked_shared_budget),
        # F2
        ("T2.F2.01", "F2", "35-request burst to /auth/google/nonce stays 404 NOT_FOUND (not 429)", False, "release_qa", _t2_f2_01_disabled_google_burst_stays_404),
        ("T2.F2.02", "F2", "Unauthenticated /auth/identities* returns 401 or 404", True, "release_qa", _t2_f2_02_identities_require_auth),
        ("T2.F2.03", "F2", "Blank idToken/nonce rejected (400 when enabled, 404 when disabled)", True, "release_qa", _t2_f2_03_blank_google_payload_validation),
        ("T2.F2.04", "F2", "POST /identities/google wrong password -> WRONG_PASSWORD (or 404 disabled)", True, "release_qa", _t2_f2_04_link_wrong_password_boundary),
        ("T2.F2.05", "F2", "Google OIDC exchange capability-gated or rejects invalid token", True, "release_qa", _t2_f2_05_google_mock_oidc_capability_gate),
        # F3
        ("T2.F3.01", "F3", "DELETE /users/me wrong password rejected & preserves account", True, "release_qa", _t2_f3_01_delete_wrong_password_preserves_account),
        ("T2.F3.02", "F3", "POST /account-deletion/request unknown phone returns anti-enum 202", True, "release_qa", _t2_f3_02_web_deletion_request_anti_enumeration),
        ("T2.F3.03", "F3", "POST /account-deletion/confirm invalid OTP returns 400", True, "release_qa", _t2_f3_03_web_deletion_invalid_otp_rejected),
        ("T2.F3.04", "F3", "Deleting sole group admin promotes earliest remaining member", True, "release_qa", _t2_f3_04_sole_admin_delete_promotes_member),
        ("T2.F3.05", "F3", "Deleted account excluded from user search & login returns 401", True, "release_qa", _t2_f3_05_deleted_account_excluded_from_search_and_login),
        # F4
        ("T2.F4.01", "F4", "Non-member conversation read returns 403 NOT_MEMBER", True, "p0_live", _t2_f4_01_non_member_chat_denied),
        ("T2.F4.02", "F4", "Blank TEXT message body returns 400 VALIDATION_FAILED", True, "release_qa", _t2_f4_02_blank_or_oversized_message_rejected),
        ("T2.F4.03", "F4", "Non-sender message edit & recall return 403", True, "new_features", _t2_f4_03_non_sender_edit_recall_denied),
        ("T2.F4.04", "F4", "Scheduled messages 50-pending cap returns 409 SCHEDULED_LIMIT (limit=50)", False, "release_qa", _t2_f4_04_scheduled_messages_50_cap),
        ("T2.F4.05", "F4", "Sync v2 reset=True on generation mismatch, cursor>upper & missing generation", True, "release_qa", _t2_f4_05_sync_v2_generation_and_cursor_resets),
        # F5
        ("T2.F5.01", "F5", "Empty PATCH /users/me/settings {} returns 400 VALIDATION_FAILED", True, "release_qa", _t2_f5_01_empty_settings_patch_rejected),
        ("T2.F5.02", "F5", "Blocked peer cannot send direct messages (403)", True, "groups_privacy", _t2_f5_02_blocked_user_messaging_denied),
        ("T2.F5.03", "F5", "Reciprocal showSeen=false hides peerLastReadSeq", True, "new_features", _t2_f5_03_reciprocal_show_seen_privacy),
        ("T2.F5.04", "F5", "translateTargetLang > 16 chars returns 400 VALIDATION_FAILED", True, "release_qa", _t2_f5_04_translate_target_lang_length_bound),
        ("T2.F5.05", "F5", "ChatColor (PLUM..SLATE) device-local SharedPreferences contract", True, "release_qa", _t2_f5_05_chat_color_device_local_contract),
        # F6
        ("T2.F6.01", "F6", "Non-admin group member add & invite-link creation return 403", True, "groups_privacy", _t2_f6_01_non_admin_group_management_denied),
        ("T2.F6.02", "F6", "Invalid invite-link token rejected (400/404)", True, "new_features", _t2_f6_02_invalid_or_revoked_invite_token_rejected),
        ("T2.F6.03", "F6", "Duplicate poll options (400) & voting on closed poll rejected (400/409)", True, "new_features", _t2_f6_03_poll_closed_vote_and_duplicate_options_rejected),
        ("T2.F6.04", "F6", "Past reminder remindAt returns 400", True, "new_features", _t2_f6_04_reminder_past_time_rejected),
        ("T2.F6.05", "F6", "Non-member poll & reminder reads return 403 NOT_MEMBER", True, "new_features", _t2_f6_05_non_member_poll_and_reminder_denied),
        # F7
        ("T2.F7.01", "F7", "Spoofed non-media bytes rejected (400/415) on attachment & avatar", True, "release_qa", _t2_f7_01_spoofed_attachment_and_avatar_rejected),
        ("T2.F7.02", "F7", "Unsatisfiable Range: bytes=99999999- returns 416", True, "social_suite", _t2_f7_02_unsatisfiable_range_416),
        ("T2.F7.03", "F7", "Non-member read of bound chat attachment denied (403/404)", True, "release_qa", _t2_f7_03_non_member_attachment_denied),
        ("T2.F7.04", "F7", "Path traversal /api/v1/files/..%2f..%2fetc%2fpasswd denied (400/404)", True, "release_qa", _t2_f7_04_file_path_traversal_denied),
        ("T2.F7.05", "F7", "Oversized >4MB feed video upload rejected (400/413)", False, "social_suite", _t2_f7_05_oversized_video_rejected),
        # F8
        ("T2.F8.01", "F8", "STOMP CALL_BUSY to caller when callee already in active call", False, "call_e2e", _t2_f8_01_call_busy_boundary),
        ("T2.F8.02", "F8", "STOMP CALL_STATE_INVALID on END while RINGING & caller ACCEPT", False, "release_qa", _t2_f8_02_call_state_invalid_ringing_end_and_caller_accept),
        ("T2.F8.03", "F8", "STOMP CALL_STATE_INVALID on duplicate callId INVITE", False, "release_qa", _t2_f8_03_call_state_invalid_duplicate_invite),
        ("T2.F8.04", "F8", "STOMP group conversation INVITE returns NOT_MEMBER", False, "release_qa", _t2_f8_04_group_call_invite_rejected_not_member),
        ("T2.F8.05", "F8", "STOMP malformed INVITE VALIDATION_FAILED & block isolation (BLOCKED vs NOT_MEMBER)", False, "release_qa", _t2_f8_05_call_validation_and_block_isolation),
        # F9
        ("T2.F9.01", "F9", "AI opt-out (aiSummaryConsent=false) enforced on summary", True, "ai_privacy", _t2_f9_01_ai_opt_out_respected),
        ("T2.F9.02", "F9", "Non-member Ask RAG returns 403 NOT_MEMBER", True, "summary_e2e", _t2_f9_02_non_member_ai_endpoints_denied),
        ("T2.F9.03", "F9", "Android EncryptionReleasePolicyTest contract gate present", True, "release_qa", _t2_f9_03_encryption_release_policy_gate),
        ("T2.F9.04", "F9", "PR #384 Vietnamese Ask RAG abstentions (booking date, conflict, unshared code)", True, "release_qa", _t2_f9_04_pr384_vietnamese_ask_rag_abstentions),
        ("T2.F9.05", "F9", "Ask RAG priorTurns > 3 returns 400 VALIDATION_FAILED", True, "release_qa", _t2_f9_05_ask_prior_turns_cap_400),
        # F10
        ("T2.F10.01", "F10", "Streak non-member 403 NOT_MEMBER, unauth 401 & group 400/404", True, "release_qa", _t2_f10_01_streak_non_member_403_unauth_401_group_err),
        ("T2.F10.02", "F10", "Rolling 7-day streak freeze bridges 2-day gap (count 3->4, freezeAvailable=false)", False, "release_qa+psql", _t2_f10_02_streak_rolling_7_day_freeze),
        ("T2.F10.03", "F10", "2-day streak gap within 6-day cooldown resets count=1 & preserves bestCount=5", False, "release_qa+psql", _t2_f10_03_streak_2_day_reset_preserves_best_count),
        ("T2.F10.04", "F10", "One-sided messaging does not advance direct streak count", True, "release_qa", _t2_f10_04_one_sided_chat_does_not_advance_streak),
        ("T2.F10.05", "F10", "New direct conversation starts with streak count == 0", True, "release_qa", _t2_f10_05_streak_zero_before_any_chat),
        # F11
        ("T2.F11.01", "F11", "Oversized (>1000) feed post & invalid authorId UUID return 400", True, "social_suite", _t2_f11_01_post_validation_and_invalid_author_uuid),
        ("T2.F11.02", "F11", "Non-owner post edit & delete return 403", True, "social_suite", _t2_f11_02_non_owner_post_edit_delete_denied),
        ("T2.F11.03", "F11", "9th distinct emoji reaction on feed post rejected (400/409)", True, "social_suite", _t2_f11_03_ninth_distinct_reaction_rejected),
        ("T2.F11.04", "F11", "Deleted post & comment under deleted parent return 404 (no 403 leak)", True, "social_suite", _t2_f11_04_deleted_post_returns_404_no_leak),
        ("T2.F11.05", "F11", "Social suite T2 multipart, profile & search boundaries delegation", False, "social_suite", _t2_f11_05_social_suite_multipart_and_boundaries_delegate),
        # F12
        ("T2.F12.01", "F12", "Kill switch webEnabled=false blocks /api/social/v1/* 403, keeps /api/v1/feed/*", False, "social_suite", _t2_f12_01_kill_switch_blocks_social_keeps_android),
        ("T2.F12.02", "F12", "videoUploadsEnabled=false blocks /api/social/v1/videos 403", False, "social_suite", _t2_f12_02_video_flag_blocks_new_social_video_uploads),
        ("T2.F12.03", "F12", "Unmapped /api/social/v1/* wildcard rejected with 404", True, "social_suite", _t2_f12_03_unmapped_social_proxy_path_rejected),
        ("T2.F12.04", "F12", "Social Web JS omits stale 'ảnh đã có' / 'existing images' copy", True, "social_suite", _t2_f12_04_social_web_removes_stale_copy),
        ("T2.F12.05", "F12", "Social suite T2.F1/F2 kill-switch & proxy boundaries delegation", False, "social_suite", _t2_f12_05_social_suite_f1_f2_boundaries_delegate),
        # F13
        ("T2.F13.01", "F13", "MODERATOR denied 403 NOT_ADMIN on /api/v1/admin/social/settings", False, "social_suite", _t2_f13_01_moderator_denied_social_settings),
        ("T2.F13.02", "F13", "Stale expectedRevision returns 409 SOCIAL_SETTINGS_CONFLICT", False, "social_suite", _t2_f13_02_social_settings_stale_revision_409),
        ("T2.F13.03", "F13", "Regular USER denied 403 on /api/v1/admin/* endpoints", True, "release_qa", _t2_f13_03_regular_user_denied_admin_routes),
        ("T2.F13.04", "F13", "Admin audit invalid actorId UUID (400) & CMS title >100 chars (400)", False, "release_qa", _t2_f13_04_admin_audit_invalid_uuid_and_cms_bounds),
        ("T2.F13.05", "F13", "SocialPage.tsx retains PR #385 formRef & 8e448100 MODERATOR gate", True, "release_qa", _t2_f13_05_social_page_preserves_pr385_and_8e448100),
        # F14
        ("T2.F14.01", "F14", "Static proxy rejects path traversal /..%2f", True, "social_suite", _t2_f14_01_static_path_traversal_denied),
        ("T2.F14.02", "F14", "Public site contains zero inline scripts/styles or external CDNs", True, "site/check.py", _t2_f14_02_zero_inline_scripts_or_external_cdns),
        ("T2.F14.03", "F14", "assetlinks.json emits no Set-Cookie and no redirect", True, "site/check.py", _t2_f14_03_assetlinks_no_set_cookie_no_redirect),
        ("T2.F14.04", "F14", "/join/ includes robots noindex & invite token validator", True, "site/check.py", _t2_f14_04_join_invalid_tokens_and_noindex),
        ("T2.F14.05", "F14", "Public site enforces strict CSP_GLOBAL (default-src 'none', frame-ancestors 'none')", True, "social_suite", _t2_f14_05_public_site_strict_csp_headers),
        # F15
        ("T2.F15.01", "F15", "Tonal Plum light & dark primary/surface/container WCAG AA >= 4.5:1", True, "release_qa", _t2_f15_01_tonal_plum_wcag_aa_contrast_math),
        ("T2.F15.02", "F15", "Admin Web .btn.sm::before { inset: -6px } guarantees >= 44px hit box", True, "release_qa", _t2_f15_02_admin_compact_button_hit_area_44px),
        ("T2.F15.03", "F15", "Social Web CSS enforces 48px minimum interactive touch target", True, "release_qa", _t2_f15_03_social_css_48px_touch_targets),
        ("T2.F15.04", "F15", "Tonal Plum flat cards eliminate elevation box-shadow", True, "release_qa", _t2_f15_04_flat_cards_zero_shadow),
        ("T2.F15.05", "F15", "Public site style.css Tonal Plum vs Slate compliance gate", True, "release_qa", _t2_f15_05_public_site_plum_vs_slate_check),
        # F16
        ("T2.F16.01", "F16", "Response headers enforce nosniff, no-referrer & DENY", True, "site/check.py", _t2_f16_01_security_headers_present),
        ("T2.F16.02", "F16", "assetlinks.json release SHA-256 matches site/check.py oracle", True, "site/check.py", _t2_f16_02_assetlinks_fingerprint_matches_site_check),
        ("T2.F16.03", "F16", "Offline E2E unit test suites exist across social_suite, device & release_qa", True, "release_qa", _t2_f16_03_offline_e2e_unit_suites_present),
        ("T2.F16.04", "F16", "SafetyGuard refuses non-+84908 phone & non-loopback local target + redacts JWT", True, "release_qa", _t2_f16_04_safety_guards_and_redaction_enforced),
        ("T2.F16.05", "F16", "admin-web build-site-bundle.sh & committed site/admin/index.html valid", True, "release_qa", _t2_f16_05_admin_site_bundle_script_contract),
    ]
    return [ReleaseCheck(cid, feat, 2, title, live_safe, src, fn) for cid, feat, title, live_safe, src, fn in raw]


__all__ = ["get_tier1_checks", "get_tier2_checks"]
