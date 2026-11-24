#!/usr/bin/env python3
"""Manifest + opt-in tombstone cleanup for e2e-created accounts (GH#322).

Every account a harness registers is upserted into a JSON manifest at
``%TEMP%/nexachat-e2e-accounts.json`` (``/tmp`` on POSIX; mode 0600 where the
platform allows it — on Windows the real protection is the user-profile ACL on
%TEMP%). An entry carries the phone, the password needed to re-login for
deletion (plus a short ``password_history`` so a rotation that happened to be
lost mid-run can still authenticate), the backend ``base_url`` it was
registered on, and the registering source — never an access or refresh token.

``cleanup()`` replays the manifest for one backend: for each entry it logs in
fresh (never a cached token), then issues ``DELETE /api/v1/users/me`` — the
same self-tombstone the product's account-deletion flow performs. The
operation is opt-in and best-effort per account, and never alters the journey
verdict. Real backends answer login with an anti-enumeration uniform
``401 INVALID_CREDENTIALS`` for both a drifted password and a self-deleted
account, so a ``401`` tries the password history first, then counts as a
failed login: after ``3`` failed-login cleanups the entry is stale and is
pruned by seed-recording instead of living forever as a zombie. ``404`` at
login stays supported as an explicit already-gone signal. The PROTECTED
refuse-list is a hard guard compared on digit-normalized phones (see
``demo/seed.sql`` and the device harness fixture ranges): those phones are
never sent a DELETE, even when a manifest entry names them.

Cleanup re-reads the manifest before writing, so prunes and counter updates
never clobber accounts a concurrent harness process recorded while cleanup was
running. A cleanup that matches zero entries for the requested backend prints
a warning (a manifest exists, but for other backends) instead of exit-silently.

Usage:
    python tools/e2e/e2e_accounts.py list [--accounts-file FILE]
    python tools/e2e/e2e_accounts.py cleanup --base-url http://localhost:8080 [--dry-run]
    python tools/e2e/e2e_accounts.py record --phone +8499... --password ... [--base-url URL]
"""

from __future__ import annotations

import argparse
import json
import os
import re
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

# Server login is anti-enumeration: a deleted account and a wrong password
# both answer 401, so a 401 cannot tell "dead" from "drifted". After this
# many cleanup passes that could not authenticate the entry, the account is
# stale (deleted or unreachable) and gets pruned from the manifest.
FAILED_LOGIN_PRUNE_THRESHOLD = 3

# Refuse-list: the demo seed users and the dedicated fixture accounts/ranges
# a later run expects to find alive. Checked before every DELETE, even when
# an entry sits in the manifest. Keep in sync with demo/seed.sql (test-
# asserted) and SEED_PHONES in device/stable_journeys.py.
PROTECTED_PHONES = frozenset({
    "+84900000001", "+84900000002", "+84900000003", "+84900000004",  # An / Binh / Chi / Dung (demo seed)
    "+84900000101", "+84900000102",                                  # stomp_e2e dedicated pair
})
PROTECTED_PREFIXES = ("+849000005", "+849000003")    # journey + summary fixture ranges


def _digits(phone: str) -> str:
    """Phone reduced to its trailing digits for refuse-list comparison."""
    digits = re.sub(r"\D", "", str(phone or ""))
    return digits[-12:] if len(digits) > 12 else digits


def manifest_path() -> str:
    override = os.environ.get(MANIFEST_ENV)
    if override:
        return override
    return os.path.join(tempfile.gettempdir(), MANIFEST_NAME)


def is_protected(phone: str) -> bool:
    digits = _digits(phone)
    if not digits:
        return False
    for protected in PROTECTED_PHONES:
        if _digits(protected) == digits:
            return True
    for prefix in PROTECTED_PREFIXES:
        if str(phone or "").startswith(prefix) or digits.startswith(_digits(prefix)):
            return True
    return False


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
        "password_history": [],
        "failed_logins": 0,
        "userId": user_id,
        "displayName": display_name,
        "source": source,
        "base_url": (backend or "").rstrip("/"),
        "addedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }
    accounts = manifest["accounts"]
    for existing in accounts:
        if existing.get("phone") == phone and existing.get("base_url") == entry["base_url"]:
            # Preserve bookkeeping fields from the previous record.
            entry["password_history"] = list(existing.get("password_history") or [])
            entry["failed_logins"] = int(existing.get("failed_logins") or 0)
            existing.update(entry)
            break
    else:
        accounts.append(entry)
    _write_manifest(manifest, target)
    return entry


def rotate_password(phone: str, new_password: str, *, backend: str = "",
                    path: str | None = None) -> bool:
    """Store the final password after the harness rotates it mid-run.

    The previous password moves to a short in-entry history (most recent
    last, capped at 3) so a cleanup whose login hits 401 with the new
    password - e.g. the rotation completed server-side but the harness
    crashed before recording it - can still authenticate with an older
    credential instead of drifting into zombie-hood.
    """
    target = path or manifest_path()
    manifest = load_manifest(target)
    backend = backend.rstrip("/")
    changed = False
    for entry in manifest["accounts"]:
        if entry.get("phone") != phone:
            continue
        if backend and entry.get("base_url") != backend:
            continue
        current = str(entry.get("password") or "")
        if current and current != new_password:
            history = list(entry.get("password_history") or [])
            if current not in history:
                history.append(current)
            entry["password_history"] = history[-3:]
        entry["password"] = new_password
        entry["failed_logins"] = 0
        changed = True
    if changed:
        _write_manifest(manifest, target)
    return changed


def _merge_cleanup_result(target: str, latest: dict, pruned: list[dict],
                           updates: dict) -> dict:
    """Apply prunes/counter updates to the *current* manifest on disk.

    ``pruned`` are (phone, base_url) keys to drop; ``updates`` maps the same
    key to entry fields to persist (failed_logins / password recovered from
    history). Entries added by a concurrent process while cleanup ran are
    preserved verbatim, and pruned entries stay pruned.
    """
    pruned_keys = {(e.get("phone"), e.get("base_url")) for e in pruned}
    accounts = []
    for entry in latest.get("accounts", []):
        key = (entry.get("phone"), entry.get("base_url"))
        if key in pruned_keys:
            continue
        if key in updates:
            entry.update(updates[key])
        accounts.append(entry)
    latest["accounts"] = accounts
    return latest


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
    after trying the password history — the entry is kept with its failed-
    login counter bumped), ``STALE_PRUNED`` (401 on this pass AND the failed-
    login counter reached the threshold - the entry is dead and pruned),
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
    counts = {"deleted": 0, "already_gone": 0, "skipped": 0,
              "stale_pruned": 0, "failed": 0}
    details: list[dict] = []
    last_login = [0.0]
    matched = 0
    pruned: list[dict] = []
    updates: dict = {}

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

    for entry in manifest["accounts"]:
        if entry.get("base_url") not in (backend, "", None):
            continue
        matched += 1
        phone = str(entry.get("phone") or "")
        password = str(entry.get("password") or "")
        history = list(entry.get("password_history") or [])
        key = (entry.get("phone"), entry.get("base_url"))
        verdict: str
        prune = False
        if is_protected(phone):
            verdict = "REFUSED"  # kept in the manifest: the guard is not a janitor
            prune = False
        elif dry_run:
            verdict = "PENDING"
        else:
            try:
                response = relogin(phone, password)
                if response.status == 401 and history and history[-1] != password:
                    # Rotation lost mid-run: try the remembered credentials.
                    for candidate in reversed(history):
                        if candidate == password:
                            continue
                        response = relogin(phone, candidate)
                        if response.status == 200:
                            password = candidate
                            updates[key] = {"password": candidate,
                                            "failed_logins": 0}
                            emit(f"[cleanup] {phone}: restored password from history")
                            break
                        if response.status != 401:
                            break
                if response.status == 404:
                    verdict, prune = "ALREADY_GONE", True
                elif response.status == 401:
                    # Anti-enumeration: 401 covers both drifted password and
                    # a self-deleted account. Count the miss; past the
                    # threshold the entry is stale and gets pruned.
                    misses = int(entry.get("failed_logins") or 0) + 1
                    if misses >= FAILED_LOGIN_PRUNE_THRESHOLD:
                        verdict, prune = "STALE_PRUNED", True
                    else:
                        verdict = f"SKIPPED 401 (miss {misses})"
                        updates[key] = {"failed_logins": misses}
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
        if prune:
            pruned.append(entry)
        if verdict == "TOMBSTONED":
            counts["deleted"] += 1
        elif verdict == "ALREADY_GONE":
            counts["already_gone"] += 1
        elif verdict == "STALE_PRUNED":
            counts["stale_pruned"] += 1
        elif verdict.startswith("FAILED"):
            counts["failed"] += 1
        elif verdict != "PENDING":
            counts["skipped"] += 1
        emit(f"[cleanup] {phone or '?'}: {verdict}")
        details.append({"phone": phone, "verdict": verdict.split(" ")[0],
                        "misses": int(entry.get("failed_logins") or 0)})
    # Re-read: entries recorded by a concurrent process while cleanup ran
    # must survive; only the prunes/counter updates we decided on apply.
    merged = _merge_cleanup_result(target, load_manifest(target), pruned, updates)
    _write_manifest(merged, target)
    warning = None
    total = len(load_manifest(target)["accounts"])
    if matched == 0 and total > 0:
        warning = (f"cleanup matched no manifest entries for {backend}; "
                   f"{total} entries (other backends) were left untouched")
        emit(f"[warn] {warning}")
    return {"backend": backend, "manifest": target, "counts": counts,
            "matched": matched, "warning": warning, "accounts": details}


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
            masked = {**entry, "password": "***", "password_history": ["***"] * len(entry.get("password_history") or [])}
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
    if result["warning"]:
        print(f"warning: {result['warning']}", file=sys.stderr)
    return 0 if not result["counts"]["failed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
