#!/usr/bin/env python3
"""Run the F4/F6 debug-APK patch window on emulator-5554 only.

The script starts `nexachat-audit` on port 5554 only when that exact AVD is not
already running. It installs the worktree debug APK without clearing app data,
drives notifications and offline sends with the existing adb UI helper, and
writes token-free screenshots, measurements, and filtered logcat evidence to
an external evidence directory. It stops after the first failed product
assertion, restores the device's network/font/locale settings, and stops only
an emulator process that it started itself.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[3]
E2E_DIR = ROOT / "tools" / "e2e"
DEVICE_DIR = E2E_DIR / "device"
CALLS_DIR = E2E_DIR / "calls"
sys.path.insert(0, str(E2E_DIR))
sys.path.insert(0, str(DEVICE_DIR))
sys.path.insert(0, str(CALLS_DIR))

from adb_ui import Device, Node  # noqa: E402
from call_e2e import StompConnection, history_item, signal  # noqa: E402
from e2e_common import Client, E2EFailure, PASSWORD, check, direct_conversation  # noqa: E402

SERIAL = "emulator-5554"
PACKAGE = "com.nexachat.debug"
MAIN_ACTIVITY_CLASS = "com.nexachat.MainActivity"
DEVICE_ACCOUNT = "+84900000001"  # An
CALLER_ACCOUNT = "+84900000002"  # Binh
DEVICE_API_BASE = "http://127.0.0.1:18093"
PERMISSIONS = (
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.RECORD_AUDIO",
)
TOKEN_RE = re.compile(r"(?i)(Bearer\s+)[A-Za-z0-9._~-]+")
JWT_RE = re.compile(r"(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,}")
JSON_TOKEN_RE = re.compile(r'(?i)("(?:accessToken|refreshToken|token)"\s*:\s*")[^"]+(")')


class ProductDefect(E2EFailure):
    """An observed mismatch in the F4/F6 product behavior under test."""


def run(command: list[str], *, check_result: bool = True, timeout: float = 60,
        text: bool = True) -> subprocess.CompletedProcess:
    completed = subprocess.run(
        command, capture_output=True, timeout=timeout, check=False, text=text,
        encoding="utf-8" if text else None, errors="replace" if text else None,
    )
    if check_result and completed.returncode != 0:
        raise RuntimeError(f"command failed with exit {completed.returncode}: {' '.join(command[:4])}")
    return completed


def adb(*args: str, timeout: float = 60, check_result: bool = True) -> str:
    result = run(["adb", "-s", SERIAL, *args], timeout=timeout, check_result=check_result)
    return result.stdout.strip()


def device_online() -> bool:
    listing = run(["adb", "devices"], check_result=True, timeout=15).stdout.splitlines()
    return any(line.startswith(SERIAL + "\tdevice") for line in listing)


def avd_name() -> str:
    result = adb("emu", "avd", "name", check_result=False, timeout=15)
    return next((line.strip() for line in result.splitlines()
                 if line.strip() and line.strip() != "OK"), "")


def start_owned_avd(avd: str, evidence_dir: Path) -> tuple[bool, subprocess.Popen | None]:
    if device_online():
        actual = avd_name()
        check(actual == avd, f"{SERIAL} is {actual or 'not a named AVD'}, expected {avd}")
        return False, None

    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or r"D:\Android\Sdk"
    emulator = Path(sdk) / "emulator" / "emulator.exe"
    check(emulator.is_file(), f"Android emulator executable is missing: {emulator}")
    avds = run([str(emulator), "-list-avds"], timeout=20).stdout.splitlines()
    check(avd in avds, f"AVD {avd} was not listed by the configured Android SDK")
    output = open(evidence_dir / "emulator-start.txt", "ab")
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    process = subprocess.Popen(
        [str(emulator), "-avd", avd, "-port", "5554", "-no-snapshot-save"],
        stdin=subprocess.DEVNULL,
        stdout=output,
        stderr=subprocess.STDOUT,
        creationflags=flags,
    )
    try:
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f"owned emulator exited with code {process.returncode}")
            if device_online():
                break
            time.sleep(2)
        check(device_online(), "emulator-5554 did not enter adb device state within 300 seconds")
        adb("wait-for-device", timeout=180)
        boot_deadline = time.monotonic() + 240
        while time.monotonic() < boot_deadline:
            if adb("shell", "getprop", "sys.boot_completed", timeout=15,
                   check_result=False) == "1":
                check(avd_name() == avd, f"started serial {SERIAL} is not AVD {avd}")
                return True, process
            time.sleep(2)
        raise RuntimeError("emulator-5554 did not finish booting within 240 seconds")
    except Exception:
        if device_online():
            adb("emu", "kill", timeout=20, check_result=False)
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.terminate()
        raise


def settings_snapshot() -> dict[str, str]:
    result: dict[str, str] = {}
    for key, namespace, name in (
        ("wifi_on", "global", "wifi_on"),
        ("mobile_data", "global", "mobile_data"),
        ("airplane_mode_on", "global", "airplane_mode_on"),
        ("font_scale", "system", "font_scale"),
        ("system_locales", "system", "system_locales"),
    ):
        result[key] = adb("shell", "settings", "get", namespace, name, timeout=15)
    return result


def set_service(name: str, enabled: bool) -> None:
    adb("shell", "svc", name, "enable" if enabled else "disable", timeout=20)


def prepare_network(before: dict[str, str]) -> None:
    if before.get("airplane_mode_on") == "1":
        adb("shell", "settings", "put", "global", "airplane_mode_on", "0")
        adb("shell", "am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE",
            "--ez", "state", "false", check_result=False)
    set_service("wifi", True)
    set_service("data", True)
    time.sleep(2)


def restore_device_settings(before: dict[str, str]) -> dict[str, str]:
    # Restore connectivity even when the run failed midway through the outage.
    desired_airplane = before.get("airplane_mode_on") == "1"
    if desired_airplane:
        adb("shell", "settings", "put", "global", "airplane_mode_on", "0")
        adb("shell", "am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE",
            "--ez", "state", "false", check_result=False)
        time.sleep(0.5)
    set_service("wifi", before.get("wifi_on") != "0")
    mobile = before.get("mobile_data")
    set_service("data", mobile != "0")
    if desired_airplane:
        adb("shell", "settings", "put", "global", "airplane_mode_on", "1")
        adb("shell", "am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE",
            "--ez", "state", "true", check_result=False)

    if before.get("font_scale") not in (None, "", "null"):
        current_font = adb("shell", "settings", "get", "system", "font_scale")
        if current_font != before["font_scale"]:
            adb("shell", "settings", "put", "system", "font_scale", before["font_scale"])
    if before.get("system_locales") not in (None, "", "null"):
        current_locale = adb("shell", "settings", "get", "system", "system_locales")
        if current_locale != before["system_locales"]:
            adb("shell", "cmd", "locale", "set", before["system_locales"], check_result=False)
    return settings_snapshot()


def parse_permission_state(package_dump: str, current_user: str, permission: str) -> str:
    """Parse one explicit runtime grant from this package's current-user dump."""
    check(current_user.isdigit(), "Android did not report a numeric current user")
    package_header = re.search(
        rf"(?m)^[ \t]*Package \[{re.escape(PACKAGE)}\](?:[ \t]*\([^()\r\n]*\))?:[ \t]*$",
        package_dump,
    )
    check(package_header is not None,
          f"dumpsys output did not contain the package record for {PACKAGE}")

    package_tail = package_dump[package_header.end():]
    package_end = re.search(r"(?m)^[ \t]*Queries:[ \t]*$", package_tail)
    package_block = package_tail[:package_end.start()] if package_end else package_tail

    user_header = re.search(
        rf"(?m)^[ \t]*User[ \t]+{re.escape(current_user)}[ \t]*:[^\r\n]*$",
        package_block,
    )
    check(user_header is not None,
          f"dumpsys output did not contain Android user {current_user} for {PACKAGE}")
    user_tail = package_block[user_header.end():]
    next_user = re.search(r"(?m)^[ \t]*User[ \t]+\d+[ \t]*:", user_tail)
    user_block = user_tail[:next_user.start()] if next_user else user_tail

    runtime_header = re.search(
        r"(?m)^(?P<indent>[ \t]*)runtime permissions:[ \t]*$", user_block
    )
    check(runtime_header is not None,
          f"dumpsys output did not contain runtime permissions for {PACKAGE} user {current_user}")
    runtime_tail = user_block[runtime_header.end():]
    indent = len(runtime_header.group("indent"))
    next_section = re.search(
        rf"(?m)^[ \t]{{0,{indent}}}[A-Za-z][A-Za-z0-9 _-]*:[ \t]*$",
        runtime_tail,
    )
    runtime_block = runtime_tail[:next_section.start()] if next_section else runtime_tail
    states = re.findall(
        rf"(?m)^[ \t]*{re.escape(permission)}:[ \t]*granted=(true|false)(?:[ \t]*,[^\r\n]*)?[ \t]*$",
        runtime_block,
    )
    check(len(states) == 1,
          f"dumpsys output did not report one explicit runtime grant state for {permission} "
          f"under Android user {current_user}; refusing to guess")
    return "granted" if states[0] == "true" else "denied"


def permission_state(permission: str) -> str:
    current_user = adb("shell", "am", "get-current-user", timeout=15)
    check(current_user.isdigit(), "Android did not report a numeric current user")
    package_dump = adb("shell", "dumpsys", "package", PACKAGE, timeout=30)
    return parse_permission_state(package_dump, current_user, permission)


def grant_test_permissions() -> dict[str, str]:
    original: dict[str, str] = {}
    for permission in PERMISSIONS:
        state = permission_state(permission)
        original[permission] = state
        if state != "granted":
            adb("shell", "pm", "grant", PACKAGE, permission, timeout=15)
    return original


def restore_permissions(original: dict[str, str]) -> None:
    for permission, state in original.items():
        current = permission_state(permission)
        if state == "granted" and current != "granted":
            adb("shell", "pm", "grant", PACKAGE, permission, check_result=False)
        elif state != "granted" and current == "granted":
            adb("shell", "pm", "revoke", PACKAGE, permission, check_result=False)


def ensure_apk_installed(apk: Path) -> None:
    check(apk.is_file(), f"worktree debug APK not found: {apk}")
    result = run(["adb", "-s", SERIAL, "install", "-r", str(apk)], timeout=180,
                 check_result=False)
    check(result.returncode == 0, f"debug APK install failed: {result.stderr.strip()[:240]}")


def ui_nodes(ui: Device, timeout: float = 60) -> list[Node]:
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            return ui.nodes()
        except Exception as error:
            last_error = error
            time.sleep(1)
    raise RuntimeError(f"could not read emulator-5554 UI hierarchy: {last_error}")


def text_matches(node: Node, candidates: tuple[str, ...]) -> bool:
    value = (node.text + " " + node.desc).casefold()
    return any(candidate.casefold() in value for candidate in candidates)


def named_conversation_row(nodes: list[Node], display_name: str) -> Node | None:
    expected = display_name.casefold()
    return next((node for node in nodes
                 if node.text.strip().casefold() == expected
                 or node.desc.strip().casefold() == expected), None)


def node_by_id(nodes: list[Node], suffix: str) -> Node | None:
    return next((node for node in nodes
                 if node.attrs.get("resource-id", "").endswith("/" + suffix)), None)


def ensure_device_account(ui: Device, evidence_dir: Path) -> None:
    adb("shell", "am", "start", "-n", f"{PACKAGE}/{MAIN_ACTIVITY_CLASS}", timeout=30)
    time.sleep(3)
    nodes = ui_nodes(ui)
    sign_in = next((node for node in nodes if text_matches(node, ("Sign in", "Đăng nhập"))), None)
    if sign_in is not None:
        phone = node_by_id(nodes, "phoneInput")
        password = node_by_id(nodes, "passwordInput")
        if phone is None or password is None:
            fields = [node for node in nodes if "EditText" in node.cls]
            check(len(fields) >= 2, "login screen did not expose phone/password inputs")
            phone, password = fields[0], fields[1]
        ui.tap_node(phone)
        ui.type_text(DEVICE_ACCOUNT)
        ui.tap_node(password)
        ui.type_text(PASSWORD)
        current = ui_nodes(ui)
        action = next((node for node in current
                       if text_matches(node, ("Sign in", "Đăng nhập"))), None)
        check(action is not None, "login action was not visible")
        ui.tap_node(action)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            nodes = ui_nodes(ui, timeout=10)
            if any(text_matches(node, ("Binh", "NexaChat Team", "Chats", "Tin nhắn"))
                   for node in nodes):
                ui.screenshot(str(evidence_dir / "f4-device-home.png"))
                return
            time.sleep(1)
        raise ProductDefect("An did not reach the inbox after local sign-in")

    # Preserve an existing session only when the visible profile identifies it as An.
    profile = next((node for node in nodes if text_matches(node, ("Profile", "Hồ sơ"))), None)
    if profile is not None:
        ui.tap_node(profile)
        time.sleep(2)
        nodes = ui_nodes(ui)
    check(any(node.text.strip() == "An" for node in nodes),
          "emulator already has a session, but the visible account could not be verified as An")
    ui.screenshot(str(evidence_dir / "f4-device-home.png"))


def wait_for_notification(ui: Device, timeout: float = 15) -> list[Node]:
    adb("shell", "cmd", "statusbar", "expand-notifications", check_result=False, timeout=15)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        nodes = ui_nodes(ui, timeout=8)
        if any(text_matches(node, ("Incoming voice call", "Cuộc gọi thoại đến")) for node in nodes):
            return nodes
        # A full-screen call activity may cover the shade; move Home, then expand again.
        ui.key("KEYCODE_HOME")
        adb("shell", "cmd", "statusbar", "expand-notifications", check_result=False, timeout=15)
        time.sleep(0.7)
    raise ProductDefect("incoming-call notification was not visible in the Android notification shade")


def tap_notification(ui: Device, action: str) -> None:
    # Killing the app can collapse the shade or change the visible node tree.
    # Reopen it after process death and resolve fresh coordinates for the tap.
    nodes = wait_for_notification(ui)
    if action == "open":
        candidates = ("Incoming voice call", "Cuộc gọi thoại đến")
    elif action == "accept":
        candidates = ("Accept", "Trả lời")
    else:
        candidates = ("Decline", "Từ chối")
    target = next((node for node in nodes if text_matches(node, candidates)), None)
    if target is None:
        # One more pull expands heads-up notifications into action rows.
        ui.swipe(500, 30, 500, 1300, 400)
        time.sleep(0.5)
        nodes = ui_nodes(ui)
        target = next((node for node in nodes if text_matches(node, candidates)), None)
    check(target is not None, f"notification {action} control was not exposed")
    ui.tap_node(target)


def resumed_call_activity() -> str:
    text = adb("shell", "dumpsys", "activity", "activities", timeout=20)
    for line in text.splitlines():
        if "mResumedActivity" in line or "topResumedActivity" in line:
            if "com.nexachat" in line:
                return line.strip()[:240]
    return ""


def wait_activity(timeout: float = 15) -> tuple[str, list[Node]]:
    deadline = time.monotonic() + timeout
    last = ""
    ui = Device(SERIAL)
    while time.monotonic() < deadline:
        last = resumed_call_activity()
        if "IncomingCallActivity" in last or "CallActivity" in last:
            return last, ui_nodes(ui, timeout=10)
        time.sleep(0.5)
    raise ProductDefect(f"call notification did not restore a call activity; resumed={last or 'other activity'}")


def login_local(client: Client, phone: str) -> dict[str, Any]:
    for attempt in range(5):
        response = client.post("/api/v1/auth/login", body={"phone": phone, "password": PASSWORD})
        if response.status == 429 and attempt < 4:
            details = (response.json() or {}).get("error", {}).get("details") or {}
            time.sleep(min(60, max(2, int(details.get("retryAfterSeconds", 10)) + 1)))
            continue
        check(response.status == 200, f"local actor login returned HTTP {response.status}")
        data = response.json() or {}
        check(bool(data.get("accessToken")), "local actor login returned no access token")
        return data
    raise E2EFailure("local actor login retry budget exhausted")


def invite_call(caller: StompConnection, conversation_id: str) -> str:
    call_id = str(uuid.uuid4())
    caller.send_call(conversation_id, signal("INVITE", call_id))
    return call_id


def finish_call(caller: StompConnection, conversation_id: str, call_id: str,
                reason: str, action: str, client: Client, caller_token: str) -> dict[str, Any]:
    caller.send_call(conversation_id, signal(action, call_id))
    event_type = {
        "CANCEL": "CALL_CANCELLED",
        "END": "CALL_ENDED",
        "REJECT": "CALL_REJECTED",
    }[action]
    event = caller.wait_event(event_type, call_id, timeout=15)
    check((event.get("payload") or {}).get("reason") == reason,
          f"call termination did not report {reason}")
    record = history_item(client, caller_token, conversation_id, call_id)
    check(record.get("endReason") == reason, f"call history did not persist {reason}")
    return {"event": event_type, "history_end_reason": record.get("endReason")}


def run_call_case(ui: Device, caller: StompConnection, conversation_id: str,
                  client: Client, caller_token: str, action: str,
                  call_id: str, evidence_dir: Path) -> dict[str, Any]:
    ui.key("KEYCODE_HOME")
    time.sleep(0.6)
    wait_for_notification(ui)
    ui.screenshot(str(evidence_dir / f"f4-{action}-notification.png"))
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(0.6)
    kill_output = adb("shell", "am", "kill", PACKAGE, timeout=20)
    time.sleep(1)
    pid_after_kill = adb("shell", "pidof", PACKAGE, check_result=False, timeout=10)
    check(not pid_after_kill, "am kill did not stop the NexaChat process")
    if action == "decline":
        tap_notification(ui, "decline")
        event = caller.wait_event("CALL_REJECTED", call_id, timeout=15)
        check((event.get("payload") or {}).get("reason") == "REJECTED",
              "Decline notification did not reject the incoming call")
        history = history_item(client, caller_token, conversation_id, call_id)
        check(history.get("endReason") == "REJECTED", "Declined call was not persisted")
        ui.screenshot(str(evidence_dir / "f4-decline-result.png"))
        return {
            "call_id": call_id,
            "process_kill_output": kill_output[:160],
            "pid_after_kill": pid_after_kill,
            "action": "notification decline",
            "server_event": "CALL_REJECTED",
            "history_end_reason": history.get("endReason"),
        }

    tap_notification(ui, "open" if action == "open" else "accept")
    activity, screen_nodes = wait_activity()
    ui.screenshot(str(evidence_dir / f"f4-{action}-screen.png"))
    if action == "open":
        has_call_controls = (
            any(text_matches(node, ("Accept", "Trả lời")) for node in screen_nodes)
            and any(text_matches(node, ("Decline", "Từ chối")) for node in screen_nodes)
        )
        check(has_call_controls, "Open did not restore the incoming call screen controls")
        outcome = finish_call(caller, conversation_id, call_id, "CANCELLED", "CANCEL",
                              client, caller_token)
    else:
        event = caller.wait_event("CALL_ACCEPTED", call_id, timeout=15)
        check(event.get("eventType") == "CALL_ACCEPTED", "Accept did not reach the caller")
        # A server acceptance is the signaling assertion; end cleanly before F6.
        outcome = finish_call(caller, conversation_id, call_id, "COMPLETED", "END",
                              client, caller_token)
    return {
        "call_id": call_id,
        "process_kill_output": kill_output[:160],
        "pid_after_kill": pid_after_kill,
        "resumed_activity": activity,
        "action": "notification open" if action == "open" else "notification accept",
        **outcome,
    }


def query_room_messages(contents: list[str]) -> list[dict[str, str]] | None:
    values = ",".join("'" + item.replace("'", "''") + "'" for item in contents)
    sql = (
        "SELECT content || '|' || state || '|' || clientMessageId FROM outbox "
        f"WHERE content IN ({values}) ORDER BY content;"
    )
    result = run([
        "adb", "-s", SERIAL, "shell", "run-as", PACKAGE,
        "sqlite3", "databases/nexachat.db", sql,
    ], check_result=False, timeout=25)
    if result.returncode != 0:
        return None
    rows = []
    for line in result.stdout.splitlines():
        parts = line.strip().split("|", 2)
        if len(parts) == 3:
            rows.append({"content": parts[0], "outbox_state": parts[1], "client_message_id": parts[2]})
    return rows


def wait_server_rows(db_container: str, db_name: str, contents: list[str], timeout: float = 40
                     ) -> list[dict[str, Any]]:
    values = ",".join("'" + item.replace("'", "''") + "'" for item in contents)
    sql = (
        "SELECT content, client_message_id::text, count(*)::text FROM messages "
        f"WHERE content IN ({values}) GROUP BY content, client_message_id ORDER BY content;"
    )
    deadline = time.monotonic() + timeout
    last_rows: list[dict[str, Any]] = []
    while time.monotonic() < deadline:
        result = run([
            "docker", "exec", "-i", db_container, "psql", "-U", "nexachat", "-d", db_name,
            "-tA", "-F", "|", "-v", "ON_ERROR_STOP=1", "-c", sql,
        ], check_result=True, timeout=20)
        last_rows = []
        for line in result.stdout.splitlines():
            fields = line.strip().split("|")
            if len(fields) == 3:
                last_rows.append({"content": fields[0], "client_message_id": fields[1],
                                  "count": int(fields[2])})
        if len({row["content"] for row in last_rows}) == len(contents):
            return last_rows
        time.sleep(1)
    return last_rows


def find_chat_composer(nodes: list[Node]) -> Node:
    node = node_by_id(nodes, "messageInput")
    if node is None:
        node = next((item for item in nodes if "EditText" in item.cls), None)
    check(node is not None, "chat composer was not visible")
    return node


def queue_offline_text(ui: Device, content: str) -> None:
    nodes = ui_nodes(ui)
    composer = find_chat_composer(nodes)
    ui.tap_node(composer)
    ui.type_text(content)
    nodes = ui_nodes(ui)
    send = node_by_id(nodes, "sendButton")
    if send is None:
        send = next((node for node in nodes if text_matches(node, ("Send", "Gửi"))), None)
    check(send is not None, "chat send control was not visible after typing")
    ui.tap_node(send)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        nodes = ui_nodes(ui, timeout=8)
        if any(content in node.text for node in nodes):
            return
        time.sleep(0.5)
    raise ProductDefect("offline text was not rendered from the local conversation cache")


def cut_network() -> None:
    set_service("wifi", False)
    set_service("data", False)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        wifi = adb("shell", "settings", "get", "global", "wifi_on", timeout=10)
        data = adb("shell", "settings", "get", "global", "mobile_data", timeout=10)
        if wifi == "0" and data == "0":
            return
        time.sleep(0.5)
    wifi = adb("shell", "settings", "get", "global", "wifi_on", timeout=10)
    data = adb("shell", "settings", "get", "global", "mobile_data", timeout=10)
    check(wifi == "0" and data in ("0", "null"),
          "svc wifi/data disable did not put the emulator into the requested offline state")


def restore_test_network(before: dict[str, str]) -> None:
    # A network cut must end with usable connectivity even if it was initially off.
    if before.get("airplane_mode_on") == "1":
        adb("shell", "settings", "put", "global", "airplane_mode_on", "0")
        adb("shell", "am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE",
            "--ez", "state", "false", check_result=False)
    set_service("wifi", True)
    set_service("data", True)
    time.sleep(2)


def find_text_node(ui: Device, content: str, timeout: float = 15) -> Node:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        nodes = ui_nodes(ui, timeout=8)
        match = next((node for node in nodes if content in node.text), None)
        if match is not None:
            return match
        time.sleep(0.5)
    raise ProductDefect(f"message text {content!r} was not visible after reopening the conversation")


def filtered_logcat(baseline: set[str]) -> tuple[list[str], bool]:
    result = run(["adb", "-s", SERIAL, "logcat", "-d", "-v", "brief"],
                 check_result=False, timeout=40)
    new_lines = [line for line in result.stdout.splitlines() if line not in baseline]
    selected: list[str] = []
    keep_trace = 0
    for line in new_lines:
        relevant = (
            "FATAL EXCEPTION" in line or "ANR in" in line
            or ("com.nexachat" in line and any(tag in line for tag in
                ("Call", "Realtime", "Stomp", "Token", "AndroidRuntime")))
        )
        if relevant:
            selected.append(line)
            keep_trace = 40 if ("FATAL EXCEPTION" in line or "ANR in" in line) else 0
        elif keep_trace > 0:
            selected.append(line)
            keep_trace -= 1
    redacted = []
    for line in selected:
        line = TOKEN_RE.sub(r"\1[REDACTED]", line)
        line = JWT_RE.sub("[REDACTED_JWT]", line)
        line = JSON_TOKEN_RE.sub(r"\1[REDACTED]\2", line)
        redacted.append(line)
    defect = any("FATAL EXCEPTION" in line or "ANR in" in line for line in new_lines)
    return redacted, defect


def outside_repository(path: Path) -> bool:
    try:
        path.resolve().relative_to(ROOT.resolve())
        return False
    except ValueError:
        return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=DEVICE_API_BASE)
    parser.add_argument("--serial", default=SERIAL, choices=(SERIAL,))
    parser.add_argument("--avd", default="nexachat-audit")
    parser.add_argument("--apk", default=str(ROOT / "android" / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"))
    parser.add_argument("--db-container", default="nexachat_l1-db-1")
    parser.add_argument("--db-name", default="nexachat_l1")
    parser.add_argument("--evidence-dir", required=True)
    args = parser.parse_args()

    evidence_dir = Path(args.evidence_dir).expanduser().resolve()
    check(outside_repository(evidence_dir), "evidence-dir must stay outside the repository")
    evidence_dir.mkdir(parents=True, exist_ok=True)
    run_id = uuid.uuid4().hex[:10]
    report_path = evidence_dir / f"patch-window-{run_id}.json"
    report: dict[str, Any] = {
        "run_id": run_id,
        "started_at_utc": datetime.now(timezone.utc).isoformat(),
        "serial": SERIAL,
        "avd": args.avd,
        "apk": str(Path(args.apk).resolve()),
        "base_url": args.base_url,
        "screenshots": [],
        "calls": [],
        "f6": {},
    }
    started_emulator = False
    emulator_process: subprocess.Popen | None = None
    before_settings: dict[str, str] = {}
    after_settings: dict[str, str] = {}
    original_permissions: dict[str, str] = {}
    caller: StompConnection | None = None
    active_call: tuple[str, str] | None = None
    client: Client | None = None
    caller_token: str | None = None
    baseline_logs: set[str] = set()
    status = "FAIL"
    failure: str | None = None
    product_defect = False
    try:
        started_emulator, emulator_process = start_owned_avd(args.avd, evidence_dir)
        report["emulator_started_by_script"] = started_emulator
        logcat_start = run(["adb", "-s", SERIAL, "logcat", "-d", "-v", "brief"],
                           check_result=False, timeout=40)
        baseline_logs = set(logcat_start.stdout.splitlines())
        before_settings = settings_snapshot()
        report["settings_before"] = before_settings
        prepare_network(before_settings)
        existing = adb("shell", "pm", "path", PACKAGE, check_result=False)
        report["package_preinstalled"] = existing.startswith("package:")
        ensure_apk_installed(Path(args.apk).resolve())
        original_permissions = grant_test_permissions()
        report["permissions_before_test"] = original_permissions
        ui = Device(SERIAL)  # Never inherit adb_ui.py's default 5558 serial.
        ensure_device_account(ui, evidence_dir)
        report["screenshots"].append("f4-device-home.png")

        client = Client(args.base_url, timeout=30)
        health = client.get("/actuator/health/liveness")
        check(health.status == 200, f"local app liveness returned HTTP {health.status}")
        actor = login_local(client, CALLER_ACCOUNT)
        caller_token = actor["accessToken"]
        device_session = login_local(client, DEVICE_ACCOUNT)
        direct = direct_conversation(client, device_session["accessToken"], CALLER_ACCOUNT)[1]
        conversation_id = str(direct["id"])
        ws_url = args.base_url.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
        caller = StompConnection(ws_url, caller_token, "l1-device-caller")

        # F4: verify process-death notification Open, Accept and Decline in order.
        for action in ("open", "accept", "decline"):
            try:
                call_id = invite_call(caller, conversation_id)
                active_call = (conversation_id, call_id)
                call_result = run_call_case(
                    ui, caller, conversation_id, client, caller_token, action, call_id,
                    evidence_dir,
                )
                report["calls"].append(call_result)
                report["screenshots"].extend([
                    f"f4-{action}-notification.png",
                    f"f4-{action}-screen.png" if action != "decline" else "f4-decline-result.png",
                ])
                active_call = None
            except Exception:
                # The F4 failure is the stop boundary; cancel the server-side ringing call.
                if active_call is not None:
                    try:
                        caller.send_call(active_call[0], signal("CANCEL", active_call[1]))
                        caller.wait_event("CALL_CANCELLED", active_call[1], timeout=5)
                    except Exception:
                        pass
                raise

        # F6: ensure a background STOMP delivery, an offline sync gap, and two real offline outbox sends.
        device.key("KEYCODE_HOME")
        adb("shell", "am", "start", "-n", f"{PACKAGE}/{MAIN_ACTIVITY_CLASS}", timeout=30)
        time.sleep(1.5)
        inbox_nodes = ui_nodes(ui)
        conversation_row = named_conversation_row(inbox_nodes, "Binh")
        check(conversation_row is not None, "seed An↔Binh conversation was not visible in the inbox")
        ui.screenshot(str(evidence_dir / "f6-background-inbox.png"))
        report["screenshots"].append("f6-background-inbox.png")
        ui.key("KEYCODE_HOME")
        time.sleep(0.7)

        stamp = uuid.uuid4().hex[:8]
        inbound_background = f"l1bg{stamp}in"
        inbound_offline = f"l1off{stamp}in"
        queued_one = f"l1off{stamp}out1"
        queued_two = f"l1off{stamp}out2"
        inbound1, _ = send_message_api(client, caller_token, conversation_id, inbound_background)
        report["f6"]["background_inbound_client_message_id"] = inbound1
        time.sleep(1.5)

        cut_network()
        report["f6"]["network_cut"] = True
        report["f6"]["network_state_during_cut"] = {
            "wifi_on": adb("shell", "settings", "get", "global", "wifi_on"),
            "mobile_data": adb("shell", "settings", "get", "global", "mobile_data"),
        }
        inbound2, _ = send_message_api(client, caller_token, conversation_id, inbound_offline)
        report["f6"]["offline_inbound_client_message_id"] = inbound2
        adb("shell", "am", "start", "-n", f"{PACKAGE}/{MAIN_ACTIVITY_CLASS}", timeout=30)
        time.sleep(1.5)
        inbox_nodes = ui_nodes(ui)
        conversation_row = named_conversation_row(inbox_nodes, "Binh")
        check(conversation_row is not None, "Binh conversation was not available while the device was offline")
        ui.tap_node(conversation_row)
        time.sleep(1.2)
        queue_offline_text(ui, queued_one)
        queue_offline_text(ui, queued_two)
        offline_room = query_room_messages([queued_one, queued_two])
        offline_room_client_ids: set[str] | None = None
        if offline_room is not None:
            check(len(offline_room) == 2, "offline texts were not both present in Room before reconnect")
            check(all(row["outbox_state"] in ("PENDING", "SENDING") for row in offline_room),
                  "offline outbox rows were not pending before reconnect")
            offline_room_client_ids = {row["client_message_id"] for row in offline_room}
            check(len(offline_room_client_ids) == 2 and all(offline_room_client_ids),
                  "offline Room outbox did not retain two distinct clientMessageId values")
            report["f6"]["offline_room_rows"] = offline_room
            report["f6"]["offline_room_client_message_ids"] = sorted(offline_room_client_ids)
        else:
            check(any(queued_one in node.text for node in ui_nodes(ui))
                  and any(queued_two in node.text for node in ui_nodes(ui)),
                  "offline texts were not visible in the Room-backed conversation")
            report["f6"]["offline_room_rows"] = "sqlite3 unavailable; both optimistic Room messages were visible in UI"
        ui.screenshot(str(evidence_dir / "f6-two-texts-queued-offline.png"))
        report["screenshots"].append("f6-two-texts-queued-offline.png")

        restore_test_network(before_settings)
        report["f6"]["network_restored"] = True
        sent_rows = wait_server_rows(args.db_container, args.db_name,
                                     [inbound_background, inbound_offline, queued_one, queued_two])
        report["f6"]["server_rows"] = sent_rows
        by_content: dict[str, list[dict[str, Any]]] = {}
        for row in sent_rows:
            by_content.setdefault(row["content"], []).append(row)
        check(set(by_content) == {inbound_background, inbound_offline, queued_one, queued_two},
              "server did not persist all four background/offline messages")
        check(all(len(rows) == 1 and rows[0]["count"] == 1
                  and bool(rows[0]["client_message_id"])
                  for rows in by_content.values()),
              "one or more F6 clientMessageId rows were duplicated")
        check(len({rows[0]["client_message_id"] for rows in by_content.values()}) == 4,
              "F6 messages did not persist four distinct clientMessageId values")
        if offline_room_client_ids is not None:
            server_outbox_ids = {by_content[content][0]["client_message_id"]
                                 for content in (queued_one, queued_two)}
            check(server_outbox_ids == offline_room_client_ids,
                  "server exact-once rows did not match Room outbox clientMessageId values")
        ui.key("KEYCODE_BACK")
        time.sleep(1)
        # Reopen the same conversation to ensure Room is the rendered source after reconnect.
        nodes = ui_nodes(ui)
        if not any(inbound_offline in node.text for node in nodes):
            row = named_conversation_row(nodes, "Binh")
            if row is not None:
                ui.tap_node(row)
        for content in (inbound_background, inbound_offline, queued_one, queued_two):
            find_text_node(ui, content, timeout=20)
        ui.screenshot(str(evidence_dir / "f6-room-after-reopen.png"))
        report["screenshots"].append("f6-room-after-reopen.png")
        report["f6"]["exact_once_by_client_message_id"] = True
        report["f6"]["visible_after_reopen"] = True
        status = "PASS"
    except Exception as error:
        failure = f"{type(error).__name__}: {error}"
        product_defect = isinstance(error, ProductDefect)
        try:
            report["failure"] = failure
            report["failure_class"] = "product_defect" if product_defect else "harness_or_environment"
        except Exception:
            pass
    finally:
        if caller is not None:
            try:
                caller.close()
            except Exception:
                pass
        if before_settings:
            if device_online():
                try:
                    after_settings = restore_device_settings(before_settings)
                    report["settings_after_restore"] = after_settings
                    report["device_settings_restored"] = all(
                        after_settings.get(key) == before_settings.get(key)
                        for key in ("wifi_on", "mobile_data", "airplane_mode_on", "font_scale", "system_locales")
                    )
                    if not report["device_settings_restored"]:
                        status = "FAIL"
                        failure = failure or "device network, font, or locale settings were not restored"
                except Exception as restore_error:
                    report["settings_restore_error"] = f"{type(restore_error).__name__}: {restore_error}"
                    report["device_settings_restored"] = False
                    status = "FAIL"
                    failure = failure or "device settings could not be restored"
            else:
                report["device_settings_restored"] = False
                status = "FAIL"
                failure = failure or "emulator was unavailable before device settings could be restored"
        if original_permissions:
            if device_online():
                try:
                    restore_permissions(original_permissions)
                    permission_after = {permission: permission_state(permission)
                                        for permission in original_permissions}
                    report["permissions_after_restore"] = permission_after
                    report["permissions_restored"] = permission_after == original_permissions
                    if not report["permissions_restored"]:
                        status = "FAIL"
                        failure = failure or "Android permissions were not restored to their initial state"
                except Exception as restore_error:
                    report["permissions_restore_error"] = f"{type(restore_error).__name__}: {restore_error}"
                    report["permissions_restored"] = False
                    status = "FAIL"
                    failure = failure or "Android permissions could not be restored"
            else:
                report["permissions_restored"] = False
                status = "FAIL"
                failure = failure or "emulator was unavailable before Android permissions could be restored"
        if baseline_logs:
            if device_online():
                try:
                    lines, has_crash = filtered_logcat(baseline_logs)
                    log_path = evidence_dir / "patch-window-logcat-excerpt.txt"
                    log_path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
                    report["logcat_excerpt"] = log_path.name
                    report["fatal_or_anr"] = has_crash
                    if has_crash:
                        status = "FAIL"
                        report["failure_class"] = "product_defect"
                        product_defect = True
                        failure = failure or "FATAL EXCEPTION or ANR in NexaChat process"
                except Exception as log_error:
                    report["logcat_capture_error"] = f"{type(log_error).__name__}: {log_error}"
                    status = "FAIL"
                    failure = failure or "logcat evidence could not be captured"
            else:
                report["logcat_capture_error"] = "emulator was unavailable before logcat capture"
                status = "FAIL"
                failure = failure or "logcat evidence could not be captured"
        if started_emulator:
            try:
                if device_online():
                    adb("emu", "kill", timeout=20)
                if emulator_process is not None:
                    try:
                        emulator_process.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        emulator_process.terminate()
                        emulator_process.wait(timeout=20)
                        report["owned_emulator_process_terminated"] = True
                report["owned_emulator_stopped"] = True
            except Exception as stop_error:
                report["owned_emulator_stop_error"] = f"{type(stop_error).__name__}: {stop_error}"
                status = "FAIL"
                failure = failure or "the emulator started by this script could not be stopped"
                report["owned_emulator_stopped"] = False
        report["status"] = status
        report["failure"] = failure
        report["product_defect"] = product_defect
        report["completed_at_utc"] = datetime.now(timezone.utc).isoformat()
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    print(f"status={status}")
    print(f"product_defect={product_defect}")
    print(f"evidence={report_path}")
    if failure:
        print(f"failure={failure}")
    return 0 if status == "PASS" and report.get("device_settings_restored", True) else 1


def send_message_api(client: Client, token: str, conversation_id: str,
                     content: str) -> tuple[str, dict[str, Any]]:
    client_message_id = str(uuid.uuid4())
    response = client.post(
        f"/api/v1/conversations/{conversation_id}/messages", token=token,
        body={
            "clientMessageId": client_message_id,
            "type": "TEXT",
            "content": content,
            "attachmentIds": [],
        },
    )
    check(response.status in (200, 201), f"local actor message returned HTTP {response.status}")
    row = response.json() or {}
    check(row.get("clientMessageId") == client_message_id,
          "local actor message changed clientMessageId")
    return client_message_id, row


if __name__ == "__main__":
    sys.exit(main())
