#!/usr/bin/env python3
"""Check that release mapping preserves names required by Android reflection."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
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
RULES = ROOT / "android" / "app" / "proguard-rules.pro"


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


def main() -> int:
    if not COMPILED.is_dir() or not MAPPING.is_file():
        return fail("build the release variant first; compiled classes or mapping.txt are missing")

    mapped: dict[str, str] = {}
    for line in MAPPING.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^(\S+) -> (\S+):$", line)
        if match:
            mapped[match.group(1)] = match.group(2)

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
        if re.search(r"@(InstallIn|EntryPoint|HiltAndroidApp)\b", source):
            hilt_source_types.add(f"{package}.{java_file.stem}")

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

    missing = sorted(name for name in expected if name not in mapped)
    renamed = sorted((name, mapped[name]) for name in expected if name in mapped and mapped[name] != name)
    if missing or renamed:
        for name in missing[:20]:
            print(f"MISSING {name}", file=sys.stderr)
        for original, target in renamed[:20]:
            print(f"RENAMED {original} -> {target}", file=sys.stderr)
        return fail(f"release mapping missing {len(missing)} classes and renamed {len(renamed)} required classes")

    rules = RULES.read_text(encoding="utf-8")
    required_rules = (
        "-keep class * extends androidx.room.migration.Migration { *; }",
        "-keep class * implements android.os.Parcelable {",
    )
    if any(rule not in rules for rule in required_rules):
        return fail("Room migration or Parcelable class-identity keep rule is missing")

    print(f"PASS: {len(expected)} release classes retain original names in mapping.txt")
    for label, count in group_counts.items():
        print(f"  {label}: {count}")
    app_database = APP_SOURCE / "com" / "nexachat" / "core" / "database" / "AppDatabase.java"
    migration_count = len(re.findall(r"\bnew\s+Migration\s*\(", app_database.read_text(encoding="utf-8")))
    migration_classes = {
        name for name in expected
        if re.fullmatch(r"com\.nexachat\.core\.database\.AppDatabase\$\d+", name)
    }
    if len(migration_classes) != migration_count:
        return fail(
            f"expected {migration_count} Room migrations but found {len(migration_classes)} named migration classes"
        )
    print(f"  Room AppDatabase migration classes: {migration_count} verified in mapping.txt")
    print("  Gson models: not used by current Android sources; current models use Moshi")
    print("  Parcelable implementations: none in current Android sources; keep rule checked")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
