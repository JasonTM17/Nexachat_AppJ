#!/usr/bin/env python3
"""Check that release mapping preserves names required by Android reflection."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
APP_SOURCE = ROOT / "android" / "app" / "src" / "main" / "java"
COMPILED = (
    ROOT
    / "android"
    / "app"
    / "build"
    / "intermediates"
    / "javac"
    / "release"
    / "compileReleaseJavaWithJavac"
    / "classes"
)
MAPPING = ROOT / "android" / "app" / "build" / "outputs" / "mapping" / "release" / "mapping.txt"
USAGE = ROOT / "android" / "app" / "build" / "outputs" / "mapping" / "release" / "usage.txt"
RULES = ROOT / "android" / "app" / "proguard-rules.pro"
MERGED_MANIFEST_CANDIDATES = (
    ROOT
    / "android"
    / "app"
    / "build"
    / "intermediates"
    / "merged_manifests"
    / "release"
    / "processReleaseManifest"
    / "AndroidManifest.xml",
    ROOT
    / "android"
    / "app"
    / "build"
    / "intermediates"
    / "merged_manifest"
    / "release"
    / "processReleaseMainManifest"
    / "AndroidManifest.xml",
)
RELEASE_BUILD_CONFIG = (
    ROOT
    / "android"
    / "app"
    / "build"
    / "generated"
    / "source"
    / "buildConfig"
    / "release"
    / "com"
    / "nexachat"
    / "BuildConfig.java"
)
RELEASE_APK_DIR = ROOT / "android" / "app" / "build" / "outputs" / "apk" / "release"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
GOOGLE_ID_TOKEN_TYPE = (
    b"com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL"
)
COMPONENT_TAGS = {"application", "activity", "activity-alias", "service", "receiver", "provider"}
WIRE_MODELS = (
    "com.nexachat.feature.archive.ArchivePage",
    "com.nexachat.feature.archive.ArchivePage$Item",
    "com.nexachat.feature.archive.ArchivePage$Attachment",
    "com.nexachat.feature.archive.ArchivePage$Link",
    "com.nexachat.feature.invite.InviteLinkRequest",
    "com.nexachat.feature.invite.InviteLinkResponse",
    "com.nexachat.feature.invite.InviteApprovalRequest",
    "com.nexachat.feature.invite.InvitePreviewResponse",
    "com.nexachat.feature.invite.JoinResponse",
    "com.nexachat.feature.invite.JoinRequestResponse",
    "com.nexachat.feature.invite.JoinRequestResponse$UserSummary",
    "com.nexachat.feature.label.LabelDto",
    "com.nexachat.feature.label.LabelWriteRequest",
    "com.nexachat.feature.poll.PollDto",
    "com.nexachat.feature.poll.PollOptionDto",
    "com.nexachat.feature.poll.PollPageDto",
    "com.nexachat.feature.poll.PollChangesDto",
    "com.nexachat.feature.poll.PollChangesDto$ChangeDto",
    "com.nexachat.feature.poll.CreatePollRequest",
    "com.nexachat.feature.poll.VoteRequest",
    "com.nexachat.feature.reminder.ReminderDto",
    "com.nexachat.feature.reminder.ReminderRequest",
)
# Firebase RTDB ephemeral state (presence v2, typing) and the optional call
# status adapter exchange these Moshi beans with the REST/STOMP bridge. Moshi
# binds fields by their unannotated Java names, so R8 must keep every field and
# the no-arg constructor; the package keep rule does that today and these
# entries make a future narrowing of that rule fail loudly.
FIREBASE_WIRE_MODELS = (
    "com.nexachat.core.network.dto.FirebaseBridgeResponse",
    "com.nexachat.core.network.dto.FirebaseLeaseRequest",
    "com.nexachat.core.network.dto.FirebaseLeaseResponse",
    "com.nexachat.core.network.dto.PeerPresenceDto",
    "com.nexachat.core.network.stomp.dto.PresenceEventDto",
    "com.nexachat.core.network.stomp.dto.TypingSignalDto",
)
CALL_STATUS_ADAPTER = "com.nexachat.call.ui.FirebaseCallStatusDisplayAdapter"


def fail(message: str) -> int:
    print(f"FAIL: {message}", file=sys.stderr)
    return 1


def compiled_names(package: str) -> set[str]:
    folder = COMPILED.joinpath(*package.split("."))
    return {
        ".".join(path.relative_to(COMPILED).with_suffix("").parts)
        for path in folder.rglob("*.class")
    }


def source_package(java_file: Path) -> str | None:
    match = re.search(r"(?m)^\s*package\s+([\w.]+)\s*;", java_file.read_text(encoding="utf-8"))
    return match.group(1) if match else None


def hilt_declared_types(package: str, stem: str, source: str) -> set[str]:
    """Return the fully qualified names of Hilt-annotated declarations."""
    declared_types = re.findall(
        r"@(?:InstallIn|EntryPoint|HiltAndroidApp)\b[^{;]*?\b(?:class|interface)\s+([A-Za-z_$][\w$]*)",
        source,
    )
    return {
        f"{package}.{declared}" if declared == stem else f"{package}.{stem}${declared}"
        for declared in declared_types
    }


def count_room_anonymous_classes(source: str) -> tuple[int, int]:
    """Return (migration, other anonymous body) counts for AppDatabase."""
    migration_count = len(re.findall(r"\bnew\s+Migration\s*\(", source))
    other_anonymous = len(
        re.findall(r"\bnew\s+(?!Migration\b)[A-Za-z_$][\w$.]*\s*\([^)]*\)\s*\{", source)
    )
    return migration_count, other_anonymous


def mapping_members(text: str) -> tuple[dict[str, str], dict[str, list[tuple[str, str]]]]:
    mapped: dict[str, str] = {}
    field_renames: dict[str, list[tuple[str, str]]] = {}
    current: str | None = None
    for line in text.splitlines():
        match = re.match(r"^(\S+) -> (\S+):$", line)
        if match:
            current = match.group(1)
            mapped[current] = match.group(2)
            field_renames[current] = []
            continue
        if current is None or not line.startswith("    ") or line.startswith("      "):
            continue
        member = line.strip()
        if "->" not in member:
            continue
        left, right = member.split("->", 1)
        if "(" in left:
            continue
        source = left.strip().split()[-1]
        target = right.strip()
        if source.startswith("$") or source.startswith("<"):
            continue
        if source != target:
            field_renames[current].append((source, target))
    return mapped, field_renames


def removed_wire_members(usage_text: str) -> list[str]:
    removed: list[str] = []
    current: str | None = None
    watched = set(WIRE_MODELS) | set(FIREBASE_WIRE_MODELS)
    for line in usage_text.splitlines():
        if line and not line.startswith(" "):
            current = line.rstrip(":")
            continue
        if current not in watched:
            continue
        member = line.strip()
        if not member or member.endswith(")"):
            if "<init>" in member:
                removed.append(f"{current} {member}")
            continue
        removed.append(f"{current} {member}")
    return removed


def manifest_class_lookups() -> dict[str, str]:
    """Class names Android or a library resolves by string, not by reference."""
    manifest = next((path for path in MERGED_MANIFEST_CANDIDATES if path.is_file()), None)
    if manifest is None:
        return {}
    root = ET.parse(manifest).getroot()
    lookups: dict[str, str] = {}
    for element in root.iter():
        tag = element.tag.rsplit("}", 1)[-1]
        if tag in COMPONENT_TAGS:
            name = element.get(ANDROID_NS + "name")
            if name and "." in name and not name.startswith(("android.", "java.")):
                lookups.setdefault(name, f"manifest <{tag}>")
        elif tag == "meta-data":
            value = (element.get(ANDROID_NS + "value") or "").strip()
            if (
                value.startswith(("androidx.", "com.google.", "com.nexachat."))
                and "." in value
                and re.fullmatch(r"[\w.$]+", value)
            ):
                lookups.setdefault(value, "manifest meta-data class name")
    # androidx.startup is a provider-authority suffix, not a class.
    lookups.pop("androidx.startup", None)
    return lookups


def google_web_client_id() -> str | None:
    if not RELEASE_BUILD_CONFIG.is_file():
        return None
    text = RELEASE_BUILD_CONFIG.read_text(encoding="utf-8")
    match = re.search(r'GOOGLE_WEB_CLIENT_ID\s*=\s*"([^"]*)"', text)
    return match.group(1) if match else None


def release_flag(name: str) -> bool | None:
    if not RELEASE_BUILD_CONFIG.is_file():
        return None
    text = RELEASE_BUILD_CONFIG.read_text(encoding="utf-8")
    match = re.search(rf"\b{name}\s*=\s*(true|false)\s*;", text)
    return None if match is None else match.group(1) == "true"


def release_apk_dex_has_google_id_type() -> bool | None:
    apks = sorted(RELEASE_APK_DIR.glob("*.apk")) if RELEASE_APK_DIR.is_dir() else []
    if not apks:
        return None
    with zipfile.ZipFile(apks[0]) as archive:
        dex_names = [
            name for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name)
        ]
        return any(GOOGLE_ID_TOKEN_TYPE in archive.read(name) for name in dex_names)


def main() -> int:
    if not COMPILED.is_dir() or not MAPPING.is_file():
        return fail("build the release variant first; compiled classes or mapping.txt are missing")

    mapped, field_renames = mapping_members(MAPPING.read_text(encoding="utf-8"))

    expected: set[str] = set()
    package_groups = {
        "Moshi DTOs": [
            "com.nexachat.core.network.dto",
            "com.nexachat.core.network.stomp.dto",
        ],
        "Room": ["com.nexachat.core.database"],
        "summary models": ["com.nexachat.summary"],
    }
    group_counts: dict[str, int] = {}
    for label, packages in package_groups.items():
        before = len(expected)
        for package in packages:
            expected.update(compiled_names(package))
        group_counts[label] = len(expected) - before

    retrofit_interfaces: set[str] = set()
    hilt_source_types: set[str] = set()
    for java_file in APP_SOURCE.rglob("*.java"):
        source = java_file.read_text(encoding="utf-8")
        package = source_package(java_file)
        if package is None:
            continue
        if "retrofit2.http." in source:
            interfaces = re.findall(r"\binterface\s+([A-Za-z_$][\w$]*)", source)
            if java_file.stem == "CallRepository":
                retrofit_interfaces.update(
                    f"{package}.CallRepository${name}" for name in interfaces
                )
            else:
                retrofit_interfaces.update(f"{package}.{name}" for name in interfaces)
        hilt_source_types.update(hilt_declared_types(package, java_file.stem, source))

    group_counts["Retrofit interfaces"] = len(retrofit_interfaces)
    group_counts["Hilt source types"] = len(hilt_source_types)
    expected.update(retrofit_interfaces)
    expected.update(hilt_source_types)

    runtime_bridges = {
        "com.nexachat.NexaChatApp",
        "com.nexachat.data.OutboxSyncWorker",
        "com.nexachat.notification.DeviceTokenSyncWorker",
        "com.nexachat.notification.NexaFirebaseMessagingService",
        "com.nexachat.core.media.MediaGlideModule",
        "androidx.media3.exoplayer.ExoPlayer",
        "com.nexachat.feature.ask.AskRequest",
        "com.nexachat.feature.ask.AskResponse",
        "com.nexachat.feature.ask.AskCitation",
        "com.nexachat.feature.ask.AskUsage",
    }
    for prefix in (
        "com.nexachat.core.network.DeviceTokenApi$",
        "com.nexachat.call.core.CallFrameCodec$",
    ):
        expected.update(name for name in mapped if name.startswith(prefix))
    group_counts["runtime bridge types"] = len(runtime_bridges)
    expected.update(runtime_bridges)
    expected.update(WIRE_MODELS)
    group_counts["feature wire models"] = len(WIRE_MODELS)
    expected.update(FIREBASE_WIRE_MODELS)
    group_counts["Firebase/presence/typing wire models"] = len(FIREBASE_WIRE_MODELS)

    missing = sorted(name for name in expected if name not in mapped)
    renamed = sorted((name, mapped[name]) for name in expected if name in mapped and mapped[name] != name)
    renamed_fields = [
        (name, source, target)
        for name in sorted(expected)
        if name in mapped and mapped[name] == name
        for source, target in field_renames.get(name, [])
    ]
    if missing or renamed or renamed_fields:
        for name in missing[:20]:
            print(f"MISSING {name}", file=sys.stderr)
        for original, target in renamed[:20]:
            print(f"RENAMED {original} -> {target}", file=sys.stderr)
        for name, source, target in renamed_fields[:20]:
            print(f"FIELD {name}.{source} -> {target}", file=sys.stderr)
        return fail(
            "release mapping missing "
            f"{len(missing)} classes, renamed {len(renamed)} required classes, "
            f"and renamed {len(renamed_fields)} serialized fields"
        )

    if USAGE.is_file():
        removed_members = removed_wire_members(USAGE.read_text(encoding="utf-8"))
        if removed_members:
            for member in removed_members[:20]:
                print(f"REMOVED {member}", file=sys.stderr)
            return fail(
                f"usage.txt removed {len(removed_members)} reflective wire members"
            )
    else:
        return fail("usage.txt is missing; cannot prove reflective fields were retained")

    rules = RULES.read_text(encoding="utf-8")
    required_rules = (
        "-keep class * extends androidx.room.migration.Migration { *; }",
        "-keep class * implements android.os.Parcelable {",
    )
    if any(rule not in rules for rule in required_rules):
        return fail("Room migration or Parcelable class-identity keep rule is missing")

    lookups = manifest_class_lookups()
    if not lookups:
        return fail("merged release manifest is missing; cannot verify manifest class names")
    broken = sorted(
        (name, mapped.get(name)) for name in lookups if mapped.get(name) != name
    )
    if broken:
        for name, target in broken[:20]:
            print(
                f"MANIFEST {lookups[name]}: {name} -> {target or 'ABSENT'}",
                file=sys.stderr,
            )
        return fail(f"{len(broken)} manifest or meta-data class names are not preserved by R8")
    print(f"  manifest/metadata class lookups: {len(lookups)} verified in mapping.txt")

    client_id = google_web_client_id()
    if client_id is None:
        print("  Google ID token path: NOT_CHECKED (release BuildConfig is missing)")
    elif not client_id:
        print("  Google ID token path: not configured; R8 removes it by design")
    else:
        has_type = release_apk_dex_has_google_id_type()
        if has_type is None:
            return fail(
                "GOOGLE_WEB_CLIENT_ID is configured but no release APK exists; "
                "run :app:assembleRelease first"
            )
        if not has_type:
            return fail(
                "GOOGLE_WEB_CLIENT_ID is configured but the release APK lost the "
                "Google ID token type constant"
            )
        print("  Google ID token path: type constant present in the release APK")

    call_status = release_flag("FIREBASE_CALL_STATUS_ENABLED")
    if call_status is None:
        print("  Firebase call-status adapter: NOT_CHECKED (release BuildConfig is missing)")
    elif not call_status:
        print("  Firebase call-status adapter: flag off; R8 removes the unreferenced adapter")
    elif CALL_STATUS_ADAPTER not in mapped:
        print(
            "WARN: FIREBASE_CALL_STATUS_ENABLED=true but "
            f"{CALL_STATUS_ADAPTER} is absent from mapping.txt; the adapter has no "
            "production reference, so the flag cannot enable call-status display. "
            "Integrate the adapter or keep the flag off.",
            file=sys.stderr,
        )
    else:
        print("  Firebase call-status adapter: present in mapping.txt with the flag on")

    print(f"PASS: {len(expected)} release classes retain original names in mapping.txt")
    for label, count in group_counts.items():
        print(f"  {label}: {count}")
    app_database = APP_SOURCE / "com" / "nexachat" / "core" / "database" / "AppDatabase.java"
    database_source = app_database.read_text(encoding="utf-8")
    migration_count, other_anonymous = count_room_anonymous_classes(database_source)
    room_anonymous_count = migration_count + other_anonymous
    migration_classes = {
        name for name in expected
        if re.fullmatch(r"com\.nexachat\.core\.database\.AppDatabase\$\d+", name)
    }
    if len(migration_classes) != room_anonymous_count:
        return fail(
            f"expected {room_anonymous_count} Room migration/callback classes but found "
            f"{len(migration_classes)} named classes"
        )
    print(
        f"  Room AppDatabase anonymous classes: {room_anonymous_count} verified in mapping.txt "
        f"({migration_count} migrations, {other_anonymous} callbacks)"
    )
    print("  Gson models: not used by current Android sources; current models use Moshi")
    print("  Parcelable implementations: none in current Android sources; keep rule checked")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
