# -*- coding: utf-8 -*-
"""Migration orchestration: apply db/migration/*.sql in order, prove the result.

Why this exists: Flyway is not enabled in this project, so landing schema
changes into a database was a manual chore (copy files, watch for gaps).
This component replaces that chore with three deterministic actions:

- apply   run pending migration files in strict numeric version order with
          ON_ERROR_STOP, recording each file in tp_migration_ledger so a
          re-run is a no-op (idempotent, resumable mid-way);
- mark-baseline  record the current file set as applied WITHOUT executing
          it, for databases already built by db/schema-init.sql (a fresh
          container does that on first boot); afterwards `apply` only
          picks up files added since the baseline mark;
- verify  derive the expected public-table set from CREATE/DROP TABLE
          statements across the migration files (final-state fold) and
          check each one against information_schema.tables, naming every
          missing object instead of passing silently.

Safety: the tool refuses to write to the factory main database or the
postgres maintenance database unless the operator passes --allow-main
(explicit override only). Chain-slot isolation rules stay intact.

Database access goes through the project's standard psql channel
(`docker exec testpike-postgres psql ...`), credentials never inlined —
the target database name comes from DB_URL in the environment.
"""
from __future__ import annotations

import argparse
import instance_names
import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MIGRATIONS_DIR = REPO_ROOT / "db" / "migration"

# Databases that must never be written by this tool without an explicit
# operator override (factory main lane + server maintenance database).
PROTECTED_DATABASES = {"testpike", "testpike_prod", "postgres"}

LEDGER_TABLE = "tp_migration_ledger"

_VNUM_RE = re.compile(r"^V(\d+)__")
_CREATE_TABLE_RE = re.compile(
    r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?"
    r"(?:[\w\".]+\.)?\"?([a-zA-Z_][\w]*)\"?", re.IGNORECASE)
_DROP_TABLE_RE = re.compile(
    r"DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?(?:[\w\".]+\.)?\"?([a-zA-Z_][\w]*)\"?",
    re.IGNORECASE)


class TargetDatabaseRefused(RuntimeError):
    """Raised when the target database is protected and no override was given."""


def _psql_cmd(db: str, extra: list[str]) -> list[str]:
    prefix = os.environ.get("INSTALLKIT_DOCKER_EXEC_PREFIX", "docker exec")
    return (prefix.split() + [instance_names.name("postgres"),
                              "psql", "-U", os.environ.get("DB_USER", "testpike"),
                              "-d", db, "-v", "ON_ERROR_STOP=1"] + extra)


def _run_sql(db: str, sql: str) -> str:
    res = subprocess.run(_psql_cmd(db, ["-tAc", sql]),
                         capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(
            f"psql on database '{db}' failed: {res.stderr.strip()[:500]}")
    return res.stdout.strip()


def _run_file(db: str, content: str) -> None:
    cmd = (["docker", "exec", "-i", instance_names.name("postgres"), "psql",
            "-U", os.environ.get("DB_USER", "testpike"), "-d", db,
            "-v", "ON_ERROR_STOP=1", "-f", "-"])
    res = subprocess.run(cmd, capture_output=True, text=True, input=content)
    if res.returncode != 0:
        raise RuntimeError(
            f"migration file failed on database '{db}': "
            f"{res.stderr.strip()[:500]}")


def vnum(filename: str) -> int:
    m = _VNUM_RE.match(filename)
    if not m:
        raise ValueError(f"not a versioned migration file: {filename}")
    return int(m.group(1))


def list_migrations(migrations_dir: Path) -> list[Path]:
    """All V<digits>__*.sql files sorted by numeric version order."""
    files = [p for p in Path(migrations_dir).glob("V*__*.sql")
             if _VNUM_RE.match(p.name)]
    return sorted(files, key=lambda p: vnum(p.name))


def db_from_url(db_url: str | None = None) -> str:
    """Extract the database name from a JDBC URL (env DB_URL by default)."""
    url = db_url or os.environ.get("DB_URL", "")
    tail = url.rsplit("/", 1)[-1]
    name = tail.split("?")[0]
    if not name:
        raise RuntimeError("DB_URL is empty — cannot derive the database name")
    return name


def check_target(db: str, allow_main: bool = False) -> None:
    if db in PROTECTED_DATABASES and not allow_main:
        raise TargetDatabaseRefused(
            f"database '{db}' is protected (factory main lane or maintenance); "
            "pass --allow-main only when you really intend to write it")


def _ensure_ledger(db: str) -> None:
    _run_sql(db, (
        f"CREATE TABLE IF NOT EXISTS {LEDGER_TABLE} ("
        "version BIGINT PRIMARY KEY,"
        "filename TEXT NOT NULL UNIQUE,"
        "applied_at TIMESTAMPTZ NOT NULL DEFAULT now())"))


def _applied_names(db: str) -> set[str]:
    rows = _run_sql(db, f"SELECT filename FROM {LEDGER_TABLE}")
    return {r for r in rows.splitlines() if r}


def apply(db: str, migrations_dir: Path, allow_main: bool = False) -> dict:
    """Run pending migrations in version order; return applied/skipped lists."""
    check_target(db, allow_main=allow_main)
    _ensure_ledger(db)
    applied_names = _applied_names(db)
    applied, skipped = [], []
    for path in list_migrations(Path(migrations_dir)):
        if path.name in applied_names:
            skipped.append(path)
            continue
        _run_file(db, path.read_text(encoding="utf-8"))
        _run_sql(db, (
            f"INSERT INTO {LEDGER_TABLE} (version, filename) "
            f"VALUES ({vnum(path.name)}, '{path.name}') "
            "ON CONFLICT (filename) DO NOTHING"))
        applied.append(path)
        applied_names.add(path.name)
    return {"applied": applied, "skipped": skipped}


def mark_baseline(db: str, migrations_dir: Path,
                  allow_main: bool = False) -> int:
    """Record the current file set as applied without executing anything."""
    check_target(db, allow_main=allow_main)
    _ensure_ledger(db)
    count = 0
    for path in list_migrations(Path(migrations_dir)):
        _run_sql(db, (
            f"INSERT INTO {LEDGER_TABLE} (version, filename) "
            f"VALUES ({vnum(path.name)}, '{path.name}') "
            "ON CONFLICT (filename) DO NOTHING"))
        count += 1
    return count


def expected_tables(migrations_dirs) -> dict[str, int]:
    """Fold CREATE/DROP TABLE across files in order; table -> last version seen."""
    live: dict[str, int] = {}
    for mdir in migrations_dirs:
        for path in list_migrations(Path(mdir)):
            version = vnum(path.name)
            text = path.read_text(encoding="utf-8", errors="replace")
            for m in _DROP_TABLE_RE.finditer(text):
                live.pop(m.group(1).lower(), None)
            for m in _CREATE_TABLE_RE.finditer(text):
                live[m.group(1).lower()] = version
    return live


def actual_tables(db: str) -> set[str]:
    rows = _run_sql(
        db,
        "SELECT table_name FROM information_schema.tables "
        "WHERE table_schema='public' AND table_type='BASE TABLE'")
    return {r for r in rows.splitlines() if r}


def verify(db: str, migrations_dirs, allow_main: bool = False) -> dict:
    """Check every table the migration set expects against information_schema.

    Returns {"checked": n, "missing": [names...]} — missing objects are
    named one by one, never summarised away.
    """
    check_target(db, allow_main=allow_main)
    expected = expected_tables(migrations_dirs)
    have = actual_tables(db)
    missing = sorted(t for t in expected if t not in have)
    return {"checked": len(expected), "missing": missing}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Apply/verify database migrations in version order")
    parser.add_argument("action", choices=["apply", "verify", "mark-baseline"])
    parser.add_argument("--db", default=None,
                        help="target database (default: parsed from DB_URL)")
    parser.add_argument("--dir", default=str(DEFAULT_MIGRATIONS_DIR),
                        help="migration files directory")
    parser.add_argument("--allow-main", action="store_true",
                        help="explicit override to write a protected database")
    args = parser.parse_args(argv)
    db = args.db or db_from_url()
    try:
        if args.action == "apply":
            result = apply(db, Path(args.dir), allow_main=args.allow_main)
            for path in result["applied"]:
                print(f"applied: {path.name}")
            print(f"done: applied={len(result['applied'])} "
                  f"skipped={len(result['skipped'])} database={db}")
        elif args.action == "mark-baseline":
            n = mark_baseline(db, Path(args.dir), allow_main=args.allow_main)
            print(f"marked {n} files as baseline on database={db}")
        else:
            report = verify(db, [Path(args.dir)], allow_main=args.allow_main)
            if report["missing"]:
                print(f"MISSING {len(report['missing'])} table(s) "
                      f"out of {report['checked']} expected on {db}:")
                for t in report["missing"]:
                    print(f"  - {t}")
                return 1
            print(f"ok: all {report['checked']} expected tables present "
                  f"on database={db}")
    except TargetDatabaseRefused as refused:
        print(f"refused: {refused}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
