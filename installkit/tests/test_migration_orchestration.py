# -*- coding: utf-8 -*-
"""Contract tests for the migration orchestration component (T293a MP1).

What it pins (user-facing capability, plain wording):
- `apply` runs db/migration/*.sql in strict numeric version order and stops
  on the first failing statement (ON_ERROR_STOP), recording each file in a
  ledger table so a re-run applies nothing (idempotent);
- `mark-baseline` records the current file set as applied without executing
  (for databases already built by schema-init), so a later `apply` only
  picks up files added after that point;
- `verify` derives expected tables from the CREATE TABLE statements of the
  applied files and checks each one against information_schema.tables,
  naming every missing object (no silent pass);
- the component refuses to touch the factory main database unless the
  operator passes an explicit override flag (chain-slot isolation).

These are real-database tests: they create and drop their own scratch
database inside this chain's slot namespace (testpike_slot4_*), never the
main slot. No mocks against the request path.
"""
import subprocess
import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "installkit"))

import migrate_apply  # noqa: E402

SCRATCH_DB = "testpike_slot4_orch_test"


def _exec_sql(db: str, sql: str) -> str:
    res = subprocess.run(
        ["docker", "exec", "testpike-postgres",
         "psql", "-U", "testpike", "-d", db, "-v", "ON_ERROR_STOP=1", "-tAc", sql],
        capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"psql failed on {db}: {res.stderr.strip()[:400]}")
    return res.stdout.strip()


@pytest.fixture(scope="module")
def scratch_db():
    _exec_sql("postgres", f'DROP DATABASE IF EXISTS {SCRATCH_DB}')
    _exec_sql("postgres", f'CREATE DATABASE {SCRATCH_DB}')
    yield SCRATCH_DB
    _exec_sql("postgres", f'DROP DATABASE IF EXISTS {SCRATCH_DB}')


@pytest.fixture(autouse=True)
def clean_scratch(scratch_db):
    """Each test starts from an empty scratch database (no shared ledger state)."""
    rows = _exec_sql(
        scratch_db,
        "SELECT tablename FROM pg_tables WHERE schemaname='public'")
    for table in rows.split():
        _exec_sql(scratch_db, f'DROP TABLE IF EXISTS "{table}" CASCADE')


@pytest.fixture()
def mini_migrations(tmp_path):
    mdir = tmp_path / "migration"
    mdir.mkdir()
    (mdir / "V1__alpha.sql").write_text(
        "CREATE TABLE tp_orch_alpha (id BIGINT PRIMARY KEY);\n", encoding="utf-8")
    # V2 references V1's table: any out-of-order execution fails loudly.
    (mdir / "V2__beta.sql").write_text(
        "CREATE TABLE tp_orch_beta (id BIGINT);\n"
        "INSERT INTO tp_orch_alpha (id) VALUES (1);\n", encoding="utf-8")
    (mdir / "V3__gamma.sql").write_text(
        "CREATE TABLE IF NOT EXISTS tp_orch_gamma (id BIGINT);\n", encoding="utf-8")
    return mdir


def test_apply_runs_in_version_order(scratch_db, mini_migrations):
    result = migrate_apply.apply(scratch_db, mini_migrations)
    assert [p.name for p in result["applied"]] == [
        "V1__alpha.sql", "V2__beta.sql", "V3__gamma.sql"]
    rows = _exec_sql(scratch_db, "SELECT count(*) FROM tp_orch_alpha")
    assert rows == "1"


def test_apply_is_idempotent(scratch_db, mini_migrations):
    migrate_apply.apply(scratch_db, mini_migrations)
    second = migrate_apply.apply(scratch_db, mini_migrations)
    assert second["applied"] == []
    # Ledger keeps one row per file, no duplicates on re-run.
    ledger = _exec_sql(
        scratch_db, "SELECT count(*) FROM tp_migration_ledger")
    files = len(list(mini_migrations.glob("V*.sql")))
    assert ledger == str(files)


def test_apply_picks_up_only_new_files(scratch_db, mini_migrations):
    migrate_apply.apply(scratch_db, mini_migrations)
    (mini_migrations / "V4__delta.sql").write_text(
        "CREATE TABLE tp_orch_delta (id BIGINT);\n", encoding="utf-8")
    result = migrate_apply.apply(scratch_db, mini_migrations)
    assert [p.name for p in result["applied"]] == ["V4__delta.sql"]


def test_verify_names_missing_tables(scratch_db, tmp_path):
    mdir = tmp_path / "mig2"
    mdir.mkdir()
    (mdir / "V1__one.sql").write_text(
        "CREATE TABLE tp_orch_v2_one (id BIGINT);\n", encoding="utf-8")
    (mdir / "V2__two.sql").write_text(
        "CREATE TABLE tp_orch_v2_two (id BIGINT);\n", encoding="utf-8")
    # Apply V1 only by marking: simulate a half-migrated database.
    _exec_sql(scratch_db, "DROP TABLE IF EXISTS tp_orch_v2_one; DROP TABLE IF EXISTS tp_orch_v2_two;")
    _exec_sql(scratch_db, "CREATE TABLE tp_orch_v2_one (id BIGINT);")
    report = migrate_apply.verify(scratch_db, [mdir])
    assert report["checked"] >= 2
    assert "tp_orch_v2_two" in report["missing"]
    assert "tp_orch_v2_one" not in report["missing"]


def test_mark_baseline_then_increment(scratch_db, mini_migrations):
    # A database built out-of-band (schema-init shape): mark baseline first,
    # then a new file must be the only pending item.
    migrate_apply.mark_baseline(scratch_db, mini_migrations)
    ledger_count = _exec_sql(
        scratch_db, "SELECT count(*) FROM tp_migration_ledger")
    assert ledger_count == "3"
    (mini_migrations / "V5__echo.sql").write_text(
        "CREATE TABLE tp_orch_echo (id BIGINT);\n", encoding="utf-8")
    result = migrate_apply.apply(scratch_db, mini_migrations)
    assert [p.name for p in result["applied"]] == ["V5__echo.sql"]


def test_refuses_main_slot_database(scratch_db):
    # Chain-slot isolation: the factory database name is off-limits without
    # the explicit operator override.
    for forbidden in ("testpike", "postgres"):
        with pytest.raises(migrate_apply.TargetDatabaseRefused):
            migrate_apply.check_target(forbidden)
    # The override flag path must exist and be explicit.
    migrate_apply.check_target("testpike", allow_main=True)


# --- merge-back door: non-product tooling ledger stays out of drift  ---

def _load_schema_diff():
    import importlib.util
    path = REPO_ROOT / "scripts" / "schema_snapshot_diff.py"
    spec = importlib.util.spec_from_file_location("schema_snapshot_diff", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# Line shapes as produced by snapshot(): exactly what a slot database that
# ran the migration runner once carries while the factory probe never does.
_LEDGER_LINES = [
    "T|tp_migration_ledger|version|bigint|true|",
    "T|tp_migration_ledger|filename|text|true|",
    "T|tp_migration_ledger|applied_at|timestamptz|true|now()",
    "I|tp_migration_ledger|tp_migration_ledger_pkey|"
    "CREATE UNIQUE INDEX tp_migration_ledger_pkey ON ... USING btree (version)",
    "C|tp_migration_ledger|tp_migration_ledger_pkey|p|PRIMARY KEY (version)",
]


def test_tooling_ledger_lines_excluded_from_drift():
    """Exclusion must HIT the real snapshot line shapes (R43: a branch that
    never triggers is a silent no-op, not a fix)."""
    module = _load_schema_diff()
    assert "tp_migration_ledger" in module.NON_PRODUCT_TABLES
    for line in _LEDGER_LINES:
        assert module._is_non_product(line), line


def test_product_shape_lines_never_excluded():
    """Planted counter-examples: an unknown extra table or a same-prefix
    product table must STILL surface as drift (no blanket filtering)."""
    module = _load_schema_diff()
    planted = [
        "T|tp_cluster_scale_event|id|bigint|true|",
        "T|tp_migration_ledger_shadow|note|text|false|",
        "I|tp_case|idx_case_project|CREATE INDEX ...",
    ]
    for line in planted:
        assert not module._is_non_product(line), line
