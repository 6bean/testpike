#!/usr/bin/env python3
"""Single source for "which database does this chain talk to".

Every test rig, verification probe and helper used to carry its own JDBC parsing with
a hard-coded default of the MAIN chain's database (jdbc:postgresql://localhost:15432/
testpike). A slot chain that started a window without sourcing its slot env therefore
silently connected to the main database and wrote or deleted fixtures there — another
chain's environment, changed by my test run, with no error anywhere.

Resolve the target here, or do not resolve it at all: a missing DB_URL is a wiring
fault and raises, it never falls back.

Credentials are read from the environment only (.env / slot env) and never printed.
"""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import slot_topology as _topology      # names, bands and chain identity live there

MAIN_DB = _topology.main_database()
JDBC_RE = re.compile(r"jdbc:postgresql://([^:/]+):?(\d*)/([\w-]+)")


def require_db(value: str, handle: str) -> str:
    """Reject a blank database handle before it reaches a query.

    A blank -d name resolves to the connection user's own database and a
    blank --db default resolves to the main development database, so an
    empty argument silently reports another environment's state as the
    caller's target (11-09 live findings on replay-ledger and
    ensure_baseline --db ""). Every database-facing tool must name its
    target explicitly; a chain reads its own slot (scripts/chain_slot.py
    env-path).
    """
    if not value or not value.strip():
        raise ValueError(
            f"{handle} must name a database explicitly (blank is not a target; "
            "for a chain slot read its own env, e.g. "
            "python3 scripts/chain_slot.py env-path)")
    return value


def require_db_url() -> str:
    url = os.environ.get("DB_URL", "").strip()
    if not url:
        raise RuntimeError(
            "DB_URL is not set. Source this chain's .env and its slot env "
            "(python3 scripts/chain_slot.py env-path) before running the window. "
            "There is deliberately no default: falling back to the main database "
            "would make a slot chain drive another chain's environment.")
    return url


def db_spec() -> tuple[str, int, str]:
    """Return (host, port, database) for the database this chain owns."""
    m = JDBC_RE.search(require_db_url())
    if not m:
        raise RuntimeError(f"DB_URL has an unexpected shape: {require_db_url()!r}")
    host, port, dbname = m.group(1), m.group(2), m.group(3)
    return host, int(port or 5432), dbname


def slot_of(dbname: str) -> int:
    """Slot encoded in a database name; 0 when the name is not a slot database."""
    got = _topology.slot_of_database(dbname)
    return 0 if got is None else got


# ports whose band must agree with the database: the pairs come from the topology table
MAIN_BASE_PORTS = {k: b for k, b in _topology.SERVICE_PORTS if k in ("ADMIN_PORT", "CORE_PORT")}


def chain_token(cwd=None) -> str:
    """Chain identity for a directory, resolved by the topology (no name is baked here)."""
    return _topology.chain_token(cwd)


def is_worktree(cwd=None) -> bool:
    return _topology.is_worktree(cwd)


def registered_slot(token: str, cwd=None):
    """Slot this chain owns: the registry's `chain` field, plus its materialized env claim.

    Matching is exact after normalization. Title/id substring matching was the previous
    behaviour and needed a hard-coded exception per chain (a new chain simply did not get
    checked); a domain that records a slot without saying which chain owns it is reported
    as a bookkeeping gap instead of being guessed at.
    """
    slots = set()
    anchor = Path(cwd or os.getcwd())
    gaps = []
    for candidate in (anchor.parent / "docs" / "parallel_readiness_registry.json",
                      anchor / "docs" / "parallel_readiness_registry.json",
                      _topology.registry_path()):
        if not candidate.is_file():
            continue
        try:
            data = json.loads(candidate.read_text(encoding="utf-8"))
        except Exception:
            continue
        for domain in data.get("domains", []):
            slot = domain.get("slot")
            if not slot:
                continue
            owner = _topology.normalize(str(domain.get("chain") or ""))
            if not owner:
                gaps.append(str(domain.get("id")))
                continue
            if owner == _topology.normalize(token):
                slots.add(int(slot))
        break
    for claim in _topology.slot_claims():
        if _topology.normalize(_topology.chain_from_env_name(claim)) == _topology.normalize(token):
            got = _topology.chain_slot_of(claim)
            if got:
                slots.add(got)
    if len(slots) > 1:
        raise RuntimeError(f"chain {token!r} is claimed on several slots {sorted(slots)}; "
                           "settle the slot bookkeeping before running anything")
    if gaps and not slots:
        raise RuntimeError(f"registry records slot(s) without a chain attribution "
                           f"({', '.join(gaps)}); record `chain` on those domains")
    return slots.pop() if slots else None


def wiring_findings(cwd=None) -> list:
    """Grade the current environment wiring: same slot everywhere, and this chain's slot.

    A worktree whose configuration resolves to slot 0 is not "the main chain by accident" -
    it is this chain about to write into the main chain's database. Every .env in every
    worktree points at the main database, and dotenv does not override an already-set
    variable, so a probe that forgets to source the slot env silently lands on slot 0.
    """
    findings = []
    try:
        host, port, dbname = db_spec()
    except RuntimeError as exc:
        return [("FAIL", str(exc))]
    db_slot = slot_of(dbname)
    token = chain_token(cwd)
    expected = None
    try:
        expected = registered_slot(token, cwd)
    except RuntimeError as exc:
        findings.append(("FAIL", str(exc)))
    if is_worktree(cwd) and db_slot == 0:
        findings.append(("FAIL", f"worktree chain {token!r} resolves to the MAIN database "
                         f"({dbname}); source this chain's slot env "
                         f"(python3 scripts/chain_slot.py env-path) or the run will write "
                         f"into the main chain's environment"))
    elif token == "main" and db_slot != 0:
        # The main chain is the resident baseline: it has no slot env, so sourcing one by
        # accident (or an inherited one in the shell) would move the main chain onto another
        # chain's database while its services stay on the main band.
        findings.append(("FAIL", f"the main chain must run on slot 0 but DB_URL resolves to "
                         f"slot {db_slot} ({dbname}); unset the inherited slot env"))
    elif expected is not None and db_slot != expected:
        findings.append(("FAIL", f"chain {token!r} owns slot {expected} but DB_URL resolves "
                         f"to slot {db_slot} ({dbname}) - mixed wiring"))
    for key, base in MAIN_BASE_PORTS.items():
        value = os.environ.get(key, "")
        if not value:
            continue
        port_slot = _topology.slot_of_port(int(value))
        if port_slot is None and (int(value) - base) % _topology.port_band() == 0:
            port_slot = (int(value) - base) // _topology.port_band()
        if port_slot is None:
            findings.append(("FAIL", f"{key}={value} is outside the slot port band (base {base})"))
        elif port_slot != db_slot:
            findings.append(("FAIL", f"{key}={value} is slot {port_slot} while the database is "
                             f"slot {db_slot} - the window would drive another chain's services"))
    if not findings:
        findings.append(("PASS", f"chain {token!r} wiring resolves to slot {db_slot} "
                         f"({dbname}) with matching port band"))
    return findings


def assert_chain_wiring(cwd=None):
    bad = [m for level, m in wiring_findings(cwd) if level == "FAIL"]
    if bad:
        raise RuntimeError(" | ".join(bad))


def bootstrap_env(cwd=None):
    """Load this chain's env the one correct way: .env first, then the slot env over it.

    Replaces the per-probe `load_dotenv(<repo>/.env)` calls, which always produced the
    main slot's configuration inside a worktree. The slot env is loaded with
    override=True so it wins over .env, and the result is asserted before anything can
    connect.
    """
    from pathlib import Path
    try:
        from dotenv import load_dotenv
    except Exception:
        load_dotenv = None
    root = Path(cwd or os.getcwd()).resolve()
    while root != root.parent and not (root / ".env").is_file():
        root = root.parent
    token = chain_token(cwd)
    if not is_worktree(cwd):
        # The main chain's identity lives in the working tree, never in the shell:
        # a slot env inherited from another chain's tick shell would survive the
        # .env load below (dotenv does not override exported keys) and silently
        # move the main chain onto that slot's database. Drop every key a slot
        # env may carry BEFORE loading the installer .env; worktree chains keep
        # the slot-env-wins path untouched.
        import chain_slot as _slot
        for _key in _slot.slot_env_keys():
            os.environ.pop(_key, None)
    if load_dotenv and (root / ".env").is_file():
        load_dotenv(str(root / ".env"), override=False)
    env_dir = Path(os.environ.get("TP_SLOT_ENV_DIR",
                                  str(Path.home() / ".hermes" / "cache" / "slot_env")))
    claims = sorted(env_dir.glob(f"{token}.slot*.env"))
    if load_dotenv and claims:
        load_dotenv(str(claims[0]), override=True)      # the slot env is the authority
        if claims[0].stat().st_size:
            try:
                db_spec()
            except RuntimeError as exc:
                raise RuntimeError(f"{claims[0]} does not carry a usable DB_URL: {exc}") from None
    assert_chain_wiring(cwd)
    return claims[0] if claims else None


def db_connect():
    """psycopg2 connection to this chain's database; credentials come from the env."""
    import psycopg2
    host, port, dbname = db_spec()
    password = os.environ.get("DB_PASSWORD", "")
    if not password:
        raise RuntimeError("DB_PASSWORD is not in the environment (.env not sourced?). "
                           "Never inline a password.")
    return psycopg2.connect(host=host, port=port, dbname=dbname,
                            user=os.environ.get("DB_USERNAME", "testpike"),
                            password=password, connect_timeout=15)


def selftest() -> int:
    """Two-sided: a wired target resolves, an unwired one refuses to guess, and a
    worktree that lands on the main slot is blocked before it can write there."""
    keep = dict(os.environ)
    import contextlib
    import tempfile
    from pathlib import Path
    try:
        os.environ["DB_URL"] = f"jdbc:postgresql://localhost:15432/{_topology.slot_database(3)}"
        host, port, dbname = db_spec()
        assert (host, port, dbname) == ("localhost", 15432, _topology.slot_database(3)), dbname
        assert slot_of(dbname) == 3 and slot_of(MAIN_DB) == 0, "slot arithmetic"
        os.environ["DB_URL"] = "jdbc:postgresql://localhost:5432/testpike"
        assert db_spec()[1] == 5432 and db_spec()[2] == MAIN_DB, "implicit port and main slot"

        os.environ.pop("DB_URL", None)
        for fn in (require_db_url, db_spec, db_connect):
            try:
                fn()
            except RuntimeError as exc:
                assert "no default" in str(exc), f"the message must explain the refusal: {exc}"
            else:
                raise AssertionError(f"{fn.__name__} must refuse without DB_URL")

        # Wiring grade: a planted synthetic chain (every name derived from the topology),
        # no docker and no real chain identity involved.
        import tempfile
        CHAIN, SLOT = "demochain", 1
        SLOT_DB = _topology.slot_database(SLOT)
        SLOT_DB_2 = _topology.slot_database(2)
        ADMIN_1 = str(_topology.slot_port(_topology.shipped_base("ADMIN_PORT"), SLOT))
        CORE_1 = str(_topology.slot_port(_topology.shipped_base("CORE_PORT"), SLOT))
        ADMIN_2 = str(_topology.slot_port(_topology.shipped_base("ADMIN_PORT"), 2))
        ADMIN_MAIN = str(_topology.shipped_base("ADMIN_PORT"))
        CORE_MAIN = str(_topology.shipped_base("CORE_PORT"))
        JDBC = "jdbc:postgresql://localhost:15432/"
        with tempfile.TemporaryDirectory() as workdir:
            wd = Path(workdir)
            (wd / "docs").mkdir()
            (wd / "docs" / "parallel_readiness_registry.json").write_text(json.dumps(
                {"domains": [{"id": "demo-domain", "title": "Demo domain",
                              "slot": SLOT, "chain": CHAIN}]}), encoding="utf-8")
            env_dir = wd / "slot_env"
            env_dir.mkdir()
            wt = wd / f"{_topology.worktree_prefix()}{CHAIN}"
            wt.mkdir()
            main_dir = wd / _topology.repo_name()
            main_dir.mkdir()
            slot_env = env_dir / f"{CHAIN}.slot{SLOT}.env"
            slot_env.write_text(
                f"DB_URL={JDBC}{SLOT_DB}\nADMIN_PORT={ADMIN_1}\nCORE_PORT={CORE_1}\n",
                encoding="utf-8")
            cwd_keep = os.getcwd()
            os.chdir(wt)
            os.environ["TP_SLOT_ENV_DIR"] = str(env_dir)
            try:
                os.environ["DB_URL"] = f"{JDBC}{MAIN_DB}"
                os.environ.pop("ADMIN_PORT", None)
                os.environ.pop("CORE_PORT", None)
                grades = wiring_findings()
                assert any(g[0] == "FAIL" and "MAIN database" in g[1] for g in grades), \
                    f"a worktree landing on the main slot must block: {grades}"
                try:
                    assert_chain_wiring()
                except RuntimeError as exc:
                    assert "slot env" in str(exc), f"the fix pointer must be named: {exc}"
                else:
                    raise AssertionError("assert_chain_wiring must raise on the main-slot case")

                os.environ.update({"DB_URL": JDBC + SLOT_DB,
                                   "ADMIN_PORT": ADMIN_1, "CORE_PORT": CORE_1})
                grades = wiring_findings()
                assert grades[0][0] == "PASS", f"correct slot wiring must pass: {grades}"

                os.environ["ADMIN_PORT"] = ADMIN_MAIN      # main band, slot database
                grades = wiring_findings()
                assert any(f"ADMIN_PORT={ADMIN_MAIN} is slot 0" in g[1] for g in grades), \
                    f"a mixed band must block: {grades}"
                off_band = str(_topology.shipped_base("ADMIN_PORT")
                               + _topology.port_band() // 2)
                os.environ["ADMIN_PORT"] = off_band        # not on any slot boundary
                grades = wiring_findings()
                assert any("outside the slot port band" in g[1] for g in grades), \
                    f"an off-band port must block: {grades}"
                os.environ["ADMIN_PORT"] = ADMIN_1

                os.environ["DB_URL"] = JDBC + SLOT_DB_2    # attribution says slot 1
                grades = wiring_findings()
                assert any(g[0] == "FAIL" and "owns slot 1 but DB_URL resolves to slot 2" in g[1]
                           for g in grades), f"a chain on the wrong slot must block: {grades}"

                second = env_dir / f"{CHAIN}.slot4.env"
                second.write_text("DB_URL=x\n", encoding="utf-8")
                grades = wiring_findings()
                assert any("several slots" in g[1] for g in grades), \
                    f"a double claim must block: {grades}"
                second.unlink()

                os.environ.pop("DB_URL", None)
                os.environ.pop("ADMIN_PORT", None)
                os.environ.pop("CORE_PORT", None)
                (wd / ".env").write_text(f"DB_URL={JDBC}{SLOT_DB}\n"
                                         f"ADMIN_PORT={ADMIN_MAIN}\n", encoding="utf-8")
                bootstrap_env(cwd=str(wt))                 # slot env wins over .env
                assert db_spec()[2] == SLOT_DB, \
                    f"bootstrap must land on the slot database, got {db_spec()[2]}"

                # A slot env that is not parseable must be named, not silently used.
                slot_env.write_text("DB_URL=x\n", encoding="utf-8")
                os.environ.pop("DB_URL", None)
                try:
                    bootstrap_env(cwd=str(wt))
                except RuntimeError as exc:
                    assert "does not carry a usable DB_URL" in str(exc), \
                        f"the offending slot env must be named: {exc}"
                else:
                    raise AssertionError("a malformed slot env must block")
                slot_env.write_text(f"DB_URL={JDBC}{SLOT_DB}\nADMIN_PORT={ADMIN_1}\n"
                                    f"CORE_PORT={CORE_1}\n", encoding="utf-8")

                # The main chain is pinned to slot 0: an inherited slot env must not move
                # it onto another chain's database.
                os.environ.update({"DB_URL": JDBC + SLOT_DB,
                                   "ADMIN_PORT": ADMIN_1, "CORE_PORT": CORE_1})
                grades = wiring_findings(cwd=str(main_dir))
                assert any(g[0] == "FAIL" and "main chain must run on slot 0" in g[1]
                           for g in grades), f"the main chain on slot 1 must block: {grades}"
                os.environ.update({"DB_URL": f"{JDBC}{MAIN_DB}",
                                   "ADMIN_PORT": ADMIN_MAIN, "CORE_PORT": CORE_MAIN})
                grades = wiring_findings(cwd=str(main_dir))
                assert grades[0][0] == "PASS", f"the main chain on slot 0 must pass: {grades}"

                # Two-sided main-chain strip: a slot env inherited in the shell must
                # be dropped by bootstrap_env (main identity lives in the worktree),
                # while keys outside the slot list stay untouched.
                (wd / ".env").write_text(f"DB_URL={JDBC}{MAIN_DB}\n"
                                         f"ADMIN_PORT={ADMIN_MAIN}\n", encoding="utf-8")
                os.environ.update({"DB_URL": JDBC + SLOT_DB,
                                   "ADMIN_PORT": ADMIN_1, "CORE_PORT": CORE_1,
                                   "TP_CHAIN_SLOT": str(SLOT), "TP_CHAIN_NAME": CHAIN,
                                   "TP_E2E_KEEP": "keep-me"})
                bootstrap_env(cwd=str(main_dir))
                assert db_spec()[2] == MAIN_DB, \
                    f"inherited slot DB_URL must be stripped for the main chain, got {db_spec()[2]}"
                assert os.environ.get("ADMIN_PORT") == ADMIN_MAIN, \
                    f"the installer .env must win after the strip, got {os.environ.get('ADMIN_PORT')}"
                assert "TP_CHAIN_SLOT" not in os.environ and "TP_CHAIN_NAME" not in os.environ, \
                    "slot identity keys inherited from another chain must be dropped"
                assert os.environ.get("TP_E2E_KEEP") == "keep-me", \
                    "keys outside the slot list must survive the strip"
                os.environ.pop("TP_E2E_KEEP", None)

                # A slot recorded without a chain attribution, and with no materialized
                # claim either, is a bookkeeping gap: it must be named, not guessed from
                # the domain title.
                slot_env.rename(env_dir / f"{CHAIN}.slot{SLOT}.env.released")
                (wd / "docs" / "parallel_readiness_registry.json").write_text(json.dumps(
                    {"domains": [{"id": "demo-domain", "title": "Demo domain", "slot": SLOT}]}),
                    encoding="utf-8")
                os.environ["DB_URL"] = JDBC + SLOT_DB
                os.environ["ADMIN_PORT"] = ADMIN_1
                os.environ["CORE_PORT"] = CORE_1
                grades = wiring_findings(cwd=str(wt))
                assert any("without a chain attribution" in g[1] for g in grades), \
                    f"an unattributed slot record must be named: {grades}"
                slot_env.write_text(f"DB_URL={JDBC}{SLOT_DB}\nADMIN_PORT={ADMIN_1}\n"
                                    f"CORE_PORT={CORE_1}\n", encoding="utf-8")
            finally:
                os.chdir(cwd_keep)
                for key in ("ADMIN_PORT", "CORE_PORT", "TP_SLOT_ENV_DIR"):
                    os.environ.pop(key, None)
    finally:
        os.environ.clear()
        os.environ.update(keep)
    print("[selftest] two-sided PASS (slot and main targets resolve; an unwired window "
          "refuses instead of falling back; a worktree on the main slot, a mixed port band, "
          "a wrong slot and a double claim all block; bootstrap prefers the slot env; the "
          "main chain strips an inherited slot env before loading its own .env)")
    return 0


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    host, port, dbname = db_spec()
    print(f"{host}:{port}/{dbname} (slot {slot_of(dbname)})")
