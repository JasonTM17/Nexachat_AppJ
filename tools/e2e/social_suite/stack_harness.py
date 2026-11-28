"""DisposableStack wrapper, ephemeral admin-web build, and target-mode safety."""

from __future__ import annotations

import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.parse
import uuid
import zipfile
from pathlib import Path
from typing import Any, Dict, List, Optional

E2E_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = Path(__file__).resolve().parents[3]
if str(E2E_ROOT) not in sys.path:
    sys.path.insert(0, str(E2E_ROOT))

import disposable_stack as ds  # noqa: E402

from .client import SocialApiClient, expect_status  # noqa: E402
from .fixtures import ADMIN_PHONES_CSV, SessionStore, seed_fixture_pool  # noqa: E402
from .proxy_server import VercelProxyServer  # noqa: E402


LOOPBACK_HOSTS = frozenset({"127.0.0.1", "localhost", "::1"})


def is_loopback_url(url: str) -> bool:
    parsed = urllib.parse.urlsplit(url)
    host = (parsed.hostname or "").lower()
    return host in LOOPBACK_HOSTS


def find_fallback_jdk_bins() -> List[Path]:
    """Finds installed JDK 24/21 bin directories for fallback if host JDK 26 fails."""
    candidates: List[Path] = []
    java_root = Path(r"C:\Program Files\Java")
    if java_root.is_dir():
        for name in ("jdk-24", "jdk-21"):
            for entry in sorted(java_root.glob(f"{name}*"), reverse=True):
                bin_dir = entry / "bin"
                if (bin_dir / "java.exe").is_file():
                    candidates.append(bin_dir)
    return candidates


def create_scratch_run_dir() -> Path:
    """Creates an isolated run dir inside teamwork_tmp when available."""
    nonce = uuid.uuid4().hex[:8]
    tw_scratch = Path.home() / ".gemini" / "antigravity" / "scratch"
    if tw_scratch.is_dir():
        run_dir = tw_scratch / "teamwork_tmp" / f"social_suite_{nonce}"
        run_dir.mkdir(parents=True, exist_ok=True)
        return run_dir
    return Path(tempfile.mkdtemp(prefix=f"nexachat_social_e2e_{nonce}_"))


def build_ephemeral_admin_bundle(out_dir: Path, repo_root: Path = REPO_ROOT) -> Path:
    """Builds admin-web with VITE_API_URL='' into out_dir (same-origin /admin/)."""
    admin_dir = repo_root / "admin-web"
    vite_js = admin_dir / "node_modules" / "vite" / "bin" / "vite.js"
    if not vite_js.is_file():
        raise RuntimeError(f"admin-web vite binary not found: {vite_js}")

    out_dir.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    env["VITE_API_URL"] = ""
    env["MSYS_NO_PATHCONV"] = "1"
    env["MSYS2_ARG_CONV_EXCL"] = "*"
    cmd = [
        "node",
        str(vite_js),
        "build",
        "--base=/admin/",
        "--outDir",
        str(out_dir),
    ]
    proc = subprocess.run(
        cmd,
        cwd=str(admin_dir),
        env=env,
        capture_output=True,
        text=True,
        timeout=120,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"admin-web ephemeral build failed ({proc.returncode}):\n"
            f"STDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
        )
    return out_dir


def rebuild_backend_jar(repo_root: Path = REPO_ROOT) -> None:
    """Rebuilds server/target/nexachat-server-*.jar via Maven."""
    mvn = "mvn.cmd" if os.name == "nt" else "mvn"
    cmd = [mvn, "-f", str(repo_root / "server" / "pom.xml"), "-DskipTests", "package"]
    proc = subprocess.run(cmd, cwd=str(repo_root), capture_output=True, text=True, timeout=600)
    if proc.returncode != 0:
        raise RuntimeError(
            f"Maven package failed ({proc.returncode}):\n{proc.stdout[-2000:]}\n{proc.stderr[-2000:]}"
        )


def is_executable_spring_jar(jar_path: Path) -> bool:
    """Returns True if jar_path exists and declares Main-Class in MANIFEST.MF."""
    if not jar_path.is_file():
        return False
    try:
        with zipfile.ZipFile(jar_path, "r") as zf:
            if "META-INF/MANIFEST.MF" not in zf.namelist():
                return False
            manifest = zf.read("META-INF/MANIFEST.MF").decode("utf-8", errors="ignore")
            return "Main-Class:" in manifest
    except (OSError, zipfile.BadZipFile):
        return False


def resolve_executable_jar(
    repo_root: Path,
    run_dir: Path,
    explicit_jar: Optional[str] = None,
) -> Path:
    """Finds an executable Spring Boot fat jar, preferring server/target then server/target*."""
    if explicit_jar:
        candidate = Path(explicit_jar)
        if not is_executable_spring_jar(candidate):
            raise RuntimeError(f"Explicit --jar is missing or lacks Main-Class: {candidate}")
        return candidate

    server_dir = repo_root / "server"
    primary_jars = sorted(
        (server_dir / "target").glob("nexachat-server-*.jar"),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    for jar in primary_jars:
        if is_executable_spring_jar(jar):
            return jar

    # Fallback: check sibling target* directories (e.g. target-m1) and snapshot to run_dir
    alt_jars: List[Path] = []
    for target_dir in server_dir.glob("target*"):
        if target_dir.is_dir() and target_dir.name != "target":
            alt_jars.extend(target_dir.glob("nexachat-server-*.jar"))
    alt_jars.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    for jar in alt_jars:
        if is_executable_spring_jar(jar):
            snapshot = run_dir / "nexachat-server.jar"
            shutil.copy2(jar, snapshot)
            return snapshot

    # Last resort: package server/target/
    rebuild_backend_jar(repo_root)
    rebuilt = sorted(
        (server_dir / "target").glob("nexachat-server-*.jar"),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    for jar in rebuilt:
        if is_executable_spring_jar(jar):
            return jar
    raise RuntimeError(f"No executable nexachat-server-*.jar found under {server_dir}")


def _wait_tcp_open(host: str, port: int, timeout: float = 20.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with socket.create_connection((host, port), timeout=1.0):
                return
        except OSError:
            time.sleep(0.25)
    raise TimeoutError(f"Timed out waiting for {host}:{port}")


class StackHarness:
    """Context manager managing backend stack, admin bundle, proxy, and cleanup."""

    def __init__(
        self,
        *,
        base_url: Optional[str] = None,
        jar: Optional[str] = None,
        rebuild_jar: bool = False,
        keep_stack_on_fail: bool = False,
        i_know_this_is_not_prod: bool = False,
        admin_vite_port: Optional[int] = None,
        run_dir: Optional[Path] = None,
        repo_root: Path = REPO_ROOT,
    ) -> None:
        self.external_base_url = base_url.rstrip("/") if base_url else None
        self.explicit_jar = jar
        self.rebuild_jar = rebuild_jar
        self.keep_stack_on_fail = keep_stack_on_fail
        self.i_know_this_is_not_prod = i_know_this_is_not_prod
        self.admin_vite_port = admin_vite_port
        self.repo_root = repo_root

        self.run_dir: Optional[Path] = run_dir
        self._owns_run_dir = run_dir is None
        self.stack: Optional[ds.DisposableStack] = None
        self.proxy: Optional[VercelProxyServer] = None
        self._vite_proc: Optional[subprocess.Popen[str]] = None

        self.spring_base_url: str = ""
        self.proxy_base_url: str = ""
        self.admin_base_url: str = ""
        self.sessions = SessionStore()
        self.initial_social_settings: Optional[dict] = None
        self.had_failure: bool = False

    def __enter__(self) -> "StackHarness":
        if self.external_base_url:
            if not is_loopback_url(self.external_base_url) and not self.i_know_this_is_not_prod:
                raise ValueError(
                    f"Refusing non-loopback --base-url {self.external_base_url!r} "
                    "without --i-know-this-is-not-prod"
                )
            self.spring_base_url = self.external_base_url
        else:
            if self.rebuild_jar:
                rebuild_backend_jar(self.repo_root)

        if self.run_dir is None:
            self.run_dir = create_scratch_run_dir()
        else:
            self.run_dir.mkdir(parents=True, exist_ok=True)

        try:
            if not self.external_base_url:
                self._start_disposable_backend()

            admin_dist: Optional[Path] = None
            if self.admin_vite_port is not None:
                self._start_vite_dev(self.admin_vite_port, self.spring_base_url)
            else:
                admin_dist = build_ephemeral_admin_bundle(self.run_dir / "admin_dist", self.repo_root)

            self.proxy = VercelProxyServer(
                site_dir=self.repo_root / "site",
                spring_base_url=self.spring_base_url,
                admin_dist_dir=admin_dist,
            )
            self.proxy_base_url = self.proxy.start()
            if self.admin_vite_port is not None:
                self.admin_base_url = f"http://127.0.0.1:{self.admin_vite_port}/admin"
            else:
                self.admin_base_url = f"{self.proxy_base_url}/admin"

            spring_client = SocialApiClient(self.spring_base_url)
            seed_fixture_pool(spring_client, self.sessions)

            if self.external_base_url:
                resp = spring_client.get(
                    "/api/v1/admin/social/settings",
                    token=self.sessions.token("admin"),
                )
                if resp.status == 200:
                    self.initial_social_settings = resp.json()

            return self
        except BaseException:
            self.had_failure = True
            self._cleanup()
            raise

    def __exit__(self, exc_type, exc, tb) -> None:
        if exc_type is not None:
            self.had_failure = True
        self._cleanup()

    def _start_disposable_backend(self) -> None:
        assert self.run_dir is not None
        jar_path = resolve_executable_jar(self.repo_root, self.run_dir, self.explicit_jar)
        self.stack = ds.DisposableStack(
            str(self.run_dir),
            server_target=str(self.repo_root / "server" / "target"),
        )
        isolated_port = ds.find_free_port(port_range=(18200, 18699))
        state = self.stack.provision(app_port=isolated_port, admin_phones=ADMIN_PHONES_CSV)
        self.spring_base_url = state["base_url"]

        orig_path = os.environ.get("PATH", "")
        try:
            self.stack.start(jar=str(jar_path), readiness_timeout=110.0)
        except ds.DisposableStackError as first_err:
            fallback_bins = find_fallback_jdk_bins()
            if not fallback_bins:
                raise first_err
            last_err: BaseException = first_err
            for jdk_bin in fallback_bins:
                os.environ["PATH"] = str(jdk_bin) + os.pathsep + orig_path
                try:
                    self.stack.start(jar=str(jar_path), readiness_timeout=110.0)
                    return
                except ds.DisposableStackError as err:
                    last_err = err
            os.environ["PATH"] = orig_path
            raise last_err

    def _start_vite_dev(self, port: int, spring_url: str) -> None:
        admin_dir = self.repo_root / "admin-web"
        vite_js = admin_dir / "node_modules" / "vite" / "bin" / "vite.js"
        env = dict(os.environ)
        env["VITE_API_PROXY_TARGET"] = spring_url
        self._vite_proc = subprocess.Popen(
            ["node", str(vite_js), "--port", str(port), "--strictPort"],
            cwd=str(admin_dir),
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            text=True,
        )
        _wait_tcp_open("127.0.0.1", port, timeout=25.0)

    def _restore_external_target(self) -> None:
        if not self.external_base_url:
            return
        spring_client = SocialApiClient(self.spring_base_url)
        try:
            admin_tok = self.sessions.token("admin")
        except KeyError:
            return

        # Best-effort unblock across fixture accounts
        roles = list(self.sessions.all_records().keys())
        for a in roles:
            for b in roles:
                if a != b:
                    try:
                        spring_client.delete(
                            f"/api/v1/users/{self.sessions.uid(b)}/block",
                            token=self.sessions.token(a),
                        )
                    except Exception:
                        pass
        for role in roles:
            if role != "admin":
                try:
                    spring_client.post(
                        f"/api/v1/admin/users/{self.sessions.uid(role)}/unsuspend",
                        token=admin_tok,
                        body={"reason": "E2E restore"},
                    )
                except Exception:
                    pass

        if self.initial_social_settings is not None:
            try:
                cur_resp = spring_client.get("/api/v1/admin/social/settings", token=admin_tok)
                if cur_resp.status == 200:
                    cur = cur_resp.json()
                    rev = cur["revision"]
                    r1 = spring_client.patch(
                        "/api/v1/admin/social/settings/flags",
                        token=admin_tok,
                        body={
                            "expectedRevision": rev,
                            "webEnabled": bool(self.initial_social_settings["webEnabled"]),
                            "videoUploadsEnabled": bool(
                                self.initial_social_settings["videoUploadsEnabled"]
                            ),
                        },
                    )
                    if r1.status == 200:
                        rev = r1.json()["revision"]
                    init_pub = self.initial_social_settings.get("published")
                    if init_pub is None and cur.get("published") is not None:
                        spring_client.post(
                            "/api/v1/admin/social/settings/announcement/unpublish",
                            token=admin_tok,
                            body={"expectedRevision": rev},
                        )
                    elif init_pub is not None:
                        d_resp = spring_client.put(
                            "/api/v1/admin/social/settings/announcement/draft",
                            token=admin_tok,
                            body={"expectedRevision": rev, **init_pub},
                        )
                        if d_resp.status == 200:
                            spring_client.post(
                                "/api/v1/admin/social/settings/announcement/publish",
                                token=admin_tok,
                                body={"expectedRevision": d_resp.json()["revision"]},
                            )
            except Exception:
                pass

    def _cleanup(self) -> None:
        if self.external_base_url:
            self._restore_external_target()

        if self.proxy is not None:
            try:
                self.proxy.stop()
            except Exception:
                pass
            self.proxy = None

        if self._vite_proc is not None:
            try:
                self._vite_proc.terminate()
                self._vite_proc.wait(timeout=5)
            except Exception:
                try:
                    self._vite_proc.kill()
                except Exception:
                    pass
            self._vite_proc = None

        keep = self.keep_stack_on_fail and self.had_failure
        if self.stack is not None and not keep:
            try:
                self.stack.stop()
            except Exception:
                pass
            try:
                self.stack.dropdb()
            except Exception:
                pass

        if self.run_dir is not None and self._owns_run_dir and not keep:
            shutil.rmtree(self.run_dir, ignore_errors=True)
