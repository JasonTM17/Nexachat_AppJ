"""Offline contract: documented app settings in .env.example are forwarded by Compose."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
COMPOSE_PATH = REPO_ROOT / "docker-compose.yml"
ENV_EXAMPLE_PATH = REPO_ROOT / ".env.example"

# Compose default per docs/operations/operations.md (Compose default column) and application.yml
# where operations.md omits a row (e.g. Google signup).
DOCUMENTED_APP_SETTINGS: dict[str, str | None] = {
    "NEXACHAT_STORAGE_PROVIDER": "local",
    "NEXACHAT_STORAGE_S3_ENDPOINT": None,
    "NEXACHAT_STORAGE_S3_REGION": "auto",
    "NEXACHAT_STORAGE_S3_BUCKET": None,
    "NEXACHAT_STORAGE_S3_ACCESS_KEY_ID": None,
    "NEXACHAT_STORAGE_S3_SECRET_ACCESS_KEY": None,
    "NEXACHAT_STORAGE_S3_API_CALL_TIMEOUT": "60s",
    "NEXACHAT_STORAGE_S3_API_CALL_ATTEMPT_TIMEOUT": "30s",
    "NEXACHAT_STORAGE_S3_CONNECTION_TIMEOUT": "5s",
    "NEXACHAT_STORAGE_S3_SOCKET_TIMEOUT": "30s",
    "NEXACHAT_MULTIPART_TEMP_DIR": None,
    "NEXACHAT_CORS_ALLOWED_ORIGINS": None,
    "NEXACHAT_GOOGLE_SIGNIN_ENABLED": "false",
    "NEXACHAT_GOOGLE_SIGNUP_ENABLED": "false",
    "NEXACHAT_GOOGLE_WEB_CLIENT_ID": None,
    "NEXACHAT_GOOGLE_ANDROID_CLIENT_IDS": None,
    "NEXACHAT_GOOGLE_JWKS_URI": "https://www.googleapis.com/oauth2/v3/certs",
    "NEXACHAT_GOOGLE_NONCE_TTL_SECONDS": "300",
}

INTERP_DEFAULT = re.compile(
    r"^\$\{(?P<name>[A-Z0-9_]+):-(?P<default>.*)\}$"
)
INTERP_REQUIRED = re.compile(
    r"^\$\{(?P<name>[A-Z0-9_]+):\?(?P<message>.+)\}$"
)


def _parse_env_example(path: Path) -> dict[str, str]:
    variables: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        variables[key.strip()] = value.strip()
    return variables


def _parse_compose_app_environment(path: Path) -> dict[str, str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    in_app = False
    app_indent: int | None = None
    in_environment = False
    env_indent: int | None = None
    environment: dict[str, str] = {}

    for raw in lines:
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip())
        stripped = raw.strip()

        if not in_app:
            if stripped == "app:":
                in_app = True
                app_indent = indent
            continue

        if app_indent is not None and indent <= app_indent and stripped.endswith(":"):
            break

        if stripped == "environment:":
            in_environment = True
            env_indent = indent
            continue

        if in_environment:
            if env_indent is not None and indent <= env_indent and not stripped.startswith("-"):
                in_environment = False
                if stripped.endswith(":"):
                    break
                continue
            match = re.match(r"^([A-Z0-9_]+):\s*(.+)$", stripped)
            if match:
                environment[match.group(1)] = match.group(2).strip()
            elif stripped and not stripped.startswith("#"):
                in_environment = False

    if not environment:
        raise ValueError(f"no app environment block found in {path}")
    return environment


def _expected_compose_value(name: str, default: str | None) -> str:
    if default is None:
        return f"${{{name}:-}}"
    return f"${{{name}:-{default}}}"


class ComposeEnvContractTests(unittest.TestCase):
    def test_env_example_documents_every_contract_name(self) -> None:
        documented = _parse_env_example(ENV_EXAMPLE_PATH)
        missing = sorted(set(DOCUMENTED_APP_SETTINGS) - set(documented))
        self.assertEqual(missing, [], f"missing from .env.example: {missing}")

    def test_compose_forwards_documented_settings(self) -> None:
        compose_env = _parse_compose_app_environment(COMPOSE_PATH)
        failures: list[str] = []

        for name, default in DOCUMENTED_APP_SETTINGS.items():
            expected = _expected_compose_value(name, default)
            actual = compose_env.get(name)
            if actual is None:
                failures.append(f"{name}: not forwarded (expected {expected})")
                continue
            if actual != expected:
                failures.append(f"{name}: got {actual!r}, expected {expected!r}")

        self.assertEqual(failures, [])


if __name__ == "__main__":
    unittest.main()
