#!/usr/bin/env python3
"""tools/e2e/fixture_apks.py - Verified release APK fixtures for upgrade journey testing.

Stdlib only. Downloads release APKs and .sha256 checksums from GitHub Releases,
verifies checksums atomically, and extracts package and signer metadata using
aapt and apksigner from the Android SDK.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import uuid
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Union

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
DEFAULT_FIXTURES_DIR = REPO_ROOT / "tools" / "e2e" / ".fixtures"
DEFAULT_GITHUB_REPO = "JasonTM17/Nexachat_AppJ"
DEFAULT_BASE_URL = f"https://github.com/{DEFAULT_GITHUB_REPO}/releases/download"


def normalize_tag(tag: str) -> str:
    """Ensure release tag is formatted properly (e.g. 1.1.0 -> v1.1.0)."""
    tag = tag.strip()
    if re.match(r"^\d+(\.\d+)*", tag):
        return f"v{tag}"
    return tag


def parse_sha256(content: str, expected_filename: Optional[str] = None) -> str:
    """Extract a 64-character hex SHA-256 hash from a checksum file string."""
    for line in content.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) >= 2 and expected_filename:
            target_name = parts[1].lstrip("*")
            if target_name == expected_filename or Path(target_name).name == expected_filename:
                cand = parts[0].strip().lower()
                if len(cand) == 64 and all(c in "0123456789abcdef" for c in cand):
                    return cand
        cand = parts[0].strip().lower()
        if len(cand) == 64 and all(c in "0123456789abcdef" for c in cand):
            return cand
    raise ValueError(f"Could not extract valid 64-character hex SHA-256 hash from content: {content[:200]}")


def compute_file_sha256(path: Union[str, Path], chunk_size: int = 65536) -> str:
    """Compute the SHA-256 hex digest of a local file."""
    hasher = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(chunk_size)
            if not chunk:
                break
            hasher.update(chunk)
    return hasher.hexdigest().lower()


def fetch(
    tag: str,
    dest_dir: Optional[Union[str, Path]] = None,
    base_url: str = DEFAULT_BASE_URL,
    force: bool = False,
    chunk_size: int = 65536,
) -> Path:
    """Download a release APK and its .sha256 checksum, verifying integrity atomically.

    Reuses existing verified APK files without re-downloading if the local checksum matches.
    """
    tag = normalize_tag(tag)
    dest_path = Path(dest_dir) if dest_dir is not None else DEFAULT_FIXTURES_DIR
    dest_path.mkdir(parents=True, exist_ok=True)

    apk_name = f"nexachat-{tag}.apk"
    sha_name = f"{apk_name}.sha256"
    apk_file = dest_path / apk_name
    sha_file = dest_path / sha_name

    # Check if existing verified file is already present on disk
    if not force and apk_file.is_file() and sha_file.is_file():
        try:
            expected_sha = parse_sha256(sha_file.read_text(encoding="utf-8"), apk_name)
            actual_sha = compute_file_sha256(apk_file, chunk_size=chunk_size)
            if actual_sha == expected_sha:
                print(f"[fixture_apks] Reusing verified fixture: {apk_file} (SHA-256: {actual_sha})")
                return apk_file
        except Exception:
            pass  # Fall through to re-download if local check fails

    base = base_url.rstrip("/")
    sha_url = f"{base}/{tag}/{sha_name}"
    apk_url = f"{base}/{tag}/{apk_name}"

    # 1. Download checksum
    print(f"[fixture_apks] Fetching checksum: {sha_url}")
    req_sha = urllib.request.Request(sha_url, headers={"User-Agent": "NexaChat-E2E-Fixture-Downloader"})
    try:
        with urllib.request.urlopen(req_sha) as resp:
            sha_raw = resp.read().decode("utf-8")
    except Exception as e:
        raise RuntimeError(f"Failed to download checksum from {sha_url}: {e}") from e

    expected_sha = parse_sha256(sha_raw, apk_name)

    # If the APK is already on disk and matches expected_sha, reuse it
    if not force and apk_file.is_file():
        actual_sha = compute_file_sha256(apk_file, chunk_size=chunk_size)
        if actual_sha == expected_sha:
            sha_file.write_text(f"{actual_sha}  {apk_name}\n", encoding="utf-8")
            print(f"[fixture_apks] Existing APK matches checksum: {apk_file} (SHA-256: {actual_sha})")
            return apk_file

    # 2. Download APK atomically
    print(f"[fixture_apks] Downloading APK: {apk_url}")
    temp_apk = dest_path / f".{apk_name}.{os.getpid()}.{uuid.uuid4().hex[:8]}.tmp"
    hasher = hashlib.sha256()

    apk_req = urllib.request.Request(apk_url, headers={"User-Agent": "NexaChat-E2E-Fixture-Downloader"})
    try:
        with urllib.request.urlopen(apk_req) as resp, open(temp_apk, "wb") as f:
            content_length_hdr = resp.headers.get("Content-Length")
            expected_bytes = int(content_length_hdr) if content_length_hdr else None
            total_bytes = 0

            while True:
                chunk = resp.read(chunk_size)
                if not chunk:
                    break
                f.write(chunk)
                hasher.update(chunk)
                total_bytes += len(chunk)

            if expected_bytes is not None and total_bytes != expected_bytes:
                raise IOError(
                    f"Truncated download for {apk_name}: received {total_bytes} bytes, expected {expected_bytes} bytes"
                )

        actual_sha = hasher.hexdigest().lower()
        if actual_sha != expected_sha:
            raise ValueError(
                f"Checksum mismatch for {apk_name}!\n"
                f"  Expected: {expected_sha}\n"
                f"  Actual:   {actual_sha}"
            )

        # Atomic rename to final APK destination
        os.replace(temp_apk, apk_file)

        # Write sha256 atomically
        temp_sha = dest_path / f".{sha_name}.{os.getpid()}.{uuid.uuid4().hex[:8]}.tmp"
        temp_sha.write_text(f"{actual_sha}  {apk_name}\n", encoding="utf-8")
        os.replace(temp_sha, sha_file)

        print(f"[fixture_apks] Verified and saved: {apk_file} (SHA-256: {actual_sha})")
        return apk_file

    finally:
        if temp_apk.exists():
            try:
                temp_apk.unlink()
            except OSError:
                pass


def find_android_tools(sdk_home: Optional[Union[str, Path]] = None) -> Tuple[Path, List[str]]:
    """Locate aapt binary and apksigner command from the newest build-tools in the Android SDK."""
    sdk_str = sdk_home or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_str:
        raise RuntimeError(
            "ANDROID_HOME (or ANDROID_SDK_ROOT) environment variable is not set. "
            "Please point ANDROID_HOME to your Android SDK directory."
        )
    sdk_dir = Path(sdk_str)
    if not sdk_dir.is_dir():
        raise RuntimeError(f"Android SDK directory does not exist: {sdk_dir}")

    build_tools_dir = sdk_dir / "build-tools"
    if not build_tools_dir.is_dir():
        raise RuntimeError(f"build-tools directory not found in Android SDK: {build_tools_dir}")

    subdirs = [p for p in build_tools_dir.iterdir() if p.is_dir()]
    if not subdirs:
        raise RuntimeError(f"No build-tools versions found in {build_tools_dir}")

    def version_sort_key(p: Path):
        return [int(x) if x.isdigit() else x for x in re.split(r"(\d+)", p.name)]

    newest_bt = sorted(subdirs, key=version_sort_key)[-1]

    # Locate aapt
    aapt_candidates = [
        newest_bt / "aapt.exe",
        newest_bt / "aapt",
    ]
    aapt_bin = next((c for c in aapt_candidates if c.is_file()), None)
    if not aapt_bin:
        raise RuntimeError(f"aapt binary not found in build-tools: {newest_bt}")

    # Locate apksigner
    jar_candidate = newest_bt / "lib" / "apksigner.jar"
    if jar_candidate.is_file():
        java_bin = "java"
        if os.environ.get("JAVA_HOME"):
            cand = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
            if cand.is_file():
                java_bin = str(cand)
        elif shutil.which("java"):
            java_bin = shutil.which("java")  # type: ignore
        apksigner_cmd = [str(java_bin), "-jar", str(jar_candidate)]
    elif os.name == "nt" and (newest_bt / "apksigner.bat").is_file():
        comspec = os.environ.get("COMSPEC", "cmd.exe")
        apksigner_cmd = [comspec, "/c", str(newest_bt / "apksigner.bat")]
    elif (newest_bt / "apksigner").is_file():
        apksigner_cmd = [str(newest_bt / "apksigner")]
    else:
        raise RuntimeError(f"apksigner not found in build-tools: {newest_bt}")

    return aapt_bin, apksigner_cmd


def parse_aapt_badging(output: str) -> Dict[str, str]:
    """Parse package name, versionCode, and versionName from 'aapt dump badging' output."""
    package_match = re.search(r"package:\s+name='([^']+)'", output)
    version_code_match = re.search(r"versionCode='([^']+)'", output)
    version_name_match = re.search(r"versionName='([^']+)'", output)

    if not package_match or not version_code_match or not version_name_match:
        raise ValueError(f"Could not parse package details from aapt output:\n{output[:400]}")

    return {
        "package": package_match.group(1),
        "versionCode": version_code_match.group(1),
        "versionName": version_name_match.group(1),
    }


def parse_apksigner_output(output: str) -> List[str]:
    """Parse signer certificate SHA-256 digests from 'apksigner verify --print-certs' output."""
    matches = re.findall(r"certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", output)
    if not matches:
        matches_formatted = re.findall(r"certificate SHA-256 digest:\s*([0-9a-fA-F:\s]+)", output)
        cleaned = [
            re.sub(r"[\s:]", "", m).lower()
            for m in matches_formatted
            if len(re.sub(r"[\s:]", "", m)) == 64
        ]
        if cleaned:
            return cleaned
        raise ValueError(f"Could not find certificate SHA-256 digest in apksigner output:\n{output[:400]}")
    return [m.lower() for m in matches]


def describe(apk: Union[str, Path], sdk_home: Optional[Union[str, Path]] = None) -> Dict[str, str]:
    """Inspect an APK and print package, versionCode, versionName, and signer SHA-256."""
    apk_path = Path(apk)
    if not apk_path.is_file():
        raise FileNotFoundError(f"APK file not found: {apk_path}")

    aapt_bin, apksigner_cmd = find_android_tools(sdk_home)

    aapt_proc = subprocess.run(
        [str(aapt_bin), "dump", "badging", str(apk_path)],
        capture_output=True,
        text=True,
        check=False,
    )
    if aapt_proc.returncode != 0:
        raise RuntimeError(f"aapt dump badging failed for {apk_path}:\n{aapt_proc.stderr}")

    badging = parse_aapt_badging(aapt_proc.stdout)

    apksigner_proc = subprocess.run(
        apksigner_cmd + ["verify", "--print-certs", str(apk_path)],
        capture_output=True,
        text=True,
        check=False,
    )
    if apksigner_proc.returncode != 0:
        raise RuntimeError(f"apksigner verify failed for {apk_path}:\n{apksigner_proc.stderr}")

    signers = parse_apksigner_output(apksigner_proc.stdout)
    signer_sha256 = signers[0] if signers else ""

    info = {
        "apk": str(apk_path),
        "package": badging["package"],
        "versionCode": badging["versionCode"],
        "versionName": badging["versionName"],
        "signer_sha256": signer_sha256,
        "all_signers": signers,
    }

    print(f"APK: {apk_path}")
    print(f"  package:        {info['package']}")
    print(f"  versionCode:    {info['versionCode']}")
    print(f"  versionName:    {info['versionName']}")
    print(f"  signer SHA-256: {info['signer_sha256']}")

    return info


def assert_shared_signer(
    apk1: Union[str, Path],
    apk2: Union[str, Path],
    sdk_home: Optional[Union[str, Path]] = None,
) -> bool:
    """Assert that two release APKs share the same signer certificate SHA-256 digest."""
    info1 = describe(apk1, sdk_home=sdk_home)
    info2 = describe(apk2, sdk_home=sdk_home)
    sig1 = info1.get("signer_sha256", "").lower()
    sig2 = info2.get("signer_sha256", "").lower()

    if not sig1 or not sig2:
        raise ValueError("Could not extract signer SHA-256 from one or both APKs.")

    if sig1 != sig2:
        raise AssertionError(
            f"Release APKs do not share the same signer certificate!\n"
            f"  {apk1}: {sig1}\n"
            f"  {apk2}: {sig2}"
        )

    print(f"[fixture_apks] Signer verification: MATCH (shared signer SHA-256: {sig1})")
    return True


def resolve_apk_argument(arg: str, dest_dir: Path) -> Path:
    """Resolve an APK path from argument, checking filesystem, dest_dir, and tag names."""
    p = Path(arg)
    if p.is_file():
        return p
    cand1 = dest_dir / arg
    if cand1.is_file():
        return cand1
    tag = normalize_tag(arg)
    cand2 = dest_dir / f"nexachat-{tag}.apk"
    if cand2.is_file():
        return cand2
    raise FileNotFoundError(f"Could not find APK: {arg} (also checked {cand1} and {cand2})")


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Fetch and verify release APK fixtures for upgrade journey testing."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    # fetch
    fetch_parser = subparsers.add_parser("fetch", help="Download release APKs and verify checksums.")
    fetch_parser.add_argument("tags", nargs="+", help="Release tags to fetch (e.g. v1.1.0 v1.1.1)")
    fetch_parser.add_argument(
        "--dest-dir",
        default=str(DEFAULT_FIXTURES_DIR),
        help=f"Directory to save APK fixtures (default: {DEFAULT_FIXTURES_DIR})",
    )
    fetch_parser.add_argument(
        "--base-url",
        default=DEFAULT_BASE_URL,
        help="Base URL for release downloads (default: GitHub Releases)",
    )
    fetch_parser.add_argument(
        "--force",
        action="store_true",
        help="Force re-download even if verified fixture exists locally",
    )

    # describe
    desc_parser = subparsers.add_parser("describe", help="Print package, version and signer info.")
    desc_parser.add_argument("apks", nargs="+", help="Paths or tags of APKs to inspect.")
    desc_parser.add_argument(
        "--dest-dir",
        default=str(DEFAULT_FIXTURES_DIR),
        help=f"Directory to search for APK fixtures (default: {DEFAULT_FIXTURES_DIR})",
    )
    desc_parser.add_argument(
        "--sdk-home",
        default=None,
        help="Path to Android SDK (defaults to ANDROID_HOME environment variable)",
    )

    args = parser.parse_args(argv)

    if args.command == "fetch":
        dest_dir = Path(args.dest_dir)
        for tag in args.tags:
            fetch(tag, dest_dir=dest_dir, base_url=args.base_url, force=args.force)
        return 0

    if args.command == "describe":
        dest_dir = Path(args.dest_dir)
        resolved_apks = [resolve_apk_argument(a, dest_dir) for a in args.apks]
        infos = []
        for apk in resolved_apks:
            infos.append(describe(apk, sdk_home=args.sdk_home))

        if len(infos) >= 2:
            first_signer = infos[0]["signer_sha256"]
            for other in infos[1:]:
                if other["signer_sha256"] != first_signer:
                    print(
                        f"ERROR: Signer mismatch between {infos[0]['apk']} ({first_signer}) "
                        f"and {other['apk']} ({other['signer_sha256']})",
                        file=sys.stderr,
                    )
                    return 1
            print(f"Signer verification: MATCH (shared signer SHA-256 starts {first_signer[:8]})")
        return 0

    return 0


if __name__ == "__main__":
    sys.exit(main())
