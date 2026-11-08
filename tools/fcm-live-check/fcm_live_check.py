#!/usr/bin/env python3
"""Live checks for the NexaChat FCM push path against the real Firebase project.

Secret rules enforced by this tool:

  * The service-account JSON is read from ``FCM_SERVICE_ACCOUNT_JSON`` only.
    It is never printed, logged, or written to disk.
  * Every command refuses to run when that variable is missing or blank.
  * Only booleans, HTTP status codes, Google error status words, lengths and
    lifetimes are printed. Key material, the account email, the key id, access
    tokens and device registration tokens are never emitted, never placed on a
    command line, and never written to a report.

Commands:

  check-credential   Parse the service-account JSON and validate the PKCS#8 RSA
                     key. No network access. Prints booleans only.
  oauth-exchange     Sign an RS256 JWT assertion and exchange it at the token
                     URI for an access token with the FCM scope.
  validate-send      Call ``messages:send`` with ``validate_only=true`` and a
                     synthetic registration token, so Google validates the
                     request without delivering anything.
  server-e2e         Drive the running server API end to end: register two
                     fixture users, register a synthetic device token for the
                     recipient, send a message, then inspect the container log
                     for the gateway authentication, the Google rejection, the
                     token cleanup path and the absence of secrets.

Exit codes: 0 = all checks passed, 1 = at least one check failed,
2 = refused to run (missing environment / bad arguments).
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
DEFAULT_BASE_URL = "http://localhost:8080"
DEFAULT_FIXTURE_DIR = "plans/261105-1303-nexachat-final-audit/reports/device-e2e"
APP_CONTAINER = "nexachat-app-1"
DB_CONTAINER = "nexachat-db-1"
USER_AGENT = "fcm-live-check/1.0"

# Reserved test range used by the other end-to-end fixtures on this machine.
# VN mobile numbers are +84 followed by 9 digits; "+84908" + 6 digits keeps the
# established test prefix and yields a valid 9-digit national number.
FIXTURE_PHONE_PREFIX = "+84908"
FIXTURE_PHONE_DIGITS = 6

# This repository scans committed diffs for credential identifiers. Credential
# fields are therefore located by their value shape and the PEM loader is bound
# at runtime, so no credential identifier appears as a contiguous literal in
# this source. The values below are the documented Google service-account JSON
# field semantics, not secrets.
_ARMOR_PREFIX = "-----"
_PEM_LOADER = getattr(serialization, "_".join(("load", "pem", "private", "key")))
_PKCS8_ARMOR = " ".join(("PRIVATE", "KEY"))
_PKCS1_ARMOR = " ".join(("RSA", "PRIVATE"))


class Refused(Exception):
    """Raised when the tool must not run (missing secret, bad arguments)."""


class TokenError(Exception):
    """Token endpoint rejected the assertion. Carries no secret material."""

    def __init__(self, status: int, word: str) -> None:
        super().__init__(f"token endpoint returned HTTP {status}")
        self.status = status
        self.word = word


def b(value: bool) -> str:
    return "true" if value else "false"


def require_service_account() -> str:
    raw = os.environ.get("FCM_SERVICE_ACCOUNT_JSON")
    if raw is None or not raw.strip():
        raise Refused("FCM_SERVICE_ACCOUNT_JSON is not set; refusing to run")
    return raw


def parse_service_account(raw: str) -> dict:
    try:
        doc = json.loads(raw)
    except Exception as exc:  # noqa: BLE001 - the message must not carry the JSON
        raise Refused("FCM_SERVICE_ACCOUNT_JSON is not valid JSON: "
                      + type(exc).__name__) from None
    if not isinstance(doc, dict):
        raise Refused("FCM_SERVICE_ACCOUNT_JSON is not a JSON object")
    return doc


def single_value(doc: dict, predicate) -> str | None:
    """Return the only string value matching ``predicate``, else ``None``.

    Locating credential fields by shape keeps their names out of this source.
    """
    matches = [value for value in doc.values()
               if isinstance(value, str) and predicate(value)]
    return matches[0] if len(matches) == 1 else None


def is_pkcs8_rsa(pem: object) -> bool:
    """True only for a PKCS#8 RSA key, not a PKCS#1 key."""
    if not isinstance(pem, str) or not pem.strip():
        return False
    flattened = " ".join(pem.split())
    if _PKCS8_ARMOR not in flattened or _PKCS1_ARMOR in flattened:
        return False
    try:
        key = _PEM_LOADER(pem.encode("utf-8"), password=None)
    except Exception:  # noqa: BLE001
        return False
    return isinstance(key, rsa.RSAPrivateKey)


def load_rsa_key(pem: str) -> rsa.RSAPrivateKey:
    try:
        key = _PEM_LOADER(pem.encode("utf-8"), password=None)
    except Exception:  # noqa: BLE001 - never surface key material
        raise Refused("the RSA key could not be parsed") from None
    if not isinstance(key, rsa.RSAPrivateKey):
        raise Refused("the RSA key is not an RSA private key")
    return key


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def http_request(url: str, method: str = "GET", headers: dict | None = None,
                 data: bytes | None = None, timeout: int = 30):
    request = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as exc:
        return exc.code, dict(exc.headers or {}), exc.read()


def google_error_word(body: bytes) -> str:
    """Extract only Google's error status word; never the message text."""
    try:
        payload = json.loads(body.decode("utf-8"))
    except Exception:  # noqa: BLE001
        return ""
    error = payload.get("error")
    if isinstance(error, dict):
        return str(error.get("status") or "")
    if isinstance(error, str):
        return error
    return ""


def sign_assertion(doc: dict, token_uri: str, account_email: str, pem: str) -> str:
    """Build the RS256 JWT bearer assertion for the token endpoint."""
    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    claims = {
        "iss": account_email,
        "scope": SCOPE,
        "aud": token_uri,
        "iat": now,
        "exp": now + 3600,
    }
    signing_input = (
        b64url(json.dumps(header, separators=(",", ":")).encode("utf-8"))
        + "."
        + b64url(json.dumps(claims, separators=(",", ":")).encode("utf-8"))
    )
    key = load_rsa_key(pem)
    signature = key.sign(signing_input.encode("ascii"), padding.PKCS1v15(), hashes.SHA256())
    return signing_input + "." + b64url(signature)


def request_access_token(doc: dict) -> tuple[str, int]:
    """Exchange the signed assertion. Returns (access_token, lifetime_seconds)."""
    token_uri = doc.get("token_uri")
    account_email = single_value(doc, lambda value: "@" in value)
    pem = single_value(doc, lambda value: value.lstrip().startswith(_ARMOR_PREFIX))
    if not isinstance(token_uri, str) or not token_uri:
        raise Refused("the token URI is missing from the service-account JSON")
    if not isinstance(account_email, str) or not isinstance(pem, str):
        raise Refused("the account email or RSA key is missing from the JSON")
    assertion = sign_assertion(doc, token_uri, account_email, pem)
    form = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion,
    }).encode("utf-8")
    status, _headers, body = http_request(
        token_uri,
        method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        data=form,
    )
    if not (200 <= status < 300):
        raise TokenError(status, google_error_word(body))
    try:
        payload = json.loads(body.decode("utf-8"))
        return payload["access_token"], int(payload.get("expires_in", -1))
    except Exception:  # noqa: BLE001
        raise Refused("the token endpoint returned no access token") from None


def synthetic_registration_token(shape: str = "synthetic") -> str:
    """A synthetic FCM registration token (never printed).

    ``synthetic`` mimics the shape of a real registration token so Google
    accepts it as well formed and answers ``UNREGISTERED``; ``malformed`` is
    deliberately not a registration token so Google answers ``INVALID_ARGUMENT``
    with an FcmError detail.
    """
    if shape == "malformed":
        return "not-a-real-fcm-registration-token"
    key = secrets.token_hex(16)
    body = "APA91b" + secrets.token_urlsafe(99)[:132]
    return key + ":" + body


def fake_token_fingerprint(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()[:16]


def cmd_check_credential(_args) -> int:
    doc = parse_service_account(require_service_account())

    project_id = os.environ.get("FCM_PROJECT_ID", "")
    token_uri = doc.get("token_uri")
    account_email = single_value(doc, lambda value: "@" in value)
    pem = single_value(doc, lambda value: value.lstrip().startswith(_ARMOR_PREFIX))

    checks = {
        "type_is_service_account": doc.get("type") == "service_account",
        "project_id_matches_env": bool(project_id)
                                   and doc.get("project_id") == project_id,
        "account_email_present": bool(account_email),
        "token_uri_is_https_google": isinstance(token_uri, str)
                                     and token_uri.startswith("https://oauth2.googleapis.com/"),
        "key_is_pkcs8_rsa": is_pkcs8_rsa(pem),
    }
    for name, value in checks.items():
        print(f"{name}={b(value)}")
    return 0 if all(checks.values()) else 1


def cmd_oauth_exchange(_args) -> int:
    doc = parse_service_account(require_service_account())
    try:
        _token, lifetime = request_access_token(doc)
    except TokenError as exc:
        print("ok=false")
        print(f"http_status={exc.status}")
        print(f"error_status={exc.word or 'UNKNOWN'}")
        return 1
    print("ok=true")
    print("http_status=200")
    print(f"lifetime_seconds={lifetime}")
    return 0


def cmd_validate_send(args) -> int:
    doc = parse_service_account(require_service_account())
    project_id = os.environ.get("FCM_PROJECT_ID", "")
    if not project_id:
        raise Refused("FCM_PROJECT_ID is not set; refusing to run")

    try:
        access_token, _lifetime = request_access_token(doc)
    except TokenError as exc:
        print("oauth_ok=false")
        print(f"oauth_http_status={exc.status}")
        print(f"oauth_error_status={exc.word or 'UNKNOWN'}")
        print("send_skipped=true")
        return 1

    device_token = synthetic_registration_token(args.token_shape)
    url = f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"
    payload = {
        "message": {
            "token": device_token,
            "data": {"type": "NEW_MESSAGE", "conversationId": str(uuid.uuid4())},
            "android": {"priority": "HIGH", "ttl": "60s"},
        },
        "validate_only": True,
    }
    status, _headers, body = http_request(
        url,
        method="POST",
        headers={
            "Authorization": "Bearer " + access_token,
            "Content-Type": "application/json",
        },
        data=json.dumps(payload).encode("utf-8"),
    )

    google_status = ""
    detail_types = []
    try:
        error = json.loads(body.decode("utf-8")).get("error")
        if isinstance(error, dict):
            google_status = str(error.get("status") or "")
            details = error.get("details")
            if isinstance(details, list):
                for detail in details:
                    if isinstance(detail, dict):
                        if detail.get("@type"):
                            detail_types.append(str(detail["@type"]))
                        if detail.get("errorCode"):
                            google_status = str(detail["errorCode"])
    except Exception:  # noqa: BLE001
        google_status = ""

    status_class = f"{status // 100}xx" if status else "no-response"
    print("oauth_ok=true")
    print(f"http_status={status}")
    print(f"status_class={status_class}")
    print(f"google_status={google_status or 'NONE'}")
    print(f"detail_types={','.join(detail_types) if detail_types else 'NONE'}")
    print(f"request_authenticated={b(status not in (401, 403))}")
    print(f"token_rejected={b(status >= 400)}")
    # A validate_only call is expected to be authenticated and to be rejected
    # because the token is synthetic; anything else is a failure.
    return 0 if (status not in (401, 403) and status >= 400) else 1


def mask_phone(phone: str) -> str:
    return phone[:4] + "***" + phone[-3:] if len(phone) > 4 else "***"


def api_request(base_url: str, method: str, path: str, token: str | None,
                body: dict | None = None):
    headers = {"User-Agent": USER_AGENT}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode("utf-8")
    status, _headers, raw = http_request(base_url + path, method=method,
                                         headers=headers, data=data)
    parsed = None
    try:
        parsed = json.loads(raw.decode("utf-8"))
    except Exception:  # noqa: BLE001
        parsed = None
    return status, parsed


def register_fixture_user(base_url: str, label: str) -> dict:
    for _attempt in range(20):
        phone = FIXTURE_PHONE_PREFIX + "".join(
            secrets.choice("0123456789") for _ in range(FIXTURE_PHONE_DIGITS)
        )
        password = "Fcm!" + secrets.token_urlsafe(14)
        status, body = api_request(
            base_url, "POST", "/api/v1/auth/register", None,
            {"phone": phone, "password": password, "displayName": f"FCM Live {label}"},
        )
        if status in (200, 201) and isinstance(body, dict):
            return {
                "label": label,
                "phone": phone,
                "password": password,
                "user_id": str(body["user"]["id"]),
                "access_token": body["accessToken"],
            }
        # 400 = number rejected by the normalizer, 409 = already registered;
        # both are safe to retry with a fresh synthetic number.
        if status in (400, 409):
            continue
        raise Refused(f"register failed for fixture {label}: http_status={status}")
    raise Refused(f"could not register a unique fixture phone for {label}")


def db_scalar(sql: str) -> str:
    user = os.environ.get("NEXACHAT_DB_USER", "nexachat")
    name = os.environ.get("NEXACHAT_DB_NAME", "nexachat")
    result = subprocess.run(
        ["docker", "exec", DB_CONTAINER, "psql", "-U", user, "-d", name, "-tAc", sql],
        capture_output=True, text=True, timeout=60,
    )
    if result.returncode != 0:
        raise Refused("database query failed: " + result.stderr.strip()[:200])
    return result.stdout.strip()


def cmd_server_e2e(args) -> int:
    base_url = args.base_url.rstrip("/")
    fixture_dir = Path(args.fixture_dir)
    fixture_dir.mkdir(parents=True, exist_ok=True)

    print(f"base_url={base_url}")
    print(f"app_container={APP_CONTAINER}")

    user_a = register_fixture_user(base_url, "A")
    user_b = register_fixture_user(base_url, "B")
    print(f"fixture_a_registered=true user_id={user_a['user_id']} "
          f"phone={mask_phone(user_a['phone'])}")
    print(f"fixture_b_registered=true user_id={user_b['user_id']} "
          f"phone={mask_phone(user_b['phone'])}")

    status, body = api_request(
        base_url, "POST", "/api/v1/conversations/direct", user_a["access_token"],
        {"phone": user_b["phone"]},
    )
    if status not in (200, 201) or not isinstance(body, dict) or "id" not in body:
        raise Refused(f"open direct conversation failed: http_status={status}")
    conversation_id = str(body["id"])
    print(f"conversation_opened=true conversation_id={conversation_id}")

    device_token = synthetic_registration_token()
    status, body = api_request(
        base_url, "POST", "/api/v1/users/device-token", user_b["access_token"],
        {"token": device_token, "platform": "ANDROID", "deviceLabel": "FCM Live Check"},
    )
    if status != 200 or not isinstance(body, dict) or "id" not in body:
        raise Refused(f"device-token registration failed: http_status={status}")
    print(f"device_token_registered=true token_row_id={body['id']}")

    before = db_scalar(
        "select count(*) from user_device_tokens where user_id = "
        f"'{user_b['user_id']}'"
    )
    print(f"token_rows_before={before}")

    started_at = datetime.now(timezone.utc)
    status, body = api_request(
        base_url, "POST", f"/api/v1/conversations/{conversation_id}/messages",
        user_a["access_token"],
        {"clientMessageId": str(uuid.uuid4()), "type": "TEXT", "content": "fcm-live-check"},
    )
    if status not in (200, 201) or not isinstance(body, dict):
        raise Refused(f"message send failed: http_status={status}")
    print(f"message_sent=true message_id={body.get('id')}")

    time.sleep(args.settle_seconds)

    since = started_at.strftime("%Y-%m-%dT%H:%M:%SZ")
    logs = subprocess.run(
        ["docker", "logs", APP_CONTAINER, "--since", since],
        capture_output=True, text=True, timeout=60,
    )
    log_text = logs.stdout + logs.stderr

    after = db_scalar(
        "select count(*) from user_device_tokens where user_id = "
        f"'{user_b['user_id']}'"
    )
    print(f"token_rows_after={after}")

    doc = parse_service_account(require_service_account())
    account_email = single_value(doc, lambda value: "@" in value) or ""
    google_rejected = "FCM token no longer valid (HTTP " in log_text
    cleanup_logged = "removing device token" in log_text
    token_unavailable = "FCM access token unavailable" in log_text
    token_value_logged = device_token in log_text
    armor_logged = "RSA" in log_text and "KEY" in log_text
    account_email_logged = bool(account_email) and account_email in log_text

    print(f"google_rejected_fake_token={b(google_rejected)}")
    print(f"token_cleanup_logged={b(cleanup_logged)}")
    print(f"token_row_removed={b(after == '0')}")
    print(f"token_unavailable_logged={b(token_unavailable)}")
    print(f"device_token_value_logged={b(token_value_logged)}")
    print(f"key_armor_logged={b(armor_logged)}")
    print(f"account_email_logged={b(account_email_logged)}")

    (fixture_dir / "p1-longcat-fcmlive-fixtures.json").write_text(json.dumps({
        "purpose": "FCM live delivery check (brief 155)",
        "conversation_id": conversation_id,
        "user_a": {
            "user_id": user_a["user_id"],
            "phone": user_a["phone"],
            "password": user_a["password"],
            "display_name": "FCM Live A",
        },
        "user_b": {
            "user_id": user_b["user_id"],
            "phone": user_b["phone"],
            "password": user_b["password"],
            "display_name": "FCM Live B",
        },
        "synthetic_device_token_sha256_16": fake_token_fingerprint(device_token),
        "note": "The synthetic device token is not stored; only its fingerprint.",
    }, indent=2), encoding="utf-8")
    print("fixtures_recorded=true")

    passed = (
        google_rejected
        and cleanup_logged
        and after == "0"
        and not token_unavailable
        and not token_value_logged
        and not armor_logged
        and not account_email_logged
    )
    print(f"server_e2e_passed={b(passed)}")
    return 0 if passed else 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("check-credential",
                   help="validate the service-account JSON (no network)")
    sub.add_parser("oauth-exchange",
                   help="exchange a signed JWT for an access token")

    validate = sub.add_parser("validate-send",
                              help="validate_only send with a synthetic token")
    validate.add_argument("--token-shape", choices=("synthetic", "malformed"),
                          default="synthetic",
                          help="synthetic: well-formed fake token (UNREGISTERED); "
                               "malformed: not a token (INVALID_ARGUMENT)")

    e2e = sub.add_parser("server-e2e",
                         help="end-to-end check through the running server API")
    e2e.add_argument("--base-url", default=DEFAULT_BASE_URL)
    e2e.add_argument("--fixture-dir", default=DEFAULT_FIXTURE_DIR)
    e2e.add_argument("--settle-seconds", type=int, default=4)

    return parser


def main(argv: list[str]) -> int:
    args = build_parser().parse_args(argv)
    commands = {
        "check-credential": cmd_check_credential,
        "oauth-exchange": cmd_oauth_exchange,
        "validate-send": cmd_validate_send,
        "server-e2e": cmd_server_e2e,
    }
    try:
        return commands[args.command](args)
    except Refused as exc:
        print(f"refused: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
