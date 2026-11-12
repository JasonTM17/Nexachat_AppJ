#!/usr/bin/env python3
"""Guarded coordinator for the phase 08 stable-journey campaign.

The coordinator reuses the existing adb UI, patch-window, REST and STOMP
helpers. It deliberately runs only journeys whose complete assertions are
available in those helpers; the remaining matrix rows are reported as
BLOCKED_CAPABILITY with their exact missing capability.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib
import json
import re
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[3]
E2E_DIR = ROOT / "tools" / "e2e"
DEVICE_DIR = E2E_DIR / "device"
SERIAL = "emulator-5554"
PACKAGE = "com.nexachat.debug"
MAIN_ACTIVITY = "com.nexachat.MainActivity"
DEFAULT_BASE_URL = "http://127.0.0.1:18093"
DEFAULT_APK = ROOT / "android" / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
PRODUCTION_HOST = "api.nexachat.io.vn"
ATTEMPTS_PER_ROW = 2  # Initial attempt plus the single permitted rerun.

TOKEN_RE = re.compile(r"(?i)\bBearer\s+[^\s,;\"']+")
JWT_RE = re.compile(r"(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,}")
PHONE_RE = re.compile(r"(?<!\w)\+?\d[\d -]{7,}\d(?!\w)")
OTP_RE = re.compile(r"(?<!\d)\d{6}(?!\d)")
SECRET_FIELD_RE = re.compile(
    r"(?i)([\"']?(?:accessToken|refreshToken|authorization|passwordHash|password|otp|code|secret|PGPASSWORD)[\"']?\s*[:=]\s*[\"']?)[^\s,;\"'}]+"
)
REDACT_KEYS = {
    "accessToken", "refreshToken", "token", "authorization", "password",
    "passwordHash", "secret", "otp", "code", "phone", "phoneNumber",
}


class HarnessError(RuntimeError):
    """A harness or environment failure, not evidence of an app defect."""


class EnvironmentFailure(HarnessError):
    """The selected local service, APK or device is unavailable."""


class ProductFailure(AssertionError):
    """A product assertion failed after the row captured evidence."""


class CapabilityBlocked(RuntimeError):
    """The row cannot be asserted with the current approved capabilities."""


@dataclass(frozen=True)
class JourneyRow:
    id: str
    title: str
    matrix_refs: tuple[str, ...]
    preconditions: tuple[str, ...]
    steps: tuple[str, ...]
    assertions: tuple[str, ...]
    captures: tuple[str, ...]
    cleanup: tuple[str, ...]
    rest_plan: tuple[tuple[str, str], ...]
    stomp_plan: tuple[str, ...] = ()
    capability_gap: str | None = None
    read_only: bool = False
    variants: tuple[dict[str, str], ...] = ()

    def __post_init__(self) -> None:
        for name in ("matrix_refs", "preconditions", "steps", "assertions", "captures", "cleanup", "stomp_plan"):
            value = getattr(self, name)
            if isinstance(value, str):
                object.__setattr__(self, name, (value,))
            else:
                object.__setattr__(self, name, tuple(value))
        object.__setattr__(self, "rest_plan", tuple(tuple(item) for item in self.rest_plan))
        object.__setattr__(self, "variants", tuple(dict(item) for item in self.variants))


ACCESSIBILITY_VARIANTS = tuple(
    {"locale": locale, "night": night, "font_scale": scale}
    for locale in ("en-US", "vi-VN")
    for night in ("no", "yes")
    for scale in ("1.0", "2.0")
)


ROWS: tuple[JourneyRow, ...] = (
    JourneyRow(
        id="auth-login",
        title="Auth/session: seeded login and identity",
        matrix_refs=("phase-08:Auth", "c1-5:Auth/session (login slice)"),
        preconditions=("Local readiness is 200", "Debug APK is present", "An↔Binh seed account exists"),
        steps=("Reuse patch_window cold-start/session checks", "Read /users/me through the existing REST client"),
        assertions=("The device session is An", "GET /api/v1/users/me returns An"),
        captures=("home.png", "ui-tree.txt", "logcat.txt", "rest-transcript.json", "summary.json"),
        cleanup=("No server mutation; keep the seeded session state"),
        rest_plan=(("GET", "/actuator/health/readiness"), ("POST", "/api/v1/auth/login"),
                   ("GET", "/api/v1/users/me")),
    ),
    JourneyRow(
        id="auth-session-lifecycle",
        title="Auth/session lifecycle",
        matrix_refs=("phase-08:Auth", "c1-5:Auth/session remainder"),
        preconditions=("Disposable local fixture accounts A/B are available", "Integrated RC installed"),
        steps=("Register/login and reject invalid credentials", "Refresh, logout, logout-all, password change",
               "Restart with saved session and switch A→B while A is paused"),
        assertions=("Accessible UI errors and successful controlled login", "Old tokens rejected and old content absent",
                    "Paused A response cannot cross into B"),
        captures=("paired account UI screenshots", "sanitized REST/STOMP transcripts", "logcat.txt", "summary.json"),
        cleanup=("Remove only generated disposable accounts and their owned rows"),
        rest_plan=(("POST", "/api/v1/auth/register"), ("POST", "/api/v1/auth/login"),
                   ("POST", "/api/v1/auth/refresh"), ("POST", "/api/v1/auth/logout-all"),
                   ("GET", "/api/v1/users/me")),
        capability_gap=("The existing device helpers cover one seeded login only; the required deterministic "
                        "paused-response account-switch barrier and full registration/logout lifecycle are not "
                        "implemented. Do not report the login slice as this full row."),
    ),
    JourneyRow(
        id="otp-forgot-password",
        title="OTP, recovery email and forgot password",
        matrix_refs=("phase-08:Auth/OTP", "c1-5:OTP/forgot password"),
        preconditions=("Disposable local mailbox and local Mailpit are configured", "Local stack uses SMTP Mailpit"),
        steps=("Request recovery email/password reset/deletion codes", "Read codes from Mailpit and exercise wrong, expired, reused and resend cases"),
        assertions=("Mailpit receipt agrees with API state", "Password changes and old sessions fail", "OTP never appears in evidence"),
        captures=("sanitized UI screenshots", "redacted REST transcript", "logcat.txt", "summary.json"),
        cleanup=("Remove only the generated disposable mailbox/account"),
        rest_plan=(("PUT", "/api/v1/users/me/email"), ("POST", "/api/v1/users/me/email/verify"),
                   ("POST", "/api/v1/auth/password-reset/request"),
                   ("POST", "/api/v1/auth/password-reset/confirm")),
        capability_gap=("No existing helper reads or verifies the local Mailpit inbox, and this coordinator does "
                        "not provision the mail sink. OTP delivery and expiry therefore cannot be asserted."),
    ),
    JourneyRow(
        id="chat-send-receive",
        title="Chat send and receive (device + peer REST/STOMP)",
        matrix_refs=("phase-08:Chat", "c1-5:Chat send/receive"),
        preconditions=("An is signed in on the device", "The seeded An↔Binh DIRECT conversation exists"),
        steps=("Subscribe Binh through realtime_probe.FrameReader", "Send a unique text from the device",
               "Have Binh send a unique reply through e2e_common.Client"),
        assertions=("Device renders both messages", "Binh REST history contains exactly one device message",
                    "Binh STOMP receives MESSAGE_CREATED for the device message"),
        captures=("home.png", "chat-after-send.png", "chat-after-receive.png", "logcat.txt",
                  "rest-transcript.json", "stomp-transcript.json", "summary.json"),
        cleanup=("Soft-delete only the two unique messages using their author sessions"),
        rest_plan=(("POST", "/api/v1/conversations/direct"), ("GET", "/api/v1/conversations/{id}/messages"),
                   ("POST", "/api/v1/conversations/{id}/messages"),
                   ("DELETE", "/api/v1/conversations/{id}/messages/{messageId}")),
        stomp_plan=("CONNECT as Binh", "SUBSCRIBE /topic/conversations.{conversationId}",
                    "Assert MESSAGE_CREATED content and message id"),
    ),
    JourneyRow(
        id="chat-interactions",
        title="Chat edits, recalls, reactions, replies, receipts, mentions and forward",
        matrix_refs=("phase-08:Chat", "c1-5:Chat remainder"),
        preconditions=("Integrated RC and disposable local conversations are installed"),
        steps=("Drive edit/recall/reaction/reply/read/mention/forward/typing UI actions", "Verify peer REST/STOMP state"),
        assertions=("One stored message per id", "UI state agrees with server tombstones, receipts and interaction metadata"),
        captures=("paired before/after UI screenshots", "redacted REST/STOMP transcripts", "logcat.txt", "summary.json"),
        cleanup=("Soft-delete only unique test messages and revert owned preferences"),
        rest_plan=(("GET", "/api/v1/conversations/{id}/messages"),
                   ("PATCH", "/api/v1/conversations/{id}/messages/{messageId}"),
                   ("DELETE", "/api/v1/conversations/{id}/messages/{messageId}")),
        stomp_plan=("Observe MESSAGE_UPDATED, MESSAGE_DELETED, REACTION_UPDATED and MESSAGE_READ"),
        capability_gap=("adb_ui exposes hierarchy and taps but there is no maintained driver for the interaction "
                        "menus, reply/mention controls and receipt UI. The send/receive row does not prove these cases."),
    ),
    JourneyRow(
        id="media",
        title="Media, files, voice notes and avatars",
        matrix_refs=("phase-08:Media", "c1-5:Media"),
        preconditions=("Local media fixture storage and a known image/audio/video fixture are available"),
        steps=("Select image/file, record voice, upload and open viewer", "Read bytes and Range response as peer/non-member"),
        assertions=("Persisted byte hash and MIME are correct", "Range is 206 and non-member is denied", "No stuck upload state"),
        captures=("picker/upload/viewer screenshots", "redacted REST transcripts", "logcat.txt", "summary.json"),
        cleanup=("Delete only messages/attachments created by this row when the API permits it"),
        rest_plan=(("POST", "/api/v1/attachments"), ("GET", "/api/v1/attachments/{id}/content"),
                   ("GET", "/api/v1/files/{key}")),
        capability_gap=("No reusable picker/media fixture driver or byte-hash oracle is available in adb_ui; "
                        "the row also includes hardware recording/playback, which an emulator cannot prove."),
    ),
    JourneyRow(
        id="groups-contacts",
        title="Groups, board, invites and contacts",
        matrix_refs=("phase-08:Groups", "c1-5:Groups", "c1-5:Invites", "c1-5:Contacts"),
        preconditions=("Integrated RC and disposable A/B/C fixtures are available"),
        steps=("Create/rename/add/remove/leave group", "Exercise contacts and invite preview/join/approval", "Verify member and non-member state"),
        assertions=("REST membership agrees with UI", "Removed user is denied", "Invite destination and approval state are correct"),
        captures=("group/member/invite screenshots", "redacted REST/STOMP transcripts", "logcat.txt", "summary.json"),
        cleanup=("Remove only groups, links, contacts and accounts created by the row"),
        rest_plan=(("POST", "/api/v1/conversations/group"), ("POST", "/api/v1/conversations/{id}/members"),
                   ("POST", "/api/v1/conversations/{id}/invite-link"), ("GET", "/api/v1/contacts")),
        capability_gap=("The available device helpers do not drive the group/member picker, approval queue or "
                        "invite return path. A REST-only membership result cannot pass the UI journey."),
    ),
    JourneyRow(
        id="polls-reminders",
        title="Polls and reminders",
        matrix_refs=("phase-08:Groups/group board", "c1-5:Polls", "c1-5:Reminders"),
        preconditions=("Disposable group fixture and local scheduler clock are available"),
        steps=("Create poll and vote/change/close from separate actors", "Create/edit/delete a due reminder and observe board"),
        assertions=("Poll counts/version agree with peer REST", "Reminder persisted due/next state agrees with board"),
        captures=("board/poll/reminder screenshots", "redacted REST/STOMP transcripts", "logcat.txt", "summary.json"),
        cleanup=("Delete only the poll/reminder fixtures created by the row"),
        rest_plan=(("POST", "/api/v1/conversations/{id}/polls"), ("POST", "/api/v1/polls/{id}/votes"),
                   ("POST", "/api/v1/conversations/{id}/reminders")),
        capability_gap=("No board UI driver or controlled reminder-clock fixture is provided by the existing "
                        "helpers. Scheduler state cannot be inferred from a screenshot alone."),
    ),
    JourneyRow(
        id="archive-preferences",
        title="Archive browser and conversation preferences",
        matrix_refs=("phase-08:Groups/archive", "c1-5:Archive/labels/preferences"),
        preconditions=("Disposable conversation with message/media fixtures is available"),
        steps=("Browse media/files/links and paging", "Archive/unarchive, mute/unmute, pin and seen/presence preferences"),
        assertions=("Caller-only flags and list contents survive refresh", "Peer cannot see private labels/preferences"),
        captures=("archive/filter/preference screenshots", "redacted REST transcripts", "logcat.txt", "summary.json"),
        cleanup=("Restore only the created conversation's preference flags"),
        rest_plan=(("GET", "/api/v1/conversations/{id}/archive"),
                   ("PUT", "/api/v1/conversations/{id}/archive"),
                   ("GET", "/api/v1/conversations?archived=true")),
        capability_gap=("No maintained UI driver covers archive tabs, filters, paging and undo; API flags alone "
                        "cannot assert the Android browser journey."),
    ),
    JourneyRow(
        id="labels-lifecycle",
        title="Labels and dialog recreation/stop-start",
        matrix_refs=("phase-08:Labels", "c1-5:Archive/labels/preferences"),
        preconditions=("Integrated label-observer fix and a disposable conversation are installed"),
        steps=("Create/assign/filter/delete labels", "Recreate dialog and stop/start with one observer/render"),
        assertions=("No duplicate render/observer", "Label assignment and inbox filter agree with REST"),
        captures=("label/dialog/filter screenshots", "redacted REST transcripts", "logcat.txt", "summary.json"),
        cleanup=("Delete only labels and assignments created by the row"),
        rest_plan=(("GET", "/api/v1/labels"), ("POST", "/api/v1/labels"),
                   ("PUT", "/api/v1/conversations/{id}/labels/{labelId}")),
        capability_gap=("The phase-06 label observer fix is not in this base and no existing device helper "
                        "asserts dialog recreation or exactly one render."),
    ),
    JourneyRow(
        id="calls-signaling",
        title="Call signaling and call UI",
        matrix_refs=("phase-08:Calls", "c1-5:Calls K1"),
        preconditions=("Integrated RC, microphone permission fixture and second STOMP actor are available"),
        steps=("Invite/accept/reject/cancel/busy/timeout", "Inspect call UI and REST call history"),
        assertions=("Call ids, state and history agree", "No leaked microphone or foreground service"),
        captures=("call UI screenshots", "redacted REST/STOMP transcripts", "logcat.txt", "summary.json"),
        cleanup=("End/cancel only the call created by the row"),
        rest_plan=(("GET", "/api/v1/calls/ice-config"), ("GET", "/api/v1/conversations/{id}/calls")),
        stomp_plan=("Use call_e2e.StompConnection for scripted second actor signaling"),
        capability_gap=("Existing call_e2e proves signaling only; the campaign's complete device audio/permission "
                        "journey lacks a maintained call UI driver and must not claim audio quality."),
    ),
    JourneyRow(
        id="calls-injected-notification",
        title="Calls after process death (injected push)",
        matrix_refs=("phase-08:Calls/F4", "c1-5:Calls K2/K3"),
        preconditions=("Phase-07 runtime push injection hook is available on the integrated RC"),
        steps=("Inject labeled CALL_INVITE while process is dead", "Open/accept/decline and verify call history"),
        assertions=("INJECTED push reaches expected action", "No phantom call after process death"),
        captures=("notification-shade and call screenshots", "redacted transcripts", "logcat.txt", "summary.json"),
        cleanup=("Cancel the injected call and clear only its notification"),
        rest_plan=(("GET", "/api/v1/conversations/{id}/calls"),),
        capability_gap=("MockFcmGateway is test-scope only and there is no runtime push hook in the existing "
                        "device tools. STOMP notification survival after am kill is not a substitute for injected FCM."),
    ),
    JourneyRow(
        id="summary-ask",
        title="Conversation summary and Ask this chat",
        matrix_refs=("phase-08:Summary and ask", "c1-5:Summary/Ask"),
        preconditions=("Local extractive engine and consent fixture are enabled", "Known-fact conversation is seeded"),
        steps=("Request summary and Ask answer", "Open cited message and exercise consent/opt-out"),
        assertions=("Citation resolves to source", "No fabricated citation or request without consent"),
        captures=("summary/ask/citation screenshots", "redacted REST transcripts", "logcat.txt", "summary.json"),
        cleanup=("Revert only consent and opt-out settings created by the row"),
        rest_plan=(("POST", "/api/v1/conversations/{id}/summary"),
                   ("POST", "/api/v1/conversations/{id}/ask")),
        capability_gap=("No existing driver seeds and identifies citation UI state. Live model/provider quality is "
                        "explicitly outside local-engine evidence."),
    ),
    JourneyRow(
        id="offline-sync",
        title="Offline outbox, reconnect sync and gap recovery",
        matrix_refs=("phase-08:Sync/offline", "c1-5:Offline/sync O1/O2"),
        preconditions=("Phase-03 sync consumer and local disposable stack are integrated"),
        steps=("Queue offline text/reaction, restart, reconnect", "Reconcile 60 edits/recalls and >100 conversations", "Switch A→B/ABA"),
        assertions=("One final row per clientMessageId", "Bounded pages converge after restart and account switch"),
        captures=("offline/after-reconnect screenshots", "REST/DB oracle transcripts", "logcat.txt", "summary.json"),
        cleanup=("Restore connectivity/settings and remove only generated fixture rows"),
        rest_plan=(("POST", "/api/v1/sync"), ("GET", "/api/v1/conversations/{id}/messages")),
        capability_gap=("The available patch_window helper covers a smaller two-message reconnect smoke only; "
                        "phase-03's integrated Room 14 client and the >100-conversation DB oracle are absent."),
    ),
    JourneyRow(
        id="injected-notifications",
        title="Injected NEW_MESSAGE and REMINDER notifications",
        matrix_refs=("phase-08:Sync and notifications", "c1-5:Notifications O3"),
        preconditions=("Phase-07 runtime injection hook and notification permission are available"),
        steps=("Inject labeled NEW_MESSAGE and REMINDER payloads", "Check sync/board refresh and notification surface"),
        assertions=("Injected payload is labeled INJECTED", "Observed app state matches the payload; no LIVE claim"),
        captures=("notification shade/screens", "redacted transcripts", "logcat.txt", "summary.json"),
        cleanup=("Clear only notifications emitted by these fixture events"),
        rest_plan=(("GET", "/api/v1/conversations"),),
        capability_gap=("There is no runtime push injection endpoint; MockFcmGateway is test-scope only. A local "
                        "service log or live provider receipt cannot substitute for the requested INJECTED evidence."),
    ),
    JourneyRow(
        id="room-13-14-upgrade",
        title="Room 13→14 upgrade with queued sends",
        matrix_refs=("phase-08:Upgrades/Room 13→14", "c1-6:Phase 3 terminal acceptance"),
        preconditions=("Frozen Room 13 APK/data fixture and Room 14 RC APK are identified by hash"),
        steps=("Install Room 13, queue sends, upgrade in place to Room 14"),
        assertions=("Queued sends and data survive migration and converge after reconnect"),
        captures=("before/after screenshots", "APK/data identity manifest", "logcat.txt", "summary.json"),
        cleanup=("Remove only disposable app data/accounts after evidence is saved"),
        rest_plan=(("POST", "/api/v1/sync"), ("GET", "/api/v1/conversations/{id}/messages")),
        capability_gap=("The Room 13 fixture APK and Room 14 RC APK identities are not supplied by this brief; "
                        "the current base is the v1.1.1 candidate."),
    ),
    JourneyRow(
        id="v111-rc-upgrade",
        title="Install v1.1.1 APK then upgrade to v1.2 RC",
        matrix_refs=("phase-08:Upgrades/v1.1.1→RC", "c1-9:release runtime gate"),
        preconditions=("Signed v1.1.1 APK and integrated RC APK have matching package/signature identity"),
        steps=("Verify both APK identities", "Install v1.1.1 then upgrade in place to the RC"),
        assertions=("Signature/package/version progression is valid", "Critical journey survives upgrade"),
        captures=("APK SHA256/certificate manifest", "upgrade screenshots", "logcat.txt", "summary.json"),
        cleanup=("Remove only disposable app data/accounts after evidence is saved"),
        rest_plan=(("GET", "/actuator/health/readiness"),),
        capability_gap=("Neither pair of release artifacts nor their approved fixture target is supplied; never "
                        "use a production base for this destructive upgrade journey."),
    ),
    JourneyRow(
        id="accessibility-sweep",
        title="Accessibility sweep (parameterized EN/VI × light/dark × 100%/200%)",
        matrix_refs=("phase-08:Accessibility", "c1-5:accessibility criteria"),
        preconditions=("Integrated RC and the critical-screen inventory are available"),
        steps=("For each locale/night/font tuple, apply settings, visit critical screens and capture the UI"),
        assertions=("No clipped text or inaccessible critical action", "TalkBack and reduced-motion checks are recorded"),
        captures=("one screenshot per parameter tuple", "ui-tree.txt per tuple", "summary.json"),
        cleanup=("Restore original locale, night mode, font scale and network settings in finally"),
        rest_plan=(("GET", "/actuator/health/readiness"),),
        capability_gap=("The helper can set and restore display settings, but there is no accepted critical-screen "
                        "inventory or TalkBack/reduced-motion oracle. A screenshot-only sweep is not a pass."),
        variants=ACCESSIBILITY_VARIANTS,
    ),
    JourneyRow(
        id="account-deletion",
        title="Account deletion (last; disposable account only)",
        matrix_refs=("phase-08:Account deletion", "c1-5:Account deletion"),
        preconditions=("A generated disposable account is signed in", "Every other selected row has finished"),
        steps=("Assert wrong password does not delete", "Confirm in-app deletion", "Verify old token rejection and phone reuse"),
        assertions=("Account/tokens are disabled", "B retains neutral history", "No deleted-account cache remains"),
        captures=("before/after UI screenshots", "redacted REST transcript", "logcat.txt", "summary.json"),
        cleanup=("Deletion itself is the cleanup; never use seed/owner accounts"),
        rest_plan=(("DELETE", "/api/v1/users/me"), ("POST", "/api/v1/account-deletion/request"),
                   ("POST", "/api/v1/account-deletion/confirm")),
        capability_gap=("The current device tools do not provision and bind a disposable account to the UI, and "
                        "the OTP deletion path also needs Mailpit. The row is deliberately last and never targets seed users."),
    ),
)

ROW_BY_ID = {row.id: row for row in ROWS}

HELPER_CALLS = {
    "auth-login": (
        {"helper": "patch_window.ensure_device_account", "account": "seed An", "fresh_login": "from --reset-app-data"},
        {"helper": "patch_window.ui_nodes", "assert": "resource ids phoneInput/passwordInput/primaryAction and text An"},
        {"helper": "e2e_common.Client.get", "path": "/api/v1/users/me", "actor": "An"},
    ),
    "chat-send-receive": (
        {"helper": "realtime_probe.FrameReader", "actor": "Binh", "subscribe": "/topic/conversations.{id}"},
        {"helper": "adb_ui.Device.tap_node", "target": "text Binh"},
        {"helper": "adb_ui.Device.type_text", "target": "resource-id messageInput", "value": "unique row marker"},
        {"helper": "adb_ui.Device.tap_node", "target": "resource-id sendButton"},
        {"helper": "e2e_common.history", "actor": "Binh", "assert": "exactly one matching row"},
        {"helper": "e2e_common.send_text", "actor": "Binh", "assert": "device renders peer message"},
    ),
}

UI_MARKERS = {
    "auth-login": ("resource-id:phoneInput", "resource-id:passwordInput",
                   "resource-id:primaryAction", "text:An", "text:Binh"),
    "chat-send-receive": ("text:Binh", "resource-id:messageInput", "resource-id:sendButton",
                           "text:<unique outbound message>", "text:<unique inbound message>"),
    "auth-session-lifecycle": ("text:localized login/error state", "text:An", "text:Binh"),
    "otp-forgot-password": ("text:localized OTP verification state", "text:password changed"),
    "chat-interactions": ("text:edited message", "text:deleted tombstone", "text:reaction/reply state"),
    "media": ("text:unique media fixture name", "text:voice duration"),
    "groups-contacts": ("text:unique group name", "text:member list", "text:invite destination"),
    "polls-reminders": ("text:unique poll question", "text:unique reminder title"),
    "archive-preferences": ("text:Archive", "text:unique archived conversation"),
    "labels-lifecycle": ("text:unique label name", "text:one label render after recreation"),
    "calls-signaling": ("text:call state", "text:call history"),
    "calls-injected-notification": ("text:Incoming voice call", "text:Accept", "text:Decline"),
    "summary-ask": ("text:summary heading", "text:citation source message"),
    "offline-sync": ("text:unique offline message", "text:converged message order"),
    "injected-notifications": ("text:notification payload marker", "text:refreshed board state"),
    "room-13-14-upgrade": ("text:queued message before and after migration"),
    "v111-rc-upgrade": ("text:Sign in", "text:Binh"),
    "accessibility-sweep": ("ui-tree marker for each of 8 locale/theme/font variants",),
    "account-deletion": ("text:localized deletion confirmation", "text:Sign in"),
}


def validate_serial(serial: str) -> str:
    if serial != SERIAL:
        raise HarnessError(
            f"refusing serial {serial!r}; this campaign is reserved for {SERIAL} and must never touch another device"
        )
    return serial


def validate_base_url(base_url: str, selected: Iterable[JourneyRow], allow_production_readonly: bool) -> None:
    parsed = urlsplit(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise HarnessError("--base-url must be an absolute http:// or https:// URL")
    if parsed.username or parsed.password:
        raise HarnessError("--base-url must not embed credentials")
    if parsed.query or parsed.fragment:
        raise HarnessError("--base-url must not embed query parameters or fragments")
    if parsed.hostname.casefold() == PRODUCTION_HOST:
        if not allow_production_readonly:
            raise HarnessError(
                f"refusing production base {PRODUCTION_HOST}; use a local disposable stack"
            )
        writable = [row.id for row in selected if not row.read_only]
        if writable:
            raise HarnessError(
                "--allow-production-readonly permits only rows explicitly marked read-only; "
                f"selected rows mutate state: {', '.join(writable)}"
            )


def derive_emulator_base_url(base_url: str) -> str:
    parsed = urlsplit(base_url)
    host = parsed.hostname or ""
    if host.casefold() in {"127.0.0.1", "localhost"}:
        authority = "10.0.2.2"
        if parsed.port:
            authority += f":{parsed.port}"
        if parsed.username or parsed.password:
            raise HarnessError("--base-url must not embed credentials")
        return f"{parsed.scheme}://{authority}{parsed.path}".rstrip("/")
    return base_url.rstrip("/")


def select_rows(value: str | None) -> tuple[JourneyRow, ...]:
    if not value:
        return ROWS
    requested = [item.strip() for item in value.split(",") if item.strip()]
    if not requested:
        raise HarnessError("--rows did not name any rows")
    unknown = sorted(set(requested) - set(ROW_BY_ID))
    if unknown:
        raise HarnessError(f"unknown row id(s): {', '.join(unknown)}")
    if len(requested) != len(set(requested)):
        raise HarnessError("--rows contains duplicate ids")
    chosen = set(requested)
    # Registry order guarantees account deletion remains last even if the user
    # listed it earlier on the command line.
    return tuple(row for row in ROWS if row.id in chosen)


def sanitize_text(value: str) -> str:
    value = TOKEN_RE.sub("Bearer [REDACTED]", value)
    value = JWT_RE.sub("[REDACTED_JWT]", value)
    value = SECRET_FIELD_RE.sub(r"\1[REDACTED]", value)
    value = PHONE_RE.sub("[REDACTED_PHONE]", value)
    value = OTP_RE.sub("[REDACTED_CODE]", value)
    return value


def redact(value: Any, key: str | None = None) -> Any:
    if key is not None:
        folded = key.casefold()
        if key in REDACT_KEYS or folded in {item.casefold() for item in REDACT_KEYS}:
            return "[REDACTED]"
        if ("token" in folded or "password" in folded or "secret" in folded
                or "authorization" in folded or "otp" in folded or "code" in folded):
            return "[REDACTED]"
    if isinstance(value, dict):
        return {str(k): redact(v, str(k)) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [redact(item) for item in value]
    if isinstance(value, str):
        return sanitize_text(value)
    return value


def safe_url_path(path: str) -> str:
    parsed = urlsplit(path)
    raw_path = re.sub(r"(?i)(/invite-links/)[^/?#]+", r"\1[REDACTED]", parsed.path)
    query = re.sub(r"(?i)(token|code|otp|password|secret)=([^&]+)", r"\1=[REDACTED]", parsed.query)
    return sanitize_text(raw_path + (f"?{query}" if query else ""))


class RecordingClient:
    """Client adapter that records sanitized request/response transcripts."""

    def __init__(self, client: Any):
        self.client = client
        self.base_url = client.base_url
        self.timeout = client.timeout
        self.records: list[dict[str, Any]] = []

    def request(self, method: str, path: str, token: str | None = None, body=None,
                params=None, raw_body=None, headers=None):
        response = self.client.request(method, path, token=token, body=body,
                                       params=params, raw_body=raw_body, headers=headers)
        request_path = path
        if params:
            from urllib.parse import urlencode
            request_path += "?" + urlencode(params)
        try:
            response_body: Any = response.json()
        except (ValueError, TypeError):
            response_body = response.text[:4000]
        self.records.append({
            "method": method.upper(),
            "path": safe_url_path(request_path),
            "headers": {"Authorization": "[REDACTED]"} if token else {},
            "request": redact(body) if body is not None else None,
            "status": response.status,
            "response": redact(response_body),
        })
        return response

    def get(self, path, token=None, params=None, headers=None):
        return self.request("GET", path, token=token, params=params, headers=headers)

    def post(self, path, token=None, body=None, headers=None):
        return self.request("POST", path, token=token, body=body, headers=headers)

    def patch(self, path, token=None, body=None, headers=None):
        return self.request("PATCH", path, token=token, body=body, headers=headers)

    def put(self, path, token=None, body=None, headers=None):
        return self.request("PUT", path, token=token, body=body, headers=headers)

    def delete(self, path, token=None, params=None, headers=None):
        return self.request("DELETE", path, token=token, params=params, headers=headers)


class Adb:
    """Structured-argv adb adapter; every call is pinned to the approved serial."""

    def __init__(self, serial: str, run: Callable[..., Any] | None = None):
        self.serial = validate_serial(serial)
        self._run = run or subprocess.run

    def command(self, *args: str, timeout: float = 60, check: bool = True) -> str:
        argv = ["adb", "-s", self.serial, *map(str, args)]
        try:
            completed = self._run(argv, capture_output=True, timeout=timeout, check=False)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise EnvironmentFailure(f"adb command unavailable: {type(error).__name__}") from error
        stdout = completed.stdout
        stderr = completed.stderr
        if isinstance(stdout, bytes):
            stdout = stdout.decode("utf-8", "replace")
        if isinstance(stderr, bytes):
            stderr = stderr.decode("utf-8", "replace")
        if check and completed.returncode != 0:
            detail = sanitize_text(str(stderr).strip())[:240]
            raise EnvironmentFailure(f"adb command failed ({completed.returncode}): {detail or 'no stderr'}")
        return str(stdout).strip()


def _load_helpers():
    for path in (str(E2E_DIR), str(DEVICE_DIR), str(E2E_DIR / "calls")):
        if path not in sys.path:
            sys.path.insert(0, path)
    return (
        importlib.import_module("e2e_common"),
        importlib.import_module("adb_ui"),
        importlib.import_module("patch_window"),
    )


def command_plan(rows: Iterable[JourneyRow], base_url: str, serial: str, apk: Path) -> dict[str, Any]:
    validate_serial(serial)
    selected = tuple(rows)
    validate_base_url(base_url, selected, False)
    host_url = base_url.rstrip("/")
    emulator_url = derive_emulator_base_url(base_url)
    stomp_url = host_url.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
    preflight_adb = [
        ["adb", "-s", serial, "get-state"],
        ["adb", "-s", serial, "shell", "getprop", "sys.boot_completed"],
        ["adb", "-s", serial, "shell", "getprop", "ro.build.version.sdk"],
        ["adb", "-s", serial, "install", "-r", str(apk)],
        ["adb", "-s", serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
        ["adb", "-s", serial, "exec-out", "cat", "/sdcard/ui.xml"],
        ["adb", "-s", serial, "exec-out", "screencap", "-p"],
        ["adb", "-s", serial, "logcat", "-d", "-v", "brief"],
    ]
    return {
        "serial": serial,
        "host_base_url": host_url,
        "emulator_base_url": emulator_url,
        "evidence_required_for_real_run": True,
        "preflight": {
            "adb_argv": preflight_adb,
            "rest": [{"method": "GET", "url": host_url + "/actuator/health/readiness"}],
        },
        "rows": [
            {
                "id": row.id,
                "matrix_refs": list(row.matrix_refs),
                "preconditions": list(row.preconditions),
                "steps": list(row.steps),
                "assertions": list(row.assertions),
                "ui_markers": list(UI_MARKERS[row.id]),
                "helper_calls": [dict(call) for call in HELPER_CALLS.get(row.id, ())],
                "adb_argv": [["adb", "-s", serial, "shell", "am", "start", "-n",
                              f"{PACKAGE}/{MAIN_ACTIVITY}"]],
                "rest": [{"method": method, "url": host_url + path}
                         for method, path in row.rest_plan],
                "stomp": ({"url": stomp_url, "steps": list(row.stomp_plan)}
                          if row.stomp_plan else {"steps": []}),
                "captures": list(row.captures),
                "cleanup": list(row.cleanup),
                "capability_gap": row.capability_gap,
                "variants": list(row.variants),
            }
            for row in selected
        ],
        "device_and_server_touched": False,
    }


def render_list(rows: Iterable[JourneyRow]) -> str:
    lines = ["ID | Journey | Matrix references | Capability disposition"]
    for row in rows:
        disposition = f"BLOCKED_CAPABILITY: {row.capability_gap}" if row.capability_gap else "Runnable with existing helpers"
        refs = "; ".join(row.matrix_refs)
        lines.append(f"{row.id} | {row.title} | {refs} | {disposition}")
    return "\n".join(lines)


def outside_repository(path: Path) -> bool:
    try:
        path.resolve().relative_to(ROOT.resolve())
        return False
    except ValueError:
        return True


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(redact(value), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _capture_row_artifacts(row_dir: Path, device: Any, patch: Any,
                           baseline: set[str], client: RecordingClient,
                           stomp_events: list[dict[str, Any]], record_start: int = 0) -> dict[str, Any]:
    captures: dict[str, Any] = {"screenshot": None, "ui_tree": None, "logcat": None}
    try:
        device.screenshot(str(row_dir / "screen.png"))
        captures["screenshot"] = "screen.png"
    except Exception as error:
        captures["screenshot_error"] = type(error).__name__
    try:
        nodes = patch.ui_nodes(device, timeout=8)
        tree = "\n".join(node.describe() for node in nodes)
        (row_dir / "ui-tree.txt").write_text(sanitize_text(tree) + "\n", encoding="utf-8")
        captures["ui_tree"] = "ui-tree.txt"
    except Exception as error:
        captures["ui_tree_error"] = type(error).__name__
    try:
        lines, has_crash = patch.filtered_logcat(baseline)
        (row_dir / "logcat.txt").write_text("\n".join(sanitize_text(line) for line in lines) +
                                             ("\n" if lines else ""), encoding="utf-8")
        captures["logcat"] = "logcat.txt"
        captures["fatal_or_anr"] = has_crash
    except Exception as error:
        captures["logcat_error"] = type(error).__name__
    _write_json(row_dir / "rest-transcript.json", client.records[record_start:])
    _write_json(row_dir / "stomp-transcript.json", stomp_events)
    return captures


def _actor_session(api: Any, recorder: RecordingClient, phone: str) -> dict[str, Any]:
    """Use the existing temp-token cache without printing credentials."""
    cached = api._cached_session(recorder, phone)
    if cached is not None:
        return cached
    response = recorder.post("/api/v1/auth/login", body={"phone": phone, "password": api.PASSWORD})
    if response.status != 200:
        raise EnvironmentFailure(f"local fixture login returned HTTP {response.status}")
    data = response.json() or {}
    session = data.get("accessToken")
    user = data.get("user") or {}
    if not session or user.get("phone") != phone:
        raise EnvironmentFailure("local fixture login response did not match the requested account")
    api._store_session(phone, session, str(user.get("id", "")))
    return data


def _assert_device_account_an(ui: Any, patch: Any, evidence_dir: Path, fresh_login: bool) -> None:
    if fresh_login:
        patch.sign_in_as_device_account(ui, evidence_dir)
    else:
        patch.ensure_device_account(ui, evidence_dir, fresh_login=False)
    nodes = patch.ui_nodes(ui, timeout=15)
    if patch.login_form_visible(nodes):
        patch.sign_in_as_device_account(ui, evidence_dir)
        nodes = patch.ui_nodes(ui, timeout=15)
    profile = next((node for node in nodes if patch.text_matches(node, ("Profile", "Hồ sơ"))), None)
    if profile is not None:
        ui.tap_node(profile)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            nodes = patch.ui_nodes(ui, timeout=8)
            if any(node.text.strip() == "An" for node in nodes):
                break
            time.sleep(0.4)
    if not any(node.text.strip() == "An" for node in nodes):
        raise EnvironmentFailure("the device session could not be verified as seeded account An")
    chats = next((node for node in nodes if patch.text_matches(node, ("Chats", "Tin nhắn"))), None)
    if chats is not None:
        ui.tap_node(chats)
        time.sleep(0.5)


def run_auth_login(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    api, adb_ui, patch = context["helpers"]
    recorder: RecordingClient = context["client"]
    ui = context["device"]
    fresh = context["reset_app_data"]
    if fresh:
        context["adb"].command("shell", "pm", "clear", PACKAGE, timeout=30)
    _assert_device_account_an(ui, patch, context["row_dir"], fresh)
    session = _actor_session(api, recorder, api.USER_A["phone"])
    me = recorder.get("/api/v1/users/me", token=session["accessToken"])
    if me.status != 200 or (me.json() or {}).get("phone") != api.USER_A["phone"]:
        raise ProductFailure("GET /api/v1/users/me did not identify the seeded device account")
    return {"device_account": "An", "rest_identity": "An"}


def run_chat_send_receive(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    api, adb_ui, patch = context["helpers"]
    recorder: RecordingClient = context["client"]
    ui = context["device"]
    if context["reset_app_data"]:
        context["adb"].command("shell", "pm", "clear", PACKAGE, timeout=30)
    _assert_device_account_an(ui, patch, context["row_dir"], context["reset_app_data"])
    nodes = patch.ui_nodes(ui, timeout=15)
    row_node = patch.named_conversation_row(nodes, "Binh")
    if row_node is None:
        raise EnvironmentFailure("seed An↔Binh conversation is not visible in the device inbox")
    ui.tap_node(row_node)
    time.sleep(0.8)
    an = _actor_session(api, recorder, api.USER_A["phone"])
    binh = _actor_session(api, recorder, api.USER_C["phone"])
    try:
        _, conversation = api.direct_conversation(recorder, an["accessToken"], api.USER_C["phone"])
    except Exception as error:
        raise EnvironmentFailure("local An↔Binh conversation fixture could not be opened") from error
    conversation_id = str(conversation.get("id", ""))
    if not conversation_id:
        raise EnvironmentFailure("local An↔Binh fixture did not return a conversation id")

    from realtime_probe import FrameReader

    ws_url = recorder.base_url.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
    reader = FrameReader(ws_url, binh["accessToken"])
    context["frame_reader"] = reader
    reader.subscribe(f"/topic/conversations.{conversation_id}")
    outbound = "sj-out-" + uuid.uuid4().hex[:10]
    inbound = "sj-in-" + uuid.uuid4().hex[:10]
    created: list[tuple[dict[str, Any], str]] = []
    result: dict[str, Any] = {}
    primary_error: Exception | None = None
    try:
        composer = patch.find_chat_composer(patch.ui_nodes(ui, timeout=15))
        ui.tap_node(composer)
        ui.type_text(outbound)
        nodes = patch.ui_nodes(ui, timeout=10)
        send = patch.node_by_id(nodes, "sendButton")
        if send is None:
            raise EnvironmentFailure("chat send button was not exposed by the UI hierarchy")
        ui.tap_node(send)

        try:
            patch.find_text_node(ui, outbound, timeout=20)
        except Exception as error:
            raise ProductFailure("device did not render its sent chat message") from error
        ui.screenshot(str(context["row_dir"] / "chat-after-send.png"))

        page_response = recorder.get(
            f"/api/v1/conversations/{conversation_id}/messages",
            token=binh["accessToken"], params={"limit": 50},
        )
        if page_response.status != 200:
            raise ProductFailure(f"peer REST history returned HTTP {page_response.status}")
        page = page_response.json() or {}
        items = page.get("items") or []
        matching = [item for item in items if item.get("content") == outbound]
        if len(matching) != 1:
            raise ProductFailure("peer REST history did not contain exactly one device message")
        outbound_id = str(matching[0].get("id", ""))
        if outbound_id:
            created.append((an, outbound_id))
        events = list(reader.events(2.0))
        context["stomp_events"].extend(events)
        observed = [event for event in events
                    if event.get("eventType") == "MESSAGE_CREATED"
                    and (event.get("payload") or {}).get("content") == outbound]
        if len(observed) != 1:
            raise ProductFailure("Binh STOMP did not observe exactly one MESSAGE_CREATED for the device message")

        try:
            inbound_message = api.send_text(recorder, binh["accessToken"], conversation_id, inbound)
        except Exception as error:
            raise ProductFailure("Binh REST send did not return a created message") from error
        inbound_id = str(inbound_message.get("id", ""))
        if inbound_id:
            created.append((binh, inbound_id))
        try:
            patch.find_text_node(ui, inbound, timeout=20)
        except Exception as error:
            raise ProductFailure("device did not render Binh's peer REST message") from error
        ui.screenshot(str(context["row_dir"] / "chat-after-receive.png"))
        result = {"conversation_id": conversation_id, "peer_history_count": 1,
                  "peer_stomp_message_count": 1, "inbound_message_rendered": True}
    except Exception as error:
        primary_error = error
    finally:
        # If the app reached the server but a later assertion failed, discover
        # only this run's unique marker and tombstone it as its author.
        for marker, author in ((outbound, an), (inbound, binh)):
            if any(existing_author is author for existing_author, _ in created):
                continue
            try:
                response = recorder.get(
                    f"/api/v1/conversations/{conversation_id}/messages",
                    token=binh["accessToken"], params={"limit": 50},
                )
                page = (response.json() or {}) if response.status == 200 else {}
                item = next((entry for entry in (page.get("items") or [])
                             if entry.get("content") == marker), None)
                if item and item.get("id"):
                    created.append((author, str(item["id"])))
            except Exception:
                pass
        cleanup_errors: list[str] = []
        for author, message_id in created:
            try:
                deleted = recorder.delete(
                    f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
                    token=author["accessToken"],
                )
                if deleted.status not in (200, 204):
                    cleanup_errors.append(f"HTTP {deleted.status}")
            except Exception as error:
                cleanup_errors.append(type(error).__name__)
        if cleanup_errors:
            context["cleanup_errors"] = cleanup_errors
    if primary_error is not None:
        raise primary_error
    if context.get("cleanup_errors"):
        raise HarnessError("one or more unique chat test messages could not be soft-deleted")
    result["cleanup"] = "unique test messages soft-deleted"
    return result


def _capability_runner(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    if row.capability_gap:
        raise CapabilityBlocked(row.capability_gap)
    raise HarnessError(f"no row executor is registered for {row.id}")


def run_registered_row(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    if row.capability_gap:
        raise CapabilityBlocked(row.capability_gap)
    executors = {
        "auth-login": run_auth_login,
        "chat-send-receive": run_chat_send_receive,
    }
    executor = executors.get(row.id)
    if executor is None:
        return _capability_runner(row, context)
    return executor(row, context)


def _status_for_error(error: Exception) -> str:
    if isinstance(error, CapabilityBlocked):
        return "BLOCKED_CAPABILITY"
    if isinstance(error, ProductFailure):
        return "FAIL_PRODUCT"
    if isinstance(error, (EnvironmentFailure, HarnessError)):
        return "FAIL_HARNESS_OR_ENVIRONMENT"
    return "FAIL_HARNESS_OR_ENVIRONMENT"


def execute_rows(rows: Iterable[JourneyRow], context: dict[str, Any],
                 runner: Callable[[JourneyRow, dict[str, Any]], dict[str, Any]] | None = None,
                 *, max_attempts: int = ATTEMPTS_PER_ROW) -> list[dict[str, Any]]:
    if max_attempts != ATTEMPTS_PER_ROW:
        raise ValueError("the campaign allows exactly one rerun per row")
    run_row = runner or _capability_runner
    results: list[dict[str, Any]] = []
    for row in rows:
        row_dir: Path = context["evidence_root"] / row.id
        row_dir.mkdir(parents=True, exist_ok=True)
        attempts: list[dict[str, Any]] = []
        status = "NOT_RUN"
        reason = None
        assertions: dict[str, Any] = {}
        for attempt in range(1, ATTEMPTS_PER_ROW + 1):
            context.update({"row": row, "row_dir": row_dir / f"attempt-{attempt}",
                            "attempt": attempt, "stomp_events": [],
                            "cleanup_errors": []})
            attempt_dir: Path = context["row_dir"]
            attempt_dir.mkdir(parents=True, exist_ok=True)
            record_start = len(context["client"].records)
            baseline: set[str] = set()
            adb: Adb = context["adb"]
            device = context["device"]
            patch = context["helpers"][2]
            try:
                raw = adb.command("logcat", "-d", "-v", "brief", check=False)
                baseline = set(raw.splitlines())
            except Exception:
                baseline = set()
            try:
                if row.capability_gap and runner is None:
                    raise CapabilityBlocked(row.capability_gap)
                value = run_row(row, context) or {}
                status = "PASS"
                assertions = redact(value)
                error_text = None
            except Exception as error:
                status = _status_for_error(error)
                reason = sanitize_text(str(error))[:1000]
                if context.get("cleanup_errors"):
                    assertions = {"cleanup_errors": list(context["cleanup_errors"])}
            finally:
                captures = _capture_row_artifacts(
                    attempt_dir, device, patch, baseline, context["client"],
                    context["stomp_events"], record_start,
                )
                if captures.get("fatal_or_anr"):
                    status = "FAIL_PRODUCT"
                    reason = reason or "logcat contained a FATAL EXCEPTION or ANR during this row"
                if captures.get("screenshot_error") or captures.get("logcat_error"):
                    status = "FAIL_HARNESS_OR_ENVIRONMENT"
                    reason = reason or "required screenshot or logcat evidence could not be captured"
                if context.get("frame_reader") is not None:
                    try:
                        context["frame_reader"].close()
                    except Exception:
                        pass
                    context["frame_reader"] = None
                _write_json(attempt_dir / "summary.json", {
                    "row_id": row.id, "attempt": attempt, "status": status,
                    "reason": reason, "assertions": assertions, "captures": captures,
                })
            attempts.append({"number": attempt, "status": status,
                             "reason": reason,
                             "evidence_dir": attempt_dir.relative_to(context["evidence_root"]).as_posix()})
            if status in {"PASS", "BLOCKED_CAPABILITY"}:
                break
            if attempt == 1:
                continue
        results.append({"id": row.id, "title": row.title, "status": status,
                        "reason": reason, "assertions": assertions, "attempts": attempts,
                        "retry_count": max(0, len(attempts) - 1)})
    return results


def snapshot_settings(adb: Adb, patch: Any) -> dict[str, str]:
    values = patch.settings_snapshot()
    values["night_mode"] = adb.command("shell", "cmd", "uimode", "night", timeout=20)
    return values


def _night_mode_value(value: str) -> str:
    match = re.search(r"\b(auto|yes|no)\b", value.casefold())
    if match:
        return match.group(1)
    if value.strip() in {"0", "1", "2"}:
        return {"0": "auto", "1": "yes", "2": "no"}[value.strip()]
    raise HarnessError("Android night mode could not be read; refusing to guess a restore value")


def restore_settings(adb: Adb, patch: Any, before: dict[str, str]) -> dict[str, str]:
    failures: list[str] = []
    try:
        patch.restore_device_settings(before)
    except Exception as error:
        failures.append(f"network/font/locale restore: {type(error).__name__}")
    try:
        adb.command("shell", "cmd", "uimode", "night", _night_mode_value(before["night_mode"]), timeout=20)
    except Exception as error:
        failures.append(f"night-mode restore: {type(error).__name__}")
    if failures:
        raise HarnessError("; ".join(failures))
    return snapshot_settings(adb, patch)


def set_accessibility_variant(adb: Adb, variant: dict[str, str]) -> None:
    if variant["font_scale"] not in {"1.0", "2.0"}:
        raise HarnessError("unexpected accessibility font scale")
    if variant["night"] not in {"yes", "no"}:
        raise HarnessError("unexpected accessibility night mode")
    if variant["locale"] not in {"en-US", "vi-VN"}:
        raise HarnessError("unexpected accessibility locale")
    adb.command("shell", "settings", "put", "system", "font_scale", variant["font_scale"])
    adb.command("shell", "cmd", "uimode", "night", variant["night"])
    adb.command("shell", "cmd", "locale", "set", variant["locale"])


def preflight(base_url: str, apk: Path, adb: Adb, recorder: RecordingClient) -> None:
    if not apk.is_file():
        raise EnvironmentFailure("debug APK is missing")
    try:
        readiness = recorder.get("/actuator/health/readiness")
    except Exception as error:
        raise EnvironmentFailure(f"local readiness request failed: {type(error).__name__}") from error
    if readiness.status != 200:
        raise EnvironmentFailure(f"{base_url.rstrip('/')}/actuator/health/readiness returned HTTP {readiness.status}")
    state = adb.command("get-state", timeout=15)
    if state != "device":
        raise EnvironmentFailure(f"{SERIAL} is not ready (adb get-state: {state or 'empty'})")
    boot = adb.command("shell", "getprop", "sys.boot_completed", timeout=15)
    if boot != "1":
        raise EnvironmentFailure(f"{SERIAL} is not boot-complete")
    api_level = adb.command("shell", "getprop", "ro.build.version.sdk", timeout=15)
    if api_level != "36":
        raise EnvironmentFailure(f"{SERIAL} must run API 36; observed API {api_level or 'unknown'}")


def install_apk(apk: Path, adb: Adb) -> None:
    install = adb.command("install", "-r", str(apk), timeout=180)
    if "success" not in install.casefold():
        raise EnvironmentFailure("debug APK install did not report Success")


def with_settings_restored(action: Callable[[], Any], restore: Callable[[], Any]) -> Any:
    """Run an action and restore emulator settings even if a row raises."""
    try:
        return action()
    finally:
        restore()


def run_campaign(rows: Iterable[JourneyRow], *, base_url: str, serial: str,
                 apk: Path, evidence_dir: Path, reset_app_data: bool = False,
                 adb_adapter: Adb | None = None,
                 runner: Callable[[JourneyRow, dict[str, Any]], dict[str, Any]] | None = None) -> dict[str, Any]:
    selected = tuple(rows)
    validate_serial(serial)
    validate_base_url(base_url, selected, False)
    if not evidence_dir:
        raise HarnessError("--evidence-dir is required for a real run")
    if not outside_repository(evidence_dir):
        raise HarnessError("evidence must be written outside the repository")
    evidence_dir.mkdir(parents=True, exist_ok=True)
    helpers = _load_helpers()
    api = helpers[0]
    adb = adb_adapter or Adb(serial)
    recorder = RecordingClient(api.Client(base_url, timeout=30))
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    run_root = evidence_dir / run_id
    run_root.mkdir(parents=True, exist_ok=False)
    summary: dict[str, Any] = {
        "run_id": run_id, "status": "NOT_RUN", "serial": serial,
        "base_url": base_url.rstrip("/"), "emulator_base_url": derive_emulator_base_url(base_url),
        "package": PACKAGE, "api_level": 36, **source_identity(),
        "apk_name": apk.name, "apk_sha256": None, "rows": [], "settings_before": None,
        "settings_after_restore": None, "device_touched": False,
    }
    context: dict[str, Any] = {"evidence_root": run_root, "client": recorder, "adb": adb,
                               "reset_app_data": reset_app_data, "helpers": helpers,
                               "frame_reader": None, "stomp_events": []}
    before: dict[str, str] | None = None
    device = helpers[1].Device(serial)
    context["device"] = device
    try:
        try:
            preflight(base_url, apk, adb, recorder)
        except Exception as error:
            status = _status_for_error(error)
            reason = sanitize_text(str(error))[:1000]
            summary["rows"] = []
            for row in selected:
                row_dir = run_root / row.id
                row_dir.mkdir(parents=True, exist_ok=True)
                _write_json(row_dir / "summary.json", {
                    "row_id": row.id, "status": status, "reason": reason,
                    "captures": {"screenshot": "NOT_AVAILABLE_PRE_DEVICE_PREFLIGHT",
                                 "logcat": "NOT_AVAILABLE_PRE_DEVICE_PREFLIGHT"},
                })
                _write_json(row_dir / "rest-transcript.json", recorder.records)
                _write_json(row_dir / "stomp-transcript.json", [])
                summary["rows"].append({"id": row.id, "title": row.title, "status": status,
                                        "reason": reason, "attempts": []})
            summary["status"] = status
            summary["preflight_error"] = reason
            return _finish_run(summary, run_root, recorder)

        patch = helpers[2]
        before = snapshot_settings(adb, patch)
        summary["settings_before"] = before

        def run_selected() -> None:
            summary["device_touched"] = True
            install_apk(apk, adb)
            summary["apk_sha256"] = sha256_file(apk)
            patch.prepare_network(before)
            summary["rows"] = execute_rows(selected, context, runner or run_registered_row)
            statuses = {item["status"] for item in summary["rows"]}
            if "FAIL_PRODUCT" in statuses:
                summary["status"] = "FAIL_PRODUCT"
            elif "FAIL_HARNESS_OR_ENVIRONMENT" in statuses:
                summary["status"] = "FAIL_HARNESS_OR_ENVIRONMENT"
            elif "BLOCKED_CAPABILITY" in statuses:
                summary["status"] = "BLOCKED_CAPABILITY"
            elif statuses == {"PASS"}:
                summary["status"] = "PASS"
            else:
                summary["status"] = "NOT_RUN"

        def restore_selected() -> None:
            after = restore_settings(adb, patch, before)
            summary["settings_after_restore"] = after
            keys = ("wifi_on", "mobile_data", "airplane_mode_on", "font_scale", "system_locales")
            summary["settings_restored"] = all(after.get(key) == before.get(key) for key in keys)
            summary["night_mode_restored"] = _night_mode_value(after.get("night_mode", "")) == _night_mode_value(before["night_mode"])

        try:
            with_settings_restored(run_selected, restore_selected)
        except Exception as error:
            summary["status"] = "FAIL_HARNESS_OR_ENVIRONMENT"
            summary["run_error"] = sanitize_text(str(error))[:500]
    finally:
        if context.get("frame_reader") is not None:
            try:
                context["frame_reader"].close()
            except Exception:
                pass
    return _finish_run(summary, run_root, recorder)


def _finish_run(summary: dict[str, Any], run_root: Path, recorder: RecordingClient) -> dict[str, Any]:
    _write_json(run_root / "run-summary.json", summary)
    _write_json(run_root / "preflight-rest-transcript.json", recorder.records)
    return summary


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_identity() -> dict[str, str | None]:
    try:
        completed = subprocess.run(
            ["git", "-C", str(ROOT), "rev-parse", "HEAD"],
            capture_output=True, text=True, timeout=10, check=False,
        )
        head = completed.stdout.strip() if completed.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        head = None
    return {"git_head": head, "stable_journeys_sha256": sha256_file(Path(__file__))}


def render_summary(summary: dict[str, Any]) -> str:
    lines = ["id | status | attempts | reason"]
    for row in summary.get("rows", []):
        lines.append(f"{row['id']} | {row['status']} | {len(row.get('attempts', []))} | {row.get('reason') or ''}")
    lines.append(f"Run status: {summary.get('status', 'NOT_RUN')}")
    return "\n".join(lines)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list", action="store_true", help="print the phase 08/c1 journey matrix")
    parser.add_argument("--dry-run", action="store_true", help="print exact structured adb/REST/STOMP plan without touching device or server")
    parser.add_argument("--rows", help="comma-separated journey row ids; default: all")
    parser.add_argument("--serial", default=SERIAL, help=f"only {SERIAL} is permitted")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL,
                        help=f"local stack host URL (default {DEFAULT_BASE_URL}); production is refused")
    parser.add_argument("--allow-production-readonly", action="store_true",
                        help="allow only explicitly read-only rows against production")
    parser.add_argument("--evidence-dir", help="required external directory for a real run")
    parser.add_argument("--apk", default=str(DEFAULT_APK), help="debug APK to install")
    parser.add_argument("--reset-app-data", action="store_true",
                        help="clear only com.nexachat.debug on emulator-5554 before sign-in")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        validate_serial(args.serial)
        selected = select_rows(args.rows)
        validate_base_url(args.base_url, selected, args.allow_production_readonly)
        if args.list:
            print(render_list(ROWS))
            return 0
        if args.dry_run:
            print(json.dumps(command_plan(selected, args.base_url, args.serial, Path(args.apk)),
                             ensure_ascii=False, indent=2))
            return 0
        if not args.evidence_dir:
            raise HarnessError("--evidence-dir is required for a real run")
        summary = run_campaign(
            selected, base_url=args.base_url, serial=args.serial, apk=Path(args.apk),
            evidence_dir=Path(args.evidence_dir), reset_app_data=args.reset_app_data,
        )
        print(render_summary(summary))
        return 0 if summary["status"] == "PASS" else 1
    except HarnessError as error:
        parser.error(str(error))
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
