#!/usr/bin/env python3
"""Applied Flyway migrations are immutable.

Editing `server/src/main/resources/db/migration/V*.sql` after it has landed
on the base branch leaves every database that already ran it permanently
drifted from the source (new shapes only appear on fresh installs). Renaming,
copying or moving an applied migration is just as bad: databases that ran it
keep a history row under the old name and none under the new one, so the
renamed file would re-run and collide. Fixes must ship as a new forward
migration instead.

Usage (repo root):
    python tools/checks/check-migrations-immutable.py
    python tools/checks/check-migrations-immutable.py --base origin/main
    python tools/checks/check-migrations-immutable.py --self-test

Uses only the Python standard library plus `git` on PATH.
Exits 0 on success, 1 when violations are found, 2 on invocation errors.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path

MIGRATION_DIR = "server/src/main/resources/db/migration"
MIGRATION_NAME = re.compile(r"^V\d+(\.\d+)*__.*\.sql$")


def git(*args: str, cwd: str | Path | None = None) -> str:
    proc = subprocess.run(
        ["git", *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {proc.stderr.strip()}")
    return proc.stdout


def merge_base(base_ref: str, cwd: str | Path | None = None) -> str:
    return git("merge-base", base_ref, "HEAD", cwd=cwd).strip()


def base_migration_paths(root: str, cwd: str | Path | None = None) -> set[str]:
    """Migration file paths that existed at the merge-base commit.

    One `git ls-tree` call instead of per-file `cat-file` probes: if the
    base tree cannot be listed, the git error propagates (exit 2) instead of
    being mistaken for "path not in tree" and silently skipping a violation.
    """
    return set(
        git("ls-tree", "-r", "--name-only", root, "--", MIGRATION_DIR,
            cwd=cwd).splitlines()
    )


def changed_migrations(base_ref: str, cwd: str | Path | None = None) -> list[str]:
    """Migration files present at the merge-base that the range touches."""
    root = merge_base(base_ref, cwd)
    diff = git("diff", "--name-status", f"{root}...HEAD", "--", MIGRATION_DIR,
               cwd=cwd)
    applied = base_migration_paths(root, cwd)
    violations: list[str] = []
    for line in diff.splitlines():
        if not line.strip():
            continue
        status, _, rest = line.partition("\t")
        paths = rest.split("\t")
        if not any(MIGRATION_NAME.match(Path(path).name) for path in paths):
            continue
        if status.startswith("A"):
            continue
        # M / D / R / C / T: every listed path matters, not just the last
        # field. Rename (R) and copy (C) lines list the merge-base path FIRST
        # and the destination second, so an applied file must not move even
        # when only the destination survives in the worktree. Any listed
        # side that existed at the merge-base is a violation.
        touched = [path for path in paths if path in applied]
        if not touched:
            continue
        violations.append(f"{status}\t" + "\t".join(touched))
    return violations


def run_self_test() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        repo = Path(tmp)
        git("init", "-q", "-b", "main", cwd=repo)
        git("config", "user.email", "check@example.test", cwd=repo)
        git("config", "user.name", "check", cwd=repo)
        mig = repo / MIGRATION_DIR
        mig.mkdir(parents=True)
        (mig / "V1__init.sql").write_text("CREATE TABLE t (id int);\n")
        git("add", ".", cwd=repo)
        git("commit", "-qm", "init", cwd=repo)

        # Clean case: a NEW migration must pass.
        git("checkout", "-qb", "add", cwd=repo)
        (mig / "V2__next.sql").write_text("ALTER TABLE t ADD COLUMN ok int;\n")
        git("add", ".", cwd=repo)
        git("commit", "-qm", "add v2", cwd=repo)
        assert changed_migrations("main", repo) == [], "new migration flagged"

        # Dirty case: editing the applied file must fail.
        (mig / "V1__init.sql").write_text("CREATE TABLE t (id bigint);\n")
        git("add", ".", cwd=repo)
        git("commit", "-qm", "edit v1", cwd=repo)
        violations = changed_migrations("main", repo)
        assert violations == ["M\t" + MIGRATION_DIR + "/V1__init.sql"], violations

        # Deleting the applied file must fail too.
        (mig / "V1__init.sql").unlink()
        git("add", ".", cwd=repo)
        git("commit", "-qm", "drop v1", cwd=repo)
        violations = changed_migrations("main", repo)
        assert violations and violations[0].startswith("D\t"), violations

        # Renaming the applied file must fail: the old name existed at the
        # merge-base even though the destination path is new.
        git("checkout", "main", "--", MIGRATION_DIR + "/V1__init.sql", cwd=repo)
        git("add", ".", cwd=repo)
        git("commit", "-qm", "restore v1", cwd=repo)
        git("mv", MIGRATION_DIR + "/V1__init.sql",
            MIGRATION_DIR + "/V1__init_renamed.sql", cwd=repo)
        git("commit", "-qm", "rename v1", cwd=repo)
        violations = changed_migrations("main", repo)
        renamed_old = MIGRATION_DIR + "/V1__init.sql"
        assert violations and any(
            violation.startswith("R") and renamed_old in violation.split("\t")
            for violation in violations
        ), violations
    print("self-test: OK")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="origin/main",
                        help="base ref whose applied migrations are immutable")
    parser.add_argument("--self-test", action="store_true",
                        help="run the internal fixture check and exit")
    args = parser.parse_args()

    if args.self_test:
        return run_self_test()

    try:
        violations = changed_migrations(args.base)
    except RuntimeError as exc:
        print(f"check-migrations-immutable: {exc}", file=sys.stderr)
        return 2

    if violations:
        print("check-migrations-immutable: applied migrations were edited:")
        for line in violations:
            print(f"  {line}")
        print("Ship the change as a new forward migration instead; applied")
        print("migration files are immutable once merged.")
        return 1
    print("check-migrations-immutable: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
