#!/usr/bin/env python3
"""Guarded coordinator for the phase 08 stable-journey campaign.

Runnable rows are seeded login, send/receive, chat-interactions,
summary-ask, archive/preferences, groups-contacts, labels-lifecycle,
polls-reminders, offline-sync, and the two injected-push journeys.
auth-session-lifecycle runs its REST-provable slice (plan 2.3(b)): the
executor proves the REST session ops on disposable accounts, drives the
paused-frame STOMP hold through the WS tunnel when the campaign can
establish one, and reports every scenario it could not drive as
unproven — the full hold proof against a live server is the deferred
plan 2.3(a) slice.
Every other matrix row stays BLOCKED_CAPABILITY with the gap that still
prevents that row from producing its evidence.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib
import json
import os
import re
import socket
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
                   ("POST", "/api/v1/auth/refresh"), ("POST", "/api/v1/auth/logout"),
                   ("POST", "/api/v1/auth/logout-all"), ("PATCH", "/api/v1/auth/password"),
                   ("GET", "/api/v1/users/me"), ("POST", "/api/v1/conversations/direct"),
                   ("POST", "/api/v1/conversations/{id}/messages"),
                   ("GET", "/api/v1/conversations/{id}/messages")),
        stomp_plan=("CONNECT with Authorization per account", "SUBSCRIBE /topic/conversations.{id}",
                    "hold account A inbound MESSAGE_CREATED while account B receives live"),
        capability_gap=("The executor proves the REST session ops on disposable "
                        "accounts (register/login, invalid credentials, refresh "
                        "rotation with the rotated-away token rejected, logout, "
                        "logout-all killing a concurrent session, password change "
                        "with the old password rejected) and reports the exact "
                        "undriven scenarios per run; the paused-frame STOMP hold / "
                        "cross-session socket isolation stays unproven until the "
                        "deferred full hold proof (plan 2.3(a)) drives it through "
                        "the tunnel against a live server — the row attempts the "
                        "hold whenever a campaign establishes the WS tunnel — "
                        "and in-app sign-up and the change-password dialog have "
                        "no wired UI driver."),
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
        capability_gap=None,
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
        capability_gap=("the pick completes in system picker UI (DocumentsUI/PhotoPicker) "
                        "uiautomator cannot drive reliably, so the in-app pick-to-upload "
                        "pipeline and 'no stuck upload state' stay unproven; voice "
                        "recording/playback needs hardware audio an emulator cannot prove. "
                        "The upload slice itself (device push, attach-sheet delegation, "
                        "byte-hash/range/403 oracle) is proven by media_driver + media_oracle "
                        "through the app's documented REST path"),
    ),
    JourneyRow(
        id="groups-contacts",
        title="Groups, invites and contacts",
        matrix_refs=("phase-08:Groups", "c1-5:Groups", "c1-5:Invites", "c1-5:Contacts"),
        preconditions=("An is signed in on the device",
                       "Seeded contacts Binh/Chi/Dung are on An's contact list "
                       "(mutual seed contacts)"),
        steps=("Browse the contacts list and enter group creation from the contacts surface",
               "Create a unique group through the UI picker (An + Binh), then open its chat",
               "Rename via the group-detail title dialog; add Chi and remove Chi through "
               "the member list; assert REST members after every mutation",
               "Create an approval-required invite link on the invite screen; Dung previews "
               "and requests over REST; approve the queued request on-device",
               "Open the invite deep link on the device (already-member -> open group chat), "
               "revoke the link through the UI, then remove the remaining members and "
               "leave the group as the last member"),
        assertions=("The contacts list agrees with GET /contacts and every group mutation "
                    "is mirrored by the UI member list and An's inbox entry members",
                    "Removed Chi loses the inbox entry, message access and link rejoin "
                    "(banned); Dung's request moves PENDING -> APPROVED through the UI "
                    "queue and he joins",
                    "The nexachat://join/{token} deep link resolves to the group, the "
                    "member path offers Open group chat, and after the last member "
                    "leaves the row is denied for every participant"),
        captures=("contacts.png", "group-create.png", "group-detail.png",
                  "member-added.png", "member-removed.png", "invite-link.png",
                  "invite-approve.png", "invite-return.png", "group-left.png",
                  "ui-tree.txt", "logcat.txt", "rest-transcript.json", "summary.json"),
        cleanup=("Revoke the row's invite link if one is still active",
                 "Remove remaining row-group members, then An leaves so the disposable "
                 "group is orphaned with no participants",
                 "Return the device to the inbox"),
        rest_plan=(("GET", "/api/v1/contacts"),
                   ("GET", "/api/v1/conversations"),
                   ("GET", "/api/v1/conversations/{id}/messages"),
                   ("DELETE", "/api/v1/conversations/{id}/members/{userId}"),
                   ("POST", "/api/v1/conversations/{id}/leave"),
                   ("GET", "/api/v1/conversations/{id}/invite-link"),
                   ("DELETE", "/api/v1/conversations/{id}/invite-link"),
                   ("GET", "/api/v1/conversations/{id}/join-requests"),
                   ("GET", "/api/v1/conversations/{id}/join-requests/me"),
                   ("GET", "/api/v1/invite-links/{token}"),
                   ("POST", "/api/v1/invite-links/{token}/join")),
    ),
    JourneyRow(
        id="polls-reminders",
        title="Polls and reminders",
        matrix_refs=("phase-08:Groups/group board", "c1-5:Polls", "c1-5:Reminders"),
        preconditions=("An is signed in on the device",
                       "A disposable An+Binh group is seeded over REST",
                       "The reminder_clock fixture (local Postgres, nexachat-db-1) "
                       "points at the stack's database — NEXACHAT_E2E_REMINDER_DB "
                       "when it is not the default 'nexachat' schema"),
        steps=("Open the group board from the chat overflow; create a poll on the "
               "Polls tab through the create sheet (question + two options)",
               "Vote on the device as An, change the selection, and watch Binh's "
               "REST vote land on the board within the poll refresh interval",
               "Close the poll through pollManage and verify the board's Closed "
               "state, the REST version trail and that a further peer vote is denied",
               "Create a reminder on the Reminders tab, edit its title through the "
               "card menu and verify board + REST agree each time",
               "Pull the persisted one-off due with reminder_clock.make_due, wait "
               "for the scheduler to fire it, then re-open the board to find the "
               "fired row gone from board and REST",
               "Create a Daily reminder, move its remindAt near-term through the "
               "REST PATCH path and let the production scheduler fire it: REST "
               "remindAt advances one day and the board card shows the next "
               "occurrence; delete the row's reminder through the card menu"),
        assertions=("The created poll renders on the board and the peer's REST list "
                    "shows it; option counts, voterCount and myOptionIds on the "
                    "device agree with REST for both actors after every vote "
                    "change, and the poll version increases strictly",
                    "After the UI close the board shows Closed with no vote/"
                    "retract controls, REST reports status CLOSED and a peer "
                    "PUT /vote is refused; /polls/changes returns monotonically "
                    "increasing versions ending at the closed version",
                    "The created/edited reminders persist on the board and in "
                    "GET /reminders with matching title, remindAt and repeat",
                    "After reminder_clock fires the one-off, the board and REST "
                    "list drop it; after the production scheduler fires the "
                    "DAILY row REST remindAt advances past the fired occurrence "
                    "and the board card shows the next day; the UI delete "
                    "removes it from board and REST"),
        captures=("board-polls.png", "poll-created.png", "poll-voted.png",
                  "poll-closed.png", "board-reminders.png", "reminder-created.png",
                  "reminder-edited.png", "reminder-fired.png", "reminder-daily.png",
                  "ui-tree.txt", "logcat.txt", "rest-transcript.json", "summary.json"),
        cleanup=("Delete any remaining row-created polls/reminders over REST",
                 "Remove Binh from the row's disposable group and have An leave",
                 "Return the device to the inbox"),
        rest_plan=(("POST", "/api/v1/conversations/group"),
                   ("POST", "/api/v1/conversations/{id}/polls"),
                   ("GET", "/api/v1/conversations/{id}/polls"),
                   ("GET", "/api/v1/conversations/{id}/polls/changes"),
                   ("PUT", "/api/v1/conversations/{id}/polls/{pollId}/vote"),
                   ("POST", "/api/v1/conversations/{id}/polls/{pollId}/close"),
                   ("DELETE", "/api/v1/conversations/{id}/polls/{pollId}"),
                   ("POST", "/api/v1/conversations/{id}/reminders"),
                   ("GET", "/api/v1/conversations/{id}/reminders"),
                   ("PATCH", "/api/v1/conversations/{id}/reminders/{reminderId}"),
                   ("DELETE", "/api/v1/conversations/{id}/reminders/{reminderId}"),
                   ("DELETE", "/api/v1/conversations/{id}/members/{userId}"),
                   ("POST", "/api/v1/conversations/{id}/leave")),
    ),
    JourneyRow(
        id="archive-preferences",
        title="Archive browser and conversation preferences",
        matrix_refs=("phase-08:Groups/archive", "c1-5:Archive/labels/preferences"),
        preconditions=("An is signed in on the device",
                       "The seeded non-E2EE An↔Binh DIRECT conversation exists "
                       "(archive items exclude encrypted messages)",
                       "The archive list is taller than one page: >30 seeded link items"),
        steps=("Seed a tagged image, file, 40+ links and one peer-authored link via REST",
               "Browse media/files/links tabs, page past the 30-item limit, apply and clear "
               "the sender filter on the shared-media screen",
               "Mute/unmute and label-assign through the chat overflow; archive from the "
               "overflow and undo the snackbar; pin/unpin and archive/unarchive through the "
               "inbox long-press sheet; browse the Archived inbox chip and pull-to-refresh",
               "Toggle showSeen/showPresence on the profile screen and read the peer-visible "
               "effect over REST and the peer's STOMP topic"),
        assertions=("Every tab/filter/page renders the tagged fixture and agrees with "
                    "GET /conversations/{id}/archive items and nextCursor",
                    "Each flag mutation lands in the caller's inbox entry and never in the "
                    "peer's (muted/mutedUntil/pinned/archived/labels)",
                    "The archived chip lists the conversation after archive and the row "
                    "disappears after unarchive; flags survive pull-to-refresh",
                    "The peer's inbox entry and /api/v1/labels never carry the private label",
                    "With showSeen off no MESSAGE_READ(an) reaches Binh's STOMP topic; with "
                    "showPresence off Binh's entry drops peerPresence"),
        captures=("archive-media.png", "archive-files.png", "archive-links-*.png",
                  "archive-filtered.png", "archived-inbox.png", "profile-privacy.png",
                  "ui-tree.txt", "logcat.txt", "rest-transcript.json",
                  "stomp-transcript.json", "summary.json"),
        cleanup=("Restore An's showSeen/showPresence settings and Binh's showSeen",
                 "Reset mute/pin/archive flags on the seeded conversation to their baseline",
                 "Unassign and delete only the row-created label; soft-delete only the "
                 "row-created messages"),
        rest_plan=(("POST", "/api/v1/attachments"),
                   ("POST", "/api/v1/conversations/{id}/messages"),
                   ("GET", "/api/v1/conversations/{id}/archive"),
                   ("GET", "/api/v1/conversations?archived={bool}"),
                   ("PUT", "/api/v1/conversations/{id}/mute"),
                   ("DELETE", "/api/v1/conversations/{id}/mute"),
                   ("PUT", "/api/v1/conversations/{id}/archive"),
                   ("DELETE", "/api/v1/conversations/{id}/archive"),
                   ("PUT", "/api/v1/conversations/{id}/pin"),
                   ("DELETE", "/api/v1/conversations/{id}/pin"),
                   ("POST", "/api/v1/labels"),
                   ("PUT", "/api/v1/conversations/{id}/labels/{labelId}"),
                   ("DELETE", "/api/v1/labels/{labelId}"),
                   ("PATCH", "/api/v1/users/me/settings"),
                   ("POST", "/api/v1/conversations/{id}/read")),
        stomp_plan=("CONNECT as Binh", "SUBSCRIBE /topic/conversations.{conversationId}",
                    "Assert MESSAGE_READ(userId=An) only while An's showSeen is enabled"),
    ),
    JourneyRow(
        id="labels-lifecycle",
        title="Labels and dialog recreation/stop-start",
        matrix_refs=("phase-08:Labels", "c1-5:Archive/labels/preferences"),
        preconditions=("An is signed in on the device",
                       "The seeded An↔Binh DIRECT conversation plus at least one other "
                       "conversation are in the inbox (the label filter must hide the rest)",
                       "The integrated build contains the label-observer and "
                       "dialog-reopen-refetch fixes"),
        steps=("Open the inbox long-press 'Assign labels' dialog, switch to manage mode "
               "and create a label through the editor (name + color + Save)",
               "Back to assignment, check the new label; verify the REST entry, the "
               "?labelId= filter and the peer's privacy boundary",
               "Select the inbox label filter chip; verify only the labeled row renders "
               "and REST ?labelId= agrees; reset with 'All labels'",
               "Reopen the dialog, rotate to landscape and back, then HOME + relaunch "
               "(stop/start): assert exactly one title/one checkbox row per label and "
               "that toggles land exactly one REST write; scan logcat for leaked windows",
               "Force-stop + relaunch: dialog is gone, assignment persists, and the "
               "reopened dialog refetches a REST-created label",
               "Delete the REST-created label through manage mode, unassign and delete "
               "the UI-created label, and watch the filter chip row disappear"),
        assertions=("The UI-created label lands in GET /api/v1/labels and its checkbox "
                    "assignment lands on An's inbox entry while Binh's entry and label "
                    "list stay clean",
                    "The inbox filter chip renders only the labeled conversation and "
                    "GET /conversations?labelId= returns exactly that id",
                    "After rotation and after HOME + relaunch the dialog exposes exactly "
                    "one title and one checkbox row per label — no duplicate surface — "
                    "and each toggle still lands exactly one REST write; logcat reports "
                    "no leaked window (the LiveData observer count itself is JVM-pinned "
                    "by LabelDialogFragmentObserverTest; uiautomator cannot read it)",
                    "Reopening the dialog after an out-of-band REST create refetches "
                    "the new label; deleting through manage mode removes it in REST "
                    "and hides the filter chip row when none remain"),
        captures=("label-created.png", "label-assigned.png", "label-filtered.png",
                  "label-dialog-landscape.png", "label-reopen-refetch.png",
                  "label-deleted.png", "labels-cleared.png",
                  "ui-tree.txt", "logcat.txt", "rest-transcript.json", "summary.json"),
        cleanup=("Unassign and delete only the row-created labels over REST",
                 "Restore accelerometer/user rotation to the pre-run values",
                 "Return the device to the inbox"),
        rest_plan=(("GET", "/api/v1/labels"), ("POST", "/api/v1/labels"),
                   ("PUT", "/api/v1/conversations/{id}/labels/{labelId}"),
                   ("DELETE", "/api/v1/conversations/{id}/labels/{labelId}"),
                   ("GET", "/api/v1/conversations?labelId={labelId}"),
                   ("DELETE", "/api/v1/labels/{labelId}")),
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
        preconditions=("An is signed in on the device",
                       "The seeded An↔Binh DIRECT conversation exists",
                       "The local stack accepts >100 REST-created conversations"),
        steps=("Open An↔Binh; interleave-seed 30 peer-edit + 30 recall sources "
               "plus a reaction target over REST",
               "Cut wifi/data; queue two texts and a 👍 through the real UI; "
               "force-stop and relaunch while still offline",
               "Restore the network; the outbox flushes exactly once per "
               "clientMessageId and the reaction lands",
               "Seed 105 disposable An+Binh groups plus one An/Chi-only group; "
               "walk the paged inbox to the oldest seeded row",
               "Force-stop; land 30 edits + 30 recalls plus a marker message in "
               "a conversation that sorts into the second 100-watermark batch; "
               "relaunch and watch the marker converge",
               "Sign out to the login form, sign in as Binh (A→B), then sign "
               "out and back in as An (ABA)"),
        assertions=("Queued texts and the offline reaction land exactly once "
                    "(REST history + server DB row count)",
                    "The inbox pager appends >100 conversations and logcat "
                    "shows the post-restart sync fanning into more than one "
                    "bounded POST /api/v1/sync batch",
                    "The tail edit, its Edited marker, a recall tombstone and "
                    "the second-batch marker all render on the device",
                    "Binh's session never shows the An-only fixture; the ABA "
                    "re-login reconverges An's inbox and a fresh peer message"),
        captures=("offline-queue.png", "offline-after-restart.png",
                  "reconnect-sync.png", "inbox-bottom.png", "chunk2-marker.png",
                  "post-restart-tail.png", "switch-b-inbox.png",
                  "switch-aba-inbox.png", "ui-tree.txt", "logcat.txt",
                  "rest-transcript.json", "summary.json"),
        cleanup=("Restore wifi/data/font/locale to the pre-row snapshot",
                 "Soft-delete only this run's unique messages as their authors",
                 "Remove the second member and leave all 106 seeded groups "
                 "(orphaned)",
                 "Leave the device signed back in as An on the inbox"),
        rest_plan=(("POST", "/api/v1/conversations/group"),
                   ("GET", "/api/v1/conversations"),
                   ("POST", "/api/v1/conversations/direct"),
                   ("GET", "/api/v1/conversations/{id}/messages"),
                   ("POST", "/api/v1/conversations/{id}/messages"),
                   ("PATCH", "/api/v1/conversations/{id}/messages/{messageId}"),
                   ("DELETE", "/api/v1/conversations/{id}/messages/{messageId}"),
                   ("DELETE", "/api/v1/conversations/{id}/members/{userId}"),
                   ("POST", "/api/v1/conversations/{id}/leave"),
                   ("POST", "/api/v1/sync")),
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
        steps=("For each locale/night/font tuple, apply settings, visit critical screens and capture the UI",
               "Run the a11y_oracle checks (labels, touch targets, image descs, focus order) on every critical screen"),
        assertions=("No clipped text or inaccessible critical action", "TalkBack and reduced-motion checks are recorded"),
        captures=("one screenshot per parameter tuple", "ui-tree.txt per tuple", "summary.json"),
        cleanup=("Restore original locale, night mode, font scale and network settings in finally"),
        rest_plan=(("GET", "/actuator/health/readiness"),),
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
        capability_gap=("the mail/REST deletion flow runs against a disposable stack (--dstack), "
                        "and the in-app UI slice (sign-in, wrong-password refusal, OTP confirm, "
                        "deleted-account cache, phone reuse on device) runs when the stack owns "
                        "the app's compile-time port; otherwise the row reports this gap. The "
                        "peer's UI render of the tombstoned author stays unproven on a single "
                        "device. The row stays last and must never target a seed user."),
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
        {"helper": "response_barrier.Barrier.hold", "method": "MESSAGE",
         "path": "^/topic/conversations.{id}$", "token_contains": "account A STOMP CONNECT token"},
        {"helper": "response_barrier.Barrier.wait_held", "n": 1, "transport": "STOMP over WebSocket tunnel"},
        {"helper": "response_barrier.Barrier.release", "when": "after account A's socket closes"},
        {"helper": "realtime_probe.FrameReader", "actors": "A held + B live",
         "subscribe": "/topic/conversations.{id}"},
        {"helper": "e2e_common.Client", "rest": "register/login/refresh/logout/logout-all/password/direct/messages"},
        {"helper": "patch_window", "ui": "invalid credentials, saved session, sign-out, sign-out-all, A to B switch"},
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
        {"helper": "adb_ui.Device.tap_node", "target": "resource-id deleteAccountRow",
         "opens": "resource-id deleteAccountToolbar"},
        {"helper": "adb_ui.Device.type_text", "target": "resource-id passwordInput",
         "assert": "resource-id inlineError renders the wrong-password refusal"},
        {"helper": "adb_ui.Device.tap_node", "target": "resource-id sendDeletionCode",
         "assert": "resource-id deletionCodeSentMessage renders"},
        {"helper": "adb_ui.Device.type_text", "target": "resource-id deletionCodeInput"},
        {"helper": "adb_ui.Device.tap_node", "target": "resource-id confirmDelete",
         "assert": "the login form returns (signed out)"},
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
    "groups-contacts": (
        {"helper": "adb_ui.Device.tap_node", "target": "resource-id inboxContactsButton",
         "assert": "contactList renders Binh/Chi/Dung and agrees with GET /contacts"},
        {"helper": "adb_ui.Device.tap_node + type_text",
         "target": "createGroupEntry -> groupNameInput, memberPickerList rows, createButton",
         "assert": "unique group opens its chat; REST inbox entry lists An ADMIN + Binh"},
        {"helper": "adb_ui.Device.tap_node",
         "target": "chatToolbar -> groupTitle rename dialog -> Save",
         "assert": "groupTitle and the REST entry title both carry the new name"},
        {"helper": "adb_ui.Device.tap_node",
         "target": "addMemberButton -> addMemberList Chi -> addMemberConfirm; "
                   "member row content-desc 'Remove Chi from the group' -> Remove",
         "assert": "memberList and REST members gain then drop Chi; Chi inbox/messages denied"},
        {"helper": "adb_ui.Device.tap_node",
         "target": "inviteLinkButton -> approvalSwitch -> createLinkButton",
         "assert": "shareUrl shows nexachat://join/<token>; REST link requiresApproval"},
        {"helper": "e2e_common.Client.get+post", "actor": "Dung",
         "path": "/api/v1/invite-links/{token} + /join",
         "assert": "preview names the group, request goes PENDING"},
        {"helper": "e2e_common.Client.post", "actor": "Chi",
         "path": "/api/v1/invite-links/{token}/join",
         "assert": "removed member rejoin is denied (ban)"},
        {"helper": "adb_ui.Device.tap_node",
         "target": "refreshRequestsButton -> approveButton on the Dung row",
         "assert": "request leaves the queue; REST shows APPROVED and Dung is a member"},
        {"helper": "adb shell am start -a VIEW -d nexachat://join/{token}",
         "assert": "statusText already-member -> openChatButton returns to the group chat"},
        {"helper": "adb_ui.Device.tap_node",
         "target": "revokeButton -> Revoke link; leaveGroupButton -> Leave group",
         "assert": "REST link 404; empty group drops every inbox entry and denies reads"},
    ),
    "polls-reminders": (
        {"helper": "e2e_common.Client.post",
         "path": "POST /api/v1/conversations/group", "actor": "An",
         "seed": "disposable group with Binh as the only member"},
        {"helper": "adb_ui.Device.tap_node",
         "target": "chat overflow moreButton -> 'Group board' -> boardTabs",
         "assert": "boardTabs renders Reminders + Polls"},
        {"helper": "adb_ui.Device.tap_node + type_text",
         "target": "pollAdd -> pollQuestionInput + optionInput x2 + pollCreateSubmit",
         "assert": "poll card lands on pollList; peer REST list agrees"},
        {"helper": "adb_ui.Device.tap_node",
         "target": "optionControl <label> -> pollVote (and again to change)",
         "assert": "optionCount/pollVoterCount agree with REST version + "
                  "myOptionIds for both actors; Binh's REST vote renders on-board"},
        {"helper": "adb_ui.Device.tap_node",
         "target": "pollManage -> 'Close poll' -> confirm",
         "assert": "pollMeta Closed, no vote/retract controls; REST status "
                  "CLOSED and the peer vote is refused"},
        {"helper": "adb_ui.Device.tap_node + type_text",
         "target": "reminderAdd -> reminderEditorTitleInput + reminderEditorSave; "
                   "reminderMenu -> Edit -> title -> Save",
         "assert": "reminder card + REST agree on title/remindAt/repeat"},
        {"helper": "adb_ui.Device.tap_node",
         "target": "reminderEditorRepeat -> 'Daily' dialog row",
         "assert": "REST repeat=DAILY; after fire remindAt advances one day"},
        {"helper": "reminder_clock.ReminderClock.make_due + wait_fired",
         "assert": "scheduler claims+fires the persisted occurrence; board and "
                  "REST drop the one-off / advance the daily next occurrence"},
        {"helper": "adb_ui.Device.tap_node",
         "target": "reminderMenu -> Delete -> confirm",
         "assert": "card + REST row are gone"},
    ),
    "offline-sync": (
        {"helper": "patch_window.settings_snapshot + cut_network",
         "assert": "two UI sends + one 👍 queue while the radios are off"},
        {"helper": "adb", "args": ["shell", "am", "force-stop", PACKAGE],
         "then": ["shell", "am", "start", "-n", f"{PACKAGE}/{MAIN_ACTIVITY}"],
         "assert": "queued texts + the optimistic chip survive process death"},
        {"helper": "patch_window.restore_test_network + wait_server_rows",
         "assert": "exactly one server row per flushed clientMessageId; "
                  "the 👍 reaches REST on its target"},
        {"helper": "e2e_common.Client.post",
         "path": "POST /api/v1/conversations/group x105 + one An/Chi group",
         "assert": "the paged inbox walk reaches sj-os-<tag>-001 (Room >100)"},
        {"helper": "e2e_common.Client.patch+delete+post", "actor": "Binh+An",
         "assert": "30 edits + 30 recalls + a second-chunk marker land while "
                  "the process is dead"},
        {"helper": "adb", "args": ["logcat", "-d", "-v", "brief"],
         "assert": ">1 POST /api/v1/sync batch; a ~100-watermark body"},
        {"helper": "adb_ui.Device.tap_node",
         "target": "inboxUserAvatar -> logoutRow -> Sign out -> "
                   "phoneInput/passwordInput/primaryAction",
         "assert": "A→B hides the An-only fixture; ABA reconverges An's inbox"},
    ),
    "archive-preferences": (
        {"helper": "e2e_common.Client.request",
         "path": "POST /api/v1/attachments + /messages", "actor": "Binh+An",
         "seed": "tagged image, file and 41 link items"},
        {"helper": "adb_ui.Device.tap_node", "target": "resource-id moreButton -> Shared media",
         "assert": "archiveTabs media/files/links render the tagged fixture"},
        {"helper": "adb_ui.Device.swipe", "target": "resource-id archiveList",
         "assert": "page-2-only link URL renders past the 30-item page"},
        {"helper": "adb_ui.Device.tap_node",
         "target": "archiveSenderFilter -> single-choice Binh -> archiveClearFilters",
         "assert": "An-authored link is hidden under the Binh filter and returns after Clear"},
        {"helper": "adb_ui.Device.tap_node",
         "target": "chat overflow Mute notifications -> Until turned back on -> Unmute",
         "assert": "An entry muted+mutedUntil flips; Binh entry unchanged"},
        {"helper": "adb_ui.Device.long_press + tap_node",
         "target": "inbox row Binh -> Pin/Unpin/Archive/Unarchive sheet items",
         "assert": "caller flags flip on REST; archived chip lists and drops the row"},
        {"helper": "adb_ui.Device.tap_node", "target": "snackbar Undo",
         "assert": "the overflow archive is reverted caller-side"},
        {"helper": "adb_ui.Device.tap_node",
         "target": "chat overflow Assign labels -> sj-lab CheckBox -> Done",
         "assert": "An's entry.labels gains the id; Binh's never does"},
        {"helper": "adb_ui.Device.tap_node",
         "target": "profile showSeen/showPresence switch rows",
         "assert": "MESSAGE_READ(an) reaches Binh's topic only while showSeen; "
                  "Binh's peerPresence appears only while showPresence"},
        {"helper": "realtime_probe.FrameReader", "actor": "Binh",
         "assert": "MESSAGE_READ frame gated by the device toggle"},
    ),
    "labels-lifecycle": (
        {"helper": "adb_ui.Device.long_press + tap_node",
         "target": "inbox row Binh -> 'Assign labels' -> 'Conversation labels' dialog",
         "assert": "assign-mode title + empty state render once"},
        {"helper": "adb_ui.Device.tap_node + type_text",
         "target": "'Manage labels' -> 'Create label' -> name field -> color chip -> 'Save'",
         "assert": "manage row 'Label: sj-lab-*' renders; GET /api/v1/labels agrees"},
        {"helper": "adb_ui.Device.tap_node",
         "target": "'Back to conversation' -> label CheckBox",
         "assert": "An entry.labels gains the id; ?labelId= returns the conversation; peer clean"},
        {"helper": "adb_ui.Device.swipe + tap_node",
         "target": "inboxFilterScroll carousel -> 'Filter inbox by sj-lab-*' chip",
         "assert": "only the labeled row renders; 'All labels' restores the full list"},
        {"helper": "adb_ui.Device.rotate + adb shell am start",
         "target": "landscape/portrait rotation, KEYCODE_HOME -> am start, am force-stop",
         "assert": "exactly one title + one CheckBox per label; toggles land one write; "
                  "no leaked-window logcat; process death drops the dialog"},
        {"helper": "adb_ui.Device.tap_node",
         "target": "reopened dialog -> 'Manage labels' -> 'Delete label sj-lab-b' -> 'Delete'",
         "assert": "REST-created label renders on reopen (refetch) then deletes; "
                  "the last delete hides the filter chip row"},
    ),
    "injected-notifications": (
        {"helper": "e2e_common.send_text", "actor": "Binh", "assert": "real message id and seq"},
        {"helper": "inject_push.main", "kind": "message", "serial": SERIAL},
        {"helper": "inject_push.main", "kind": "reminder", "serial": SERIAL},
        {"helper": "adb", "args": ["shell", "dumpsys", "notification", "--noredact"]},
    ),
    "media": (
        {"helper": "media_driver.push_fixture",
         "target": "adb push deterministic png/pdf fixtures to /sdcard/Download",
         "assert": "on-device sha256sum equals the fixture digest (pull fallback)"},
        {"helper": "adb_ui.Device.tap_node",
         "target": "resource-id attachButton -> pickVisualRow/pickDocumentRow",
         "assert": "the app's attach sheet opens; dumpsys shows the system "
                   "PhotoPicker/DocumentsUI resumed; the pick itself stays unproven"},
        {"helper": "media_driver.verify_upload_slice",
         "target": "POST /api/v1/attachments multipart + messages{attachmentIds}",
         "actor": "An (owner), Binh (member), Chi (non-member)",
         "assert": "owner+member receive SHA-256-equal bytes; Range is 206 with "
                   "exact slices; non-member GET is 403 NOT_MEMBER"},
        {"helper": "none (BLOCKED_CAPABILITY)",
         "blocked": "voice recording/playback needs hardware audio an emulator "
                    "cannot provide; pick completion is system picker UI"},
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
    "accessibility-sweep": (
        {"helper": "stable_journeys.set_accessibility_variant",
         "assert": "locale/night/font_scale applied per ACCESSIBILITY_VARIANTS tuple"},
        {"helper": "stable_journeys.snapshot_settings + restore_settings",
         "assert": "display settings restored in finally"},
        {"helper": "a11y_oracle.summarize_findings",
         "assert": "labels/touch-target/image-desc/focus-order checks per critical screen"},
        {"helper": "a11y_oracle.get_enabled_accessibility_services + restore_talkback",
         "assert": "TalkBack state snapshotted, recorded and restored"},
    ),
}

UI_MARKERS = {
    "auth-login": ("resource-id:phoneInput", "resource-id:passwordInput",
                   "resource-id:primaryAction", "text:An", "text:Binh"),
    "chat-send-receive": ("text:Binh", "resource-id:messageInput", "resource-id:sendButton",
                           "text:<unique outbound message>", "text:<unique inbound message>"),
    "auth-session-lifecycle": ("text:localized login/error state",
                               "text:Journey UI A", "text:Journey UI B",
                               "text:Sign out all devices"),
    "otp-forgot-password": ("text:localized OTP verification state", "text:password changed"),
    "chat-interactions": (
        "resource-id:messageActionsTitle", "resource-id:actionLabel",
        "resource-id:replyPreview", "resource-id:replySnippet",
        "resource-id:editedLabel", "text:This message was deleted",
        "resource-id:forwardDialogRoot", "resource-id:forwardSend",
        "content-desc:Read",
    ),
    "media": ("resource-id:attachButton", "resource-id:pickVisualRow",
              "resource-id:pickDocumentRow", "text:unique media fixture name",
              "text:voice duration"),
    "groups-contacts": (
        "resource-id:inboxContactsButton", "resource-id:contactList",
        "resource-id:createGroupEntry", "resource-id:groupNameInput",
        "resource-id:memberPickerList", "resource-id:createButton",
        "resource-id:chatToolbar", "resource-id:groupTitle",
        "resource-id:memberList", "resource-id:addMemberButton",
        "resource-id:addMemberList", "resource-id:addMemberConfirm",
        "content-desc:Remove <member> from this group?",
        "resource-id:inviteLinkButton", "resource-id:approvalSwitch",
        "resource-id:createLinkButton", "resource-id:shareUrl",
        "resource-id:requestList", "resource-id:approveButton",
        "resource-id:statusText", "resource-id:openChatButton",
        "resource-id:revokeButton", "resource-id:leaveGroupButton",
        "text:You are already a member.", "text:Create group",
    ),
    "polls-reminders": (
        "resource-id:boardTabs", "resource-id:boardContent",
        "resource-id:pollList", "resource-id:pollAdd",
        "resource-id:pollQuestionInput", "resource-id:optionInput",
        "resource-id:pollCreateSubmit", "resource-id:pollQuestion",
        "resource-id:pollMeta", "resource-id:pollOptions",
        "resource-id:optionControl", "resource-id:optionCount",
        "resource-id:pollVoterCount", "resource-id:pollVote",
        "resource-id:pollRetract", "resource-id:pollManage",
        "resource-id:reminderList", "resource-id:reminderAdd",
        "resource-id:reminderEditorTitleInput", "resource-id:reminderEditorSave",
        "resource-id:reminderEditorRepeat", "resource-id:reminderTitle",
        "resource-id:reminderTime", "resource-id:reminderMenu",
        "text:Close poll", "text:Daily", "text:Edit", "text:Delete",
        "text:<sj-poll question>", "text:<sj-rem reminder title>",
    ),
    "archive-preferences": (
        "resource-id:archiveTabs", "resource-id:archiveList",
        "resource-id:archiveMediaTitle", "resource-id:archiveRowTitle",
        "resource-id:archiveSenderFilter", "resource-id:archiveClearFilters",
        "text:Photos & videos", "text:Files", "text:Links",
        "text:Shared media", "text:Mute notifications",
        "text:Until turned back on", "text:Unmute notifications",
        "text:Pin conversation", "text:Unpin conversation",
        "text:Archive", "text:Unarchive", "text:Undo",
        "text:Conversation archived", "resource-id:filterArchivedChip",
        "resource-id:filterAllChip", "text:Assign labels",
        "text:Conversation labels", "text:<sj-lab label checkbox>",
        "resource-id:showSeen", "resource-id:showPresence",
    ),
    "labels-lifecycle": (
        "resource-id:conversationList", "text:Assign labels",
        "text:Conversation labels", "text:Manage labels",
        "text:Create label", "text:New label",
        "desc:Label name (1–30 characters)", "text:Save",
        "text:Back to conversation", "text:Done",
        "desc:Assign label <sj-lab checkbox>", "text:All labels",
        "desc:Filter inbox by <sj-lab>", "desc:Manage conversation labels",
        "desc:Label: <sj-lab>", "desc:Delete label <sj-lab>",
        "text:Delete label?", "text:Delete",
    ),
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
    "offline-sync": (
        "resource-id:conversationList", "text:Binh",
        "resource-id:messageInput", "resource-id:sendButton",
        "resource-id:messageBody", "desc:Like", "desc:👍 reaction chip",
        "resource-id:messageActionsTitle", "text:<sj-ot queued texts>",
        "text:sj-os-<tag>-001", "text:<sj-ose edited tail>",
        "text:Edited", "text:This message was deleted",
        "resource-id:inboxUserAvatar", "resource-id:profileName",
        "resource-id:logoutRow", "text:Sign out",
        "resource-id:phoneInput", "resource-id:passwordInput",
        "resource-id:primaryAction", "text:An",
    ),
    "injected-notifications": (
        "text:You have a new message", "text:Tin nhắn mới",
        "text:A reminder is due", "text:Nhắc nhở", "label:INJECTED",
    ),
    "room-13-14-upgrade": ("text:queued message before and after migration"),
    "v111-rc-upgrade": ("text:Sign in", "text:Binh"),
    "accessibility-sweep": (
        "resource-id:conversationList", "resource-id:messageInput",
        "resource-id:archiveTabs", "resource-id:profileName",
        "resource-id:select_dialog_listview", "resource-id:contactList",
    ),
    "account-deletion": (
        "resource-id:deleteAccountRow", "resource-id:deleteAccountToolbar",
        "resource-id:passwordInput", "resource-id:confirmDelete",
        "resource-id:inlineError", "text:Current password is incorrect",
        "resource-id:sendDeletionCode", "resource-id:deletionCodeSentMessage",
        "resource-id:deletionCodeInput", "resource-id:deleteProgress",
        "text:Delete my account", "text:Sign in",
    ),
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

        # The device send flushes through the outbox worker, which can sit
        # behind a large inbox resync after a cold start. Poll the peer's REST
        # history for a bounded window instead of a single immediate read, then
        # keep the exact-one assertion on the converged page.
        matching: list[dict[str, Any]] = []
        last_status: int | None = None
        deadline = time.monotonic() + 45
        while True:
            page_response = recorder.get(
                f"/api/v1/conversations/{conversation_id}/messages",
                token=binh["accessToken"], params={"limit": 50},
            )
            last_status = page_response.status
            if page_response.status == 200:
                page = page_response.json() or {}
                items = page.get("items") or []
                matching = [item for item in items if item.get("content") == outbound]
                if len(matching) == 1:
                    break
                if len(matching) > 1:
                    raise ProductFailure("peer REST history contained duplicate copies of the device message")
            if time.monotonic() >= deadline:
                if last_status != 200:
                    raise ProductFailure(f"peer REST history returned HTTP {last_status}")
                raise ProductFailure("peer REST history did not contain exactly one device message")
            time.sleep(2)
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


_ANR_TITLE_MARKERS = ("isn't responding", "Not Responding", "không phản hồi")
_ANR_WAIT_LABELS = ("Wait", "Đợi")


def _dismiss_occluder(context: dict[str, Any], nodes: list[Any]) -> bool:
    """Dismiss any modal surface that hides the app's real tree.

    Two occluders are handled here: system ANR dialogs and the app's own
    StreakMilestoneSheet, which pops over the inbox when a streak increments
    mid-journey and leaves every wait polling a 'Keep it going' sheet instead
    of the conversation list (run-50 attempt-2 froze the walk for ~40min)."""
    if _dismiss_anr_dialog(context, nodes):
        return True
    done = next((node for node in nodes
                 if _rid_ends(node, "streakMilestoneDone") and node.clickable),
                None)
    if done is not None:
        context["device"].tap_node(done)
        return True
    # A pulled-down notification shade / quick-settings panel replaces the
    # app's whole a11y tree with com.android.systemui nodes, so every app
    # marker reads absent until the shade is collapsed (attempt: the seeded
    # sweep group 'never reached the inbox' while the panel was up).
    if nodes and all(
            "systemui" in (node.attrs.get("package") or "")
            for node in nodes):
        context["device"].key("KEYCODE_BACK")
        return True
    return False


def _dismiss_anr_dialog(context: dict[str, Any], nodes: list[Any]) -> bool:
    """Tap 'Wait' on a system 'not responding' dialog occluding the app.

    The debug build and SystemUI both ANR under the seeded burst load; while
    the dialog is up every node probe sees only its tiny tree and the wait
    budget burns out on an occluded surface (run-14: 'System UI isn't
    responding' ate the whole device-account restore window)."""
    if not any(any(marker in (node.text or "") or marker in (node.desc or "")
                   for marker in _ANR_TITLE_MARKERS)
               for node in nodes):
        return False
    wait = next((node for node in nodes
                 if node.text.strip() in _ANR_WAIT_LABELS
                 and node.clickable and node.box), None)
    if wait is None:
        return False
    context["device"].tap_node(wait)
    return True


def _deadline_dump(context: dict[str, Any], label: str) -> None:
    """Best-effort forensic capture when a bounded wait expires.

    A ProductFailure that carries only its message leaves the verdict
    "rendered late vs never applied" undecidable — the offline-sync
    investigation (run-51) burned six consecutive attempts on INDETERMINATE
    outcomes because nothing showed the screen at the deadline. Dropping the
    a11y tree, a screenshot, and an unfiltered logcat slice next to the row's
    other evidence turns the next timeout into a diagnosis instead of a
    rerun. Every capture is individually guarded: a dump error must never
    mask the real failure.
    """
    row_dir = context.get("row_dir")
    if row_dir is None:
        return
    try:
        row_dir = Path(row_dir)
        row_dir.mkdir(parents=True, exist_ok=True)
        safe = re.sub(r"[^A-Za-z0-9_.-]+", "-", str(label)).strip("-")[:48] \
            or "wait"
        stem = f"deadline-{safe}-{int(time.time())}"
        try:
            nodes = _ui_nodes(context)
            (row_dir / f"{stem}-ui-tree.txt").write_text(
                sanitize_text("\n".join(node.describe() for node in nodes)) +
                "\n", encoding="utf-8")
        except Exception:
            pass
        try:
            context["device"].screenshot(str(row_dir / f"{stem}.png"))
        except Exception:
            pass
        try:
            adb = context.get("adb")
            if adb is not None:
                text = adb.command("logcat", "-d", "-v", "brief", "-t", "400",
                                   timeout=15)
                (row_dir / f"{stem}-logcat.txt").write_text(
                    sanitize_text(str(text)) + "\n", encoding="utf-8")
        except Exception:
            pass
    except Exception:
        pass


def _wait_ui_node(context: dict[str, Any], predicate: Callable[[Any], bool],
                  what: str, timeout: float | None = None) -> Any:
    deadline = time.monotonic() + float(timeout or _ui_timeout(context))
    while True:
        nodes = _ui_nodes(context)
        if _dismiss_occluder(context, nodes):
            time.sleep(0.8)
            continue
        match = next((node for node in nodes if predicate(node)), None)
        if match is not None:
            return match
        if time.monotonic() >= deadline:
            _deadline_dump(context, what)
            raise ProductFailure(what)
        time.sleep(0.4)


def _wait_ui_absent(context: dict[str, Any], predicate: Callable[[Any], bool],
                  what: str, timeout: float | None = None) -> None:
    deadline = time.monotonic() + float(timeout or _ui_timeout(context))
    while True:
        nodes = _ui_nodes(context)
        if _dismiss_occluder(context, nodes):
            time.sleep(0.8)
            continue
        if not any(predicate(node) for node in nodes):
            return
        if time.monotonic() >= deadline:
            _deadline_dump(context, what)
            raise ProductFailure(what)
        time.sleep(0.4)


def _wait_sheet_closed(context: dict[str, Any], timeout: float = 12) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not any(_rid_ends(node, "messageActionsTitle") for node in _ui_nodes(context)):
            return
        time.sleep(0.4)
    _deadline_dump(context, "the message action sheet did not dismiss")
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
            _deadline_dump(context, f"message bubble {content!r} was not on screen")
            raise ProductFailure(f"message bubble {content!r} was not on screen")
        ui.long_press(bubble)
        sheet_deadline = time.monotonic() + 6
        while time.monotonic() < sheet_deadline:
            if any(_rid_ends(node, "messageActionsTitle") for node in _ui_nodes(context)):
                return
            time.sleep(0.4)
        if time.monotonic() >= deadline:
            _deadline_dump(context, "the message action sheet did not open after long-press")
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
    _deadline_dump(context, "action not offered by the message action sheet")
    raise ProductFailure(f"action {labels!r} was not offered by the message action sheet")


def _forward_sheet_scroll(context: dict[str, Any]) -> None:
    """Scroll the forward picker's conversation list down by one viewport."""
    patch = context["helpers"][2]
    nodes = _ui_nodes(context)
    list_node = patch.node_by_id(nodes, "forwardList")
    if list_node is not None and getattr(list_node, "box", None):
        left, top, right, bottom = list_node.box
        cx = (left + right) // 2
        context["device"].swipe(cx, bottom - 24, cx, top + 32, 300)
        return
    root = nodes[0] if nodes else None
    box = getattr(root, "box", None) or (0, 0, 320, 640)
    width, height = box[2], box[3]
    context["device"].swipe(width // 2, int(height * 0.78), width // 2, int(height * 0.55), 300)


def _wait_forward_target(context: dict[str, Any], desc: str) -> Any:
    """Wait for a forward-target row, scrolling the picker list until it appears."""
    for _ in range(8):
        target = next(
            (node for node in _ui_nodes(context)
             if (node.desc or "").strip() == desc),
            None,
        )
        if target is not None:
            return target
        _forward_sheet_scroll(context)
        time.sleep(0.4)
    _deadline_dump(context, "the seeded group was not offered as a forward target")
    raise ProductFailure("the seeded group was not offered as a forward target")


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
    api = context["helpers"][0]
    patch = context["helpers"][2]
    recorder: RecordingClient = context["client"]
    ui = context["device"]
    an, binh, conversation_id, _recipient_id, binh_user_id = _seed_pair(context)
    an_user_id = str((an.get("user") or {}).get("id") or "")

    # Resolve the seeded forward target by title — the UUID is never embedded
    # and the lookup stays unpaged: stale run leftovers push the seed group
    # past any paged read. A consumed/renamed/archived target is re-seeded
    # REST-only here (board evidence: absent twice after earlier journeys
    # consumed it) instead of env-failing the row mid-journey.
    group_id, group_heals = _ensure_forward_target_group(api, recorder, an, binh)
    if group_heals:
        context.setdefault("seed_heals", []).extend(group_heals)

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
        target = _wait_forward_target(context, _CHAT_FORWARD_TARGET_TITLE)
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
    """Wait for the summary sheet's READY body; report the last visible error.

    Bullet rows render inside `summarySheetScroll`; uiautomator prunes the
    rows sitting below the sheet's inner viewport, so a valid summary with
    its bullets below the fold reports "no content" forever. The sheet is
    paged down each round like `_summary_citation_chip` already does.
    """
    ui = context["device"]
    deadline = time.monotonic() + _ASK_RESULT_TIMEOUT
    last_error = ""
    seen_header = None
    while True:
        nodes = _ui_nodes(context)
        bullets = [node for node in nodes
                   if _rid_ends(node, "summaryBulletText") and _node_visible(node)]
        header = next((node for node in nodes
                       if _rid_ends(node, "summaryResultHeader")
                       and node.text.strip() and _node_visible(node)), None)
        # Scrolling to expose bullets expands the sheet and slides the header
        # above the viewport — header and bullets are never co-visible on a
        # filled sheet. Latch the header at first sight (the collapsed sheet
        # always opens with it at the top) and return it once bullets appear.
        if header is not None:
            seen_header = header
        if bullets and seen_header is not None:
            return seen_header
        error = next((node for node in nodes
                      if _rid_ends(node, "summaryErrorText")
                      and node.text.strip() and _node_visible(node)), None)
        last_error = error.text.strip() if error is not None else last_error
        if time.monotonic() >= deadline:
            detail = f" (error card: {last_error!r})" if last_error else ""
            raise ProductFailure(f"the LOCAL summary did not render its content{detail}")
        scroll = next((node for node in nodes
                       if _rid_ends(node, "summarySheetScroll")), None)
        if scroll is not None:
            box = getattr(scroll, "box", None) or (0, 0, 320, 640)
            ui.swipe((box[0] + box[2]) // 2, box[3] - 30,
                     (box[0] + box[2]) // 2, box[1] + (box[3] - box[1]) // 3,
                     350)
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


# --- archive-preferences ------------------------------------------------------
# Shared-media browser (media/files/links tabs, sender filter, keyset paging),
# per-member pin/mute/archive flags driven through the real chat overflow and
# the inbox long-press sheet, the archive undo snackbar, a caller-private label
# round-trip, and the showSeen/showPresence profile switches. Every flag is
# asserted on the device UI AND on the caller's and the peer's REST inbox —
# caller-only state must never leak into the peer's view.
_ARCHIVE_MENU_ITEM = ("Shared media", "Nội dung đã chia sẻ")
_ARCHIVE_TAB_MEDIA = ("Photos & videos", "Ảnh/Video")
_ARCHIVE_TAB_FILES = ("Files", "Tệp")
_ARCHIVE_TAB_LINKS = ("Links", "Liên kết")
_ARCHIVE_ALL_SENDERS = ("All senders", "Tất cả người gửi")
_ARCHIVE_CLEAR_FILTERS = ("Clear", "Xóa lọc")
_PREF_MENU_MUTE = ("Mute notifications", "Tắt thông báo")
_PREF_MENU_UNMUTE = ("Unmute notifications", "Bật thông báo")
_PREF_MUTE_FOREVER = ("Until turned back on", "Cho đến khi bật lại")
_PREF_MENU_ARCHIVE = ("Archive", "Lưu trữ")
_PREF_MENU_UNARCHIVE = ("Unarchive", "Bỏ lưu trữ")
_PREF_MENU_PIN = ("Pin conversation", "Ghim trò chuyện")
_PREF_MENU_UNPIN = ("Unpin conversation", "Bỏ ghim trò chuyện")
_PREF_MENU_LABELS = ("Assign labels", "Gán nhãn")
_PREF_LABELS_TITLE = ("Conversation labels", "Nhãn trò chuyện")
_PREF_LABELS_DONE = ("Done", "Hoàn tất")
_PREF_ARCHIVED_SNACKBAR = ("Conversation archived", "Đã lưu trữ cuộc trò chuyện")
_PREF_UNDO = ("Undo", "Hoàn tác")
_ARCHIVE_LINK_MESSAGES = 4
_ARCHIVE_LINKS_PER_MESSAGE = 10  # server MAX_LINKS cap; 4*10+1 = 41 > PAGE_SIZE(30)
_PNG_1PX = bytes.fromhex(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
    "0000000d49444154789c626001000000ffff03000006000557bfabd4"
    "0000000049454e44ae426082")


def _multipart_upload(recorder: RecordingClient, token: str, filename: str,
                      content: bytes, content_type: str) -> dict[str, Any]:
    """POST /api/v1/attachments with a hand-built multipart body."""
    boundary = "----sj" + uuid.uuid4().hex[:16]
    body = (
        f"--{boundary}\r\n"
        f"Content-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode("utf-8") + content + f"\r\n--{boundary}--\r\n".encode("utf-8")
    response = recorder.request(
        "POST", "/api/v1/attachments", token=token,
        raw_body=(body, f"multipart/form-data; boundary={boundary}"))
    if response.status != 201:
        raise EnvironmentFailure(f"attachment upload returned HTTP {response.status}")
    return dict(response.json() or {})


def _send_attachment_message(recorder: RecordingClient, token: str,
                             conversation_id: str, msg_type: str,
                             attachment_id: str) -> dict[str, Any]:
    response = recorder.post(
        f"/api/v1/conversations/{conversation_id}/messages",
        token=token,
        body={"clientMessageId": str(uuid.uuid4()), "type": msg_type,
              "content": None, "attachmentIds": [attachment_id]},
    )
    if response.status not in (200, 201):
        raise EnvironmentFailure(f"{msg_type} seed message returned HTTP {response.status}")
    return dict(response.json() or {})


def _archive_page(recorder: RecordingClient, token: str, conversation_id: str,
                  archive_type: str, *, limit: int = 30,
                  cursor: str | None = None, sender_id: str | None = None) -> dict[str, Any]:
    params: dict[str, Any] = {"type": archive_type, "limit": limit}
    if cursor:
        params["cursor"] = cursor
    if sender_id:
        params["senderId"] = sender_id
    response = recorder.get(
        f"/api/v1/conversations/{conversation_id}/archive",
        token=token, params=params)
    if response.status != 200:
        raise ProductFailure(f"archive {archive_type} returned HTTP {response.status}")
    return dict(response.json() or {})


def _inbox_entry(recorder: RecordingClient, token: str,
                 conversation_id: str, *, archived: bool) -> dict[str, Any] | None:
    """Fresh REST read of one caller's inbox entry for the conversation."""
    response = recorder.get(
        "/api/v1/conversations", token=token,
        params={"archived": "true" if archived else "false"})
    if response.status != 200:
        raise ProductFailure(f"inbox returned HTTP {response.status}")
    body = response.json() or []
    items = body.get("items") if isinstance(body, dict) else body
    for item in items or []:
        if str(item.get("id")) == conversation_id:
            return item
    return None


def _wait_inbox_entry(recorder: RecordingClient, token: str, conversation_id: str,
                      predicate: Callable[[dict[str, Any] | None], bool],
                      what: str, *, archived: bool = False,
                      timeout: float = 15.0) -> dict[str, Any] | None:
    deadline = time.monotonic() + timeout
    while True:
        entry = _inbox_entry(recorder, token, conversation_id, archived=archived)
        if predicate(entry):
            return entry
        if time.monotonic() >= deadline:
            raise ProductFailure(what)
        time.sleep(0.6)


def _peer_flag_clean(recorder: RecordingClient, token: str, conversation_id: str,
                     field: str, what: str) -> dict[str, Any] | None:
    """Assert the peer's member-scoped flag stayed unset (no propagation wait —
    per-member flags are independent rows written only for the caller)."""
    entry = _inbox_entry(recorder, token, conversation_id, archived=False)
    value = (entry or {}).get(field)
    if value not in (None, False, [], {}):
        raise ProductFailure(f"{what}: peer inbox entry leaked {field}={value!r}")
    return entry


def _entry_labels(entry: dict[str, Any] | None) -> set[str]:
    return {str(item).casefold() for item in (entry or {}).get("labels") or []}


def _patch_settings(recorder: RecordingClient, token: str,
                    body: dict[str, Any]) -> dict[str, Any]:
    response = recorder.patch("/api/v1/users/me/settings", token=token, body=body)
    if response.status != 200:
        raise ProductFailure(f"settings PATCH returned HTTP {response.status}")
    return dict(response.json() or {})


def _tap_dialog_item(context: dict[str, Any], labels: tuple[str, ...],
                     what: str | None = None,
                     timeout: float | None = None) -> Any:
    """Tap an AlertDialog row, scrolling the dialog's list when the row is off
    screen. Scoped to android.R.id.text1 so a background node with the same
    localized text (e.g. the VI 'Lưu trữ' chip) can't win."""
    ui = context["device"]
    deadline = time.monotonic() + float(timeout or _ui_timeout(context))
    while True:
        nodes = _ui_nodes(context)
        match = next(
            (node for node in nodes
             if node.text.strip() in labels and (
                 _rid_ends(node, "text1") or "CheckedTextView" in node.cls)),
            None)
        if match is not None:
            ui.tap_node(match)
            return match
        if time.monotonic() >= deadline:
            raise ProductFailure(what or f"the dialog did not offer {labels!r}")
        # The chat overflow lists >10 actions: page its ListView down.
        listview = next((node for node in nodes
                         if _rid_ends(node, "select_dialog_listview")), None)
        if listview is not None and getattr(listview, "box", None):
            left, top, right, bottom = listview.box
            cx = (left + right) // 2
            ui.swipe(cx, bottom - int((bottom - top) * 0.15),
                     cx, top + int((bottom - top) * 0.35), 250)
        time.sleep(0.4)


def _open_chat_menu(context: dict[str, Any], item_labels: tuple[str, ...]) -> None:
    """Open the chat overflow and pick an item by its dialog row text."""
    ui = context["device"]
    more = _wait_ui_node(
        context,
        lambda node: _rid_ends(node, "moreButton"),
        "the chat overflow button was not exposed",
    )
    ui.tap_node(more)
    _tap_dialog_item(context, item_labels)


def _inbox_conversation_row(context: dict[str, Any], title: str | tuple,
                            nodes: list | None = None) -> Any | None:
    """Title/avatar node of a conversation row INSIDE the conversationList.

    The 'Active now' presence strip also renders a 'Binh' label when the peer
    is online, so a bare text match long-presses the strip instead of the row.
    Constrain the match to nodes inside the conversationList bounds.

    `title` may be a tuple of accepted aliases: run-31 attempt-1 rendered the
    seeded DM's row title as the just-delivered last-message content
    ("sj-osp-<tag>") instead of the peer name — the row was present, bumped to
    the top with its unread badge, and self-healed on the next inbox fetch.
    Matching the expected message text still identifies the same row
    unambiguously (only that conversation can display it).

    `nodes` lets a wait loop reuse the dump it already fetched — three
    uiautomator dumps per iteration made the page-down sweep too slow to
    reach deep rows inside one timeout window (run-36: ~106 stale rows).
    """
    titles = (title,) if isinstance(title, str) else tuple(title)
    if nodes is None:
        nodes = _ui_nodes(context)
    container = next((node for node in nodes
                      if _rid_ends(node, "conversationList")), None)
    if container is None or not container.box:
        # An empty variant drops the RecyclerView from the a11y dump entirely
        # (verified: only 'No archived chats' remains) — no row can render,
        # so reporting "absent" is truthful. An unbounded fallback would
        # false-positive on the presence-strip card for the same peer.
        return None
    top, bottom = container.box[1], container.box[3]
    for node in nodes:
        if node.text.strip() not in titles and node.desc.strip() not in titles:
            continue
        if node.box and top <= node.box[1] and node.box[3] <= bottom:
            return node
    return None


def _inbox_conversation_row_visible(context: dict[str, Any], title: str | tuple,
                                    nodes: list | None = None) -> Any | None:
    """Like `_inbox_conversation_row` but also accepts a row clipped by the
    viewport when at least half of it is still on screen.

    On the 320x640 emulator the inbox header (active-now strip, wrapped
    filter chips, label chips, Manage labels) shrinks the conversationList to
    a sliver, so a restored top row renders partially below the fold — it is
    present in the ui tree yet fails the fully-inside-bounds check
    (final-journeys2 archive-preferences: Undo restored the row but the wait
    reported it missing). A node whose visible height covers its center is
    readable and tappable, so it counts as present; the match still requires
    the node to exist in the dump inside the conversationList bounds.
    """
    titles = (title,) if isinstance(title, str) else tuple(title)
    if nodes is None:
        nodes = _ui_nodes(context)
    container = next((node for node in nodes
                      if _rid_ends(node, "conversationList")), None)
    if container is None or not container.box:
        return None
    top, bottom = container.box[1], container.box[3]
    for node in nodes:
        if node.text.strip() not in titles and node.desc.strip() not in titles:
            continue
        if not node.box:
            continue
        height = node.box[3] - node.box[1]
        if height <= 0:
            continue
        visible = min(node.box[3], bottom) - max(node.box[1], top)
        if visible * 2 >= height:
            return node
    return None


def _open_inbox_actions(context: dict[str, Any]) -> None:
    """Long-press the seeded peer row until the actions dialog opens."""
    ui = context["device"]
    deadline = time.monotonic() + _ui_timeout(context)
    while True:
        # Sweep below the rendered viewport — a low-sorted peer row is
        # otherwise invisible to the a11y dump (labels-lifecycle attempt-1).
        if not _inbox_row_found_scrolled(context, "Binh"):
            if time.monotonic() >= deadline:
                raise ProductFailure("the seeded peer row was not on the inbox")
            continue
        row = _inbox_conversation_row_visible(context, "Binh")
        if row is None:
            if time.monotonic() >= deadline:
                raise ProductFailure("the seeded peer row was not on the inbox")
            time.sleep(0.4)
            continue
        ui.long_press(row)
        dialog_deadline = time.monotonic() + 6
        while time.monotonic() < dialog_deadline:
            if any(_rid_ends(node, "text1") or "CheckedTextView" in node.cls
                   for node in _ui_nodes(context)):
                return
            time.sleep(0.4)
        if time.monotonic() >= deadline:
            raise ProductFailure("the inbox long-press actions dialog did not open")


def _wait_binh_row(context: dict[str, Any], present: bool, what: str,
                   timeout: float | None = None) -> None:
    if present:
        deadline = time.monotonic() + float(timeout or _ui_timeout(context))
        while True:
            # A restored row can sort below the rendered viewport — sweep
            # the list before declaring the wait failed (run-archprefs-10).
            if _inbox_row_found_scrolled(context, "Binh"):
                return
            if time.monotonic() >= deadline:
                raise ProductFailure(what)
    else:
        deadline = time.monotonic() + float(timeout or _ui_timeout(context))
        while True:
            # A partially clipped row still proves the row is in the tree, so
            # the absence wait must use the same visibility test as presence.
            if _inbox_conversation_row_visible(context, "Binh") is None:
                return
            if time.monotonic() >= deadline:
                raise ProductFailure(what)
            time.sleep(0.4)


def _scroll_list_until(context: dict[str, Any], container_rid: str,
                       predicate: Callable[[Any], bool], what: str,
                       swipes: int = 14) -> Any:
    """Swipe up inside a list container until predicate matches a node."""
    ui = context["device"]
    for _ in range(swipes):
        nodes = _ui_nodes(context)
        match = next((node for node in nodes if predicate(node)), None)
        if match is not None:
            return match
        container = next((node for node in nodes if _rid_ends(node, container_rid)), None)
        box = getattr(container, "box", None) or (0, 0, 320, 640)
        cx = (box[0] + box[2]) // 2
        height = box[3] - box[1]
        ui.swipe(cx, box[3] - int(height * 0.12), cx, box[1] + int(height * 0.30), 300)
        time.sleep(0.7)
    raise ProductFailure(what)


def _scroll_list_to_top(context: dict[str, Any], container_rid: str,
                        swipes: int = 10) -> None:
    """Swipe down inside a list container to return to the first page."""
    ui = context["device"]
    for _ in range(swipes):
        nodes = _ui_nodes(context)
        container = next((node for node in nodes if _rid_ends(node, container_rid)), None)
        box = getattr(container, "box", None) or (0, 0, 320, 640)
        cx = (box[0] + box[2]) // 2
        height = box[3] - box[1]
        # Mirror of the upward sweep: a down-fling must out-travel the
        # collapsing app bar's expansion before it scrolls the list back to
        # the top, so it continues past the container edge.
        screen_bottom = max((node.box[3] for node in nodes if node.box), default=640)
        ui.swipe(cx, box[1] + int(height * 0.06), cx,
                 min(screen_bottom, box[3] + int(height * 2)), 300)
        time.sleep(0.5)


def _inbox_row_found_scrolled(context: dict[str, Any], title: str | tuple,
                              swipes: int = 5) -> bool:
    """Sweep the conversationList downward looking for a row title.

    `_inbox_conversation_row` only sees rendered nodes — a restored or
    low-sorted row below the ~4-row viewport reports absent forever
    (run-archprefs-verify-10: the un-archived Binh row sorted last and the
    row check timed out below the fold). Each round dumps once per
    viewport and pages down; when the sweep is exhausted the list is
    scrolled back to the top so the next wait round starts stable.
    """
    ui = context["device"]
    for _ in range(swipes):
        nodes = _ui_nodes(context)
        if _inbox_conversation_row_visible(context, title, nodes) is not None:
            return True
        container = next((node for node in nodes
                          if _rid_ends(node, "conversationList")), None)
        if container is None:
            break
        box = getattr(container, "box", None) or (0, 0, 320, 640)
        cx = (box[0] + box[2]) // 2
        height = box[3] - box[1]
        # The collapsing inbox header consumes the start of an up-fling until
        # it collapses; a gesture shorter than the bar's snap edge leaves the
        # bar expanded and the list unmoved. When the chips wrap, the bar can
        # be taller than the whole list viewport, so the fling must continue
        # well above the container — the gesture still scrolls the view under
        # its start point. Keep the start above the gesture-nav home strip:
        # starting the fling at the bottom edge backgrounds the app.
        screen_bottom = max((node.box[3] for node in nodes if node.box), default=640)
        ui.swipe(cx, min(box[3] - int(height * 0.12), screen_bottom - 48), cx,
                 max(1, box[1] - int(height * 2)), 300)
        time.sleep(0.5)
    _scroll_list_to_top(context, "conversationList")
    return False


def _pull_to_refresh(context: dict[str, Any]) -> None:
    nodes = _ui_nodes(context)
    container = next((node for node in nodes if _rid_ends(node, "conversationList")), None)
    box = getattr(container, "box", None) or (0, 0, 320, 640)
    cx = (box[0] + box[2]) // 2
    height = box[3] - box[1]
    context["device"].swipe(cx, box[1] + int(height * 0.15),
                            cx, box[1] + int(height * 0.55), 350)


def _switch_checked(context: dict[str, Any], switch_rid: str,
                    expected: bool, what: str, timeout: float = 10.0) -> None:
    wanted = "true" if expected else "false"
    _wait_ui_node(
        context,
        lambda node: _rid_ends(node, switch_rid)
        and node.attrs.get("checked") == wanted,
        what, timeout=timeout)


def _scroll_profile_until(context: dict[str, Any], *rids: str) -> None:
    """Scroll the profile column until every named switch is on screen."""
    ui = context["device"]
    for _ in range(10):
        nodes = _ui_nodes(context)
        if all(any(_rid_ends(node, rid) for node in nodes) for rid in rids):
            return
        column = next((node for node in nodes if _rid_ends(node, "profileColumn")), None)
        box = getattr(column, "box", None) or (0, 0, 320, 640)
        cx = (box[0] + box[2]) // 2
        height = box[3] - box[1]
        ui.swipe(cx, box[3] - int(height * 0.15), cx, box[1] + int(height * 0.30), 300)
        time.sleep(0.6)
    raise ProductFailure(f"profile switches {rids!r} were not reachable")


def _stomp_events(context: dict[str, Any], reader: Any, seconds: float) -> list[dict[str, Any]]:
    """Collect envelopes for a bounded window and record them for evidence."""
    seen: list[dict[str, Any]] = []
    for envelope in reader.events(seconds):
        context["stomp_events"].append(envelope)
        seen.append(envelope)
    return seen


def _message_read_for(events: list[dict[str, Any]], user_id: str) -> bool:
    return any(
        envelope.get("eventType") == "MESSAGE_READ"
        and str((envelope.get("payload") or {}).get("userId") or "") == user_id
        for envelope in events)


def run_archive_preferences(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """Shared-media browser + member-scoped prefs on the seeded An↔Binh direct.

    The device runs as seeded An. Deterministic fixtures (a tagged image, file,
    41 link items across five messages, a private label) are seeded over REST
    so the archive browser genuinely pages and filters. Every UI-driven flag
    (mute, pin, archive, labels, showSeen, showPresence) is verified on the
    caller's REST inbox AND against the peer's — caller-only state must never
    leak. showSeen is proven through Binh's STOMP topic (MESSAGE_READ frames),
    showPresence through the peerPresence field of Binh's inbox entry.
    """
    _campaign_serial(context)
    _campaign_base_url(context)
    _prepare_seeded_an(context)
    api, _adb_ui, patch = context["helpers"]
    recorder: RecordingClient = context["client"]
    ui = context["device"]
    an, binh, conversation_id, an_user_id, _binh_user_id = _seed_pair(context)
    _, conversation = api.direct_conversation(recorder, an["accessToken"], api.USER_C["phone"])
    if conversation.get("e2eeEnabled"):
        raise EnvironmentFailure(
            "the seeded An↔Binh conversation is E2EE; archive items are unavailable by design")
    row_dir = Path(context["row_dir"])
    tag = uuid.uuid4().hex[:8]
    png_name = f"sj-arch-{tag}.png"
    txt_name = f"sj-arch-{tag}.txt"
    label_name = f"sj-lab-{tag}"
    an_link_url = f"https://e2e.test/sj-{tag}-an-00"
    page2_marker = f"sj-{tag}-b0-"  # oldest seeded link message: lands past page 1

    from realtime_probe import FrameReader

    ws_url = recorder.base_url.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
    factory = context.get("frame_reader_factory") or FrameReader
    reader = factory(ws_url, binh["accessToken"])
    context["frame_reader"] = reader
    reader.subscribe(f"/topic/conversations.{conversation_id}")

    an_settings0 = _user_settings(recorder, an["accessToken"])
    binh_settings0 = _user_settings(recorder, binh["accessToken"])
    entry0 = _inbox_entry(recorder, an["accessToken"], conversation_id, archived=False)
    if entry0 is None:
        raise EnvironmentFailure("the seeded conversation is missing from An's inbox")
    baseline_flags = {field: entry0.get(field)
                      for field in ("muted", "mutedUntil", "pinned", "archived")}

    seeded: list[tuple[str, str]] = []  # (message id, author access token)
    label_id = ""
    last_seq = 0
    result: dict[str, Any] = {}
    primary_error: Exception | None = None
    try:
        # Fixture: the peer must publish read receipts for the gating check.
        if binh_settings0.get("showSeen") is not True:
            _patch_settings(recorder, binh["accessToken"], {"showSeen": True})
        if an_settings0.get("showSeen") is not True:
            _patch_settings(recorder, an["accessToken"], {"showSeen": True})
        if an_settings0.get("showPresence") is not True:
            _patch_settings(recorder, an["accessToken"], {"showPresence": True})

        # --- REST fixtures -------------------------------------------------
        png = _multipart_upload(recorder, binh["accessToken"], png_name, _PNG_1PX, "image/png")
        image_msg = _send_attachment_message(
            recorder, binh["accessToken"], conversation_id, "IMAGE", str(png.get("id")))
        seeded.append((str(image_msg.get("id")), binh["accessToken"]))
        txt = _multipart_upload(
            recorder, binh["accessToken"], txt_name,
            f"sj archive file fixture {tag}\n".encode(), "text/plain")
        file_msg = _send_attachment_message(
            recorder, binh["accessToken"], conversation_id, "FILE", str(txt.get("id")))
        seeded.append((str(file_msg.get("id")), binh["accessToken"]))
        for index in range(_ARCHIVE_LINK_MESSAGES):
            links = " ".join(
                f"https://e2e.test/sj-{tag}-b{index}-{link}"
                for link in range(_ARCHIVE_LINKS_PER_MESSAGE))
            sent = api.send_text(recorder, binh["accessToken"], conversation_id, links)
            seeded.append((str(sent.get("id")), binh["accessToken"]))
            last_seq = max(last_seq, int(sent.get("seq") or 0))
        own_link = api.send_text(recorder, an["accessToken"], conversation_id, an_link_url)
        seeded.append((str(own_link.get("id")), an["accessToken"]))
        last_seq = max(last_seq, int(own_link.get("seq") or 0))
        if not all(message_id for message_id, _ in seeded):
            raise EnvironmentFailure("a seeded fixture message did not return an id")
        created = recorder.post(
            "/api/v1/labels", token=an["accessToken"],
            body={"name": label_name, "colorId": "BLUE"})
        if created.status not in (200, 201):
            raise EnvironmentFailure(f"label create returned HTTP {created.status}")
        label_id = str((created.json() or {}).get("id") or "")
        if not label_id:
            raise EnvironmentFailure("label create did not return an id")

        # The activity-scoped LabelViewModel.load() only fetches once per
        # process lifetime, so the REST-created label is invisible to the
        # running app until a cold start. Force-stop + relaunch loads the
        # label and the seeded messages into fresh ViewModels.
        context["adb"].command("shell", "am", "force-stop", PACKAGE, timeout=20)
        context["adb"].command("shell", "am", "start", "-n",
                               f"{PACKAGE}/{MAIN_ACTIVITY}", timeout=30)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "inboxUserAvatar"),
            "the inbox did not reload after the fixture relaunch",
            timeout=30)

        # --- open the seeded direct ----------------------------------------
        row_node = _inbox_conversation_row(context, "Binh")
        if row_node is None:
            raise EnvironmentFailure("seed An↔Binh conversation is not visible in the device inbox")
        ui.tap_node(row_node)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "messageInput"),
            "the seeded direct conversation did not open its composer",
        )

        # === phase 1: shared-media browser =================================
        _open_chat_menu(context, _ARCHIVE_MENU_ITEM)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "archiveTabs"),
            "the shared-media screen did not open",
        )

        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "archiveMediaTitle") and node.text.strip() == png_name,
            "the media tab did not render the seeded image",
        )
        ui.screenshot(str(row_dir / "archive-media.png"))
        media_rest = _archive_page(recorder, an["accessToken"], conversation_id, "MEDIA", limit=5)
        if not any(str((item.get("attachment") or {}).get("fileName")) == png_name
                   for item in media_rest.get("items") or []):
            raise ProductFailure("REST MEDIA archive did not list the seeded image")

        _tap_text(context, _ARCHIVE_TAB_FILES)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "archiveRowTitle") and node.text.strip() == txt_name,
            "the files tab did not render the seeded file",
        )
        ui.screenshot(str(row_dir / "archive-files.png"))
        file_rest = _archive_page(recorder, an["accessToken"], conversation_id, "FILE", limit=5)
        if not any(str((item.get("attachment") or {}).get("fileName")) == txt_name
                   for item in file_rest.get("items") or []):
            raise ProductFailure("REST FILE archive did not list the seeded file")

        _tap_text(context, _ARCHIVE_TAB_LINKS)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "archiveRowTitle") and f"sj-{tag}" in node.text,
            "the links tab did not render any seeded link",
        )
        if any(_rid_ends(node, "archiveRowTitle") and page2_marker in node.text
               for node in _ui_nodes(context)):
            raise ProductFailure("a page-2-only link rendered on the first page")
        ui.screenshot(str(row_dir / "archive-links-page1.png"))

        # REST keyset paging across the seeded 41-item link set.
        page1 = _archive_page(recorder, an["accessToken"], conversation_id, "LINK", limit=30)
        items1 = list(page1.get("items") or [])
        cursor = page1.get("nextCursor")
        if len(items1) != 30 or not cursor:
            raise ProductFailure(
                f"archive LINK page 1 did not fill the page or lost its cursor "
                f"({len(items1)} items, cursor={cursor!r})")
        page2 = _archive_page(
            recorder, an["accessToken"], conversation_id, "LINK", limit=30, cursor=cursor)
        items2 = list(page2.get("items") or [])
        tagged = {
            str((item.get("link") or {}).get("url"))
            for item in items1 + items2
            if f"sj-{tag}" in str((item.get("link") or {}).get("url"))
        }
        if len(tagged) != 41:
            raise ProductFailure(
                f"REST link paging covered {len(tagged)}/41 seeded links across two pages")
        if not any(page2_marker in url for url in tagged if url):
            raise ProductFailure("the oldest seeded link message was missing from page 2")

        # UI paging: scroll the real RecyclerView until a page-2 URL renders.
        _scroll_list_until(
            context, "archiveList",
            lambda node: _rid_ends(node, "archiveRowTitle") and page2_marker in node.text,
            "the links list never paged to a page-2-only URL",
        )
        ui.screenshot(str(row_dir / "archive-links-paged.png"))

        # Sender filter: dialog -> Binh hides An's own seeded link. The chip is
        # disabled until the loaded pages surface senders, so retry the tap.
        sender_deadline = time.monotonic() + _ui_timeout(context)
        while True:
            nodes = _ui_nodes(context)
            dialog_open = any(
                _rid_ends(node, "text1") or "CheckedTextView" in node.cls
                for node in nodes)
            if not dialog_open:
                chip = next((node for node in nodes
                             if _rid_ends(node, "archiveSenderFilter")), None)
                if chip is None:
                    raise ProductFailure("the archive sender filter chip was not rendered")
                ui.tap_node(chip)
            try:
                _tap_dialog_item(
                    context, ("Binh",),
                    "the sender dialog did not list Binh", timeout=4)
                break
            except ProductFailure:
                if time.monotonic() >= sender_deadline:
                    raise ProductFailure("the archive sender filter never opened its dialog")
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "archiveSenderFilter")
            and node.text.strip() in (f"Sender: Binh", "Người gửi: Binh"),
            "the sender filter chip did not switch to Binh",
        )
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "archiveSenderFilter")
            and node.text.strip() in (f"Sender: Binh", f"Người gửi: Binh"),
            "the sender filter chip did not switch to Binh",
        )
        _wait_ui_absent(
            context,
            lambda node: _rid_ends(node, "archiveRowTitle") and "-an-" in node.text,
            "An-authored link stayed visible under the Binh sender filter",
        )
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "archiveRowTitle") and f"sj-{tag}-b" in node.text,
            "Binh's links disappeared under the Binh sender filter",
        )
        ui.screenshot(str(row_dir / "archive-filtered.png"))
        filtered = _archive_page(
            recorder, an["accessToken"], conversation_id, "LINK",
            limit=50, sender_id=_binh_user_id)
        filtered_items = filtered.get("items") or []
        if not filtered_items or any(
                str(item.get("senderId")) != _binh_user_id for item in filtered_items):
            raise ProductFailure("REST senderId filter did not return only Binh's items")
        clear = _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "archiveClearFilters")
            and node.text.strip() in _ARCHIVE_CLEAR_FILTERS,
            "the Clear filter control was not visible",
        )
        ui.tap_node(clear)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "archiveSenderFilter")
            and node.text.strip() in _ARCHIVE_ALL_SENDERS,
            "the sender filter chip did not reset to All senders",
        )
        # An's link is the newest item: it sits on the first page, so reset
        # the scroll position before asserting it returned.
        _scroll_list_to_top(context, "archiveList")
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "archiveRowTitle") and "-an-" in node.text,
            "An-authored link did not return after clearing the sender filter",
        )
        ui.screenshot(str(row_dir / "archive-unfiltered.png"))
        ui.key("KEYCODE_BACK")
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "messageInput"),
            "the shared-media back navigation did not return to the chat",
        )

        # === phase 2: chat overflow mute -> presets -> unmute ===============
        _open_chat_menu(context, _PREF_MENU_MUTE)
        _tap_dialog_item(
            context, _PREF_MUTE_FOREVER,
            "the mute preset dialog did not offer 'Until turned back on'")
        _wait_inbox_entry(
            recorder, an["accessToken"], conversation_id,
            lambda entry: entry is not None and entry.get("muted") is True
            and entry.get("mutedUntil") is None,
            "the forever mute never persisted on An's inbox entry")
        _peer_flag_clean(recorder, binh["accessToken"], conversation_id, "muted",
                         "An's mute leaked into Binh's inbox entry")
        result["mute"] = "muted forever caller-side; peer unaffected"

        _open_chat_menu(context, _PREF_MENU_UNMUTE)
        _wait_inbox_entry(
            recorder, an["accessToken"], conversation_id,
            lambda entry: entry is not None and entry.get("muted") is False,
            "the unmute never persisted on An's inbox entry")
        result["unmute"] = "muted flag cleared caller-side"

        # === phase 3: chat overflow labels (caller-private) =================
        _open_chat_menu(context, _PREF_MENU_LABELS)
        _wait_ui_node(
            context,
            lambda node: node.text.strip() in _PREF_LABELS_TITLE,
            "the conversation-labels dialog did not open",
        )
        checkbox = _wait_ui_node(
            context,
            lambda node: node.text.strip() == label_name
            and ("CheckBox" in node.cls or node.clickable),
            "the private label checkbox did not render",
        )
        ui.tap_node(checkbox)
        _wait_inbox_entry(
            recorder, an["accessToken"], conversation_id,
            lambda entry: entry is not None and label_id.casefold() in _entry_labels(entry),
            "the label assignment never persisted on An's inbox entry")
        peer_entry = _inbox_entry(recorder, binh["accessToken"], conversation_id, archived=False)
        if label_id.casefold() in _entry_labels(peer_entry):
            raise ProductFailure("the private label leaked into Binh's inbox entry")
        peer_labels = recorder.get("/api/v1/labels", token=binh["accessToken"])
        if peer_labels.status != 200:
            raise ProductFailure(f"peer /api/v1/labels returned HTTP {peer_labels.status}")
        if any(str(item.get("id")).casefold() == label_id.casefold()
               for item in peer_labels.json() or []):
            raise ProductFailure("the private label leaked into Binh's label list")
        ui.screenshot(str(row_dir / "label-assigned.png"))
        _tap_text(context, _PREF_LABELS_DONE)
        _wait_ui_absent(
            context,
            lambda node: node.text.strip() in _PREF_LABELS_TITLE,
            "the conversation-labels dialog did not dismiss",
        )
        result["labels"] = "assigned caller-side; peer entry and peer /labels stay clean"

        # === phase 4: overflow archive -> snackbar undo =====================
        # The snackbar renders only after the archive write commits (the chat
        # fragment pops when it observes the archived flag), so verify REST
        # first — the GET converges before the snackbar's ~3s TTL starts. The
        # Undo action then has to be captured and tapped inside one dump
        # window: any delay for further round-trips lets it auto-dismiss.
        _open_chat_menu(context, _PREF_MENU_ARCHIVE)
        _wait_inbox_entry(
            recorder, an["accessToken"], conversation_id,
            lambda entry: entry is not None and entry.get("archived") is True,
            "the overflow archive never persisted on An's archived inbox entry",
            archived=True)
        _wait_inbox_entry(
            recorder, an["accessToken"], conversation_id,
            lambda entry: entry is None,
            "the conversation stayed in An's default inbox after archive")
        deadline = time.monotonic() + _ui_timeout(context)
        undo = None
        snackbar_seen = False
        while time.monotonic() < deadline:
            nodes = _ui_nodes(context)
            if any(node.text.strip() in _PREF_ARCHIVED_SNACKBAR for node in nodes):
                # Message and action live in the same snackbar hierarchy, so
                # they appear atomically in one dump.
                snackbar_seen = True
                undo = next((node for node in nodes
                             if node.text.strip() in _PREF_UNDO), None)
                break
            time.sleep(0.25)
        if not snackbar_seen:
            raise ProductFailure(
                "the archive snackbar did not render after the overflow archive")
        if undo is None:
            raise ProductFailure("the archive snackbar did not offer Undo")
        # Tap before the screenshot: the snackbar's ~3s TTL can expire inside
        # one dump+capture window on a slow bridge, and a stale Undo tap then
        # lands on whatever surfaced underneath (run-sweep-verify-12).
        ui.tap_node(undo)
        ui.screenshot(str(row_dir / "archive-snackbar.png"))
        _wait_inbox_entry(
            recorder, an["accessToken"], conversation_id,
            lambda entry: entry is not None and entry.get("archived") is False,
            "the undo never restored the conversation to An's inbox")
        _peer_flag_clean(recorder, binh["accessToken"], conversation_id, "archived",
                         "An's archive leaked into Binh's inbox entry")
        _wait_binh_row(
            context, True,
            "the conversation row did not return to the inbox after Undo",
            timeout=60.0)
        result["archive_undo"] = ("overflow archive persisted caller-side; the "
                                  "snackbar's Undo action reverted it")

        # === phase 5: inbox sheet pin, archive, unarchive ===================
        _open_inbox_actions(context)
        _tap_dialog_item(context, _PREF_MENU_PIN, "the inbox sheet did not offer Pin conversation")
        _wait_inbox_entry(
            recorder, an["accessToken"], conversation_id,
            lambda entry: entry is not None and entry.get("pinned") is True
            and entry.get("pinnedAt") is not None,
            "the pin never persisted on An's inbox entry")
        _peer_flag_clean(recorder, binh["accessToken"], conversation_id, "pinned",
                         "An's pin leaked into Binh's inbox entry")
        _open_inbox_actions(context)
        _tap_dialog_item(
            context, _PREF_MENU_UNPIN,
            "the inbox sheet did not offer Unpin conversation after pin")
        _wait_inbox_entry(
            recorder, an["accessToken"], conversation_id,
            lambda entry: entry is not None and entry.get("pinned") is False,
            "the unpin never persisted on An's inbox entry")
        result["pin"] = "pin/unpin round-tripped caller-side; peer unaffected"

        _open_inbox_actions(context)
        _tap_dialog_item(context, _PREF_MENU_ARCHIVE, "the inbox sheet did not offer Archive")
        _wait_inbox_entry(
            recorder, an["accessToken"], conversation_id,
            lambda entry: entry is None,
            "the conversation stayed in An's default inbox after the sheet archive")
        _wait_inbox_entry(
            recorder, an["accessToken"], conversation_id,
            lambda entry: entry is not None and entry.get("archived") is True,
            "the conversation never appeared in An's archived inbox", archived=True)
        archived_chip = _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "filterArchivedChip"),
            "the Archived inbox chip was not exposed",
        )
        ui.tap_node(archived_chip)
        _wait_binh_row(
            context, True,
            "the archived conversation did not render in the Archived inbox view")
        ui.screenshot(str(row_dir / "archived-inbox.png"))
        _open_inbox_actions(context)
        _tap_dialog_item(
            context, _PREF_MENU_UNARCHIVE,
            "the inbox sheet did not offer Unarchive inside the archived view")
        _wait_inbox_entry(
            recorder, an["accessToken"], conversation_id,
            lambda entry: entry is not None and entry.get("archived") is False,
            "the sheet unarchive never persisted on An's inbox entry")
        # Stay on the Archived view while the flag propagates: the row is
        # filtered out of this variant by the app's own Room projection, and a
        # clean op sequence drops it in ~2-3s (measured on the running build).
        # Repeatedly re-selecting the filter chips instead starves the page
        # reconcile — every variant switch bumps the page generation, so an
        # in-flight refresh's prune pass is superseded before it can run.
        _wait_binh_row(
            context, False,
            "the unarchived conversation still rendered in the Archived "
            "inbox view", timeout=40)
        ui.tap_node(_wait_ui_node(
            context,
            lambda node: _rid_ends(node, "filterAllChip"),
            "the All inbox chip was not exposed after unarchive",
        ))
        _wait_binh_row(
            context, True,
            "the conversation did not re-render in the All inbox after unarchive")
        result["archive_sheet"] = ("inbox-sheet archive listed the row under Archived; "
                                   "unarchive dropped it on the Archived re-query "
                                   "and returned it to All")

        # === phase 6: pull-to-refresh — flags + private label survive =======
        _pull_to_refresh(context)
        time.sleep(1.5)
        refreshed = _inbox_entry(recorder, an["accessToken"], conversation_id, archived=False)
        if refreshed is None:
            raise ProductFailure("the conversation vanished from An's inbox after refresh")
        for field, expected in (("muted", False), ("pinned", False), ("archived", False)):
            if refreshed.get(field) is not expected:
                raise ProductFailure(
                    f"inbox refresh lost the caller flag {field}={refreshed.get(field)!r}")
        if label_id.casefold() not in _entry_labels(refreshed):
            raise ProductFailure("the private label assignment did not survive refresh")
        _wait_binh_row(context, True, "the peer row did not re-render after refresh")
        _wait_ui_node(
            context,
            lambda node: node.text.strip() == label_name,
            "the private label chip did not render in the inbox after refresh",
        )
        result["refresh"] = "flags and the private label survived pull-to-refresh"

        # === phase 7: profile seen/presence privacy =========================
        avatar = _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "inboxUserAvatar"),
            "the profile entry point was not exposed",
        )
        ui.tap_node(avatar)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "profileName") and node.text.strip() == "An",
            "the profile screen did not open for An",
        )
        _scroll_profile_until(context, "showSeen", "showPresence")
        ui.screenshot(str(row_dir / "profile-privacy.png"))

        # Drain any MESSAGE_READ the app itself broadcast while navigating.
        _stomp_events(context, reader, 1.5)

        seen_switch = _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "showSeen"),
            "the showSeen switch was not exposed",
        )
        ui.tap_node(seen_switch)
        _switch_checked(context, "showSeen", False, "the showSeen switch did not turn off")
        deadline = time.monotonic() + 15
        while _user_settings(recorder, an["accessToken"]).get("showSeen") is not False:
            if time.monotonic() >= deadline:
                raise ProductFailure("the showSeen toggle never persisted server-side")
            time.sleep(0.5)
        mark = recorder.post(
            f"/api/v1/conversations/{conversation_id}/read",
            token=an["accessToken"], body={"upToSeq": last_seq})
        if mark.status not in (200, 204):
            raise EnvironmentFailure(f"conversation read returned HTTP {mark.status}")
        leaked = _stomp_events(context, reader, 4.0)
        if _message_read_for(leaked, an_user_id):
            raise ProductFailure(
                "MESSAGE_READ reached the peer's topic while showSeen was disabled")

        _scroll_profile_until(context, "showSeen")
        seen_switch = _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "showSeen"),
            "the showSeen switch was not exposed for re-enable",
        )
        ui.tap_node(seen_switch)
        _switch_checked(context, "showSeen", True, "the showSeen switch did not turn back on")
        deadline = time.monotonic() + 15
        while _user_settings(recorder, an["accessToken"]).get("showSeen") is not True:
            if time.monotonic() >= deadline:
                raise ProductFailure("the showSeen re-enable never persisted server-side")
            time.sleep(0.5)
        mark = recorder.post(
            f"/api/v1/conversations/{conversation_id}/read",
            token=an["accessToken"], body={"upToSeq": last_seq})
        if mark.status not in (200, 204):
            raise EnvironmentFailure(f"conversation read returned HTTP {mark.status}")
        if not _message_read_for(_stomp_events(context, reader, 6.0), an_user_id):
            raise ProductFailure(
                "MESSAGE_READ did not reach the peer's topic while showSeen was enabled")
        result["show_seen"] = "off suppressed MESSAGE_READ(an) on the peer topic; on restored it"

        presence_switch = _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "showPresence"),
            "the showPresence switch was not exposed",
        )
        ui.tap_node(presence_switch)
        _switch_checked(
            context, "showPresence", False, "the showPresence switch did not turn off")
        deadline = time.monotonic() + 15
        while _user_settings(recorder, an["accessToken"]).get("showPresence") is not False:
            if time.monotonic() >= deadline:
                raise ProductFailure("the showPresence toggle never persisted server-side")
            time.sleep(0.5)
        _wait_inbox_entry(
            recorder, binh["accessToken"], conversation_id,
            lambda entry: entry is not None and not entry.get("peerPresence"),
            "Binh's inbox entry still carried An's peerPresence with showPresence off")
        _scroll_profile_until(context, "showPresence")
        presence_switch = _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "showPresence"),
            "the showPresence switch was not exposed for re-enable",
        )
        ui.tap_node(presence_switch)
        _switch_checked(
            context, "showPresence", True, "the showPresence switch did not turn back on")
        deadline = time.monotonic() + 15
        while _user_settings(recorder, an["accessToken"]).get("showPresence") is not True:
            if time.monotonic() >= deadline:
                raise ProductFailure("the showPresence re-enable never persisted server-side")
            time.sleep(0.5)
        _wait_inbox_entry(
            recorder, binh["accessToken"], conversation_id,
            lambda entry: entry is not None and bool(entry.get("peerPresence")),
            "Binh's inbox entry did not regain An's peerPresence after re-enable")
        result["show_presence"] = ("off removed peerPresence from the peer's entry; "
                                   "on restored it")
        ui.screenshot(str(row_dir / "profile-privacy-restored.png"))
        result["summary"] = "all phases passed"
    except Exception as error:
        primary_error = error
    finally:
        cleanup_errors: list[str] = []
        # Back to the inbox so the next row starts from a known surface.
        try:
            for _ in range(6):
                nodes = _ui_nodes(context)
                if any(_rid_ends(node, "inboxUserAvatar") for node in nodes):
                    break
                ui.key("KEYCODE_BACK")
                time.sleep(0.5)
        except Exception as error:
            cleanup_errors.append(f"ui restore: {type(error).__name__}")
        try:
            # Reset member flags to the un-flagged baseline, then re-apply any
            # flag that was actually set before this row ran.
            recorder.delete(f"/api/v1/conversations/{conversation_id}/archive",
                            token=an["accessToken"])
            recorder.delete(f"/api/v1/conversations/{conversation_id}/mute",
                            token=an["accessToken"])
            recorder.delete(f"/api/v1/conversations/{conversation_id}/pin",
                            token=an["accessToken"])
            if baseline_flags.get("archived") is True:
                recorder.put(f"/api/v1/conversations/{conversation_id}/archive",
                             token=an["accessToken"])
            if baseline_flags.get("pinned") is True:
                recorder.put(f"/api/v1/conversations/{conversation_id}/pin",
                             token=an["accessToken"])
            if baseline_flags.get("muted") is True:
                body = ({"forever": True} if baseline_flags.get("mutedUntil") is None
                        else {"mutedUntil": baseline_flags.get("mutedUntil")})
                recorder.request("PUT", f"/api/v1/conversations/{conversation_id}/mute",
                                 token=an["accessToken"], body=body)
        except Exception as error:
            cleanup_errors.append(f"flag restore: {type(error).__name__}")
        if label_id:
            try:
                recorder.delete(
                    f"/api/v1/conversations/{conversation_id}/labels/{label_id}",
                    token=an["accessToken"])
            except Exception as error:
                cleanup_errors.append(f"label unassign: {type(error).__name__}")
            try:
                deleted = recorder.delete(f"/api/v1/labels/{label_id}", token=an["accessToken"])
                if deleted.status not in (200, 204):
                    cleanup_errors.append(f"label delete HTTP {deleted.status}")
            except Exception as error:
                cleanup_errors.append(f"label delete: {type(error).__name__}")
        for key, wanted in (("showSeen", an_settings0.get("showSeen")),
                            ("showPresence", an_settings0.get("showPresence"))):
            try:
                current = _user_settings(recorder, an["accessToken"]).get(key)
                if current is not wanted:
                    _patch_settings(recorder, an["accessToken"], {key: wanted})
            except Exception as error:
                cleanup_errors.append(f"{key} restore: {type(error).__name__}")
        try:
            current = _user_settings(recorder, binh["accessToken"]).get("showSeen")
            if current is not binh_settings0.get("showSeen"):
                _patch_settings(
                    recorder, binh["accessToken"],
                    {"showSeen": binh_settings0.get("showSeen")})
        except Exception as error:
            cleanup_errors.append(f"peer showSeen restore: {type(error).__name__}")
        for message_id, author_token in seeded:
            try:
                deleted = recorder.delete(
                    f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
                    token=author_token)
                if deleted.status not in (200, 204):
                    cleanup_errors.append(f"message HTTP {deleted.status}")
            except Exception as error:
                cleanup_errors.append(type(error).__name__)
        if cleanup_errors:
            context["cleanup_errors"] = cleanup_errors
    if primary_error is not None:
        raise primary_error
    if context.get("cleanup_errors"):
        raise HarnessError("archive-preferences cleanup could not restore fixtures")
    result["cleanup"] = ("flags/settings/labels restored; "
                         f"{len(seeded)} seeded messages soft-deleted")
    return result


# --- labels-lifecycle -------------------------------------------------------
# Caller-owned label CRUD through the real surfaces: the inbox long-press
# "Assign labels" dialog (assignment mode CheckBoxes + the manage-mode
# create/edit/delete controls), the LabelEditorDialogFragment name/color form,
# the inbox labelFilterChips carousel, and the server-backed ?labelId= filter.
# "One observer/one render" is asserted through the device-visible surface:
# after rotation and after a HOME + am start stop/start cycle the dialog must
# still expose exactly one title and exactly one row per label, the retained
# surface must land exactly one REST write per toggle, and logcat must not
# report a leaked window or fragment exception. The literal LiveData observer
# count is JVM-pinned by LabelDialogFragmentObserverTest and cannot be read
# through uiautomator; the row asserts its observable consequences instead.
_LBL_MENU_ITEM = ("Assign labels", "Gán nhãn")
_LBL_ASSIGN_TITLE = ("Conversation labels", "Nhãn trò chuyện")
_LBL_MANAGE_TITLE = ("Manage labels", "Quản lý nhãn")
_LBL_MODE_MANAGE = ("Manage labels", "Quản lý nhãn")       # assign-mode toggle button
_LBL_MODE_BACK = ("Back to conversation", "Quay lại")      # manage-mode toggle button
_LBL_CREATE = ("Create label", "Tạo nhãn")
_LBL_EDITOR_TITLE = ("New label", "Nhãn mới")
_LBL_NAME_HINT = ("Label name (1–30 characters)", "Tên nhãn (1–30 ký tự)")
_LBL_SAVE = ("Save", "Lưu")
_LBL_DONE = ("Done", "Hoàn tất")
_LBL_DELETE_TITLE = ("Delete label?", "Xóa nhãn?")
_LBL_DELETE_BUTTON = ("Delete", "Xóa")
_LBL_FILTER_ALL = ("All labels", "Tất cả nhãn")
_LBL_MANAGE_CHIP_DESC = ("Manage conversation labels", "Quản lý nhãn trò chuyện")
_LBL_COLOR = ("Blue", "Xanh dương")
_LBL_EMPTY_FILTER = ("No conversations have the", "Chưa có cuộc trò chuyện")
_LBL_EMPTY_ASSIGN = ("Create a label before assigning it.", "Tạo nhãn trước khi gán.")
_LBL_WINDOW_LEAK = ("has leaked window", "WindowLeaked")


def _desc_variants(label: str, en: str, vi: str) -> set[str]:
    return {en.replace("{name}", label), vi.replace("{name}", label)}


def _lbl_filter_desc(name: str) -> set[str]:
    return _desc_variants(name, "Filter inbox by {name}", "Lọc theo nhãn {name}")


def _lbl_assign_desc(name: str) -> set[str]:
    return _desc_variants(name, "Assign label {name}", "Gán nhãn {name}")


def _lbl_chip_desc(name: str) -> set[str]:
    return _desc_variants(name, "Label: {name}", "Nhãn: {name}")


def _lbl_delete_desc(name: str) -> set[str]:
    return _desc_variants(name, "Delete label {name}", "Xóa nhãn {name}")


def _rest_labels(recorder: RecordingClient, token: str) -> list[dict[str, Any]]:
    response = recorder.get("/api/v1/labels", token=token)
    if response.status != 200:
        raise ProductFailure(f"GET /api/v1/labels returned HTTP {response.status}")
    body = response.json() or []
    return [dict(item) for item in body] if isinstance(body, list) else []


def _wait_rest_label(recorder: RecordingClient, token: str, name: str,
                     timeout: float = 15.0) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while True:
        match = next((item for item in _rest_labels(recorder, token)
                      if item.get("name") == name), None)
        if match is not None:
            return match
        if time.monotonic() >= deadline:
            raise ProductFailure(f"REST /api/v1/labels never listed {name!r}")
        time.sleep(0.5)


def _filtered_ids(recorder: RecordingClient, token: str, label_id: str) -> set[str]:
    response = recorder.get("/api/v1/conversations", token=token,
                            params={"labelId": label_id})
    if response.status != 200:
        raise ProductFailure(f"GET /conversations?labelId= returned HTTP {response.status}")
    body = response.json() or []
    items = body if isinstance(body, list) else (body.get("items") or [])
    return {str(item.get("id")) for item in items}


def _open_assign_dialog(context: dict[str, Any]) -> None:
    """Inbox long-press on the seeded Binh row -> 'Assign labels' -> assign dialog."""
    _open_inbox_actions(context)
    _tap_dialog_item(
        context, _LBL_MENU_ITEM,
        "the inbox actions sheet did not offer 'Assign labels'")
    _wait_ui_node(
        context,
        lambda node: node.text.strip() in _LBL_ASSIGN_TITLE and not node.desc.strip(),
        "the conversation-labels dialog did not open")


def _tap_dialog_button(context: dict[str, Any], labels: tuple[str, ...],
                       what: str | None = None, timeout: float | None = None) -> Any:
    """Tap a Button inside the label dialog. The inbox 'Manage labels' chip
    shares the same text, so the match requires an empty content-desc — the
    chip always carries 'Manage conversation labels'/'Quản lý nhãn trò chuyện'."""
    ui = context["device"]
    deadline = time.monotonic() + float(timeout or _ui_timeout(context))
    while True:
        nodes = _ui_nodes(context)
        match = next((node for node in nodes
                      if node.text.strip() in labels
                      and "Button" in node.cls and not node.desc.strip()), None)
        if match is not None:
            ui.tap_node(match)
            return match
        if time.monotonic() >= deadline:
            raise ProductFailure(what or f"the label dialog button {labels!r} was not offered")
        time.sleep(0.4)


def _label_dialog_titles(context: dict[str, Any], title_labels: tuple[str, ...]) -> list[Any]:
    """Nodes carrying a dialog title: bare text with no content-desc, so the
    inbox 'Manage labels' chip (desc='Manage conversation labels') is excluded."""
    return [node for node in _ui_nodes(context)
            if node.text.strip() in title_labels and not node.desc.strip()]


def _assert_single_label_dialog(context: dict[str, Any], label_name: str,
                                *, checked: bool | None = None,
                                title_labels: tuple[str, ...] = _LBL_ASSIGN_TITLE,
                                checkboxes: int = 1) -> None:
    """Exactly one dialog surface: one title node and one CheckBox row per
    expected label — a second observer/render surface would duplicate either."""
    titles = _label_dialog_titles(context, title_labels)
    if len(titles) != 1:
        raise ProductFailure(
            f"expected exactly one label-dialog title, found {len(titles)}")
    nodes = _ui_nodes(context)
    boxes = [node for node in nodes
             if "CheckBox" in node.cls and node.text.strip() == label_name]
    if len(boxes) != checkboxes:
        raise ProductFailure(
            f"expected {checkboxes} CheckBox row(s) for {label_name!r}, found {len(boxes)}")
    if checked is not None:
        wanted = "true" if checked else "false"
        if boxes[0].attrs.get("checked") != wanted:
            raise ProductFailure(
                f"the {label_name!r} checkbox did not render checked={wanted}")


def _carousel_node(context: dict[str, Any],
                   predicate: Callable[[Any], bool], what: str,
                   *, swipes: int = 14) -> Any:
    """Scroll the inbox chip carousel horizontally until predicate matches a
    node whose center is inside the inboxFilterScroll viewport, then return it.
    swipe left (x2<x1) reveals chips to the right; swipe right returns."""
    ui = context["device"]
    for attempt in range(swipes):
        nodes = _ui_nodes(context)
        scroll = next((node for node in nodes if _rid_ends(node, "inboxFilterScroll")), None)
        box = getattr(scroll, "box", None) or (0, 0, 320, 640)
        viewport_left, viewport_right = box[0], box[2]
        for node in nodes:
            if not predicate(node) or not node.box:
                continue
            cx, cy = node.center
            if viewport_left <= cx <= viewport_right:
                return node
        mid_y = (box[1] + box[3]) // 2
        # Alternate: first reveal chips to the right, then sweep back left.
        if attempt % 3 == 2:
            ui.swipe(box[0] + int((box[2] - box[0]) * 0.10), mid_y,
                     box[2] - int((box[2] - box[0]) * 0.10), mid_y, 350)
        else:
            ui.swipe(box[2] - int((box[2] - box[0]) * 0.10), mid_y,
                     box[0] + int((box[2] - box[0]) * 0.10), mid_y, 350)
        time.sleep(0.7)
    raise ProductFailure(what)


def _label_chip(context: dict[str, Any], name: str) -> Any:
    """The inbox filter chip for a label, found through its localized desc."""
    wanted = _lbl_filter_desc(name)
    return _carousel_node(
        context,
        lambda node: node.desc.strip() in wanted,
        f"the inbox label filter chip for {name!r} never appeared")


def _inbox_row_visible(context: dict[str, Any], title: str) -> bool:
    return _inbox_conversation_row(context, title) is not None


def _wait_inbox_row_absent(context: dict[str, Any], title: str, what: str,
                         timeout: float = 12.0) -> None:
    deadline = time.monotonic() + timeout
    while True:
        if not _inbox_row_visible(context, title):
            return
        if time.monotonic() >= deadline:
            raise ProductFailure(what)
        time.sleep(0.4)


def _logcat_window_leaks(context: dict[str, Any], baseline: set[str]) -> list[str]:
    raw = context["adb"].command(
        "shell", "logcat", "-d", "-v", "brief", timeout=30, check=False)
    delta = set(raw.splitlines()) - baseline
    return sorted(line for line in delta
                  if any(marker in line for marker in _LBL_WINDOW_LEAK))


def _device_rotation(context: dict[str, Any]) -> dict[str, str]:
    adb = context["adb"]
    return {
        "accelerometer_rotation": adb.command(
            "shell", "settings", "get", "system", "accelerometer_rotation", timeout=15),
        "user_rotation": adb.command(
            "shell", "settings", "get", "system", "user_rotation", timeout=15),
    }


def _restore_rotation(context: dict[str, Any], before: dict[str, str]) -> None:
    adb = context["adb"]
    wanted_auto = before.get("accelerometer_rotation") or "1"
    wanted_user = before.get("user_rotation") or "0"
    try:
        adb.command("shell", "settings", "put", "system", "user_rotation",
                    wanted_user if wanted_user.isdigit() else "0", timeout=15)
        adb.command("shell", "settings", "put", "system", "accelerometer_rotation",
                    wanted_auto if wanted_auto.isdigit() else "1", timeout=15)
    except Exception as error:
        errors = context.setdefault("cleanup_errors", [])
        errors.append(f"rotation restore: {type(error).__name__}")


def _set_label_checked(context: dict[str, Any], name: str, want: bool) -> None:
    """Tap the assignment CheckBox until its rendered state matches `want`."""
    ui = context["device"]
    wanted = "true" if want else "false"
    wanted_desc = _lbl_assign_desc(name)
    for _ in range(3):
        node = _wait_ui_node(
            context,
            lambda n: "CheckBox" in n.cls and n.text.strip() == name
            and (not n.desc.strip() or n.desc.strip() in wanted_desc),
            f"the assignment checkbox for {name!r} did not render")
        if node.attrs.get("checked") == wanted:
            return
        ui.tap_node(node)
        time.sleep(0.8)
    raise ProductFailure(f"the {name!r} checkbox never reached checked={wanted}")


def _manage_row(context: dict[str, Any], name: str, timeout: float | None = None) -> Any:
    wanted = _lbl_chip_desc(name)
    return _wait_ui_node(
        context,
        lambda node: node.desc.strip() in wanted or node.text.strip() == name,
        f"the manage row for {name!r} did not render",
        timeout=timeout)


def _delete_label_ui(context: dict[str, Any], name: str) -> None:
    """Manage-mode row -> delete icon -> 'Delete label?' -> 'Delete'."""
    wanted = _lbl_delete_desc(name)
    icon = _wait_ui_node(
        context,
        lambda node: node.desc.strip() in wanted,
        f"the delete control for {name!r} was not offered")
    context["device"].tap_node(icon)
    _wait_ui_node(
        context,
        lambda node: node.text.strip() in _LBL_DELETE_TITLE,
        "the delete confirmation did not open")
    _tap_dialog_button(
        context, _LBL_DELETE_BUTTON,
        "the delete confirmation did not offer 'Delete'")


def _labels_rest_sweep(context: dict[str, Any], an_token: str,
                       conversation_id: str, created: set[str]) -> None:
    """Unassign + delete only the labels this row created."""
    errors: list[str] = context.setdefault("cleanup_errors", [])
    recorder: RecordingClient = context["client"]
    try:
        remaining = {str(item.get("id")) for item in _rest_labels(recorder, an_token)}
    except Exception:
        remaining = set()
    for label_id in sorted(created):
        if not label_id or label_id not in remaining:
            continue
        try:
            recorder.delete(
                f"/api/v1/conversations/{conversation_id}/labels/{label_id}",
                token=an_token)
        except Exception as error:
            errors.append(f"label unassign {label_id[:8]}: {type(error).__name__}")
        try:
            deleted = recorder.delete(f"/api/v1/labels/{label_id}", token=an_token)
            if deleted.status not in (200, 204):
                errors.append(f"label delete {label_id[:8]} HTTP {deleted.status}")
        except Exception as error:
            errors.append(f"label delete {label_id[:8]}: {type(error).__name__}")


def run_labels_lifecycle(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """Create/assign/filter/delete a caller-private label through the UI, then
    recreate the dialog by rotation and by a HOME + am start stop/start and by
    a force-stop relaunch — asserting one render surface, one REST write per
    toggle, and agreement with GET /api/v1/labels + ?labelId= throughout."""
    _campaign_serial(context)
    _campaign_base_url(context)
    _prepare_seeded_an(context)
    api, _adb_ui, patch = context["helpers"]
    recorder: RecordingClient = context["client"]
    ui = context["device"]
    an, binh, conversation_id, _an_user_id, _binh_user_id = _seed_pair(context)
    row_dir = Path(context["row_dir"])
    tag = uuid.uuid4().hex[:8]
    label_a = f"sj-lab-{tag}"
    label_b = f"sj-lab-b-{tag}"
    rotation_before = _device_rotation(context)
    created_ids: set[str] = set()
    label_a_id = ""
    result: dict[str, Any] = {}
    primary_error: Exception | None = None
    try:
        # === phase 1: create through the editor dialog ======================
        _open_assign_dialog(context)
        _assert_single_label_dialog(context, label_a, checkboxes=0)
        _tap_dialog_button(
            context, _LBL_MODE_MANAGE,
            "the assignment dialog did not offer the manage-mode toggle")
        _wait_ui_node(
            context,
            lambda node: node.text.strip() in _LBL_MANAGE_TITLE and not node.desc.strip(),
            "the label dialog did not switch to manage mode")
        _tap_dialog_button(
            context, _LBL_CREATE,
            "manage mode did not offer 'Create label'")
        _wait_ui_node(
            context,
            lambda node: node.text.strip() in _LBL_EDITOR_TITLE,
            "the label editor dialog did not open")
        name_input = _wait_ui_node(
            context,
            lambda node: node.desc.strip() in _LBL_NAME_HINT
            and "EditText" in node.cls,
            "the label name field did not render")
        ui.tap_node(name_input)
        ui.type_text(label_a)
        # The IME covers the color chips; one BACK drops the keyboard without
        # dismissing the editor (the form is a real DialogFragment).
        ui.key("KEYCODE_BACK")
        color = _wait_ui_node(
            context,
            lambda node: node.text.strip() in _LBL_COLOR
            and "RadioButton" in node.cls,
            "the editor color chips did not render")
        ui.tap_node(color)
        _tap_dialog_button(
            context, _LBL_SAVE, "the editor did not offer 'Save'")
        _manage_row(context, label_a)
        created = _wait_rest_label(recorder, an["accessToken"], label_a)
        label_a_id = str(created.get("id") or "")
        if not label_a_id:
            raise ProductFailure("REST /api/v1/labels listed the UI-created label without an id")
        created_ids.add(label_a_id)
        ui.screenshot(str(row_dir / "label-created.png"))
        result["create"] = "label created through the editor; REST /labels agrees"

        # === phase 2: assign through the checkbox ===========================
        _tap_dialog_button(
            context, _LBL_MODE_BACK,
            "manage mode did not offer 'Back to conversation'")
        _assert_single_label_dialog(context, label_a, checked=False)
        _set_label_checked(context, label_a, True)
        _wait_inbox_entry(
            recorder, an["accessToken"], conversation_id,
            lambda entry: entry is not None
            and label_a_id.casefold() in _entry_labels(entry),
            "the UI label assignment never persisted on An's inbox entry")
        _assert_single_label_dialog(context, label_a, checked=True)
        ui.screenshot(str(row_dir / "label-assigned.png"))
        filtered = _filtered_ids(recorder, an["accessToken"], label_a_id)
        if filtered != {conversation_id}:
            raise ProductFailure(
                f"REST ?labelId= agreed {sorted(filtered)!r}, expected only the seeded conversation")
        peer_entry = _inbox_entry(recorder, binh["accessToken"], conversation_id,
                                  archived=False)
        if label_a_id.casefold() in _entry_labels(peer_entry):
            raise ProductFailure("the private label leaked into Binh's inbox entry")
        if any(str(item.get("id")).casefold() == label_a_id.casefold()
               for item in _rest_labels(recorder, binh["accessToken"])):
            raise ProductFailure("the private label leaked into Binh's label list")
        _tap_dialog_button(
            context, _LBL_DONE, "the assignment dialog did not offer 'Done'")
        _wait_ui_absent(
            context,
            lambda node: node.text.strip() in _LBL_ASSIGN_TITLE and not node.desc.strip(),
            "the assignment dialog did not dismiss")
        result["assign"] = "checkbox landed on An's entry; ?labelId= agrees; peer clean"

        # === phase 3: inbox label filter chip ===============================
        chip = _label_chip(context, label_a)
        ui.tap_node(chip)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "conversationList"),
            "the inbox list vanished under the label filter")
        deadline = time.monotonic() + _ui_timeout(context)
        while not _inbox_row_visible(context, "Binh"):
            if time.monotonic() >= deadline:
                raise ProductFailure("the labeled conversation never rendered under its filter")
            time.sleep(0.4)
        _wait_inbox_row_absent(
            context, "NexaChat Team",
            "an unlabeled group row stayed visible under the label filter")
        _wait_inbox_row_absent(
            context, "Chi",
            "an unlabeled direct row stayed visible under the label filter")
        ui.screenshot(str(row_dir / "label-filtered.png"))
        result["filter"] = "label chip shows only the labeled row; REST ?labelId= agrees"
        all_chip = _carousel_node(
            context,
            lambda node: node.text.strip() in _LBL_FILTER_ALL and not node.desc.strip(),
            "the 'All labels' filter chip never appeared")
        ui.tap_node(all_chip)
        # Delta-sync restore plus a full scroll sweep can exceed the stock UI
        # timeout on a loaded bridge — rows return on their own, just late
        # (run-sweep-verify-13: Binh present in both post-failure captures).
        deadline = time.monotonic() + 60.0
        while not _inbox_row_found_scrolled(context, "NexaChat Team"):
            if time.monotonic() >= deadline:
                raise ProductFailure("unlabeled rows did not return after 'All labels'")
        while not _inbox_row_found_scrolled(context, "Binh"):
            if time.monotonic() >= deadline:
                raise ProductFailure("the labeled row did not return after 'All labels'")
        result["unfilter"] = "All labels restores the full inbox"

        # === phase 4: dialog recreation — rotation + stop/start =============
        _open_assign_dialog(context)
        _assert_single_label_dialog(context, label_a, checked=True)
        leak_baseline: set[str] = set()
        try:
            raw = context["adb"].command(
                "shell", "logcat", "-d", "-v", "brief", timeout=30, check=False)
            leak_baseline = set(raw.splitlines())
        except Exception:
            leak_baseline = set()
        ui.rotate(1)
        _wait_ui_node(
            context,
            lambda node: node.text.strip() in _LBL_ASSIGN_TITLE and not node.desc.strip(),
            "the assignment dialog did not re-render after rotation to landscape",
            timeout=25)
        _assert_single_label_dialog(context, label_a, checked=True)
        ui.screenshot(str(row_dir / "label-dialog-landscape.png"))
        ui.rotate(0)
        _wait_ui_node(
            context,
            lambda node: node.text.strip() in _LBL_ASSIGN_TITLE and not node.desc.strip(),
            "the assignment dialog did not re-render after rotation to portrait",
            timeout=25)
        _assert_single_label_dialog(context, label_a, checked=True)
        ui.key("KEYCODE_HOME")
        context["adb"].command("shell", "am", "start", "-n",
                               f"{PACKAGE}/{MAIN_ACTIVITY}", timeout=30)
        _wait_ui_node(
            context,
            lambda node: node.text.strip() in _LBL_ASSIGN_TITLE and not node.desc.strip(),
            "the assignment dialog did not survive the HOME + relaunch stop/start",
            timeout=25)
        _assert_single_label_dialog(context, label_a, checked=True)
        leaks = _logcat_window_leaks(context, leak_baseline)
        if leaks:
            raise ProductFailure(
                "logcat reported a leaked window across dialog recreation: "
                + sanitize_text(leaks[0])[:300])
        # The retained surface still drives exactly one write per toggle.
        _set_label_checked(context, label_a, False)
        _wait_inbox_entry(
            recorder, an["accessToken"], conversation_id,
            lambda entry: entry is not None
            and label_a_id.casefold() not in _entry_labels(entry),
            "the post-recreation unassign never persisted on An's inbox entry")
        _set_label_checked(context, label_a, True)
        _wait_inbox_entry(
            recorder, an["accessToken"], conversation_id,
            lambda entry: entry is not None
            and label_a_id.casefold() in _entry_labels(entry),
            "the post-recreation re-assign never persisted on An's inbox entry")
        result["recreate"] = ("rotation + HOME/relaunch keep exactly one dialog surface; "
                              "one REST write per toggle; no leaked window")
        _tap_dialog_button(
            context, _LBL_DONE, "the assignment dialog did not offer 'Done'")
        _wait_ui_absent(
            context,
            lambda node: node.text.strip() in _LBL_ASSIGN_TITLE and not node.desc.strip(),
            "the assignment dialog did not dismiss after recreation checks")

        # Process death: the dialog is gone, the assignment persists in Room,
        # and reopening refetches from REST (post-#181 contract).
        context["adb"].command("shell", "am", "force-stop", PACKAGE, timeout=20)
        context["adb"].command("shell", "am", "start", "-n",
                               f"{PACKAGE}/{MAIN_ACTIVITY}", timeout=30)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "inboxUserAvatar"),
            "the inbox did not render after the force-stop relaunch",
            timeout=30)
        if _label_dialog_titles(context, _LBL_ASSIGN_TITLE):
            raise ProductFailure("a label dialog survived process death")
        _open_assign_dialog(context)
        _assert_single_label_dialog(context, label_a, checked=True)
        result["restart"] = "force-stop drops the dialog; assignment persists; reopen refetches"

        # === phase 5: reopen refetch + unassign + delete ====================
        # A label created over REST while the app runs is invisible until a
        # refresh — reopening the dialog must refetch it (the merged fix).
        response = recorder.post(
            "/api/v1/labels", token=an["accessToken"],
            body={"name": label_b, "colorId": "TEAL"})
        if response.status not in (200, 201):
            raise EnvironmentFailure(f"REST label B create returned HTTP {response.status}")
        label_b_id = str((response.json() or {}).get("id") or "")
        if not label_b_id:
            raise EnvironmentFailure("REST label B create did not return an id")
        created_ids.add(label_b_id)
        _tap_dialog_button(
            context, _LBL_DONE, "the reopened dialog did not offer 'Done'")
        _wait_ui_absent(
            context,
            lambda node: node.text.strip() in _LBL_ASSIGN_TITLE and not node.desc.strip(),
            "the reopened dialog did not dismiss")
        _open_assign_dialog(context)
        _wait_ui_node(
            context,
            lambda node: "CheckBox" in node.cls and node.text.strip() == label_b,
            "a REST-created label did not appear when the dialog reopened (stale labels)")
        ui.screenshot(str(row_dir / "label-reopen-refetch.png"))
        result["refetch"] = "reopened dialog refetches REST-created labels"

        _tap_dialog_button(
            context, _LBL_MODE_MANAGE,
            "the assignment dialog did not offer the manage-mode toggle")
        _wait_ui_node(
            context,
            lambda node: node.text.strip() in _LBL_MANAGE_TITLE and not node.desc.strip(),
            "the label dialog did not switch to manage mode")
        _manage_row(context, label_b)
        _delete_label_ui(context, label_b)
        _wait_ui_absent(
            context,
            lambda node: node.desc.strip() in _lbl_chip_desc(label_b),
            "the manage row for the deleted REST label did not disappear")
        deadline = time.monotonic() + _ui_timeout(context)
        while label_b_id in {str(item.get("id")) for item in _rest_labels(recorder, an["accessToken"])}:
            if time.monotonic() >= deadline:
                raise ProductFailure("the UI-deleted label survived in REST /api/v1/labels")
            time.sleep(0.5)
        ui.screenshot(str(row_dir / "label-deleted.png"))
        result["delete"] = "manage-mode delete removed the label in UI and REST"

        _tap_dialog_button(
            context, _LBL_MODE_BACK,
            "manage mode did not offer 'Back to conversation'")
        _set_label_checked(context, label_a, False)
        _wait_inbox_entry(
            recorder, an["accessToken"], conversation_id,
            lambda entry: entry is not None
            and label_a_id.casefold() not in _entry_labels(entry),
            "the UI unassign never persisted on An's inbox entry")
        result["unassign"] = "unchecking removed the assignment on REST"

        _tap_dialog_button(
            context, _LBL_MODE_MANAGE,
            "the assignment dialog did not offer the manage-mode toggle")
        _wait_ui_node(
            context,
            lambda node: node.text.strip() in _LBL_MANAGE_TITLE and not node.desc.strip(),
            "the label dialog did not switch to manage mode")
        _delete_label_ui(context, label_a)
        deadline = time.monotonic() + _ui_timeout(context)
        while label_a_id in {str(item.get("id")) for item in _rest_labels(recorder, an["accessToken"])}:
            if time.monotonic() >= deadline:
                raise ProductFailure("the UI-deleted label survived in REST /api/v1/labels")
            time.sleep(0.5)
        created_ids.discard(label_a_id)
        # With zero labels left the whole filter row must disappear.
        _tap_dialog_button(
            context, _LBL_DONE, "the emptied dialog did not offer 'Done'")
        _wait_ui_absent(
            context,
            lambda node: (node.text.strip() in _LBL_ASSIGN_TITLE
                          or node.text.strip() in _LBL_MANAGE_TITLE)
            and not node.desc.strip(),
            "the label dialog did not dismiss")
        _wait_ui_absent(
            context,
            lambda node: node.text.strip() in _LBL_FILTER_ALL
            or node.desc.strip() in _LBL_MANAGE_CHIP_DESC,
            "the label filter chip row stayed visible after the last label was deleted")
        ui.screenshot(str(row_dir / "labels-cleared.png"))
        result["delete_last"] = "deleting the final label hides the filter chip row"
    except Exception as error:
        primary_error = error
    finally:
        _restore_rotation(context, rotation_before)
        _labels_rest_sweep(context, an["accessToken"], conversation_id, created_ids)
        try:
            ui.key("KEYCODE_BACK")
            ui.key("KEYCODE_BACK")
            _wait_ui_node(
                context,
                lambda node: _rid_ends(node, "inboxUserAvatar"),
                "the UI did not return to the inbox",
                timeout=10)
        except Exception:
            try:
                context["adb"].command("shell", "am", "force-stop", PACKAGE, timeout=20)
                context["adb"].command("shell", "am", "start", "-n",
                                       f"{PACKAGE}/{MAIN_ACTIVITY}", timeout=30)
            except Exception:
                pass
    if primary_error is not None:
        raise primary_error
    if context.get("cleanup_errors"):
        raise HarnessError("labels-lifecycle cleanup could not restore fixtures")
    result["cleanup"] = "row-created labels unassigned+deleted; rotation restored; inbox reached"
    return result


_GROUPS_DUNG_PHONE = "+84900000004"  # seeded Dung; never a member until the UI adds him
_GROUPS_TOKEN_RE = re.compile(r"[A-Za-z0-9_-]{43}")


def _node_enabled(node: Any) -> bool:
    return getattr(node, "attrs", {}).get("enabled") == "true"


def _node_checked(node: Any) -> bool:
    return getattr(node, "attrs", {}).get("checked") == "true"


def _list_children(context: dict[str, Any], container_rid: str) -> list[Any]:
    """A11y nodes fully inside the bounds of a list container."""
    nodes = _ui_nodes(context)
    container = next((node for node in nodes if _rid_ends(node, container_rid)), None)
    if container is None or not container.box:
        return []
    top, bottom = container.box[1], container.box[3]
    return [node for node in nodes
            if node.box and top <= node.box[1] and node.box[3] <= bottom]


def _list_row(context: dict[str, Any], container_rid: str, *, rid: str | None = None,
              text: str | None = None, desc: str | None = None) -> Any | None:
    """First node inside a list container matching the given selectors."""
    for node in _list_children(context, container_rid):
        if rid is not None and not _rid_ends(node, rid):
            continue
        if text is not None and node.text.strip() != text:
            continue
        if desc is not None and node.desc.strip() != desc:
            continue
        if rid is None and text is None and desc is None:
            continue
        return node
    return None


def _wait_list_row(context: dict[str, Any], container_rid: str, what: str,
                   *, present: bool = True, timeout: float | None = None,
                   rid: str | None = None, text: str | None = None,
                   desc: str | None = None) -> Any | None:
    deadline = time.monotonic() + float(timeout or _ui_timeout(context))
    while True:
        node = _list_row(context, container_rid, rid=rid, text=text, desc=desc)
        if present and node is not None:
            return node
        if not present and node is None:
            return None
        if time.monotonic() >= deadline:
            raise ProductFailure(what)
        time.sleep(0.4)


def _group_entry(recorder: RecordingClient, token: str, conversation_id: str) -> dict[str, Any] | None:
    return _inbox_entry(recorder, token, conversation_id, archived=False)


def _group_roles(entry: dict[str, Any] | None) -> dict[str, str]:
    """member display name -> role from an inbox entry projection."""
    roles: dict[str, str] = {}
    for member in (entry or {}).get("members") or []:
        name = str(member.get("displayName") or member.get("phone")
                   or member.get("userId") or "")
        if name:
            roles[name] = str(member.get("role") or "")
    return roles


def _wait_group_roles(recorder: RecordingClient, token: str, conversation_id: str,
                      expected: dict[str, str], what: str,
                      timeout: float = 15.0) -> dict[str, Any]:
    """Poll the caller's inbox entry until its member map equals expected."""
    deadline = time.monotonic() + timeout
    entry: dict[str, Any] | None = None
    while True:
        entry = _group_entry(recorder, token, conversation_id)
        if _group_roles(entry) == expected:
            return dict(entry or {})
        if time.monotonic() >= deadline:
            raise ProductFailure(
                f"{what}: inbox members {_group_roles(entry)!r} != {expected!r}")
        time.sleep(0.6)


def _member_names_ui(context: dict[str, Any]) -> set[str]:
    return {node.text.strip() for node in _list_children(context, "memberList")
            if _rid_ends(node, "memberName") and node.text.strip()}


def _screen_size(context: dict[str, Any]) -> tuple[int, int]:
    cached = context.get("_screen_size")
    if isinstance(cached, tuple):
        return cached
    size = (320, 640)
    try:
        out = context["device"].shell("wm", "size")
        match = re.search(r"(\d+)x(\d+)", out or "")
        if match:
            size = (int(match.group(1)), int(match.group(2)))
    except Exception:
        pass
    context["_screen_size"] = size
    return size


def _on_screen(node: Any, size: tuple[int, int]) -> bool:
    """A node is tappable only while its bounds intersect the real window —
    NestedScrollView children keep a11y bounds even below the fold."""
    if node is None or not node.box:
        return False
    width, height = size
    left, top, right, bottom = node.box
    return left < width and right > 0 and top < height and bottom > 0


def _scroll_screen_down(context: dict[str, Any]) -> None:
    width, height = _screen_size(context)
    context["device"].swipe(
        width // 2, int(height * 0.72), width // 2, int(height * 0.30), 350)


def _scroll_until_on_screen(context: dict[str, Any], rid: str, what: str,
                          swipes: int = 8) -> Any:
    """Swipe the scroll container down until the rid is inside the window."""
    size = _screen_size(context)
    for _ in range(swipes):
        match = next((node for node in _ui_nodes(context)
                      if _rid_ends(node, rid)), None)
        if _on_screen(match, size):
            return match
        _scroll_screen_down(context)
        time.sleep(0.6)
    raise ProductFailure(what)


def _tap_dialog_text(context: dict[str, Any], label: str,
                     exclude_rid: str | None = None, timeout: float | None = None) -> Any:
    """Tap a confirmation label inside a dialog, never the same-labelled
    screen button left behind the modal (Leave group, Revoke link) or the
    dialog's own title TextView — the leave dialog's title is also
    'Leave group', so only a clickable control qualifies."""
    node = _wait_ui_node(
        context,
        lambda item: item.text.strip() == label and item.clickable
        and (exclude_rid is None or not _rid_ends(item, exclude_rid)),
        f"the {label} confirmation was not rendered",
        timeout=timeout)
    context["device"].tap_node(node)
    return node


def _remove_member_rest(recorder: RecordingClient, token: str, conversation_id: str,
                        user_id: str) -> None:
    response = recorder.delete(
        f"/api/v1/conversations/{conversation_id}/members/{user_id}", token=token)
    for _attempt in range(2):
        if response.status != 429:
            break
        details = ((response.json() or {}).get("error") or {}).get("details") or {}
        try:
            retry_after = int(details.get("retryAfterSeconds") or 5)
        except (TypeError, ValueError):
            retry_after = 5
        time.sleep(min(30, max(2, retry_after + 1)))
        response = recorder.delete(
            f"/api/v1/conversations/{conversation_id}/members/{user_id}",
            token=token)
    _expect_status(response, {200, 204, 403, 404},
                   f"remove member {user_id}")


def run_groups_contacts(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """Group lifecycle + invite approval on a disposable group.

    The device runs as seeded An. Every mutation is driven through the Android
    UI and cross-checked against REST truth: contacts list -> group create
    picker -> chat -> group detail (rename, member add/remove) -> invite
    manage (approval link create, join-request approval, revoke) -> invite
    join deep link -> leave as last member. Chi is the removed member who must
    be denied everywhere; Dung requests to join and is approved on-device.
    """
    _campaign_serial(context)
    _campaign_base_url(context)
    _prepare_seeded_an(context)
    api, _adb_ui, _patch = context["helpers"]
    recorder: RecordingClient = context["client"]
    ui = context["device"]
    an = _actor_session(api, recorder, api.USER_A["phone"])
    _actor_session(api, recorder, api.USER_C["phone"])  # Binh warms the session cache
    chi = _actor_session(api, recorder, api.USER_B["phone"])
    dung = _actor_session(api, recorder, _GROUPS_DUNG_PHONE)
    an_token = an["accessToken"]
    chi_token = chi["accessToken"]
    dung_token = dung["accessToken"]
    an_id = str((an.get("user") or {}).get("id") or "")
    if not an_id:
        raise EnvironmentFailure("the An session did not expose a user id")
    row_dir = Path(context["row_dir"])
    tag = uuid.uuid4().hex[:8]
    title = f"sj-grp-{tag}"
    title_renamed = f"{title}-r"
    conversation_id = ""
    link_revoked = False
    result: dict[str, Any] = {}
    primary_error: Exception | None = None
    try:
        contacts = recorder.get("/api/v1/contacts", token=an_token)
        _expect_status(contacts, {200}, "contacts list")
        contact_names = {str((item or {}).get("displayName") or "")
                         for item in (contacts.json() or [])}
        for needed in ("Binh", "Chi", "Dung"):
            if needed not in contact_names:
                raise EnvironmentFailure(
                    f"seeded contact {needed} is missing from An's REST contacts")
        contact_ids = {str((item or {}).get("displayName") or ""):
                       str((item or {}).get("contactUserId") or "")
                       for item in (contacts.json() or [])}
        binh_id = contact_ids.get("Binh", "")
        chi_id = contact_ids.get("Chi", "")
        dung_id = contact_ids.get("Dung", "")
        if not all((binh_id, chi_id, dung_id)):
            raise EnvironmentFailure("seeded contacts did not expose user ids")

        # === contacts surface ==============================================
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "inboxContactsButton"),
            "the inbox contacts entry point was not visible")
        ui.tap_node(next(node for node in _ui_nodes(context)
                         if _rid_ends(node, "inboxContactsButton")))
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "contactList"),
            "the contacts screen did not render contactList")
        deadline = time.monotonic() + _ui_timeout(context)
        while True:
            ui_names = {node.text.strip()
                        for node in _list_children(context, "contactList")
                        if _rid_ends(node, "name") and node.text.strip()}
            if {"Binh", "Chi", "Dung"}.issubset(ui_names):
                break
            if time.monotonic() >= deadline:
                raise ProductFailure(
                    f"the contacts list did not render every seeded contact: {sorted(ui_names)!r}")
            time.sleep(0.4)
        missing_on_server = {"Binh", "Chi", "Dung"} - contact_names
        if missing_on_server:
            raise ProductFailure(
                f"contacts rendered on-device but missing from REST: {sorted(missing_on_server)}")
        ui.screenshot(str(row_dir / "contacts.png"))
        result["contacts"] = ("contactList rendered Binh/Chi/Dung and every name "
                              "agreed with GET /contacts")

        entry_point = _wait_ui_node(
            context, lambda node: _rid_ends(node, "createGroupEntry"),
            "the contacts surface did not expose createGroupEntry")
        ui.tap_node(entry_point)

        # === create through the UI picker ==================================
        name_input = _wait_ui_node(
            context, lambda node: _rid_ends(node, "groupNameInput"),
            "the group-create screen did not open")
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "memberPickerList"),
            "the member picker did not render")
        ui.tap_node(name_input)
        ui.type_text(title)
        # The opened IME covers createButton, so dismiss it once the text is
        # in — but only while the button is genuinely hidden, otherwise a BACK
        # would navigate away from this screen.
        for _ in range(6):
            if any(_rid_ends(node, "createButton") for node in _ui_nodes(context)):
                break
            ui.key("KEYCODE_BACK")
            time.sleep(0.5)
        picker_row = _wait_list_row(
            context, "memberPickerList",
            "the picker did not list Binh",
            rid="name", text="Binh")
        ui.tap_node(picker_row)
        deadline = time.monotonic() + _ui_timeout(context)
        while True:
            # The CheckBox is accessibility-hidden; the row itself carries the
            # checkable/checked state through rowContent's a11y delegate.
            selected = [node for node in _list_children(context, "memberPickerList")
                        if _rid_ends(node, "rowContent") and _node_checked(node)]
            create_button = next((node for node in _ui_nodes(context)
                                  if _rid_ends(node, "createButton")), None)
            if selected and create_button is not None and _node_enabled(create_button):
                break
            if time.monotonic() >= deadline:
                raise ProductFailure("picking Binh never enabled the Create button")
            time.sleep(0.4)
        ui.screenshot(str(row_dir / "group-create.png"))
        ui.tap_node(create_button)
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "messageInput"),
            "the created group did not land on its chat composer",
            timeout=30)

        # The new group must surface in An's REST inbox with the picked member.
        deadline = time.monotonic() + 15
        entry = None
        while time.monotonic() < deadline:
            response = recorder.get("/api/v1/conversations", token=an_token,
                                    params={"archived": "false"})
            _expect_status(response, {200}, "inbox read after create")
            body = response.json() or []
            items = body.get("items") if isinstance(body, dict) else body
            for item in items or []:
                if str(item.get("title") or "") == title:
                    entry = item
                    conversation_id = str(item.get("id") or "")
            if entry is not None:
                break
            time.sleep(0.6)
        if not conversation_id:
            raise ProductFailure(
                f"the UI-created group {title!r} never appeared in An's inbox")
        entry = _wait_group_roles(
            recorder, an_token, conversation_id,
            {"An": "ADMIN", "Binh": "MEMBER"},
            "the created group's REST members disagree with the picker selection")
        if str(entry.get("type") or "") != "GROUP":
            raise ProductFailure("the created conversation is not type GROUP")
        result["create"] = ("groupNameInput + memberPickerList created the group; "
                            "REST members are An ADMIN + Binh")

        # === group detail: members agree ==================================
        toolbar = _wait_ui_node(
            context, lambda node: _rid_ends(node, "chatToolbar") and node.clickable,
            "the group chat toolbar was not clickable",
            timeout=10)
        ui.tap_node(toolbar)
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "memberList"),
            "the group detail member list did not open")
        title_node = _wait_ui_node(
            context, lambda node: _rid_ends(node, "groupTitle"),
            "the group detail title did not render")
        if title_node.text.strip() != title:
            raise ProductFailure(
                f"group detail title {title_node.text.strip()!r} != {title!r}")
        deadline = time.monotonic() + _ui_timeout(context)
        while True:
            names = _member_names_ui(context)
            if names == {"An", "Binh"}:
                break
            if time.monotonic() >= deadline:
                raise ProductFailure(
                    f"member list {sorted(names)!r} != ['An', 'Binh']")
            time.sleep(0.4)
        admins = [node for node in _list_children(context, "memberList")
                  if _rid_ends(node, "memberRole") and node.text.strip()]
        if [node.text.strip() for node in admins] != ["Admin"]:
            raise ProductFailure(
                f"expected exactly one Admin badge, saw {[n.text for n in admins]!r}")
        ui.screenshot(str(row_dir / "group-detail.png"))
        result["member_list"] = ("group detail rendered An(Admin)+Binh and agreed "
                                 "with the REST member map")

        # === rename through the title dialog ===============================
        ui.tap_node(title_node)
        _wait_ui_node(
            context, lambda node: node.text.strip() == "Rename group",
            "the rename dialog did not open")
        editor = _wait_ui_node(
            context, lambda node: "EditText" in node.cls,
            "the rename dialog did not expose an editor")
        ui.tap_node(editor)
        ui.move_end()
        ui.delete_chars(len(title))
        ui.type_text(title_renamed)
        _tap_dialog_text(context, "Save")
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "groupTitle")
            and node.text.strip() == title_renamed,
            "the group title did not update on the detail screen")
        _wait_inbox_entry(
            recorder, an_token, conversation_id,
            lambda item: item is not None
            and str(item.get("title") or "") == title_renamed,
            "the rename never reached An's REST inbox entry")
        result["rename"] = "the title dialog renamed the group on UI and REST"

        # === add a member through the bottom sheet =========================
        add_button = _wait_ui_node(
            context, lambda node: _rid_ends(node, "addMemberButton"),
            "the add-member button was not visible for the admin")
        ui.tap_node(add_button)
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "addMemberList"),
            "the add-member sheet did not open")
        sheet_row = _wait_list_row(
            context, "addMemberList",
            "the add-member sheet did not list Chi",
            rid="name", text="Chi")
        ui.tap_node(sheet_row)
        confirm = _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "addMemberConfirm") and _node_enabled(node),
            "selecting Chi never enabled the sheet confirm button")
        ui.tap_node(confirm)
        _wait_ui_absent(
            context, lambda node: _rid_ends(node, "addMemberList"),
            "the add-member sheet did not close after confirm")
        _wait_list_row(
            context, "memberList",
            "Chi did not appear in the member list after the sheet add",
            rid="memberName", text="Chi")
        ui.screenshot(str(row_dir / "member-added.png"))
        _wait_group_roles(
            recorder, an_token, conversation_id,
            {"An": "ADMIN", "Binh": "MEMBER", "Chi": "MEMBER"},
            "REST members did not gain Chi after the UI add")
        result["add_member"] = "the sheet added Chi; member list and REST agree"

        # === remove the member through the row button ======================
        remove_button = _wait_ui_node(
            context,
            lambda node: node.desc.strip() == "Remove Chi from the group",
            "the member row did not expose Chi's remove button")
        ui.tap_node(remove_button)
        _wait_ui_node(
            context, lambda node: node.text.strip() == "Remove Chi from this group?",
            "the remove confirmation did not open")
        _tap_dialog_text(context, "Remove")
        _wait_ui_absent(
            context,
            lambda node: _rid_ends(node, "memberName")
            and node.text.strip() == "Chi",
            "Chi stayed in the member list after the confirmed remove")
        ui.screenshot(str(row_dir / "member-removed.png"))
        _wait_group_roles(
            recorder, an_token, conversation_id,
            {"An": "ADMIN", "Binh": "MEMBER"},
            "REST members still carried Chi after the UI remove")

        # Removed user denial: inbox row gone, history read refused.
        _wait_inbox_entry(
            recorder, chi_token, conversation_id,
            lambda item: item is None,
            "the removed member kept the group in her inbox")
        denied = recorder.get(
            f"/api/v1/conversations/{conversation_id}/messages", token=chi_token,
            params={"limit": 1})
        _expect_status(denied, {403},
                       "the removed member's message read")
        result["remove_member"] = ("Chi was removed through the member row; her "
                                   "inbox dropped the group and history reads 403")

        # === invite link through the manage screen =========================
        invite_entry = _wait_ui_node(
            context, lambda node: _rid_ends(node, "inviteLinkButton"),
            "the invite-link entry point was not visible for the admin")
        ui.tap_node(invite_entry)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "linkStatus")
            and node.text.strip() == "No active link.",
            "the invite screen did not open on the no-link state")
        switch = _wait_ui_node(
            context, lambda node: _rid_ends(node, "approvalSwitch"),
            "the approval switch was not exposed")
        ui.tap_node(switch)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "approvalSwitch") and _node_checked(node),
            "the approval switch did not stay on")
        create_link = _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "createLinkButton")
            and _node_enabled(node),
            "the create-link button never enabled")
        ui.tap_node(create_link)
        share_node = _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "shareUrl")
            and _GROUPS_TOKEN_RE.search(node.text or "") is not None,
            "the created invite link never rendered a share URL",
            timeout=30)
        share_match = _GROUPS_TOKEN_RE.search(share_node.text or "")
        assert share_match is not None  # guaranteed by the wait predicate
        token = share_match.group(0)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "linkStatus")
            and node.text.strip() == "Active invite link",
            "the invite status never flipped to active")
        ui.screenshot(str(row_dir / "invite-link.png"))
        link_read = recorder.get(
            f"/api/v1/conversations/{conversation_id}/invite-link", token=an_token)
        _expect_status(link_read, {200}, "invite-link read")
        link_body = link_read.json() or {}
        if link_body.get("requiresApproval") is not True:
            raise ProductFailure(
                "the UI-created invite link did not persist requiresApproval")
        result["invite_link"] = ("approval switch + Create link produced a share URL; "
                                 "REST agrees requiresApproval=true")

        # The removed member is banned from every link (no link was active at
        # removal -> conversation-wide ban row); her join attempt must 403.
        banned = recorder.post(
            f"/api/v1/invite-links/{token}/join", token=chi_token)
        _expect_status(banned, {403},
                       "the removed member's invite rejoin")

        # Dung previews then requests to join; approval is required.
        preview = recorder.get(f"/api/v1/invite-links/{token}", token=dung_token)
        _expect_status(preview, {200}, "invite preview")
        preview_body = preview.json() or {}
        if (str(preview_body.get("conversationId") or "") != conversation_id
                or str(preview_body.get("title") or "") != title_renamed
                or preview_body.get("requiresApproval") is not True
                or preview_body.get("isMember") is not False):
            raise ProductFailure(
                f"invite preview lied about destination/state: {preview_body!r}")
        if int(preview_body.get("memberCount") or 0) != 2:
            raise ProductFailure(
                f"invite preview memberCount {preview_body.get('memberCount')!r} != 2")
        joined = recorder.post(
            f"/api/v1/invite-links/{token}/join", token=dung_token)
        _expect_status(joined, {200, 201}, "invite join request")
        join_body = joined.json() or {}
        if str(join_body.get("status") or "") != "PENDING":
            raise ProductFailure(
                f"the approval-required join returned {join_body.get('status')!r}")
        if str(join_body.get("conversationId") or "") != conversation_id:
            raise ProductFailure("the join request targeted the wrong conversation")

        # === approval queue on-device ======================================
        # The requests section sits below the fold of the screen's
        # NestedScrollView; off-screen children keep a11y bounds, so scroll
        # until the refresh control is inside the window before tapping.
        refresh = _scroll_until_on_screen(
            context, "refreshRequestsButton",
            "the request section could not be scrolled into view")
        ui.tap_node(refresh)
        _scroll_screen_down(context)  # requestList binds rows only in view
        time.sleep(0.6)
        _wait_list_row(
            context, "requestList",
            "Dung's pending request never reached the on-device queue",
            rid="requestName", text="Dung", timeout=30)
        size = _screen_size(context)
        approve = None
        for _ in range(8):
            approve = _list_row(context, "requestList", rid="approveButton")
            if _on_screen(approve, size):
                break
            _scroll_screen_down(context)
            time.sleep(0.6)
        else:
            raise ProductFailure("the approve control could not be scrolled on-screen")
        ui.screenshot(str(row_dir / "invite-approve.png"))
        ui.tap_node(approve)
        _wait_ui_absent(
            context,
            lambda node: _rid_ends(node, "requestName")
            and node.text.strip() == "Dung",
            "the approved request stayed in the queue",
            timeout=30)
        deadline = time.monotonic() + 15
        mine = None
        while time.monotonic() < deadline:
            own = recorder.get(
                f"/api/v1/conversations/{conversation_id}/join-requests/me",
                token=dung_token)
            if own.status == 200:
                mine = own.json() or {}
                if str(mine.get("status") or "") == "APPROVED":
                    break
            time.sleep(0.6)
        if not mine or str(mine.get("status") or "") != "APPROVED":
            raise ProductFailure("Dung's request did not become APPROVED after the UI tap")
        _wait_group_roles(
            recorder, an_token, conversation_id,
            {"An": "ADMIN", "Binh": "MEMBER", "Dung": "MEMBER"},
            "REST members did not gain the approved joiner")
        pending_left = recorder.get(
            f"/api/v1/conversations/{conversation_id}/join-requests",
            token=an_token, params={"status": "PENDING"})
        _expect_status(pending_left, {200}, "pending request list")
        if (pending_left.json() or []):
            raise ProductFailure("a pending request remained after the approval")
        dung_entry = _wait_inbox_entry(
            recorder, dung_token, conversation_id,
            lambda item: item is not None,
            "the approved joiner never received the inbox entry")
        result["approval"] = ("the on-device queue approved Dung: PENDING -> APPROVED, "
                              "member list + joiner inbox agree")

        # === invite return path: deep link -> already-member -> chat =======
        context["adb"].command(
            "shell", "am", "start", "-a", "android.intent.action.VIEW",
            "-d", f"nexachat://join/{token}", PACKAGE, timeout=30)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "statusText")
            and node.text.strip() == "You are already a member.",
            "the invite deep link did not resolve to the already-member state",
            timeout=30)
        join_title = _wait_ui_node(
            context, lambda node: _rid_ends(node, "groupTitle"),
            "the invite join screen did not render a group title")
        if join_title.text.strip() != title_renamed:
            raise ProductFailure(
                f"invite join previewed {join_title.text.strip()!r}, "
                f"not {title_renamed!r}")
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "openChatButton"),
            "the already-member state exposed no Open group chat action")
        ui.screenshot(str(row_dir / "invite-return.png"))
        ui.tap_node(next(node for node in _ui_nodes(context)
                         if _rid_ends(node, "openChatButton")))
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "messageInput"),
            "Open group chat did not return to the group composer",
            timeout=30)
        result["invite_return"] = ("nexachat://join/<token> resolved to this group; "
                                   "Open group chat landed on its composer")

        # === revoke through the UI =========================================
        # The VIEW deep link launched a second MainActivity on top of the
        # running task (standard launchMode), so BACK-walking races two back
        # stacks and can finish through to the launcher — observed live as the
        # row landing on the home screen. Cold-start the app instead, then
        # re-open the group the same way the journey first did:
        # inbox -> conversation row -> chat toolbar -> detail -> invite entry.
        context["adb"].command("shell", "am", "force-stop", PACKAGE, timeout=20)
        context["adb"].command("shell", "am", "start", "-n",
                               f"{PACKAGE}/{MAIN_ACTIVITY}", timeout=30)
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "inboxUserAvatar"),
            "the inbox did not reload after the invite-return deep link",
            timeout=30)
        row_for_revoke = _inbox_conversation_row(context, title_renamed)
        if row_for_revoke is None:
            raise ProductFailure("the group row vanished before the revoke step")
        ui.tap_node(row_for_revoke)
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "messageInput"),
            "reopening the group chat failed before revoke",
            timeout=30)
        toolbar_revoke = _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "chatToolbar") and node.clickable,
            "the group chat toolbar was not clickable before revoke",
            timeout=10)
        ui.tap_node(toolbar_revoke)
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "memberList"),
            "the group detail did not reopen for the revoke step")
        invite_entry2 = _wait_ui_node(
            context, lambda node: _rid_ends(node, "inviteLinkButton"),
            "the invite screen could not be reopened for revoke")
        ui.tap_node(invite_entry2)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "linkStatus")
            and node.text.strip() == "Active invite link",
            "the reopened invite screen did not show the active link")
        revoke = None
        for _ in range(8):
            candidate = next(
                (node for node in _ui_nodes(context)
                 if _rid_ends(node, "revokeButton") and _node_enabled(node)), None)
            if _on_screen(candidate, _screen_size(context)):
                revoke = candidate
                break
            _scroll_screen_down(context)
            time.sleep(0.6)
        if revoke is None:
            raise ProductFailure("the revoke button could not be scrolled on-screen")
        ui.tap_node(revoke)
        _wait_ui_node(
            context, lambda node: node.text.strip() == "Revoke this invite link?",
            "the revoke confirmation did not open")
        _tap_dialog_text(context, "Revoke link", exclude_rid="revokeButton")
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "linkStatus")
            and node.text.strip() == "No active link.",
            "the invite link stayed active after the confirmed revoke",
            timeout=30)
        gone = recorder.get(
            f"/api/v1/conversations/{conversation_id}/invite-link", token=an_token)
        _expect_status(gone, {404}, "invite-link read after revoke")
        dead = recorder.get(f"/api/v1/invite-links/{token}", token=dung_token)
        _expect_status(dead, {404}, "invite preview after revoke")
        link_revoked = True
        result["revoke"] = ("the revoke dialog killed the link: UI no-link state, "
                            "admin read 404, Dung's preview 404")

        # === leave as the last member ======================================
        _remove_member_rest(recorder, an_token, conversation_id, binh_id)
        _remove_member_rest(recorder, an_token, conversation_id, dung_id)
        _wait_group_roles(
            recorder, an_token, conversation_id, {"An": "ADMIN"},
            "REST still listed other members before the leave")
        # Cold-start again: by this point the running app has processed a
        # long Room write backlog (contacts, create, member + invite events,
        # two relaunches), which once deadlocked the post-leave cleanup for
        # minutes; a fresh process binds the member list from a cold fetch
        # instead of a stale render of the removed members.
        context["adb"].command("shell", "am", "force-stop", PACKAGE, timeout=20)
        context["adb"].command("shell", "am", "start", "-n",
                               f"{PACKAGE}/{MAIN_ACTIVITY}", timeout=30)
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "inboxUserAvatar"),
            "the inbox did not reload before the leave step",
            timeout=30)
        group_row = _inbox_conversation_row(context, title_renamed)
        if group_row is None:
            raise ProductFailure("the group row vanished before the leave step")
        ui.tap_node(group_row)
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "messageInput"),
            "reopening the group chat failed before leave",
            timeout=30)
        toolbar2 = _wait_ui_node(
            context, lambda node: _rid_ends(node, "chatToolbar") and node.clickable,
            "the group chat toolbar was not clickable before leave",
            timeout=10)
        ui.tap_node(toolbar2)
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "memberList"),
            "the group detail did not reopen for the leave step")
        deadline = time.monotonic() + _ui_timeout(context)
        while True:
            names = _member_names_ui(context)
            if names == {"An"}:
                break
            if time.monotonic() >= deadline:
                raise ProductFailure(
                    f"member list still shows {sorted(names)!r} before leave")
            time.sleep(0.4)
        leave = _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "leaveGroupButton") and _node_enabled(node),
            "the leave button was not enabled for the last member")
        ui.tap_node(leave)
        _wait_ui_node(
            context, lambda node: node.text.strip() == "Leave this group?",
            "the leave confirmation did not open")
        _tap_dialog_text(context, "Leave group", exclude_rid="leaveGroupButton")
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "inboxUserAvatar"),
            "leaving did not return the device to the inbox",
            timeout=30)
        deadline = time.monotonic() + 30
        while True:
            if _inbox_conversation_row(context, title_renamed) is None:
                break
            if time.monotonic() >= deadline:
                raise ProductFailure("the left group still renders a conversation row")
            time.sleep(0.5)
        ui.screenshot(str(row_dir / "group-left.png"))
        _wait_inbox_entry(
            recorder, an_token, conversation_id,
            lambda item: item is None,
            "An kept the emptied group in her inbox")
        empty_read = recorder.get(
            f"/api/v1/conversations/{conversation_id}/messages", token=an_token,
            params={"limit": 1})
        _expect_status(empty_read, {403},
                       "the last member's read after leaving")
        result["leave"] = ("last member left through the UI; the row vanished from "
                           "the inbox and history reads deny everyone")
        result["summary"] = "all phases passed"
    except Exception as error:
        primary_error = error
    finally:
        cleanup_errors: list[str] = []
        try:
            for _ in range(6):
                nodes = _ui_nodes(context)
                if any(_rid_ends(node, "inboxUserAvatar") for node in nodes):
                    break
                ui.key("KEYCODE_BACK")
                time.sleep(0.5)
        except Exception as error:
            cleanup_errors.append(f"ui restore: {type(error).__name__}")
        if conversation_id:
            try:
                entry = _group_entry(recorder, an_token, conversation_id)
            except Exception as error:
                entry = None
                cleanup_errors.append(f"member read: {type(error).__name__}")
            try:
                if not link_revoked:
                    recorder.delete(
                        f"/api/v1/conversations/{conversation_id}/invite-link",
                        token=an_token)
            except Exception as error:
                cleanup_errors.append(f"invite revoke: {type(error).__name__}")
            try:
                pending = recorder.get(
                    f"/api/v1/conversations/{conversation_id}/join-requests",
                    token=an_token, params={"status": "PENDING"})
                if pending.status == 200:
                    for request in pending.json() or []:
                        request_id = str((request or {}).get("id") or "")
                        if request_id:
                            recorder.post(
                                f"/api/v1/conversations/{conversation_id}"
                                f"/join-requests/{request_id}/reject",
                                token=an_token)
            except Exception as error:
                cleanup_errors.append(f"request sweep: {type(error).__name__}")
            try:
                for member in (entry or {}).get("members") or []:
                    member_id = str(member.get("userId") or "")
                    if member_id and member_id != an_id:
                        removed = recorder.delete(
                            f"/api/v1/conversations/{conversation_id}/members/{member_id}",
                            token=an_token)
                        if removed.status not in (200, 204, 403, 404):
                            cleanup_errors.append(
                                f"member sweep HTTP {removed.status}")
                    elif not member_id:
                        cleanup_errors.append("member sweep: member without userId")
            except Exception as error:
                cleanup_errors.append(f"member sweep: {type(error).__name__}")
            try:
                left = recorder.post(
                    f"/api/v1/conversations/{conversation_id}/leave", token=an_token)
                if left.status not in (200, 204, 400, 403, 404):
                    cleanup_errors.append(f"leave HTTP {left.status}")
            except Exception as error:
                cleanup_errors.append(f"leave: {type(error).__name__}")
        if cleanup_errors:
            context["cleanup_errors"] = cleanup_errors
    if primary_error is not None:
        raise primary_error
    if context.get("cleanup_errors"):
        raise HarnessError("groups-contacts cleanup could not release the fixture")
    result["cleanup"] = ("invite link revoked, requests swept, remaining members "
                         "removed and An left the disposable group")
    return result


# --- polls-reminders ---------------------------------------------------------
# The group board's Reminders/Polls tabs are content-described; both lists are
# resource-id RecyclerViews and every card control is resource-id tagged. Poll
# mutations are REST-only (the server publishes no STOMP frame for polls); the
# reminder due/next transition is driven by reminder_clock's DB fixture, which
# pulls the persisted remind_at under the real scheduler instead of faking a
# device clock.
_BOARD_MENU_ITEM = ("Group board",)
_BOARD_TAB_REMINDERS = "Reminders"
_BOARD_TAB_POLLS = "Polls"
_POLL_PEER_REFRESH_TIMEOUT = 25.0  # PollFragment re-reads the board every ~15s
_REMINDER_REPEAT_LABEL = {"NONE": "No repeat", "DAILY": "Daily",
                          "WEEKLY": "Weekly", "MONTHLY": "Monthly"}
_VOTE_COUNT_RE = re.compile(r"^(\d+)\s+votes?\b")
_VOTER_COUNT_RE = re.compile(r"^(\d+)\s+voters?\b")


def _import_reminder_clock() -> Any:
    if str(E2E_DIR) not in sys.path:
        sys.path.insert(0, str(E2E_DIR))
    return importlib.import_module("reminder_clock")


def _reminder_clock(context: dict[str, Any]) -> Any:
    """The DB fixture for the stack under test.

    The device-E2E jar serves its own database on the shared postgres
    container — which is not necessarily the default `nexachat` schema, so the
    caller overrides it via context["reminder_db"] or the
    NEXACHAT_E2E_REMINDER_DB env var. reminder_clock's target validation keeps
    the name inside the local nexa* family. An unusable target (bad env var,
    missing helper module) is a harness/environment failure, never a product
    verdict."""
    override = context.get("reminder_clock")
    if override is not None:
        return override
    try:
        module = _import_reminder_clock()
        db_name = (context.get("reminder_db")
                   or os.environ.get("NEXACHAT_E2E_REMINDER_DB")
                   or module.DEFAULT_DB_NAME)
        return module.ReminderClock(db_name=db_name)
    except Exception as error:
        raise EnvironmentFailure(
            "the reminder_clock DB fixture is not usable (set "
            "NEXACHAT_E2E_REMINDER_DB to the stack's database): "
            f"{type(error).__name__}: {error}") from error


def _is_reminder_clock_error(error: Exception, class_name: str) -> bool:
    """True when the DB fixture raised one of reminder_clock's typed errors.

    The helper module owns the taxonomy (ReminderNotFoundError,
    PsqlExecutionError, ...) and speaks to Postgres only through
    machine-readable `psql -tA -F` output, so the row classifies failures on
    the raised class instead of matching raw psql output text."""
    try:
        expected = getattr(_import_reminder_clock(), class_name, None)
    except Exception:
        return False
    return isinstance(expected, type) and isinstance(error, expected)


_BOARD_MONTH_ABBR = ("Jan", "Feb", "Mar", "Apr", "May", "Jun",
                     "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")


def _device_local_now(context: dict[str, Any]) -> datetime:
    out = context["adb"].command(
        "shell", "date", "+%Y-%m-%dT%H:%M:%S%z", timeout=15)
    return datetime.strptime(out.strip(), "%Y-%m-%dT%H:%M:%S%z")


def _board_date_label(day: datetime) -> str:
    return f"{_BOARD_MONTH_ABBR[day.month - 1]} {day.day}, {day.year}"


def _iso_instant(value: str) -> datetime | None:
    """Parse the server's ISO-8601 instant (variable fraction digits, Z)."""
    try:
        return datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except (ValueError, AttributeError):
        return None


def _instant_after(value: str, earlier: str) -> bool:
    """True when `value` parses to a strictly later instant than `earlier`."""
    parsed_value = _iso_instant(value)
    parsed_earlier = _iso_instant(earlier)
    return parsed_value is not None and parsed_earlier is not None \
        and parsed_value > parsed_earlier


def _pull_refresh_inbox(context: dict[str, Any]) -> None:
    """Drag down inside the conversation list to fire swipeRefresh.

    The authoritative inbox only re-fetches on launch or a user refresh, so a
    group created over REST may wait on a slow STOMP event; the pull-down is
    the same gesture a user would make."""
    node = next((item for item in _ui_nodes(context)
                 if _rid_ends(item, "conversationList")), None)
    if node is None or not node.box:
        return
    left, top, right, bottom = node.box
    cx = (left + right) // 2
    context["device"].swipe(cx, top + 30, cx, min(bottom - 10, top + 170), 350)


def _open_group_board(context: dict[str, Any]) -> None:
    """Chat overflow -> 'Group board' (below the fold) -> board tab strip."""
    _open_chat_menu(context, _BOARD_MENU_ITEM)
    _wait_ui_node(
        context, lambda node: _rid_ends(node, "boardTabs"),
        "the group board did not open", timeout=30)


def _board_tab(context: dict[str, Any], label: str) -> None:
    """Select a board tab by its content-desc; a selected tab is a no-op tap."""
    node = _wait_ui_node(
        context,
        lambda item: item.desc.strip() == label and bool(item.box),
        f"the board tab {label!r} was not rendered")
    context["device"].tap_node(node)
    time.sleep(0.6)


def _back_to_chat(context: dict[str, Any]) -> None:
    """Leave the board so re-entering re-fetches the lists (onResume refresh)."""
    context["device"].key("KEYCODE_BACK")
    _wait_ui_node(
        context, lambda node: _rid_ends(node, "moreButton"),
        "leaving the board did not return to the group chat", timeout=15)


def _popup_item(context: dict[str, Any], label: str, what: str) -> None:
    """Tap a PopupMenu row by exact text inside the generic 'title'/'text1' rows.

    The reminder card menu and poll manage menu render platform rows whose
    resource ids are generic (title/text1); their exact text is unique inside
    the open popup, while the same strings on the board itself carry
    feature-specific ids and never match this predicate.
    """
    node = _wait_ui_node(
        context,
        lambda item: item.text.strip() == label
        and (_rid_ends(item, "title") or _rid_ends(item, "text1")),
        what)
    context["device"].tap_node(node)


def _type_sheet_field(context: dict[str, Any], rid: str, text: str,
                      index: int = 0, replace_len: int = 0) -> None:
    """Tap a sheet EditText, type, then BACK away the opened IME.

    The Material sheets squeeze their scroll body to a strip while the IME is
    up, so the next field is unreachable until the keyboard is dismissed; BACK
    closes only the IME while text input was genuinely in flight.
    """
    ui = context["device"]
    nodes = sorted(
        (node for node in _ui_nodes(context) if _rid_ends(node, rid) and node.box),
        key=lambda node: (node.box[1], node.box[0]))
    if index >= len(nodes):
        raise ProductFailure(f"sheet field {rid}[{index}] was not rendered")
    ui.tap_node(nodes[index])
    time.sleep(0.5)
    if replace_len:
        ui.move_end()
        ui.delete_chars(replace_len)
    ui.type_text(text)
    time.sleep(0.4)
    ui.key("KEYCODE_BACK")
    time.sleep(0.6)


def _poll_page(recorder: RecordingClient, token: str, conversation_id: str) -> list[dict[str, Any]]:
    response = recorder.get(
        f"/api/v1/conversations/{conversation_id}/polls", token=token,
        params={"limit": 50})
    _expect_status(response, {200}, "poll list")
    return list((response.json() or {}).get("items") or [])


def _poll_rest(recorder: RecordingClient, token: str, conversation_id: str,
               poll_id: str) -> dict[str, Any] | None:
    return next((item for item in _poll_page(recorder, token, conversation_id)
                 if str(item.get("id") or "") == poll_id), None)


def _wait_poll_rest(recorder: RecordingClient, token: str, conversation_id: str,
                    poll_id: str, predicate: Callable[[dict[str, Any] | None], bool],
                    what: str, timeout: float = 15.0) -> dict[str, Any] | None:
    deadline = time.monotonic() + timeout
    while True:
        poll = _poll_rest(recorder, token, conversation_id, poll_id)
        if predicate(poll):
            return poll
        if time.monotonic() >= deadline:
            raise ProductFailure(what)
        time.sleep(0.6)


def _poll_option_rest(poll: dict[str, Any], label: str) -> dict[str, Any] | None:
    return next((opt for opt in (poll or {}).get("options") or []
                 if str(opt.get("label") or "") == label), None)


def _poll_counts_rest(poll: dict[str, Any]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for opt in (poll or {}).get("options") or []:
        label = str(opt.get("label") or "")
        voters = opt.get("voterIds")
        count = opt.get("voteCount")
        counts[label] = int(count) if count is not None else len(voters or [])
    return counts


def _reminders_rest(recorder: RecordingClient, token: str,
                    conversation_id: str) -> list[dict[str, Any]]:
    response = recorder.get(
        f"/api/v1/conversations/{conversation_id}/reminders", token=token)
    _expect_status(response, {200}, "reminder list")
    return list(response.json() or [])


def _reminder_rest(recorder: RecordingClient, token: str, conversation_id: str,
                   title: str) -> dict[str, Any] | None:
    return next((item for item in _reminders_rest(recorder, token, conversation_id)
                 if str(item.get("title") or "") == title), None)


def _wait_reminder_rest(recorder: RecordingClient, token: str, conversation_id: str,
                        title: str, predicate: Callable[[dict[str, Any] | None], bool],
                        what: str, timeout: float = 15.0) -> dict[str, Any] | None:
    deadline = time.monotonic() + timeout
    while True:
        reminder = _reminder_rest(recorder, token, conversation_id, title)
        if predicate(reminder):
            return reminder
        if time.monotonic() >= deadline:
            raise ProductFailure(what)
        time.sleep(0.6)


def _poll_option_counts_ui(context: dict[str, Any]) -> dict[str, int]:
    """label -> rendered optionCount inside the card's pollOptions block."""
    counts: dict[str, int] = {}
    current: str | None = None
    for node in _list_children(context, "pollOptions"):
        if _rid_ends(node, "optionControl"):
            current = node.text.strip()
        elif _rid_ends(node, "optionCount") and current is not None:
            match = _VOTE_COUNT_RE.match(node.text.strip())
            counts[current] = int(match.group(1)) if match else -1
            current = None
    return counts


def _poll_voter_count_ui(context: dict[str, Any]) -> int | None:
    node = next((item for item in _list_children(context, "pollList")
                 if _rid_ends(item, "pollVoterCount")), None)
    if node is None:
        return None
    match = _VOTER_COUNT_RE.match(node.text.strip())
    return int(match.group(1)) if match else None


def _wait_poll_counts_ui(context: dict[str, Any], expected: dict[str, int],
                         voters: int, what: str, timeout: float = 15.0) -> None:
    deadline = time.monotonic() + timeout
    while True:
        if (_poll_option_counts_ui(context) == expected
                and _poll_voter_count_ui(context) == voters):
            return
        if time.monotonic() >= deadline:
            raise ProductFailure(
                f"{what}: option counts {_poll_option_counts_ui(context)!r}, "
                f"voter count {_poll_voter_count_ui(context)!r}")
        time.sleep(0.5)


def _tap_poll_option(context: dict[str, Any], label: str) -> None:
    node = _wait_list_row(
        context, "pollOptions",
        f"the option control {label!r} was not on the poll card",
        rid="optionControl", text=label)
    context["device"].tap_node(node)


def _poll_meta_ui(context: dict[str, Any]) -> str:
    node = next((item for item in _list_children(context, "pollList")
                 if _rid_ends(item, "pollMeta")), None)
    return node.text.strip() if node is not None else ""


def run_polls_reminders(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """Group-board polls + reminders on a disposable An+Binh group.

    Every mutation is driven through the Android board UI; peer truth comes
    from Binh's REST client. The reminder due/next transition is pulled under
    the real scheduler through reminder_clock's DB fixture — the persisted
    remind_at is moved due, the production sweep claims and fires it, and the
    board is re-entered so its onResume fetch shows the post-fire list. On the
    local stack the push gateway is a mock, so no shade notification is
    asserted; the board list and REST are the persisted truth this row proves.
    """
    _campaign_serial(context)
    _campaign_base_url(context)
    _prepare_seeded_an(context)
    api, _adb_ui, _patch = context["helpers"]
    recorder: RecordingClient = context["client"]
    ui = context["device"]
    an = _actor_session(api, recorder, api.USER_A["phone"])
    binh = _actor_session(api, recorder, api.USER_C["phone"])
    an_token = an["accessToken"]
    binh_token = binh["accessToken"]
    binh_id = str((binh.get("user") or {}).get("id") or "")
    an_id = str((an.get("user") or {}).get("id") or "")
    if not binh_id or not an_id:
        raise EnvironmentFailure("seed actor sessions did not expose user ids")
    clock = _reminder_clock(context)
    row_dir = Path(context["row_dir"])
    tag = uuid.uuid4().hex[:8]
    group_title = f"sj-pb-{tag}"
    poll_question = f"sj-poll-{tag}"
    option_a = f"sj-a-{tag}"
    option_b = f"sj-b-{tag}"
    reminder_title = f"sj-rem-{tag}"
    reminder_title2 = f"{reminder_title}-e"
    daily_title = f"sj-remd-{tag}"
    conversation_id = ""
    poll_id = ""
    created_reminder_ids: set[str] = set()
    cleanup_errors: list[str] = []
    result: dict[str, Any] = {}
    primary_error: Exception | None = None
    try:
        created = recorder.post(
            "/api/v1/conversations/group", token=an_token,
            body={"title": group_title, "memberIds": [binh_id]})
        _expect_status(created, {200, 201}, "disposable group create")
        conversation_id = str((created.json() or {}).get("id") or "")
        if not conversation_id:
            raise EnvironmentFailure("group create did not return an id")
        _wait_group_roles(
            recorder, an_token, conversation_id,
            {"An": "ADMIN", "Binh": "MEMBER"},
            "the seeded group's members disagree with the REST create")

        # The REST-created group reaches the device inbox via the next
        # authoritative fetch; pull-refresh the list until it does.
        group_row = None
        deadline = time.monotonic() + 45
        next_refresh = time.monotonic()
        while True:
            group_row = _inbox_conversation_row(context, group_title)
            if group_row is not None:
                break
            if time.monotonic() >= deadline:
                raise ProductFailure("the seeded group row never reached the inbox")
            if time.monotonic() >= next_refresh:
                _pull_refresh_inbox(context)
                next_refresh = time.monotonic() + 8
            time.sleep(0.5)
        ui.tap_node(group_row)
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "moreButton"),
            "the seeded group chat did not open", timeout=30)
        _open_group_board(context)
        ui.screenshot(str(row_dir / "board-reminders.png"))

        # === poll: create through the sheet ================================
        _board_tab(context, _BOARD_TAB_POLLS)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "pollEmpty") or _rid_ends(node, "pollList"),
            "the polls tab did not render its list surface")
        ui.screenshot(str(row_dir / "board-polls.png"))
        add_poll = _wait_ui_node(
            context, lambda node: _rid_ends(node, "pollAdd"),
            "the New poll control was not exposed")
        ui.tap_node(add_poll)
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "pollQuestionInput"),
            "the poll create sheet did not open")
        _type_sheet_field(context, "pollQuestionInput", poll_question)
        _type_sheet_field(context, "optionInput", option_a, index=0)
        _type_sheet_field(context, "optionInput", option_b, index=1)
        submit = _wait_ui_node(
            context, lambda node: _rid_ends(node, "pollCreateSubmit"),
            "the create sheet lost its submit control")
        ui.tap_node(submit)
        _wait_ui_absent(
            context, lambda node: _rid_ends(node, "pollQuestionInput"),
            "the poll create sheet did not close")
        _wait_list_row(
            context, "pollList",
            "the created poll never rendered on the board",
            rid="pollQuestion", text=poll_question, timeout=30)
        ui.screenshot(str(row_dir / "poll-created.png"))
        # Exactly one poll exists; identify it by question through Binh's list.
        deadline = time.monotonic() + 15
        peer_poll: dict[str, Any] | None = None
        while True:
            peer_poll = next(
                (item for item in _poll_page(recorder, binh_token, conversation_id)
                 if str(item.get("question") or "") == poll_question), None)
            if peer_poll is not None:
                break
            if time.monotonic() >= deadline:
                raise ProductFailure(
                    "Binh's REST list did not carry the created poll")
            time.sleep(0.6)
        poll_id = str(peer_poll.get("id") or "")
        if not poll_id:
            raise ProductFailure("the created poll did not expose an id")
        version_0 = int(peer_poll.get("version") or 0)
        if str(peer_poll.get("status") or "") != "OPEN":
            raise ProductFailure("the created poll is not OPEN for the peer")
        if int(peer_poll.get("voterCount") or 0) != 0:
            raise ProductFailure("the new poll already carried a voter")
        opt_a_rest = _poll_option_rest(peer_poll, option_a)
        opt_b_rest = _poll_option_rest(peer_poll, option_b)
        if opt_a_rest is None or opt_b_rest is None:
            raise ProductFailure("the REST poll options disagree with the sheet")
        result["poll_create"] = ("create sheet landed the poll on the board; "
                                 "peer REST shows it OPEN with both options")

        # === poll: An votes on device, then changes the selection ==========
        _tap_poll_option(context, option_a)
        vote_button = _wait_list_row(
            context, "pollList",
            "the Vote control was not exposed on the poll card",
            rid="pollVote")
        ui.tap_node(vote_button)
        _wait_poll_counts_ui(
            context, {option_a: 1, option_b: 0}, 1,
            "the device vote did not land on the board")
        an_poll = _wait_poll_rest(
            recorder, an_token, conversation_id, poll_id,
            lambda poll: poll is not None
            and [str(v) for v in (poll.get("myOptionIds") or [])]
            == [str(opt_a_rest.get("id"))],
            "An's REST myOptionIds did not reflect the device vote")
        version_1 = int(an_poll.get("version") or 0)
        if not version_1 > version_0:
            raise ProductFailure("the poll version did not rise after the first vote")
        if _poll_counts_rest(an_poll) != {option_a: 1, option_b: 0}:
            raise ProductFailure("REST option counts disagree with the board")
        ui.screenshot(str(row_dir / "poll-voted.png"))

        _tap_poll_option(context, option_b)
        vote_button2 = _wait_list_row(
            context, "pollList",
            "the Vote control vanished before the change vote",
            rid="pollVote")
        ui.tap_node(vote_button2)
        _wait_poll_counts_ui(
            context, {option_a: 0, option_b: 1}, 1,
            "the changed vote did not move An's selection on the board")
        changed_poll = _wait_poll_rest(
            recorder, an_token, conversation_id, poll_id,
            lambda poll: poll is not None
            and [str(v) for v in (poll.get("myOptionIds") or [])]
            == [str(opt_b_rest.get("id"))],
            "An's REST myOptionIds did not follow the changed vote")
        version_2 = int(changed_poll.get("version") or 0)
        if not version_2 > version_1:
            raise ProductFailure("the poll version did not rise after the change vote")
        result["poll_vote"] = ("device vote + change landed on the board and "
                               "REST (version rose twice, myOptionIds agree)")

        # === poll: Binh votes over REST; the board refreshes ================
        binh_vote = recorder.put(
            f"/api/v1/conversations/{conversation_id}/polls/{poll_id}/vote",
            token=binh_token, body={"optionIds": [opt_a_rest.get("id")]})
        _expect_status(binh_vote, {200}, "Binh's REST vote")
        _wait_poll_counts_ui(
            context, {option_a: 1, option_b: 1}, 2,
            "Binh's REST vote never rendered on the device board",
            timeout=_POLL_PEER_REFRESH_TIMEOUT)
        peer_poll2 = _wait_poll_rest(
            recorder, binh_token, conversation_id, poll_id,
            lambda poll: poll is not None
            and int(poll.get("voterCount") or 0) == 2,
            "peer REST did not reach two voters")
        version_3 = int(peer_poll2.get("version") or 0)
        if not version_3 > version_2:
            raise ProductFailure("the poll version did not rise after Binh's vote")
        if [str(v) for v in (peer_poll2.get("myOptionIds") or [])] != [str(opt_a_rest.get("id"))]:
            raise ProductFailure("Binh's myOptionIds did not record his vote")
        result["poll_peer"] = ("Binh's REST vote rendered on the board inside the "
                               "refresh interval; REST shows 2 voters / counts 1+1")

        # === poll: close through manage =====================================
        manage = _wait_list_row(
            context, "pollList",
            "the poll manage control was not exposed",
            rid="pollManage")
        ui.tap_node(manage)
        _popup_item(context, "Close poll", "the manage menu did not offer Close poll")
        _wait_ui_node(
            context,
            lambda node: node.text.strip().startswith("Close this poll?"),
            "the close confirmation did not open")
        _tap_dialog_text(context, "Close poll")
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "pollMeta")
            and node.text.strip().startswith("Closed"),
            "the board did not flip the poll to Closed", timeout=30)
        _wait_ui_absent(
            context, lambda node: _rid_ends(node, "pollVote"),
            "the closed poll still exposes a Vote control")
        ui.screenshot(str(row_dir / "poll-closed.png"))
        closed_poll = _wait_poll_rest(
            recorder, binh_token, conversation_id, poll_id,
            lambda poll: poll is not None
            and str(poll.get("status") or "") == "CLOSED",
            "REST did not report the poll CLOSED after the UI close")
        if not closed_poll.get("closedAt"):
            raise ProductFailure("the closed poll has no closedAt")
        version_4 = int(closed_poll.get("version") or 0)
        if not version_4 > version_3:
            raise ProductFailure("the poll version did not rise on close")
        late_vote = recorder.put(
            f"/api/v1/conversations/{conversation_id}/polls/{poll_id}/vote",
            token=binh_token, body={"optionIds": [opt_b_rest.get("id")]})
        if late_vote.status in (200, 201):
            raise ProductFailure("a peer vote was accepted on a closed poll")
        changes = recorder.get(
            f"/api/v1/conversations/{conversation_id}/polls/changes",
            token=binh_token, params={"since": 0, "limit": 100})
        _expect_status(changes, {200}, "poll changes feed")
        versions = [int(item.get("version") or 0)
                    for item in (changes.json() or {}).get("items") or []
                    if str(item.get("pollId") or "") == poll_id]
        if not versions or versions[-1] != version_4 or versions != sorted(versions):
            raise ProductFailure(
                f"the changes feed does not end at the closed version: {versions!r}")
        result["poll_close"] = ("manage -> close flipped the board + REST; the peer "
                                "vote is refused and the version trail is monotonic")

        # === reminder: create + edit through the board =====================
        _board_tab(context, _BOARD_TAB_REMINDERS)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "reminderEmpty")
            or _rid_ends(node, "reminderList"),
            "the reminders tab did not render its list surface")
        add_reminder = _wait_ui_node(
            context, lambda node: _rid_ends(node, "reminderAdd"),
            "the New reminder control was not exposed")
        ui.tap_node(add_reminder)
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "reminderEditorTitleInput"),
            "the reminder editor did not open")
        _type_sheet_field(context, "reminderEditorTitleInput", reminder_title)
        save = _wait_ui_node(
            context, lambda node: _rid_ends(node, "reminderEditorSave"),
            "the reminder editor lost its Save control")
        ui.tap_node(save)
        _wait_list_row(
            context, "reminderList",
            "the created reminder never rendered on the board",
            rid="reminderTitle", text=reminder_title, timeout=30)
        first_rest = _wait_reminder_rest(
            recorder, an_token, conversation_id, reminder_title,
            lambda item: item is not None,
            "the created reminder is absent from REST")
        reminder_id = str(first_rest.get("id") or "")
        if not reminder_id:
            raise ProductFailure("the created reminder did not expose an id")
        created_reminder_ids.add(reminder_id)
        first_remind_at = str(first_rest.get("remindAt") or "")
        if str(first_rest.get("repeat") or "NONE") != "NONE":
            raise ProductFailure("the new reminder is not a one-off")
        ui.screenshot(str(row_dir / "reminder-created.png"))
        result["reminder_create"] = ("editor created the reminder; board card and "
                                     "REST row agree (one-off, future remindAt)")

        # Edit the title through the card menu; remindAt/repeat are resent.
        menu = _wait_list_row(
            context, "reminderList",
            "the reminder card did not expose its options menu",
            rid="reminderMenu")
        ui.tap_node(menu)
        _popup_item(context, "Edit", "the reminder menu did not offer Edit")
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "reminderEditorTitleInput"),
            "the reminder editor did not reopen for edit")
        _type_sheet_field(
            context, "reminderEditorTitleInput", reminder_title2,
            replace_len=len(reminder_title))
        save2 = _wait_ui_node(
            context, lambda node: _rid_ends(node, "reminderEditorSave"),
            "the reminder editor lost its Save control on edit")
        ui.tap_node(save2)
        _wait_list_row(
            context, "reminderList",
            "the edited reminder title never rendered on the board",
            rid="reminderTitle", text=reminder_title2, timeout=30)
        edited_rest = _wait_reminder_rest(
            recorder, an_token, conversation_id, reminder_title2,
            lambda item: item is not None,
            "the edited reminder title never reached REST")
        if str(edited_rest.get("id") or "") != reminder_id:
            raise ProductFailure("the reminder edit created a new row instead of patching")
        if str(edited_rest.get("remindAt") or "") != first_remind_at:
            raise ProductFailure("a title-only edit changed remindAt")
        ui.screenshot(str(row_dir / "reminder-edited.png"))
        result["reminder_edit"] = ("card menu -> edit patched the title; board and "
                                   "REST agree and remindAt was unchanged")

        # === one-off fires via reminder_clock ==============================
        # The fixture rewrites the persisted remind_at under the production
        # scheduler; the next <=1s sweep claims and completes it.
        try:
            due = clock.make_due(reminder_id, minutes_ago=1)
        except Exception as error:
            hint = ""
            if _is_reminder_clock_error(error, "ReminderNotFoundError"):
                hint = (" (reminder row missing from the target db? set "
                        "NEXACHAT_E2E_REMINDER_DB to the stack's database)")
            elif _is_reminder_clock_error(error, "PsqlExecutionError"):
                hint = (" (psql via `docker exec` failed or is missing; check "
                        "the stack's DB container)")
            raise EnvironmentFailure(
                f"reminder_clock could not pull the row due: {error}{hint}") from error
        if due.get("status") not in ("DUE", "CLAIMED", "FIRED"):
            raise EnvironmentFailure(f"make_due left the reminder {due.get('status')!r}")
        try:
            # wait_fired returns on the claim lease; the REST disappearance
            # below is the authoritative 'fired and completed' signal.
            clock.wait_fired(reminder_id, timeout=20)
        except Exception as error:
            raise EnvironmentFailure(
                f"the one-off reminder never fired: {error}") from error
        _wait_reminder_rest(
            recorder, an_token, conversation_id, reminder_title2,
            lambda item: item is None,
            "the fired one-off stayed in the REST list", timeout=20)
        _back_to_chat(context)
        _open_group_board(context)
        _board_tab(context, _BOARD_TAB_REMINDERS)
        _wait_list_row(
            context, "reminderList",
            "the fired one-off still renders on the board",
            present=False, rid="reminderTitle", text=reminder_title2, timeout=15)
        ui.screenshot(str(row_dir / "reminder-fired.png"))
        result["reminder_fired"] = ("reminder_clock fired the persisted occurrence; "
                                    "board and REST dropped the one-off")

        # === daily reminder: create, fire, next occurrence, delete ==========
        add_daily = _wait_ui_node(
            context, lambda node: _rid_ends(node, "reminderAdd"),
            "the New reminder control was not exposed for the daily row")
        ui.tap_node(add_daily)
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "reminderEditorTitleInput"),
            "the reminder editor did not open for the daily row")
        _type_sheet_field(context, "reminderEditorTitleInput", daily_title)
        repeat_row = _wait_ui_node(
            context, lambda node: _rid_ends(node, "reminderEditorRepeat"),
            "the repeat field was not exposed")
        ui.tap_node(repeat_row)
        _tap_dialog_item(context, (_REMINDER_REPEAT_LABEL["DAILY"],))
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "reminderEditorRepeat")
            and "Daily" in node.text,
            "the repeat field did not switch to Daily")
        save3 = _wait_ui_node(
            context, lambda node: _rid_ends(node, "reminderEditorSave"),
            "the reminder editor lost its Save control for the daily row")
        ui.tap_node(save3)
        _wait_list_row(
            context, "reminderList",
            "the daily reminder never rendered on the board",
            rid="reminderTitle", text=daily_title, timeout=30)
        daily_rest = _wait_reminder_rest(
            recorder, an_token, conversation_id, daily_title,
            lambda item: item is not None,
            "the daily reminder is absent from REST")
        daily_id = str(daily_rest.get("id") or "")
        if not daily_id:
            raise ProductFailure("the daily reminder did not expose an id")
        created_reminder_ids.add(daily_id)
        if str(daily_rest.get("repeat") or "") != "DAILY":
            raise ProductFailure("the repeat picker did not persist DAILY")
        ui.screenshot(str(row_dir / "reminder-daily.png"))

        # Move remindAt just ahead through the real PATCH path so the row's
        # anchor lands in the near future; the production scheduler then fires
        # the occurrence and nextAfter advances it exactly one day. A DB-side
        # make_due would instead reset remindAt back to the still-future
        # anchor, which cannot prove the next-occurrence transition.
        near = (datetime.now(timezone.utc) + timedelta(seconds=75)
                ).strftime("%Y-%m-%dT%H:%M:%SZ")
        patched = recorder.patch(
            f"/api/v1/conversations/{conversation_id}/reminders/{daily_id}",
            token=an_token, body={"remindAt": near})
        _expect_status(patched, {200}, "daily remindAt PATCH")
        patched_at = str((patched.json() or {}).get("remindAt") or near)
        try:
            clock.wait_fired(daily_id, timeout=120)
        except Exception as error:
            raise EnvironmentFailure(
                f"the daily occurrence never fired: {error}") from error
        next_rest = _wait_reminder_rest(
            recorder, an_token, conversation_id, daily_title,
            lambda item: item is not None
            and _instant_after(str(item.get("remindAt") or ""), patched_at),
            "the daily reminder did not advance to its next occurrence",
            timeout=20)
        _back_to_chat(context)
        _open_group_board(context)
        _board_tab(context, _BOARD_TAB_REMINDERS)
        _wait_list_row(
            context, "reminderList",
            "the daily reminder vanished from the board after firing",
            rid="reminderTitle", text=daily_title, timeout=15)
        time_node = _list_row(
            context, "reminderList", rid="reminderTime")
        if time_node is None or not time_node.text.strip():
            raise ProductFailure("the daily card lost its next-occurrence time")
        # The board must render the REST-persisted next occurrence: parse the
        # server's remindAt and label it in the device timezone.
        next_at = _iso_instant(str(next_rest.get("remindAt") or ""))
        device_tz = _device_local_now(context).tzinfo
        if next_at is None or device_tz is None:
            raise EnvironmentFailure("could not resolve the next occurrence's timezone")
        next_day = _board_date_label(next_at.astimezone(device_tz))
        if next_day not in time_node.text:
            raise ProductFailure(
                f"the daily card shows {time_node.text.strip()!r}, "
                f"not the persisted next occurrence {next_day!r}")
        result["reminder_daily"] = (
            "the fired DAILY reminder advanced in REST and the board card "
            "shows the next day's occurrence")

        menu2 = _wait_list_row(
            context, "reminderList",
            "the daily card did not expose its options menu",
            rid="reminderMenu")
        ui.tap_node(menu2)
        _popup_item(context, "Delete", "the reminder menu did not offer Delete")
        _wait_ui_node(
            context,
            lambda node: node.text.strip() == "Delete this reminder?",
            "the delete confirmation did not open")
        _tap_dialog_text(context, "Delete")
        _wait_list_row(
            context, "reminderList",
            "the deleted reminder stayed on the board",
            present=False, rid="reminderTitle", text=daily_title, timeout=20)
        _wait_reminder_rest(
            recorder, an_token, conversation_id, daily_title,
            lambda item: item is None,
            "the deleted reminder stayed in REST", timeout=15)
        result["reminder_delete"] = ("card menu -> delete removed the daily "
                                     "reminder from board and REST")
        result["summary"] = "all phases passed"
    except Exception as error:
        primary_error = error
    finally:
        try:
            for _ in range(6):
                nodes = _ui_nodes(context)
                if any(_rid_ends(node, "inboxUserAvatar") for node in nodes):
                    break
                ui.key("KEYCODE_BACK")
                time.sleep(0.5)
        except Exception as error:
            cleanup_errors.append(f"ui restore: {type(error).__name__}")
        if conversation_id:
            try:
                if poll_id:
                    deleted = recorder.delete(
                        f"/api/v1/conversations/{conversation_id}/polls/{poll_id}",
                        token=an_token)
                    if deleted.status not in (200, 204, 404):
                        cleanup_errors.append(f"poll delete HTTP {deleted.status}")
            except Exception as error:
                cleanup_errors.append(f"poll delete: {type(error).__name__}")
            try:
                # Every reminder this conversation carries is this attempt's
                # fixture (the group is disposable): delete tracked ids plus a
                # prefix sweep for any row whose id was never captured.
                sweep_ids = set(created_reminder_ids)
                try:
                    for item in _reminders_rest(recorder, an_token, conversation_id):
                        if str(item.get("title") or "").startswith("sj-rem"):
                            sweep_ids.add(str(item.get("id") or ""))
                except Exception:
                    pass
                for rid in sorted(sweep_ids):
                    if not rid:
                        continue
                    deleted = recorder.delete(
                        f"/api/v1/conversations/{conversation_id}/reminders/{rid}",
                        token=an_token)
                    if deleted.status not in (200, 204, 404):
                        cleanup_errors.append(f"reminder {rid} delete HTTP {deleted.status}")
            except Exception as error:
                cleanup_errors.append(f"reminder sweep: {type(error).__name__}")
            try:
                _remove_member_rest(recorder, an_token, conversation_id, binh_id)
            except Exception as error:
                cleanup_errors.append(f"member sweep: {type(error).__name__}")
            try:
                left = recorder.post(
                    f"/api/v1/conversations/{conversation_id}/leave", token=an_token)
                if left.status not in (200, 204, 400, 403, 404):
                    cleanup_errors.append(f"leave HTTP {left.status}")
            except Exception as error:
                cleanup_errors.append(f"leave: {type(error).__name__}")
        if cleanup_errors:
            context["cleanup_errors"] = cleanup_errors
    if primary_error is not None:
        raise primary_error
    if context.get("cleanup_errors"):
        raise HarnessError("polls-reminders cleanup could not release the fixture")
    result["cleanup"] = ("row poll/reminders removed, Binh removed and An left "
                         "the disposable group")
    return result


# --- offline-sync -------------------------------------------------------------
# O1/O2: the device queues text sends plus a reaction while the radios are off,
# the queued work survives process death and flushes exactly once on reconnect,
# the bounded watermark sync reconciles a >100-conversation set plus a burst of
# peer edits/recalls across a cold restart, and a real A->B->A sign-in switch
# re-establishes each caller's truth.

_OFFLINE_GROUP_COUNT = 105      # pushes the caller's conversation set past 100
_OFFLINE_SEEN_MIN = 100         # rendered rows the paged inbox walk must surface
_OFFLINE_FIXTURE_EACH = 30      # 30 edits + 30 recalls = 60 reconciled changes
# Each STOMP MessageCreated frame also queues a full page-1 inbox refresh on
# the device; a ~2 msg/s REST burst serialized ~60 refreshes and pegged the
# debug app at >130% CPU until the system showed its ANR dialog (run-6
# attempt-1 logcat). ~1 msg/s stays far under the write rate bucket while
# letting each refresh finish before the next push lands.
_OFFLINE_SEND_GAP_S = 1.2       # keeps fixture sends under the write rate bucket
# OkHttp Level.BASIC logs "--> POST <url> (<bytes>-byte body)"; ~100 watermarks
# cannot fit inside a body smaller than this floor.
_OFFLINE_SYNC_POST_RE = re.compile(r"--> POST \S+/api/v1/sync")
_OFFLINE_SYNC_BYTES_RE = re.compile(r"--> POST \S+/api/v1/sync[^\n]*?\((\d+)-byte body\)")
_OFFLINE_FULL_BATCH_BYTES = 3000
_OFFLINE_SIGN_OUT_LABELS = ("Sign out", "Đăng xuất")
_OFFLINE_RETRY_LABELS = ("Retry", "Thử lại")
_OFFLINE_SYNC_POLL_S = 180.0
# The tail-edit assertion kept dying to the silent-pop/re-tap race inside its
# 60s budget on the bloated e2e dataset, with no artifact separating "still
# settling" from "never applied" (run-51 advisory probe). Widening the window
# distinguishes a budget race from a reconcile gap — a passing run is
# unaffected because the wait returns on the first match.
_OFFLINE_TAIL_EDIT_WAIT_S = 300.0
_OFFLINE_ROW_SWEEP_LIMIT = 56  # ~1-viewport page-downs per inbox row scan cycle


# --- media upload slice --------------------------------------------------------
# The 'media' row stays BLOCKED_CAPABILITY by design. Two slices can never be
# proven on an emulator and are deliberately NOT driven here:
#   - voice recording/playback: the composer micButton -> recordingOverlay ->
#     MediaViewer audio path needs real hardware capture/render an emulator
#     cannot provide (negative scope; see _MEDIA_SLICE_UNPROVEN);
#   - pick completion: tapping the app's own attach-sheet rows delegates to
#     SYSTEM pickers (DocumentsUI for OpenDocument, PhotoPicker for
#     PickVisualMedia) whose surface is owned by the OS -- uiautomator cannot
#     reliably select a file in them, so the in-app upload pipeline
#     (ImageCompressor re-encode, upload progress UI, 'no stuck upload state')
#     stays unproven.
# What the slice DOES prove with evidence (media-slice.json):
#   1. push: deterministic media_oracle fixtures land on the device unchanged
#      (on-device sha256sum, adb-pull fallback);
#   2. attach: the app's composer attach sheet opens and both rows delegate to
#      the system pickers (dumpsys proves the resumed components);
#   3. bytes: upload through the app's documented REST path -- the same
#      POST /api/v1/attachments multipart + SendMessageRequest.attachmentIds
#      surface AttachmentRepository/MessageRepository call after a completed
#      pick -- then owner+member SHA-256 byte equality, exact 206 Range
#      slices, and a non-member GET refused 403 NOT_MEMBER on both serve
#      endpoints (the row's 'Persisted byte hash and MIME are correct' and
#      'Range is 206 and non-member is denied' assertions).
_MEDIA_SLICE_UNPROVEN = (
    "the pick completes in system picker UI (DocumentsUI/PhotoPicker) that "
    "uiautomator cannot drive reliably, so the in-app pick-to-upload pipeline "
    "(ImageCompressor, upload progress, 'no stuck upload state') is unproven",
    "voice recording/playback needs hardware audio capture/render an "
    "emulator cannot prove",
)
_MEDIA_PNG_WIDTH = 32
_MEDIA_PNG_HEIGHT = 24


def _import_media_driver():
    if str(DEVICE_DIR) not in sys.path:
        sys.path.insert(0, str(DEVICE_DIR))
    return importlib.import_module("media_driver")


def _media_push_fixture(context: dict[str, Any], fixture: Any) -> dict[str, Any]:
    """Push one deterministic fixture onto the device and hash-verify it there."""
    driver = _import_media_driver()
    try:
        return driver.push_fixture(
            context["adb"], fixture, host_dir=context["row_dir"]
        )
    except driver.MediaDeviceError as error:
        raise EnvironmentFailure(f"media fixture push failed: {error}") from error


def _media_picker_launch(context: dict[str, Any], what: str) -> str:
    """Poll `dumpsys activity activities` until a system picker is resumed.

    Returns the resumed system-picker component; a missing app surface is the
    caller's ProductFailure while an unreadable dumpsys raises
    EnvironmentFailure. The pick itself is never completed (system UI) -- the
    caller records that as unproven.
    """
    driver = _import_media_driver()
    adb: Adb = context["adb"]
    component = None
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        try:
            dump = adb.command("shell", "dumpsys", "activity", "activities",
                               timeout=20, check=False)
        except Exception as error:
            raise EnvironmentFailure(f"dumpsys could not be read: {error}") from error
        component = driver.resumed_activity_component(dump)
        if driver.is_system_picker_component(component):
            return component
        time.sleep(0.5)
    raise ProductFailure(
        f"tapping the {what} row did not launch a system picker "
        f"(last resumed component: {component!r})"
    )


def _media_attach_via_ui(context: dict[str, Any]) -> dict[str, Any]:
    """Open the composer attach sheet and prove both rows delegate to system
    pickers: visual -> PhotoPicker provider, document -> DocumentsUI.

    The sheet rows are the app's own surface (attachButton /
    pickVisualRow / pickDocumentRow), so a missing sheet or a row that fails
    to delegate is a product failure. Each launched system picker is dismissed
    with KEYCODE_BACK so the chat is restored for the REST slice.
    """
    ui = context["device"]
    row_dir = Path(context["row_dir"])
    delegations: list[dict[str, Any]] = []
    for row_rid, what, evidence in (
        ("pickVisualRow", "visual media", "attach-visual.png"),
        ("pickDocumentRow", "document", "attach-document.png"),
    ):
        attach = _wait_ui_node(
            context, lambda node: _rid_ends(node, "attachButton"),
            "the chat composer attach control was not visible",
        )
        ui.tap_node(attach)
        row_node = _wait_ui_node(
            context, lambda node: _rid_ends(node, row_rid),
            "the composer attach sheet did not open",
        )
        ui.screenshot(str(row_dir / evidence))
        ui.tap_node(row_node)
        component = _media_picker_launch(context, what)
        ui.screenshot(str(row_dir / f"system-picker-{'visual' if 'Visual' in row_rid else 'document'}.png"))
        ui.key("KEYCODE_BACK")
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "attachButton"),
            "the chat did not return after leaving the system picker",
        )
        delegations.append({"sheet_row": row_rid, "system_picker": component,
                            "pick_completed": False})
    return {
        "attach_sheet": "opened",
        "delegations": delegations,
        "pick_completed": False,
        "unproven": list(_MEDIA_SLICE_UNPROVEN),
    }


def _media_verify_bytes(context: dict[str, Any], owner: dict[str, Any],
                        member: dict[str, Any], outsider: dict[str, Any],
                        conversation_id: str, fixture: Any, msg_type: str,
                        *, width: int | None = None,
                        height: int | None = None) -> dict[str, Any]:
    """Upload one fixture through the app's documented REST path and run the
    byte-hash oracle: owner+member SHA-256 equality, 206 Range slices, and the
    outsider's 403 NOT_MEMBER refusal on both serve endpoints."""
    driver = _import_media_driver()
    try:
        return driver.verify_upload_slice(
            context["client"],
            owner_token=owner["accessToken"],
            member_token=member["accessToken"],
            outsider_token=outsider["accessToken"],
            conversation_id=conversation_id,
            fixture=fixture,
            msg_type=msg_type,
            image_width=width,
            image_height=height,
        )
    except driver.MediaProductError as error:
        raise ProductFailure(f"media byte oracle failed: {error}") from error
    except driver.MediaDeviceError as error:
        raise EnvironmentFailure(f"media byte oracle could not run: {error}") from error


def run_media_upload_slice(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """Prove the media upload slice; the row still ends BLOCKED_CAPABILITY.

    Runs only the assertions an emulator can evidence: fixtures pushed to the
    device and hash-verified there, the attach sheet delegating to the system
    pickers, and the documented REST upload path serving back byte-identical
    content with exact 206 Range slices and a non-member 403 refusal. The
    pick-to-upload app pipeline and voice recording/playback stay unproven,
    so after the evidence is written the executor raises CapabilityBlocked
    with the residual gaps -- the row never reports PASS.
    """
    _campaign_serial(context)
    _campaign_base_url(context)
    _prepare_seeded_an(context)
    api, _adb_ui, patch = context["helpers"]
    driver = _import_media_driver()
    row_dir = Path(context["row_dir"])
    pushed: list[dict[str, Any]] = []
    verified: list[dict[str, Any]] = []
    seeded: list[tuple[str, str]] = []  # (message id, owner token) for the sweep
    attach_slice: dict[str, Any] | None = None
    primary_error: Exception | None = None
    try:
        nodes = patch.ui_nodes(context["device"], timeout=15)
        row_node = patch.named_conversation_row(nodes, "Binh")
        if row_node is None:
            raise EnvironmentFailure(
                "seed An↔Binh conversation is not visible in the device inbox")
        context["device"].tap_node(row_node)
        _wait_ui_node(
            context, lambda node: _rid_ends(node, "attachButton"),
            "the chat composer attach control was not visible",
        )

        an, binh, conversation_id, _a_uid, _b_uid = _seed_pair(context)
        _, conversation = api.direct_conversation(
            context["client"], an["accessToken"], api.USER_C["phone"])
        if conversation.get("e2eeEnabled"):
            raise EnvironmentFailure(
                "the seeded An↔Binh conversation is E2EE; the app hides media "
                "rows there by design, so the upload slice cannot run in it")
        chi = _actor_session(api, context["client"], api.USER_B["phone"])

        # 1. deterministic fixtures land on the device unchanged.
        pdf = driver.build_fixture("pdf")
        png = driver.build_fixture("png", width=_MEDIA_PNG_WIDTH,
                                   height=_MEDIA_PNG_HEIGHT)
        pushed.append(_media_push_fixture(context, pdf))
        pushed.append(_media_push_fixture(context, png))

        # 2. the app's attach sheet delegates to the system pickers.
        attach_slice = _media_attach_via_ui(context)

        # 3. byte oracle through the app's documented upload path.
        pdf_result = _media_verify_bytes(
            context, an, binh, chi, conversation_id, pdf, "FILE")
        verified.append(pdf_result)
        seeded.append((pdf_result["message_id"], an["accessToken"]))
        png_result = _media_verify_bytes(
            context, an, binh, chi, conversation_id, png, "IMAGE",
            width=_MEDIA_PNG_WIDTH, height=_MEDIA_PNG_HEIGHT)
        verified.append(png_result)
        seeded.append((png_result["message_id"], an["accessToken"]))

        context["device"].key("KEYCODE_BACK")  # back to the inbox
    except Exception as error:
        primary_error = error
        raise
    finally:
        for message_id, token in seeded:
            failure = driver.soft_delete_message(
                context["client"], token, conversation_id, message_id)
            if failure:
                context.setdefault("cleanup_errors", []).append(f"media: {failure}")
        for item in pushed:
            driver.remove_device_file(context["adb"], item["remote_path"])
        _write_json(row_dir / "media-slice.json", {
            "row": row.id,
            "pushed": pushed,
            "attach": attach_slice,
            "verified": verified,
            "unproven": list(_MEDIA_SLICE_UNPROVEN),
        })
        if primary_error is None and context.get("cleanup_errors"):
            raise HarnessError("media slice cleanup failed")
    raise CapabilityBlocked(
        "upload slice proven (device push hashes, attach-sheet delegation, "
        "byte-identity/range/403 oracle; evidence in media-slice.json); row "
        "stays blocked: " + "; ".join(_MEDIA_SLICE_UNPROVEN))


# --- accessibility-sweep ------------------------------------------------------
# The accepted critical-screen inventory: every (locale, night, font_scale)
# variant must visit each screen below and pass the a11y_oracle checks on its
# dumped UI tree. Marker rids come from the screen layouts -- fragment_inbox
# (conversationList), fragment_chat (messageInput), fragment_archive
# (archiveTabs), fragment_profile (profileName), the conversation-labels
# AlertDialog (select_dialog_listview), fragment_contacts (contactList). The
# direct and group chat share fragment_chat, so the executor tells the two
# chat cells apart by the seeded peer/group title rendered in the chat header.
CRITICAL_SCREENS: tuple[dict[str, str], ...] = (
    {"screen": "inbox", "marker_rid": "conversationList"},
    {"screen": "direct-chat", "marker_rid": "messageInput"},
    {"screen": "group-chat", "marker_rid": "messageInput"},
    {"screen": "archive", "marker_rid": "archiveTabs"},
    {"screen": "settings", "marker_rid": "profileName"},
    {"screen": "labels", "marker_rid": "text:Conversation labels|Nhãn trò chuyện"},
    {"screen": "contacts", "marker_rid": "contactList"},
)


def _offline_patch(context: dict[str, Any], fn: str, *args: Any, **kwargs: Any) -> Any:
    """Run a patch_window step, translating its product defect into ours."""
    patch = context["helpers"][2]
    try:
        return getattr(patch, fn)(*args, **kwargs)
    except Exception as error:
        defect = getattr(patch, "ProductDefect", None)
        if defect is not None and isinstance(error, defect):
            raise ProductFailure(str(error)) from error
        raise


def _offline_rest_once(make_call: Callable[[], Any], allowed: set[int],
                       what: str) -> Any:
    """One REST fixture call; a single 429 retry keeps the seeded write burst
    honest, and a second rate-limit is an environment signal, not a defect."""
    response = make_call()
    if response.status == 401:
        # Seed access tokens expire inside the >40-minute journey; every call
        # site resolves them through _tok per invocation, so a plain retry
        # lands on a fresh session (run-25 attempt-1 died on a stale token).
        response = make_call()
    if response.status == 429:
        details = ((response.json() or {}).get("error") or {}).get("details") or {}
        try:
            retry_after = int(details.get("retryAfterSeconds") or 5)
        except (TypeError, ValueError):
            retry_after = 5
        time.sleep(min(60, max(2, retry_after + 1)))
        response = make_call()
    if response.status == 429:
        raise EnvironmentFailure(f"the local stack rate-limited {what}")
    _expect_status(response, allowed, what)
    return response


def _offline_send_rest(recorder: RecordingClient,
                       tok: Callable[[], str],
                       conversation_id: str, content: str) -> dict[str, Any]:
    response = _offline_rest_once(
        lambda: recorder.post(
            f"/api/v1/conversations/{conversation_id}/messages", token=tok(),
            body={"clientMessageId": str(uuid.uuid4()), "type": "TEXT",
                  "content": content, "attachmentIds": []}),
        {200, 201}, f"fixture send of {content!r}")
    return dict(response.json() or {})


def _offline_history(recorder: RecordingClient,
                     tok: Callable[[], str],
                     conversation_id: str) -> list[dict[str, Any]]:
    """Newest 100 history rows — the 60-fixture burst stays inside one page."""
    def call():
        return recorder.get(
            f"/api/v1/conversations/{conversation_id}/messages",
            token=tok(), params={"limit": 100})
    response = call()
    if response.status == 401:
        response = call()
    _expect_status(response, {200}, "the direct-conversation history")
    return list((response.json() or {}).get("items") or [])


def _offline_inbox_ids(recorder: RecordingClient,
                       tok: Callable[[], str]) -> set[str]:
    """Every unarchived conversation id in the caller's REST inbox."""
    def call():
        return recorder.get("/api/v1/conversations", token=tok(),
                            params={"archived": "false"})
    response = call()
    if response.status == 401:
        response = call()
    _expect_status(response, {200}, "the caller inbox list")
    body = response.json() or []
    items = body.get("items") if isinstance(body, dict) else body
    return {str(item.get("id")) for item in items or [] if item.get("id")}


def _offline_chunk_tail(recorder: RecordingClient, tok: Callable[[], str],
                        seeded_ids: set[str]) -> str:
    """The seeded group id sorting deepest in the caller's conversation set.

    SyncV2Consumer orders watermarks by conversation id and batches 100 per
    POST /api/v1/sync, so an id past position 100 cannot ride in the first
    batch; a marker landing there proves the second batch was applied.
    """
    ids = sorted(_offline_inbox_ids(recorder, tok))
    if len(ids) <= 100:
        raise EnvironmentFailure(
            f"the caller inbox holds {len(ids)} conversations; >100 are required")
    tail = [cid for index, cid in enumerate(ids)
            if index >= 100 and cid in seeded_ids]
    if not tail:
        raise EnvironmentFailure("no seeded group sorts into the second sync batch")
    return tail[-1]


def _offline_server_reachable(context: dict[str, Any]) -> bool:
    """TCP probe from inside the emulator to the configured API endpoint.

    `nc -z` on the ranchu image prints nothing and exits 0 on connect; any
    `nc:` diagnostic means the device-side route to the server is down."""
    parsed = urlsplit(derive_emulator_base_url(str(context.get("base_url") or "")))
    host = parsed.hostname or "10.0.2.2"
    port = str(parsed.port or (443 if parsed.scheme == "https" else 80))
    probe = context["adb"].command(
        "shell", "nc", "-z", "-w", "3", host, port,
        timeout=15, check=False)
    return "nc:" not in probe and "unreachable" not in probe.casefold() \
        and "refused" not in probe.casefold()


def _offline_wait_network(context: dict[str, Any], what: str,
                          timeout: float = 90) -> None:
    """Wait for the emulator's route to the API to come back.

    The virtwifi radio re-associates slowly after svc toggles and has died
    outright under the seeded-storm load (run-16 attempt-2: 'No connection'
    for the whole ~15-minute walk while host-side REST seeding was healthy).
    One svc re-enable is allowed; a still-dead route is an environment
    failure, not a product defect."""
    deadline = time.monotonic() + timeout
    recovered = False
    while time.monotonic() < deadline:
        if _offline_server_reachable(context):
            return
        if not recovered:
            context["adb"].command("shell", "svc", "wifi", "enable",
                                   timeout=15, check=False)
            context["adb"].command("shell", "svc", "data", "enable",
                                   timeout=15, check=False)
            recovered = True
        time.sleep(3)
    raise EnvironmentFailure(what)


def _offline_force_stop(context: dict[str, Any]) -> None:
    context["adb"].command("shell", "am", "force-stop", PACKAGE, timeout=20)


def _offline_relaunch(context: dict[str, Any]) -> None:
    context["adb"].command("shell", "am", "start", "-n",
                           f"{PACKAGE}/{MAIN_ACTIVITY}", timeout=30)
    time.sleep(4)


def _offline_app_foreground(context: dict[str, Any]) -> bool:
    """True when the focused window belongs to the app — the node tree alone
    cannot tell a mid-app surface from the launcher."""
    focus = context["adb"].command("shell", "dumpsys", "window",
                                   timeout=20, check=False)
    for line in focus.splitlines():
        if "mCurrentFocus" in line:
            return f"{PACKAGE}/" in line
    return True


def _offline_wait_inbox(context: dict[str, Any], what: str,
                        timeout: float | None = None) -> None:
    # Accept either inbox marker: the avatar node only becomes a11y-visible
    # after the signed-in profile binds, while conversationList attaches with
    # the fragment. Both rids exist only in fragment_inbox.xml, so matching
    # either still proves the inbox surface is up (run-48 attempt-2 rendered
    # the inbox for ~180s without the avatar node appearing in the tree).
    _wait_ui_node(
        context,
        lambda node: _rid_ends(node, "inboxUserAvatar")
        or _rid_ends(node, "conversationList"),
        what, timeout=timeout)


def _offline_wait_inbox_row(context: dict[str, Any], title: str | tuple,
                            what: str,
                            timeout: float | None = None) -> Any:
    """Pull-refresh the inbox until a title row renders, then return it."""
    deadline = time.monotonic() + float(timeout or max(90.0, _ui_timeout(context)))
    last_pull = 0.0
    sweeps = 0
    while True:
        if time.monotonic() >= deadline:
            _deadline_dump(context, what)
            raise ProductFailure(what)
        nodes = _ui_nodes(context)
        if _dismiss_occluder(context, nodes):
            time.sleep(0.8)
            continue
        row = _inbox_conversation_row(context, title, nodes=nodes)
        if row is not None:
            return row
        # The target row can sit anywhere in a long stale inbox — uiautomator
        # drops invisible children, so neither a clipped row 0 (run-33/run-34:
        # 'Binh' sat as an invisible child just above the viewport edge) nor a
        # row ~105 leftover seeded groups deep (run-36 fresh start) is ever in
        # the dump. Cycle: nudge toward the top once (reveals a clipped row 0 /
        # fires PTR at rest), then page DOWN one viewport per miss so every
        # rendered row gets a match attempt; at the sweep limit hard-fling
        # back to the top for the next cycle. One uiautomator dump per
        # iteration keeps the sweep fast enough to reach ~106+ rows inside
        # the deadline. A pull-refresh per miss (~1s cadence) kept the list in
        # the rid-less churn window (run-32 attempt-1), so refresh only every
        # 8s — mid-list PTR only nudges ~2 rows back up, net forward.
        list_node = next((node for node in nodes
                          if _rid_ends(node, "conversationList")), None)
        if list_node is not None and list_node.box:
            cx = (list_node.box[0] + list_node.box[2]) // 2
            if sweeps == 0:
                context["device"].swipe(
                    cx, list_node.box[1] + 60, cx, list_node.box[3] - 40, 300)
                sweeps = 1
                time.sleep(0.5)
            elif sweeps <= _OFFLINE_ROW_SWEEP_LIMIT:
                context["device"].swipe(
                    cx, list_node.box[3] - 40, cx, list_node.box[1] + 60, 250)
                sweeps += 1
                time.sleep(0.35)
            else:
                for _ in range(3):
                    context["device"].swipe(
                        cx, list_node.box[1] + 40,
                        cx, list_node.box[3] - 10, 80)
                    time.sleep(0.3)
                sweeps = 0
        if time.monotonic() - last_pull >= 8:
            _pull_refresh_inbox(context)
            last_pull = time.monotonic()
        time.sleep(0.4)


def _offline_open_direct(context: dict[str, Any],
                         title: str | tuple = "Binh") -> None:
    """Inbox -> the seeded An<->Binh chat through the real row tap.

    `title` accepts the alias tuple form: post-relaunch the DM row has been
    observed rendering the just-delivered message text in place of the peer
    name, so the caller passes the sent probe text alongside 'Binh'."""
    ui = context["device"]
    row = _offline_wait_inbox_row(
        context, title, "the seeded Binh row was not on the inbox")
    ui.tap_node(row)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        nodes = _ui_nodes(context)
        if _dismiss_occluder(context, nodes):
            time.sleep(0.8)
            continue
        if any(_rid_ends(node, "messageInput") for node in nodes):
            return
        # A tap can land on a row mid-re-layout under the refresh churn, and
        # a freshly opened chat can pop itself back to the inbox; while the
        # row list is still the visible surface, re-resolve and tap again
        # instead of idling the whole budget (run-20 attempt-1).
        if any(_rid_ends(node, "conversationList") for node in nodes):
            retry_row = _inbox_conversation_row(context, title)
            if retry_row is not None:
                ui.tap_node(retry_row)
                time.sleep(1.0)
                continue
        time.sleep(0.6)
    _deadline_dump(context, "the An-Binh conversation did not open")
    raise ProductFailure("the An-Binh conversation did not open")


def _offline_inbox_scroll(context: dict[str, Any]) -> None:
    """One overlapping scroll-up drag inside the conversation list.

    The gesture has to satisfy two constraints on this 320x640 gesture-nav
    emulator. It must start far enough above the screen's bottom edge that the
    system does not steal it as a swipe-home (the earlier edge-band swipe
    repeatedly sent the app to the launcher mid-walk). And it must be short
    and slow enough that fling momentum cannot skip a whole viewport between
    a11y dumps — skipped rows never enter the collector's distinct-title set,
    which is how a fully paged inbox still "surfaced" only a fraction of its
    rows. ~28% of the list height over 650ms advances roughly two rows per
    drag, so consecutive viewports always overlap."""
    nodes = _ui_nodes(context)
    container = next((node for node in nodes
                      if _rid_ends(node, "conversationList")), None)
    box = getattr(container, "box", None) or (0, 0, 320, 640)
    cx = (box[0] + box[2]) // 2
    height = box[3] - box[1]
    context["device"].swipe(cx, box[1] + int(height * 0.68),
                            cx, box[1] + int(height * 0.40), 650)


def _offline_ensure_all_filter(context: dict[str, Any]) -> None:
    """Re-select the 'All' inbox chip when a persisted filter survived.

    InboxViewModel restores selectedFilter from saved state across process
    death; a leftover 'Unread'/'Groups'/'Archived' selection shrinks the
    rendered set and the >100-row walk then fails for the wrong reason."""
    chip = next((node for node in _ui_nodes(context)
                 if _rid_ends(node, "filterAllChip")), None)
    if chip is None or not chip.box:
        return
    if chip.attrs.get("checked") == "true":
        return
    context["device"].tap_node(chip)
    time.sleep(0.8)


def _offline_wait_conv_body(context: dict[str, Any],
                            reopen_title: str | tuple,
                            matcher: Callable[[Any], bool], what: str,
                            timeout: float = 60) -> Any:
    """Wait for `matcher` to hit a messageBody inside an open conversation.

    The conversation screen can silently pop itself back to the inbox while
    a fetch settles (run-18 attempts 1+2: the direct view returned to the
    inbox mid-burst, so messageBody nodes vanished for the rest of the
    deadline). When the inbox resurfaces, the matching row is tapped again
    and the wait keeps going instead of burning the budget outside the
    chat.

    A reopened conversation can also settle at a scroll position where the
    reconciled tail rows sit below the rendered window (run-21: three
    deterministic "reconciled tail edit was not rendered" failures with the
    edits confirmed applied host-side), or with the target row mostly
    clipped above the viewport's top edge — uiautomator drops clipped
    children, so the deadline dump showed the tail-edit row rendered as a
    23px sliver (desc + Edited marker) while its messageBody TextView never
    entered the tree (run-51). The periodic swipe therefore ping-pongs: three
    swipes toward older rows (revealing content clipped above the top edge),
    three back toward the newest rows, alternating. A genuinely absent
    message still fails on the deadline."""
    ui = context["device"]
    deadline = time.monotonic() + timeout
    next_scroll = time.monotonic() + 8.0
    scroll_count = 0
    grace_used = False
    while True:
        nodes = _ui_nodes(context)
        if _dismiss_occluder(context, nodes):
            time.sleep(0.8)
            continue
        hit = next((node for node in nodes
                    if _rid_ends(node, "messageBody") and matcher(node)),
                   None)
        if hit is not None:
            return hit
        if time.monotonic() >= deadline:
            # One last shot before the dump: if the chat popped to the inbox
            # at the deadline, re-tap the row once and grant a short grace —
            # the normal loop (scroll ping-pong, occluders) then runs a few
            # more seconds and the dump captures the best possible screen
            # instead of whichever transient state the clock hit.
            if not grace_used and any(
                    _rid_ends(node, "conversationList") for node in nodes):
                row = _inbox_conversation_row(context, reopen_title)
                if row is not None:
                    grace_used = True
                    ui.tap_node(row)
                    deadline = time.monotonic() + 10.0
                    continue
            _deadline_dump(context, what)
            raise ProductFailure(what)
        if any(_rid_ends(node, "conversationList") for node in nodes):
            row = _inbox_conversation_row(context, reopen_title)
            if row is not None:
                ui.tap_node(row)
                time.sleep(1.2)
                continue
            # The row can sit far below the rendered window — the fixture
            # seeds >100 group conversations after bumping the DM, so the
            # post-restart inbox sorts the DM deep. Sweep the list toward
            # older rows instead of polling a viewport that never contains
            # it; the deadline still bounds the total search.
            if time.monotonic() >= next_scroll:
                _offline_inbox_scroll(context)
                next_scroll = time.monotonic() + 1.5
        else:
            message_list = next(
                (node for node in nodes
                 if _rid_ends(node, "messageList")), None)
            if message_list is not None and time.monotonic() >= next_scroll:
                bounds = message_list.box
                if bounds is not None:
                    cx = bounds[0] + (bounds[2] - bounds[0]) // 2
                    top = bounds[1]
                    bottom = bounds[3]
                    span = bottom - top
                    # Down-swipe pulls older rows into the top edge; up-swipe
                    # walks toward the newest rows. Three each, alternating —
                    # the target can sit on either side of the open viewport.
                    if (scroll_count // 3) % 2 == 0:
                        ui.swipe(cx, top + int(span * 0.3),
                                 cx, top + int(span * 0.7),
                                 duration_ms=350)
                    else:
                        ui.swipe(cx, top + int(span * 0.7),
                                 cx, top + int(span * 0.3),
                                 duration_ms=350)
                    scroll_count += 1
                next_scroll = time.monotonic() + 4.0
                continue
        time.sleep(0.7)


def _offline_open_action_sheet(context: dict[str, Any], reopen_title: str,
                               content: str) -> None:
    """Long-press a rendered bubble, re-entering the chat when it pops away.

    Same silent-pop race as _offline_wait_conv_body: the action-sheet poll
    can outlive the conversation screen, so when the inbox resurfaces the
    matching row is tapped again and the long-press retries instead of
    raising on a bubble that is no longer on screen."""
    ui = context["device"]
    deadline = time.monotonic() + _ui_timeout(context)
    while True:
        bubble = _message_bubble(context, content)
        if bubble is None:
            if time.monotonic() >= deadline:
                _deadline_dump(
                    context, f"message bubble {content!r} was not on screen")
                raise ProductFailure(
                    f"message bubble {content!r} was not on screen")
            nodes = _ui_nodes(context)
            if any(_rid_ends(node, "conversationList") for node in nodes):
                row = _inbox_conversation_row(context, reopen_title)
                if row is not None:
                    ui.tap_node(row)
                    time.sleep(1.2)
                    continue
            time.sleep(0.7)
            continue
        ui.long_press(bubble)
        sheet_deadline = time.monotonic() + 6
        while time.monotonic() < sheet_deadline:
            if any(_rid_ends(node, "messageActionsTitle")
                   for node in _ui_nodes(context)):
                return
            time.sleep(0.4)
        if time.monotonic() >= deadline:
            _deadline_dump(
                context, "the message action sheet did not open after long-press")
            raise ProductFailure(
                "the message action sheet did not open after long-press")


def _offline_walk_inbox(context: dict[str, Any], title: str,
                        swipes: int = 260) -> None:
    """Scroll the inbox until the oldest seeded group row renders — the pager
    must have appended every 30-row page above it into Room.

    Progress is measured by the smallest sj-os-<tag>-NNN suffix in the
    viewport (the tail row 001 sorts deepest): the walk is stalled only
    when that frontier stops descending, never when a post-restart re-walk
    is still descending through already-paged rows (run-26 attempt-1: one
    restart at swipe 40 restarted at the top and the flat 80-swipe budget
    expired around row 052). A visible Retry banner re-issues the killed
    page fetch without a scroll reset; a frontier stall with no banner
    escalates to a bounded relaunch that clears the #202 generation lock.
    """
    prefix = title.rsplit("-", 1)[0] + "-"
    suffix_re = re.compile(r"^" + re.escape(prefix) + r"(\d{3})$")
    restarts = 0
    retry_taps = 0
    missing = 0
    stall = 0
    frontier: int | None = None
    for swipe in range(swipes):
        nodes = _ui_nodes(context)
        if not any(_rid_ends(node, "conversationList") for node in nodes):
            missing += 1
            if missing >= 2:
                # A coalesced drag-tap can drop the app inside a chat here
                # too; BACK returns to the inbox for a fraction of a
                # relaunch (same recovery as the collector's walk).
                if _offline_app_foreground(context):
                    context["device"].key("KEYCODE_BACK")
                    time.sleep(1.0)
                else:
                    _offline_relaunch(context)
                _offline_wait_inbox(
                    context,
                    "the inbox did not return during the tail walk",
                    timeout=45)
                _offline_ensure_all_filter(context)
                missing = 0
                frontier = None  # viewport position changed; re-baseline
            time.sleep(1.0)
            continue
        missing = 0
        container = next((node for node in nodes
                          if _rid_ends(node, "conversationList")), None)
        top, bottom = container.box[1], container.box[3]
        row = next((node for node in nodes
                    if ((node.text or "").strip() == title
                        or (node.desc or "").strip() == title)
                    and node.box and top <= node.box[1]
                    and node.box[3] <= bottom), None)
        if row is not None:
            return
        current = min((int(match.group(1)) for node in nodes
                       for match in [suffix_re.match((node.text or "").strip())]
                       if match), default=None)
        if current is not None and (frontier is None or current < frontier):
            frontier = current
            stall = 0
        else:
            stall += 1
        if stall >= 14:
            # Same #202 starve the collector escalates on. A Retry banner
            # means the page fetch died and can be re-issued in place; no
            # banner at a dead frontier means the generation lock needs a
            # fresh process (run-24 attempt-2 never left the ~90-row Room
            # window, run-26 attempt-1 stalled at row ~052).
            retry = next((node for node in nodes
                          if node.clickable
                          and (node.text or "").strip()
                          in _OFFLINE_RETRY_LABELS), None)
            if retry is not None and retry_taps < 6:
                context["device"].tap_node(retry)
                retry_taps += 1
                stall = 6
                time.sleep(1.5)
                continue
            if restarts >= 3:
                break
            _offline_force_stop(context)
            _offline_relaunch(context)
            _offline_wait_inbox(
                context, "the inbox did not return for the tail walk",
                timeout=60)
            _offline_ensure_all_filter(context)
            restarts += 1
            stall = 0
            frontier = None  # re-walk descends from the top: not a stall
            continue
        _offline_inbox_scroll(context)
        time.sleep(1.0)
    raise ProductFailure(f"the inbox never reached the oldest seeded row {title!r}")


def _offline_wait_sync_quiet(context: dict[str, Any], timeout: float = 240) -> None:
    """Wait until the device stops issuing POST /api/v1/sync calls.

    After a >100-conversation seed the SyncV2 consumer drains its delta pages
    as a burst of small requests; pulling a fresh inbox generation mid-burst
    supersedes the in-flight write before it lands. The quiet window lets the
    watermark drain finish so the next refresh lands."""
    deadline = time.monotonic() + timeout
    last_count = -1
    stable = 0
    while time.monotonic() < deadline:
        posts, _bodies = _offline_sync_requests(context)
        if posts == last_count:
            stable += 1
            if stable >= 2:
                return
        else:
            stable = 0
            last_count = posts
        time.sleep(4)


def _offline_pull_until_seed(context: dict[str, Any], prefix: str,
                             timeout: float = 150) -> None:
    """Pull once, then give the landed generation time to render.

    Re-pulling inside the window restarts the generation race, so a second
    pull only happens once after the first half of the deadline passes."""
    _offline_ensure_all_filter(context)
    _pull_refresh_inbox(context)
    deadline = time.monotonic() + timeout
    repulled = False
    while time.monotonic() < deadline:
        time.sleep(2.5)
        nodes = _ui_nodes(context)
        if _dismiss_occluder(context, nodes):
            time.sleep(0.8)
            continue
        if any((node.text or "").strip().startswith(prefix)
               for node in nodes):
            return
        if not repulled and time.monotonic() > deadline - timeout / 2:
            _pull_refresh_inbox(context)
            repulled = True
    if not _offline_server_reachable(context):
        _offline_wait_network(
            context, "the emulator lost connectivity while the seeded "
                     "inbox first page was fetched", timeout=60)


def _offline_collect_inbox_titles(context: dict[str, Any], prefix: str,
                                  need: int, swipes: int = 240) -> set[str]:
    """Accumulate distinct inbox row titles matching `prefix` while paging down.

    Each viewport renders ~5-7 rows; sweeping the whole seeded block while
    collecting proves >100 conversations paged through the UI even when the
    exact tail row is hard to name (display order follows lastMessageAt).
    When the dump loses the conversationList node entirely the app has been
    backgrounded — relaunching costs less than swiping the launcher for the
    rest of the budget."""
    _offline_ensure_all_filter(context)
    seen: set[str] = set()
    stall = 0
    last = 0
    missing = 0
    starve_cycles = 0
    restarts = 0
    for _ in range(swipes):
        nodes = _ui_nodes(context)
        if _dismiss_occluder(context, nodes):
            time.sleep(1.0)
            continue
        container = next((node for node in nodes
                          if _rid_ends(node, "conversationList")), None)
        if container is None or not container.box:
            missing += 1
            if missing >= 2:
                # A drag that the system coalesced into a tap opens whichever
                # conversation sits under the finger (run-19 attempt-1 ended
                # inside Chi's chat, then cold-restored back into it after
                # the relaunch). When the app is still foreground one BACK
                # returns to the inbox for a fraction of a relaunch's cost.
                if _offline_app_foreground(context):
                    context["device"].key("KEYCODE_BACK")
                    time.sleep(1.0)
                else:
                    _offline_relaunch(context)
                try:
                    _offline_wait_inbox(
                        context,
                        "the inbox did not return during the seeded walk",
                        timeout=45)
                except Exception:
                    _offline_relaunch(context)
                    _offline_wait_inbox(
                        context,
                        "the inbox did not return during the seeded walk")
                _offline_ensure_all_filter(context)
                missing = 0
            time.sleep(1.0)
            continue
        missing = 0
        for node in nodes:
            text = (node.text or "").strip()
            if text.startswith(prefix):
                seen.add(text)
        if len(seen) >= need:
            return seen
        stall = stall + 1 if len(seen) == last else 0
        if len(seen) != last:
            starve_cycles = 0
        last = len(seen)
        if stall >= 15:
            # Long quiet stall: if the radio died mid-walk no pull will land
            # anything; probe before wasting another refresh cycle.
            if not _offline_server_reachable(context):
                _offline_wait_network(
                    context, "the emulator lost connectivity during the "
                             "seeded inbox walk", timeout=60)
            # The page-1 write may have been superseded before landing (#202
            # generation race), which also leaves hasMoreInboxPages=false so
            # no further page can ever be requested. When the network banner
            # is up its Retry button re-issues the fetch without dragging the
            # list back to the top; a pull-drag mid-walk would scroll several
            # rows upward and waste traversal on every stall cycle.
            retry = next((node for node in nodes
                          if node.clickable
                          and (node.text or "").strip()
                          in _OFFLINE_RETRY_LABELS), None)
            if retry is not None:
                context["device"].tap_node(retry)
                starve_cycles += 1
            elif starve_cycles >= 3 and restarts < 2:
                # A sustained starve means the supersede race keeps killing
                # every page fetch; only a fresh process clears the
                # generation lock so the next page-1 lands current. Room
                # rows persist, so `seen` survives — this is a user reopening
                # the app, not a data poke (run-24 attempt-2 starved ~90).
                _offline_force_stop(context)
                _offline_relaunch(context)
                _offline_wait_inbox(
                    context,
                    "the inbox did not return for the walk restart",
                    timeout=60)
                _offline_ensure_all_filter(context)
                restarts += 1
                starve_cycles = 0
            else:
                _pull_refresh_inbox(context)
                starve_cycles += 1
            stall = 0
            time.sleep(1.5)
            continue
        _offline_inbox_scroll(context)
        time.sleep(1.0)
    return seen


def _offline_restore_an(context: dict[str, Any]) -> None:
    """Leave the device signed in as seed An through the real UI.

    A failure mid A->B->A switch would otherwise strand the emulator on the
    login form or inside Binh's session and poison every following row. The
    shared ensure_device_account probe fails the row outright on either of
    those states, so this restore resolves them itself: a visible login form
    gets An's credentials through the journey's own sign-in path, and a live
    session is checked on the profile screen, signed out when it is not An,
    then signed back in as An."""
    api, _adb_ui, patch = context["helpers"]
    ui = context["device"]
    deadline = time.monotonic() + 90
    launcher_relaunches = 0
    while True:
        nodes = _ui_nodes(context)
        if _dismiss_occluder(context, nodes):
            time.sleep(0.8)
            continue
        if patch.login_form_visible(nodes):
            _offline_sign_in(context, api.USER_A["phone"], "An")
            return
        avatar = next((node for node in nodes
                       if _rid_ends(node, "inboxUserAvatar")), None)
        if avatar is not None:
            ui.tap_node(avatar)
            name = _wait_ui_node(
                context, lambda node: _rid_ends(node, "profileName"),
                "the profile name did not render during restore", timeout=20)
            name_is_an = name.text.strip() == "An"
            ui.key("KEYCODE_BACK")
            # A restored task can keep the profile surface as its root, so
            # BACK may exit to the launcher instead of the inbox (run-19
            # attempt-2). Poll briefly, relaunch while the app is gone, and
            # fall back to the outer loop's recovery rather than failing on
            # a dead back stack.
            inbox_back = False
            inbox_deadline = time.monotonic() + 15
            while time.monotonic() < inbox_deadline:
                probe = _ui_nodes(context)
                if _dismiss_occluder(context, probe):
                    time.sleep(0.8)
                    continue
                if any(_rid_ends(node, "inboxUserAvatar") for node in probe):
                    inbox_back = True
                    break
                if not _offline_app_foreground(context):
                    _offline_relaunch(context)
                time.sleep(0.8)
            if not inbox_back:
                continue
            if name_is_an:
                return
            _offline_sign_out(context)
            _offline_sign_in(context, api.USER_A["phone"], "An")
            return
        if time.monotonic() >= deadline:
            raise EnvironmentFailure(
                "neither the login form nor the inbox rendered while "
                "restoring the device account")
        if _offline_app_foreground(context):
            # Some in-app surface (splash, chat, profile): Back pops one
            # fragment toward the inbox or the login form.
            ui.key("KEYCODE_BACK")
        else:
            # On the launcher a stray Back already sent the app home; only a
            # fresh am start brings the task forward again (run-11 attempt-1).
            # A dead task can still resume a mid-app surface as its root —
            # after one plain relaunch that changed nothing, force-stop so
            # the next start rebuilds the stack from the start destination
            # (run-19 attempt-2 restored into a profile-root task).
            if launcher_relaunches >= 1:
                _offline_force_stop(context)
            _offline_relaunch(context)
            launcher_relaunches += 1
        time.sleep(1.5)


def _offline_prepare_an(context: dict[str, Any]) -> None:
    """_prepare_seeded_an, except restore-before-assert instead of assert-first.

    The shared prep verifies the visible account and fails when a prior run
    stranded the emulator signed out or on a non-An session; this row repairs
    that state through the real UI before its first assertion runs. The GMS
    autofill service is also parked for the row's lifetime (and restored in
    the finally): it intermittently overwrites the login phone field with a
    saved credential between our fill and the Sign-in tap."""
    adb = context["adb"]
    adb.command("shell", "cmd", "statusbar", "collapse",
                timeout=20, check=False)
    context["offline_autofill_before"] = adb.command(
        "shell", "settings", "get", "secure", "autofill_service",
        timeout=15, check=False)
    adb.command("shell", "settings", "put", "secure", "autofill_service",
                "null", timeout=15, check=False)
    if context.get("reset_app_data"):
        _clear_app_data(context)
    _offline_restore_an(context)


_OFFLINE_LOGCAT_TS_RE = re.compile(r"^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})")


def _offline_logcat_clock(context: dict[str, Any]) -> str:
    """Device-side 'MM-DD HH:MM:SS.000' watermark for a sync window.

    `adb shell` re-parses arguments on-device, so the format must not
    contain a literal space; `date +%m-%dT%H:%M:%S` ships as a single
    argument and the T is swapped back for the logcat threadtime grammar
    (run-28 attempt-1 died on `date: Max 1 argument`)."""
    stamp = context["adb"].command(
        "shell", "date", "+%m-%dT%H:%M:%S", timeout=15).strip()
    return f"{stamp.replace('T', ' ')}.000"


def _offline_sync_requests(context: dict[str, Any],
                           since: str | None = None) -> tuple[int, list[int]]:
    """(count, body sizes) of POST /api/v1/sync lines in the logcat buffer.

    `since` keeps only lines whose threadtime stamp is at or after a
    device-side watermark, filtered here in Python rather than through
    `logcat -t`: a malformed -t argument makes logcat exit non-zero with
    an empty stdout, which is indistinguishable from "no sync traffic"
    (run-29 attempt-1 polled 0 while ~70 real POSTs sat in the window).
    The unscoped whole-buffer count is only a point-in-time size anyway:
    this run's >40-minute walk rotates the ring buffer hard enough that
    baseline sync lines evict before the second read (run-27 attempt-1).
    """
    raw = context["adb"].command("logcat", "-d", "-v", "threadtime",
                               timeout=40, check=False)
    posts = 0
    bodies: list[int] = []
    for line in raw.splitlines():
        if not _OFFLINE_SYNC_POST_RE.search(line):
            continue
        if since:
            stamp = _OFFLINE_LOGCAT_TS_RE.match(line)
            # Only a proven-older line is excluded; a line without a
            # parseable stamp stays in (harness fakes log raw OkHttp).
            if stamp is not None and stamp.group(1) < since:
                continue
        posts += 1
        match = _OFFLINE_SYNC_BYTES_RE.search(line)
        if match:
            bodies.append(int(match.group(1)))
    return posts, bodies


def _offline_sign_out(context: dict[str, Any]) -> None:
    """Profile -> Sign out -> confirm, landing on the login form."""
    ui = context["device"]
    patch = context["helpers"][2]
    avatar = _wait_ui_node(
        context, lambda node: _rid_ends(node, "inboxUserAvatar"),
        "the inbox profile entry point was not visible")
    ui.tap_node(avatar)
    _scroll_profile_until(context, "logoutRow")
    logout = _wait_ui_node(
        context, lambda node: _rid_ends(node, "logoutRow"),
        "the Sign out row was not rendered")
    ui.tap_node(logout)
    confirm = _wait_ui_node(
        context,
        lambda node: node.text.strip() in _OFFLINE_SIGN_OUT_LABELS
                     and node.clickable and not _rid_ends(node, "logoutRow"),
        "the Sign out confirmation was not rendered")
    ui.tap_node(confirm)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        nodes = _ui_nodes(context)
        if _dismiss_occluder(context, nodes):
            time.sleep(0.8)
            continue
        if patch.login_form_visible(nodes):
            return
        time.sleep(0.7)
    raise EnvironmentFailure("the login form did not appear after Sign out")


def _offline_sign_in(context: dict[str, Any], phone: str,
                     display_name: str, password: str | None = None) -> None:
    """Drive the real login form for an account, then prove the profile
    identifies that account — the same probe _assert_device_account_an uses.

    password defaults to the seeded patch_window PASSWORD; disposable
    accounts pass their own password explicitly.
    """
    patch = context["helpers"][2]
    ui = context["device"]
    deadline = time.monotonic() + 45
    while True:
        nodes = _ui_nodes(context)
        if _dismiss_occluder(context, nodes):
            time.sleep(0.8)
            continue
        if patch.login_form_visible(nodes):
            break
        if time.monotonic() >= deadline:
            raise EnvironmentFailure(
                f"the login form never appeared for {display_name}")
        time.sleep(0.7)
    # The emulator's GMS autofill intermittently overwrites the phone field
    # with a saved credential between our fill and the Sign-in tap (run-9
    # attempt-1), and the first tap right after the form draws can miss input
    # focus while the IME settles (run-11 attempt-2). Refill-and-verify a
    # bounded number of times before giving up.
    resolved_password = (password if password is not None
                         else getattr(patch, "PASSWORD", "password123"))
    for _attempt in range(3):
        try:
            patch.fill_login_field(ui, "phoneInput", phone)
            ui.key("KEYCODE_BACK")
            patch.fill_login_field(ui, "passwordInput", resolved_password)
        except Exception:
            if _attempt == 2:
                raise
            time.sleep(1.5)
            continue
        context["adb"].command("shell", "input", "keyevent", "4",
                               timeout=15, check=False)
        time.sleep(1)
        nodes = _ui_nodes(context)
        phone_field = patch.node_by_id(nodes, "phoneInput")
        if phone_field is not None and phone_field.text.strip() == phone:
            break
    else:
        raise EnvironmentFailure(
            f"the login phone field would not hold {phone!r}")
    nodes = _ui_nodes(context)
    action = patch.node_by_id(nodes, "primaryAction") or next(
        (node for node in nodes
         if patch.text_matches(node, ("Sign in", "Đăng nhập"))), None)
    if action is None:
        raise EnvironmentFailure("the sign-in control was not exposed")
    ui.tap_node(action)
    # A fresh sign-in has to REST-sync the whole inbox before the avatar rid
    # exists; on a loaded fixture (~106 conversations over an encrypted Room
    # cold open) that first sync has outrun the 90s budget (run-47 attempt-2
    # cleanup restore died here while the inbox rendered seconds later).
    # Poll inline instead of _offline_wait_inbox: if the submit tap missed or
    # the auth call failed, the app sits on the login form for the whole
    # budget while the row finally-restore later signs in fine and the
    # capture misleadingly shows a rendered inbox (run-48/run-49 attempt-2).
    inbox_deadline = time.monotonic() + 180
    retap_deadline = time.monotonic() + 20
    while True:
        nodes = _ui_nodes(context)
        if _dismiss_occluder(context, nodes):
            time.sleep(0.8)
            continue
        if any(_rid_ends(node, "inboxUserAvatar")
               or _rid_ends(node, "conversationList") for node in nodes):
            break
        if time.monotonic() >= inbox_deadline:
            raise ProductFailure(f"{display_name} did not reach the inbox")
        if time.monotonic() >= retap_deadline:
            retry = patch.node_by_id(nodes, "primaryAction") or next(
                (node for node in nodes
                 if patch.text_matches(node, ("Sign in", "Đăng nhập"))), None)
            if retry is not None and patch.login_form_visible(nodes):
                ui.tap_node(retry)
                time.sleep(1.5)
            retap_deadline = time.monotonic() + 20
        time.sleep(0.7)
    avatar = _wait_ui_node(
        context, lambda node: _rid_ends(node, "inboxUserAvatar"),
        "the profile entry point was not visible after sign-in")
    ui.tap_node(avatar)
    _wait_ui_node(
        context,
        lambda node: _rid_ends(node, "profileName")
                     and node.text.strip() == display_name,
        f"the signed-in profile did not identify {display_name}", timeout=15)
    ui.key("KEYCODE_BACK")
    _offline_wait_inbox(context, "the inbox did not return after the profile check")


def _offline_purge_stale_groups(recorder: RecordingClient,
                                token: Callable[[], str],
                                self_user_id: str) -> int:
    """Leave every leftover ``sj-os-*`` group still in An's inbox.

    A run that dies before its member/leave sweep orphans ~106 seeded groups;
    they accumulate in the inbox (run-49 attempt-2: 106 orphans from attempt-1
    alone), stretch the post-restore first sync past the sign-in budget — the
    failure capture showed a fully rendered inbox seconds after the 180s
    deadline — and bury seeded rows other journeys scan for. Best-effort: a
    purge miss never blocks the journey; the per-run sweep still releases
    what it can at teardown."""
    try:
        response = recorder.get("/api/v1/conversations", token=token())
    except Exception:
        return 0
    if response.status != 200:
        return 0
    body = response.json() or []
    items = body.get("items") if isinstance(body, dict) else body
    purged = 0
    for item in items or []:
        if item.get("type") != "GROUP":
            continue
        if not str(item.get("title") or "").startswith("sj-os-"):
            continue
        conv_id = str(item.get("id") or "")
        if not conv_id:
            continue
        try:
            for member in item.get("members") or []:
                member_id = str(member.get("userId") if isinstance(member, dict)
                                else member or "")
                if member_id and member_id != self_user_id:
                    _remove_member_rest(recorder, token(), conv_id, member_id)
            left = recorder.post(
                f"/api/v1/conversations/{conv_id}/leave", token=token())
            for _attempt in range(2):
                if left.status != 429:
                    break
                details = ((left.json() or {}).get("error") or {}) \
                    .get("details") or {}
                try:
                    retry_after = int(details.get("retryAfterSeconds") or 5)
                except (TypeError, ValueError):
                    retry_after = 5
                time.sleep(min(30, max(2, retry_after + 1)))
                left = recorder.post(
                    f"/api/v1/conversations/{conv_id}/leave", token=token())
            if left.status in (200, 204):
                purged += 1
        except Exception:
            continue
    return purged


def run_offline_sync(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """Offline queue -> restart -> reconnect reconcile on the seeded device.

    Scope, kept honest:
      * The Room oracle degrades to UI assertions: nexachat.db is SQLCipher
        and the device image carries no sqlite3 binary, so the outbox is
        proven through rendered bubbles and the server-side exact-once query
        instead of raw rows.
      * The >100-conversation claim is proven two ways: the paged inbox walk
        reaches the oldest seeded row (every page above it lives in Room),
        and OkHttp request lines show the post-restart watermark sync fanning
        out into more than one bounded POST /api/v1/sync batch; a marker
        message sent to a conversation that sorts into the second batch is
        then rendered from its inbox row.
      * Account switch clears Room (clearAccountDataPreservingOutbox), so the
        post-ABA assertion is a fresh bounded convergence: the inbox repages
        and a brand-new peer message lands.

    Every violated product assertion raises ProductFailure; fixture failures
    (network toggling, REST seeding, sign-in surfaces) stay harness errors.
    """
    _campaign_serial(context)
    _campaign_base_url(context)
    _offline_prepare_an(context)
    api, _adb_ui, patch = context["helpers"]
    recorder: RecordingClient = context["client"]
    ui = context["device"]
    an = _actor_session(api, recorder, api.USER_A["phone"])
    binh = _actor_session(api, recorder, api.USER_C["phone"])
    chi = _actor_session(api, recorder, api.USER_B["phone"])
    an_id = str((an.get("user") or {}).get("id") or "")
    binh_id = str((binh.get("user") or {}).get("id") or "")
    chi_id = str((chi.get("user") or {}).get("id") or "")
    if not an_id or not binh_id or not chi_id:
        raise EnvironmentFailure("seed actor sessions did not expose user ids")
    _TOK_PHONES = {"an": api.USER_A["phone"], "binh": api.USER_C["phone"],
                   "chi": api.USER_B["phone"]}

    def _tok(label: str) -> str:
        """Resolve a live access token at call time.

        The journey runs longer than the ~15-minute token TTL, so fixtures
        never hold a token string — _actor_session revalidates through the
        temp-file session cache and re-logins once it goes stale (run-25
        attempt-1 expired mid-walk)."""
        return str(_actor_session(api, recorder, _TOK_PHONES[label])
                   ["accessToken"])

    _ta = lambda: _tok("an")
    _tb = lambda: _tok("binh")
    purged = _offline_purge_stale_groups(recorder, _ta, an_id)
    if purged:
        print(f"[offline-sync] purged {purged} leftover sj-os-* groups")
    try:
        _, conversation = api.direct_conversation(
            recorder, _ta(), api.USER_C["phone"])
    except Exception as error:
        raise EnvironmentFailure(
            "the seeded An-Binh conversation could not be opened") from error
    conversation_id = str(conversation.get("id") or "")
    if not conversation_id:
        raise EnvironmentFailure("the seeded conversation returned no id")

    row_dir = Path(context["row_dir"])
    tag = uuid.uuid4().hex[:8]
    ot_texts = [f"sj-ot1-{tag}", f"sj-ot2-{tag}"]
    react_target = f"sj-osr-{tag}"
    edit_src = [f"sj-osm-{tag}-e{i:02d}"
                for i in range(1, _OFFLINE_FIXTURE_EACH + 1)]
    recall_src = [f"sj-osm-{tag}-r{i:02d}"
                  for i in range(1, _OFFLINE_FIXTURE_EACH + 1)]
    edited_to = {content: f"sj-ose-{tag}-{index + 1:02d}"
                 for index, content in enumerate(edit_src)}
    marker_text = f"sj-osm2-{tag}"
    final_text = f"sj-osf-{tag}"
    aonly_title = f"sj-os-{tag}-aonly"

    seeded: dict[str, str] = {}                  # title -> conversation id
    group_members: list[tuple[str, str]] = []    # (conv id, member uid to sweep)
    created: list[tuple[str, str, str]] = []  # (conv, msg, author phone)
    edit_ids: list[str] = []
    recall_ids: list[str] = []
    aonly_id = ""
    marker_conv_id = ""
    marker_title = ""
    cleanup_errors: list[str] = []
    result: dict[str, Any] = {}
    primary_error: Exception | None = None
    before = _offline_patch(context, "settings_snapshot")
    try:
        # --- fixture: 60 interleaved mutation sources + a reaction target ----
        # Interleaving authors keeps each 30/10s write bucket honest and leaves
        # one edited and one recalled message inside the rendered tail after
        # the restart (tail order follows original seq, not the edit time).
        # Bump the seeded DM toward inbox row 0 before opening it — on a stale
        # inbox the row sits >100 leftover conversations deep (run-36/37) and
        # even the page-down sweep may not reach it inside one cycle. The
        # delta/pull-refresh that lands this text is itself under test, and
        # the row can render the message text as its title post-bump.
        bump_text = f"sj-osb-{tag}"
        bump_msg = _offline_send_rest(
            recorder, _tb, conversation_id, bump_text)
        created.append((conversation_id, str(bump_msg.get("id")), api.USER_C["phone"]))
        _offline_open_direct(context, title=("Binh", bump_text))
        for index in range(_OFFLINE_FIXTURE_EACH):
            e_msg = _offline_send_rest(
                recorder, _tb, conversation_id, edit_src[index])
            created.append((conversation_id, str(e_msg.get("id")), api.USER_C["phone"]))
            edit_ids.append(str(e_msg.get("id")))
            time.sleep(_OFFLINE_SEND_GAP_S / 2)
            r_msg = _offline_send_rest(
                recorder, _ta, conversation_id, recall_src[index])
            created.append((conversation_id, str(r_msg.get("id")), api.USER_A["phone"]))
            recall_ids.append(str(r_msg.get("id")))
            time.sleep(_OFFLINE_SEND_GAP_S / 2)
        target_msg = _offline_send_rest(
            recorder, _tb, conversation_id, react_target)
        created.append((conversation_id, str(target_msg.get("id")), api.USER_C["phone"]))
        # The 61-frame burst lands over ~42s while the app applies each frame
        # serially (Room write + coalesced inbox refresh + chat re-render); the
        # tail sits behind that backlog, so a 60s wall clock expired mid-drain
        # (run-35/-38 attempt-1) even though the same phase passed on retry.
        _offline_wait_conv_body(
            context, "Binh",
            lambda node: node.text.strip() == react_target,
            "the seeded fixture burst did not reach the open conversation",
            timeout=120)

        # --- Phase O: queue two texts + one reaction while offline ----------
        _offline_patch(context, "cut_network")
        for content in ot_texts:
            _offline_patch(context, "queue_offline_text", ui, content)
        _offline_open_action_sheet(context, "Binh", react_target)
        tile = _wait_ui_node(
            context,
            lambda node: (node.desc or "").strip() in _CHAT_REACTION_TILE,
            "the Like reaction tile was not offered while offline")
        ui.tap_node(tile)
        _wait_sheet_closed(context)
        _wait_ui_node(
            context,
            lambda node: _CHAT_REACTION_EMOJI in (node.desc or "")
                         and ("yours" in node.desc or "của bạn" in node.desc),
            "the offline reaction did not render its optimistic chip")
        ui.screenshot(str(row_dir / "offline-queue.png"))

        # Queued work survives process death: cold-relaunch while still cut.
        _offline_force_stop(context)
        _offline_relaunch(context)
        _offline_wait_inbox(
            context, "the inbox did not return after the offline restart",
            timeout=60)
        _offline_open_direct(context)
        for content in ot_texts:
            _offline_wait_conv_body(
                context, "Binh",
                lambda node, c=content: c in (node.text or ""),
                f"queued message {content!r} did not survive the offline restart")
        ui.screenshot(str(row_dir / "offline-after-restart.png"))

        # --- Phase R1: reconnect flush, exactly once per clientMessageId -----
        _offline_patch(context, "restore_test_network", before)
        _offline_wait_network(
            context,
            "the device never regained connectivity after the offline phase")
        flushed: list[dict[str, Any]] = []
        deadline = time.monotonic() + 45
        while True:
            items = _offline_history(recorder, _tb, conversation_id)
            flushed = [item for item in items
                       if item.get("content") in set(ot_texts)]
            if len(flushed) >= 2:
                break
            if time.monotonic() >= deadline:
                raise ProductFailure(
                    "the reconnect flush did not land both queued texts on REST")
            time.sleep(0.8)
        if {item.get("content") for item in flushed} != set(ot_texts) \
                or len(flushed) != 2:
            raise ProductFailure(
                "the flush produced a duplicate or missing queued text")
        client_ids = {str(item.get("clientMessageId") or "")
                      for item in flushed}
        if len(client_ids) != 2 or "" in client_ids:
            raise ProductFailure(
                "the flushed texts did not carry two distinct clientMessageIds")
        for item in flushed:
            created.append((conversation_id, str(item.get("id")), api.USER_A["phone"]))
        db_note = "server-db oracle unavailable"
        try:
            db_rows = _offline_patch(
                context, "wait_server_rows",
                os.environ.get("NEXACHAT_E2E_DB_CONTAINER", "nexachat-db-1"),
                os.environ.get("NEXACHAT_E2E_DB_NAME", "nexachat"),
                ot_texts)
        except Exception:
            db_rows = None
        if db_rows:
            matched = {row_db.get("content"): int(row_db.get("count") or 0)
                       for row_db in db_rows
                       if row_db.get("content") in set(ot_texts)}
            if any(count != 1 for count in matched.values()):
                raise ProductFailure(
                    "the server database holds a duplicate row for a queued "
                    "clientMessageId")
            if set(matched) == set(ot_texts):
                db_note = "exactly one server row per queued text"
            else:
                db_note = f"server-db matched {len(matched)}/{len(ot_texts)} texts"
        _history_wait(
            recorder, _tb(), conversation_id,
            lambda item: item.get("content") == react_target and any(
                entry.get("emoji") == _CHAT_REACTION_EMOJI
                and int(entry.get("count") or 0) >= 1
                for entry in (item.get("reactions") or [])),
            "the offline reaction never landed on its REST target", timeout=30)
        ui.screenshot(str(row_dir / "reconnect-sync.png"))

        # --- Phase R2: >100 conversations into Room via the paged inbox ------
        ui.key("KEYCODE_BACK")
        _offline_wait_inbox(context, "the inbox did not return for seeding")
        for index in range(1, _OFFLINE_GROUP_COUNT + 1):
            title = f"sj-os-{tag}-{index:03d}"
            created_group = _offline_rest_once(
                lambda t=title: recorder.post(
                    "/api/v1/conversations/group", token=_ta(),
                    body={"title": t, "memberIds": [binh_id]}),
                {200, 201}, f"seeded group {title}")
            cid = str((created_group.json() or {}).get("id") or "")
            if not cid:
                raise EnvironmentFailure("a seeded group create returned no id")
            seeded[title] = cid
            group_members.append((cid, binh_id))
        created_group = _offline_rest_once(
            lambda: recorder.post(
                "/api/v1/conversations/group", token=_ta(),
                body={"title": aonly_title, "memberIds": [chi_id]}),
            {200, 201}, "the An/Chi-only group")
        aonly_id = str((created_group.json() or {}).get("id") or "")
        if not aonly_id:
            raise EnvironmentFailure("the An/Chi group create returned no id")
        group_members.append((aonly_id, chi_id))

        # Group creation writes a system message, so every seeded group carries
        # lastMessageSeq=1 and ranks by createdAt DESC under the messaged
        # direct chat — the last-created group is the page-1 probe for Binh.
        top_title = f"sj-os-{tag}-{_OFFLINE_GROUP_COUNT:03d}"
        # Watermark window opens here, not at the R3 force-stop: whether a
        # reconnect relaunch POSTs its watermarks at all is racy in product
        # (SyncEngine silently completes when the session epoch moved or the
        # cursor row is not loaded yet, and STOMP can deliver the pending
        # deltas first — run-29's relaunch stormed 57 posts while run-30's
        # issued zero). Every post-seed relaunch — the walk's starve
        # restarts included — fans the same >100-watermark set into the
        # same bounded batches, so the window spans them all.
        sync_since = _offline_logcat_clock(context)
        _offline_wait_sync_quiet(context)
        _offline_pull_until_seed(context, f"sj-os-{tag}-")
        seen = _offline_collect_inbox_titles(
            context, f"sj-os-{tag}-", _OFFLINE_SEEN_MIN)
        # The walk is only done when the oldest seeded row itself renders:
        # sj-os-<tag>-001 sorts deepest, so reaching it proves every paged
        # inbox row above it was appended into Room.
        try:
            _offline_walk_inbox(context, f"sj-os-{tag}-001")
        finally:
            ui.screenshot(str(row_dir / "inbox-bottom.png"))
        if len(seen) < _OFFLINE_SEEN_MIN:
            raise ProductFailure(
                f"the paged inbox walk surfaced only {len(seen)} seeded "
                f"conversation rows; the >{_OFFLINE_SEEN_MIN}-conversation set "
                "must all render from Room")
        marker_conv_id = _offline_chunk_tail(
            recorder, _ta, set(seeded.values()))
        marker_title = next(title for title, cid in seeded.items()
                            if cid == marker_conv_id)

        # --- Phase R3: restart, 60 reconciled deltas, second-batch marker ----
        _offline_force_stop(context)
        for index, message_id in enumerate(edit_ids):
            _offline_rest_once(
                lambda mid=message_id, i=index: recorder.patch(
                    f"/api/v1/conversations/{conversation_id}/messages/{mid}",
                    token=_tb(),
                    body={"content": edited_to[edit_src[i]]}),
                {200}, f"peer edit {index + 1}")
        for index, message_id in enumerate(recall_ids):
            _offline_rest_once(
                lambda mid=message_id: recorder.delete(
                    f"/api/v1/conversations/{conversation_id}/messages/{mid}",
                    token=_ta()),
                {200, 204}, f"fixture recall {index + 1}")
        marker_msg = _offline_send_rest(
            recorder, _tb, marker_conv_id, marker_text)
        created.append((marker_conv_id, str(marker_msg.get("id")), api.USER_C["phone"]))
        _offline_relaunch(context)
        _offline_wait_inbox(context,
                            "the inbox did not return after the reconcile restart")
        marker_row = _offline_wait_inbox_row(
            context, marker_title,
            "the second-batch marker conversation never converged", timeout=60)
        ui.tap_node(marker_row)
        _wait_ui_node(context, lambda node: _rid_ends(node, "messageInput"),
                      "the marker conversation did not open", timeout=30)
        _offline_wait_conv_body(
            context, marker_title,
            lambda node: marker_text in (node.text or ""),
            "the marker message was not rendered in the second-batch conversation")
        ui.screenshot(str(row_dir / "chunk2-marker.png"))
        # The marker row renders from Room/STOMP ahead of the watermark
        # fan-out: the relaunch's POST /api/v1/sync batches land seconds to
        # minutes after the marker (run-27 attempt-1 read the buffer ~2.5
        # minutes early and counted 0 while the real batches were still
        # draining). Poll until the bounded batches show, then assert —
        # still-absent sync traffic after the window is a real product gap.
        sync_deadline = time.monotonic() + _OFFLINE_SYNC_POLL_S
        new_posts, new_bodies = 0, []
        while True:
            new_posts, new_bodies = _offline_sync_requests(context, sync_since)
            if (new_posts >= 2
                    and any(body > _OFFLINE_FULL_BATCH_BYTES
                            for body in new_bodies)):
                break
            if time.monotonic() >= sync_deadline:
                break
            time.sleep(5)
        if new_posts < 2:
            raise ProductFailure(
                f"the reconnect sync issued {new_posts} POST /api/v1/sync "
                "requests; the >100-watermark set must fan into at least two "
                "bounded batches")
        if new_bodies and len(new_bodies) == new_posts \
                and max(new_bodies) <= _OFFLINE_FULL_BATCH_BYTES:
            raise ProductFailure(
                "no POST /api/v1/sync body was large enough to carry a "
                "~100-watermark batch")
        ui.key("KEYCODE_BACK")
        _offline_wait_inbox(context, "the inbox did not return from the marker chat")
        # With >100 fresh groups ranking above it, the direct row sits far down
        # the inbox; a new peer text bumps it to the top AND proves a
        # post-restart new-message delta landed.
        probe_text = f"sj-osp-{tag}"
        probe_msg = _offline_send_rest(
            recorder, _tb, conversation_id, probe_text)
        created.append((conversation_id, str(probe_msg.get("id")),
                        api.USER_C["phone"]))
        # Post-relaunch the DM row has rendered the fresh last-message text
        # as its title instead of the peer name (run-31 attempt-1); either
        # string identifies the same unambiguous row.
        _offline_open_direct(
            context, title=("Binh", probe_text) + _CHAT_TOMBSTONE_MARKERS)
        _offline_wait_conv_body(
            context, ("Binh", probe_text) + _CHAT_TOMBSTONE_MARKERS,
            lambda node: probe_text in (node.text or ""),
            "the post-restart peer text did not render in the direct chat",
            timeout=60)
        last_edit = edited_to[edit_src[-1]]
        # After the 30-message recall burst the DM row's newest activity is a
        # tombstone, so its inbox title/preview can render "This message was
        # deleted" instead of the peer name or the probe text — neither of
        # the earlier aliases then matches the row (exact-match lookup) even
        # though it is visible at the top of the list.
        try:
            _offline_wait_conv_body(
                context,
                ("Binh", probe_text, last_edit) + _CHAT_TOMBSTONE_MARKERS,
                lambda node: node.text.strip() == last_edit,
                "the reconciled tail edit was not rendered",
                timeout=_OFFLINE_TAIL_EDIT_WAIT_S)
        except ProductFailure as failure:
            # Cheapest discriminator (Kongming): the REST history oracle at the
            # row's end never runs on this failure path, so fold server truth
            # into the error — "HAS" separates a UI-scroll miss from a
            # reconcile gap without opening the deadline dump first.
            applied = any(item.get("content") == last_edit
                          for item in _offline_history(
                              recorder, _tb, conversation_id))
            raise ProductFailure(
                f"{failure} (server history "
                f"{'HAS' if applied else 'does NOT have'} "
                "the edited content)") from failure
        _wait_ui_node(
            context,
            lambda node: node.text.strip() in _CHAT_EDITED_MARKERS,
            "the Edited marker did not render after the reconcile")
        _wait_ui_node(
            context,
            lambda node: node.text.strip() in _CHAT_TOMBSTONE_MARKERS,
            "no recalled tombstone rendered after the reconcile")
        ui.screenshot(str(row_dir / "post-restart-tail.png"))
        history = _offline_history(recorder, _tb, conversation_id)
        edited_rows = [item for item in history
                       if item.get("content") in set(edited_to.values())]
        if len(edited_rows) != _OFFLINE_FIXTURE_EACH or not all(
                item.get("editedAt") for item in edited_rows):
            raise ProductFailure(
                "the 30 peer edits did not all persist on REST history")
        recalled_rows = [item for item in history
                         if str(item.get("id") or "") in set(recall_ids)]
        if len(recalled_rows) != _OFFLINE_FIXTURE_EACH or not all(
                item.get("deletedAt") for item in recalled_rows):
            raise ProductFailure(
                "the 30 fixture recalls did not all persist on REST history")

        # --- Phase X: account switch A -> B -> A ------------------------------
        ui.key("KEYCODE_BACK")
        _offline_wait_inbox(context, "the inbox did not return for the switch")
        _offline_sign_out(context)
        _offline_sign_in(context, api.USER_C["phone"], "Binh")
        _offline_wait_inbox_row(
            context, top_title,
            "Binh's session did not converge the shared seeded groups",
            timeout=60)
        if aonly_id in _offline_inbox_ids(recorder, _tb):
            raise ProductFailure(
                "the An/Chi-only fixture leaked into Binh's inbox")
        ui.screenshot(str(row_dir / "switch-b-inbox.png"))
        _offline_sign_out(context)
        _offline_sign_in(context, api.USER_A["phone"], "An")
        final_msg = _offline_send_rest(
            recorder, _tb, conversation_id, final_text)
        created.append((conversation_id, str(final_msg.get("id")), api.USER_C["phone"]))
        _offline_open_direct(context, title=("Binh", final_text))
        # A fresh sign-in's STOMP/delta pipeline lags well behind the inbox
        # REST sync: the ABA message already bumped the Binh row's timestamp
        # before the conversation surface had the body (run-51 attempt-1 timed
        # out at 60s while the row itself had just updated). Give the
        # conversation-level land a wider window than the warm-session waits.
        _offline_wait_conv_body(
            context, ("Binh", final_text),
            lambda node: node.text.strip() == final_text,
            "a fresh peer message did not land after the ABA switch",
            timeout=150)
        ui.screenshot(str(row_dir / "switch-aba-inbox.png"))
        ui.key("KEYCODE_BACK")
        _offline_wait_inbox(context, "the inbox did not return at row end")
        result = {
            "offline_queue": "two texts + one Like survived process death",
            "exact_once": ("two flushed texts, distinct clientMessageIds; "
                           + db_note),
            "offline_reaction": "Like chip rendered offline and landed on REST",
            "large_set": (f"{_OFFLINE_GROUP_COUNT} seeded groups paged into "
                          "Room; oldest row rendered"),
            "reconcile": ("30 edits + 30 recalls verified on REST; tail edit, "
                          "Edited marker and tombstone rendered"),
            "multi_batch": (f"{new_posts} POST /api/v1/sync requests after "
                            "restart" +
                            (f"; largest body {max(new_bodies)} bytes"
                             if new_bodies else "")),
            "second_chunk_marker": f"{marker_title} rendered {marker_text!r}",
            "account_switch": "A->B hid the An-only fixture; ABA reconverged",
        }
    except Exception as error:
        primary_error = error
    finally:
        try:
            _offline_patch(context, "restore_device_settings", before)
        except Exception as error:
            cleanup_errors.append(f"settings restore: {type(error).__name__}")
        autofill_before = context.pop("offline_autofill_before", None)
        if autofill_before and autofill_before != "null":
            try:
                context["adb"].command(
                    "shell", "settings", "put", "secure", "autofill_service",
                    autofill_before, timeout=15, check=False)
            except Exception as error:
                cleanup_errors.append(
                    f"autofill restore: {type(error).__name__}")
        # Row phases outlive the JWT lifetime, so fixture sweep re-auths
        # through the cached/validated login helper instead of the start-of-row
        # tokens (first live run: every delete returned 401).
        sweep_tokens: dict[str, str] = {}
        for phone in {author_phone
                      for _c, _m, author_phone in created} | {
                      api.USER_A["phone"]}:
            try:
                session = api.login(recorder, phone)
                token = str(session.get("accessToken") or "")
                if token:
                    sweep_tokens[phone] = token
            except Exception:
                continue
        for conv_id, message_id, author_phone in created:
            token = sweep_tokens.get(author_phone) or (
                _ta() if author_phone == api.USER_A["phone"] else _tb())
            try:
                deleted = None
                # The >60-delete burst outruns the per-minute write bucket;
                # run-31 attempt-2 passed every product assertion then lost
                # its PASS to six 429s here. Retry honoring retryAfterSeconds
                # so an exhausted bucket is a delay, not a cleanup miss.
                for _attempt in range(3):
                    deleted = recorder.delete(
                        f"/api/v1/conversations/{conv_id}/messages/"
                        f"{message_id}", token=token)
                    if deleted.status != 429:
                        break
                    details = ((deleted.json() or {}).get("error") or {}) \
                        .get("details") or {}
                    try:
                        retry_after = int(
                            details.get("retryAfterSeconds") or 5)
                    except (TypeError, ValueError):
                        retry_after = 5
                    time.sleep(min(30, max(2, retry_after + 1)))
                if deleted is not None and deleted.status not in (200, 204, 404):
                    cleanup_errors.append(f"message delete HTTP {deleted.status}")
            except Exception as error:
                cleanup_errors.append(f"message sweep: {type(error).__name__}")
            time.sleep(0.25)
        sweep_an = sweep_tokens.get(api.USER_A["phone"], _ta())

        def _cleanup_call(make_call: Callable[[], Any]) -> Any:
            """Bounded 429 drain shared by the member/leave sweep below."""
            response = make_call()
            for _attempt in range(2):
                if response.status != 429:
                    break
                details = ((response.json() or {}).get("error") or {}) \
                    .get("details") or {}
                try:
                    retry_after = int(details.get("retryAfterSeconds") or 5)
                except (TypeError, ValueError):
                    retry_after = 5
                time.sleep(min(30, max(2, retry_after + 1)))
                response = make_call()
            return response

        for conv_id, member_id in group_members:
            try:
                _remove_member_rest(recorder, sweep_an, conv_id, member_id)
            except Exception as error:
                cleanup_errors.append(f"member sweep: {type(error).__name__}")
            try:
                left = _cleanup_call(lambda: recorder.post(
                    f"/api/v1/conversations/{conv_id}/leave", token=sweep_an))
                if left.status not in (200, 204, 400, 403, 404):
                    cleanup_errors.append(f"leave HTTP {left.status}")
            except Exception as error:
                cleanup_errors.append(f"leave: {type(error).__name__}")
        try:
            _offline_restore_an(context)
        except Exception as error:
            cleanup_errors.append(
                f"device account restore: {type(error).__name__}")
        if cleanup_errors:
            context["cleanup_errors"] = cleanup_errors
    if primary_error is not None:
        raise primary_error
    if context.get("cleanup_errors"):
        raise HarnessError("offline-sync cleanup could not release its fixtures")
    result["cleanup"] = ("queued/fixture messages soft-deleted; 106 groups "
                         "orphaned (member removed, An left); settings restored")
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


# --- seed fixture self-healing -----------------------------------------------
# The demo stack's seed rows are shared mutable state: earlier journeys,
# one-off probes and manual renames consume, archive or rename them. Run
# evidence: the 'NexaChat Team' forward target was absent from An's inbox
# twice after earlier journeys consumed it, and An's display_name drifted
# 'An'->'An Nguyen' after a rename journey, breaking the accessibility
# sweep's account verification (the manual fix was UPDATE users SET
# display_name='An'). Drifted fixture state is REST-reseedable, so the
# local-mode preflight verifies and heals it instead of env-failing a row
# mid-journey. A missing seed ACCOUNT is still an environment failure: only
# the psql demo seed can restore its contact graph and history.

_SEED_DUNG_FALLBACK = {"phone": _GROUPS_DUNG_PHONE, "name": "Dung"}


def _seed_fixture_users(api: Any) -> tuple[dict[str, str], ...]:
    """Fixture accounts of the local seed contract: An, Binh, Chi, Dung."""
    dung = getattr(api, "USER_D", None)
    if not (isinstance(dung, dict) and dung.get("phone") and dung.get("name")):
        dung = _SEED_DUNG_FALLBACK
    return (api.USER_A, api.USER_C, api.USER_B, dung)


def _heal_seed_display_name(api: Any, recorder: RecordingClient,
                            fixture: dict[str, str]) -> str | None:
    """Verify one seed account's display_name; PATCH it back when drifted.

    The actor session already carries the live displayName (fresh login
    response or the token cache's validating GET /users/me), so a healthy
    fixture costs no extra call. Returns a heal note, or None when healthy.
    """
    phone, expected = str(fixture["phone"]), str(fixture["name"])
    session = _actor_session(api, recorder, phone)
    current = str((session.get("user") or {}).get("displayName") or "").strip()
    if current == expected:
        return None
    healed = recorder.patch(
        "/api/v1/users/me", token=session["accessToken"], body={"displayName": expected})
    body = (healed.json() or {}) if healed.status == 200 else {}
    restored = str(body.get("displayName") or "") if isinstance(body, dict) else ""
    if healed.status != 200 or restored != expected:
        raise EnvironmentFailure(
            f"seed account {expected!r} display_name drifted to {current or '(blank)'!r} "
            f"and the REST heal returned HTTP {healed.status}")
    return f"display_name {expected!r} restored from {current or '(blank)'!r}"


def _forward_target_entry(recorder: RecordingClient, an_token: str,
                          *, archived: bool) -> dict[str, Any] | None:
    """The seeded 'NexaChat Team' GROUP inbox entry, or None.

    Unpaged on purpose: stale run leftovers push the seed group past any
    paged read."""
    response = recorder.get(
        "/api/v1/conversations", token=an_token,
        params={"archived": "true"} if archived else None)
    if response.status != 200:
        raise EnvironmentFailure(f"conversation inbox returned HTTP {response.status}")
    body = response.json() or []
    items = body.get("items") if isinstance(body, dict) else body
    return next((item for item in (items or [])
                 if item.get("type") == "GROUP"
                 and item.get("title") == _CHAT_FORWARD_TARGET_TITLE
                 and item.get("id")), None)


def _forward_target_member_ids(entry: dict[str, Any]) -> set[str]:
    return {str((member or {}).get("userId") or "")
            for member in (entry.get("members") or [])}


def _search_user_id(recorder: RecordingClient, token: str, phone: str) -> str | None:
    """Best-effort seed member id via GET /users/search?phone= (no login)."""
    try:
        response = recorder.get("/api/v1/users/search", token=token, params={"phone": phone})
    except Exception:
        return None
    if response.status != 200:
        return None
    rows = response.json() or []
    return str((rows[0] or {}).get("id") or "") if rows else None


def _ensure_forward_target_group(api: Any, recorder: RecordingClient,
                                 an: dict[str, Any],
                                 binh: dict[str, Any]) -> tuple[str, list[str]]:
    """Verify the seeded 'NexaChat Team' forward target; re-seed it over REST.

    Heals in order: an archived row is unarchived back into the default
    inbox view; a group that lost Binh regains him through the admin member
    add; a consumed/renamed/never-seeded target is re-created as a fresh
    group (creator An, Binh, plus Chi/Dung when their accounts still
    resolve). Raises EnvironmentFailure only when the re-seed itself fails,
    so drifted fixture state never env-fails the row mid-journey again.
    """
    heals: list[str] = []
    an_token = an["accessToken"]
    binh_id = str((binh.get("user") or {}).get("id") or "")
    group = _forward_target_entry(recorder, an_token, archived=False)
    if group is None:
        group = _forward_target_entry(recorder, an_token, archived=True)
        if group is not None:
            restored = recorder.delete(
                f"/api/v1/conversations/{group['id']}/archive", token=an_token)
            if restored.status not in (200, 204):
                raise EnvironmentFailure(
                    f"re-seeding {_CHAT_FORWARD_TARGET_TITLE!r}: unarchive returned "
                    f"HTTP {restored.status}")
            heals.append("archived forward target restored to the default inbox")
    if group is not None and binh_id and binh_id not in _forward_target_member_ids(group):
        added = recorder.post(
            f"/api/v1/conversations/{group['id']}/members",
            token=an_token, body={"userIds": [binh_id]})
        if added.status in (200, 201, 204):
            heals.append("forward target regained Binh as a member")
        else:
            # A shell group An cannot administrate cannot carry the row's
            # forwards; drop it and re-seed a fresh one below.
            group = None
    if group is None:
        member_ids = [binh_id]
        for fixture in _seed_fixture_users(api)[2:]:
            extra_id = _search_user_id(recorder, an_token, str(fixture["phone"]))
            if extra_id and extra_id not in member_ids:
                member_ids.append(extra_id)
        created = recorder.post(
            "/api/v1/conversations/group", token=an_token,
            body={"title": _CHAT_FORWARD_TARGET_TITLE, "memberIds": member_ids})
        body = (created.json() or {}) if created.status in (200, 201) else {}
        if created.status not in (200, 201) or not isinstance(body, dict) or not body.get("id"):
            raise EnvironmentFailure(
                f"re-seeding {_CHAT_FORWARD_TARGET_TITLE!r} returned HTTP {created.status}")
        group = body
        heals.append("forward target re-created for An with the resolvable seed peers")
    return str(group["id"]), heals


def ensure_seed_fixture_state(api: Any, recorder: RecordingClient) -> dict[str, Any]:
    """Verify and REST-re-seed the local demo fixture before any row runs.

    The seed contract (demo/seed.sql expressed as REST calls): the fixture
    accounts with their fixture display_names, the An<->Binh DIRECT
    conversation, and the 'NexaChat Team' group An forwards into. Rows
    consume this shared state between runs, so every campaign start
    re-verifies it and heals the drift instead of env-failing mid-journey.
    """
    heals: list[str] = []
    for fixture in _seed_fixture_users(api):
        note = _heal_seed_display_name(api, recorder, fixture)
        if note:
            heals.append(note)
    an = _actor_session(api, recorder, api.USER_A["phone"])
    binh = _actor_session(api, recorder, api.USER_C["phone"])
    status, _conversation = api.direct_conversation(
        recorder, an["accessToken"], api.USER_C["phone"])
    if status == 201:
        heals.append("An<->Binh direct conversation re-created")
    _, group_heals = _ensure_forward_target_group(api, recorder, an, binh)
    heals.extend(group_heals)
    return {
        "checked": [*(str(item["name"]) for item in _seed_fixture_users(api)),
                    "An<->Binh direct conversation", _CHAT_FORWARD_TARGET_TITLE],
        "heals": heals,
    }


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
    # Runtime grants are a precondition of the dead-process assertion: without
    # POST_NOTIFICATIONS the incoming-call surface cannot legally appear
    # (notification suppressed, activity fallback BAL-blocked). The grant is
    # idempotent, so applying it unconditionally mirrors _clear_app_data.
    context["helpers"][2].grant_test_permissions()
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


def _import_disposable_stack():
    if str(E2E_DIR) not in sys.path:
        sys.path.insert(0, str(E2E_DIR))
    return importlib.import_module("disposable_stack")


# Rows that provision their own backend through apply_server_env when --dstack
# is on; selected alone they do not require the shared local server.
_DSTACK_SELF_PROVISIONED_ROWS = frozenset({"otp-forgot-password", "account-deletion"})


def _dstack_apply_server_env(context: dict[str, Any], api: Any) -> Callable[[dict[str, str]], dict[str, Any]]:
    """apply_server_env backed by disposable_stack: provision + start a private server.

    The returned handle carries the stack base_url, a sql() bound to the
    disposable database, and close() that stops the backend and drops the DB.
    The stack lives under the row's attempt dir so repeated attempts and
    neighbouring mail rows each get a fresh database. context may pin the
    app port ("dstack_app_port") so the device app — whose base URL is
    compile-time — can reach the stack.
    """
    def apply(env: dict[str, str]) -> dict[str, Any]:
        dstack = _import_disposable_stack()
        row_dir = context.get("row_dir") or Path(context["evidence_root"]) / "dstack-row"
        stack = dstack.DisposableStack(
            str(Path(row_dir) / "dstack"),
            server_target=str(context.get("dstack_server_target")
                              or dstack.DEFAULT_SERVER_TARGET))
        pinned_port = context.get("dstack_app_port")
        state = stack.provision(
            app_port=int(pinned_port) if pinned_port else None,
            extra_env=dict(env),
            extra_java_args=[
                # Mailpit is plaintext SMTP; the default config requires STARTTLS.
                "--spring.mail.properties.mail.smtp.starttls.enable=false",
                "--spring.mail.properties.mail.smtp.starttls.required=false",
            ])
        try:
            stack.start()
        except Exception:
            try:
                stack.stop()
            except Exception:
                pass
            stack.dropdb()
            raise
        base_url = str(state.get("base_url") or dstack.base_url_for(int(state["app_port"])))

        def close() -> None:
            try:
                stack.stop()
            finally:
                stack.dropdb()

        return {"base_url": base_url, "stack": stack, "state": state,
                "sql": stack.sql, "close": close}

    return apply


def _with_mail_sink(context: dict[str, Any], row: JourneyRow,
                    action: Callable[[Any], None]) -> dict[str, Any] | None:
    """Start Mailpit, hand its env to context["apply_server_env"], run action.

    apply_server_env may return a disposable-server handle carrying "base_url"
    (or a ready "client"), a "sql" callable, and "close". When a handle is
    returned the action runs against that server: context["client"] and
    context["base_url"] are swapped for the action (the stack recorder aliases
    the campaign transcript) and restored afterwards, then close() and
    mail.stop() run in that order.
    """
    apply_env = context.get("apply_server_env")
    if apply_env is None:
        raise CapabilityBlocked(
            row.capability_gap
            or "mail sink is not applied to the local server; rerun with --dstack")
    mail = context.get("mail_sink") or _import_mail_sink()
    started = False
    handle: dict[str, Any] | None = None
    previous: dict[str, Any] = {}
    try:
        mail.start()
        started = True
        env = mail.server_env()
        host = str(env.get("MAIL_SMTP_HOST", ""))
        if env.get("NEXACHAT_MAIL_PROVIDER") != "smtp" or not host:
            raise HarnessError("mail sink server_env() did not point at an SMTP sink")
        if PRODUCTION_HOST.casefold() in host.casefold():
            raise HarnessError("mail sink host looks like production")
        handle = apply_env(env)
        if handle and (handle.get("base_url") or handle.get("client")):
            previous = {"client": context["client"],
                        "base_url": context.get("base_url")}
            stack_client = handle.get("client")
            if stack_client is None:
                helpers = context.get("helpers") or _load_helpers()
                stack_client = RecordingClient(
                    helpers[0].Client(handle["base_url"], timeout=30))
            if hasattr(stack_client, "records"):
                stack_client.records = previous["client"].records
            context["client"] = stack_client
            context["base_url"] = str(handle.get("base_url") or previous["base_url"]).rstrip("/")
            context["dstack_handle"] = handle
        action(mail)
        return handle
    finally:
        if previous:
            context["client"] = previous["client"]
            context["base_url"] = previous["base_url"]
            context.pop("dstack_handle", None)
        if handle and callable(handle.get("close")):
            try:
                handle["close"]()
            except Exception as error:
                context.setdefault("cleanup_errors", []).append(
                    f"disposable stack teardown: {error}")
        if started:
            mail.stop()


def _prove_expired_reset_code(context: dict[str, Any], mail: Any, phone: str, email: str) -> None:
    """Age a fresh reset code inside the disposable DB, then confirm it is refused.

    EmailOtpService.CODE_TTL is fixed at 10 minutes, so expiry is proven by
    rewinding expires_at on the disposable database rather than by waiting.
    """
    handle = context.get("dstack_handle") or {}
    sql = handle.get("sql")
    if sql is None:
        raise CapabilityBlocked(
            "expired-code proof needs a disposable database; rerun with --dstack")
    if "'" in email or '"' in email:
        raise HarnessError("disposable email must not contain quotes")
    client = context["client"]
    mail.clear()
    context.get("sleep", time.sleep)(61)
    requested = client.post("/api/v1/auth/password-reset/request", body={"phone": phone})
    _expect_status(requested, {202}, "password reset expiry request")
    code = _read_otp(mail, email)
    sql("UPDATE email_otps SET expires_at = now() - interval '5 minutes' "
        f"WHERE purpose = 'RESET_PASSWORD' AND email = '{email}' AND consumed_at IS NULL")
    expired = _confirm_reset(client, phone, code, REPLACEMENT_PASSWORD + "-3")
    _expect_status(expired, {400}, "expired reset code")


def run_otp_forgot_password(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """Reset a disposable password on a disposable stack pointed at Mailpit.

    Requires context["apply_server_env"] (--dstack provisions a private
    backend); without it the row stays BLOCKED_CAPABILITY before Mailpit or a
    database is touched.
    """
    _campaign_serial(context)
    _campaign_base_url(context)
    phone = _require_disposable_phone(str(context.get("disposable_phone") or _new_disposable_phone()))
    email = str(context.get("disposable_email") or _new_disposable_email())

    def action(mail: Any) -> None:
        _complete_password_reset(context, mail, phone, email)
        _prove_expired_reset_code(context, mail, phone, email)

    _with_mail_sink(context, row, action)
    return {"password_reset": "request/wrong/confirm/reuse/resend all behaved",
            "expired_code": "aged OTP was refused by the disposable stack"}


def _require_deletion_last(context: dict[str, Any]) -> None:
    selected = tuple(context.get("selected_ids") or ())
    if not selected or selected[-1] != "account-deletion":
        raise HarnessError("account deletion must run last")


_DELETION_WRONG_PASSWORD_LABELS = ("Current password is incorrect",
                                  "Mật khẩu hiện tại không đúng")
_DELETION_CODE_SENT_LABELS = ("We sent a confirmation code to your verified email address",
                              "Chúng tôi đã gửi mã xác nhận đến địa chỉ email đã xác minh của bạn")
_DELETION_UNPROVEN = ("in-app confirmation, the deleted-account cache, peer-neutral "
                      "history and phone reuse on device stay unproven")


def _port_bindable(port: int) -> bool:
    """True when the loopback port can be bound right now (no server owns it)."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(("127.0.0.1", int(port)))
        except OSError:
            return False
        return True


def _deletion_app_port(context: dict[str, Any]) -> int | None:
    """Port the device app's compile-time base URL points at (10.0.2.2:<port>).

    Reads the pre-swap campaign base URL when present: _with_mail_sink
    temporarily replaces context["base_url"] with the stack's own URL.
    """
    base_url = str(context.get("deletion_app_base_url")
                   or context.get("base_url") or "")
    parsed = urlsplit(derive_emulator_base_url(base_url))
    return parsed.port


def _deletion_device_gap(context: dict[str, Any]) -> str | None:
    """Explicit reason the in-app deletion UI cannot be driven, or None."""
    if context.get("device") is None or context.get("adb") is None \
            or not context.get("helpers"):
        return (f"no device driver is bound to this context, so {_DELETION_UNPROVEN}")
    handle = context.get("dstack_handle") or {}
    stack_base = str(handle.get("base_url") or "")
    if not stack_base:
        return (f"the disposable stack did not report a base URL, so {_DELETION_UNPROVEN}")
    app_port = _deletion_app_port(context)
    stack_port = urlsplit(stack_base).port
    if app_port is None or stack_port != app_port:
        return (f"the app's compile-time base URL points at 10.0.2.2:{app_port} but the "
                f"disposable stack serves {stack_base or 'an unknown port'}; stop the shared "
                f"local server and rerun this row alone with --dstack so the stack can own "
                f"the app port, otherwise {_DELETION_UNPROVEN}")
    return None


def _deletion_sign_in(context: dict[str, Any], phone: str, password: str,
                      display_name: str) -> None:
    """Relaunch the app and sign the disposable account in through the real UI.

    A session left over from a previous backend is signed out first: its
    token cannot be valid against the disposable stack's fresh JWT secret.
    """
    patch = context["helpers"][2]
    _offline_relaunch(context)
    deadline = time.monotonic() + 45
    while True:
        nodes = _ui_nodes(context)
        if patch.login_form_visible(nodes):
            break
        if patch.inbox_visible(nodes):
            _offline_sign_out(context)
            break
        if time.monotonic() >= deadline:
            raise EnvironmentFailure(
                f"neither the login form nor the inbox appeared for {display_name}")
        time.sleep(0.7)
    _offline_sign_in(context, phone, display_name, password=password)


def _deletion_open_delete_screen(context: dict[str, Any]) -> None:
    """Inbox -> profile -> the destructive deleteAccountRow -> delete screen."""
    ui = context["device"]
    patch = context["helpers"][2]
    avatar = _wait_ui_node(
        context, lambda node: _rid_ends(node, "inboxUserAvatar"),
        "the inbox profile entry point was not visible")
    ui.tap_node(avatar)
    _wait_ui_node(
        context, lambda node: _rid_ends(node, "profileName"),
        "the profile screen did not open")
    _scroll_profile_until(context, "deleteAccountRow")
    row = _wait_ui_node(
        context, lambda node: _rid_ends(node, "deleteAccountRow"),
        "the Delete account row was not rendered")
    ui.tap_node(row)
    _wait_ui_node(
        context, lambda node: _rid_ends(node, "deleteAccountToolbar"),
        "the delete-account screen did not open", timeout=30)


def _deletion_wait_signed_out(context: dict[str, Any], timeout: float = 60) -> None:
    patch = context["helpers"][2]
    deadline = time.monotonic() + timeout
    while True:
        if patch.login_form_visible(_ui_nodes(context)):
            return
        if time.monotonic() >= deadline:
            raise ProductFailure("the app did not return to the login form after deletion")
        time.sleep(0.7)


def _deletion_device_slice(context: dict[str, Any], mail: Any, phone: str,
                           email: str, token: str, peer: dict[str, Any]) -> dict[str, Any]:
    """Drive the in-app deletion UI against the disposable stack.

    Real rids from fragment_delete_account.xml: passwordInput, confirmDelete,
    inlineError, sendDeletionCode, deletionCodeSentMessage, deletionCodeInput.
    The OTP code is read from Mailpit and never logged or captured.
    """
    client = context["client"]
    ui = context["device"]
    patch = context["helpers"][2]
    peer_name = str(peer.get("displayName") or "Journey Peer")
    peer_token = str(peer["accessToken"])
    peer_phone = str(peer["phone"])

    # Peer fixture: a direct conversation whose inbox row is titled by the
    # peer, plus one unique message authored by the doomed account.
    direct = client.post("/api/v1/conversations/direct", token=token,
                         body={"phone": peer_phone})
    _expect_status(direct, {200, 201}, "deletion peer direct conversation")
    conversation_id = str((direct.json() or {}).get("id") or "")
    if not conversation_id:
        raise EnvironmentFailure("the direct conversation returned no id")
    marker = "sj-del-" + uuid.uuid4().hex[:10]
    sent = client.post(f"/api/v1/conversations/{conversation_id}/messages",
                       token=token,
                       body={"clientMessageId": str(uuid.uuid4()), "type": "TEXT",
                             "content": marker, "attachmentIds": []})
    _expect_status(sent, {200, 201}, "deletion peer marker message")

    # 1. Deterministic sign-in of the disposable account through the real UI.
    _deletion_sign_in(context, phone, DISPOSABLE_PASSWORD, "Journey Delete")

    # 2. UI wrong-password refusal (password flow: passwordInput + confirmDelete).
    _deletion_open_delete_screen(context)
    patch.fill_login_field(ui, "passwordInput", "not-the-account-password")
    ui.key("KEYCODE_BACK")
    confirm = _wait_ui_node(
        context, lambda node: _rid_ends(node, "confirmDelete"),
        "the Delete my account button was not rendered")
    ui.tap_node(confirm)
    _wait_ui_node(
        context,
        lambda node: _rid_ends(node, "inlineError")
                     and patch.text_matches(node, _DELETION_WRONG_PASSWORD_LABELS),
        "the wrong-password refusal (inlineError 'Current password is incorrect') "
        "was not rendered")
    still_there = client.get("/api/v1/users/me", token=token)
    _expect_status(still_there, {200}, "account after the UI wrong-password refusal")

    # 3. In-app OTP confirmation. The code flow only renders for an account
    # with no password hash (the V33 Google-only state), so simulate that
    # supported server state on the disposable database — the same fixture
    # pattern the expired-reset-code proof uses — then re-enter the screen
    # so the profile refetch flips the UI to the code flow.
    sql = (context.get("dstack_handle") or {}).get("sql")
    if sql is None:
        raise CapabilityBlocked(
            f"the in-app deletion OTP flow needs the disposable database; rerun "
            f"with --dstack, otherwise {_DELETION_UNPROVEN}")
    sql(f"UPDATE users SET password_hash = NULL WHERE phone = '{phone}'")
    ui.key("KEYCODE_BACK")
    ui.key("KEYCODE_BACK")
    _offline_wait_inbox(
        context, "the inbox did not return before the in-app OTP pass")
    _deletion_open_delete_screen(context)
    send_code = _wait_ui_node(
        context, lambda node: _rid_ends(node, "sendDeletionCode"),
        "the Send code button was not rendered for the passwordless account")
    ui.tap_node(send_code)
    _wait_ui_node(
        context,
        lambda node: _rid_ends(node, "deletionCodeSentMessage")
                     and patch.text_matches(node, _DELETION_CODE_SENT_LABELS),
        "the deletion code-sent message was not rendered")
    code = _read_otp(mail, email)
    patch.fill_login_field(ui, "deletionCodeInput", code)
    ui.key("KEYCODE_BACK")
    confirm = _wait_ui_node(
        context, lambda node: _rid_ends(node, "confirmDelete"),
        "the confirm button was not rendered after the code was sent")
    ui.tap_node(confirm)
    _deletion_wait_signed_out(context)

    # 4. Post-deletion: token death, peer-neutral history, phone reuse, and
    #    no deleted-account cache on the freshly re-registered device session.
    rejected = client.get("/api/v1/users/me", token=token)
    _expect_status(rejected, {401}, "token after in-app account deletion")
    _history_item_by_content(client, peer_token, conversation_id, marker)
    reused = client.post(
        "/api/v1/auth/register",
        body={"phone": phone, "password": DISPOSABLE_PASSWORD,
              "displayName": "Journey Reused"},
    )
    _expect_status(reused, {200, 201}, "phone reuse after in-app deletion")
    _deletion_sign_in(context, phone, DISPOSABLE_PASSWORD, "Journey Reused")
    deadline = time.monotonic() + 20
    while True:
        _pull_refresh_inbox(context)
        if _inbox_conversation_row(context, peer_name) is None:
            break
        if time.monotonic() >= deadline:
            raise ProductFailure(
                "the deleted account's inbox cache still rendered the peer conversation")
        time.sleep(0.7)

    return {
        "sign_in": "the disposable account signed in through the real login form",
        "wrong_password_ui": "confirmDelete with a bad password rendered the "
                             "inlineError refusal and the account survived",
        "in_app_deletion": "sendDeletionCode + deletionCodeInput + confirmDelete "
                           "deleted the account and returned to the login form",
        "token_rejection": "the pre-deletion token is rejected after in-app deletion",
        "peer_history": "the peer's REST history keeps the deleted account's message",
        "cache_and_reuse": "the re-registered phone signs in on device to an inbox "
                           "with no deleted-account conversation row",
    }


def run_account_deletion(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """Delete a disposable account on a disposable stack, in-app when possible.

    The mail/REST flow runs when context["apply_server_env"] exists (--dstack).
    The in-app UI slice additionally needs the disposable stack to own the
    device app's compile-time port (10.0.2.2:<base_url port>); when it cannot,
    the row reports the explicit gap instead of a silent pass.
    """
    _campaign_serial(context)
    _campaign_base_url(context)
    _require_deletion_last(context)
    phone = _require_disposable_phone(str(context.get("disposable_phone") or _new_disposable_phone()))
    email = str(context.get("disposable_email") or _new_disposable_email())
    results: dict[str, Any] = {}

    # Pin the stack to the app's compiled port when that port is free, so the
    # device app can reach the disposable backend. An occupied port (the
    # shared local server) leaves the stack on a random port and the device
    # slice degrades to its explicit blocked reason below. The pre-swap base
    # URL is kept for the gap check because _with_mail_swap replaces it.
    app_port = _deletion_app_port(context)
    context["deletion_app_base_url"] = str(context.get("base_url") or "")
    if app_port is not None and _port_bindable(app_port):
        context["dstack_app_port"] = app_port

    def action(mail: Any) -> None:
        client = context["client"]
        registered = _register_disposable(client, phone, "Journey Delete")
        token = registered["accessToken"]
        _verify_recovery_email(client, mail, token, email)
        denied = client.request(
            "DELETE", "/api/v1/users/me", token=token,
            body={"password": "not-the-account-password"},
        )
        _expect_status(denied, {401}, "wrong password deletion")
        still_there = client.get("/api/v1/users/me", token=token)
        _expect_status(still_there, {200}, "account after wrong password")
        gap = _deletion_device_gap(context)
        if gap is None:
            peer_phone = _new_disposable_phone()
            peer_name = "Journey Peer " + uuid.uuid4().hex[:6]
            peer_registered = _register_disposable(client, peer_phone, peer_name)
            peer = {"accessToken": peer_registered["accessToken"],
                    "phone": peer_phone, "displayName": peer_name}
            results.update(_deletion_device_slice(
                context, mail, phone, email, token, peer))
            return
        # The device slice cannot run: still prove the REST/OTP deletion, then
        # report the explicit gap — never a silent pass.
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
            body={"phone": phone, "password": DISPOSABLE_PASSWORD,
                  "displayName": "Journey Reused"},
        )
        _expect_status(reused, {200, 201}, "phone reuse after deletion")
        raise CapabilityBlocked(gap)

    _with_mail_sink(context, row, action)
    if not results:
        raise CapabilityBlocked(row.capability_gap)
    return results


# --- accessibility-sweep -----------------------------------------------------

def _import_a11y_oracle():
    if str(DEVICE_DIR) not in sys.path:
        sys.path.insert(0, str(DEVICE_DIR))
    return importlib.import_module("a11y_oracle")


_A11Y_DENSITY_RE = re.compile(r"(\d{3,4})")


def _a11y_density(adb: Adb) -> float:
    """The effective DPI `adb shell wm density` reports (an override wins over
    the physical value). a11y_oracle rejects 160-based scale multipliers, so
    an unreadable density blocks the row instead of mis-scaling the floor."""
    output = adb.command("shell", "wm", "density", timeout=20, check=False) or ""
    numbers = _A11Y_DENSITY_RE.findall(output)
    if not numbers:
        raise CapabilityBlocked("wm density did not report a DPI; the a11y oracle needs it")
    return float(numbers[-1])


def _a11y_talkback_snapshot(adb: Adb) -> dict[str, str]:
    """The secure settings a11y_oracle.get_* reads; restored after the sweep."""
    return {
        "services": adb.command("shell", "settings", "get", "secure",
                                "enabled_accessibility_services", timeout=20, check=False),
        "enabled": adb.command("shell", "settings", "get", "secure",
                               "accessibility_enabled", timeout=20, check=False),
    }


def _a11y_talkback_restore(adb: Adb, before: dict[str, str]) -> None:
    # Same semantics as a11y_oracle.restore_talkback: a missing snapshot clears
    # the services list and an unreadable flag falls back to disabled, so a
    # swept device is never left with TalkBack force-enabled.
    services = str(before.get("services") or "").strip()
    restored = services if services not in ("", "null") else '""'
    flag = str(before.get("enabled") or "").strip()
    flag = flag if flag in ("0", "1") else "0"
    adb.command("shell", "settings", "put", "secure",
                "enabled_accessibility_services", restored, timeout=20, check=False)
    adb.command("shell", "settings", "put", "secure",
                "accessibility_enabled", flag, timeout=20, check=False)


def _a11y_reduced_motion_snapshot(adb: Adb) -> str:
    return adb.command("shell", "settings", "get", "global",
                       "animator_duration_scale", timeout=20, check=False)


def _a11y_reduced_motion_restore(adb: Adb, saved: str) -> None:
    if str(saved).strip() not in ("", "null"):
        adb.command("shell", "settings", "put", "global",
                    "animator_duration_scale", saved.strip(), timeout=20, check=False)


def _a11y_back_to_inbox(context: dict[str, Any]) -> None:
    """Press BACK until the inbox renders again (archive needs two presses).
    The inbox marker is checked before every press, so the inbox itself never
    receives a BACK that could exit the app."""
    ui = context["device"]
    deadline = time.monotonic() + _ui_timeout(context)
    while True:
        if any(_rid_ends(node, "conversationList")
               or _rid_ends(node, "inboxUserAvatar") for node in _ui_nodes(context)):
            return
        if time.monotonic() >= deadline:
            raise ProductFailure("the sweep could not return to the inbox between screens")
        ui.key("KEYCODE_BACK")
        time.sleep(0.6)


def _a11y_open_screen(context: dict[str, Any], screen: str, group_title: str) -> None:
    """Navigate to one critical screen from the inbox via real UI taps."""
    ui = context["device"]
    if screen == "inbox":
        return
    if screen in ("direct-chat", "group-chat"):
        title = "Binh" if screen == "direct-chat" else group_title
        deadline = time.monotonic() + _ui_timeout(context)
        row = None
        while row is None:
            row = _inbox_conversation_row(context, title)
            if row is not None:
                break
            if time.monotonic() >= deadline:
                raise ProductFailure(f"the {title!r} conversation row was not on the inbox")
            time.sleep(0.4)
        ui.tap_node(row)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "messageInput"),
            f"the {title!r} conversation did not open its composer")
        return
    if screen == "archive":
        # The shared-media browser is reached from a chat overflow, so open the
        # seeded direct first (the same path run_archive_preferences takes).
        _a11y_open_screen(context, "direct-chat", group_title)
        _open_chat_menu(context, _ARCHIVE_MENU_ITEM)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "archiveTabs"),
            "the shared-media screen did not open")
        return
    if screen == "settings":
        avatar = _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "inboxUserAvatar"),
            "the inbox avatar was not exposed")
        ui.tap_node(avatar)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "profileName"),
            "the profile/preferences screen did not open")
        return
    if screen == "labels":
        _open_assign_dialog(context)
        return
    if screen == "contacts":
        entry = _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "inboxContactsButton"),
            "the inbox contacts button was not exposed")
        ui.tap_node(entry)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "contactList"),
            "the contacts screen did not open")
        return
    raise HarnessError(f"unknown critical screen {screen!r}")


def _a11y_sweep_cell(context: dict[str, Any], oracle: Any, density: float,
                     variant: dict[str, str], screen: dict[str, str],
                     group_title: str) -> dict[str, Any]:
    """Visit one (variant, screen) cell and run every a11y_oracle check on it.

    A cell only passes when the oracle produced at least one assertion; a cell
    whose oracle could not run reports BLOCKED, never a silent screenshot pass.
    """
    ui = context["device"]
    row_dir = Path(context["row_dir"])
    name = screen["screen"]
    marker = screen["marker_rid"]
    _a11y_open_screen(context, name, group_title)
    if marker.startswith("text:"):
        # The labels dialog is built programmatically and carries no resource
        # ids at all — a rid marker can never fire, so these screens match on
        # localized title text instead ("text:EN|VI").
        texts = tuple(marker[5:].split("|"))
        _wait_ui_node(
            context,
            lambda node: node.text.strip() in texts,
            f"the {name} screen did not expose {marker}")
    else:
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, marker),
            f"the {name} screen did not expose {marker}")
    nodes = _ui_nodes(context)
    cell: dict[str, Any] = {
        "screen": name, "locale": variant["locale"],
        "night": variant["night"], "font_scale": variant["font_scale"],
    }
    try:
        report = oracle.summarize_findings(nodes, density=density, screen="auto")
    except oracle.A11yOracleError as error:
        cell.update({"status": "BLOCKED", "reason": str(error)})
        return cell
    try:
        ui.screenshot(str(row_dir / (
            f"a11y-{variant['locale']}-{variant['night']}-"
            f"{variant['font_scale']}-{name}.png")))
    except Exception as error:
        context.setdefault("cleanup_errors", []).append(
            f"a11y screenshot {name}: {type(error).__name__}")
    cell.update({
        "status": "PASS" if report["ok"] else "FINDINGS",
        "node_count": report["node_count"],
        "interactive_count": report["interactive_count"],
        "checks": {check: {
                       "ok": entry["ok"], "count": entry["count"],
                       "clipped_skipped_count": entry.get("clipped_skipped_count", 0),
                       "framework_skipped_count": entry.get("framework_skipped_count", 0),
                   } for check, entry in report["checks"].items()},
    })
    if not report["ok"]:
        # Keep the evidence bounded: the first findings per check carry the
        # rid/text/bounds a TalkBack pass needs to confirm.
        findings: list[dict[str, Any]] = []
        for check, entry in report["checks"].items():
            for finding in entry["findings"][:5]:
                findings.append({
                    "check": check,
                    "issue": finding.get("issue"),
                    "rid": finding.get("rid"),
                    "text": finding.get("text"),
                    "content_desc": finding.get("content_desc"),
                    "bounds": finding.get("bounds"),
                })
        cell["findings"] = findings
    return cell


def run_accessibility_sweep(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """Sweep the critical-screen inventory under every display variant.

    For each (locale, night, font_scale) tuple the executor applies the display
    settings, navigates to every CRITICAL_SCREENS entry via real UI taps and
    runs the a11y_oracle checks (unlabeled interactive nodes, sub-48dp touch
    targets, unnamed images, focus-order candidates) on the dumped UI tree.
    Every cell carries oracle assertions or reports BLOCKED; a screenshot-only
    cell is never a pass. TalkBack and reduced-motion state are snapshotted,
    recorded and restored -- the dump oracles are triage candidates pending a
    TalkBack-verified pass (see a11y_oracle.HEURISTIC_CAVEATS).
    """
    _campaign_serial(context)
    _campaign_base_url(context)
    _prepare_seeded_an(context)
    api, _adb_ui, patch = context["helpers"]
    recorder: RecordingClient = context["client"]
    adb: Adb = context["adb"]
    oracle = _import_a11y_oracle()
    an = _actor_session(api, recorder, api.USER_A["phone"])
    binh = _actor_session(api, recorder, api.USER_C["phone"])
    an_id = str((an.get("user") or {}).get("id") or "")
    binh_id = str((binh.get("user") or {}).get("id") or "")
    if not an_id or not binh_id:
        raise EnvironmentFailure("seed actor sessions did not expose user ids")

    tag = uuid.uuid4().hex[:8]
    group_title = f"sj-a11y-{tag}"
    marker_text = f"sj-a11y-{tag}-msg"
    _, conversation = api.direct_conversation(recorder, an["accessToken"], api.USER_C["phone"])
    direct_id = str(conversation.get("id") or "")
    if not direct_id:
        raise EnvironmentFailure("the seeded direct conversation returned no id")
    created_group = _offline_rest_once(
        lambda: recorder.post(
            "/api/v1/conversations/group", token=an["accessToken"],
            body={"title": group_title, "memberIds": [binh_id]}),
        {200, 201}, "sweep group create")
    group_id = str((created_group.json() or {}).get("id") or "")
    if not group_id:
        raise EnvironmentFailure("the sweep group create returned no id")
    for conversation_id in (direct_id, group_id):
        _offline_send_rest(recorder, lambda: an["accessToken"], conversation_id, marker_text)

    # The activity-scoped ViewModels only fetch once per process lifetime, so
    # the REST-seeded group needs a cold start before it can render.
    adb.command("shell", "am", "force-stop", PACKAGE, timeout=20)
    adb.command("shell", "am", "start", "-n", f"{PACKAGE}/{MAIN_ACTIVITY}", timeout=30)
    _wait_ui_node(
        context,
        lambda node: (_rid_ends(node, "conversationList")
                      or _rid_ends(node, "inboxUserAvatar")),
        "the inbox did not reload after the sweep fixture relaunch",
        timeout=60)
    # A just-seeded group can render below the fold on a loaded inbox — use
    # the scrolling finder instead of a bare text wait (same class of fix as
    # the below-fold row waits elsewhere).
    deadline = time.monotonic() + 60
    while not _inbox_row_found_scrolled(context, group_title):
        if time.monotonic() >= deadline:
            raise ProductFailure("the seeded sweep group did not reach the inbox")
        time.sleep(0.5)
    # The Binh DM also carries a fresh fixture message; give its row the same
    # convergence budget so the direct-chat cell never hunts a row whose sync
    # is still landing after the cold start.
    deadline = time.monotonic() + 60
    while not _inbox_row_found_scrolled(context, "Binh"):
        if time.monotonic() >= deadline:
            raise ProductFailure("the seeded peer conversation did not reach the inbox")
        time.sleep(0.5)

    density = _a11y_density(adb)
    before = snapshot_settings(adb, patch)
    talkback_before = _a11y_talkback_snapshot(adb)
    motion_before = _a11y_reduced_motion_snapshot(adb)
    cells: list[dict[str, Any]] = []
    cleanup_errors: list[str] = []
    primary_error: Exception | None = None
    try:
        for variant in ACCESSIBILITY_VARIANTS:
            set_accessibility_variant(adb, variant)
            _wait_ui_node(
                context,
                lambda node: (_rid_ends(node, "conversationList")
                              or _rid_ends(node, "inboxUserAvatar")),
                "the inbox did not render after the variant switch",
                timeout=30)
            for screen in CRITICAL_SCREENS:
                cell = _a11y_sweep_cell(
                    context, oracle, density, variant, screen, group_title)
                cells.append(cell)
                if cell["status"] == "BLOCKED":
                    continue
                if screen["screen"] == "labels":
                    # Dismiss the labels dialog by its own Done button; BACK on
                    # a dialog can also dismiss the parent screen.
                    _tap_dialog_button(
                        context, _LBL_DONE,
                        "the labels dialog did not offer Done after the sweep cell")
                _a11y_back_to_inbox(context)
    except Exception as error:
        primary_error = error
    finally:
        try:
            restore_settings(adb, patch, before)
        except Exception as error:
            cleanup_errors.append(f"settings restore: {type(error).__name__}")
        try:
            _a11y_talkback_restore(adb, talkback_before)
        except Exception as error:
            cleanup_errors.append(f"talkback restore: {type(error).__name__}")
        try:
            _a11y_reduced_motion_restore(adb, motion_before)
        except Exception as error:
            cleanup_errors.append(f"reduced-motion restore: {type(error).__name__}")
        if cleanup_errors:
            context["cleanup_errors"] = cleanup_errors
    if primary_error is not None:
        raise primary_error
    if context.get("cleanup_errors"):
        raise HarnessError("the sweep could not restore every display setting")

    row_dir = Path(context["row_dir"])
    _write_json(row_dir / "a11y-sweep-cells.json", cells)
    blocked = [cell for cell in cells if cell["status"] == "BLOCKED"]
    if blocked:
        raise CapabilityBlocked(
            f"{len(blocked)} of {len(cells)} sweep cells could not run the a11y oracle: "
            + "; ".join(f"{cell['screen']}[{cell['locale']}/{cell['night']}/"
                        f"{cell['font_scale']}]: {cell['reason']}" for cell in blocked))
    failing = [cell for cell in cells if cell["status"] == "FINDINGS"]
    if failing:
        raise ProductFailure(
            f"{len(failing)} of {len(cells)} sweep cells failed the a11y oracle "
            f"(details in a11y-sweep-cells.json): "
            + "; ".join(f"{cell['screen']}[{cell['locale']}/{cell['night']}/"
                        f"{cell['font_scale']}]: "
                        + ", ".join(f"{check}={entry['count']}"
                                    for check, entry in cell["checks"].items()
                                    if not entry["ok"])
                        for cell in failing))
    return {
        "matrix": (f"{len(ACCESSIBILITY_VARIANTS)} variants x "
                   f"{len(CRITICAL_SCREENS)} critical screens, all oracle cells PASS"),
        "cells": cells,
        "density_dpi": density,
        "talkback": {"services_before": talkback_before["services"],
                     "enabled_before": talkback_before["enabled"],
                     "restored": True},
        "reduced_motion": {"animator_duration_scale_before": motion_before,
                           "restored": True},
        "heuristic_caveats": list(oracle.HEURISTIC_CAVEATS),
        "restore": ("locale, night mode, font scale, TalkBack and animator "
                    "scale restored in finally"),
    }


_AUTH_NEW_PASSWORD = DISPOSABLE_PASSWORD + "-changed"
_AUTH_LOGOUT_ALL_LABELS = ("Sign out all devices", "Đăng xuất mọi thiết bị")


def _auth_barrier_factory(context: dict[str, Any]) -> Any:
    """Resolve the barrier factory; imports response_barrier lazily."""
    factory = context.get("barrier_factory")
    if factory is not None:
        return factory
    if str(E2E_DIR) not in sys.path:
        sys.path.insert(0, str(E2E_DIR))
    import response_barrier

    def start(upstream: str) -> Any:
        barrier = response_barrier.Barrier(upstream)
        barrier.__enter__()
        return barrier

    return start


def _auth_reader_factory(context: dict[str, Any]) -> Any:
    """Resolve the STOMP reader factory; imports realtime_probe lazily."""
    factory = context.get("frame_reader_factory")
    if factory is not None:
        return factory
    if str(DEVICE_DIR) not in sys.path:
        sys.path.insert(0, str(DEVICE_DIR))
    from realtime_probe import FrameReader
    return FrameReader


def _auth_note(scenarios: list[dict[str, Any]], name: str, evidence: str) -> None:
    scenarios.append({"scenario": name, "status": "driven", "evidence": evidence})


_AUTH_LOGIN_BACKOFF_MAX_SECONDS = 60


def _auth_login_once(client: Any, phone: str, password: str) -> Any:
    return client.post("/api/v1/auth/login",
                       body={"phone": phone, "password": password})


def _auth_login_with_rate_limit_backoff(client: Any, phone: str,
                                        password: str) -> Any:
    """POST /auth/login, retrying once after a bounded 429 backoff.

    Serial board runs push the per-phone login limiter over the edge; the
    server answers HTTP 429 with error.details.retryAfterSeconds. Sleep
    exactly that long and retry ONCE when the hint is present and at most
    the bound; a second 429, a missing hint, or a larger hint is returned
    to the caller so the rate limit surfaces, never masked.
    """
    response = _auth_login_once(client, phone, password)
    if response.status == 429:
        details = ((response.json() or {}).get("error") or {}).get("details") or {}
        try:
            retry_after = int(details.get("retryAfterSeconds") or 0)
        except (TypeError, ValueError):
            retry_after = 0
        if 1 <= retry_after <= _AUTH_LOGIN_BACKOFF_MAX_SECONDS:
            time.sleep(retry_after)
            response = _auth_login_once(client, phone, password)
    return response


def _auth_rest_lifecycle(context: dict[str, Any], client: Any,
                         scenarios: list[dict[str, Any]],
                         accounts: list[dict[str, Any]]) -> None:
    """Drive register/login/invalid credentials/refresh rotation/logout/logout-all/password change."""
    phone = _require_disposable_phone(str(context.get("auth_phone") or _new_disposable_phone()))
    registered = _register_disposable(client, phone, "Journey Auth")
    accounts.append({"label": "rest", "phone": phone,
                     "password": DISPOSABLE_PASSWORD,
                     "token": registered.get("accessToken")})
    duplicate = client.post(
        "/api/v1/auth/register",
        body={"phone": phone, "password": DISPOSABLE_PASSWORD,
              "displayName": "Journey Auth Dup"})
    _expect_status(duplicate, {409}, "duplicate registration")
    _auth_note(scenarios, "register",
               "201 with a token pair; duplicate phone rejected with HTTP 409")

    def login(password: str) -> dict[str, Any]:
        response = _auth_login_with_rate_limit_backoff(client, phone, password)
        _expect_status(response, {200}, "auth login")
        data = response.json() or {}
        if not data.get("accessToken") or not data.get("refreshToken"):
            raise EnvironmentFailure("login did not return a token pair")
        return data

    session = login(DISPOSABLE_PASSWORD)
    me = client.get("/api/v1/users/me", token=session["accessToken"])
    _expect_status(me, {200}, "users/me after login")
    _auth_note(scenarios, "login", "200; /users/me confirms the disposable identity")

    bad = client.post("/api/v1/auth/login",
                      body={"phone": phone, "password": "not-the-account-password"})
    _expect_status(bad, {401}, "invalid credential login")
    _auth_note(scenarios, "invalid-credentials-rest",
               "wrong password rejected with HTTP 401")

    refreshed = client.post("/api/v1/auth/refresh",
                            body={"refreshToken": session["refreshToken"]})
    _expect_status(refreshed, {200}, "token refresh")
    refresh_data = refreshed.json() or {}
    if (not refresh_data.get("accessToken") or not refresh_data.get("refreshToken")
            or refresh_data["refreshToken"] == session["refreshToken"]):
        raise EnvironmentFailure("refresh did not rotate the token pair")
    me = client.get("/api/v1/users/me", token=refresh_data["accessToken"])
    _expect_status(me, {200}, "users/me with refreshed access token")
    reused = client.post("/api/v1/auth/refresh",
                         body={"refreshToken": session["refreshToken"]})
    _expect_status(reused, {401}, "rotated-away refresh token rejection")
    _auth_note(scenarios, "refresh",
               "200; the rotated pair is accepted by /users/me and reusing "
               "the rotated-away refresh token is rejected with HTTP 401")

    # Single logout must kill only the presenting session: a concurrent
    # login has to survive by rotating its own kept refresh token.
    session = login(DISPOSABLE_PASSWORD)
    concurrent = login(DISPOSABLE_PASSWORD)
    out = client.post("/api/v1/auth/logout", token=session["accessToken"],
                      body={"refreshToken": session["refreshToken"]})
    _expect_status(out, {204}, "logout")
    _expect_status(client.get("/api/v1/users/me", token=session["accessToken"]),
                   {401}, "logout access token rejection")
    _expect_status(client.post("/api/v1/auth/refresh",
                               body={"refreshToken": session["refreshToken"]}),
                   {401}, "logout refresh token rejection")
    survived = client.post("/api/v1/auth/refresh",
                           body={"refreshToken": concurrent["refreshToken"]})
    _expect_status(survived, {200},
                   "concurrent-session refresh after single logout")
    me = client.get("/api/v1/users/me",
                    token=(survived.json() or {}).get("accessToken"))
    _expect_status(me, {200}, "users/me after the concurrent session rotated")
    _auth_note(scenarios, "logout",
               "204; the logged-out session's access and refresh tokens are "
               "rejected while a concurrent session recovers by rotating its "
               "kept refresh token")

    # Logout-all must kill every session, not only the one that called it.
    session = login(DISPOSABLE_PASSWORD)
    concurrent = login(DISPOSABLE_PASSWORD)
    out = client.post("/api/v1/auth/logout-all", token=session["accessToken"])
    _expect_status(out, {204}, "logout-all")
    for label, dead in (("issuing session", session),
                        ("concurrent session", concurrent)):
        _expect_status(client.get("/api/v1/users/me", token=dead["accessToken"]),
                       {401}, f"logout-all {label} access token rejection")
        _expect_status(client.post("/api/v1/auth/refresh",
                                   body={"refreshToken": dead["refreshToken"]}),
                       {401}, f"logout-all {label} refresh token rejection")
    _auth_note(scenarios, "logout-all",
               "204; both the issuing session and a concurrent second session "
               "lose their access and refresh tokens")

    session = login(DISPOSABLE_PASSWORD)
    changed = client.patch("/api/v1/auth/password",
                           token=session["accessToken"],
                           body={"oldPassword": DISPOSABLE_PASSWORD,
                                 "newPassword": _AUTH_NEW_PASSWORD})
    _expect_status(changed, {204}, "password change")
    _expect_status(client.post("/api/v1/auth/login",
                               body={"phone": phone, "password": DISPOSABLE_PASSWORD}),
                   {401}, "old password rejection")
    session = login(_AUTH_NEW_PASSWORD)
    me = client.get("/api/v1/users/me", token=session["accessToken"])
    _expect_status(me, {200}, "users/me with post-change session")
    _auth_note(scenarios, "password-change",
               "204; old password 401, new password 200")
    accounts[0]["password"] = _AUTH_NEW_PASSWORD
    accounts[0]["token"] = session["accessToken"]


def _auth_paused_frame(context: dict[str, Any], client: Any, base_url: str,
                       scenarios: list[dict[str, Any]],
                       blocked: list[dict[str, Any]],
                       accounts: list[dict[str, Any]]) -> None:
    """Hold account A's inbound STOMP MESSAGE_CREATED through the WS tunnel.

    Account B keeps receiving the same broadcast live; after A's socket
    closes, releasing the hold must not deliver A's frame anywhere else.
    """
    name = "paused-frame-a-to-b-isolation"
    try:
        barrier_factory = _auth_barrier_factory(context)
        reader_factory = _auth_reader_factory(context)
    except Exception as error:
        blocked.append({"scenario": name,
                        "reason": f"STOMP tunnel helpers unavailable: {type(error).__name__}"})
        return
    phone_a = _require_disposable_phone(
        str(context.get("auth_pause_phone_a") or _new_disposable_phone()))
    phone_b = _require_disposable_phone(
        str(context.get("auth_pause_phone_b") or _new_disposable_phone()))
    account_a = _register_disposable(client, phone_a, "Journey Pause A")
    account_b = _register_disposable(client, phone_b, "Journey Pause B")
    accounts.append({"label": "pause-a", "phone": phone_a,
                     "password": DISPOSABLE_PASSWORD,
                     "token": account_a.get("accessToken")})
    accounts.append({"label": "pause-b", "phone": phone_b,
                     "password": DISPOSABLE_PASSWORD,
                     "token": account_b.get("accessToken")})
    token_a, token_b = account_a["accessToken"], account_b["accessToken"]
    direct = client.post("/api/v1/conversations/direct", token=token_a,
                         body={"phone": phone_b})
    _expect_status(direct, {200, 201}, "A-B direct conversation")
    conversation_id = str((direct.json() or {}).get("id") or "")
    if not conversation_id:
        raise EnvironmentFailure("the direct conversation returned no id")
    topic = f"/topic/conversations.{conversation_id}"
    marker = str(context.get("auth_pause_marker")
                 or ("sj-paused-" + uuid.uuid4().hex[:10]))
    barrier = reader_a = reader_b = None
    try:
        try:
            barrier = barrier_factory(base_url)
            barrier.hold(method="MESSAGE",
                         path=rf"^/topic/conversations\.{re.escape(conversation_id)}$",
                         token_contains=token_a)
            base = str(getattr(barrier, "url", "") or base_url)
            ws_url = base.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
            reader_a = reader_factory(ws_url, token_a)
            reader_a.subscribe(topic)
            reader_b = reader_factory(ws_url, token_b)
            reader_b.subscribe(topic)
        except Exception as error:
            # The WS hold tunnel is a harness capability, not a product
            # assertion: on a stack that cannot establish it, the paused-frame
            # hold stays unproven and the row reports partial coverage
            # instead of failing (plan 2.3(b); the live hold proof is the
            # deferred 2.3(a) slice).
            blocked.append({"scenario": name,
                            "reason": ("the WS hold tunnel could not be "
                                       f"established ({type(error).__name__}); the "
                                       "paused-frame STOMP hold / cross-session "
                                       "socket isolation stays unproven on this stack")})
            return
        sent = client.post(f"/api/v1/conversations/{conversation_id}/messages",
                           token=token_b,
                           body={"clientMessageId": str(uuid.uuid4()),
                                 "type": "TEXT", "content": marker,
                                 "attachmentIds": []})
        _expect_status(sent, {200, 201}, "paused-frame marker send")
        try:
            barrier.wait_held(1, timeout=20)
        except Exception as error:
            raise ProductFailure(
                "account A's inbound STOMP frame was not held by the tunnel") from error

        def topic_events(reader: Any, seconds: float) -> list[dict[str, Any]]:
            found = []
            for event in reader.events(seconds):
                context.setdefault("stomp_events", []).append(event)
                if (event.get("eventType") == "MESSAGE_CREATED"
                        and str(event.get("_destination", "")) == topic
                        and (event.get("payload") or {}).get("content") == marker):
                    found.append(event)
            return found

        live = topic_events(reader_b, 3.0)
        if len(live) != 1:
            raise ProductFailure(
                f"account B observed {len(live)} live topic frames, expected 1")
        items = _history_items(client, token_b, conversation_id)
        if sum(1 for item in items if item.get("content") == marker) != 1:
            raise ProductFailure("peer history does not show exactly one marker")
        reader_a.close()
        reader_a = None
        barrier.release()
        leaked = topic_events(reader_b, 1.5)
        if leaked:
            raise ProductFailure("the held account-A frame crossed into account B")
        _auth_note(scenarios, name,
                   "A's inbound MESSAGE_CREATED stayed held in the WS tunnel; B received "
                   "exactly one live copy and nothing after A closed and the hold released")
    finally:
        for reader in (reader_a, reader_b):
            if reader is not None:
                try:
                    reader.close()
                except Exception:
                    pass
        if barrier is not None:
            close = getattr(barrier, "close", None)
            if close is not None:
                try:
                    close()
                except Exception:
                    pass


def _auth_invalid_login_ui(context: dict[str, Any], phone: str) -> None:
    """Wrong password must keep the form and render the accessible error."""
    patch = context["helpers"][2]
    ui = context["device"]
    patch.fill_login_field(ui, "phoneInput", phone)
    ui.key("KEYCODE_BACK")
    patch.fill_login_field(ui, "passwordInput", "not-the-account-password")
    context["adb"].command("shell", "input", "keyevent", "4",
                           timeout=15, check=False)
    time.sleep(1)
    nodes = _ui_nodes(context)
    action = patch.node_by_id(nodes, "primaryAction") or next(
        (node for node in nodes
         if patch.text_matches(node, ("Sign in", "Đăng nhập"))), None)
    if action is None:
        raise EnvironmentFailure("the sign-in control was not exposed")
    ui.tap_node(action)
    _wait_ui_node(
        context,
        lambda node: _rid_ends(node, "errorBanner") and bool((node.text or "").strip()),
        "the invalid-credential error banner did not appear", timeout=20)


def _auth_sign_out_all(context: dict[str, Any]) -> None:
    """Profile -> Sign out all devices -> confirm, landing on the login form."""
    ui = context["device"]
    avatar = _wait_ui_node(
        context, lambda node: _rid_ends(node, "inboxUserAvatar"),
        "the inbox profile entry point was not visible")
    ui.tap_node(avatar)
    _scroll_profile_until(context, "logoutAllRow")
    row = _wait_ui_node(
        context, lambda node: _rid_ends(node, "logoutAllRow"),
        "the Sign out all devices row was not rendered")
    ui.tap_node(row)
    confirm = _wait_ui_node(
        context,
        lambda node: node.text.strip() in _AUTH_LOGOUT_ALL_LABELS
                     and node.clickable and not _rid_ends(node, "logoutAllRow"),
        "the Sign out all devices confirmation was not rendered")
    ui.tap_node(confirm)
    patch = context["helpers"][2]
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if patch.login_form_visible(_ui_nodes(context)):
            return
        time.sleep(0.7)
    raise EnvironmentFailure("the login form did not appear after Sign out all devices")


def _auth_device_scenarios(context: dict[str, Any], client: Any,
                           scenarios: list[dict[str, Any]],
                           blocked: list[dict[str, Any]],
                           accounts: list[dict[str, Any]]) -> None:
    """Drive the UI scenarios when a device-bound campaign supplies the stack."""
    blocked.append({"scenario": "register-ui",
                    "reason": "app sign-up is email+OTP; the mail sink is not applied "
                              "to the local server"})
    blocked.append({"scenario": "password-change-ui",
                    "reason": "the change-password dialog has no wired UI driver; "
                              "REST PATCH /auth/password is driven"})
    device, helpers = context.get("device"), context.get("helpers")
    if device is None or helpers is None or context.get("adb") is None:
        reason = "no device-bound campaign context (adb/device/helpers absent)"
        for name in ("invalid-credentials-ui", "saved-session-restart",
                     "logout-ui", "logout-all-ui", "account-switch-ui"):
            blocked.append({"scenario": name, "reason": reason})
        return
    patch = helpers[2]
    ui = device
    phone_a = _require_disposable_phone(
        str(context.get("auth_ui_phone_a") or _new_disposable_phone()))
    phone_b = _require_disposable_phone(
        str(context.get("auth_ui_phone_b") or _new_disposable_phone()))
    _register_disposable(client, phone_a, "Journey UI A")
    _register_disposable(client, phone_b, "Journey UI B")
    # Device sign-out revokes every access token the account owns, so the
    # harness sessions cannot be reused for cleanup; relogin at delete time.
    accounts.append({"label": "ui-a", "phone": phone_a,
                     "password": DISPOSABLE_PASSWORD, "token": None})
    accounts.append({"label": "ui-b", "phone": phone_b,
                     "password": DISPOSABLE_PASSWORD, "token": None})

    nodes = _ui_nodes(context)
    if patch.inbox_visible(nodes):
        _offline_sign_out(context)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if patch.login_form_visible(_ui_nodes(context)):
            break
        time.sleep(0.7)
    else:
        raise EnvironmentFailure("the login form was not available for the auth UI scenarios")

    _auth_invalid_login_ui(context, phone_a)
    _auth_note(scenarios, "invalid-credentials-ui",
               "wrong password kept the form and rendered the error banner")

    try:
        _offline_sign_in(context, phone_a, "Journey UI A")
        _offline_force_stop(context)
        _offline_relaunch(context)
        _offline_wait_inbox(context,
                            "the saved session did not restore the inbox after restart")
        profile = _wait_ui_node(
            context, lambda node: _rid_ends(node, "inboxUserAvatar"),
            "the inbox profile entry point was not visible after restart",
            timeout=30)
        ui.tap_node(profile)
        _wait_ui_node(
            context,
            lambda node: _rid_ends(node, "profileName")
                         and node.text.strip() == "Journey UI A",
            "the restarted session did not identify Journey UI A", timeout=20)
        ui.key("KEYCODE_BACK")
        _offline_wait_inbox(context, "the inbox did not return after the restart check")
        _auth_note(scenarios, "saved-session-restart",
                   "force-stop + relaunch restored the inbox as Journey UI A "
                   "without re-login")

        _offline_sign_out(context)
        _auth_note(scenarios, "logout-ui",
                   "profile sign-out returned to the login form")

        _offline_sign_in(context, phone_a, "Journey UI A")
        _auth_sign_out_all(context)
        _auth_note(scenarios, "logout-all-ui",
                   "sign-out-all devices returned to the login form")

        _offline_sign_in(context, phone_a, "Journey UI A")
        _offline_sign_out(context)
        _offline_sign_in(context, phone_b, "Journey UI B")
        _auth_note(scenarios, "account-switch-ui",
                   "A to B switch verified profileName Journey UI B; "
                   "A's session ended at sign-out")
    finally:
        try:
            _offline_restore_an(context)
        except Exception as error:
            context.setdefault("cleanup_errors", []).append(
                f"device account restore: {type(error).__name__}")


def _auth_cleanup_accounts(client: Any,
                           accounts: list[dict[str, Any]]) -> list[str]:
    """Best-effort DELETE /users/me for each disposable account."""
    errors: list[str] = []
    for account in accounts:
        label = account["label"]
        token = account.get("token")
        try:
            for attempt in range(2):
                if not token:
                    login = _auth_login_with_rate_limit_backoff(
                        client, account["phone"], account["password"])
                    if login.status != 200:
                        errors.append(f"{label}: relogin HTTP {login.status}")
                        break
                    token = (login.json() or {}).get("accessToken")
                deleted = client.request(
                    "DELETE", "/api/v1/users/me", token=token,
                    body={"password": account["password"]})
                if deleted.status in (200, 204):
                    break
                if deleted.status == 401 and attempt == 0:
                    token = None
                    continue
                errors.append(f"{label}: delete HTTP {deleted.status}")
                break
        except Exception as error:
            errors.append(f"{label}: {type(error).__name__}")
    return errors


def run_auth_session_lifecycle(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    """Drive each auth/session scenario and report exact per-run coverage.

    REST token lifecycle runs through context["client"]; the paused-frame
    A->B isolation uses response_barrier's WebSocket tunnel; device UI
    scenarios run only when the campaign supplies adb/device/helpers. The
    REST lifecycle is the row's proven core (plan 2.3(b)): it must pass or
    the row fails. Scenarios that cannot be driven land in blocked and the
    row returns partial coverage naming each undriven scenario — the
    paused-frame STOMP hold stays unproven until the deferred full hold
    proof (plan 2.3(a)) drives it through the tunnel against a live
    server. A driven scenario that fails still fails the row; nothing
    reports proven without its evidence.
    """
    _campaign_serial(context)
    base_url = _campaign_base_url(context)
    client = context.get("client")
    if client is None:
        raise HarnessError("client is required")
    row_dir = Path(context.get("row_dir") or context.get("evidence_root") or ".")
    scenarios: list[dict[str, Any]] = []
    blocked: list[dict[str, Any]] = []
    accounts: list[dict[str, Any]] = []
    primary_error: Exception | None = None
    try:
        _auth_rest_lifecycle(context, client, scenarios, accounts)
        _auth_paused_frame(context, client, base_url, scenarios, blocked, accounts)
        _auth_device_scenarios(context, client, scenarios, blocked, accounts)
    except Exception as error:
        primary_error = error
        raise
    finally:
        cleanup_errors = _auth_cleanup_accounts(client, accounts)
        context.setdefault("cleanup_errors", []).extend(cleanup_errors)
        _write_json(row_dir / "auth-scenarios.json",
                    {"scenarios": scenarios, "blocked": blocked})
        if primary_error is None and context.get("cleanup_errors"):
            raise HarnessError("auth-session cleanup failed")
    if blocked:
        unproven = "; ".join(
            f"{item['scenario']} ({item['reason']})" for item in blocked)
        coverage = ("partial: the REST session lifecycle is proven on disposable "
                    "accounts; unproven: " + unproven)
    else:
        coverage = ("full: the REST session lifecycle, the paused-frame STOMP "
                    "hold and the bound device scenarios were all driven")
    return {"scenarios": [item["scenario"] for item in scenarios],
            "blocked": blocked,
            "coverage": coverage}


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


_ROW_EXECUTOR_NAMES: dict[str, str] = {
    "auth-login": "run_auth_login",
    "chat-send-receive": "run_chat_send_receive",
    "chat-interactions": "run_chat_interactions",
    "summary-ask": "run_summary_ask",
    "archive-preferences": "run_archive_preferences",
    "labels-lifecycle": "run_labels_lifecycle",
    "groups-contacts": "run_groups_contacts",
    "polls-reminders": "run_polls_reminders",
    "offline-sync": "run_offline_sync",
    "auth-session-lifecycle": "run_auth_session_lifecycle",
    "calls-injected-notification": "run_calls_injected_notification",
    "injected-notifications": "run_injected_notifications",
    "otp-forgot-password": "run_otp_forgot_password",
    "account-deletion": "run_account_deletion",
    "accessibility-sweep": "run_accessibility_sweep",
    "media": "run_media_upload_slice",
}


def run_registered_row(row: JourneyRow, context: dict[str, Any]) -> dict[str, Any]:
    # Registered executors decide their own capability gate: a row whose gap is
    # only the missing apply_server_env hook still runs its mail/REST portion
    # when --dstack supplies one, then reports the residual gap itself. The
    # lookup stays dynamic so tests can patch the executor by name.
    executor = globals().get(_ROW_EXECUTOR_NAMES.get(row.id, ""))
    if executor is not None:
        return executor(row, context)
    if row.capability_gap:
        raise CapabilityBlocked(row.capability_gap)
    return _capability_runner(row, context)


def _status_for_error(error: Exception) -> str:
    if isinstance(error, CapabilityBlocked):
        return "BLOCKED_CAPABILITY"
    if isinstance(error, ProductFailure):
        return "FAIL_PRODUCT"
    if isinstance(error, (EnvironmentFailure, HarnessError)):
        return "FAIL_HARNESS_OR_ENVIRONMENT"
    return "FAIL_HARNESS_OR_ENVIRONMENT"


def _uiautomator_pids(ps_output: str) -> list[str]:
    """PIDs of `uiautomator` processes listed in `ps -A -o PID,ARGS` output.

    Matched on the cmdline rather than the comm name: the app_process
    holding the UiAutomation registration is renamed to `uiautomator` only
    after JVM init, so a young or wedged holder appears as
    `app_process /system/bin com.android.commands.uiautomator.Launcher`."""
    pids: list[str] = []
    for line in ps_output.splitlines():
        fields = line.split()
        if fields and fields[0].isdigit() and "uiautomator" in line:
            pids.append(fields[0])
    return pids


def _kill_orphan_uiautomators(adb: Adb) -> list[str]:
    """Kill stale `uiautomator` shell processes left over from earlier rows.

    A dump that was still shutting down holds the UiAutomationService
    registration; the next `uiautomator dump` then FATALs inside the shell
    process. Housekeeping must never decide a row's verdict, so every adb
    failure is swallowed and the killed pids are only reported back.
    """
    try:
        pids = _uiautomator_pids(adb.command(
            "shell", "ps", "-A", "-o", "PID,ARGS", timeout=15, check=False))
    except Exception:
        return []
    killed: list[str] = []
    for pid in pids:
        try:
            adb.command("shell", "kill", "-9", pid, timeout=15, check=False)
            killed.append(pid)
        except Exception:
            pass
    return killed


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
                            "cleanup_errors": [], "seed_heals": []})
            attempt_dir: Path = context["row_dir"]
            attempt_dir.mkdir(parents=True, exist_ok=True)
            record_start = len(context["client"].records)
            baseline: set[str] = set()
            adb: Adb = context["adb"]
            device = context["device"]
            patch = context["helpers"][2]
            _kill_orphan_uiautomators(adb)
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
                if os.environ.get("SJ_DEBUG_TRACEBACK"):
                    import traceback
                    traceback.print_exc()
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
                    "seed_heals": list(context.get("seed_heals") or []),
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
    # The effective device locale lives on persist.sys.locale — system_locales
    # alone does not describe what apps render on API 33+.
    values["device_locale"] = adb.command("shell", "getprop", "persist.sys.locale", timeout=20)
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
        saved_device_locale = before.get("device_locale")
        if saved_device_locale not in (None, "", "null"):
            adb.command("shell", "cmd", "locale", "set-device-locale",
                        saved_device_locale, timeout=20)
    except Exception as error:
        failures.append(f"device-locale restore: {type(error).__name__}")
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
    # API 33+ moved the effective locale to LocaleManagerService: a bare
    # system_locales write persists but does not reconfigure apps (post-
    # v1.2.18 sweep: every vi-VN cell captured English UI while
    # persist.sys.locale stayed en-US). `cmd locale set-device-locale` is the
    # switch that actually reaches the app; the settings write stays so the
    # snapshot key keeps reporting what the sweep asked for.
    adb.command("shell", "settings", "put", "system", "system_locales", variant["locale"])
    adb.command("shell", "cmd", "locale", "set-device-locale", variant["locale"])


def preflight(base_url: str, apk: Path, adb: Adb, recorder: RecordingClient,
              *, require_shared_server: bool = True) -> None:
    if not apk.is_file():
        raise EnvironmentFailure("debug APK is missing")
    if require_shared_server:
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
                 dstack: bool = False,
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
        "seed_fixture": None,
    }
    context: dict[str, Any] = {"evidence_root": run_root, "client": recorder, "adb": adb,
                               "reset_app_data": reset_app_data, "helpers": helpers,
                               "frame_reader": None, "stomp_events": [],
                               "serial": serial, "base_url": base_url.rstrip("/")}
    if dstack:
        context["apply_server_env"] = _dstack_apply_server_env(context, api)
    before: dict[str, str] | None = None
    device = helpers[1].Device(serial)
    context["device"] = device
    try:
        try:
            # Rows that provision their own disposable backend (--dstack mail
            # rows selected alone) do not need the shared local server; the
            # device checks below still apply.
            shared_rows = [row.id for row in selected
                           if row.id not in _DSTACK_SELF_PROVISIONED_ROWS]
            preflight(base_url, apk, adb, recorder,
                      require_shared_server=not (dstack and not shared_rows))
            if shared_rows:
                # Local-mode seed preflight: the demo fixture is shared
                # mutable state that earlier journeys consume/rename/archive,
                # so verify and REST-re-seed it here (display names, the
                # An<->Binh direct, the 'NexaChat Team' forward target)
                # instead of letting a row env-fail mid-journey on drift.
                summary["seed_fixture"] = ensure_seed_fixture_state(api, recorder)
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
            keys = ("wifi_on", "mobile_data", "airplane_mode_on", "font_scale",
                    "system_locales", "device_locale", "navigation_mode")
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
    parser.add_argument("--dstack", action="store_true",
                        help="let mail/OTP rows provision a disposable backend via "
                             "tools/e2e/disposable_stack.py instead of staying blocked")
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
            dstack=args.dstack,
        )
        print(render_summary(summary))
        return 0 if summary["status"] == "PASS" else 1
    except HarnessError as error:
        parser.error(str(error))
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
