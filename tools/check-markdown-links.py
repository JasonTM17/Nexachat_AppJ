#!/usr/bin/env python3
"""Check relative Markdown links in a repository.

Usage (from the repo root):
    python tools/check-markdown-links.py [PATH ...]

Defaults to every *.md file under the current directory. External links
(http/https/mailto), pure anchors and inline code are ignored. Exits 0 when
no broken relative link is found, 1 otherwise.
"""
from __future__ import annotations

import re
import sys
import argparse
from pathlib import Path

LINK_RE = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
REF_DEF_RE = re.compile(r"^\s{0,3}\[[^\]]+\]:\s*(\S+)", re.MULTILINE)
SKIP_PREFIXES = ("http://", "https://", "mailto:", "tel:", "data:", "//")
SKIP_DIRS = frozenset({"node_modules", ".git"})


def iter_markdown_files(paths: list[Path]) -> list[Path]:
    files: list[Path] = []
    for raw in paths:
        path = Path(raw)
        if path.is_dir():
            files.extend(sorted(
                p for p in path.rglob("*.md")
                if p.is_file() and not SKIP_DIRS.intersection(p.parts)
            ))
        elif path.is_file():
            files.append(path)
        else:
            print(f"SKIP (not found): {path}", file=sys.stderr)
    return files


def targets(text: str) -> list[str]:
    found = LINK_RE.findall(text)
    found.extend(REF_DEF_RE.findall(text))
    result = []
    for target in found:
        target = target.strip().strip("<>")
        # Drop angle-bracket autolinks such as <https://...>.
        if target.startswith("<") and target.endswith(">"):
            target = target[1:-1]
        if not target or target.startswith(SKIP_PREFIXES):
            continue
        if target.startswith("#"):
            continue
        # Strip anchors and query strings.
        target = target.split("#", 1)[0].split("?", 1)[0]
        if target:
            result.append(target)
    return result


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Check relative Markdown links in files or directories.")
    parser.add_argument("paths", nargs="*", type=Path, help="Markdown file or directory (default: current tree)")
    paths = parser.parse_args(argv[1:]).paths or [Path(".")]
    files = iter_markdown_files(paths)
    broken = 0
    checked = 0
    for md in files:
        text = md.read_text(encoding="utf-8", errors="replace")
        for target in targets(text):
            checked += 1
            resolved = (md.parent / target).resolve()
            if not resolved.exists():
                broken += 1
                print(f"BROKEN {md}: {target}")
    print(f"checked {checked} relative links in {len(files)} markdown files")
    if broken:
        print(f"FAIL: {broken} broken link(s)")
        return 1
    print("PASS: no broken relative links")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
