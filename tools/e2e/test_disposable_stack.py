#!/usr/bin/env python3
"""Offline world-model tests for the disposable backend provisioner.

No java, docker, or postgres is started: the psql runner, process controller,
health probe, HTTP client, port probe, and sleep are all fakes. Only pure
helper functions touch the real filesystem (temp run dirs).
"""

from __future__ import annotations

import base64
import io
import json
import os
import re
import shutil
import socket
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

# Ensure tools/e2e is on sys.path regardless of execution working directory
_DIR = Path(__file__).resolve().parent
if str(_DIR) not in sys.path:
    sys.path.insert(0, str(_DIR))

import disposable_stack
from disposable_stack import (
    DEFAULT_DB_NAME_PREFIX,
    DEFAULT_EXTRA_JAVA_ARGS,
    DEFAULT_PORT_RANGE,
    DEFAULT_STOP_GRACE_SECONDS,
    LOGIN_PATH,
    MAINTENANCE_DB,
    NotOwnedError,
    PortUnavailableError,
    ProcessError,
    ProvisionError,
    READINESS_PATH,
    REGISTER_PATH,
    ReadinessTimeoutError,
    SEED_PASSWORD,
    SHARED_DB_NAME,
    SeedError,
    StateError,
    TargetRefusalError,
    DisposableStack,
    base_url_for,
    build_env,
    build_login_body,
    build_register_body,
    env_file_path,
    find_free_port,
    find_newest_jar,
    generate_db_name,
    generate_jwt_secret,
    generate_refresh_replay_key,
    is_local_host,
    jdbc_url_for,
    load_state,
    readiness_url_for,
    read_pid_file,
    remove_pid_file,
    render_env_sh,
    save_state,
    seed_users,
    shell_quote,
    state_file_path,
    to_posix_path,
    uploads_dir_path,
    validate_db_name,
    validate_safe_identifier,
    write_pid_file,
)


def auth_payload(phone: str) -> dict:
    """Shared register/login success body (e2e_common contract)."""
    return {
        "accessToken": f"tok-{phone}",
        "user": {"id": f"uid-{phone}", "phone": phone},
    }


class FakePsqlRunner:
    """Records psql invocations; returns canned outputs in call order."""

    def __init__(self, responses=None):
        self.queries = []
        self.responses = list(responses or [])

    def __call__(
        self,
        sql,
        *,
        db_name,
        container="nexachat-db-1",
        user="nexachat",
        host="localhost",
        timeout=30.0,
    ):
        self.queries.append(
            {"sql": sql, "db_name": db_name, "container": container, "user": user, "host": host}
        )
        index = len(self.queries) - 1
        if index < len(self.responses):
            return self.responses[index]
        return self.responses[-1] if self.responses else ""


class FakeProcessController:
    """Records spawn/terminate; pids stay alive until terminated."""

    def __init__(self):
        self.spawned = []
        self.terminates = []
        self.alive = set()
        self._next_pid = 4100

    def spawn(self, command, env, log_file):
        pid = self._next_pid
        self._next_pid += 1
        self.spawned.append(
            {"command": list(command), "env": dict(env), "log_file": log_file, "pid": pid}
        )
        self.alive.add(pid)
        return pid

    def is_alive(self, pid):
        return pid is not None and pid in self.alive

    def terminate(self, pid, grace_seconds=DEFAULT_STOP_GRACE_SECONDS):
        self.terminates.append({"pid": pid, "grace_seconds": grace_seconds})
        self.alive.discard(pid)


class FakeHealthProbe:
    """Returns scripted (status, payload) results or raises scripted errors."""

    def __init__(self, results):
        self.results = list(results)
        self.calls = []

    def __call__(self, url, timeout=None):
        self.calls.append({"url": url, "timeout": timeout})
        index = min(len(self.calls) - 1, len(self.results) - 1)
        result = self.results[index]
        if isinstance(result, Exception):
            raise result
        return result


class FakeSeedClient:
    """Records POSTs; returns scripted (status, payload) per call."""

    def __init__(self, script):
        self.script = list(script)
        self.calls = []

    def post(self, path, body=None):
        self.calls.append({"path": path, "body": dict(body or {})})
        index = min(len(self.calls) - 1, len(self.script) - 1)
        status, payload = self.script[index]
        return FakeSeedResponse(status, payload)


class FakeSeedResponse:
    def __init__(self, status, payload):
        self.status = status
        self._payload = payload

    @property
    def text(self):
        return json.dumps(self._payload)

    def json(self):
        if self._payload is None:
            raise ValueError("no json body")
        return self._payload


class FakeSleep:
    def __init__(self):
        self.calls = []

    def __call__(self, seconds):
        self.calls.append(seconds)


class HealthProbeErrorStub(disposable_stack.HealthProbeError):
    """Scripted probe failure (connection refused etc.)."""


class TempRunDirMixin:
    """Gives each test a fresh run_dir under the OS temp area."""

    def setUp(self):
        self.run_dir = tempfile.mkdtemp(prefix="disposable_stack_test_")
        self.addCleanup(shutil.rmtree, self.run_dir, True)


def make_stack(run_dir, runner=None, process=None, probe=None, port_picker=None,
               client_factory=None, sleep=None, server_target=None):
    return DisposableStack(
        run_dir,
        runner=runner or FakePsqlRunner(),
        process_controller=process or FakeProcessController(),
        health_probe=probe,
        port_picker=port_picker or (lambda: 18042),
        client_factory=client_factory,
        sleep=sleep or FakeSleep(),
        server_target=server_target or disposable_stack.DEFAULT_SERVER_TARGET,
    )


def provision_stack(run_dir, **stack_kwargs):
    """Provisions a stack with fake plumbing; returns (stack, state)."""
    stack = make_stack(run_dir, **stack_kwargs)
    state = stack.provision(
        db_name="nexachat_e2e_t1", app_port=18042, admin_phones="+84900000590"
    )
    return stack, state


class TestValidation(unittest.TestCase):
    def test_local_host_detection(self):
        self.assertTrue(is_local_host("localhost"))
        self.assertTrue(is_local_host("127.0.0.1"))
        self.assertTrue(is_local_host("127.5.4.3"))
        self.assertTrue(is_local_host("::1"))
        self.assertTrue(is_local_host("docker"))
        self.assertFalse(is_local_host("192.168.1.100"))
        self.assertFalse(is_local_host("db.example.internal"))

    def test_validate_db_name_accepts(self):
        self.assertEqual(validate_db_name("nexachat_e2e_ab12cd34"), "nexachat_e2e_ab12cd34")
        self.assertEqual(validate_db_name("nexachat_j8"), "nexachat_j8")

    def test_validate_db_name_refuses(self):
        for bad in [
            "postgres",
            "Nexachat_Upper",
            "nexachat; DROP TABLE users",
            "nexachat-dash",
            "1nexachat",
            "",
            None,
            123,
        ]:
            with self.subTest(bad=bad):
                with self.assertRaises(TargetRefusalError):
                    validate_db_name(bad)

    def test_validate_safe_identifier(self):
        self.assertEqual(validate_safe_identifier("container", "nexachat-db-1"), "nexachat-db-1")
        for bad in ["nexochat-db; rm -rf /", "user name", None, ""]:
            with self.subTest(bad=bad):
                with self.assertRaises(ValueError):
                    validate_safe_identifier("container", bad)

    def test_stack_refuses_non_local_db_host(self):
        with self.assertRaises(TargetRefusalError) as ctx:
            DisposableStack(tempfile.mkdtemp(), db_host="db.example.internal")
        self.assertIn("target host must be a local host", str(ctx.exception))


class TestSecretGeneration(unittest.TestCase):
    def test_jwt_secret_format(self):
        first = generate_jwt_secret()
        second = generate_jwt_secret()
        self.assertEqual(len(first), 128)
        self.assertTrue(re.fullmatch(r"[0-9a-f]{128}", first))
        self.assertNotEqual(first, second)

    def test_refresh_replay_key_is_canonical_base64_32_bytes(self):
        key = generate_refresh_replay_key()
        raw = base64.b64decode(key, validate=True)
        self.assertEqual(len(raw), 32)
        self.assertEqual(base64.b64encode(raw).decode("ascii"), key)
        self.assertNotEqual(key, generate_refresh_replay_key())

    def test_generate_db_name(self):
        name = generate_db_name()
        self.assertTrue(re.fullmatch(r"nexachat_e2e_[0-9a-f]{8}", name))
        self.assertNotEqual(name, generate_db_name())
        self.assertTrue(generate_db_name("nexochat_j8_").startswith("nexochat_j8_"))


class TestPortPicking(unittest.TestCase):
    def test_find_free_port_skips_busy_ports(self):
        picked = find_free_port(probe=lambda host, port: port != 18000)
        self.assertEqual(picked, 18001)

    def test_find_free_port_all_busy_raises(self):
        with self.assertRaises(PortUnavailableError) as ctx:
            find_free_port(probe=lambda host, port: False)
        self.assertIn("no free TCP port in 18000-18999", str(ctx.exception))

    def test_find_free_port_custom_range(self):
        picked = find_free_port(
            port_range=(18050, 18052), probe=lambda host, port: port == 18052
        )
        self.assertEqual(picked, 18052)

    def test_find_free_port_invalid_range(self):
        with self.assertRaises(ValueError):
            find_free_port(port_range=(18010, 18000))

    def test_find_free_port_real_socket_skips_held_port(self):
        held = None
        for candidate in range(18000, 19000):
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            try:
                sock.bind(("127.0.0.1", candidate))
            except OSError:
                sock.close()
                continue
            held = (sock, candidate)
            break
        if held is None:
            self.skipTest("no bindable 18xxx port on this machine")
        sock, port = held
        try:
            picked = find_free_port()
            self.assertTrue(DEFAULT_PORT_RANGE[0] <= picked <= DEFAULT_PORT_RANGE[1])
            self.assertNotEqual(picked, port)
        finally:
            sock.close()


class TestShellAndEnvRendering(TempRunDirMixin, unittest.TestCase):
    def test_shell_quote(self):
        self.assertEqual(shell_quote("jdbc:postgresql://localhost:5433/x"), "jdbc:postgresql://localhost:5433/x")
        self.assertEqual(shell_quote("Bed1kyKf+a/Q="), "Bed1kyKf+a/Q=")
        self.assertEqual(shell_quote("hello world"), "'hello world'")
        self.assertEqual(shell_quote("it's"), "'it'\\''s'")
        self.assertEqual(shell_quote(""), "")

    def test_to_posix_path(self):
        self.assertEqual(to_posix_path("D:\\run\\uploads dir"), "D:/run/uploads dir")
        self.assertEqual(to_posix_path("/tmp/run/uploads"), "/tmp/run/uploads")

    def test_build_env_keys_order_and_values(self):
        stack, state = provision_stack(self.run_dir)
        env = build_env(state)
        expected_keys = [
            "DATABASE_URL", "DATABASE_USER", "DATABASE_PASSWORD", "SERVER_PORT",
            "JWT_SECRET", "NEXACHAT_REFRESH_REPLAY_KEY", "STORAGE_ROOT",
            "NEXACHAT_STORAGE_PROVIDER", "NEXACHAT_ADMIN_PHONES",
            "NEXACHAT_E2EE_V1_ACTIVATION_ENABLED", "NEXACHAT_GOOGLE_SIGNIN_ENABLED",
            "NEXACHAT_GOOGLE_SIGNUP_ENABLED", "FCM_ENABLED",
            "FIREBASE_REALTIME_ENABLED", "FIREBASE_CALL_STATUS_ENABLED",
            "NEXACHAT_SUMMARY_LLM_ENABLED", "NEXACHAT_SUMMARY_LLM_FALLBACK_ENABLED",
            "NEXACHAT_SUMMARY_LOCAL_LLM_ENABLED", "NEXACHAT_RAG_EMBEDDING_PROVIDER",
            "NEXACHAT_MAIL_PROVIDER", "NEXACHAT_RETENTION_ENABLED",
            "NEXACHAT_TRUSTED_PROXY_CIDRS",
        ]
        self.assertEqual(list(env)[: len(expected_keys)], expected_keys)
        self.assertEqual(env["DATABASE_URL"], "jdbc:postgresql://localhost:5433/nexachat_e2e_t1")
        self.assertEqual(env["SERVER_PORT"], "18042")
        self.assertEqual(env["JWT_SECRET"], state["jwt_secret"])
        self.assertEqual(env["NEXACHAT_REFRESH_REPLAY_KEY"], state["refresh_replay_key"])
        self.assertEqual(env["STORAGE_ROOT"], to_posix_path(state["uploads_dir"]))
        self.assertEqual(env["NEXACHAT_ADMIN_PHONES"], "+84900000590")
        self.assertEqual(env["NEXACHAT_E2EE_V1_ACTIVATION_ENABLED"], "true")
        for offline_key in [
            "FCM_ENABLED", "FIREBASE_REALTIME_ENABLED", "FIREBASE_CALL_STATUS_ENABLED",
            "NEXACHAT_SUMMARY_LLM_ENABLED", "NEXACHAT_SUMMARY_LLM_FALLBACK_ENABLED",
            "NEXACHAT_SUMMARY_LOCAL_LLM_ENABLED", "NEXACHAT_RETENTION_ENABLED",
            "NEXACHAT_GOOGLE_SIGNIN_ENABLED", "NEXACHAT_GOOGLE_SIGNUP_ENABLED",
        ]:
            self.assertEqual(env[offline_key], "false", offline_key)
        self.assertEqual(env["NEXACHAT_RAG_EMBEDDING_PROVIDER"], "lexical")

    def test_build_env_merges_extra_env_sorted_and_quoted(self):
        second_dir = os.path.join(tempfile.mkdtemp(prefix="disposable_stack_extra_"))
        self.addCleanup(shutil.rmtree, second_dir, True)
        stack2 = make_stack(second_dir)
        state2 = stack2.provision(
            db_name="nexachat_e2e_t2",
            extra_env={"ZZZ_NOTE": "hello world", "AAA_NOTE": "plain"},
        )
        env = build_env(state2)
        keys = list(env)
        self.assertEqual(keys[-2:], ["AAA_NOTE", "ZZZ_NOTE"])
        self.assertEqual(env["AAA_NOTE"], "plain")
        self.assertEqual(env["ZZZ_NOTE"], "hello world")
        content = render_env_sh(state2)
        self.assertIn("export AAA_NOTE=plain", content)
        self.assertIn("export ZZZ_NOTE='hello world'", content)

    def test_render_env_sh_header_and_lines(self):
        stack, state = provision_stack(self.run_dir)
        content = render_env_sh(state)
        lines = content.splitlines()
        header = lines[0]
        self.assertIn("nexachat_e2e_t1", header)
        self.assertIn("18042", header)
        self.assertIn("NOT for repo/commit", header)
        for line in lines[1:]:
            self.assertTrue(line.startswith("export "), line)
        self.assertIn(
            f"export DATABASE_URL=jdbc:postgresql://localhost:5433/nexachat_e2e_t1", content
        )
        self.assertIn(f"export SERVER_PORT=18042", content)
        self.assertIn(f"export JWT_SECRET={state['jwt_secret']}", content)
        self.assertIn(
            f"export NEXACHAT_REFRESH_REPLAY_KEY={state['refresh_replay_key']}", content
        )
        self.assertIn(f"export STORAGE_ROOT={to_posix_path(state['uploads_dir'])}", content)
        self.assertTrue(content.endswith("\n"))

    def test_render_env_sh_e2ee_flag_off(self):
        stack = make_stack(self.run_dir)
        state = stack.provision(
            db_name="nexachat_e2e_t3", app_port=18043,
            e2ee_activation_enabled=False,
        )
        self.assertIn("export NEXACHAT_E2EE_V1_ACTIVATION_ENABLED=false", render_env_sh(state))

    def test_env_sh_written_on_disk_matches_state(self):
        stack, state = provision_stack(self.run_dir)
        self.assertTrue(os.path.isfile(state["env_file"]))
        with open(state["env_file"], "r", encoding="utf-8") as handle:
            on_disk = handle.read()
        self.assertEqual(on_disk, render_env_sh(state))
        self.assertIn(state["jwt_secret"], on_disk)
        self.assertNotIn("\r", on_disk)


class TestStateFileRoundTrip(TempRunDirMixin, unittest.TestCase):
    SAMPLE = {
        "version": 1,
        "owner": "disposable_stack",
        "run_dir": "/somewhere/run",
        "db_name": "nexachat_e2e_roundtrip",
        "db_created_by": "disposable_stack",
        "container": "nexachat-db-1",
        "db_user": "nexachat",
        "db_password": "nexachat",
        "db_host": "localhost",
        "db_port": 5433,
        "jdbc_url": "jdbc:postgresql://localhost:5433/nexachat_e2e_roundtrip",
        "app_port": 18077,
        "base_url": "http://127.0.0.1:18077",
        "readiness_url": "http://127.0.0.1:18077/actuator/health/readiness",
        "jwt_secret": "a" * 128,
        "refresh_replay_key": "x" * 43 + "=",
        "admin_phones": "",
        "e2ee_activation_enabled": True,
        "extra_env": {"K": "V"},
        "extra_java_args": ["--x=y"],
        "uploads_dir": "/somewhere/run/uploads",
        "env_file": "/somewhere/run/env.sh",
        "pid_file": "/somewhere/run/backend.pid",
        "log_file": "/somewhere/run/backend.log",
        "jar_path": None,
        "pid": None,
        "provisioned_at": "2026-11-19T00:00:00Z",
        "started_at": None,
        "ready_at": None,
        "stopped_at": None,
        "dropped": False,
        "dropped_at": None,
    }

    def test_round_trip(self):
        save_state(self.run_dir, dict(self.SAMPLE))
        self.assertEqual(load_state(self.run_dir), self.SAMPLE)

    def test_load_missing_returns_none(self):
        self.assertIsNone(load_state(self.run_dir))

    def test_load_corrupt_raises_state_error(self):
        Path(state_file_path(self.run_dir)).write_text("{not json", encoding="utf-8")
        with self.assertRaises(StateError):
            load_state(self.run_dir)

    def test_load_non_object_raises_state_error(self):
        Path(state_file_path(self.run_dir)).write_text("[1, 2]", encoding="utf-8")
        with self.assertRaises(StateError):
            load_state(self.run_dir)

    def test_stack_requires_state(self):
        stack = make_stack(self.run_dir)
        with self.assertRaises(StateError) as ctx:
            stack.load_state_required()
        self.assertIn("run provision first", str(ctx.exception))

    def test_pid_file_round_trip(self):
        self.assertIsNone(read_pid_file(self.run_dir))
        write_pid_file(self.run_dir, 4242)
        self.assertEqual(read_pid_file(self.run_dir), 4242)
        remove_pid_file(self.run_dir)
        self.assertIsNone(read_pid_file(self.run_dir))


class TestProvision(TempRunDirMixin, unittest.TestCase):
    def test_provision_happy_path(self):
        runner = FakePsqlRunner()
        stack = make_stack(self.run_dir, runner=runner, port_picker=lambda: 18042)
        state = stack.provision(db_name="nexachat_e2e_t1", admin_phones="+84900000590")

        self.assertEqual(len(runner.queries), 2)
        exists_query, create_query = runner.queries
        self.assertEqual(exists_query["db_name"], MAINTENANCE_DB)
        self.assertEqual(
            exists_query["sql"],
            "SELECT 1 FROM pg_database WHERE datname = 'nexachat_e2e_t1';",
        )
        self.assertEqual(create_query["db_name"], MAINTENANCE_DB)
        self.assertEqual(create_query["sql"], 'CREATE DATABASE "nexachat_e2e_t1";')
        self.assertEqual(create_query["container"], "nexachat-db-1")
        self.assertEqual(create_query["user"], "nexachat")

        self.assertEqual(state["db_name"], "nexachat_e2e_t1")
        self.assertEqual(state["app_port"], 18042)
        self.assertEqual(state["jdbc_url"], "jdbc:postgresql://localhost:5433/nexachat_e2e_t1")
        self.assertEqual(state["jdbc_url"], jdbc_url_for("nexachat_e2e_t1"))
        self.assertEqual(state["base_url"], base_url_for(18042))
        self.assertEqual(state["readiness_url"], readiness_url_for(18042))
        self.assertTrue(re.fullmatch(r"[0-9a-f]{128}", state["jwt_secret"]))
        replay_raw = base64.b64decode(state["refresh_replay_key"], validate=True)
        self.assertEqual(len(replay_raw), 32)
        self.assertEqual(state["extra_java_args"], list(DEFAULT_EXTRA_JAVA_ARGS))
        self.assertTrue(os.path.isdir(state["uploads_dir"]))
        self.assertTrue(os.path.isfile(state["env_file"]))
        self.assertEqual(load_state(self.run_dir), state)
        self.assertFalse(state["dropped"])
        self.assertIsNone(state["pid"])

    def test_provision_random_db_name(self):
        runner = FakePsqlRunner()
        stack = make_stack(self.run_dir, runner=runner)
        state = stack.provision()
        self.assertTrue(re.fullmatch(r"nexachat_e2e_[0-9a-f]{8}", state["db_name"]))
        self.assertTrue(state["db_name"].startswith(DEFAULT_DB_NAME_PREFIX))
        self.assertIn(state["db_name"], runner.queries[0]["sql"])
        self.assertIn(state["db_name"], runner.queries[1]["sql"])

    def test_provision_random_name_retries_on_collision(self):
        runner = FakePsqlRunner(responses=["1", "", ""])
        stack = make_stack(self.run_dir, runner=runner)
        state = stack.provision()
        self.assertEqual(len(runner.queries), 3)
        first_sql = runner.queries[0]["sql"]
        second_sql = runner.queries[1]["sql"]
        self.assertNotEqual(first_sql, second_sql)
        self.assertIn("pg_database", first_sql)
        self.assertIn("pg_database", second_sql)
        self.assertTrue(runner.queries[2]["sql"].startswith("CREATE DATABASE"))
        self.assertIn(state["db_name"], runner.queries[2]["sql"])

    def test_provision_refuses_existing_db(self):
        runner = FakePsqlRunner(responses=["1"])
        stack = make_stack(self.run_dir, runner=runner)
        with self.assertRaises(ProvisionError) as ctx:
            stack.provision(db_name="nexachat_e2e_taken")
        self.assertIn("already exists", str(ctx.exception))
        self.assertEqual(len(runner.queries), 1)
        self.assertFalse(os.path.isfile(state_file_path(self.run_dir)))
        self.assertFalse(os.path.isdir(uploads_dir_path(self.run_dir)))

    def test_provision_refuses_existing_state_file(self):
        save_state(self.run_dir, {"version": 1})
        runner = FakePsqlRunner()
        stack = make_stack(self.run_dir, runner=runner)
        with self.assertRaises(ProvisionError) as ctx:
            stack.provision(db_name="nexachat_e2e_t9")
        self.assertIn("already exists", str(ctx.exception))
        self.assertEqual(len(runner.queries), 0)

    def test_provision_refuses_shared_db_name(self):
        runner = FakePsqlRunner()
        stack = make_stack(self.run_dir, runner=runner)
        with self.assertRaises(ProvisionError) as ctx:
            stack.provision(db_name=SHARED_DB_NAME)
        self.assertIn("shared development database", str(ctx.exception))
        self.assertEqual(len(runner.queries), 0)

    def test_provision_refuses_unsafe_db_name(self):
        runner = FakePsqlRunner()
        stack = make_stack(self.run_dir, runner=runner)
        with self.assertRaises(TargetRefusalError):
            stack.provision(db_name="nexochat_bad; DROP TABLE users")
        with self.assertRaises(TargetRefusalError):
            stack.provision(db_name="Nexachat_Upper")
        self.assertEqual(len(runner.queries), 0)

    def test_provision_refuses_port_colliding_with_db_port(self):
        stack = make_stack(self.run_dir)
        with self.assertRaises(ProvisionError) as ctx:
            stack.provision(db_name="nexachat_e2e_t4", app_port=5433)
        self.assertIn("collides with the database port", str(ctx.exception))

    def test_provision_rolls_back_db_when_state_write_fails(self):
        runner = FakePsqlRunner(responses=["", "", ""])
        stack = make_stack(self.run_dir, runner=runner)

        def failing_save(run_dir, state):
            raise OSError("simulated disk failure")

        with patch.object(disposable_stack, "save_state", failing_save):
            with self.assertRaises(OSError):
                stack.provision(db_name="nexachat_e2e_rollback")
        self.assertEqual(len(runner.queries), 3)
        drop_sql = runner.queries[2]["sql"]
        self.assertIn('DROP DATABASE IF EXISTS "nexachat_e2e_rollback"', drop_sql)
        self.assertIn("WITH (FORCE)", drop_sql)
        self.assertFalse(os.path.isfile(state_file_path(self.run_dir)))

    def test_provision_records_custom_db_credentials(self):
        runner = FakePsqlRunner()
        stack = DisposableStack(
            self.run_dir,
            container="alt-db-1",
            db_user="altuser",
            db_password="altpass",
            runner=runner,
            process_controller=FakeProcessController(),
            port_picker=lambda: 18099,
        )
        state = stack.provision(db_name="nexachat_e2e_alt")
        self.assertEqual(runner.queries[0]["container"], "alt-db-1")
        self.assertEqual(runner.queries[0]["user"], "altuser")
        self.assertEqual(state["db_user"], "altuser")
        self.assertEqual(state["db_password"], "altpass")
        self.assertEqual(state["jdbc_url"], "jdbc:postgresql://localhost:5433/nexachat_e2e_alt")
        self.assertIn("export DATABASE_USER=altuser", render_env_sh(state))


class TestJarDiscovery(TempRunDirMixin, unittest.TestCase):
    def _make_jars(self):
        target = os.path.join(self.run_dir, "target")
        os.makedirs(target)
        now = time.time()
        jars = {
            "nexachat-server-0.0.1.jar": now - 1000,
            "nexachat-server-0.1.0.jar": now,
            "nexachat-server-0.2.0-SNAPSHOT.jar": now + 1000,
        }
        for name, mtime in jars.items():
            path = os.path.join(target, name)
            Path(path).write_text("fake")
            os.utime(path, (mtime, mtime))
        # Sources jar is newest but must be skipped.
        sources = os.path.join(target, "nexachat-server-0.3.0-sources.jar")
        Path(sources).write_text("fake")
        os.utime(sources, (now + 2000, now + 2000))
        # Unrelated jar and a directory masquerading as a jar are skipped.
        Path(os.path.join(target, "other-lib-1.0.jar")).write_text("fake")
        os.makedirs(os.path.join(target, "nexachat-server-fake.jar"))
        return target

    def test_find_newest_jar_skips_sources_and_non_artifacts(self):
        target = self._make_jars()
        picked = find_newest_jar(target)
        self.assertEqual(os.path.basename(picked), "nexachat-server-0.2.0-SNAPSHOT.jar")

    def test_find_newest_jar_missing_dir(self):
        with self.assertRaises(ProvisionError):
            find_newest_jar(os.path.join(self.run_dir, "nope"))

    def test_find_newest_jar_no_candidates(self):
        empty = os.path.join(self.run_dir, "empty")
        os.makedirs(empty)
        with self.assertRaises(ProvisionError):
            find_newest_jar(empty)


class TestStart(TempRunDirMixin, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.jar = os.path.join(self.run_dir, "fake-server.jar")
        Path(self.jar).write_text("fake")

    def _ready_probe(self):
        return FakeHealthProbe(
            [HealthProbeErrorStub("connection refused"), (200, {"status": "UP"})]
        )

    def test_start_happy_path(self):
        probe = self._ready_probe()
        process = FakeProcessController()
        runner = FakePsqlRunner()
        stack = make_stack(self.run_dir, runner=runner, process=process, probe=probe)
        stack.provision(db_name="nexachat_e2e_t1")

        state = stack.start(jar=self.jar, readiness_timeout=5, poll_interval=0)

        self.assertEqual(len(process.spawned), 1)
        spawn = process.spawned[0]
        self.assertEqual(spawn["command"][0:3], ["java", "-jar", os.path.abspath(self.jar)])
        self.assertIn("--server.address=127.0.0.1", spawn["command"])
        self.assertIn("--server.port=18042", spawn["command"])
        for arg in DEFAULT_EXTRA_JAVA_ARGS:
            self.assertIn(arg, spawn["command"])
        self.assertEqual(spawn["log_file"], state["log_file"])

        env = spawn["env"]
        self.assertEqual(env["SERVER_PORT"], "18042")
        self.assertEqual(env["DATABASE_URL"], "jdbc:postgresql://localhost:5433/nexachat_e2e_t1")
        self.assertEqual(env["DATABASE_USER"], "nexachat")
        self.assertEqual(env["JWT_SECRET"], state["jwt_secret"])
        self.assertEqual(env["NEXACHAT_REFRESH_REPLAY_KEY"], state["refresh_replay_key"])
        self.assertEqual(env["STORAGE_ROOT"], to_posix_path(state["uploads_dir"]))
        self.assertEqual(env["FCM_ENABLED"], "false")

        self.assertEqual(read_pid_file(self.run_dir), spawn["pid"])
        persisted = load_state(self.run_dir)
        self.assertEqual(persisted["pid"], spawn["pid"])
        self.assertEqual(persisted["jar_path"], os.path.abspath(self.jar))
        self.assertIsNotNone(persisted["started_at"])
        self.assertIsNotNone(persisted["ready_at"])

        self.assertEqual(len(probe.calls), 2)
        self.assertEqual(
            probe.calls[0]["url"], "http://127.0.0.1:18042" + READINESS_PATH
        )

    def test_start_discovers_newest_jar(self):
        target = TestJarDiscovery._make_jars(self)
        runner = FakePsqlRunner()
        process = FakeProcessController()
        stack = make_stack(
            self.run_dir, runner=runner, process=process,
            probe=FakeHealthProbe([(200, {"status": "UP"})]),
            server_target=target,
        )
        stack.provision(db_name="nexachat_e2e_t1")
        state = stack.start(readiness_timeout=5, poll_interval=0)
        expected = find_newest_jar(target)
        self.assertEqual(process.spawned[0]["command"][2], expected)
        self.assertEqual(state["jar_path"], expected)

    def test_start_readiness_timeout_terminates_spawned_process(self):
        probe = FakeHealthProbe([HealthProbeErrorStub("connection refused")])
        process = FakeProcessController()
        stack = make_stack(self.run_dir, process=process, probe=probe)
        stack.provision(db_name="nexachat_e2e_t1")

        with self.assertRaises(ReadinessTimeoutError) as ctx:
            stack.start(jar=self.jar, readiness_timeout=0.05, poll_interval=0.01)
        self.assertIn("not ready within", str(ctx.exception))

        self.assertEqual(len(process.spawned), 1)
        spawned_pid = process.spawned[0]["pid"]
        self.assertEqual(
            process.terminates, [{"pid": spawned_pid, "grace_seconds": 10.0}]
        )
        self.assertIsNone(read_pid_file(self.run_dir))
        self.assertIsNone(load_state(self.run_dir)["pid"])

    def test_start_readiness_reports_down_status(self):
        probe = FakeHealthProbe([(503, {"status": "DOWN"})])
        process = FakeProcessController()
        stack = make_stack(self.run_dir, process=process, probe=probe)
        stack.provision(db_name="nexachat_e2e_t1")
        with self.assertRaises(ReadinessTimeoutError) as ctx:
            stack.start(jar=self.jar, readiness_timeout=0.05, poll_interval=0.01)
        self.assertIn("HTTP 503", str(ctx.exception))
        self.assertEqual(len(process.terminates), 1)

    def test_start_refuses_when_already_running(self):
        process = FakeProcessController()
        stack = make_stack(self.run_dir, process=process)
        stack.provision(db_name="nexachat_e2e_t1")
        process.alive.add(4242)
        write_pid_file(self.run_dir, 4242)
        with self.assertRaises(ProcessError) as ctx:
            stack.start(jar=self.jar)
        self.assertIn("already running", str(ctx.exception))
        self.assertEqual(len(process.spawned), 0)

    def test_start_requires_state(self):
        stack = make_stack(self.run_dir)
        with self.assertRaises(StateError):
            stack.start(jar=self.jar)

    def test_start_refuses_missing_jar(self):
        stack = make_stack(self.run_dir)
        stack.provision(db_name="nexachat_e2e_t1")
        with self.assertRaises(ProvisionError) as ctx:
            stack.start(jar=os.path.join(self.run_dir, "missing.jar"))
        self.assertIn("jar not found", str(ctx.exception))


class TestStop(TempRunDirMixin, unittest.TestCase):
    def test_stop_terminates_recorded_pid(self):
        process = FakeProcessController()
        stack = make_stack(self.run_dir, process=process)
        stack.provision(db_name="nexachat_e2e_t1")
        process.alive.add(4242)
        write_pid_file(self.run_dir, 4242)

        state = stack.stop()
        self.assertEqual(
            process.terminates,
            [{"pid": 4242, "grace_seconds": DEFAULT_STOP_GRACE_SECONDS}],
        )
        self.assertIsNone(read_pid_file(self.run_dir))
        self.assertIsNone(state["pid"])
        self.assertIsNotNone(state["stopped_at"])
        self.assertEqual(load_state(self.run_dir)["stopped_at"], state["stopped_at"])

    def test_stop_not_running_is_noop(self):
        process = FakeProcessController()
        stack = make_stack(self.run_dir, process=process)
        stack.provision(db_name="nexachat_e2e_t1")
        state = stack.stop()
        self.assertEqual(process.terminates, [])
        self.assertIsNone(state["pid"])
        self.assertIsNone(state["stopped_at"])
        self.assertIsNone(read_pid_file(self.run_dir))

    def test_stop_clears_stale_pid_file_without_terminating(self):
        process = FakeProcessController()
        stack = make_stack(self.run_dir, process=process)
        stack.provision(db_name="nexachat_e2e_t1")
        write_pid_file(self.run_dir, 7777)  # never alive in the fake
        state = stack.stop()
        self.assertEqual(process.terminates, [])
        self.assertIsNone(read_pid_file(self.run_dir))
        self.assertIsNone(state["stopped_at"])

    def test_stop_terminates_only_its_own_process(self):
        process = FakeProcessController()
        stack_a = make_stack(os.path.join(self.run_dir, "a"), process=process)
        stack_b = make_stack(os.path.join(self.run_dir, "b"), process=process)
        stack_a.provision(db_name="nexachat_e2e_a")
        stack_b.provision(db_name="nexachat_e2e_b")

        pid_a = process.spawn(command=["java"], env={}, log_file="a.log")
        pid_b = process.spawn(command=["java"], env={}, log_file="b.log")
        write_pid_file(stack_a.run_dir, pid_a)
        write_pid_file(stack_b.run_dir, pid_b)

        stack_a.stop()
        self.assertEqual(process.terminates, [{"pid": pid_a, "grace_seconds": 10.0}])
        self.assertTrue(process.is_alive(pid_b))
        self.assertEqual(read_pid_file(stack_b.run_dir), pid_b)
        self.assertIsNone(read_pid_file(stack_a.run_dir))

    def test_stop_raises_when_process_survives_terminate(self):
        class StubbornController(FakeProcessController):
            def terminate(self, pid, grace_seconds=10.0):
                self.terminates.append({"pid": pid, "grace_seconds": grace_seconds})
                # stays alive on purpose

        process = StubbornController()
        stack = make_stack(self.run_dir, process=process)
        stack.provision(db_name="nexachat_e2e_t1")
        pid = process.spawn(command=["java"], env={}, log_file="x.log")
        write_pid_file(self.run_dir, pid)
        with self.assertRaises(ProcessError) as ctx:
            stack.stop()
        self.assertIn("did not exit", str(ctx.exception))


class TestSeed(unittest.TestCase):
    PHONE = "+84900000001"
    NAME = "An"

    def test_register_request_construction(self):
        client = FakeSeedClient([(201, auth_payload(self.PHONE))])
        records = seed_users(client, [(self.PHONE, self.NAME)])
        self.assertEqual(len(client.calls), 1)
        self.assertEqual(client.calls[0]["path"], REGISTER_PATH)
        self.assertEqual(
            client.calls[0]["body"],
            {"phone": self.PHONE, "password": SEED_PASSWORD, "displayName": self.NAME},
        )
        self.assertEqual(records, [
            {
                "phone": self.PHONE,
                "name": self.NAME,
                "user_id": f"uid-{self.PHONE}",
                "access_token": f"tok-{self.PHONE}",
            }
        ])

    def test_register_409_falls_back_to_login(self):
        client = FakeSeedClient([
            (409, {"error": {"code": "PHONE_TAKEN"}}),
            (200, auth_payload(self.PHONE)),
        ])
        records = seed_users(client, [(self.PHONE, self.NAME)])
        self.assertEqual(len(client.calls), 2)
        self.assertEqual(client.calls[0]["path"], REGISTER_PATH)
        self.assertEqual(client.calls[1]["path"], LOGIN_PATH)
        self.assertEqual(
            client.calls[1]["body"], {"phone": self.PHONE, "password": SEED_PASSWORD}
        )
        self.assertNotIn("displayName", client.calls[1]["body"])
        self.assertEqual(records[0]["user_id"], f"uid-{self.PHONE}")

    def test_register_unexpected_status_raises(self):
        client = FakeSeedClient([(500, {"error": {"code": "BOOM"}})])
        with self.assertRaises(SeedError) as ctx:
            seed_users(client, [(self.PHONE, self.NAME)])
        self.assertIn("expected 200/201/409", str(ctx.exception))

    def test_login_unexpected_status_raises(self):
        client = FakeSeedClient([
            (409, {"error": {"code": "PHONE_TAKEN"}}),
            (500, {"error": {"code": "BOOM"}}),
        ])
        with self.assertRaises(SeedError) as ctx:
            seed_users(client, [(self.PHONE, self.NAME)])
        self.assertIn("login", str(ctx.exception))

    def test_register_rate_limit_is_waited_out(self):
        client = FakeSeedClient([
            (429, {"error": {"code": "RATE_LIMITED", "details": {"retryAfterSeconds": 0}}}),
            (201, auth_payload(self.PHONE)),
        ])
        fake_sleep = FakeSleep()
        records = seed_users(client, [(self.PHONE, self.NAME)], sleep=fake_sleep)
        self.assertEqual(fake_sleep.calls, [1.0])  # retryAfterSeconds + 1
        self.assertEqual(records[0]["phone"], self.PHONE)

    def test_login_rate_limit_is_waited_out(self):
        client = FakeSeedClient([
            (409, {"error": {"code": "PHONE_TAKEN"}}),
            (429, {"error": {"code": "RATE_LIMITED", "details": {"retryAfterSeconds": 2}}}),
            (200, auth_payload(self.PHONE)),
        ])
        fake_sleep = FakeSleep()
        records = seed_users(client, [(self.PHONE, self.NAME)], sleep=fake_sleep)
        self.assertEqual(fake_sleep.calls, [3.0])
        self.assertEqual(records[0]["access_token"], f"tok-{self.PHONE}")

    def test_missing_access_token_raises(self):
        client = FakeSeedClient([
            (201, {"user": {"id": "u1", "phone": self.PHONE}}),
        ])
        with self.assertRaises(SeedError) as ctx:
            seed_users(client, [(self.PHONE, self.NAME)])
        self.assertIn("no accessToken", str(ctx.exception))

    def test_missing_user_id_raises(self):
        client = FakeSeedClient([
            (201, {"accessToken": "t", "user": {"phone": self.PHONE}}),
        ])
        with self.assertRaises(SeedError):
            seed_users(client, [(self.PHONE, self.NAME)])

    def test_phone_mismatch_raises(self):
        client = FakeSeedClient([
            (201, {"accessToken": "t", "user": {"id": "u1", "phone": "+84999999999"}}),
        ])
        with self.assertRaises(SeedError) as ctx:
            seed_users(client, [(self.PHONE, self.NAME)])
        self.assertIn("returned user", str(ctx.exception))

    def test_multiple_users_seeded_in_order(self):
        users = [("+84900000001", "An"), ("+84900000003", "Chi")]
        client = FakeSeedClient([
            (201, auth_payload("+84900000001")),
            (201, auth_payload("+84900000003")),
        ])
        records = seed_users(client, users)
        self.assertEqual([r["phone"] for r in records], ["+84900000001", "+84900000003"])
        self.assertEqual(
            [c["body"]["displayName"] for c in client.calls], ["An", "Chi"]
        )

    def test_users_accept_dicts_and_tuples(self):
        client = FakeSeedClient([(201, auth_payload("+84900000001"))])
        records = seed_users(client, [{"phone": "+84900000001", "name": "An"}])
        self.assertEqual(records[0]["name"], "An")

    def test_malformed_user_raises_value_error(self):
        with self.assertRaises(ValueError):
            seed_users(FakeSeedClient([]), [("no-name",)])
        with self.assertRaises(ValueError):
            seed_users(FakeSeedClient([]), [])

    def test_seed_body_builders_match_e2e_contract(self):
        self.assertEqual(
            build_register_body("+84900000001", "An"),
            {"phone": "+84900000001", "password": "password123", "displayName": "An"},
        )
        self.assertEqual(
            build_login_body("+84900000001"),
            {"phone": "+84900000001", "password": "password123"},
        )


class TestSeedViaStack(TempRunDirMixin, unittest.TestCase):
    def test_seed_uses_state_base_url_and_injectable_client(self):
        seen_urls = []
        client = FakeSeedClient([(201, auth_payload("+84900000001"))])
        stack = make_stack(
            self.run_dir,
            client_factory=lambda url: seen_urls.append(url) or client,
        )
        stack.provision(db_name="nexachat_e2e_t1")
        records = stack.seed([("+84900000001", "An")])
        self.assertEqual(seen_urls, ["http://127.0.0.1:18042"])
        self.assertEqual(records[0]["phone"], "+84900000001")

    def test_seed_requires_state(self):
        stack = make_stack(self.run_dir)
        with self.assertRaises(StateError):
            stack.seed([("+84900000001", "An")])


class TestDropdbSafety(TempRunDirMixin, unittest.TestCase):
    def test_dropdb_drops_only_recorded_db(self):
        runner = FakePsqlRunner()
        process = FakeProcessController()
        stack = make_stack(self.run_dir, runner=runner, process=process)
        state = stack.provision(db_name="nexachat_e2e_dropme")

        result = stack.dropdb()
        self.assertEqual(len(runner.queries), 3)
        drop_query = runner.queries[2]
        self.assertEqual(drop_query["db_name"], MAINTENANCE_DB)
        self.assertEqual(
            drop_query["sql"],
            'DROP DATABASE IF EXISTS "nexachat_e2e_dropme" WITH (FORCE);',
        )
        self.assertTrue(result["dropped"])
        self.assertIsNotNone(result["dropped_at"])
        self.assertTrue(load_state(self.run_dir)["dropped"])

    def test_dropdb_twice_refuses(self):
        stack = make_stack(self.run_dir)
        stack.provision(db_name="nexachat_e2e_dropme")
        stack.dropdb()
        with self.assertRaises(NotOwnedError) as ctx:
            stack.dropdb()
        self.assertIn("already dropped", str(ctx.exception))

    def test_dropdb_without_state_refuses(self):
        stack = make_stack(self.run_dir)
        with self.assertRaises(NotOwnedError) as ctx:
            stack.dropdb()
        self.assertIn("nothing owned here", str(ctx.exception))

    def test_dropdb_refuses_state_not_created_by_tool(self):
        save_state(
            self.run_dir,
            {
                "version": 1,
                "db_name": "nexachat_handmade",
                "db_created_by": "someone_else",
            },
        )
        runner = FakePsqlRunner()
        stack = make_stack(self.run_dir, runner=runner)
        with self.assertRaises(NotOwnedError) as ctx:
            stack.dropdb()
        self.assertIn("does not record a database created by disposable_stack", str(ctx.exception))
        self.assertEqual(len(runner.queries), 0)

    def test_dropdb_refuses_shared_development_db(self):
        save_state(
            self.run_dir,
            {
                "version": 1,
                "db_name": SHARED_DB_NAME,
                "db_created_by": "disposable_stack",
            },
        )
        runner = FakePsqlRunner()
        stack = make_stack(self.run_dir, runner=runner)
        with self.assertRaises(NotOwnedError) as ctx:
            stack.dropdb()
        self.assertIn("shared development database", str(ctx.exception))
        self.assertEqual(len(runner.queries), 0)

    def test_dropdb_refuses_invalid_db_name_in_state(self):
        save_state(
            self.run_dir,
            {
                "version": 1,
                "db_name": "nexochat; DROP DATABASE postgres",
                "db_created_by": "disposable_stack",
            },
        )
        runner = FakePsqlRunner()
        stack = make_stack(self.run_dir, runner=runner)
        with self.assertRaises(NotOwnedError) as ctx:
            stack.dropdb()
        self.assertIn("invalid database name", str(ctx.exception))
        self.assertEqual(len(runner.queries), 0)

    def test_dropdb_refuses_while_backend_running(self):
        runner = FakePsqlRunner()
        process = FakeProcessController()
        stack = make_stack(self.run_dir, runner=runner, process=process)
        stack.provision(db_name="nexachat_e2e_live")
        process.alive.add(4242)
        write_pid_file(self.run_dir, 4242)

        with self.assertRaises(ProcessError) as ctx:
            stack.dropdb()
        self.assertIn("stop before dropping", str(ctx.exception))
        self.assertEqual(len(runner.queries), 2)  # provision only; no DROP


class TestStatus(TempRunDirMixin, unittest.TestCase):
    def test_status_unprovisioned(self):
        stack = make_stack(self.run_dir)
        report = stack.status()
        self.assertEqual(report["run_dir"], os.path.abspath(self.run_dir))
        self.assertFalse(report["provisioned"])
        self.assertFalse(report["running"])

    def test_status_running_with_db(self):
        runner = FakePsqlRunner(responses=["", "", "1"])
        process = FakeProcessController()
        stack = make_stack(self.run_dir, runner=runner, process=process)
        stack.provision(db_name="nexachat_e2e_t1")
        process.alive.add(4242)
        write_pid_file(self.run_dir, 4242)

        report = stack.status()
        self.assertTrue(report["provisioned"])
        self.assertTrue(report["running"])
        self.assertEqual(report["pid"], 4242)
        self.assertTrue(report["db_exists"])
        self.assertEqual(report["db_name"], "nexachat_e2e_t1")
        self.assertEqual(report["base_url"], "http://127.0.0.1:18042")
        self.assertEqual(len(runner.queries), 3)

    def test_status_stopped_pid_not_reported(self):
        runner = FakePsqlRunner(responses=["", "", "1"])
        process = FakeProcessController()
        stack = make_stack(self.run_dir, runner=runner, process=process)
        stack.provision(db_name="nexachat_e2e_t1")
        write_pid_file(self.run_dir, 4242)  # stale: not alive in the fake

        report = stack.status()
        self.assertFalse(report["running"])
        self.assertIsNone(report["pid"])
        self.assertTrue(report["db_exists"])

    def test_status_skips_db_check_after_drop(self):
        runner = FakePsqlRunner()
        process = FakeProcessController()
        stack = make_stack(self.run_dir, runner=runner, process=process)
        stack.provision(db_name="nexachat_e2e_t1")
        stack.dropdb()
        queries_before = len(runner.queries)
        report = stack.status()
        self.assertTrue(report["dropped"])
        self.assertFalse(report["db_exists"])
        self.assertEqual(len(runner.queries), queries_before)

    def test_status_survives_db_check_error(self):
        class BrokenRunner(FakePsqlRunner):
            def __call__(self, *args, **kwargs):
                raise disposable_stack.PsqlExecutionError("docker down")

        process = FakeProcessController()
        stack = make_stack(self.run_dir, runner=BrokenRunner(), process=process)
        save_state(
            self.run_dir,
            {
                "version": 1,
                "run_dir": self.run_dir,
                "db_name": "nexachat_e2e_t1",
                "db_created_by": "disposable_stack",
                "container": "nexachat-db-1",
                "app_port": 18042,
                "base_url": "http://127.0.0.1:18042",
                "env_file": env_file_path(self.run_dir),
                "pid_file": disposable_stack.pid_file_path(self.run_dir),
                "dropped": False,
            },
        )
        report = stack.status()
        self.assertFalse(report["db_exists"])
        self.assertFalse(report["running"])


class TestCliFlow(TempRunDirMixin, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.jar = os.path.join(self.run_dir, "fake-server.jar")
        Path(self.jar).write_text("fake")

    def test_cli_provision_start_status_stop_dropdb(self):
        runner = FakePsqlRunner(responses=["", "", "1", ""])
        process = FakeProcessController()
        probe = FakeHealthProbe([(200, {"status": "UP"})])

        with patch.object(disposable_stack, "_ACTIVE_PSQL_RUNNER", runner), \
             patch.object(disposable_stack, "_ACTIVE_PROCESS_CONTROLLER", process), \
             patch.object(disposable_stack, "_ACTIVE_HEALTH_PROBE", probe):
            with patch("sys.stdout", new_callable=io.StringIO) as out:
                ret = disposable_stack.main(
                    ["provision", self.run_dir, "--db-name", "nexachat_e2e_cli1", "--port", "18055"]
                )
            self.assertEqual(ret, 0)
            self.assertIn("nexachat_e2e_cli1", out.getvalue())
            self.assertIn("18055", out.getvalue())

            with patch("sys.stdout", new_callable=io.StringIO) as out:
                ret = disposable_stack.main(
                    ["start", self.run_dir, "--jar", self.jar, "--readiness-timeout", "5"]
                )
            self.assertEqual(ret, 0)
            self.assertIn("http://127.0.0.1:18055", out.getvalue())
            self.assertEqual(len(process.spawned), 1)
            self.assertEqual(process.spawned[0]["command"][2], os.path.abspath(self.jar))

            with patch("sys.stdout", new_callable=io.StringIO) as out:
                ret = disposable_stack.main(["status", self.run_dir, "--json"])
            self.assertEqual(ret, 0)
            report = json.loads(out.getvalue())
            self.assertTrue(report["running"])
            self.assertTrue(report["db_exists"])

            with patch("sys.stdout", new_callable=io.StringIO) as out:
                ret = disposable_stack.main(["stop", self.run_dir])
            self.assertEqual(ret, 0)
            self.assertIn("backend stopped", out.getvalue())

            with patch("sys.stdout", new_callable=io.StringIO) as out:
                ret = disposable_stack.main(["dropdb", self.run_dir])
            self.assertEqual(ret, 0)
            self.assertIn("dropped database", out.getvalue())
            self.assertIn('DROP DATABASE IF EXISTS "nexachat_e2e_cli1"', runner.queries[-1]["sql"])

            with patch("sys.stdout", new_callable=io.StringIO), \
                 patch("sys.stderr", new_callable=io.StringIO) as err_out:
                ret = disposable_stack.main(["dropdb", self.run_dir])
            self.assertEqual(ret, 1)
            self.assertIn("disposable_stack: error:", err_out.getvalue())
            self.assertIn("already dropped", err_out.getvalue())

    def test_cli_seed_prints_token_records(self):
        seen_urls = []
        client = FakeSeedClient([
            (201, auth_payload("+84900000001")),
            (201, auth_payload("+84900000003")),
        ])
        runner = FakePsqlRunner()
        with patch.object(disposable_stack, "_ACTIVE_PSQL_RUNNER", runner), \
             patch.object(
                 disposable_stack, "_ACTIVE_CLIENT_FACTORY",
                 lambda url: seen_urls.append(url) or client,
             ):
            with patch("sys.stdout", new_callable=io.StringIO):
                ret = disposable_stack.main(
                    ["provision", self.run_dir, "--db-name", "nexachat_e2e_seed", "--port", "18061"]
                )
            self.assertEqual(ret, 0)

            with patch("sys.stdout", new_callable=io.StringIO) as out:
                ret = disposable_stack.main(
                    [
                        "seed", self.run_dir,
                        "--user", "+84900000001=An",
                        "--user", "+84900000003=Chi",
                    ]
                )
            self.assertEqual(ret, 0)
            records = json.loads(out.getvalue())
            self.assertEqual(
                [r["phone"] for r in records], ["+84900000001", "+84900000003"]
            )
            self.assertEqual(records[0]["user_id"], "uid-+84900000001")
            self.assertEqual(records[0]["access_token"], "tok-+84900000001")
        self.assertEqual(seen_urls, ["http://127.0.0.1:18061"])

    def test_cli_seed_rejects_malformed_user_spec(self):
        with patch.object(disposable_stack, "_ACTIVE_PSQL_RUNNER", FakePsqlRunner()), \
             patch("sys.stderr", new_callable=io.StringIO) as err_out:
            ret = disposable_stack.main(
                ["seed", self.run_dir, "--user", "not-a-phone=An"]
            )
        self.assertEqual(ret, 2)
        self.assertIn("E.164", err_out.getvalue())

    def test_cli_dropdb_refuses_unprovisioned_dir(self):
        with patch.object(disposable_stack, "_ACTIVE_PSQL_RUNNER", FakePsqlRunner()), \
             patch("sys.stderr", new_callable=io.StringIO) as err_out:
            ret = disposable_stack.main(["dropdb", self.run_dir])
        self.assertEqual(ret, 1)
        self.assertIn("nothing owned here", err_out.getvalue())

    def test_cli_provision_rejects_bad_extra_env(self):
        with patch.object(disposable_stack, "_ACTIVE_PSQL_RUNNER", FakePsqlRunner()), \
             patch("sys.stderr", new_callable=io.StringIO) as err_out:
            ret = disposable_stack.main(
                ["provision", self.run_dir, "--extra-env", "bad key=value"]
            )
        self.assertEqual(ret, 2)
        self.assertIn("KEY=VALUE", err_out.getvalue())


if __name__ == "__main__":
    unittest.main()
