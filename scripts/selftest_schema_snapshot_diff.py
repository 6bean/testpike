#!/usr/bin/env python3
"""Schema snapshot diff gate self-test (AU batch merge-back door).

Proves the four behaviours the merge-back gate relies on, against real
databases (no mocks):
  1. two identical-shape databases diff clean (rc 0);
  2. a manual DDL inside one database is named as drift (rc 1) — this is
     the "no manual DDL in a slot" detection the merge-back door needs;
  3. the replay ledger reports a freshly-cloned full schema as applied for
     recent migrations (rc 0);
  4. destroyed probe databases leave zero residue.

Run:  python3 scripts/selftest_schema_snapshot_diff.py
Exit: 0 = all assertions held.
"""
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import chain_slot  # noqa: E402  (shared slot factory — single source, R40)
import schema_snapshot_diff as ssd  # noqa: E402
import shutil as _sh  # fixture temp dirs (3b/3c)
import tempfile as _tf  # fixture temp dirs (3b/3c)

PROBE_A = "tp_probe_ssd_a"
PROBE_B = "tp_probe_ssd_b"
PROBE_EMPTY = "tp_probe_ssd_empty"
PROBE_C = "tp_probe_ssd_cons"


def fail(msg: str) -> None:
    print(f"SELFTEST FAIL: {msg}")
    sys.exit(1)


def make_probe(db: str) -> None:
    if chain_slot._db_exists(db):
        chain_slot._psql(chain_slot.MAIN_DB, f"DROP DATABASE {db}")
    chain_slot._ensure_template()
    r = chain_slot._psql(chain_slot.MAIN_DB,
                         f"CREATE DATABASE {db} TEMPLATE {chain_slot.TEMPLATE_DB}")
    if r.returncode != 0:
        fail(f"cannot create probe {db}: {r.stderr[-300:]}")


def main() -> int:
    make_probe(PROBE_A)
    make_probe(PROBE_B)

    # 1. identical shapes -> clean
    rc, lines = ssd.diff_databases(PROBE_A, PROBE_B)
    if rc != 0:
        fail(f"identical probes reported drift: {lines[:3]}")
    print("OK 1: identical-shape pair diffs clean")

    # 2. manual DDL in probe B -> named drift
    r = chain_slot._psql(PROBE_B, "ALTER TABLE tp_project ADD COLUMN ssd_drift_marker int")
    if r.returncode != 0:
        fail(f"cannot inject drift: {r.stderr[-300:]}")
    rc, lines = ssd.diff_databases(PROBE_A, PROBE_B)
    if rc != 1:
        fail(f"manual DDL not flagged (rc={rc})")
    if not any("tp_project" in ln and "ssd_drift_marker" in ln for ln in lines):
        fail(f"drift not named with table+column: {lines[:5]}")
    print("OK 2: manual DDL inside a database is named (table+column)")

    # 3. replay ledger against a full-shape probe: recent migrations applied
    applied, unapplied, skipped = ssd.replay_ledger(PROBE_A, since=300)
    if unapplied:
        fail(f"full-shape probe reports unapplied recent migrations: {unapplied[:5]}")
    if applied <= 0:
        fail("replay ledger found zero applied probes (silent no-op branch)")
    print(f"OK 3: replay ledger rc clean (applied={applied} skipped={skipped})")

    # 3b. superseded column markers never demanded (11-05 live red: V119 added
    # tp_apitest_request.vars_json, V120 dropped it on purpose; the ledger still
    # flagged the freshly rebuilt slot UNAPPLIED forever). Two-way: a full-shape
    # clone MUST pass since=0, and a synthetic drop/revive fixture proves the
    # exemption never swallows live or re-added columns.
    applied0, unapplied0, _ = ssd.replay_ledger(PROBE_A, since=0)
    if unapplied0:
        fail(f"full-shape clone reports unapplied (superseded-marker regression): {unapplied0[:5]}")
    import tempfile as _tf
    d = Path(_tf.mkdtemp(prefix="ssd-cols-fx-"))
    (d / "V119__a.sql").write_text("ALTER TABLE tp_demo ADD COLUMN IF NOT EXISTS pool jsonb;")
    (d / "V120__b.sql").write_text(
        "ALTER TABLE tp_demo DROP COLUMN IF EXISTS pool;\n"
        "ALTER TABLE tp_demo2 ADD COLUMN IF NOT EXISTS pool jsonb;\n")
    (d / "V121__c.sql").write_text("ALTER TABLE tp_demo ADD COLUMN IF NOT EXISTS live_col jsonb;\n"
                                   "ALTER TABLE tp_demo2 DROP COLUMN IF EXISTS pool;\n")
    (d / "V122__d.sql").write_text("ALTER TABLE tp_demo2 ADD COLUMN IF NOT EXISTS pool jsonb;")
    fx_files = sorted(d.glob("V*__*.sql"))
    dc = ssd._dropped_columns(fx_files)
    if ("tp_demo", "pool") not in dc:
        fail("superseded column was not exempted (fixture drop not seen)")
    if ("tp_demo", "live_col") in dc or ("tp_demo2", "pool") in dc:
        fail(f"exemption swallowed a live/re-added column: {sorted(dc)}")
    import shutil as _sh
    _sh.rmtree(d, ignore_errors=True)
    print("OK 3b: superseded column markers exempt, live and re-added columns still demanded")

    # 3c. same-name CHECK re-definition must be named, never folded into a
    # clean ledger (11-11 live miss: V485 re-created ck_appauto_device_type
    # with a wider value range while the database still held the old
    # predicate; name-existence called it applied). Two-way fixture:
    # planted mismatch MUST be caught, fixed shape MUST go clean, and a
    # later explicit DROP exempts the demand instead of resurrecting it.
    d3 = Path(_tf.mkdtemp(prefix="ssd-cons-fx-"))
    (d3 / "V400__base.sql").write_text(
        "CREATE TABLE tp_ssd_demo (device_type text);\n"
        "ALTER TABLE tp_ssd_demo ADD CONSTRAINT ck_demo_type "
        "CHECK (device_type IN ('A', 'B'));\n")
    (d3 / "V401__grow.sql").write_text(
        "ALTER TABLE tp_ssd_demo DROP CONSTRAINT IF EXISTS ck_demo_type;\n"
        "ALTER TABLE tp_ssd_demo ADD CONSTRAINT ck_demo_type "
        "CHECK (device_type IN ('A', 'B', 'C'));\n")
    chain_slot._psql(chain_slot.MAIN_DB, f"DROP DATABASE IF EXISTS {PROBE_C}")
    cr = chain_slot._psql(chain_slot.MAIN_DB, f"CREATE DATABASE {PROBE_C}")
    if cr.returncode != 0:
        fail(f"cannot create the constraint probe: {cr.stderr[-200:]}")
    old_dir, ssd.MIGRATION_DIR = ssd.MIGRATION_DIR, d3
    try:
        # database frozen at the OLD predicate (V400 applied, V401 not)
        chain_slot._psql(PROBE_C, "CREATE TABLE tp_ssd_demo (device_type text)")
        chain_slot._psql(PROBE_C, "ALTER TABLE tp_ssd_demo ADD CONSTRAINT ck_demo_type "
                                  "CHECK (device_type IN ('A', 'B'))")
        _, un3, _ = ssd.replay_ledger(PROBE_C, since=0)
        if not any("ck_demo_type" in u and "redefinition" in u for u in un3):
            fail(f"same-name CHECK re-definition folded into a clean ledger: {un3}")
        # applying the wider predicate -> clean
        chain_slot._psql(PROBE_C, "ALTER TABLE tp_ssd_demo DROP CONSTRAINT IF EXISTS ck_demo_type")
        chain_slot._psql(PROBE_C, "ALTER TABLE tp_ssd_demo ADD CONSTRAINT ck_demo_type "
                                  "CHECK (device_type IN ('A', 'B', 'C'))")
        a3, un3b, _ = ssd.replay_ledger(PROBE_C, since=0)
        if un3b:
            fail(f"fixed CHECK shape still reported unapplied: {un3b}")
        if a3 <= 0:
            fail("constraint ledger applied-count zero (silent no-op branch)")
        # a later explicit DROP exempts instead of demanding
        (d3 / "V402__drop.sql").write_text(
            "ALTER TABLE tp_ssd_demo DROP CONSTRAINT IF EXISTS ck_demo_type;\n")
        chain_slot._psql(PROBE_C, "ALTER TABLE tp_ssd_demo DROP CONSTRAINT IF EXISTS ck_demo_type")
        _, un3c, _ = ssd.replay_ledger(PROBE_C, since=0)
        if any("ck_demo_type" in u for u in un3c):
            fail(f"dropped constraint still demanded (exemption broken): {un3c}")
    finally:
        ssd.MIGRATION_DIR = old_dir
        _sh.rmtree(d3, ignore_errors=True)
        chain_slot._psql(chain_slot.MAIN_DB, f"DROP DATABASE {PROBE_C}")
    print("OK 3c: CHECK re-definition detected by definition compare; fixed shape "
          "clean; later drop exempt")

    # 3d. data-only roster (r44 blind spot: a pure-UPDATE migration introduces
    # no object to probe, so the ledger counted it under `skipped` and a slot
    # that had never replayed it still printed a clean summary). Two-way
    # fixture: a backfill-only file MUST be named, an object-bearing file MUST
    # NOT be, the `since` filter MUST apply to the roster too, and the roster
    # length MUST equal the skipped count the ledger reports for the same set.
    d4 = Path(_tf.mkdtemp(prefix="ssd-dataonly-fx-"))
    (d4 / "V500__obj.sql").write_text("CREATE TABLE tp_ssd_demo2 (id bigint);")
    (d4 / "V501__backfill.sql").write_text(
        "UPDATE tp_ssd_demo2 SET id = id WHERE id > 0;\n")
    old_dir4, ssd.MIGRATION_DIR = ssd.MIGRATION_DIR, d4
    try:
        roster = ssd.data_only_migrations(since=0)
        if roster != ["V501__backfill.sql"]:
            fail(f"data-only roster wrong (got {roster})")
        if ssd.data_only_migrations(since=502):
            fail("since filter did not drop the data-only file from the roster")
        _, unapplied4, skipped4 = ssd.replay_ledger(PROBE_A, since=0)
        if skipped4 != len(roster):
            fail(f"roster and skipped count disagree (roster={roster} skipped={skipped4})")
        if not any("V500__obj.sql" in u for u in unapplied4):
            fail(f"object-bearing fixture file was not demanded: {unapplied4}")
    finally:
        ssd.MIGRATION_DIR = old_dir4
        _sh.rmtree(d4, ignore_errors=True)
    print("OK 3d: data-only migration named, object-bearing file exempt, "
          "since filter honoured, roster == skipped count")

    # 4. CLI surface returns the documented exit codes
    r = subprocess.run([sys.executable, str(HERE / "schema_snapshot_diff.py"),
                        "diff", PROBE_A, PROBE_B], capture_output=True, text=True)
    if r.returncode != 1:
        fail(f"CLI drift exit code != 1 (got {r.returncode})")
    r = subprocess.run([sys.executable, str(HERE / "schema_snapshot_diff.py"),
                        "diff", PROBE_A, PROBE_A], capture_output=True, text=True)
    if r.returncode != 0:
        fail(f"CLI clean diff exit code != 0 (got {r.returncode})")
    print("OK 4: CLI exit codes match the gate contract")

    # 4b. an empty --db handle must fail closed (11-09 live finding: the ledger
    # quietly fell back to the shipped default database, so a chain that forgot
    # to pass its slot db read MAIN-slot shapes and reported them as its own —
    # R48 clause 4 class: an undefined handle is worse than a literal).
    r = subprocess.run([sys.executable, str(HERE / "schema_snapshot_diff.py"),
                        "replay-ledger", "--db", ""], capture_output=True, text=True)
    if r.returncode == 0 or "--db" not in (r.stderr + r.stdout):
        fail(f"empty --db fell through instead of failing closed "
             f"(rc={r.returncode}, out={(r.stdout + r.stderr)[-200:]})")
    if f"db={chain_slot.MAIN_DB}" in r.stdout:
        fail(f"empty --db still queried the shipped default database {chain_slot.MAIN_DB}")
    r = subprocess.run([sys.executable, str(HERE / "schema_snapshot_diff.py"),
                        "snapshot", " "], capture_output=True, text=True)
    if r.returncode == 0 or "database" not in (r.stderr + r.stdout).lower():
        fail(f"blank snapshot target fell through (rc={r.returncode})")
    print("OK 4b: blank database handles fail closed on every subcommand")

    # 4c. the ledger names the single apply channel when it reports unapplied
    # migrations — pointing at a file without the way to apply it left operators
    # hand-running DDL in the slot, which is what check-slot exists to catch.
    chain_slot._psql(chain_slot.MAIN_DB, f"DROP DATABASE IF EXISTS {PROBE_EMPTY}")
    cr = chain_slot._psql(chain_slot.MAIN_DB, f"CREATE DATABASE {PROBE_EMPTY}")
    if cr.returncode != 0:
        fail(f"cannot create the empty probe: {cr.stderr[-200:]}")
    try:
        r = subprocess.run([sys.executable, str(HERE / "schema_snapshot_diff.py"),
                            "replay-ledger", "--db", PROBE_EMPTY],
                           capture_output=True, text=True)
        if r.returncode != 1:
            fail(f"empty database should report unapplied migrations (rc={r.returncode})")
        if "apply_slot_migration.sh" not in r.stdout:
            fail("ledger names unapplied files without the single apply channel")
    finally:
        chain_slot._psql(chain_slot.MAIN_DB, f"DROP DATABASE {PROBE_EMPTY}")
    if "apply_slot_migration.sh" not in (ssd.__doc__ or ""):
        fail("module usage text does not name the apply channel")
    print("OK 4c: unapplied ledger points at the single apply channel")

    # 4d. the data-only roster must reach the operator through the CLI, and
    # must be able to stay quiet: the positive side is the live migration tree
    # (roster non-empty by construction here), the negative side a --since
    # beyond every file. A branch that can only ever fire, or never fire, is a
    # silent no-op rather than a signal.
    expect_warn = bool(ssd.data_only_migrations(since=0))
    r = subprocess.run([sys.executable, str(HERE / "schema_snapshot_diff.py"),
                        "replay-ledger", "--db", PROBE_A],
                       capture_output=True, text=True)
    if ("WARN data-only" in r.stdout) != expect_warn:
        fail(f"data-only WARN disagrees with the roster "
             f"(expect={expect_warn}, out={r.stdout[-300:]})")
    if "data_only=" not in r.stdout:
        fail("ledger summary does not carry the data-only count")
    r = subprocess.run([sys.executable, str(HERE / "schema_snapshot_diff.py"),
                        "replay-ledger", "--db", PROBE_A, "--since", "V9999999999"],
                       capture_output=True, text=True)
    if r.returncode != 0 or "WARN data-only" in r.stdout:
        fail(f"an empty file window still warned (rc={r.returncode}, "
             f"out={r.stdout[-200:]})")
    print("OK 4d: data-only WARN fires on the live roster, quiet on an empty window")

    # teardown: drop probes and prove zero residue
    for db in (PROBE_A, PROBE_B, PROBE_EMPTY):
        chain_slot._psql(chain_slot.MAIN_DB, f"DROP DATABASE {db}")
        if chain_slot._db_exists(db):
            fail(f"probe {db} survived teardown")
    print("OK 5: probes destroyed, zero residue")
    print("SELFTEST PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
