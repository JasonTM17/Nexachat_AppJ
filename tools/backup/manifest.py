"""Paired backup manifest format and shared helpers."""

from __future__ import annotations

import hashlib
import json
import logging
import os
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

FORMAT_VERSION = 1
MANIFEST_NAME = "manifest.json"
COMPLETE_MARKER = "complete.marker"
LOCK_NAME = ".capture.lock"
OBJECTS_DIR = "objects"
DUMP_NAME = "database.sql"

FILES_PATH = "/api/v1/files/"
CANONICAL_KEY_RE = re.compile(
    r"^(?!/)(?!.*\.\.)(?!.*\\)[^/]+(/[^/]+)*$"
)


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def is_canonical_storage_key(storage_key: str | None) -> bool:
    if storage_key is None or not storage_key.strip():
        return False
    if ".." in storage_key or "\\" in storage_key or storage_key.startswith("/"):
        return False
    parts = storage_key.split("/")
    if any(not segment or segment == "." for segment in parts):
        return False
    return bool(CANONICAL_KEY_RE.match(storage_key))


def storage_key_from_avatar_url(avatar_url: str | None) -> str | None:
    if avatar_url is None:
        return None
    value = avatar_url.strip()
    if not value:
        return None
    path = value
    if "://" in value:
        fragment = value.find("#")
        if fragment >= 0:
            value = value[:fragment]
        query = value.find("?")
        if query >= 0:
            value = value[:query]
        slash = value.find("/", value.find("://") + 3)
        path = value[slash:] if slash >= 0 else value
    prefix = path.rfind(FILES_PATH)
    if prefix < 0:
        return None
    key = path[prefix + len(FILES_PATH) :]
    return key if key else None


@dataclass(frozen=True)
class Reconciliation:
    referenced: int
    present: int
    missing: list[str]

    def as_dict(self) -> dict[str, Any]:
        return {
            "referenced": self.referenced,
            "present": self.present,
            "missing": list(self.missing),
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Reconciliation":
        return cls(
            int(data["referenced"]),
            int(data["present"]),
            list(data.get("missing") or []),
        )


def manifest_path(output_dir: Path) -> Path:
    return output_dir / MANIFEST_NAME


def complete_marker_path(output_dir: Path) -> Path:
    return output_dir / COMPLETE_MARKER


def lock_path(output_dir: Path) -> Path:
    return output_dir / LOCK_NAME


def load_manifest(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def write_manifest(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(".tmp")
    with temp.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2, sort_keys=True)
        handle.write("\n")
    temp.replace(path)


def write_complete_marker(output_dir: Path) -> None:
    marker = complete_marker_path(output_dir)
    marker.write_text(utc_now_iso() + "\n", encoding="utf-8")


def assert_complete_backup(output_dir: Path) -> None:
    if not complete_marker_path(output_dir).is_file():
        raise ValueError("backup is incomplete: missing complete.marker")
    manifest = manifest_path(output_dir)
    if not manifest.is_file():
        raise ValueError("backup is incomplete: missing manifest.json")


def assert_manifest_version(payload: dict[str, Any]) -> None:
    version = payload.get("format_version")
    if version != FORMAT_VERSION:
        raise ValueError(
            f"unsupported manifest format_version {version!r}; expected {FORMAT_VERSION}"
        )


class SecretRedactingFormatter(logging.Formatter):
    """Drop log records that would echo configured secrets."""

    def __init__(self, secrets: list[str], *args: object, **kwargs: object) -> None:
        super().__init__(*args, **kwargs)  # type: ignore[arg-type]
        self._secrets = [s for s in secrets if s]

    def format(self, record: logging.LogRecord) -> str:
        message = super().format(record)
        for secret in self._secrets:
            if not secret:
                continue
            explicit = (
                f"password={secret}",
                f"secret={secret}",
                f"PGPASSWORD={secret}",
                f"token={secret}",
            )
            if any(marker in message for marker in explicit):
                raise RuntimeError("secret material would appear in backup tool logs")
            if len(secret) >= 16 and secret in message:
                raise RuntimeError("secret material would appear in backup tool logs")
        return message


def configure_logging() -> None:
    secrets = [
        os.environ.get("BACKUP_DB_PASSWORD") or "",
        os.environ.get("DATABASE_PASSWORD") or "",
        os.environ.get("NEXACHAT_STORAGE_S3_SECRET_ACCESS_KEY") or "",
        os.environ.get("NEXACHAT_STORAGE_S3_ACCESS_KEY_ID") or "",
    ]
    handler = logging.StreamHandler()
    handler.setFormatter(SecretRedactingFormatter(secrets, "%(levelname)s %(message)s"))
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(logging.INFO)
