#!/usr/bin/env python3
"""Schema snapshot diff — the AU-batch merge-back door .

Why this exists: parallel chains now develop on their own chain slots
(scripts/chain_slot.py). The merge-back gate needs one mechanical proof
beyond the R42 three greens: the slot's schema must be reproducible from
committed sources only. A hand-run DDL inside a slot that never became a
migration file is exactly the drift this tool names — "no manual DDL in a
slot" is enforced here, not by convention.

Subcommands:
 snapshot <db> print the normalized schema shape
 diff <dbA> <dbB> name every shape drift; rc 1 = drift
 check-slot <N> rebuild a probe from the factory template
 (+ optional --migrations DIR, where DIR holds
 only the migrations added after the factory
 snapshot was baked — replaying the whole
 db/migration tree onto the template re-runs
 ADD statements for objects that are already
 baked in and reports a false drift), diff the
 slot against it, drop the probe. Run this
 BEFORE merging a chain branch back.
 replay-ledger [--db D] [--since V]
 which migrations' objects exist in the db
 (post-merge-back slot0 quick regression:
 tp_schema_version is vacuous, the V-number
 object probe stands in for it, ruling §8).
 This subcommand only reads: to close an
 UNAPPLIED row, apply that file through the
 single apply channel
 tools/ops/apply_slot_migration.sh <file>
 [--db D] and re-run this ledger — never
 hand-run DDL inside a slot (check-slot is
 what detects that).

Every database argument is required to name a real database: a blank value
fails closed instead of reaching psql, where an empty name resolves to the
connection user's own database and would report another environment's shape
as the caller's target.

Shape snapshot = public-schema tables/columns (+notnull/default), indexes
(name+definition), table constraints (name+definition). Data is never
compared. Probe objects recognised in migration files: CREATE TABLE names,
CREATE INDEX names, ALTER TABLE ... ADD COLUMN targets.

Behaviour self-test: python3 scripts/selftest_schema_snapshot_diff.py
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import chain_slot  # noqa: E402  slot factory helpers — single source (R40)
from db_target import require_db  # blank-handle guard — single source (R40)

ROOT = chain_slot.ROOT
MIGRATION_DIR = ROOT / "db" / "migration"

COLS_SQL = (
    "SELECT 'T|'||c.relname||'|'||a.attname||'|'||format_type(a.atttypid,a.atttypmod)"
    "||'|'||a.attnotnull||'|'||COALESCE(pg_get_expr(d.adbin,d.adrelid),'') "
    "FROM pg_attribute a "
    "JOIN pg_class c ON c.oid=a.attrelid AND c.relkind IN ('r','p') "
    "JOIN pg_namespace n ON n.oid=c.relnamespace "
    "AND n.nspname NOT IN ('pg_catalog','information_schema') "
    "LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum "
    "WHERE a.attnum>0 AND NOT a.attisdropped ORDER BY 1")
IDX_SQL = ("SELECT 'I|'||tablename||'|'||indexname||'|'||indexdef FROM pg_indexes "
           "WHERE schemaname NOT IN ('pg_catalog','information_schema') ORDER BY 1")
CON_SQL = ("SELECT 'C|'||rel.relname||'|'||con.conname||'|'||con.contype::text||'|'"
           "||pg_get_constraintdef(con.oid) FROM pg_constraint con "
           "JOIN pg_class rel ON rel.oid=con.conrelid "
           "JOIN pg_namespace n ON n.oid=rel.relnamespace "
           "WHERE n.nspname NOT IN ('pg_catalog','information_schema') ORDER BY 1")


def snapshot(db: str) -> list[str]:
    """Normalized schema shape lines for one database (sorted)."""
    lines: list[str] = []
    for sql in (COLS_SQL, IDX_SQL, CON_SQL):
        r = chain_slot._psql(db, sql)
        if r.returncode != 0:
            raise RuntimeError(f"snapshot of {db} failed: {r.stderr[-300:]}")
        lines.extend(ln for ln in r.stdout.splitlines() if ln.strip())
    return sorted(lines)


# Tooling bookkeeping tables that live only in operational databases.
# The installkit migration runner (installkit/migrate_apply.py) creates
# tp_migration_ledger on any database it applies migrations to, so every
# slot that ever ran a verification window carries rows the factory probe
# never has. That is operator history, not product schema: without this
# exclusion the merge-back door reports permanent "+ only-in-second" drift
# with no schema change behind it. Names here must stay in sync with the
# LEDGER_TABLE constant in installkit/migrate_apply.py (single source of
# the shape, second consumer of the name — R40 note).
NON_PRODUCT_TABLES = frozenset({"tp_migration_ledger"})


def _is_non_product(line: str) -> bool:
    """True when a snapshot line describes an object owned by a tooling table.

    Snapshot line shapes (see COLS_SQL / IDX_SQL / CON_SQL above): the second
    field is always the owning table name for all three kinds (T|table|column,
    I|table|index, C|table|constraint), so one split covers every projection.
    """
    parts = line.split("|", 2)
    return len(parts) > 1 and parts[1] in NON_PRODUCT_TABLES


def diff_databases(db_a: str, db_b: str) -> tuple[int, list[str]]:
    """Compare two databases. Returns (rc, drift lines): rc 1 = drift named
    with '- only-in-first' / '+ only-in-second' prefixes, rc 0 = identical.
    Tooling bookkeeping is filtered out of both sides (see NON_PRODUCT_TABLES
    above) so it can neither fake drift nor mask a product difference."""
    a = {ln for ln in snapshot(db_a) if not _is_non_product(ln)}
    b = {ln for ln in snapshot(db_b) if not _is_non_product(ln)}
    drift = [f"- {ln}" for ln in sorted(a - b)] + [f"+ {ln}" for ln in sorted(b - a)]
    return (1, drift) if drift else (0, [])


def _replay_probe(db: str, migrations: list[Path]) -> None:
    """Apply migration files into a probe database in V-number order."""
    for path in sorted(migrations, key=lambda p: _vnum(p.name)):
        chain_slot._load_sql(db, path)


def _vnum(name: str) -> int:
    m = re.match(r"V(\d+)__", name)
    return int(m.group(1)) if m else 10 ** 9


CREATE_TABLE_RE = re.compile(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?"
                             r"(?!IF\b|NOT\b|EXISTS\b)([a-z_][\w.]*)", re.IGNORECASE)
CREATE_IDX_RE = re.compile(r"CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?"
                            r"(?:CONCURRENTLY\s+)?(?!IF\b|NOT\b|EXISTS\b|CONCURRENTLY\b)"
                            r"([a-z_][\w]*)", re.IGNORECASE)
ADD_COL_RE = re.compile(r"ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?([a-z_][\w.]*)\s+"
                        r"ADD\s+(?:COLUMN\s+)?(?:IF\s+NOT\s+EXISTS\s+)?"
                        r"(?!(?:CONSTRAINT|PRIMARY|UNIQUE|FOREIGN|CHECK|EXCLUDE)\b)"
                        r"(?!IF\b|NOT\b|EXISTS\b|COLUMN\b)([a-z_][\w]*)", re.IGNORECASE)
DROP_RE = re.compile(r"DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?(?!IF\b|EXISTS\b)([a-z_][\w.]*)|"
                     r"DROP\s+INDEX\s+(?:IF\s+EXISTS\s+)?(?:CONCURRENTLY\s+)?"
                     r"(?!IF\b|EXISTS\b|CONCURRENTLY\b)([a-z_][\w.]*)", re.IGNORECASE)
DROP_COL_RE = re.compile(r"ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?([a-z_][\w.]*)\s+"
                         r"DROP\s+COLUMN\s+(?:IF\s+EXISTS\s+)?(?!IF\b|EXISTS\b)"
                         r"([a-z_][\w]*)", re.IGNORECASE)


ADD_CHECK_RE = re.compile(
    r"ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?([a-z_][\w.]*)\s+"
    r"ADD\s+CONSTRAINT\s+(?!IF\b|EXISTS\b)([a-z_][\w]*)\s+"
    r"CHECK\s*\((.*)\)\s*(?:NOT\s+VALID\s*)?$", re.IGNORECASE | re.DOTALL)
DROP_CONS_RE = re.compile(
    r"ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?([a-z_][\w.]*)\s+"
    r"DROP\s+CONSTRAINT\s+(?:IF\s+EXISTS\s+)?(?!IF\b|EXISTS\b)([a-z_][\w.]*)",
    re.IGNORECASE)
# tokens that carry no meaning once the file expression is canonicalised by
# PostgreSQL: IN is rewritten to = ANY (ARRAY[...]) and literals gain casts.
# "check" is structural too: pg_get_constraintdef prefixes the full line with
# the keyword while the file probe captures the predicate between the parens.
# "between" is structural: PostgreSQL canonicalises BETWEEN into ">= AND <=",
# value tokens stay in the set so a real range change is still named.
STRUCTURAL_TOKENS = {"in", "any", "array", "check", "between"}


def _bare(name: str) -> str:
    """Drop any schema qualification: snapshots are public-schema only."""
    return name.split(".")[-1].lower()


def _def_tokens(expr: str) -> set[str]:
    """Comparable token set of a CHECK expression (file side or pg side).

    Strips type casts (``::text``), quotes, parentheses and commas, lowercases
    everything and drops structural tokens (IN/ANY/ARRAY): the same logical
    predicate written by hand or returned by pg_get_constraintdef reduces to
    one token set. Not a SQL parser — a drift probe: an inequality always
    names both sides for a human verdict, never silently edits."""
    cleaned = re.sub(r"::\s*\w+(?:\s+\w+)?", " ", expr)
    cleaned = re.sub(r"[^0-9a-zA-Z_\u0080-\uffff]+", " ", cleaned)
    return {t for t in cleaned.lower().split() if t and t not in STRUCTURAL_TOKENS}


def _constraint_timeline(files: list[Path]) -> dict[int, list[tuple[str, str, str]]]:
    """vnum -> final CHECK shapes the file at that version must leave behind.

    Final-shape semantics keyed by version, same caliber as _dropped_columns:
    the LAST add/drop event decides whether the constraint is demanded, and
    when a migration re-creates a constraint it first dropped (the V485
    ck_appauto_device_type shape: DROP IF EXISTS + ADD with a wider value
    list), the demand upgrades from name-existence to DEFINITION equality —
    the name is already there, only the predicate changed. Earlier adds of
    the same (table, name) yield the definition duty to the re-definer."""
    last_add: dict[tuple[str, str], tuple[int, str]] = {}
    last_drop: dict[tuple[str, str], int] = {}
    for path in sorted(files, key=lambda p: _vnum(p.name)):
        vnum = _vnum(path.name)
        text = re.sub(r"--[^\n]*", "", path.read_text(encoding="utf-8", errors="replace"))
        for stmt in re.split(r";\s*\n|;\s*$", text):
            m = ADD_CHECK_RE.search(stmt)
            if m:
                last_add[(_bare(m.group(1)), _bare(m.group(2)))] = (vnum, m.group(3))
            for t, c in DROP_CONS_RE.findall(stmt):
                last_drop[(_bare(t), _bare(c))] = vnum
    by_file: dict[int, list[tuple[str, str, str]]] = {}
    for (tbl, con), (vnum, expr) in last_add.items():
        if last_drop.get((tbl, con), -1) > vnum:
            continue  # later DROP: final shape has no such constraint
        by_file.setdefault(vnum, []).append((tbl, con, expr))
    return by_file


def migration_probes(path: Path, dropped_final: set[str],
                     dropped_cols: "frozenset[tuple[str, str]] | set[tuple[str, str]]" = frozenset()) -> tuple[list[str], list[tuple[str, str]]]:
    """Objects a migration file should leave behind: table/index names and
    (table, column) pairs. Later DROPs are removed (final-shape semantics,
    e.g. V301 created a table that V304 legitimately dropped; same for columns:
    V119's request-level vars_json is superseded by V120's drop); COMMENT/INSERT
    statements never create probe objects."""
    text = path.read_text(encoding="utf-8", errors="replace")
    text = re.sub(r"--[^\n]*", "", text)  # SQL line comments never define objects
    stmts = [s for s in re.split(r";\s*\n", text)
             if not re.match(r"\s*(DROP|COMMENT|INSERT|UPDATE|DELETE|SET|SELECT)", s,
                             re.IGNORECASE)]
    body = "\n".join(stmts)
    tables = sorted({_bare(n) for n in CREATE_TABLE_RE.findall(body)})
    idxs = sorted({_bare(n) for n in CREATE_IDX_RE.findall(body)} - set(tables))
    cols = sorted({(_bare(t), _bare(c)) for t, c in ADD_COL_RE.findall(body)})
    idxs = [i for i in idxs
            if not any(i.startswith("idx_" + t) or i.startswith("uk_" + t)
                       or i.startswith("fk_" + t) for t in dropped_final)]
    tables = [t for t in tables if t not in dropped_final]
    idxs = [i for i in idxs if i not in dropped_final]
    cols = [(t, c) for t, c in cols
            if t not in dropped_final and (t, c) not in dropped_cols]
    return tables + idxs, cols


def _dropped_objects(files: list[Path]) -> set[str]:
    """Names whose LAST create/drop event across the migration timeline is a
    DROP (final-shape semantics; a later CREATE revives the object)."""
    create_v: dict[str, int] = {}
    drop_v: dict[str, int] = {}
    for path in sorted(files, key=lambda p: _vnum(p.name)):
        vnum = _vnum(path.name)
        text = re.sub(r"--[^\n]*", "", path.read_text(encoding="utf-8", errors="replace"))
        for name in CREATE_TABLE_RE.findall(text) + CREATE_IDX_RE.findall(text):
            create_v[_bare(name)] = vnum
        for m in DROP_RE.finditer(text):
            drop_v[_bare(m.group(1) or m.group(2))] = vnum
    return {n for n, d in drop_v.items() if d > create_v.get(n, -1)}


def _dropped_columns(files: list[Path]) -> set[tuple[str, str]]:
    """Final-shape semantics for (table, column) pairs, same caliber as
    _dropped_objects: the LAST add/drop event across the migration timeline
    decides existence. Live finding: V119 added tp_apitest_request.vars_json
    and V120 deliberately dropped it (pool promoted to collection level); the
    ledger still demanded the column and the freshly rebuilt slot was forever
    "UNAPPLIED V119" — a superseded marker, not a schema gap."""
    add_v: dict[tuple[str, str], int] = {}
    drop_v: dict[tuple[str, str], int] = {}
    for path in sorted(files, key=lambda p: _vnum(p.name)):
        vnum = _vnum(path.name)
        text = re.sub(r"--[^\n]*", "", path.read_text(encoding="utf-8", errors="replace"))
        for t, c in ADD_COL_RE.findall(text):
            add_v[(_bare(t), _bare(c))] = vnum
        for t, c in DROP_COL_RE.findall(text):
            drop_v[(_bare(t), _bare(c))] = vnum
    return {k for k, d in drop_v.items() if d > add_v.get(k, -1)}


def _probe_context(files: list[Path]):
    """(dropped objects, dropped columns, final CHECK timeline) for a file set.

    One computation shared by every ledger consumer, so the skipped count and
    the data-only roster can never disagree with each other."""
    return _dropped_objects(files), _dropped_columns(files), _constraint_timeline(files)


def _file_probes(path: Path, ctx) -> tuple[list[str], list[tuple[str, str]], list]:
    """What one migration offers the ledger to probe: objects, columns, checks.
    Empty for every slot means the file is a data backfill — verifiable by
    shape never."""
    dropped, dropped_cols, cons_final = ctx
    names, cols = migration_probes(path, dropped, dropped_cols)
    return names, cols, cons_final.get(_vnum(path.name), [])


def data_only_migrations(since: int = 0) -> list[str]:
    """Names of migrations that carry no probeable schema object (pure
    UPDATE/INSERT backfills against existing rows).

    Why this exists: the ledger counted them as `skipped` and moved on, so a
    slot that never replayed a data migration still printed a clean summary —
    that is how a governance backfill sat unapplied in this chain's slot and
    only surfaced as an unrelated gate red one round later. Naming them makes
    the blind spot an action item: replay the listed file through the single
    apply channel, then re-read this ledger.

    Reading the `since` filter: file names that do not parse as a plain
    version number (letter-suffixed legacy seeds such as the llm sys_config
    insert) are ordered last by the existing rule and therefore fall inside
    every `since` window — a listed file of that shape is never a filter bug.
    """
    all_files = sorted(MIGRATION_DIR.glob("V*__*.sql"), key=lambda p: _vnum(p.name))
    ctx = _probe_context(all_files)
    return [p.name for p in all_files
            if _vnum(p.name) >= since and not any(_file_probes(p, ctx))]


def replay_ledger(db: str, since: int = 0) -> tuple[int, list[str], int]:
    """(applied_count, unapplied_names, skipped_count) for migrations whose
    objects can be probed. Migrations with no recognisable object are
    skipped (counted, never silently dropped); the roster of that count is
    `data_only_migrations`, printed by the replay-ledger subcommand."""
    all_files = sorted(MIGRATION_DIR.glob("V*__*.sql"), key=lambda p: _vnum(p.name))
    ctx = _probe_context(all_files)
    files = [p for p in all_files if _vnum(p.name) >= since]
    snap = snapshot(db)
    known_tables = {ln.split("|")[1] for ln in snap if ln.startswith("T|")}
    known_names = {ln.split("|")[2] for ln in snap if ln.startswith("I|")}
    known_cols = {(ln.split("|")[1], ln.split("|")[2]) for ln in snap if ln.startswith("T|")}
    # C|table|conname|contype|definition lines — the name alone cannot prove a
    # re-created CHECK replayed (11-11 live miss: main slot kept the old
    # 3-value device_type predicate while the ledger called V485 applied)
    known_cons = {}
    for ln in snap:
        if ln.startswith("C|"):
            parts = ln.split("|", 4)
            if len(parts) == 5:
                known_cons[(parts[1], parts[2])] = parts[4]
    applied, unapplied, skipped = 0, [], 0
    for path in files:
        names, cols, cons = _file_probes(path, ctx)
        if not names and not cols and not cons:
            skipped += 1
            continue
        missing = [n for n in names if n not in known_tables and n not in known_names]
        missing += [f"{t}.{c}" for t, c in cols if (t, c) not in known_cols]
        redefined = []
        for tbl, con, expr in cons:
            live = known_cons.get((tbl, con))
            if live is None:
                missing.append(f"{tbl}.{con}(check)")
            elif _def_tokens(expr) != _def_tokens(live):
                redefined.append(f"{tbl}.{con} expected [{expr.strip()[:90]}] found [{live.strip()[:90]}]")
        if missing or redefined:
            entry = f"{path.name}: missing {'/'.join(missing[:4])}" if missing else \
                    f"{path.name}: redefinition not applied"
            unapplied.append(entry + "".join(f" | redefined {r}" for r in redefined[:2]))
        else:
            applied += 1
    return applied, unapplied, skipped


def check_slot(slot: int, migrations_dir: Path | None) -> tuple[int, list[str]]:
    """Merge-back door: rebuild the committed shape in a throwaway probe and
    diff the slot against it. Probe name keeps the tp_probe_ prefix so a
    crashed run is cleaned by the next one."""
    db = chain_slot.slot_resources(slot)["database"]
    if not chain_slot._db_exists(db):
        return 2, [f"slot {slot} database {db} does not exist"]
    probe = f"tp_probe_slot{slot}"
    chain_slot._ensure_template()
    chain_slot._psql(chain_slot.MAIN_DB, f"DROP DATABASE IF EXISTS {probe}")
    r = chain_slot._psql(chain_slot.MAIN_DB,
                         f"CREATE DATABASE {probe} TEMPLATE {chain_slot.TEMPLATE_DB}")
    if r.returncode != 0:
        return 2, [f"probe create failed: {r.stderr[-300:]}"]
    try:
        if migrations_dir:
            _replay_probe(probe, list(migrations_dir.glob("V*__*.sql")))
        rc, lines = diff_databases(probe, db)
        return rc, lines
    finally:
        chain_slot._psql(chain_slot.MAIN_DB, f"DROP DATABASE {probe}")


def main(argv: list[str]) -> int:
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    cmd, args = argv[0], argv[1:]
    try:
        if cmd == "snapshot":
            for ln in snapshot(require_db(args[0], "snapshot <db>")):
                print(ln)
            return 0
        if cmd == "diff":
            rc, lines = diff_databases(require_db(args[0], "diff <dbA>"),
                                       require_db(args[1], "diff <dbB>"))
            for ln in lines:
                print(ln)
            print(f"SCHEMA_DIFF {'DRIFT' if rc else 'CLEAN'} ({len(lines)} lines) "
                  f"{args[0]} vs {args[1]}")
            return rc
        if cmd == "check-slot":
            slot = int(args[0])
            mig = None
            if "--migrations" in args:
                idx = args.index("--migrations") + 1
                if idx >= len(args):
                    sys.exit("usage: check-slot <N> [--migrations DIR] -- DIR must "
                             "hold only the migrations added after the factory snapshot; "
                             "replaying the full db/migration tree onto the template "
                             "re-runs ADD statements for already-baked objects "
                             "(badge_token case)")
                mig = Path(args[idx])
            rc, lines = check_slot(slot, mig)
            for ln in lines:
                print(ln)
            print(f"SLOT_CHECK slot{slot} "
                  + ("CLEAN" if rc == 0 else f"DRIFT/ERROR ({len(lines)} lines)"))
            return rc
        if cmd == "replay-ledger":
            db = chain_slot.MAIN_DB
            since = 0
            if "--db" in args:
                db = require_db(args[args.index("--db") + 1], "--db")
            if "--since" in args:
                since = int(args[args.index("--since") + 1].lstrip("Vv"))
            applied, unapplied, skipped = replay_ledger(db, since)
            for line in unapplied:
                print("UNAPPLIED " + line)
            if unapplied:
                print("APPLY each file through the single channel, then re-run this "
                      f"ledger: tools/ops/apply_slot_migration.sh "
                      f"<db/migration/Vxxx__name.sql> --db {db}")
            data_only = data_only_migrations(since)
            if data_only:
                # Newest entries are the actionable ones for a slot cut from a
                # recent snapshot, so the tail of the roster is what gets shown.
                shown = ", ".join(data_only[-15:])
                older = len(data_only) - min(len(data_only), 15)
                print(f"WARN data-only migrations the ledger cannot verify by "
                      f"shape (n={len(data_only)}{f', {older} older not listed' if older else ''}): "
                      f"{shown}")
                print(f"     replay each into this slot through "
                      f"tools/ops/apply_slot_migration.sh <file> --db {db} when the "
                      f"slot predates it, then re-run this ledger")
            print(f"REPLAY_LEDGER db={db} applied={applied} "
                  f"unapplied={len(unapplied)} skipped={skipped} "
                  f"data_only={len(data_only)}")
            return 1 if unapplied else 0
        print(f"unknown subcommand: {cmd}", file=sys.stderr)
        return 2
    except (IndexError, ValueError) as e:
        print(f"usage error: {e}", file=sys.stderr)
        return 2
    except RuntimeError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
