#!/usr/bin/env python3
"""Manifest + opt-in tombstone cleanup for e2e-created accounts (GH#322).

Every account a harness registers is upserted into a JSON manifest at
``%TEMP%/nexachat-e2e-accounts.json`` (``/tmp`` on POSIX; mode 0600 where the
platform allows it). An entry carries the phone, the password needed to
re-login for deletion, the backend ``base_url`` it was registered on, and the
registering source — never an access or refresh token. Password drift between
runs is tracked with ``rotate_password`` so the manifest keeps the *final*
credential.

``cleanup()`` replays the manifest for one backend: for each entry it logs
in fresh (never a cached token), then issues ``DELETE /api/v1/users/me`` —
the same self-tombstone the product's account-deletion flow performs. The
operation is opt-in and best-effort per account: a 404 means the account is
already gone, a 401 means the stored password drifted, and neither fails the
run. The PROTECTED refuse-list is a hard guard: those phones are never sent
a DELETE, even when a manifest entry names them.

Usage:
    python tools/e2e/e2e_accounts.py list [--accounts-file FILE]
    python tools/e2e/e2e_accounts.py cleanup --base-url http://localhost:8080 [--dry-run]
    python tools/e2e/e2e_accounts.py record --phone +8499... --password ... [--base-url URL]
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
import time
from datetime import datetime, timezone

MANIFEST_ENV = "NEXACHAT_E2E_ACCOUNTS_FILE"
MANIFEST_NAME = "nexachat-e2e-accounts.json"

LOGIN_PATH = "/api/v1/auth/login"
DELETE_PATH = "/api/v1/users/me"

# Login is limited to 10 requests / 5 min per IP: pace every login attempt
# and honour a bounded retryAfterSeconds once, like e2e_common.login does.
LOGIN_PACE_SECONDS = 1.0
LOGIN_RETRY_MAX_SECONDS = 120

# Refuse-list: the demo seed users and the dedicated fixture accounts/ranges
# a later run expects to find alive. Checked before every DELETE, even when
# an entry sits in the manifest.
PROTECTED_PHONES = frozenset({
    "+84900000001", "+84900000002", "+84900000003",  # An / Binh / Chi (demo seed)
    "+84900000101", "+84900000102",                  # stomp_e2e dedicated pair
})
PROTECTED_PREFIXES = ("+849000005", "+849000003")    # journey + summary fixture ranges


def manifest_path() -> str:
    override = os.environ.get(MANIFEST_ENV)
    if override:
        return override
    return os.path.join(tempfile.gettempdir(), MANIFEST_NAME)


def is_protected(phone: str) -> bool:
    return phone in PROTECTED_PHONES or phone.startswith(PROTECTED_PREFIXES)


def load_manifest(path: str | None = None) -> dict:
    try:
        with open(path or manifest_path(), "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, ValueError):
        return {"version": 1, "accounts": []}
    if not isinstance(data, dict) or not isinstance(data.get("accounts"), list):
        return {"version": 1, "accounts": []}
    return data


def _write_manifest(manifest: dict, path: str) -> None:
    """Best-effort write at 0600; bookkeeping must never break a journey."""
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(manifest, handle, indent=1)
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass
    except OSError:
        pass


def record(phone: str, password: str, *, source: str = "", backend: str = "",
           user_id=None, display_name: str = "", path: str | None = None) -> dict | None:
    """Upsert a freshly registered account; ``None`` for protected phones."""
    if not phone or is_protected(phone):
        return None
    target = path or manifest_path()
    manifest = load_manifest(target)
    entry = {
        "phone": phone,
        "password": password,
        "userId": user_id,
        "displayName": display_name,
        "source": source,
        "base_url": (backend or "").rstrip("/"),
        "addedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }
    accounts = manifest["accounts"]
    for existing in accounts:
        if existing.get("phone") == phone and existing.get("base_url") == entry["base_url"]:
            existing.update(entry)
            break
    else:
        accounts.append(entry)
    _write_manifest(manifest, target)
    return entry


def rotate_password(phone: str, new_password: str, *, backend: str = "",
                    path: str | None = None) -> bool:
    """Store the final password after the harness rotates it mid-run."""
    target = path or manifest_path()
    manifest = load_manifest(target)
    backend = backend.rstrip("/")
    changed = False
    for entry in manifest["accounts"]:
        if entry.get("phone") != phone:
            continue
        if backend and entry.get("base_url") != backend:
            continue
        entry["password"] = new_password
        changed = True
    if changed:
        _write_manifest(manifest, target)
    return changed


def _retry_after_seconds(response) -> int:
    details = ((response.json() or {}).get("error") or {}).get("details") or {}
    try:
        return int(details.get("retryAfterSeconds") or 0)
    except (TypeError, ValueError):
        return 0


def cleanup(backend: str, log=print, *, path: str | None = None, client=None,
            dry_run: bool = False, pace_seconds: float = LOGIN_PACE_SECONDS,
            sleep=time.sleep) -> dict:
    """Login-then-DELETE every manifest account registered on ``backend``.

    Per-account verdicts: ``TOMBSTONED`` (204, entry pruned), ``ALREADY_GONE``
    (404 at login or delete, pruned), ``SKIPPED`` (protected phone, or 401
    password drift after one re-login — the entry is kept, never fatal),
    ``FAILED`` (any other status or a transport exception). Returns the
    counts dict for ``summary.account_cleanup``; callers must not let the
    result alter the campaign verdict.
    """
    emit = log if callable(log) else print
    target = path or manifest_path()
    manifest = load_manifest(target)
    backend = (backend or "").rstrip("/")
    if client is None:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import e2e_common
        client = e2e_common.Client(backend, timeout=30)
    counts = {"deleted": 0, "already_gone": 0, "skipped": 0, "failed": 0}
    details: list[dict] = []
    last_login = [0.0]

    def login(phone: str, password: str):
        gap = pace_seconds - (time.monotonic() - last_login[0])
        if gap > 0:
            sleep(gap)
        response = client.post(LOGIN_PATH, body={"phone": phone, "password": password})
        last_login[0] = time.monotonic()
        return response

    def relogin(phone: str, password: str):
        """One 429-aware login; returns the Response."""
        response = login(phone, password)
        if response.status == 429:
            wait = min(LOGIN_RETRY_MAX_SECONDS, _retry_after_seconds(response) + 1)
            if wait > 0:
                emit(f"[wait] cleanup login budget exhausted; retrying in {wait}s")
                sleep(wait)
                response = login(phone, password)
        return response

    kept: list[dict] = []
    for entry in manifest["accounts"]:
        if entry.get("base_url") not in (backend, "", None):
            kept.append(entry)
            continue
        phone = str(entry.get("phone") or "")
        password = str(entry.get("password") or "")
        verdict: str
        prune = False
        if is_protected(phone):
            verdict = "REFUSED"
        elif dry_run:
            verdict = "PENDING"
        else:
            try:
                response = relogin(phone, password)
                if response.status == 404:
                    verdict, prune = "ALREADY_GONE", True
                elif response.status == 401:
                    verdict = "SKIPPED"  # manifest password drifted; entry kept
                elif response.status != 200:
                    verdict = f"FAILED login HTTP {response.status}"
                else:
                    token = (response.json() or {}).get("accessToken")
                    if not token:
                        verdict = "FAILED login without accessToken"
                    else:
                        verdict = "SKIPPED"  # DELETE 401 after retry = drift
                        for attempt in range(2):
                            deleted = client.request(
                                "DELETE", DELETE_PATH, token=token,
                                body={"password": password})
                            if deleted.status in (200, 204):
                                verdict, prune = "TOMBSTONED", True
                                break
                            if deleted.status == 404:
                                verdict, prune = "ALREADY_GONE", True
                                break
                            if deleted.status == 401 and attempt == 0:
                                fresh = relogin(phone, password)
                                if fresh.status != 200:
                                    verdict = ("ALREADY_GONE" if fresh.status == 404
                                               else "SKIPPED")
                                    prune = fresh.status == 404
                                    break
                                token = (fresh.json() or {}).get("accessToken")
                                continue
                            if deleted.status != 401:
                                verdict = f"FAILED delete HTTP {deleted.status}"
                            break
            except Exception as error:  # noqa: BLE001 - per-account best effort
                verdict = f"FAILED {type(error).__name__}"
        if verdict == "TOMBSTONED":
            counts["deleted"] += 1
        elif verdict == "ALREADY_GONE":
            counts["already_gone"] += 1
        elif verdict.startswith("FAILED"):
            counts["failed"] += 1
        elif verdict != "PENDING":
            counts["skipped"] += 1
        if not prune:
            kept.append(entry)
        emit(f"[cleanup] {phone or '?'}: {verdict}")
        details.append({"phone": phone, "verdict": verdict})
    manifest["accounts"] = kept
    _write_manifest(manifest, target)
    return {"backend": backend, "manifest": target, "counts": counts,
            "accounts": details}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("list", "record", "cleanup"))
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--accounts-file", default=None)
    parser.add_argument("--phone", default=None)
    parser.add_argument("--password", default=None)
    parser.add_argument("--display-name", default="")
    parser.add_argument("--source", default="manual")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)

    path = args.accounts_file or manifest_path()
    if args.command == "list":
        for entry in load_manifest(path)["accounts"]:
            masked = {**entry, "password": "***"}
            print(json.dumps(masked, ensure_ascii=False))
        return 0
    if args.command == "record":
        if not args.phone or not args.password:
            parser.error("record needs --phone and --password")
        entry = record(args.phone, args.password, source=args.source,
                       backend=args.base_url, display_name=args.display_name,
                       path=path)
        if entry is None:
            print(f"refused: {args.phone} is on the protected list")
            return 1
        print(f"recorded {args.phone}")
        return 0
    result = cleanup(args.base_url, path=path if args.accounts_file else None,
                     dry_run=args.dry_run)
    print(json.dumps(result["counts"]))
    return 0 if not result["counts"]["failed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
