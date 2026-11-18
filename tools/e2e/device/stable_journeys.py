#!/usr/bin/env python3
"""Guarded coordinator for the phase 08 stable-journey campaign.

Runnable rows are seeded login, send/receive, chat-interactions, summary-ask,
and the two injected-push journeys. Every other matrix row stays
BLOCKED_CAPABILITY with the gap that still prevents that row from producing
its evidence.
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
from datetime import datetime, timedelta, timezone
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
        capability_gap=("response_barrier can hold account A's REST response for GET /api/v1/conversations and "
                        "release it after the switch. STOMP stays unproven: the barrier refuses WebSocket upgrades "
                        "with HTTP 501, so an in-flight STOMP frame during A→B is not paused and B's socket "
                        "isolation is not evidence. Registration, invalid-credential UI, refresh, logout, "
                        "logout-all, password change, and saved-session restart are still not driven. Do not "
                        "report the login slice as this full row."),
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
        capability_gap=("mail_sink can start Mailpit and server_env() names the SMTP settings, but this "
                        "coordinator does not restart the already-running local server, so those settings are "
                        "not applied. EmailOtpService.CODE_TTL is 10 minutes with no test clock, so an expired "
                        "code cannot be asserted. Wrong, reused, and resend therefore have no delivered-mail "
                        "evidence yet."),
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
        preconditions=("An is signed in on the device",
                       "The seeded An↔Binh DIRECT conversation exists",
                       "The seeded 'NexaChat Team' group exists as the forward target"),
        steps=("Send unique texts from the device and long-press each bubble",
               "Drive Reply, React (👍), Edit, Recall and Forward through the action sheet",
               "Mark the conversation read as Binh over REST"),
        assertions=("Reply renders the quoted banner and REST replyToId",
                    "Reaction chip and REST reactions/REACTION_UPDATED agree",
                    "Edited text and Edited marker match REST editedAt/MESSAGE_UPDATED",
                    "Recall tombstone matches peer REST deletedAt/MESSAGE_DELETED",
                    "Forwarded copy lands in the group REST history",
                    "Device shows the peer Read receipt and MESSAGE_READ arrives on the topic"),
        captures=("chat-interactions-*.png", "ui-tree.txt", "logcat.txt",
                  "rest-transcript.json", "stomp-transcript.json", "summary.json"),
        cleanup=("Soft-delete only the unique direct and group messages created by this row"),
        rest_plan=(("GET", "/api/v1/conversations/{id}/messages"),
                   ("PATCH", "/api/v1/conversations/{id}/messages/{messageId}"),
                   ("DELETE", "/api/v1/conversations/{id}/messages/{messageId}"),
                   ("POST", "/api/v1/conversations/{id}/read")),
        stomp_plan=("SUBSCRIBE /topic/conversations.{directId} and /topic/conversations.{groupId}",
                    "Assert MESSAGE_CREATED (replyToId), REACTION_UPDATED, MESSAGE_UPDATED (editedAt), "
                    "MESSAGE_DELETED and MESSAGE_READ frames"),
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
    ),
    JourneyRow(
        id="summary-ask",
        title="Conversation summary and Ask this chat",
        matrix_refs=("phase-08:Summary and ask", "c1-5:Summary/Ask"),
        preconditions=("The seeded An↔Binh DIRECT conversation is not E2EE",
                       "An is signed in on the device; aiSummaryConsent is fixture-enabled and restored",
                       "The local extractive engines are enabled (no LLM provider keys required)"),
        steps=("Seed a unique lookup fact plus fillers as Binh and mark the conversation read as An",
               "Open the summary sheet from the chat overflow, switch to Last N and read the LOCAL summary",
               "Tap a summary citation chip, then drive Ask this chat: type the fact question, read the "
               "extractive answer, tap the Sources chip and land on the cited message",
               "Verify the ask through peer REST cache replay and exercise the ai-opt-out exclusion "
               "round-trip as Binh"),
        assertions=("The summary sheet renders LOCAL engine sections and a jumpable citation chip; the "
                    "chip tap dismisses the sheet through the real jump listener",
                    "Ask this chat answers EXTRACTIVE quoting the seeded fact with a Sources chip",
                    "The citation tap dismisses the sheet and renders the cited messageBody",
                    "Peer REST replays the same question as CACHE citing the seeded message id",
                    "Binh ai-opt-out drops the fact citation (contextOmitted) and opt back in restores it"),
        captures=("summary-ask-*.png", "ui-tree.txt", "logcat.txt",
                  "rest-transcript.json", "summary.json"),
        cleanup=("Soft-delete only the seeded fact/filler messages",
                 "Restore An aiSummaryConsent and Binh ai-opt-out to their pre-run values"),
        rest_plan=(("PATCH", "/api/v1/users/me/settings"),
                   ("POST", "/api/v1/conversations/{id}/messages"),
                   ("POST", "/api/v1/conversations/{id}/read"),
                   ("POST", "/api/v1/conversations/{id}/summary"),
                   ("POST", "/api/v1/conversations/{id}/ask"),
                   ("PUT", "/api/v1/conversations/{id}/ai-opt-out"),
                   ("DELETE", "/api/v1/conversations/{id}/ai-opt-out"),
                   ("DELETE", "/api/v1/conversations/{id}/messages/{messageId}")),
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
        capability_gap=("fixture_apks can fetch verified v1.1.0 and v1.1.1 release APKs that share a signer, "
                        "but AppDatabase is still version 13 and no Room 14 build exists. Those release APKs are "
                        "not a Room 13→14 migration pair, so queued-send survival cannot be asserted."),
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
        capability_gap=("fixture_apks verifies the signed v1.1.1 APK (package com.nexachat, versionCode "
                        "1001001). The v1.2 RC and Room 14 build do not exist, and com.nexachat.debug cannot "
                        "replace that release package in place. A v1.1.0 to v1.1.1 install is not this row. "
                        "Never use a production base."),
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
        capability_gap=("mail_sink can read a deletion OTP, but this coordinator does not restart the local "
                        "server with server_env(), and no helper binds a disposable account into the debug UI. "
                        "Wrong-password refusal on device, in-app confirmation, the deleted-account cache, "
                        "peer-neutral history, and phone reuse on device stay unproven. The row stays last and "
                        "must never target a seed user."),
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
    "chat-interactions": (
        {"helper": "adb_ui.Device.long_press", "target": "resource-id messageBody", "opens": "messageActionsTitle sheet"},
        {"helper": "adb_ui.Device.tap_node", "target": "reaction tile desc Like", "assert": "chip desc '👍, 1, yours'"},
        {"helper": "adb_ui.Device.tap_node", "target": "actionLabel Reply/Edit/Recall/Forward"},
        {"helper": "adb_ui.Device.tap_node", "target": "confirm Delete; forwardSend; sendButton desc Save changes"},
        {"helper": "e2e_common.history", "actor": "Binh", "assert": "reactions/replyToId/editedAt/deletedAt/forwarded copy"},
        {"helper": "e2e_common.Client.post", "path": "/api/v1/conversations/{id}/read", "actor": "Binh",
         "assert": "device deliveryIcon desc Read"},
        {"helper": "realtime_probe.FrameReader", "actor": "Binh",
         "assert": "REACTION_UPDATED/MESSAGE_CREATED/MESSAGE_UPDATED/MESSAGE_DELETED/MESSAGE_READ"},
    ),
    "auth-session-lifecycle": (
        {"helper": "response_barrier.Barrier.hold", "method": "GET",
         "path": "^/api/v1/conversations$", "token_contains": "account A access token"},
        {"helper": "response_barrier.Barrier.release", "when": "after the device session is account B"},
        {"helper": "unproven", "transport": "STOMP", "reason": "WebSocket upgrade returns HTTP 501"},
    ),
    "otp-forgot-password": (
        {"helper": "mail_sink.start"},
        {"helper": "mail_sink.server_env"},
        {"helper": "mail_sink.wait_for_message"},
        {"helper": "mail_sink.extract_otp"},
        {"helper": "mail_sink.stop", "when": "finally"},
    ),
    "account-deletion": (
        {"helper": "mail_sink.start"},
        {"helper": "mail_sink.server_env"},
        {"helper": "mail_sink.wait_for_message"},
        {"helper": "mail_sink.extract_otp"},
        {"helper": "mail_sink.stop", "when": "finally"},
    ),
    "calls-injected-notification": (
        {"helper": "adb", "args": ["shell", "input", "keyevent", "KEYCODE_HOME"]},
        {"helper": "adb", "args": ["shell", "am", "kill", PACKAGE]},
        {"helper": "inject_push.main", "kind": "call", "serial": SERIAL},
        {"helper": "adb_ui.Device.tap_node", "target": "text Decline or Từ chối"},
    ),
    "summary-ask": (
        {"helper": "adb_ui.Device.tap_node", "target": "resource-id moreButton"},
        {"helper": "adb_ui.Device.tap_node", "target": "text Summarize conversation",
         "opens": "resource-id summarySheetRoot"},
        {"helper": "adb_ui.Device.tap_node", "target": "summary scope chip Last N",
         "assert": "resource-id summaryResultHeader/summaryBulletText render the LOCAL summary"},
        {"helper": "adb_ui.Device.tap_node", "target": "resource-id citationChip (↗ …)",
         "assert": "summary sheet dismisses through the citation jump listener"},
        {"helper": "adb_ui.Device.tap_node", "target": "text Ask this chat",
         "opens": "resource-id askSheetRoot"},
        {"helper": "adb_ui.Device.type_text", "target": "resource-id askQuestion",
         "value": "lookup question with a unique codename"},
        {"helper": "adb_ui.Device.tap_node", "target": "resource-id askSend",
         "assert": "resource-id askAnswer/askModeBadge/citationButton render the EXTRACTIVE answer"},
        {"helper": "adb_ui.Device.tap_node", "target": "resource-id citationButton",
         "assert": "sheet dismisses and the cited messageBody renders"},
        {"helper": "e2e_common.Client.post", "path": "/api/v1/conversations/{id}/ask",
         "actor": "An", "assert": "CACHE replay cites the seeded fact message id"},
        {"helper": "e2e_common.Client.put", "path": "/api/v1/conversations/{id}/ai-opt-out",
         "actor": "Binh", "assert": "ask drops the fact citation and reports contextOmitted"},
    ),
    "injected-notifications": (
        {"helper": "e2e_common.send_text", "actor": "Binh", "assert": "real message id and seq"},
        {"helper": "inject_push.main", "kind": "message", "serial": SERIAL},
        {"helper": "inject_push.main", "kind": "reminder", "serial": SERIAL},
        {"helper": "adb", "args": ["shell", "dumpsys", "notification", "--noredact"]},
    ),
    "room-13-14-upgrade": (
        {"helper": "fixture_apks.fetch", "tag": "v1.1.0"},
        {"helper": "fixture_apks.fetch", "tag": "v1.1.1"},
        {"helper": "fixture_apks.assert_shared_signer"},
        {"helper": "adb", "args": ["adb", "-s", SERIAL, "install", "-r", "<room14-apk>"],
         "blocked": "Room 14 build does not exist"},
    ),
    "v111-rc-upgrade": (
        {"helper": "fixture_apks.fetch", "tag": "v1.1.1"},
        {"helper": "fixture_apks.describe"},
        {"helper": "adb", "args": ["adb", "-s", SERIAL, "install", "-r", "<v1.1.1-apk>"],
         "then": ["adb", "-s", SERIAL, "install", "-r", "<v1.2-rc-apk>"],
         "blocked": "v1.2 RC and Room 14 build do not exist"},
    ),
}

UI_MARKERS = {
    "auth-login": ("resource-id:phoneInput", "resource-id:passwordInput",
                   "resource-id:primaryAction", "text:An", "text:Binh"),
    "chat-send-receive": ("text:Binh", "resource-id:messageInput", "resource-id:sendButton",
                           "text:<unique outbound message>", "text:<unique inbound message>"),
    "auth-session-lifecycle": ("text:localized login/error state", "text:An", "text:Binh"),
    "otp-forgot-password": ("text:localized OTP verification state", "text:password changed"),
    "chat-interactions": (
        "resource-id:messageActionsTitle", "resource-id:actionLabel",
        "resource-id:replyPreview", "resource-id:replySnippet",
        "resource-id:editedLabel", "text:This message was deleted",
        "resource-id:forwardDialogRoot", "resource-id:forwardSend",
        "content-desc:Read",
    ),
    "media": ("text:unique media fixture name", "text:voice duration"),
    "groups-contacts": ("text:unique group name", "text:member list", "text:invite destination"),
    "polls-reminders": ("text:unique poll question", "text:unique reminder title"),
    "archive-preferences": ("text:Archive", "text:unique archived conversation"),
    "labels-lifecycle": ("text:unique label name", "text:one label render after recreation"),
    "calls-signaling": ("text:call state", "text:call history"),
    "calls-injected-notification": ("text:Incoming voice call", "text:Accept", "text:Decline"),
    "summary-ask": (
        "resource-id:moreButton", "resource-id:summarySheetRoot",
        "resource-id:summaryScopeChips", "resource-id:summaryResultHeader",
        "resource-id:summaryBulletText", "resource-id:citationChip",
        "resource-id:askSheetRoot", "resource-id:askQuestion",
        "resource-id:askSend", "resource-id:askAnswer",
        "resource-id:askModeBadge", "resource-id:citationButton",
        "resource-id:messageBody",
    ),
    "offline-sync": ("text:unique offline message", "text:converged message order"),
    "injected-notifications": (
        "text:You have a new message", "text:Tin nhắn mới",
        "text:A reminder is due", "text:Nhắc nhở", "label:INJECTED",
    ),
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
    profile = patch.node_by_id(nodes, "inboxUserAvatar")
    if profile is None:
        raise EnvironmentFailure("the inbox profile entry point was not visible")
    ui.tap_node(profile)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        nodes = patch.ui_nodes(ui, timeout=8)
        name = patch.node_by_id(nodes, "profileName")
        if name is not None and name.text.strip() == "An":
            ui.key("KEYCODE_BACK")
            return
        time.sleep(0.4)
    raise EnvironmentFailure("the device session could not be verified as seeded account An")


def _clear_app_data(context: dict[str, Any]) -> None:
    context["adb"].command("shell", "pm", "clear", PACKAGE, timeout=30)
    # pm clear revokes runtime grants; several journeys' preconditions require
    # the notification/audio grants in patch_window.PERMISSIONS.
    context["helpers"][2].grant_test_permissions()


def run_auth_login(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    api, adb_ui, patch = context["helpers"]
    recorder: RecordingClient = context["client"]
    ui = context["device"]
    fresh = context["reset_app_data"]
    if fresh:
        _clear_app_data(context)
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
        _clear_app_data(context)
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
        composer: Any = None
        composer_error: Exception | None = None
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            try:
                composer = patch.find_chat_composer(patch.ui_nodes(ui, timeout=10))
                break
            except Exception as error:
                composer_error = error
                time.sleep(0.8)
        if composer is None:
            raise composer_error or EnvironmentFailure("chat composer was not visible")
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
        # A member receives MESSAGE_CREATED on both the conversation topic and
        # their /user/queue/inbox push by design: assert the single message id
        # arrived on the topic itself rather than counting raw frames.
        observed = [event for event in events
                    if event.get("eventType") == "MESSAGE_CREATED"
                    and (event.get("payload") or {}).get("content") == outbound]
        on_topic = [event for event in observed
                    if str(event.get("_destination", "")).endswith(
                        "/topic/conversations." + conversation_id)]
        if not on_topic:
            raise ProductFailure("Binh STOMP did not observe the device message on the conversation topic")
        if len({(event.get("payload") or {}).get("id") for event in observed}) != 1:
            raise ProductFailure("Binh STOMP observed more than one message id for the device message")

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


# --- chat-interactions -------------------------------------------------------
# Localized labels for the long-press action sheet and its confirmation
# surfaces. The device runs en-US in the campaign but the sweep variants may
# leave vi-VN behind, so every tap resolves both locales.
_CHAT_ACTION_LABELS = {
    "reply": ("Reply", "Trả lời"),
    "edit": ("Edit", "Chỉnh sửa"),
    "recall": ("Recall", "Thu hồi"),
    "forward": ("Forward", "Chuyển tiếp"),
}
_CHAT_REACTION_TILE = ("Like", "Thích")  # 👍 is ReactionSet.emoji(0)/reaction_like
_CHAT_REACTION_EMOJI = "👍"
_CHAT_DELETE_CONFIRM = ("Delete", "Xóa")
_CHAT_EDITED_MARKERS = ("Edited", "Đã chỉnh sửa")
_CHAT_TOMBSTONE_MARKERS = ("This message was deleted", "Tin nhắn đã bị xóa")
_CHAT_READ_MARKERS = ("Read", "Đã xem")
_CHAT_SAVE_DESC = ("Save changes", "Lưu thay đổi")
_CHAT_SEND_DESC = ("Send", "Gửi")
_CHAT_FORWARD_TITLE = ("Forward message", "Chuyển tiếp tin nhắn")
_CHAT_FORWARD_TARGET_TITLE = "NexaChat Team"


def _rid_ends(node: Any, suffix: str) -> bool:
    return str(getattr(node, "attrs", {}).get("resource-id", "") or "").endswith("/" + suffix)


def _wait_ui_node(context: dict[str, Any], predicate: Callable[[Any], bool],
                  what: str, timeout: float | None = None) -> Any:
    deadline = time.monotonic() + float(timeout or _ui_timeout(context))
    while True:
        match = next((node for node in _ui_nodes(context) if predicate(node)), None)
        if match is not None:
            return match
        if time.monotonic() >= deadline:
            raise ProductFailure(what)
        time.sleep(0.4)


def _wait_ui_absent(context: dict[str, Any], predicate: Callable[[Any], bool],
                  what: str, timeout: float | None = None) -> None:
    deadline = time.monotonic() + float(timeout or _ui_timeout(context))
    while True:
        if not any(predicate(node) for node in _ui_nodes(context)):
            return
        if time.monotonic() >= deadline:
            raise ProductFailure(what)
        time.sleep(0.4)


def _wait_sheet_closed(context: dict[str, Any], timeout: float = 12) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not any(_rid_ends(node, "messageActionsTitle") for node in _ui_nodes(context)):
            return
        time.sleep(0.4)
    raise ProductFailure("the message action sheet did not dismiss")


def _chat_send_text(context: dict[str, Any], content: str) -> None:
    """Type into the chat composer and tap send; assert the bubble renders."""
    patch = context["helpers"][2]
    ui = context["device"]
    composer = patch.find_chat_composer(_ui_nodes(context))
    ui.tap_node(composer)
    ui.type_text(content)
    send = _wait_ui_node(
        context,
        lambda node: _rid_ends(node, "sendButton"),
        "the chat send control was not exposed",
    )
    ui.tap_node(send)
    _wait_ui_node(
        context,
        lambda node: _rid_ends(node, "messageBody") and node.text.strip() == content,
        f"device did not render sent message {content!r}",
    )


def _message_bubble(context: dict[str, Any], content: str) -> Any:
    return next(
        (node for node in _ui_nodes(context)
         if _rid_ends(node, "messageBody") and node.text.strip() == content),
        None,
    )


def _open_action_sheet(context: dict[str, Any], content: str) -> None:
    """Long-press the rendered bubble until the action sheet title appears."""
    ui = context["device"]
    deadline = time.monotonic() + _ui_timeout(context)
    while True:
        bubble = _message_bubble(context, content)
        if bubble is None:
            raise ProductFailure(f"message bubble {content!r} was not on screen")
        ui.long_press(bubble)
        sheet_deadline = time.monotonic() + 6
        while time.monotonic() < sheet_deadline:
            if any(_rid_ends(node, "messageActionsTitle") for node in _ui_nodes(context)):
                return
            time.sleep(0.4)
        if time.monotonic() >= deadline:
            raise ProductFailure("the message action sheet did not open after long-press")


def _sheet_scroll(context: dict[str, Any]) -> None:
    """Scroll the open action sheet's list down by one viewport."""
    patch = context["helpers"][2]
    nodes = _ui_nodes(context)
    list_node = patch.node_by_id(nodes, "messageActionList")
    if list_node is not None and getattr(list_node, "box", None):
        left, top, right, bottom = list_node.box
        cx = (left + right) // 2
        context["device"].swipe(cx, bottom - 24, cx, top + 32, 300)
        return
    # Fallback: swipe up inside the lower half of the screen.
    root = nodes[0] if nodes else None
    box = getattr(root, "box", None) or (0, 0, 320, 640)
    width, height = box[2], box[3]
    context["device"].swipe(width // 2, int(height * 0.78), width // 2, int(height * 0.55), 300)


def _tap_sheet_action(context: dict[str, Any], labels: tuple[str, ...]) -> None:
    """Tap the action-sheet row whose label matches, scrolling the sheet if needed."""
    for _ in range(6):
        target = next(
            (node for node in _ui_nodes(context)
             if _rid_ends(node, "actionLabel") and node.text.strip() in labels),
            None,
        )
        if target is not None:
            context["device"].tap_node(target)
            return
        _sheet_scroll(context)
        time.sleep(0.4)
    raise ProductFailure(f"action {labels!r} was not offered by the message action sheet")


def _history_items(recorder: RecordingClient, token: str,
                   conversation_id: str) -> list[dict[str, Any]]:
    response = recorder.get(
        f"/api/v1/conversations/{conversation_id}/messages",
        token=token, params={"limit": 50},
    )
    if response.status != 200:
        raise ProductFailure(f"peer REST history returned HTTP {response.status}")
    return list((response.json() or {}).get("items") or [])


def _history_wait(recorder: RecordingClient, token: str, conversation_id: str,
                  predicate: Callable[[dict[str, Any]], bool],
                  what: str, timeout: float = 12) -> dict[str, Any]:
    """Return the first peer-visible history row matching predicate, or fail."""
    deadline = time.monotonic() + timeout
    while True:
        items = _history_items(recorder, token, conversation_id)
        match = next((item for item in items if predicate(item)), None)
        if match is not None:
            return match
        if time.monotonic() >= deadline:
            raise ProductFailure(what)
        time.sleep(0.5)


def _history_item_by_content(recorder: RecordingClient, token: str,
                             conversation_id: str, content: str) -> dict[str, Any]:
    return _history_wait(
        recorder, token, conversation_id,
        lambda item: item.get("content") == content,
        f"peer REST history did not contain a {content!r} message",
    )


def _stomp_of_type(events: Iterable[dict[str, Any]], event_type: str,
                   destination_suffix: str | None = None) -> list[dict[str, Any]]:
    out = []
    for event in events:
        if event.get("eventType") != event_type:
            continue
        if destination_suffix and not str(event.get("_destination", "")).endswith(destination_suffix):
            continue
        out.append(event)
    return out


def run_chat_interactions(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """Reply/react/edit/recall/forward through the device long-press sheet.

    The device runs as seeded An; Binh participates through his REST session
    and a subscribed STOMP FrameReader on the direct and forward-target
    topics. Every interaction is asserted on the device UI first, then on
    Binh's API-visible transcript, and (except Forward, which lands on the
    group topic) on the STOMP frame the peer received.
    """
    _campaign_serial(context)
    _campaign_base_url(context)
    _prepare_seeded_an(context)
    patch = context["helpers"][2]
    recorder: RecordingClient = context["client"]
    ui = context["device"]
    an, binh, conversation_id, _recipient_id, binh_user_id = _seed_pair(context)
    an_user_id = str((an.get("user") or {}).get("id") or "")

    # Resolve the seeded forward target by title — the UUID is never embedded.
    inbox = recorder.get("/api/v1/conversations", token=an["accessToken"], params={"limit": 50})
    if inbox.status != 200:
        raise EnvironmentFailure(f"conversation inbox returned HTTP {inbox.status}")
    inbox_body = inbox.json() or []
    inbox_items = inbox_body.get("items") if isinstance(inbox_body, dict) else inbox_body
    group = next((item for item in (inbox_items or [])
                  if item.get("type") == "GROUP"
                  and item.get("title") == _CHAT_FORWARD_TARGET_TITLE), None)
    if group is None or not group.get("id"):
        raise EnvironmentFailure("seeded 'NexaChat Team' forward target was absent from the inbox")
    group_id = str(group["id"])

    # Open the seeded direct conversation on the device.
    row_node = patch.named_conversation_row(_ui_nodes(context), "Binh")
    if row_node is None:
        raise EnvironmentFailure("seed An↔Binh conversation is not visible in the device inbox")
    ui.tap_node(row_node)
    _wait_ui_node(
        context,
        lambda node: _rid_ends(node, "messageInput"),
        "the seeded direct conversation did not open its composer",
    )

    from realtime_probe import FrameReader

    ws_url = recorder.base_url.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
    factory = context.get("frame_reader_factory") or FrameReader
    reader = factory(ws_url, binh["accessToken"])
    context["frame_reader"] = reader
    reader.subscribe(f"/topic/conversations.{conversation_id}")
    reader.subscribe(f"/topic/conversations.{group_id}")

    tag = uuid.uuid4().hex[:8]
    msg_a = "sj-ia-" + tag          # reaction + reply target
    msg_b = "sj-ib-" + tag          # edit target
    msg_b_edited = "sj-ibx-" + tag  # replacement content after Edit
    msg_c = "sj-ic-" + tag          # recall target
    msg_d = "sj-id-" + tag          # forward target
    reply_text = "sj-ir-" + tag     # reply to msg_a
    row_dir = Path(context["row_dir"])

    observed: list[dict[str, Any]] = []

    def drain(seconds: float = 2.5) -> None:
        events = list(reader.events(seconds))
        observed.extend(events)
        context["stomp_events"].extend(events)

    # (conversation_id, message_id, author session) for the soft-delete sweep.
    created: list[tuple[str, str, dict[str, Any]]] = []
    ids: dict[str, str] = {}
    result: dict[str, Any] = {}
    primary_error: Exception | None = None
    try:
        # --- send + react 👍 ---
        _chat_send_text(context, msg_a)
        a_rest = _history_item_by_content(recorder, binh["accessToken"], conversation_id, msg_a)
        ids["a"] = str(a_rest["id"])
        created.append((conversation_id, ids["a"], an))

        _open_action_sheet(context, msg_a)
        tile = _wait_ui_node(
            context,
            lambda node: (node.desc or "").strip() in _CHAT_REACTION_TILE,
            "the 👍 reaction tile was not offered by the action sheet",
        )
        ui.tap_node(tile)
        _wait_sheet_closed(context)
        _wait_ui_node(
            context,
            lambda node: _CHAT_REACTION_EMOJI in (node.desc or "")
                         and ("yours" in node.desc or "của bạn" in node.desc),
            "the reaction chip did not render on the device",
        )
        ui.screenshot(str(row_dir / "chat-interactions-react.png"))
        drain()
        _history_wait(
            recorder, binh["accessToken"], conversation_id,
            lambda item: item.get("id") == ids["a"]
                         and any(entry.get("emoji") == _CHAT_REACTION_EMOJI
                                 and int(entry.get("count") or 0) >= 1
                                 for entry in (item.get("reactions") or [])),
            "peer REST does not show the 👍 reaction",
        )
        reaction_frames = [event for event in _stomp_of_type(observed, "REACTION_UPDATED")
                           if (event.get("payload") or {}).get("messageId") == ids["a"]]
        if not any(_CHAT_REACTION_EMOJI in json.dumps(
                (event.get("payload") or {}).get("reactions"), ensure_ascii=False)
                for event in reaction_frames):
            raise ProductFailure("Binh STOMP did not receive REACTION_UPDATED with the 👍 state")

        # --- reply with a rendered quote ---
        _open_action_sheet(context, msg_a)
        _tap_sheet_action(context, _CHAT_ACTION_LABELS["reply"])
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "replyPreview") and msg_a in node.text,
            "the reply banner did not quote the target message",
        )
        _chat_send_text(context, reply_text)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "replySnippet") and msg_a in node.text,
            "the sent reply did not render the quoted snippet",
        )
        ui.screenshot(str(row_dir / "chat-interactions-reply.png"))
        r_rest = _history_item_by_content(recorder, binh["accessToken"], conversation_id, reply_text)
        ids["reply"] = str(r_rest["id"])
        created.append((conversation_id, ids["reply"], an))
        if str(r_rest.get("replyToId") or "") != ids["a"]:
            raise ProductFailure("peer REST reply did not carry replyToId of the target")
        reply_ref = r_rest.get("replyTo") or {}
        if reply_ref.get("id") != ids["a"] or reply_ref.get("content") != msg_a:
            raise ProductFailure("peer REST replyTo preview did not embed the quoted message")
        drain()
        reply_frames = [event for event in _stomp_of_type(
            observed, "MESSAGE_CREATED", "/topic/conversations." + conversation_id)
            if (event.get("payload") or {}).get("id") == ids["reply"]]
        if not reply_frames:
            raise ProductFailure("Binh STOMP did not receive MESSAGE_CREATED for the reply")

        # --- edit ---
        _chat_send_text(context, msg_b)
        b_rest = _history_item_by_content(recorder, binh["accessToken"], conversation_id, msg_b)
        ids["b"] = str(b_rest["id"])
        created.append((conversation_id, ids["b"], an))

        _open_action_sheet(context, msg_b)
        _tap_sheet_action(context, _CHAT_ACTION_LABELS["edit"])
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "replyPreview") and msg_b in node.text,
            "the edit banner did not show the original message",
        )
        composer = patch.find_chat_composer(_ui_nodes(context))
        ui.tap_node(composer)
        ui.move_end()
        ui.delete_chars(len(msg_b))
        ui.type_text(msg_b_edited)
        save = _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "sendButton")
                         and (node.desc or "").strip() in _CHAT_SAVE_DESC,
            "the composer did not switch to the save affordance",
        )
        ui.tap_node(save)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "messageBody") and node.text.strip() == msg_b_edited,
            "the edited message text did not render on the device",
        )
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "editedLabel")
                         and node.text.strip() in _CHAT_EDITED_MARKERS,
            "the Edited marker did not render on the device",
        )
        ui.screenshot(str(row_dir / "chat-interactions-edit.png"))
        _history_wait(
            recorder, binh["accessToken"], conversation_id,
            lambda item: item.get("id") == ids["b"]
                         and item.get("content") == msg_b_edited
                         and bool(item.get("editedAt")),
            "peer REST did not store the edited content/editedAt",
        )
        drain()
        update_frames = [event for event in _stomp_of_type(observed, "MESSAGE_UPDATED")
                         if (event.get("payload") or {}).get("id") == ids["b"]
                         and (event.get("payload") or {}).get("content") == msg_b_edited
                         and (event.get("payload") or {}).get("editedAt")]
        if not update_frames:
            raise ProductFailure("Binh STOMP did not receive MESSAGE_UPDATED for the edit")

        # --- recall/unsend ---
        _chat_send_text(context, msg_c)
        c_rest = _history_item_by_content(recorder, binh["accessToken"], conversation_id, msg_c)
        ids["c"] = str(c_rest["id"])
        created.append((conversation_id, ids["c"], an))

        _open_action_sheet(context, msg_c)
        _tap_sheet_action(context, _CHAT_ACTION_LABELS["recall"])
        confirm = _wait_ui_node(
            context,
            lambda node: node.text.strip() in _CHAT_DELETE_CONFIRM and node.clickable,
            "the recall confirmation did not offer Delete",
        )
        ui.tap_node(confirm)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "messageBody")
                         and node.text.strip() in _CHAT_TOMBSTONE_MARKERS,
            "the deleted-message tombstone did not render on the device",
        )
        ui.screenshot(str(row_dir / "chat-interactions-recall.png"))
        _history_wait(
            recorder, binh["accessToken"], conversation_id,
            lambda item: item.get("id") == ids["c"]
                         and bool(item.get("deletedAt"))
                         and item.get("content") is None,
            "peer REST did not tombstone the recalled message",
        )
        drain()
        delete_frames = [event for event in _stomp_of_type(observed, "MESSAGE_DELETED")
                         if (event.get("payload") or {}).get("messageId") == ids["c"]]
        if not delete_frames:
            raise ProductFailure("Binh STOMP did not receive MESSAGE_DELETED for the recall")

        # --- forward to the seeded group ---
        _chat_send_text(context, msg_d)
        d_rest = _history_item_by_content(recorder, binh["accessToken"], conversation_id, msg_d)
        ids["d"] = str(d_rest["id"])
        created.append((conversation_id, ids["d"], an))

        _open_action_sheet(context, msg_d)
        _tap_sheet_action(context, _CHAT_ACTION_LABELS["forward"])
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "forwardDialogRoot"),
            "the forward picker did not open",
        )
        target = _wait_ui_node(
            context,
            lambda node: (node.desc or "").strip() == _CHAT_FORWARD_TARGET_TITLE,
            "the seeded group was not offered as a forward target",
        )
        ui.tap_node(target)
        send = _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "forwardSend")
                         and node.text.strip() in _CHAT_SEND_DESC,
            "the forward Send control was not enabled",
        )
        ui.tap_node(send)
        _wait_ui_absent(
            context,
            lambda node: _rid_ends(node, "forwardDialogRoot"),
            "the forward picker did not dismiss",
        )
        ui.screenshot(str(row_dir / "chat-interactions-forward.png"))
        forwarded = _history_item_by_content(recorder, an["accessToken"], group_id, msg_d)
        ids["forwarded"] = str(forwarded["id"])
        created.append((group_id, ids["forwarded"], an))
        if str(forwarded.get("senderId") or "") != an_user_id:
            raise ProductFailure("the forwarded group message was not authored by An")
        drain()
        forward_frames = [event for event in _stomp_of_type(
            observed, "MESSAGE_CREATED", "/topic/conversations." + group_id)
            if (event.get("payload") or {}).get("id") == ids["forwarded"]]
        if not forward_frames:
            raise ProductFailure("Binh STOMP did not receive MESSAGE_CREATED in the forward target")

        # --- peer read receipt ---
        latest_seq = max(int(item.get("seq") or 0)
                         for item in _history_items(recorder, binh["accessToken"], conversation_id))
        read = recorder.post(
            f"/api/v1/conversations/{conversation_id}/read",
            token=binh["accessToken"], body={"upToSeq": latest_seq},
        )
        if read.status not in (200, 204):
            raise ProductFailure(f"peer read receipt returned HTTP {read.status}")
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "deliveryIcon")
                         and (node.desc or "").strip() in _CHAT_READ_MARKERS,
            "the device did not render the peer Read receipt",
        )
        ui.screenshot(str(row_dir / "chat-interactions-read.png"))
        drain()
        read_frames = [event for event in _stomp_of_type(observed, "MESSAGE_READ")
                       if str((event.get("payload") or {}).get("userId") or "") == binh_user_id]
        if not read_frames:
            raise ProductFailure("the conversation topic did not broadcast MESSAGE_READ")

        result = {
            "reaction": "👍 on device + REST + REACTION_UPDATED",
            "reply": "quote rendered + replyToId + MESSAGE_CREATED",
            "edit": "Edited marker + editedAt + MESSAGE_UPDATED",
            "recall": "tombstone + deletedAt + MESSAGE_DELETED",
            "forward": "NexaChat Team history + group MESSAGE_CREATED",
            "read_receipt": "device Read + MESSAGE_READ",
        }
    except Exception as error:
        primary_error = error
    finally:
        # Discover any of this run's unique markers that reached the server but
        # whose ids were not recorded yet, so the sweep is complete.
        markers = (msg_a, msg_b, msg_b_edited, msg_c, msg_d, reply_text)
        known = {message_id for _cid, message_id, _author in created}
        for conv_id, token in ((conversation_id, binh["accessToken"]),
                               (group_id, an["accessToken"])):
            try:
                for item in _history_items(recorder, token, conv_id):
                    if item.get("content") in markers and str(item.get("id")) not in known:
                        created.append((conv_id, str(item["id"]), an))
                        known.add(str(item["id"]))
            except Exception:
                pass
        cleanup_errors: list[str] = []
        for conv_id, message_id, author in created:
            try:
                deleted = recorder.delete(
                    f"/api/v1/conversations/{conv_id}/messages/{message_id}",
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
        raise HarnessError("one or more unique chat-interactions messages could not be soft-deleted")
    result["cleanup"] = "unique direct and group messages soft-deleted"
    return result


# --- summary-ask ---------------------------------------------------------------
# Conversation summary sheet + Ask this chat on the seeded An↔Binh direct.
# The summary's scope chips are rendered client-side; the deterministic path is
# "Last N" (UNREAD depends on the peer-read watermark). Ask uses a lookup-style
# question against a seeded unique fact, so the local extractive engine answers
# deterministically and cites the fact message itself.
_SUMMARY_MENU_ITEM = ("Summarize conversation", "Tóm tắt trò chuyện")
_SUMMARY_SCOPE_LAST_N = ("Last N", "N tin gần nhất")
_SUMMARY_LOCAL_BADGE = ("Local", "Cục bộ")
_SUMMARY_CHIP_PREFIX = "↗"  # summary_citation_chip "↗ <label>" is the jumpable form
_SUMMARY_CHIP_UNAVAILABLE = ("Unavailable", "Không khả dụng")
_ASK_MENU_ITEM = ("Ask this chat", "Hỏi trong trò chuyện")
_ASK_MODE_BADGE = ("From a message", "Từ tin nhắn")
_ASK_CONSENT_TITLE = ("Use AI to answer?", "Bật AI để trả lời?")
_ASK_CONSENT_CONFIRM = ("Agree", "Đồng ý")
_ASK_FILLER_COUNT = 14  # pushes the fact bubble off the initial viewport
_ASK_RESULT_TIMEOUT = 45.0


def _node_visible(node: Any) -> bool:
    box = getattr(node, "box", None)
    return box is not None and box[2] > box[0] and box[3] > box[1]


def _codename(nonce: str, salt: str) -> str:
    """Letters-only distinctive token; the same shape ai_privacy_e2e probes with."""
    alphabet = "bcdfghjklmnpqrstvwxz"
    seed = uuid.uuid5(uuid.NAMESPACE_URL, nonce + "|" + salt).hex
    return "zq" + "".join(alphabet[int(ch, 16) % len(alphabet)] for ch in seed[:9])


def _open_chat_overflow(context: dict[str, Any], item_labels: tuple[str, ...]) -> None:
    """Tap the chat toolbar overflow and pick the menu row matching item_labels."""
    ui = context["device"]
    more = _wait_ui_node(
        context,
        lambda node: _rid_ends(node, "moreButton"),
        "the chat overflow button was not exposed",
    )
    ui.tap_node(more)
    item = _wait_ui_node(
        context,
        lambda node: node.text.strip() in item_labels,
        f"the chat overflow menu did not offer {item_labels!r}",
    )
    ui.tap_node(item)


def _user_settings(recorder: RecordingClient, token: str) -> dict[str, Any]:
    response = recorder.get("/api/v1/users/me/settings", token=token)
    if response.status != 200:
        raise EnvironmentFailure(f"GET /api/v1/users/me/settings returned HTTP {response.status}")
    return dict(response.json() or {})


def _set_ai_consent(recorder: RecordingClient, token: str, value: bool) -> None:
    response = recorder.patch(
        "/api/v1/users/me/settings", token=token, body={"aiSummaryConsent": value},
    )
    if response.status != 200 or (response.json() or {}).get("aiSummaryConsent") is not value:
        raise ProductFailure("the aiSummaryConsent settings PATCH did not echo the requested value")


def _ai_opt_out_path(conversation_id: str) -> str:
    return f"/api/v1/conversations/{conversation_id}/ai-opt-out"


def _ai_opt_out_read(recorder: RecordingClient, token: str, conversation_id: str) -> bool:
    response = recorder.get(_ai_opt_out_path(conversation_id), token=token)
    if response.status != 200:
        raise EnvironmentFailure(f"ai-opt-out GET returned HTTP {response.status}")
    return bool((response.json() or {}).get("aiOptOut"))


def _ai_opt_out_write(recorder: RecordingClient, token: str,
                      conversation_id: str, value: bool) -> None:
    call = recorder.put if value else recorder.delete
    response = call(_ai_opt_out_path(conversation_id), token=token)
    if response.status != 200 or (response.json() or {}).get("aiOptOut") is not value:
        raise ProductFailure(
            f"ai-opt-out {'PUT' if value else 'DELETE'} did not persist {value}")


def _call_minute_retry(call: Callable[[], Any]) -> Any:
    """One retry when the per-minute AI bucket answers 429 (rag/summary rate)."""
    response = call()
    if getattr(response, "status", None) == 429:
        time.sleep(62 - (time.time() % 60))
        response = call()
    return response


def _ask_question(recorder: RecordingClient, token: str,
                  conversation_id: str, question: str) -> dict[str, Any]:
    response = _call_minute_retry(lambda: recorder.post(
        f"/api/v1/conversations/{conversation_id}/ask",
        token=token, body={"question": question},
    ))
    if response.status != 200:
        raise ProductFailure(f"ask returned HTTP {response.status}")
    return dict(response.json() or {})


def _ask_cited_ids(payload: dict[str, Any]) -> list[str]:
    return [str(citation.get("messageId"))
            for citation in (payload or {}).get("citations") or []
            if isinstance(citation, dict) and citation.get("messageId")]


def _wait_summary_ready(context: dict[str, Any]) -> Any:
    """Wait for the summary sheet's READY body; report the last visible error."""
    deadline = time.monotonic() + _ASK_RESULT_TIMEOUT
    last_error = ""
    while True:
        nodes = _ui_nodes(context)
        bullets = [node for node in nodes
                   if _rid_ends(node, "summaryBulletText") and _node_visible(node)]
        header = next((node for node in nodes
                       if _rid_ends(node, "summaryResultHeader")
                       and node.text.strip() and _node_visible(node)), None)
        if bullets and header is not None:
            return header
        error = next((node for node in nodes
                      if _rid_ends(node, "summaryErrorText")
                      and node.text.strip() and _node_visible(node)), None)
        last_error = error.text.strip() if error is not None else last_error
        if time.monotonic() >= deadline:
            detail = f" (error card: {last_error!r})" if last_error else ""
            raise ProductFailure(f"the LOCAL summary did not render its content{detail}")
        time.sleep(0.5)


def _summary_citation_chip(context: dict[str, Any]) -> Any:
    """Find a jumpable summary citation chip, scrolling the sheet to reveal one."""
    ui = context["device"]
    unavailable = 0
    for _ in range(8):
        nodes = _ui_nodes(context)
        for node in nodes:
            if not (_rid_ends(node, "citationChip") and node.clickable and _node_visible(node)):
                continue
            if node.text.strip().startswith(_SUMMARY_CHIP_PREFIX):
                return node
            if node.text.strip() in _SUMMARY_CHIP_UNAVAILABLE:
                unavailable += 1
        scroll = next((node for node in nodes if _rid_ends(node, "summarySheetScroll")), None)
        box = getattr(scroll, "box", None) or (0, 0, 320, 640)
        ui.swipe((box[0] + box[2]) // 2, box[3] - 30, (box[0] + box[2]) // 2,
                 box[1] + (box[3] - box[1]) // 3, 350)
        time.sleep(0.6)
    raise ProductFailure(
        f"the LOCAL summary rendered no jumpable citation chip ({unavailable} unavailable chips)")


def _wait_ask_answer(context: dict[str, Any], send_labels: tuple[str, ...]) -> tuple[Any, list[Any]]:
    """Wait for the ask sheet's rendered answer; handle the consent dialog once.

    Returns (askAnswer node, citationButton nodes). A missing send tap is
    retried once — the IME can cover the button on small viewports.
    """
    ui = context["device"]
    deadline = time.monotonic() + _ASK_RESULT_TIMEOUT
    consent_tapped_at: float | None = None
    send_retried = 0
    idle_since = time.monotonic()
    while True:
        nodes = _ui_nodes(context)
        if any(node.text.strip() in _ASK_CONSENT_TITLE for node in nodes):
            if consent_tapped_at is None:
                agree = next((node for node in nodes
                              if node.text.strip() in _ASK_CONSENT_CONFIRM), None)
                if agree is not None:
                    consent_tapped_at = time.monotonic()
                    ui.tap_node(agree)
                    time.sleep(0.5)
                    continue
        status = next((node for node in nodes
                       if _rid_ends(node, "askStatusMessage")
                       and node.text.strip() and _node_visible(node)), None)
        answer = next((node for node in nodes
                       if _rid_ends(node, "askAnswer")
                       and node.text.strip() and _node_visible(node)), None)
        chips = [node for node in nodes
                 if _rid_ends(node, "citationButton") and _node_visible(node)]
        if answer is not None and chips:
            return answer, chips
        # After agreeing to consent the consent-required status can linger for
        # a beat while the sheet re-requests; do not report it as terminal yet.
        consent_grace = (consent_tapped_at is not None
                         and time.monotonic() - consent_tapped_at < 4)
        if status is not None and not consent_grace:
            raise ProductFailure(f"the ask sheet surfaced an error: {status.text.strip()!r}")
        loading = any(_rid_ends(node, "askSkeleton") and _node_visible(node) for node in nodes)
        if loading:
            idle_since = time.monotonic()
        elif send_retried < 1 and time.monotonic() - idle_since > 5:
            send = next((node for node in nodes
                         if _rid_ends(node, "askSend")
                         and node.text.strip() in send_labels), None)
            if send is not None:
                send_retried += 1
                ui.tap_node(send)
                idle_since = time.monotonic()
        if time.monotonic() >= deadline:
            raise ProductFailure("the ask sheet never rendered an answer or a terminal state")
        time.sleep(0.5)


def run_summary_ask(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """Summary sheet + Ask this chat over the seeded An↔Binh direct.

    The device runs as seeded An; Binh authors the unique lookup fact and its
    filler over REST so the extractive engine has a deterministic hit. Summary
    and ask are asserted on the device UI first; the ask is then verified
    server-side through the shared cache replay, and Binh's ai-opt-out is
    exercised as a REST round-trip (it removes the fact citation).
    """
    _campaign_serial(context)
    _campaign_base_url(context)
    _prepare_seeded_an(context)
    api, _adb_ui, patch = context["helpers"]
    recorder: RecordingClient = context["client"]
    ui = context["device"]
    an, binh, conversation_id, _an_user_id, _binh_user_id = _seed_pair(context)
    _, conversation = api.direct_conversation(recorder, an["accessToken"], api.USER_C["phone"])
    if conversation.get("e2eeEnabled"):
        raise EnvironmentFailure(
            "the seeded An↔Binh conversation is E2EE; summary/ask are unavailable by design")

    original_consent = bool(_user_settings(recorder, an["accessToken"]).get("aiSummaryConsent"))
    original_opt_out = _ai_opt_out_read(recorder, binh["accessToken"], conversation_id)

    row_dir = Path(context["row_dir"])
    tag = uuid.uuid4().hex[:10]
    code = _codename(tag, "code")
    secret = _codename(tag, "secret")
    fact_content = f"The {code} locker passphrase is {secret} for the north warehouse."
    question = f"What is the {code} locker passphrase?"
    seeded: list[str] = []
    result: dict[str, Any] = {}
    primary_error: Exception | None = None
    try:
        # Consent fixture: the asker consents and neither member is opted out.
        _set_ai_consent(recorder, an["accessToken"], True)
        _ai_opt_out_write(recorder, binh["accessToken"], conversation_id, False)
        _ai_opt_out_write(recorder, an["accessToken"], conversation_id, False)

        fact = api.send_text(recorder, binh["accessToken"], conversation_id, fact_content)
        fact_id = str(fact.get("id") or "")
        if not fact_id:
            raise EnvironmentFailure("Binh's seeded fact did not return a message id")
        fact_seq = int(fact.get("seq") or 0)
        seeded.append(fact_id)
        last_seq = fact_seq
        for index in range(_ASK_FILLER_COUNT):
            filler = api.send_text(
                recorder, binh["accessToken"], conversation_id,
                f"sj-ask filler {index} {tag}")
            seeded.append(str(filler.get("id") or ""))
            last_seq = max(last_seq, int(filler.get("seq") or 0))
        if not all(seeded):
            raise EnvironmentFailure("a seeded filler did not return a message id")
        # Marking read settles the unread banner and opens the chat at the
        # bottom, so the fact bubble is genuinely off the initial viewport.
        read = recorder.post(
            f"/api/v1/conversations/{conversation_id}/read",
            token=an["accessToken"], body={"upToSeq": last_seq},
        )
        if read.status not in (200, 204):
            raise EnvironmentFailure(f"conversation read returned HTTP {read.status}")

        row_node = patch.named_conversation_row(_ui_nodes(context), "Binh")
        if row_node is None:
            raise EnvironmentFailure("seed An↔Binh conversation is not visible in the device inbox")
        ui.tap_node(row_node)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "messageInput"),
            "the seeded direct conversation did not open its composer",
        )

        # --- conversation summary sheet -----------------------------------
        _open_chat_overflow(context, _SUMMARY_MENU_ITEM)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "summarySheetRoot"),
            "the summary sheet did not open",
        )
        last_n = _wait_ui_node(
            context,
            lambda node: node.text.strip() in _SUMMARY_SCOPE_LAST_N and node.clickable,
            "the summary scope chips did not offer Last N",
        )
        ui.tap_node(last_n)
        header = _wait_summary_ready(context)
        header_text = header.text.strip()
        if not any(badge in header_text for badge in _SUMMARY_LOCAL_BADGE):
            raise ProductFailure(
                f"the summary header did not name the LOCAL engine: {header_text!r}")
        bullets = [node for node in _ui_nodes(context)
                   if _rid_ends(node, "summaryBulletText") and _node_visible(node)]
        ui.screenshot(str(row_dir / "summary-ask-summary.png"))

        chip = _summary_citation_chip(context)
        chip_label = chip.text.strip()
        ui.tap_node(chip)
        _wait_ui_absent(
            context,
            lambda node: _rid_ends(node, "summarySheetRoot"),
            "the summary citation chip did not dismiss the sheet",
        )
        ui.screenshot(str(row_dir / "summary-ask-citation-jump.png"))

        # Peer-side REST verify of the same summary contract the sheet used.
        summary_verify = _call_minute_retry(lambda: recorder.post(
            f"/api/v1/conversations/{conversation_id}/summary",
            token=an["accessToken"],
            body={"scope": "LAST_N", "limit": 50, "refresh": False},
        ))
        if summary_verify.status != 200:
            raise ProductFailure(f"peer REST summary returned HTTP {summary_verify.status}")
        summary_body = dict(summary_verify.json() or {})
        if summary_body.get("generatedBy") != "LOCAL":
            raise ProductFailure(
                f"peer REST summary was not produced by the LOCAL engine: "
                f"{summary_body.get('generatedBy')!r}")
        if int(summary_body.get("messageCount") or 0) < 5:
            raise ProductFailure("peer REST summary covered fewer than 5 messages")

        # --- ask this chat -------------------------------------------------
        fact_visible_before = any(
            _rid_ends(node, "messageBody") and code in (node.text or "")
            for node in _ui_nodes(context))
        _open_chat_overflow(context, _ASK_MENU_ITEM)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "askSheetRoot"),
            "the ask sheet did not open",
        )
        field = _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "askQuestion"),
            "the ask question field was not exposed",
        )
        ui.tap_node(field)
        ui.type_text(question)
        send = _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "askSend") and node.clickable,
            "the ask send control was not exposed",
        )
        ui.tap_node(send)
        answer_node, chips = _wait_ask_answer(context, ("Ask", "Hỏi"))
        answer_text = answer_node.text.strip()
        if secret not in answer_text:
            raise ProductFailure("the ask answer did not quote the seeded fact")
        fact_chip = next(
            (chip for chip in chips
             if code in (chip.text or "") or code in (chip.desc or "")), None)
        if fact_chip is None:
            raise ProductFailure("no Sources chip cited the seeded fact snippet")
        badge = next((node for node in _ui_nodes(context)
                      if _rid_ends(node, "askModeBadge")
                      and node.text.strip() in _ASK_MODE_BADGE
                      and _node_visible(node)), None)
        if badge is None:
            raise ProductFailure("the EXTRACTIVE 'From a message' badge did not render")
        ui.screenshot(str(row_dir / "summary-ask-answer.png"))

        # Peer REST verify: the same question must now replay the device ask's
        # cached answer — proof the sheet's request reached the server.
        verify = _ask_question(recorder, an["accessToken"], conversation_id, question)
        if verify.get("mode") != "CACHE" or (verify.get("usage") or {}).get("cached") is not True:
            raise ProductFailure(
                f"the device ask did not populate the shared cache (mode {verify.get('mode')!r})")
        verify_citation = next(
            (citation for citation in verify.get("citations") or []
             if str(citation.get("messageId")) == fact_id), None)
        if verify_citation is None:
            raise ProductFailure("the cached ask did not cite the seeded fact message")
        if int(verify_citation.get("seq") or -1) != fact_seq:
            raise ProductFailure("the cached citation seq did not match the fact message")

        # Citation tap: the sheet dismisses and the cited bubble renders. The
        # fillers pushed the fact off the initial viewport, so its appearance
        # is real jump evidence.
        ui.tap_node(fact_chip)
        _wait_ui_absent(
            context,
            lambda node: _rid_ends(node, "askSheetRoot"),
            "the ask sheet did not dismiss after the citation tap",
        )
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "messageBody") and code in (node.text or ""),
            "the cited message did not render after the ask citation jump",
        )
        ui.screenshot(str(row_dir / "summary-ask-jump.png"))

        # Consent/opt-out exercise over REST: Binh's opt-out must drop the
        # fact from AI context (contextOmitted + no citation); opting back in
        # restores it.
        _ai_opt_out_write(recorder, binh["accessToken"], conversation_id, True)
        omitted = _ask_question(recorder, an["accessToken"], conversation_id, question)
        if omitted.get("contextOmitted") is not True:
            raise ProductFailure("the ask did not report contextOmitted while Binh was opted out")
        if fact_id in _ask_cited_ids(omitted):
            raise ProductFailure("the ask still cited the opted-out author's fact message")
        if secret in (omitted.get("answer") or "") or secret in json.dumps(
                omitted.get("citations") or []):
            raise ProductFailure("the ask leaked the opted-out author's text")
        _ai_opt_out_write(recorder, binh["accessToken"], conversation_id, False)
        restored: dict[str, Any] = {}
        for _ in range(3):
            restored = _ask_question(recorder, an["accessToken"], conversation_id, question)
            if fact_id in _ask_cited_ids(restored):
                break
            time.sleep(1.5)
        if fact_id not in _ask_cited_ids(restored):
            raise ProductFailure("opting back in did not restore the fact citation")
        if restored.get("contextOmitted") is not False:
            raise ProductFailure("contextOmitted stayed true after opting back in")

        result = {
            "summary": "LOCAL sheet rendered + citation chip jump dismissed it",
            "summary_header": header_text,
            "summary_chip": chip_label,
            "summary_rest": {"generatedBy": summary_body.get("generatedBy"),
                             "cached": summary_body.get("cached"),
                             "messageCount": summary_body.get("messageCount")},
            "ask_answer": "EXTRACTIVE badge + answer quotes the seeded fact",
            "ask_rest_verify": "CACHE replay cites the seeded message id and seq",
            "citation_jump": "sheet dismissed; cited messageBody rendered",
            "fact_visible_before_jump": fact_visible_before,
            "opt_out": "fact citation dropped (contextOmitted) and restored",
        }
    except Exception as error:
        primary_error = error
    finally:
        cleanup_errors: list[str] = []
        # Best-effort UI restore: leave any open sheet/dialog before the next row.
        try:
            for _ in range(3):
                nodes = _ui_nodes(context)
                if any(_rid_ends(node, suffix) for node in nodes
                       for suffix in ("askSheetRoot", "summarySheetRoot")):
                    ui.key("KEYCODE_BACK")
                    time.sleep(0.4)
                else:
                    break
        except Exception as error:
            cleanup_errors.append(type(error).__name__)
        try:
            _ai_opt_out_write(recorder, binh["accessToken"], conversation_id, original_opt_out)
        except Exception as error:
            cleanup_errors.append(f"opt-out restore: {type(error).__name__}")
        try:
            _set_ai_consent(recorder, an["accessToken"], original_consent)
        except Exception as error:
            cleanup_errors.append(f"consent restore: {type(error).__name__}")
        for message_id in seeded:
            try:
                deleted = recorder.delete(
                    f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
                    token=binh["accessToken"],
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
        raise HarnessError("summary-ask cleanup could not restore settings or delete fixtures")
    result["cleanup"] = "seeded messages soft-deleted; consent and opt-out restored"
    return result


SEED_PHONES = frozenset({
    "+84900000001", "+84900000002", "+84900000003",
    "+84900000101", "+84900000102",
})
DISPOSABLE_PASSWORD = "password123"
REPLACEMENT_PASSWORD = "password123-reset"
CALL_MARKER_SETS = (
    ("Incoming voice call", "Accept", "Decline"),
    ("Cuộc gọi thoại đến", "Trả lời", "Từ chối"),
    ("Incoming call", "Accept", "Decline"),
    ("Cuộc gọi đến", "Trả lời", "Từ chối"),
)
MESSAGE_SHADE = ("You have a new message", "Tin nhắn mới", "Bạn có tin nhắn mới")
REMINDER_SHADE = ("A reminder is due", "Nhắc nhở", "Đến giờ nhắc")
_SHADE_MARKERS = MESSAGE_SHADE + REMINDER_SHADE + ("Incoming voice call", "Cuộc gọi thoại đến")
INCOMING_ACTIVITY = "com.nexachat.call.ui.IncomingCallActivity"


def _campaign_serial(context: dict[str, Any]) -> str:
    serial = context.get("serial")
    if serial is None and context.get("adb") is not None:
        serial = getattr(context["adb"], "serial", None)
    if not isinstance(serial, str) or not serial:
        raise HarnessError("serial is required")
    return validate_serial(serial)


def _campaign_base_url(context: dict[str, Any]) -> str:
    base_url = context.get("base_url")
    if not isinstance(base_url, str) or not base_url:
        raise HarnessError("local base URL is required")
    row = context.get("row")
    selected = (row,) if isinstance(row, JourneyRow) else ()
    validate_base_url(base_url, selected, False)
    return base_url.rstrip("/")


def _require_disposable_phone(phone: str) -> str:
    if phone in SEED_PHONES:
        raise HarnessError("refusing seed user; this journey uses a disposable account only")
    if not phone.startswith("+") or not phone[1:].isdigit():
        raise HarnessError("disposable phone is not an E.164 number")
    return phone


def _new_disposable_phone() -> str:
    return _require_disposable_phone(f"+8499{uuid.uuid4().int % 10_000_000:07d}")


def _new_disposable_email() -> str:
    return f"sj-{uuid.uuid4().hex[:12]}@example.test"


def _import_inject_push():
    if str(DEVICE_DIR) not in sys.path:
        sys.path.insert(0, str(DEVICE_DIR))
    return importlib.import_module("inject_push")


def _import_mail_sink():
    if str(E2E_DIR) not in sys.path:
        sys.path.insert(0, str(E2E_DIR))
    return importlib.import_module("mail_sink")


def _invoke_inject_push(serial: str, kind_args: list[str]) -> None:
    """Deliver a payload through inject_push.main. Never assemble the broadcast here."""
    inject_push = _import_inject_push()
    validated = inject_push.require_serial(serial)
    try:
        code = inject_push.main(["--serial", validated, *kind_args])
    except inject_push.InjectPushError as error:
        raise HarnessError(str(error)) from error
    if code != 0:
        raise EnvironmentFailure(f"inject_push exited {code}")


def _mark_injected(context: dict[str, Any]) -> None:
    row_dir = context.get("row_dir")
    if row_dir is None:
        return
    path = Path(row_dir)
    path.mkdir(parents=True, exist_ok=True)
    (path / "injection-label.txt").write_text("INJECTED\n", encoding="utf-8")


def _node_texts(nodes: Iterable[Any]) -> set[str]:
    return {str(getattr(node, "text", "")).strip() for node in nodes}


def _ui_nodes(context: dict[str, Any]) -> list[Any]:
    patch = context["helpers"][2]
    return list(patch.ui_nodes(context["device"], timeout=8))


def _ui_timeout(context: dict[str, Any]) -> float:
    return float(context.get("ui_timeout", 20))


def _wait_for_call_surface(context: dict[str, Any], adb: Adb,
                           options: tuple[tuple[str, ...], ...]) -> tuple[tuple[str, ...], str]:
    """Wait for the injected-call markers on whichever surface the platform shows.

    On API 34+ this app cannot hold USE_FULL_SCREEN_INTENT (no dialer role), so
    BAL keeps IncomingCallActivity invisible and the incoming-call surface is
    the PRIORITY_MAX heads-up notification. On older builds the activity itself
    is the surface. Returns (matched labels, "activity"|"notification").
    """
    deadline = time.monotonic() + _ui_timeout(context)
    while True:
        texts = _node_texts(_ui_nodes(context))
        for labels in options:
            if set(labels).issubset(texts):
                return labels, "activity"
        dump = _notification_dump(adb)
        for labels in options:
            if all(marker in dump for marker in labels):
                return labels, "notification"
        if time.monotonic() >= deadline:
            raise ProductFailure("injected call markers were absent")
        time.sleep(0.4)


def _wait_for_marker_set(context: dict[str, Any],
                         options: tuple[tuple[str, ...], ...]) -> tuple[str, ...]:
    deadline = time.monotonic() + _ui_timeout(context)
    while True:
        texts = _node_texts(_ui_nodes(context))
        for labels in options:
            if set(labels).issubset(texts):
                return labels
        if time.monotonic() >= deadline:
            raise ProductFailure("injected call markers were absent")
        time.sleep(0.4)


def _wait_until_absent(context: dict[str, Any], labels: tuple[str, ...]) -> None:
    deadline = time.monotonic() + _ui_timeout(context)
    while True:
        if not (set(labels) & _node_texts(_ui_nodes(context))):
            return
        if time.monotonic() >= deadline:
            raise ProductFailure("injected call UI remained after decline")
        time.sleep(0.4)


def _tap_text(context: dict[str, Any], labels: tuple[str, ...]) -> bool:
    for node in _ui_nodes(context):
        if str(getattr(node, "text", "")).strip() in labels:
            context["device"].tap_node(node)
            return True
    return False


def _prepare_seeded_an(context: dict[str, Any]) -> None:
    # A shade left open (by a prior row or a manual probe) hides the login form.
    context["adb"].command("shell", "cmd", "statusbar", "collapse", timeout=20, check=False)
    if context.get("reset_app_data"):
        _clear_app_data(context)
    _assert_device_account_an(
        context["device"], context["helpers"][2], context.get("row_dir"),
        bool(context.get("reset_app_data")),
    )


def _seed_pair(context: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any], str, str, str]:
    api = context["helpers"][0]
    recorder = context["client"]
    an = _actor_session(api, recorder, api.USER_A["phone"])
    binh = _actor_session(api, recorder, api.USER_C["phone"])
    _, conversation = api.direct_conversation(recorder, an["accessToken"], api.USER_C["phone"])
    conversation_id = str(conversation.get("id") or "")
    recipient_id = str((an.get("user") or {}).get("id") or "")
    caller_id = str((binh.get("user") or {}).get("id") or "")
    if not conversation_id or not recipient_id or not caller_id:
        raise EnvironmentFailure("seed An/Binh fixture did not return ids")
    return an, binh, conversation_id, recipient_id, caller_id


def _cancel_injected_notifications(adb: Adb, dump: str) -> None:
    # API 36 removed `cmd notification cancel` entirely, so cleanup goes through
    # the debug receiver's DUMP-gated CLEAR_NOTIFICATIONS action instead.
    if not any(marker in dump for marker in _SHADE_MARKERS):
        return
    adb.command(
        "shell", "am", "broadcast",
        "-n", f"{PACKAGE}/com.nexachat.debug.DebugPushInjectionReceiver",
        "-a", f"{PACKAGE}.CLEAR_NOTIFICATIONS",
        timeout=20,
    )
    deadline = time.monotonic() + 10
    while True:
        remaining = _notification_dump(adb)
        if not any(marker in remaining for marker in _SHADE_MARKERS):
            return
        if time.monotonic() >= deadline:
            raise HarnessError("injected notifications still posted after debug clear broadcast")
        time.sleep(0.3)


_CALL_DUMP_MARKERS = (
    "Incoming voice call", "Incoming video call",
    "Cuộc gọi thoại đến", "Cuộc gọi video đến",
)


def _injected_call_record_absent(adb: Adb) -> bool:
    return not any(marker in _notification_dump(adb) for marker in _CALL_DUMP_MARKERS)


def _notification_dump(adb: Adb) -> str:
    return adb.command("shell", "dumpsys", "notification", "--noredact", timeout=30, check=False)


def run_calls_injected_notification(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """F4: dead process, inject_push call, incoming-call markers, then decline.

    One incoming call cannot be both accepted and declined. The executor shows
    both controls and declines so the injected UI does not remain. Server call
    history is read only to reject a phantom row for this local call id.
    """
    serial = _campaign_serial(context)
    _campaign_base_url(context)
    adb = context["adb"]
    _prepare_seeded_an(context)
    an, _binh, conversation_id, recipient_id, caller_id = _seed_pair(context)
    call_id = "sj-call-" + uuid.uuid4().hex[:8]
    cleanup_errors: list[str] = []
    primary_error: Exception | None = None
    try:
        adb.command("shell", "input", "keyevent", "KEYCODE_HOME", timeout=20)
        # am kill only removes cached processes; an in-flight WorkManager job
        # holds the process above the kill threshold until it finishes, so retry
        # kill+probe for a bounded window instead of checking once.
        for _ in range(30):
            adb.command("shell", "am", "kill", PACKAGE, timeout=20, check=False)
            time.sleep(0.5)
            if not adb.command("shell", "pidof", PACKAGE, timeout=15, check=False).strip():
                break
        else:
            raise EnvironmentFailure("process still alive after am kill; refusing force-stop")
        _invoke_inject_push(serial, [
            "call",
            "--recipient-id", recipient_id,
            "--conversation-id", conversation_id,
            "--call-id", call_id,
            "--caller-id", caller_id,
            "--caller-name", "Binh",
        ])
        _mark_injected(context)
        labels, surface = _wait_for_call_surface(context, adb, CALL_MARKER_SETS)
        activities = adb.command("shell", "dumpsys", "activity", "activities", timeout=30, check=False)
        row_dir = context.get("row_dir")
        if row_dir is not None and hasattr(context.get("device"), "screenshot"):
            context["device"].screenshot(str(Path(row_dir) / "call-incoming.png"))
        declined = _tap_text(context, ("Decline", "Từ chối"))
        if not declined and not _injected_call_record_absent(adb):
            # The heads-up peek can collapse before the uiautomator dump catches
            # it (the push-only session only lives until the ~15s signaling grace
            # expires), and the IncomingCallActivity is non-exported, so neither
            # `am start` nor shade-row text is a reliable substitute for the
            # user's tap. Fire the real production decline action instead: the
            # DUMP-gated debug hook resolves the notification's own decline
            # PendingIntent and send()s it, running the identical
            # CallForegroundService -> CallManager.reject path as a user tap.
            adb.command(
                "shell", "am", "broadcast",
                "-n", f"{PACKAGE}/com.nexachat.debug.DebugPushInjectionReceiver",
                "-a", f"{PACKAGE}.DECLINE_INCOMING_CALL",
                timeout=20,
            )
            deadline = time.monotonic() + 10
            while not _injected_call_record_absent(adb):
                if time.monotonic() >= deadline:
                    raise ProductFailure("injected call record remained after decline broadcast")
                time.sleep(0.4)
            declined = True
        if not declined and not _injected_call_record_absent(adb):
            raise ProductFailure("decline action was not on screen")
        _wait_until_absent(context, labels)
        history = context["client"].get(
            f"/api/v1/conversations/{conversation_id}/calls",
            token=an["accessToken"], params={"limit": 20},
        )
        if history.status != 200:
            raise ProductFailure(f"call history returned HTTP {history.status}")
        body = history.json() or {}
        items = body.get("items") if isinstance(body, dict) else body
        for item in items or []:
            if str(item.get("callId") or "") == call_id:
                raise ProductFailure("injected call appeared in server call history")
        return {"label": "INJECTED", "declined": True, "phantom_call": False,
                "incoming_surface": surface, "activity_present": INCOMING_ACTIVITY in activities}
    except Exception as error:
        primary_error = error
        raise
    finally:
        try:
            _tap_text(context, ("Decline", "Từ chối"))
        except Exception as error:
            cleanup_errors.append(type(error).__name__)
        try:
            _cancel_injected_notifications(adb, _notification_dump(adb))
        except Exception as error:
            cleanup_errors.append(type(error).__name__)
        if cleanup_errors:
            context["cleanup_errors"] = cleanup_errors
            if primary_error is None:
                raise HarnessError("injected call cleanup failed")


def _wait_for_generic_shade(adb: Adb, forbidden: tuple[str, ...], timeout: float) -> str:
    deadline = time.monotonic() + timeout
    while True:
        dump = _notification_dump(adb)
        message_ok = any(marker in dump for marker in MESSAGE_SHADE)
        reminder_ok = any(marker in dump for marker in REMINDER_SHADE)
        if message_ok and reminder_ok:
            if any(secret and secret in dump for secret in forbidden):
                raise ProductFailure("notification shade contained the payload body")
            return dump
        if time.monotonic() >= deadline:
            raise ProductFailure("notification shade did not show the injected generic text")
        time.sleep(0.4)


def run_injected_notifications(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """Inject NEW_MESSAGE and REMINDER through inject_push and read the generic shade.

    The notification copy is the generic string, not the message or reminder
    body. Evidence is labeled INJECTED. This does not claim a live provider push.
    """
    serial = _campaign_serial(context)
    _campaign_base_url(context)
    adb = context["adb"]
    _prepare_seeded_an(context)
    an, binh, conversation_id, recipient_id, _caller_id = _seed_pair(context)
    api = context["helpers"][0]
    recorder = context["client"]
    body = "sj-msg-" + uuid.uuid4().hex[:12]
    title = "sj-rem-" + uuid.uuid4().hex[:12]
    message_id = ""
    reminder_id = ""
    cleanup_errors: list[str] = []
    primary_error: Exception | None = None
    try:
        message = api.send_text(recorder, binh["accessToken"], conversation_id, body)
        message_id = str(message.get("id") or "")
        seq = str(int(message.get("seq")))
        if not message_id:
            raise EnvironmentFailure("peer send did not return a message id")
        adb.command("shell", "input", "keyevent", "KEYCODE_HOME", timeout=20)
        _invoke_inject_push(serial, [
            "message",
            "--recipient-id", recipient_id,
            "--conversation-id", conversation_id,
            "--message-id", message_id,
            "--seq", seq,
        ])
        remind_at = (datetime.now(timezone.utc) + timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
        created = recorder.post(
            f"/api/v1/conversations/{conversation_id}/reminders",
            token=an["accessToken"],
            body={"title": title, "remindAt": remind_at, "repeat": "NONE"},
        )
        if created.status not in (200, 201):
            raise ProductFailure(f"reminder create returned HTTP {created.status}")
        reminder_id = str((created.json() or {}).get("id") or "")
        if not reminder_id:
            raise EnvironmentFailure("reminder create did not return an id")
        occurrence = uuid.uuid4().hex
        _invoke_inject_push(serial, [
            "reminder",
            "--recipient-id", recipient_id,
            "--conversation-id", conversation_id,
            "--reminder-id", reminder_id,
            "--occurrence", occurrence,
        ])
        _wait_for_generic_shade(adb, (body, title), _ui_timeout(context))
        _mark_injected(context)
        return {
            "label": "INJECTED",
            "message_shade": True,
            "reminder_shade": True,
            "payload_body_in_shade": False,
        }
    except Exception as error:
        primary_error = error
        raise
    finally:
        try:
            _cancel_injected_notifications(adb, _notification_dump(adb))
        except Exception as error:
            cleanup_errors.append(type(error).__name__)
        if message_id:
            try:
                deleted = recorder.delete(
                    f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
                    token=binh["accessToken"],
                )
                if deleted.status not in (200, 204):
                    cleanup_errors.append(f"message HTTP {deleted.status}")
            except Exception as error:
                cleanup_errors.append(type(error).__name__)
        if reminder_id:
            try:
                deleted = recorder.delete(
                    f"/api/v1/conversations/{conversation_id}/reminders/{reminder_id}",
                    token=an["accessToken"],
                )
                if deleted.status not in (200, 204):
                    cleanup_errors.append(f"reminder HTTP {deleted.status}")
            except Exception as error:
                cleanup_errors.append(type(error).__name__)
        if cleanup_errors:
            context["cleanup_errors"] = cleanup_errors
            if primary_error is None:
                raise HarnessError("injected notification cleanup failed")


def _expect_status(response: Any, allowed: set[int], what: str) -> None:
    if response.status not in allowed:
        raise ProductFailure(f"{what} returned HTTP {response.status}")


def _read_otp(mail: Any, email: str) -> str:
    message = mail.wait_for_message(email, timeout=60)
    code = mail.extract_otp(message)
    if not isinstance(code, str) or len(code) != 6 or not code.isdigit():
        raise EnvironmentFailure("mail sink did not return a 6-digit code")
    return code


def _register_disposable(client: Any, phone: str, name: str) -> dict[str, Any]:
    response = client.post(
        "/api/v1/auth/register",
        body={"phone": phone, "password": DISPOSABLE_PASSWORD, "displayName": name},
    )
    _expect_status(response, {200, 201}, "disposable registration")
    data = response.json() or {}
    user = data.get("user") or {}
    if not data.get("accessToken") or user.get("phone") != phone:
        raise EnvironmentFailure("disposable registration did not return the requested account")
    return data


def _verify_recovery_email(client: Any, mail: Any, token: str, email: str) -> None:
    requested = client.put("/api/v1/users/me/email", token=token, body={"email": email})
    _expect_status(requested, {202}, "recovery email request")
    code = _read_otp(mail, email)
    verified = client.post("/api/v1/users/me/email/verify", token=token, body={"code": code})
    _expect_status(verified, {200}, "recovery email verify")
    mail.clear()


def _confirm_reset(client: Any, phone: str, code: str, new_password: str) -> Any:
    return client.post(
        "/api/v1/auth/password-reset/confirm",
        body={"phone": phone, "code": code, "newPassword": new_password},
    )


def _complete_password_reset(context: dict[str, Any], mail: Any, phone: str, email: str) -> None:
    """Request, read, and consume a reset code. Expiry is intentionally not claimed."""
    client = context["client"]
    registered = _register_disposable(client, phone, "Journey Disposable")
    token = registered["accessToken"]
    _verify_recovery_email(client, mail, token, email)
    requested = client.post("/api/v1/auth/password-reset/request", body={"phone": phone})
    _expect_status(requested, {202}, "password reset request")
    code = _read_otp(mail, email)
    wrong = _confirm_reset(client, phone, "111111" if code == "000000" else "000000", REPLACEMENT_PASSWORD)
    _expect_status(wrong, {400}, "wrong reset code")
    changed = _confirm_reset(client, phone, code, REPLACEMENT_PASSWORD)
    _expect_status(changed, {204}, "password reset confirm")
    reused = _confirm_reset(client, phone, code, REPLACEMENT_PASSWORD)
    _expect_status(reused, {400}, "reused reset code")
    me = client.get("/api/v1/users/me", token=token)
    _expect_status(me, {401}, "old session after password reset")
    old_login = client.post(
        "/api/v1/auth/login", body={"phone": phone, "password": DISPOSABLE_PASSWORD},
    )
    if old_login.status == 200:
        raise ProductFailure("old password was still accepted")
    if old_login.status not in {400, 401, 403}:
        raise EnvironmentFailure(f"old password login returned HTTP {old_login.status}")
    replacement = client.post(
        "/api/v1/auth/login", body={"phone": phone, "password": REPLACEMENT_PASSWORD},
    )
    _expect_status(replacement, {200}, "login with the replacement password")
    context.get("sleep", time.sleep)(61)
    mail.clear()
    resent = client.post("/api/v1/auth/password-reset/request", body={"phone": phone})
    _expect_status(resent, {202}, "password reset resend")
    resent_code = _read_otp(mail, email)
    stale = _confirm_reset(client, phone, code, REPLACEMENT_PASSWORD + "-2")
    _expect_status(stale, {400}, "previous reset code after resend")
    fresh = _confirm_reset(client, phone, resent_code, REPLACEMENT_PASSWORD + "-2")
    _expect_status(fresh, {204}, "resent reset code")


def _with_mail_sink(context: dict[str, Any], row: JourneyRow, action: Callable[[Any], None]) -> None:
    mail = context.get("mail_sink") or _import_mail_sink()
    started = False
    try:
        mail.start()
        started = True
        env = mail.server_env()
        host = str(env.get("MAIL_SMTP_HOST", ""))
        if env.get("NEXACHAT_MAIL_PROVIDER") != "smtp" or not host:
            raise HarnessError("mail sink server_env() did not point at an SMTP sink")
        if PRODUCTION_HOST.casefold() in host.casefold():
            raise HarnessError("mail sink host looks like production")
        apply_env = context.get("apply_server_env")
        if apply_env is None:
            raise CapabilityBlocked(row.capability_gap or "mail sink is not applied to the local server")
        apply_env(env)
        action(mail)
    finally:
        if started:
            mail.stop()


def run_otp_forgot_password(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """Start the mail sink and reset a disposable password when the server can be pointed at it.

    The campaign does not restart an already-running server, and OTP expiry has
    no test clock, so this executor always ends BLOCKED_CAPABILITY. It is not
    registered for a device run.
    """
    _campaign_serial(context)
    _campaign_base_url(context)
    phone = _require_disposable_phone(str(context.get("disposable_phone") or _new_disposable_phone()))
    email = str(context.get("disposable_email") or _new_disposable_email())

    def action(mail: Any) -> None:
        _complete_password_reset(context, mail, phone, email)
        raise CapabilityBlocked(row.capability_gap)

    _with_mail_sink(context, row, action)
    raise CapabilityBlocked(row.capability_gap)


def _require_deletion_last(context: dict[str, Any]) -> None:
    selected = tuple(context.get("selected_ids") or ())
    if not selected or selected[-1] != "account-deletion":
        raise HarnessError("account deletion must run last")


def _complete_account_deletion(context: dict[str, Any], mail: Any, phone: str, email: str) -> None:
    client = context["client"]
    registered = _register_disposable(client, phone, "Journey Delete")
    token = registered["accessToken"]
    _verify_recovery_email(client, mail, token, email)
    denied = client.request(
        "DELETE", "/api/v1/users/me", token=token, body={"password": "not-the-account-password"},
    )
    _expect_status(denied, {401}, "wrong password deletion")
    still_there = client.get("/api/v1/users/me", token=token)
    _expect_status(still_there, {200}, "account after wrong password")
    requested = client.post("/api/v1/account-deletion/request", body={"phone": phone})
    _expect_status(requested, {202}, "deletion code request")
    code = _read_otp(mail, email)
    confirmed = client.post(
        "/api/v1/account-deletion/confirm", body={"phone": phone, "code": code},
    )
    _expect_status(confirmed, {204}, "deletion confirm")
    rejected = client.get("/api/v1/users/me", token=token)
    _expect_status(rejected, {401}, "token after account deletion")
    reused = client.post(
        "/api/v1/auth/register",
        body={"phone": phone, "password": DISPOSABLE_PASSWORD, "displayName": "Journey Reused"},
    )
    _expect_status(reused, {200, 201}, "phone reuse after deletion")


def run_account_deletion(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """OTP-delete a disposable account only. In-app cache and peer UI stay blocked.

    Not registered for a device run: the local server is not restarted with
    server_env(), and no helper signs the disposable account into the debug UI.
    """
    _campaign_serial(context)
    _campaign_base_url(context)
    _require_deletion_last(context)
    phone = _require_disposable_phone(str(context.get("disposable_phone") or _new_disposable_phone()))
    email = str(context.get("disposable_email") or _new_disposable_email())

    def action(mail: Any) -> None:
        _complete_account_deletion(context, mail, phone, email)
        raise CapabilityBlocked(row.capability_gap)

    _with_mail_sink(context, row, action)
    raise CapabilityBlocked(row.capability_gap)


def run_auth_session_lifecycle(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """Hold account A's REST conversations response, then stop.

    STOMP cannot pass the barrier, and the registration/logout UI is not driven,
    so the row stays blocked even when a barrier is supplied. Not registered.
    """
    _campaign_serial(context)
    base_url = _campaign_base_url(context)
    factory = context.get("barrier_factory")
    if factory is None:
        raise CapabilityBlocked(row.capability_gap)
    barrier = factory(base_url)
    try:
        barrier.hold(
            method="GET", path=r"^/api/v1/conversations$",
            token_contains=context.get("account_a_token"),
        )
        raise CapabilityBlocked(row.capability_gap)
    finally:
        close = getattr(barrier, "close", None)
        if close is not None:
            close()


def run_room_13_14_upgrade(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """Refuse to install or migrate. Room 14 does not exist, so no APK is fetched."""
    _campaign_serial(context)
    _campaign_base_url(context)
    raise CapabilityBlocked(row.capability_gap)


def run_v111_rc_upgrade(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """Refuse to install. The v1.2 RC / Room 14 destination does not exist."""
    _campaign_serial(context)
    _campaign_base_url(context)
    raise CapabilityBlocked(row.capability_gap)


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
        "chat-interactions": run_chat_interactions,
        "summary-ask": run_summary_ask,
        "calls-injected-notification": run_calls_injected_notification,
        "injected-notifications": run_injected_notifications,
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
    selected = tuple(rows)
    context["selected_ids"] = tuple(row.id for row in selected)
    run_row = runner or _capability_runner
    results: list[dict[str, Any]] = []
    for row in selected:
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
                               "frame_reader": None, "stomp_events": [],
                               "serial": serial, "base_url": base_url.rstrip("/")}
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
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
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
