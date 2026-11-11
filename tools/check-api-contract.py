#!/usr/bin/env python3
"""Fail when a server controller route is missing from docs/api-contract.md.

The REST route inventory is derived statically from the Spring controller
sources (class-level @RequestMapping prefix plus method-level @*Mapping
annotations), so it also covers controllers that are conditional on a feature
flag. Every route must be mentioned in the API contract as
``METHOD /path``; the checker also reports contract entries that no controller
serves any more.

Usage:
    python tools/check-api-contract.py [--root REPO_ROOT] [--verbose]

Exit code 0 when the contract covers every route and has no stale entries.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

CONTROLLER_DIR = Path("server/src/main/java")
CONTRACT = Path("docs/api-contract.md")

MAPPING_ANNOTATIONS = {
    "GetMapping": ("GET",),
    "PostMapping": ("POST",),
    "PutMapping": ("PUT",),
    "PatchMapping": ("PATCH",),
    "DeleteMapping": ("DELETE",),
}

STRING_LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
ANNOTATION = re.compile(
    r"@(RequestMapping|GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)\b"
)
REQUEST_METHOD = re.compile(r"RequestMethod\.([A-Z]+)")
CLASS_DECL = re.compile(r"\b(?:public\s+)?(?:final\s+)?class\s+\w+")
DOC_ROUTE = re.compile(
    r"`?\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s+"
    r"(/api/v1/[A-Za-z0-9_./{}*:-]*|/actuator[A-Za-z0-9_./{}*:-]*"
    r"|/ws[A-Za-z0-9_./{}*:-]*"
    r"|/(?:auth|users|contacts|conversations|labels|messages|attachments|files"
    r"|sync|chat|calls|invite-links|account-deletion|devices|rag|realtime)"
    r"[A-Za-z0-9_./{}*:-]*)`?"
)
VARIABLE = re.compile(r"\{[^}]*\}")


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def annotation_arguments(source: str, start: int) -> str:
    """Return the parenthesised argument text of an annotation at ``start``."""
    index = source.find("(", start)
    if index == -1:
        return ""
    depth = 0
    for position in range(index, len(source)):
        char = source[position]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return source[index + 1 : position]
    return ""


def annotation_paths(arguments: str) -> list[str]:
    """Extract mapping paths from an annotation argument string.

    Handles positional paths, ``value = ...``/``path = ...``, and string arrays.
    Non-path arguments (``method``, ``consumes``, ``produces``, ``params``,
    ``headers``) contain no ``/``-prefixed literal and are ignored.
    """
    paths: list[str] = []
    for literal in STRING_LITERAL.findall(arguments):
        if literal.startswith("/"):
            paths.append(literal)
    return paths


def normalize_route(path: str) -> str:
    path = VARIABLE.sub("{}", path)
    path = re.sub(r"/{2,}", "/", path)
    return path.rstrip("/") or "/"


def controller_routes(root: Path) -> set[tuple[str, str]]:
    routes: set[tuple[str, str]] = set()
    base = root / CONTROLLER_DIR
    for source_path in sorted(base.rglob("*Controller.java")):
        source = read_text(source_path)
        if "@RestController" not in source and "@Controller" not in source:
            continue

        class_match = CLASS_DECL.search(source)
        class_body = source[: class_match.start()] if class_match else source
        prefixes: list[str] = []
        for match in ANNOTATION.finditer(class_body):
            if match.group(1) == "RequestMapping":
                prefixes.extend(annotation_paths(annotation_arguments(source, match.end())))
        if not prefixes:
            prefixes = [""]

        method_zone = source[class_match.end() :] if class_match else source
        method_mappings: list[tuple[tuple[str, ...], list[str]]] = []
        for match in ANNOTATION.finditer(method_zone):
            name = match.group(1)
            arguments = annotation_arguments(method_zone, match.end())
            if name == "RequestMapping":
                methods = tuple(m.group(1) for m in REQUEST_METHOD.finditer(arguments))
            else:
                methods = MAPPING_ANNOTATIONS[name]
            paths = annotation_paths(arguments)
            method_mappings.append((methods or ("ANY",), paths))

        for methods, paths in method_mappings:
            expanded = paths or [""]
            for prefix in prefixes:
                for path in expanded:
                    if path.startswith("/api/") or path.startswith("/actuator") or path.startswith("/ws"):
                        full = path
                    else:
                        full = prefix.rstrip("/") + "/" + path.lstrip("/")
                    for method in methods:
                        routes.add((method, normalize_route(full)))
    return routes


def contract_routes(root: Path, contract: Path) -> set[tuple[str, str]]:
    text = read_text(root / contract)
    routes: set[tuple[str, str]] = set()
    for match in DOC_ROUTE.finditer(text):
        method, path = match.group(1), match.group(2)
        if not path.startswith(("/api/", "/actuator", "/ws")):
            path = "/api/v1" + ("/" if not path.startswith("/") else "") + path
        routes.add((method, normalize_route(path)))
    return routes


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".", help="repository root")
    parser.add_argument("--contract", default=str(CONTRACT),
                        help="contract path relative to --root (default: docs/api-contract.md)")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()
    root = Path(args.root).resolve()
    contract = Path(args.contract)

    code = controller_routes(root)
    documented = contract_routes(root, contract)
    missing = sorted(code - documented)
    stale = sorted(documented - code)

    for method, path in missing:
        print(f"MISSING from {contract}: {method} {path}")
    for method, path in stale:
        print(f"STALE in {contract}: {method} {path} (no controller route)")
    if args.verbose:
        for method, path in sorted(code):
            print(f"route {method} {path}")
    print(
        f"checked {len(code)} controller routes against {len(documented)} contract entries: "
        f"{len(missing)} missing, {len(stale)} stale"
    )
    return 1 if missing or stale else 0


if __name__ == "__main__":
    sys.exit(main())
