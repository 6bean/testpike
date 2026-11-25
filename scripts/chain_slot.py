#!/usr/bin/env python3

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import slot_topology as _topology      # the only place that names slots, ports and chains

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "docs" / "parallel_readiness_registry.json"

# Instance identity is env-overridable so the install path itself can be proven
# against a throwaway PostgreSQL container (empty data volume first boot, factory
# install proof). Defaults are the dev compose lane => existing chains zero drift.
MAIN_DB = _topology.main_database()
TEMPLATE_DB = _topology.template_database()   # read-only factory template (schema-init source)
MAIN_BUCKET = _topology.main_bucket()
PG_CONTAINER = _topology.container("pg")
RABBIT_CONTAINER = _topology.container("rabbit")
REDIS_CONTAINER = _topology.container("redis")
S3_ACCESS = "S3_ACCESS_KEY"
S3_SECRET = "S3_SECRET_KEY"
DB_USER_KEY = "DB_USERNAME"
DB_PASS_KEY = "DB_PASSWORD"
SWF_ENTRYPOINT = ROOT / "tools" / "ops" / "swf-entrypoint.sh"
SCHEMA_INIT = ROOT / "db" / "schema-init.sql"
PORT_OFFSET_PER_SLOT = _topology.port_band()
# Allocatable pool: slot0 stays the shared main chain, 1..8 per dev instance.
SLOT_POOL = tuple(range(1, 9))


def registered_slots(registry: Path = REGISTRY) -> dict:
    """slot -> domain id for every slot > 0 registered in the readiness ledger.
    The registry is the allocation bookkeeping; missing/0 means the shared slot."""
    out = {}
    if Path(registry).exists():
        try:
            data = json.loads(Path(registry).read_text(encoding="utf-8"))
            for d in data.get("domains", []):
                s = d.get("slot")
                if isinstance(s, int) and s > 0:
                    out.setdefault(s, d.get("id", "?"))
        except Exception:
            pass  # conservative: an unreadable registry shows every slot as free; create is idempotent anyway
    return out


def free_slot_pool(exclude=None) -> list:
    """Slots not taken by `exclude` (defaults to the registered ones)."""
    taken = registered_slots() if exclude is None else set(exclude)
    return [n for n in SLOT_POOL if n not in taken]


def allocate_free_slot(registry: Path = REGISTRY) -> int | None:
    """First unregistered slot for a newly loaded chain, or None when the
    pool is exhausted. Pure arithmetic: no docker side effects.

    Bookkeeping alone is not authority: while this function read only the registry,
    five chains were handed slot 1 (the registry never recorded a slot for them),
    so they would have shared one database, one port band and one MQ vhost with the
    chain already running there. Host truth from slot_occupancy is applied first and
    the registry only ever removes options, never adds them.
    """
    occupied, blind = set(), set()
    try:
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        import slot_occupancy
        if not os.environ.get("DB_URL"):
            # Allocation is blind without a database target, and a blind slot used to be
            # handed out as a free one. Load this repo's own configuration first.
            try:
                import db_target
                db_target.bootstrap_env(cwd=Path(__file__).resolve().parents[1])
            except Exception as exc:
                print(f"slot-probe-note: config load failed {type(exc).__name__}: {exc}", file=sys.stderr)
        rows = slot_occupancy.probe()
        occupied = {r["slot"] for r in rows if r["occupied"]}
        blind = {r["slot"] for r in rows if r.get("blind")}
        if blind:
            note = next((r["db_note"] for r in rows if r.get("blind")), "")
            print(f"slot-withhold: {sorted(blind)}  slot(s) unreadable in the database ledger, withheld from handout | {note}",
                  file=sys.stderr)
    except Exception as exc:
        # conservative: registry filtering still applies, but never silently
        print(f"slot-probe-unavailable: live probe failed {type(exc).__name__}: {exc}", file=sys.stderr)
    taken = set(registered_slots(registry)) | occupied | blind
    for n in free_slot_pool(exclude=taken):
        return n
    return None



def slot_resources(slot: int) -> dict:
    """Deterministic identity of one slot. slot0 == current shared dev stack."""
    if slot < 0:
        raise ValueError(f"slot must be >= 0, got {slot}")
    if slot == 0:
        return {
            "slot": 0,
            "database": MAIN_DB,
            "redis_db": "0",
            "mq_vhost": "/",
            "s3_bucket": MAIN_BUCKET,
            "swf_meta_db": _topology.swf_meta_database(0),
            "swf_container": _topology.swf_container_for(0),
            "port_base": 0,
        }
    return {
        "slot": slot,
        "database": _topology.slot_database(slot),
        "redis_db": _topology.redis_db_for(slot),
        "mq_vhost": _topology.vhost_for(slot),
        "s3_bucket": _topology.bucket_for(slot),
        "swf_meta_db": _topology.swf_meta_database(slot),
        "swf_container": _topology.swf_container_for(slot),
        "port_base": slot * PORT_OFFSET_PER_SLOT,
    }


def slot_port(base_port: int, slot: int) -> int:
    """Per-slot port: base + band*slot; slot0 keeps the base (zero drift)."""
    return _topology.slot_port(base_port, slot)


def residue_targets(slot: int) -> list:
    """Everything create allocates; drop must prove each item is gone."""
    r = slot_resources(slot)
    return [r["database"], r["mq_vhost"], r["s3_bucket"], str(r["redis_db"]),
            r["swf_meta_db"], r["swf_container"]]


def env_template_text(slot: int) -> str:
    """Full isolation key set for one slot's env file.

    Every line is derived from the topology, so a service registered there appears in
    newly issued slots without touching this function. Key spellings are what the
    applications actually read: MOCK_SERVER_TRAFFIC_PORT must stay exactly as written (the
    earlier MOCK_TRAFFIC_PORT was never consumed, so the mock traffic connector fell back
    to the main slot's port and collided).
    """
    r = slot_resources(slot)
    lines = [
        f"# {_topology.repo_name()} chain-slot env — slot {slot} "
        "(generated by scripts/chain_slot.py; resolve it through `chain_slot.py env-path`)",
        f"DB_URL=jdbc:postgresql://{_topology.db_target()[0]}:{_topology.db_target()[1]}/{r['database']}",
        # The compose app tier addresses the server by in-network container name (5432 is
        # the container's own listen port, a shipped internal, not the host mapping), so the
        # host-side DB_URL above cannot cross that boundary and docker-compose.yml reads the
        # alias APP_DB_URL instead. A slot env without this export lets every chain's
        # containers boot on the shipped main-slot database while its tooling probes slotN —
        # measured live 2026-11-22 (startup seed ran its no-op branch against the main db).
        f"APP_DB_URL=jdbc:postgresql://{_topology.container('pg')}:5432/{r['database']}",
        f"REDIS_DATABASE={r['redis_db']}",
        f"MQ_VHOST={r['mq_vhost']}",
        f"S3_BUCKET={r['s3_bucket']}",
        f"SWF_META_DB_NAME={r['swf_meta_db']}",
        f"SERVER_PORT_OFFSET={r['port_base']}",
    ]
    # bases come from THIS install's configuration (application.yml reads the same env
    # keys, and the fallback defaults are proven identical by the topology selftest), so a
    # user who re-targeted 8081 to something else gets slots around their own port.
    lines += [f"{key}={_topology.slot_port(_topology.base_port(key, base), slot)}"
              for key, base in _topology.SERVICE_PORTS]
    # The service descriptors resolve inter-service calls from URL keys, not from the
    # raw port keys above (admin/application.yml reads ${CORE_BASE_URL:...}, api the
    # same, core and admin read ${AISERVICE_BASE_URL:...}). A slot that only exports
    # ports lets every chain's services fall back to the shipped main-slot defaults,
    # which silently routes one chain's traffic into another chain's data plane
    # (journey-chain E2E hit the main core this way on 2026-11-05). The URL keys are
    # therefore derived from this slot's own resolved ports — single source, no drift.
    slot_ports = {key: _topology.slot_port(_topology.base_port(key, base), slot)
                  for key, base in _topology.SERVICE_PORTS}
    lines.append(f"CORE_BASE_URL=http://localhost:{slot_ports['CORE_PORT']}")
    lines.append(f"AISERVICE_BASE_URL=http://localhost:{slot_ports['AISERVICE_PORT']}")
    # The S3 endpoint must resolve to THIS slot's gateway container (published on the
    # slot's own band), never to slot 0's shared gateway.
    lines.append(f"S3_ENDPOINT=http://localhost:{slot_ports['SWF_S3_HOST_PORT']}")
    # Storage file URLs are served back through the admin port (shipped default 8081);
    # core's ${STORAGE_LOCAL_PUBLIC_BASE_URL:...} must follow this slot's admin band.
    lines.append("STORAGE_LOCAL_PUBLIC_BASE_URL="
                 f"http://localhost:{slot_ports['ADMIN_PORT']}/storage/files")
    # Tooling base family : probes read TP_*_BASE as the explicit override of
    # their port-derived default; pinning it per slot means a missing process-port
    # export can never silently route a chain's probe onto slot 0.
    lines += [f"{key}=http://localhost:{slot_ports[port_key]}"
              for key, port_key in _topology.BASE_URL_FAMILY]
    # The compose app tier maps HOST ports through APP_*_HOST_PORT keys (its
    # descriptors read e.g. ${APP_ADMIN_HOST_PORT:-18081}). A slot env that
    # exports only the raw service-port keys leaves `compose up` on the shipped
    # defaults, which collide with other planes on the shared host — measured
    # live 2026-11-22: the slot4 stack's ai-service failed to bind 18084
    # (slot 0's JVM band) and the dependent app tier stayed Created. Pin the
    # mappings to this slot's own band, derived from the same single source.
    lines += [f"APP_{name}_HOST_PORT={slot_ports[port_key]}"
              for name, port_key in (("ADMIN", "ADMIN_PORT"), ("API", "API_PORT"),
                                     ("CORE", "CORE_PORT"), ("AISERVICE", "AISERVICE_PORT"))]
    lines.append("")
    return "\n".join(lines)



def slot_env_keys() -> tuple[str, ...]:
    """Every env key a slot env file may carry (derived from the slot 0 template).

    The main chain uses this list to drop slot variables inherited from another
    chain's shell before loading its own .env: dotenv never overrides a key that
    is already exported, so an inherited DB_URL would silently move the main
    chain onto another slot's database while its services stay on the main band
    (observed on the main tick shell on 2026-11-08). The key set is parsed from
    `env_template_text`, never re-typed here, so a new isolation key added to
    the topology is stripped by the main chain without touching this function.
    """
    keys = {line.split("=", 1)[0]
            for line in env_template_text(0).splitlines()
            if "=" in line and not line.startswith("#")}
    return tuple(sorted(keys | {"TP_CHAIN_SLOT", "TP_CHAIN_NAME"}))


def chain_env_text(slot: int, chain: str, base_text: str) -> str:
    """One chain's complete .env: installer values plus this slot's distinct keys.

    The isolation keys come from the topology (env_template_text), the rest is the installer
    file the user configured at install time, so a retargeted port or host is inherited by every
    chain while the resource-owning keys stay per-chain.
    """
    overrides = {}
    for line in env_template_text(slot).splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            overrides[k] = v
    overrides["TP_CHAIN_SLOT"] = str(slot)
    overrides["TP_CHAIN_NAME"] = chain
    out, seen = [], set()
    for line in base_text.splitlines():
        m = re.match(r"^\s*([A-Z][A-Z0-9_]*)=(.*)$", line)
        if m and m.group(1) in overrides:
            out.append(f"{m.group(1)}={overrides[m.group(1)]}")
            seen.add(m.group(1))
        else:
            out.append(line)
    extra = [f"{k}={v}" for k, v in overrides.items() if k not in seen]
    if extra:
        out += ["# --- chain isolation keys (written by `chain_slot.py configure`) ---"] + extra
    return "\n".join(out) + "\n"


def configure_chain_env(tree: Path, chain: str | None = None, slot: int | None = None,
                        dry_run: bool = False, force: bool = False) -> dict:
    """Materialise one chain's own .env and prove it is distinct from every other chain."""
    tree = Path(tree).resolve()
    env = tree / ".env"
    main_root = Path(__file__).resolve().parents[1]
    if chain is None:
        chain = chain_token_for_tree(tree) or tree.name
    if slot is None:
        if tree == main_root:
            slot = 0
        else:
            cur = None
            try:
                cur = current_slot(cwd=tree)
            except Exception:
                cur = None
            slot = cur if cur and cur > 0 else allocate_free_slot()
    if slot is None:
        raise RuntimeError("no free slot (live occupancy full or database config not loaded); refusing to configure")
    if tree != main_root and slot == 0:
        raise RuntimeError(f"worktree {tree.name} must not land on slot 0 (reserved for the main chain)")

    base_file = _topology.installer_env_file()
    base_text = base_file.read_text(encoding="utf-8", errors="ignore") if base_file.is_file() else ""
    forced_identity = None
    if env.is_file() and not env.is_symlink():
        own = env.read_text(encoding="utf-8", errors="ignore")
        m = re.search(r"^TP_CHAIN_NAME=(\S+)", own, re.M)
        ms = re.search(r"^TP_CHAIN_SLOT=(\d+)", own, re.M)
        if m and ms and m.group(1) != chain:
            if not force:
                raise RuntimeError(f"{env} is already registered to chain {m.group(1)}(slot {ms.group(1)}); "
                                   f"reassigning it to {chain} requires --force with a reason")
            # --force REASON is the attributed override channel (11-14 fix: the
            # parameter was accepted but never consumed, so the flag was dead code).
            forced_identity = f"{m.group(1)}(slot {ms.group(1)}) -> {chain}"
    text = chain_env_text(slot, chain, base_text)

    report = {"tree": str(tree), "chain": chain, "slot": slot,
              "was_symlink": env.is_symlink(), "dry_run": dry_run}
    if forced_identity:
        report["forced_identity"] = forced_identity
    if dry_run:
        report["written"] = False
        return report
    if env.is_symlink():
        link_to = os.path.realpath(env)
        env.unlink()
        report["replaced_symlink"] = link_to
    env.write_text(text, encoding="utf-8")
    report["written"] = True
    if slot >= 1:
        # tree identity without a host claim = the ledger reads the slot free while
        # a chain writes to it (11-05 journey rebuild). Register idempotently.
        report["claim"] = str(ensure_claim(chain, slot))
    report["conflicts"] = check_env_distinctness(tree)
    return report


def check_env_distinctness(root: Path | None = None) -> list:
    """Two chains must never own the same database / port band / redis db / vhost / bucket.

    Only claim holders join the comparison: a tree counts as a chain when it is
    the main checkout (slot0 resident) or its .env declares TP_CHAIN_SLOT>=1 AND a
    live claim file exists for that (chain, slot). The claim ledger is the authority
    for ownership (R47: register-first, live-scan-only); an .env slot declaration
    whose claim was released or never registered is a ledger drift, not an
    ownership fact — counting it produced duplicate-red storms where an idle merged
    chain's stale .env collided with a scratch R41 window that legitimately claimed
    the same slot (11-18 measured twice, slot7/slot8 family; the scratch tree was
    the real holder). Drifted trees are skipped with a named WARN so they stay
    visible, and two trees that both hold live claims on one resource key remain a
    hard red — the invariant "no two owners share a slot" is never relaxed.
    """
    roots = [Path(p) for p in _sibling_trees(root)]
    main_root = ROOT.resolve()
    rows = []
    for t in roots:
        f = t / ".env"
        if not f.is_file():
            if t.resolve() != main_root:
                print(f"[distinct] skip {t.name}: not a chain tree (no .env, excluded from key comparison)", file=sys.stderr)
            rows.append((t.name, "missing .env" if t.resolve() == main_root else None))
            continue
        text = f.read_text(encoding="utf-8", errors="ignore")
        if f.is_symlink():
            rows.append((t.name, "symlink (each chain must own a real .env file in its own tree)"))
        get = lambda k: (re.search(rf"^{k}=(.*)$", text, re.M) or [None, ""])[1]
        slot_val = get("TP_CHAIN_SLOT") or "0"
        if t.resolve() != main_root and int(slot_val or 0) < 1:
            print(f"[distinct] skip {t.name}: not a chain tree (TP_CHAIN_SLOT={slot_val or '0'}, no slot claimed)",
                  file=sys.stderr)
            continue
        if t.resolve() != main_root:
            holder = chain_token_for_tree(t) or get("TP_CHAIN_NAME") or t.name
            if not claim_file(holder, int(slot_val)).is_file():
                print(f"[distinct] skip {t.name}: slot={slot_val} has no live claim (ledger divergence, "
                      f"claim={claim_file(holder, int(slot_val)).name}); this tree holds no resource keys; "
                      f"a chain reusing it must run configure to re-claim first", file=sys.stderr)
                continue
        rows.append((t.name, {"slot": slot_val, "db": get("DB_URL"),
                              "admin": get("ADMIN_PORT"), "core": get("CORE_PORT"),
                              "redis": get("REDIS_DATABASE"), "vhost": get("MQ_VHOST"),
                              "bucket": get("S3_BUCKET"),
                              "swfdb": get("SWF_META_DB_NAME")}))
    seen = {}
    bad = []
    for name, val in rows:
        if val is None:
            continue
        if isinstance(val, str):
            bad.append(f"{name}: {val}")
            continue
        for field in ("db", "admin", "core", "redis", "vhost", "bucket", "swfdb"):
            key = (field, val.get(field))
            if val.get(field) in ("", None):
                continue
            if key[1] in [v for k, v in seen.items() if k[0] == field]:
                bad.append(f"{name}: {field}={val[field]} duplicates another tree")
            seen[key] = val[field]
    return bad


def _sibling_trees(root=None):
    """Every tree of this repo (main + linked worktrees), each listed exactly once.

    The set comes from `git worktree list`, so it is identical no matter which tree runs
    the tool. The old shape always started from this module's own ROOT and globbed the
    parent for worktrees: invoked from a worktree it listed that worktree twice (self +
    glob) while dropping the main tree, so the distinctness check self-collided — a
    permanently red gate is a tool defect, not an environment conflict.
    The directory glob is only the no-git fallback.
    """
    tree = Path(root) if root else ROOT
    out = [tree]
    try:
        res = subprocess.run(["git", "-C", str(tree), "worktree", "list", "--porcelain"],
                             capture_output=True, text=True, timeout=10)
        for line in res.stdout.splitlines():
            if line.startswith("worktree "):
                p = Path(line[len("worktree "):])
                if p.is_dir():
                    out.append(p)
    except (OSError, subprocess.SubprocessError):
        base = tree.parent
        out.extend(p for p in sorted(base.glob(f"{_topology.worktree_prefix()}*")) if p.is_dir())
    uniq, seen = [], set()
    for p in out:
        r = str(Path(p).resolve())
        if r not in seen:
            seen.add(r)
            uniq.append(Path(r))
    return uniq


def chain_token_for_tree(tree: Path) -> str | None:
    # the worktree prefix lives in the topology only (R48④: recipes and tools must not
    # hard-code repo/chain names — this literal was a second naming source of the same
    # family that produced the 11-05 ghost-slot incident when it drifted from the tree)
    name = Path(tree).name
    prefix = _topology.worktree_prefix()
    return (name[len(prefix):] or None) if prefix and name.startswith(prefix) else None


def claim_file(chain: str, slot: int) -> Path:
    """Public seam: where one chain's live slot claim must live (topology naming).
    The assembler and every recipe resolve claims through here — never by hand-building
    '{project}.slotN.env' strings (11-05: the hand-built name did not match the
    directory-derived token the chain's first tick reads, so it found no file and
    auto-issued a ghost slot)."""
    return _topology.slot_env_path(chain, slot)


def ensure_claim(chain: str, slot: int) -> Path:
    """Idempotent claim registration: write when missing, NEVER rewrite a live claim.
    Closes the 11-05 journey hole: a slot rebuilt through configure wrote the tree .env
    only, the claim stayed .released, and the host ledger read an actively written slot
    as free."""
    target = claim_file(chain, slot)
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.is_file():
        target.write_text(env_template_text(slot), encoding="utf-8")
    return target



def verify_chain_env(tree: Path) -> list:
    """A chain's own .env must be self-consistent: identity slot == database slot == port band."""
    env = Path(tree).resolve() / ".env"
    bad = []
    if env.is_symlink():
        bad.append(f".env is a symlink -> every chain would share one config ({os.path.realpath(env)})")
        return bad
    text = env.read_text(encoding="utf-8", errors="ignore")
    get = lambda k: (re.search(rf"^{k}=(.*)$", text, re.M) or [None, ""])[1]
    declared = get("TP_CHAIN_SLOT")
    if declared == "":
        bad.append("TP_CHAIN_SLOT missing (this chain identity not registered)")
        return bad
    slot = int(declared)
    db = get("DB_URL")
    m = re.search(r"/([A-Za-z0-9_-]+?)(?:\?|$)", db)
    if m:
        got = _topology.slot_of_database(m.group(1))
        if got is not None and got != slot:
            bad.append(f"database {m.group(1)} belongs to slot{got}, not the declared slot{slot}")
    for key, base in _topology.SERVICE_PORTS:
        v = get(key)
        if v.isdigit():
            expect = _topology.slot_port(_topology.base_port(key, base), slot)
            if int(v) != expect:
                bad.append(f"{key}={v} != {expect} expected for slot{slot} (port-band overlap risk)")
    # Tooling base family : a TP_*_BASE whose port leaves this slot's band sends
    # that chain's probes into another chain's data plane even while the raw port keys
    # stay correct — the checklist catches the divergence and names the key.
    for key, port_key in _topology.BASE_URL_FAMILY:
        v = get(key)
        pv = get(port_key)
        if not v:
            continue
        m = re.search(r"://[^/]+:(\d+)", v)
        if m and pv and m.group(1) != pv:
            bad.append(f"{key}={v} port disagrees with this slot {port_key}={pv} (base-url family drift)")
        elif not m:
            bad.append(f"{key}={v} is not an http(s) base url with a port")
    rdb = get("REDIS_DATABASE")
    if rdb.isdigit() and int(rdb) != slot:
        bad.append(f"REDIS_DATABASE={rdb} should equal slot number {slot}")
    vh = get("MQ_VHOST")
    if vh and vh != _topology.vhost_for(slot):
        bad.append(f"MQ_VHOST={vh} should be {_topology.vhost_for(slot)}")
    bk = get("S3_BUCKET")
    if bk and bk != _topology.bucket_for(slot):
        bad.append(f"S3_BUCKET={bk} should be {_topology.bucket_for(slot)}")
    swf = get("SWF_META_DB_NAME")
    if swf and swf != _topology.swf_meta_database(slot):
        bad.append(f"SWF_META_DB_NAME={swf} should be {_topology.swf_meta_database(slot)}")
    return bad


def _run(cmd: list, stdin: bytes | None = None, timeout: int = 180) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, input=stdin, capture_output=True, text=True, timeout=timeout)


def _need_docker() -> None:
    if shutil.which("docker") is None:
        raise RuntimeError("docker CLI not on PATH — slot tooling talks to the dev compose stack")


def _psql(database: str, sql: str) -> subprocess.CompletedProcess:
    return _run(["docker", "exec", PG_CONTAINER, "psql", "-U", "testpike", "-d", database,
                 "-tA", "-v", "ON_ERROR_STOP=1", "-c", sql])


def _db_exists(database: str) -> bool:
    # pg_isready answers the SERVER, not the DATABASE: even for a missing db it
    # prints "accepting connections" (rc=0), which once made init skip CREATE.
    # Existence must come from the catalog.
    r = _psql(MAIN_DB, f"SELECT 1 FROM pg_database WHERE datname = '{database}'")
    return r.returncode == 0 and r.stdout.strip() == "1"


def _load_sql(database: str, path: Path) -> None:
    # text mode end-to-end: subprocess with text=True requires str input, not bytes
    r = _run(["docker", "exec", "-i", PG_CONTAINER, "psql", "-U", "testpike", "-d", database,
              "-v", "ON_ERROR_STOP=1", "-q"], stdin=path.read_text(encoding="utf-8"), timeout=300)
    if r.returncode != 0:
        raise RuntimeError(f"psql load into {database} failed: {r.stderr[-400:]}")


def _rabbit_cli() -> list | None:
    """rabbitmqctl path inside the compose image (dev builds ship no rabbitmq-admin)."""
    r = _run(["docker", "exec", RABBIT_CONTAINER, "sh", "-c",
              "command -v rabbitmqctl || ls /opt/rabbitmq/sbin/rabbitmqctl 2>/dev/null"], timeout=30)
    path = r.stdout.strip().splitlines()[0].strip() if r.stdout.strip() else ""
    return ["docker", "exec", RABBIT_CONTAINER, path] if path else None


def _rabbit_vhosts() -> set:
    cli = _rabbit_cli()
    if cli is None:
        return set()
    # -q prints a header line plus one name per line; match whole lines so a
    # root vhost "/" can never be substring-matched into existence.
    r = _run(cli + ["-q", "list_vhosts"], timeout=60)
    return {l.strip() for l in r.stdout.splitlines() if l.strip() and l.strip() != "name"}


# --- : fourth retirement proof = this slot's vhost drained -----------------
# 11-04 measured incident: a retired verification window left 907342 dispatch
# messages sitting in the main slot's vhost; the next window's executor picked
# them up and stopped claiming fresh work, so three same-shape windows were
# judged red. Data/process/port proofs never looked at MQ messages, so the
# residue was invisible at retirement. This gate reads queue backlog over the
# HTTP management API and blocks slot drop until the slot's queues are drained.

def mgmt_base() -> str:
    """Management API base URL (R48: env key + factory default = compose mapping)."""
    port = os.environ.get("MQ_MGMT_HOST_PORT", "").strip()
    if not port:
        envf = ROOT / ".env"
        if envf.is_file():
            m = re.search(r"^MQ_MGMT_HOST_PORT=(\S+)", envf.read_text(encoding="utf-8"), re.M)
            port = m.group(1) if m else ""
    return f"http://localhost:{port or '15672'}"


def _ctl_queue_counts(vhost: str) -> dict | None:
    """Synchronous queue counts via rabbitmqctl (no management-DB stats lag).

    None = unreadable (fail-closed upstream must not read this as zero).
    """
    cli = _rabbit_cli()
    if cli is None:
        return None
    r = _run(cli + ["-q", "list_queues", "-p", vhost,
                    "name", "messages_ready", "messages_unacknowledged"], timeout=60)
    if r.returncode != 0:
        return None
    counts = {}
    for line in r.stdout.splitlines():
        parts = [p.lstrip("~") for p in line.split("\t")]
        if len(parts) >= 3 and parts[1].isdigit() and parts[2].isdigit():
            counts[parts[0]] = (int(parts[1]), int(parts[2]))
    return counts


def vhost_backlog(vhost: str) -> dict:
    """ready+unacked per queue for ONE vhost via the management API.

    Tri-state on purpose (P18): an unreadable backlog is reported unknown=True,
    never folded into "empty" — a blind read must not open a retirement gate.
    Credentials are read from repo .env and only ever sent as a Basic header.
    The management DB refreshes queue stats asynchronously (measured: counts
    read 0 for seconds after a live publish), so an apparent zero is
    cross-checked against the synchronous rabbitmqctl view before the gate
    believes it — a lagged zero must not open the retirement path.
    """
    import base64
    import urllib.error
    import urllib.parse
    import urllib.request
    url = f"{mgmt_base()}/api/queues/{urllib.parse.quote(vhost, safe='')}"
    try:
        token = base64.b64encode(
            (f"{_env_pair('RABBITMQ_USERNAME')}:{_env_pair('RABBITMQ_PASSWORD')}").encode()).decode()
        req = urllib.request.Request(url, headers={"Authorization": f"Basic {token}"})
        with urllib.request.urlopen(req, timeout=15) as resp:
            rows = json.loads(resp.read().decode())
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            # The management API answers 404 for a vhost that was never
            # created, and a vhost without a definition cannot hold queues,
            # so the backlog is trivially drained (measured 2026-11-12: the
            # 404 was folded into unknown=True and wrongly kept a clean
            # slot-retirement gate shut). Other HTTP errors stay unknown=True.
            return {"unknown": False,
                    "reason": "vhost absent (management API 404): nothing to drain",
                    "total": 0, "queues": []}
        return {"unknown": True, "reason": f"{type(exc).__name__}: {exc}", "total": 0, "queues": []}
    except Exception as exc:
        return {"unknown": True, "reason": f"{type(exc).__name__}: {exc}", "total": 0, "queues": []}
    queues, total = [], 0
    for q in rows if isinstance(rows, list) else []:
        ready = int(q.get("messages_ready") or 0)
        unacked = int(q.get("messages_unacknowledged") or 0)
        holders = []
        for c in q.get("consumer_details") or []:
            ch = c.get("channel_details") or {}
            pid = ch.get("pid")
            if isinstance(pid, (list, tuple)):
                pid = ".".join(str(x) for x in pid)
            holders.append(f"pid {pid} user {c.get('user') or '?'} "
                           f"client {c.get('connection_name') or ch.get('name') or '?'}")
        total += ready + unacked
        queues.append({"name": q.get("name"), "ready": ready, "unacked": unacked,
                       "consumers": len(holders), "channels": holders})
    note = ""
    ctl = _ctl_queue_counts(vhost)
    if ctl is not None:
        live = sum(r + u for r, u in ctl.values())
        api_total = sum(q["ready"] + q["unacked"] for q in queues)
        if live != api_total:
            known = {q["name"] for q in queues}
            for name, (ready, unacked) in sorted(ctl.items()):
                if name in known:
                    for q in queues:
                        if q["name"] == name:
                            q["ready"], q["unacked"] = ready, unacked
                else:
                    queues.append({"name": name, "ready": ready, "unacked": unacked,
                                   "consumers": 0, "channels": []})
            total = live
            note = ("management API stats lagged (partial or stale counts); "
                    "synchronous broker view used")
    queues.sort(key=lambda x: (-(x["ready"] + x["unacked"]), x["name"] or ""))
    return {"unknown": False, "reason": note, "total": total, "queues": queues}


def backlog_gate(slot: int, vhost: str, account: dict | None = None) -> str:
    """Block message when this slot's vhost still holds work; "" when clear.

    Never queries or judges "/" — the main shared baseline belongs to no slot,
    and a slot retirement must not touch it. account= injects a read for tests
    so the two-sided selftest needs no live broker.
    """
    if vhost == "/":
        return ""
    acct = account if account is not None else vhost_backlog(vhost)
    if acct.get("unknown"):
        return (f"slot {slot} vhost {vhost} backlog unreadable via management API "
                f"({acct.get('reason')}) — fail-closed, the retirement gate stays shut. "
                "Fix the management endpoint or pass --force REASON with attribution")
    stuck = [q for q in acct["queues"] if q["ready"] + q["unacked"] > 0]
    if not stuck:
        return ""
    lines = "; ".join(
        f"{q['name']} ready={q['ready']} unacked={q['unacked']} consumers={q['consumers']}"
        + (f" [{'; '.join(q['channels'])}]" if q["channels"] else "")
        for q in stuck)
    return (f"slot {slot} vhost {vhost} has backlog {acct.get('total')}: {lines} — "
            "drain via the consumer, or clear only this slot's queues with "
            f"'python3 scripts/chain_slot.py purge {slot} --force REASON' "
            "(other slots' and the main vhost are off-limits), then retry")


def purge_slot_queues(slot: int) -> dict:
    """The legitimate drain channel: purge ONLY this slot's vhost's queues.

    Refuses to run blind: the backlog must be readable first, and each queue is
    pinned to this slot's vhost — the main shared vhost and other slots' vhosts
    are never addressed here.
    """
    if slot <= 0:
        raise RuntimeError("slot 0 is the shared dev baseline; never purge its vhost")
    rabbit = _rabbit_cli()
    if rabbit is None:
        raise RuntimeError("rabbitmqctl not found — cannot purge blindly")
    vhost = slot_resources(slot)["mq_vhost"]
    acct = vhost_backlog(vhost)
    if acct["unknown"]:
        raise RuntimeError(f"backlog unreadable for {vhost} ({acct['reason']}) — refusing a blind purge")
    purged = []
    for q in acct["queues"]:
        pr = _run(rabbit + ["purge_queue", "-p", vhost, "-q", q["name"]], timeout=60)
        if pr.returncode != 0:
            raise RuntimeError(f"purge_queue {vhost}/{q['name']} failed: {pr.stderr[-300:]}")
        purged.append(q["name"])
    return {"slot": slot, "vhost": vhost, "purged_queues": purged,
            "after": vhost_backlog(vhost)}


# --- : pin the consumer timeout on the slot's own vhost ----------------------
# Channel correction (measured on the 3.13.7 broker, sources verified against
# rabbit_vhost_limit.erl / rabbit_channel.erl):
#   * The "vhost-limits" runtime parameter accepts ONLY max-connections and
#     max-queues in this release — a consumer_timeout term is rejected as an
#     "Unrecognised term" (management API PUT -> 400, rabbitmqctl
#     set_vhost_limits -> exit 69; both reproduced live, table
#     rabbit_vhost_limit.beam holds zero rows because nothing can be stored).
#   * The live per-queue knob is rabbit_channel:get_queue_consumer_timeout ->
#     args_policy_lookup("consumer-timeout"), resolved from the queue POLICY
#     key consumer-timeout or the x-consumer-timeout declare argument (the
#     tighter value wins). A vhost-scoped policy is therefore the correct
#     channel to pin the ack deadline for every queue a slot's chain creates.
#   * PUT /api/vhosts/{name} with the field returns 204 but is a silent no-op
#     (measured) — a 2xx never proves the pin, so the only acceptance test is
#     the list_policies read-back carrying the exact value.
POLICY_NAME = "tp-consumer-timeout"


def consumer_timeout_ms() -> int:
    """Ack deadline a slot pins (R48: env key + shipped factory default).

 set-of-three: an in-flight message must never be re-delivered
 mid-run, so the ack window factory is pinned at >= 2 x the extraction
 task budget (K8 ai.doc.extract-deadline-seconds). V487 raised that
 deadline to 7200 s while this factory stayed at 7,200,000 ms, which put
 the two windows at exact equality and made every full-length gold run
 race the broker's 406 delivery-ack close (measured 2026-11-15: task
 finish 7206 s vs ack 7200 s). Recalibrated to 14,400,000 ms = 2 x the
 current deadline. A lower explicit override is possible on purpose but
 fails the slot gate selftest floor.
 """
    raw = os.environ.get("TP_MQ_ACK_TIMEOUT_MS", "").strip()
    if not raw:
        envf = ROOT / ".env"
        if envf.is_file():
            m = re.search(r"^TP_MQ_ACK_TIMEOUT_MS=(\S+)", envf.read_text(encoding="utf-8"), re.M)
            raw = m.group(1) if m else ""
    return int(raw or "14400000")


def _pin_vhost_consumer_timeout(vhost: str, runner=None, ms=None) -> dict:
    """Set + read-back-prove the consumer-timeout policy on ONE slot vhost.

    runner is an injection seam for the two-sided selftest (defaults to the
    real rabbitmqctl transport). Refuses the shared main vhost outright.
    """
    if vhost == "/":
        raise RuntimeError("refusing to pin consumer timeout on the shared main vhost '/'")
    run = runner or _run
    cli = _rabbit_cli()
    if cli is None:
        if runner is None:
            raise RuntimeError("rabbitmqctl not found inside testpike-rabbitmq")
        cli = ["rabbitmqctl"]
    want = consumer_timeout_ms() if ms is None else int(ms)
    sr = run(cli + ["set_policy", "-p", vhost, POLICY_NAME, ".*",
                    json.dumps({"consumer-timeout": want}),
                    "--apply-to", "queues"], timeout=60)
    if sr.returncode != 0:
        raise RuntimeError(f"set_policy consumer-timeout failed for {vhost}: {sr.stderr[-300:]}")
    # list_policies takes no column args (unlike list_queues) — measured live.
    lr = run(cli + ["-q", "list_policies", "-p", vhost], timeout=60)
    if lr.returncode != 0:
        raise RuntimeError(f"list_policies read-back failed for {vhost}: {lr.stderr[-300:]}")
    for line in (lr.stdout or "").splitlines():
        parts = [f.strip() for f in line.split("\t")]
        if POLICY_NAME not in parts:
            continue
        for field in parts:
            if field.startswith("{"):
                try:
                    got = json.loads(field).get("consumer-timeout")
                except Exception:
                    got = None
                if got == want:
                    return {"vhost": vhost, "policy": POLICY_NAME, "consumer_timeout_ms": want}
    raise RuntimeError(
        f"consumer timeout {want}ms not visible in {vhost} policy read-back — "
        "refusing to claim the pin on a status code alone")


def _swf_inspect(name: str) -> dict | None:
    """Live docker state of one gateway container; None when it does not exist."""
    r = _run(["docker", "inspect", "--format",
              '{{.State.Running}}|{{index .Config.Env 0}}', name], timeout=30)
    if r.returncode != 0:
        return None
    return {"running": r.stdout.strip().startswith("true")}


def _swf_ensure_container(slot: int) -> str:
    """Start this slot's SeaweedFS gateway container (idempotent; config from topology).

    One weed process binds exactly one metadata store, so each chain owns a container
    rather than sharing slot 0's gateway. Host ports ride the slot band; the container
    listens on the defaults inside its own network namespace. Metadata database must
    already exist (_swf_ensure_meta_db runs first). Credentials are passed through the
    docker environment only — never echoed."""
    r = slot_resources(slot)
    name = r["swf_container"]
    state = _swf_inspect(name)
    if state and state["running"]:
        return "gateway container already running"
    if state:
        sr = _run(["docker", "start", name], timeout=60)
        if sr.returncode != 0:
            raise RuntimeError(f"docker start {name} failed: {sr.stderr[-200:]}")
        return "started existing gateway container"
    _need_docker()
    if not SWF_ENTRYPOINT.is_file():
        raise RuntimeError(f"missing {SWF_ENTRYPOINT} — the launcher is single-source "
                           "for compose and slots (R40); refuse a half-wired slot")
    host = _topology.value("TP_DB_HOST")
    net = _topology.value("TP_SWF_NETWORK")
    cmd = ["docker", "run", "-d", "--restart", "unless-stopped", "--name", name, "--network", net,
           "-v", f"{SWF_ENTRYPOINT}:/swf-entrypoint.sh:ro",
           "-v", f"{_topology.swf_volume_for(slot)}:/data",
           "-e", f"SWF_META_DB_HOST={_topology.container('pg')}",
           "-e", "SWF_META_DB_PORT=5432",
           "-e", f"SWF_META_DB_NAME={r['swf_meta_db']}",
           "-e", f"SWF_META_DB_USER={_env_pair(DB_USER_KEY)}",
           "-e", f"SWF_META_DB_PASSWORD={_env_pair(DB_PASS_KEY)}",
           "-e", f"S3_ACCESS_KEY={_env_pair(S3_ACCESS)}",
           "-e", f"S3_SECRET_KEY={_env_pair(S3_SECRET)}",
           "-p", f"{_topology.slot_port(_topology.base_port('SWF_S3_HOST_PORT', 8333), slot)}:8333",
           # Slot UI ports stay loopback-bound (no unsigned UI on the LAN; the main
           # slot fronts its consoles through the seaweedfs-ui-auth Basic-Auth proxy).
           "-p", f"127.0.0.1:{_topology.slot_port(_topology.base_port('SWF_MASTER_HOST_PORT', 9333), slot)}:9333",
           "-p", f"127.0.0.1:{_topology.slot_port(_topology.base_port('SWF_FILER_HOST_PORT', 8888), slot)}:8888",
           "--label", "tp-managed=1",
           "--label", f"tp-slot={slot}",
           "--entrypoint", "/bin/sh",
           os.environ.get("SWF_IMAGE") or "chrislusf/seaweedfs:4.53",
           "/swf-entrypoint.sh"]
    rr = _run(cmd, timeout=300)
    if rr.returncode != 0:
        raise RuntimeError(f"seaweedfs slot container failed: {rr.stderr[-300:]}")
    return "started gateway container " + name


def _swf_ensure_meta_db(slot: int) -> str:
    """Create the SeaweedFS metadata database (same PG instance, per-slot database)."""
    dbname = slot_resources(slot)["swf_meta_db"]
    exists = _run(["docker", "exec", PG_CONTAINER, "psql", "-U", "testpike", "-d", "postgres",
                   "-tA", "-c", f"SELECT 1 FROM pg_database WHERE datname='{dbname}'"],
                  timeout=60)
    if exists.stdout.strip() == "1":
        return "metadata db already present"
    # C collation is load-bearing: SeaweedFS S3 list order must be byte-lexicographic
    # (a locale collation makes it fall back to a slower COLLATE "C" sort and clients
    # that merge sorted listings may report spurious diffs).
    cr = _run(["docker", "exec", PG_CONTAINER, "psql", "-U", "testpike", "-d", "postgres",
               "-c", f'CREATE DATABASE "{dbname}" OWNER testpike TEMPLATE template0 '
                     "LC_COLLATE 'C' LC_CTYPE 'C' ENCODING 'UTF8'"], timeout=60)
    if cr.returncode != 0 and "already exists" not in cr.stderr:
        raise RuntimeError(f"CREATE DATABASE {dbname} failed: {cr.stderr[-200:]}")
    return f"created metadata db {dbname}"


def _swf_retire_container(slot: int) -> bool:
    r = slot_resources(slot)
    name, vol = r["swf_container"], _topology.swf_volume_for(slot)
    _run(["docker", "rm", "-f", name], timeout=90)
    _run(["docker", "volume", "rm", vol], timeout=60)
    return _swf_inspect(name) is None


def _env_pair(name: str) -> str:
    """Read one credential pair from repo .env without ever printing it."""
    envf = ROOT / ".env"
    for raw in envf.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith(f"{name}="):
            return line.split("=", 1)[1].strip()
    raise RuntimeError(f"{name} missing from .env")


def _swf_apply_provider_fixup(database: str) -> bool:
    pass
    r = _psql(database, "UPDATE tp_sys_config SET config_value='s3' "
                        "WHERE config_key='storage.provider' AND config_value='minio'")
    return r.returncode == 0


def plan_table() -> list:
    """Allocation plan: registered slots (from the readiness ledger) vs free ones."""
    registered = set(registered_slots())
    return [{"slot": n, "status": "registered" if n in registered else "free",
             **{k: v for k, v in slot_resources(n).items() if k != "slot"}}
            for n in range(1, 9)]


def _schema_init_sha() -> str:
    import hashlib
    return hashlib.sha256(SCHEMA_INIT.read_bytes()).hexdigest()


def _template_up_to_date() -> bool:
    if not _db_exists(TEMPLATE_DB):
        return False
    # NB: pg_shdescription columns are (objoid, classoid, description) — there
    # is no objsubid/comment here; guessing those silently errors the query
    # (rc!=0) and would rebuild the template on every single run.
    r = _psql(MAIN_DB, "SELECT sd.description LIKE '%sha256:%" + _schema_init_sha() + "%' "
                       "FROM pg_shdescription sd JOIN pg_database d ON d.oid = sd.objoid "
                       f"WHERE d.datname = '{TEMPLATE_DB}'")
    return r.returncode == 0 and r.stdout.strip() == "t"


def _ensure_template() -> bool:
    """(Re)build the factory template from schema-init.sql.
    Returns True when the template (re)build happened."""
    if not SCHEMA_INIT.exists():
        raise RuntimeError(f"{SCHEMA_INIT} missing — cannot seed the slot schema")
    if _template_up_to_date():
        return False
    if _db_exists(TEMPLATE_DB):
        _psql(MAIN_DB, "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                       f"WHERE datname = '{TEMPLATE_DB}'")
        _psql(MAIN_DB, f'DROP DATABASE "{TEMPLATE_DB}"')
    cr = _psql(MAIN_DB, f'CREATE DATABASE "{TEMPLATE_DB}"')
    if cr.returncode != 0:
        raise RuntimeError(f"template create failed: {cr.stderr[-300:]}")
    _load_sql(TEMPLATE_DB, SCHEMA_INIT)
    # Seed the local-auth admin into the factory itself so every clone
    # inherits a usable login (see _seed_local_admin docstring).
    _seed_local_admin(TEMPLATE_DB)
    note = _psql(MAIN_DB, f"COMMENT ON DATABASE \"{TEMPLATE_DB}\" IS "
                          f"'tp-schema-sha256:{_schema_init_sha()}'")
    if note.returncode != 0:
        raise RuntimeError(f"template stamp failed: {note.stderr[-300:]}")
    return True


def _seed_local_admin(database: str) -> bool:
    """Mirror the slot0 local-auth `admin` row into a factory-cloned slot DB.

    Why this exists: the product seeder (UserSeedInitializer) creates `admin`
    only while tp_user is empty, but the factory schema-init ships five demo
    accounts first — so a fresh slot clone (and a fresh installkit boot) has
    no admin row and no lane login. This step keeps the slot harness usable
    without touching product semantics. Credentials hygiene: the stored
    bcrypt hash is copied row-wise from the main DB over a pipe and never
    printed; the operation is skipped (lane unusable) unless
    ADMIN_INIT_PASSWORD is configured, matching what the services expect.
    Returns True when the row was inserted, False when already present.
    """
    if not os.environ.get("ADMIN_INIT_PASSWORD"):
        raise RuntimeError(
            "ADMIN_INIT_PASSWORD not set in the environment — slot lanes need "
            "the same .env credentials as the dev lane (source .env first)")
    have = _psql(database, "SELECT count(*) FROM tp_user WHERE username='admin'")
    if have.returncode != 0:
        raise RuntimeError(f"admin presence check failed: {have.stderr[-200:]}")
    if have.stdout.strip() != "0":
        return False
    dump = _run(["docker", "exec", "-i", PG_CONTAINER, "psql", "-U", "testpike",
                 "-d", MAIN_DB, "-c",
                 "COPY (SELECT * FROM tp_user WHERE username='admin') TO stdout"])
    if dump.returncode != 0:
        raise RuntimeError(f"admin row export failed: {dump.stderr[-200:]}")
    if not dump.stdout.strip():
        raise RuntimeError(f"{MAIN_DB} has no seed admin row to mirror")
    load = _run(["docker", "exec", "-i", PG_CONTAINER, "psql", "-U", "testpike",
                 "-d", database, "-c", "COPY tp_user FROM stdin"],
                stdin=dump.stdout, timeout=60)
    if load.returncode != 0:
        raise RuntimeError(f"admin seed load failed: {load.stderr[-200:]}")
    return True


def _seed_storage_root(database: str) -> bool:
    """Give a slot database its usable local-storage root.

    The factory seed ships storage.local-root as '' (meaning: not configured),
    so document parsing refuses every upload on a fresh slot. The dev baseline
    runtime convention is the relative path 'data/local-storage', resolved
    against each service's own working tree — slot chains therefore never share
    a directory. Idempotent: an explicit non-empty value (operator choice)
    is never overwritten.
    """
    probe = _psql(database, "SELECT 1 FROM tp_sys_config "
                            "WHERE config_key='storage.local-root' "
                            "AND (config_value IS NULL OR config_value='')")
    if probe.returncode != 0 or probe.stdout.strip() != "1":
        return False  # row missing or operator-set value: never overwrite
    upd = _psql(database, "UPDATE tp_sys_config SET config_value='data/local-storage' "
                          "WHERE config_key='storage.local-root'")
    if upd.returncode != 0:
        raise RuntimeError(f"storage root seed failed: {upd.stderr[-200:]}")
    return True


def init_slot(slot: int, env_out: Path | None = None) -> dict:
    _need_docker()
    if slot <= 0:
        raise RuntimeError("slot 0 is the shared dev baseline; init/drop only manage chain slots >= 1")
    r = slot_resources(slot)
    steps = []

    # 1. PostgreSQL: factory template (rebuilt only when schema-init changed),
    #    then clone the slot database from it.
    if _ensure_template():
        steps.append("rebuilt schema template")
    if not _db_exists(r["database"]):
        cr = _run(["docker", "exec", PG_CONTAINER, "psql", "-U", "testpike", "-d", MAIN_DB,
                   "-tA", "-c", f'CREATE DATABASE "{r["database"]}" TEMPLATE "{TEMPLATE_DB}"'],
                  timeout=180)
        if cr.returncode != 0 and "already exists" not in cr.stderr:
            raise RuntimeError(f"CREATE DATABASE (template clone) failed: {cr.stderr[-300:]}")
        steps.append("cloned slot database from template")
    # Idempotent login seed (no-op once the factory template carries the row).
    if _seed_local_admin(r["database"]):
        steps.append("seeded local-auth admin")
    # Idempotent storage seed: fresh slots must be able to parse uploads.
    if _seed_storage_root(r["database"]):
        steps.append("seeded local storage root")

    # 2. RabbitMQ vhost + permissions on the testpike user.
    rabbit = _rabbit_cli()
    if rabbit is None:
        raise RuntimeError("rabbitmqctl not found inside testpike-rabbitmq")
    if r["mq_vhost"] not in _rabbit_vhosts():
        vr = _run(rabbit + ["add_vhost", r["mq_vhost"]], timeout=60)
        if vr.returncode != 0:
            raise RuntimeError(f"add_vhost failed: {vr.stderr[-300:]}")
        steps.append("created mq vhost")
    pr = _run(rabbit + ["set_permissions", "-p", r["mq_vhost"], "testpike", ".*", ".*", ".*"],
              timeout=60)
    if pr.returncode != 0:
        raise RuntimeError(f"set_permissions failed: {pr.stderr[-300:]}")
    steps.append("granted mq permissions")

    # 2b. Pin the ack deadline on THIS slot's vhost (policy consumer-timeout,
    # read-back-proven; the main vhost is off-limits by construction).
    pin = _pin_vhost_consumer_timeout(r["mq_vhost"])
    steps.append(f"pinned consumer timeout {pin['consumer_timeout_ms']}ms on {pin['vhost']}")

    # 3. Object storage = SeaweedFS: metadata database (same PG instance, per-slot DB)
    #    plus the slot's own gateway container. The bucket itself bootstraps lazily via
    #    the S3 API (S3StorageProvider.ensureBucket), so no CLI dependency here.
    steps.append(_swf_ensure_meta_db(slot))
    steps.append(_swf_ensure_container(slot))
    if _swf_apply_provider_fixup(r["database"]):
        steps.append("aligned storage.provider on slot db")

    # 4. Redis logical db needs no creation (SELECT n is implicit); presence check:
    rr = _run(["docker", "exec", REDIS_CONTAINER, "redis-cli", "-n",
               str(r["redis_db"]), "DBSIZE"], timeout=30)
    if rr.returncode != 0:
        raise RuntimeError(f"redis db index {r['redis_db']} unusable: {rr.stderr[-200:]}")
    steps.append("verified redis logical db")

    # 5. env template output.
    if env_out is not None:
        env_out.write_text(env_template_text(slot), encoding="utf-8")
        steps.append(f"wrote env template -> {env_out}")

    return {"slot": slot, "resources": r, "steps": steps, "residue_targets": residue_targets(slot)}


def drop_guard(slot: int) -> str:
    """Ownership check before destroying anything.

    A slot is a database plus a bucket plus a vhost: dropping it deletes another
    chain's data outright. While allocation trusted the registry, five chains were
    handed slot 1, so a routine "close my slot" from any one of them would have
    wiped the chain actually running there. Refuse unless this repository owns the
    evidence: live ports in the band must belong to this worktree, and no other
    chain's env file may claim the slot.
    """
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import slot_occupancy
    here = str(Path(__file__).resolve().parents[1]).rstrip("/")
    rows = [r for r in slot_occupancy.probe(slots=[slot])]
    if not rows:
        return ""
    row = rows[0]
    table = slot_occupancy.listen_table()
    foreign, mine = [], []
    for port in row["live_ports"]:
        pid, cwd = table.get(port, (0, ""))
        if not cwd:
            continue
        (mine if cwd.rstrip("/") == here else foreign).append(f"port {port} pid {pid} cwd {cwd}")
    if foreign:
        return ("refusing to drop slot %d: %s" % (slot, "; ".join(foreign))
                + " — dropping deletes another chain's database/bucket/vhost. "
                  "Reclaim only your own slot, or stop that chain first.")
    if len(row["env_files"]) > 1 and not mine:
        return (f"refusing to drop slot {slot}: {len(row['env_files'])} chains claim it "
                f"({', '.join(row['env_files'])}) and none of them is this worktree "
                f"({Path(here).name}) - settle the double claim first")
    if len(row["env_files"]) > 1:
        print(f"[WARN] slot {slot} is claimed by {row['env_files']}; proceeding because "
              f"this worktree owns every live port in the band")
    return ""


def drop_slot(slot: int, force_reason: str | None = None) -> dict:
    _need_docker()
    if slot <= 0:
        raise RuntimeError("slot 0 is the shared dev baseline; refusing to drop it")
    blocked = drop_guard(slot)
    if blocked and not force_reason:
        raise RuntimeError(blocked + " (pass --force REASON only when ownership is proven)")
    r = slot_resources(slot)
    # Fourth retirement proof : a slot dropped while its vhost still holds
    # messages strands that work onto whichever executor reads next — the 11-04
    # 907342 residue. Block until drained; --force only with an attributed reason.
    gate = backlog_gate(slot, r["mq_vhost"])
    if gate and not force_reason:
        raise RuntimeError(gate)
    if gate:
        print(f"[WARN] backlog gate overridden for slot {slot} ({gate}); "
              f"recorded reason: {force_reason}")
    gone = {}

    _psql(MAIN_DB, f'CHECKPOINT')
    dr = _psql(MAIN_DB, f'SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = \'{r["database"]}\'')
    qr = _psql(MAIN_DB, f'DROP DATABASE IF EXISTS "{r["database"]}"')
    gone["pg_database"] = qr.returncode == 0 and not _db_exists(r["database"])

    rabbit = _rabbit_cli()
    if rabbit is not None and r["mq_vhost"] in _rabbit_vhosts():
        vr = _run(rabbit + ["delete_vhost", r["mq_vhost"]], timeout=60)
        gone["mq_vhost"] = vr.returncode == 0 and r["mq_vhost"] not in _rabbit_vhosts()
    else:
        vr = None
        gone["mq_vhost"] = rabbit is not None and r["mq_vhost"] not in _rabbit_vhosts()

    # Storage plane teardown order: gateway container + its data volume first (it holds
    # connections to the metadata DB), then the metadata DB itself. The bucket namespace
    # lives inside that database, so proving the DB gone proves the bucket gone.
    gone["swf_container"] = _swf_retire_container(slot)
    _psql(MAIN_DB, "CHECKPOINT")
    _psql(MAIN_DB, "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                   f"WHERE datname = '{r['swf_meta_db']}'")
    mr = _psql(MAIN_DB, f'DROP DATABASE IF EXISTS "{r["swf_meta_db"]}"')
    gone["swf_meta_db"] = (mr.returncode == 0
                           and "SELECT 1 FROM pg_database WHERE datname='"
                           + r["swf_meta_db"] + "'" not in mr.stderr)
    if not gone["swf_meta_db"]:
        chk = _run(["docker", "exec", PG_CONTAINER, "psql", "-U", "testpike", "-d", "postgres",
                    "-tA", "-c", f"SELECT 1 FROM pg_database WHERE datname='{r['swf_meta_db']}'"],
                   timeout=30)
        gone["swf_meta_db"] = chk.stdout.strip() != "1"
    gone["s3_bucket"] = gone["swf_meta_db"] and gone["swf_container"]

    fr = _run(["docker", "exec", _topology.container("redis"), "redis-cli", "-n",
               str(r["redis_db"]), "FLUSHDB", "ASYNC"], timeout=30)
    sz = _run(["docker", "exec", _topology.container("redis"), "redis-cli", "-n",
               str(r["redis_db"]), "DBSIZE"], timeout=30)
    gone["redis_db_flushed"] = fr.returncode == 0 and (sz.stdout.strip() in ("0", ""))

    # Releasing the chain's claim on this slot is part of destroying it: a stale env file
    # keeps the slot looking occupied to every later allocation and blocks its reuse.
    released = []
    for claim in sorted(_topology.slot_env_dir().glob(f"*.slot{slot}.env")):
        claim.rename(claim.with_name(claim.name + ".released"))
        released.append(claim.name)
    gone["env_claims_released"] = released

    residue = [t for ok, t in [
        (gone["pg_database"], r["database"]),
        (gone["mq_vhost"], r["mq_vhost"]),
        (gone["s3_bucket"], f"bucket {r['s3_bucket']}"),
        (gone["swf_meta_db"], r["swf_meta_db"]),
        (gone["swf_container"], r["swf_container"]),
        (gone["redis_db_flushed"], f"redis db {r['redis_db']}"),
    ] if not ok]
    return {"slot": slot, "gone": gone, "residue": residue,
            "vhost_delete_rc": vr.returncode if vr is not None else 0}


def selftest() -> int:
    """Two-sided: a foreign holder blocks, this worktree's own holder does not.

    The fixture redirects TP_SLOT_ENV_DIR (the supported override) rather than rebinding a
    module global the probe no longer reads, and derives both the slot's port and the
    foreign worktree path from the topology, so nothing here names a project or a chain.
    """
    import tempfile
    import slot_occupancy

    here = str(Path(__file__).resolve().parents[1])
    slot = 3
    port = _topology.slot_port(_topology.shipped_base("ADMIN_PORT"), slot)
    foreign_dir = f"{Path(here).parent}/{_topology.worktree_prefix()}other"
    keep_env = os.environ.get("TP_SLOT_ENV_DIR")
    keep_listen, keep_probe = slot_occupancy.listen_table, slot_occupancy.db_slot_present
    with tempfile.TemporaryDirectory() as workdir:
        envdir = Path(workdir)
        os.environ["TP_SLOT_ENV_DIR"] = str(envdir)
        _topology.reset_env_cache()
        (envdir / "mine.slot3.env").write_text("DB_URL=x\n")
        slot_occupancy.db_slot_present = lambda s: True
        try:
            slot_occupancy.listen_table = lambda: {port: (111, foreign_dir)}
            blocked = drop_guard(slot)
            assert "refusing to drop slot 3" in blocked, f"foreign holder must block: {blocked}"
            assert "other" in blocked, f"the block message must name the holder: {blocked}"

            slot_occupancy.listen_table = lambda: {port: (222, here)}
            assert drop_guard(slot) == "", "own holder must not block"

            (envdir / "other.slot3.env").write_text("DB_URL=x\n")
            slot_occupancy.listen_table = lambda: {}
            double = drop_guard(slot)
            assert "chains claim it" in double, f"double claim with no live owner must block: {double}"

            slot_occupancy.listen_table = lambda: {port: (222, here)}
            drop_guard(slot)   # own ports + double claim => warns (printed) but allows

            slot_occupancy.listen_table = lambda: {}
            slot_occupancy.db_slot_present = lambda s: False
            (envdir / "solo.slot4.env").write_text("DB_URL=x\n")
            assert drop_guard(4) == "", "an unheld slot is free to manage"

            # claim seam, two-sided: ensure_claim registers a missing claim under the
            # TOPOLOGY name and never rewrites a live one; chain_token_for_tree derives
            # the token from the worktree prefix without naming any concrete chain.
            made = ensure_claim("fxtok", 6)
            assert made.name == "fxtok.slot6.env" and made.is_file(), made
            made.write_text("DB_URL=kept\n")
            ensure_claim("fxtok", 6)
            assert made.read_text() == "DB_URL=kept\n", "a live claim must never be rewritten"
            fx_tree = Path(workdir) / f"{_topology.worktree_prefix()}fxtree"
            fx_tree.mkdir()
            assert chain_token_for_tree(fx_tree) == "fxtree", "tree token from topology prefix"
            assert chain_token_for_tree(Path(workdir) / "not-a-worktree") is None

            # configure identity guard, two-sided (11-14 fix verification): an env
            # registered to another chain must block WITHOUT --force and must pass
            # WITH force=True, recording the attributed identity switch in the
            # report. The force branch previously was dead code (raised either way).
            # dry_run keeps the fixture free of docker/claim side effects.
            (fx_tree / ".env").write_text("TP_CHAIN_NAME=otherchain\nTP_CHAIN_SLOT=0\n")
            try:
                configure_chain_env(fx_tree, "fxtree", 5, dry_run=True, force=False)
                raise AssertionError("identity mismatch must block without --force")
            except RuntimeError as exc:
                assert "otherchain" in str(exc), f"block message must name the holder: {exc}"
            rep_forced = configure_chain_env(fx_tree, "fxtree", 5, dry_run=True, force=True)
            assert rep_forced.get("forced_identity", "").startswith("otherchain"), \
                f"--force must record the switch: {rep_forced}"
            assert rep_forced.get("written") is False, "dry_run must write nothing"
            (fx_tree / ".env").unlink()

            # distinctness face, two-sided (11-14): only registered chains join the
            # comparison. A scratch R41 tree without .env or without a claimed slot
            # must never duplicate slot0 into a permanently red gate; two real chains
            # sharing one database must still be reported. The fake main tree lives
            # entirely in the temp dir — the repo's own .env is never touched.
            keep_siblings = globals().get("_sibling_trees")
            keep_claim_file = globals().get("claim_file")
            fake_main = Path(workdir) / "fake-main"
            claims_dir = Path(workdir) / "claims"
            try:
                globals()["claim_file"] = lambda chain, slot: claims_dir / f"{chain}.slot{slot}.env"
                fake_main.mkdir()
                (fake_main / ".env").write_text(
                    "TP_CHAIN_SLOT=0\nDB_URL=jdbc:postgresql://localhost:5432/shared_db\n")
                scratch = Path(workdir) / "scratch-tree"
                scratch.mkdir()
                chain_b = Path(workdir) / "chain-b-tree"
                chain_b.mkdir()
                globals()["_sibling_trees"] = lambda root=None: [fake_main, scratch, chain_b]
                assert check_env_distinctness() == [], \
                    "scratch trees (no .env / no slot) must not collide with slot0"
                (scratch / ".env").write_text(
                    "TP_CHAIN_SLOT=5\nTP_CHAIN_NAME=scratch-claimant\nDB_URL=shared\n")
                (chain_b / ".env").write_text(
                    "TP_CHAIN_SLOT=6\nTP_CHAIN_NAME=chain-b\nDB_URL=shared\n")
                # both declare slots, neither holds a live claim (11-18 ledger-drift
                # family): drift is skipped, never a phantom duplicate red.
                assert check_env_distinctness() == [], \
                    f"drifted declarations must not red-flag: {check_env_distinctness()}"
                claims_dir.mkdir()
                (claims_dir / "chain-b.slot6.env").write_text("claimed\n")
                assert check_env_distinctness() == [], \
                    "a claim holder must not collide with a drifted declaration"
                # hard invariant kept: two live claim holders sharing one db still red.
                (claims_dir / "scratch-claimant.slot5.env").write_text("claimed\n")
                assert any("shared" in b for b in check_env_distinctness()), \
                    "two claimed chains sharing one db must be reported"
            finally:
                if keep_siblings is not None:
                    globals()["_sibling_trees"] = keep_siblings
                if keep_claim_file is not None:
                    globals()["claim_file"] = keep_claim_file

            # slot resolution is identity-driven, two-sided: with a stale sibling present the
            # tree's own TP_CHAIN_SLOT must win over glob order (measured regression: sorted()
            # picked slot2 while the tree declared slot3), and a declared-but-missing claim
            # must fail loudly instead of silently resolving onto another slot.
            keep_wt = _topology.is_worktree
            _topology.is_worktree = lambda cwd=None: True
            try:
                (tree := fx_tree)
                (tree / ".env").write_text("TP_CHAIN_NAME=fxtree\nTP_CHAIN_SLOT=3\n")
                (envdir / "fxtree.slot2.env").write_text("DB_URL=stale\n")
                (envdir / "fxtree.slot3.env").write_text("DB_URL=identity\n")
                resolved = env_path_for(chain="fxtree", create=False, cwd=tree)
                assert resolved.name == "fxtree.slot3.env", \
                    f"declared slot must win over glob order: {resolved}"
                (envdir / "fxtree.slot3.env").unlink()
                try:
                    env_path_for(chain="fxtree", create=False, cwd=tree)
                    raise AssertionError("missing identity claim must fail, not take a sibling")
                except RuntimeError:
                    pass
                resolved = env_path_for(chain="fxtree", create=True, cwd=tree)
                assert resolved.name == "fxtree.slot3.env" and resolved.is_file(), \
                    f"create must materialise the declared slot: {resolved}"
            finally:
                _topology.is_worktree = keep_wt

            # ack-deadline floor : the factory
            # face must sit at >= 2x the current 7200 s extraction deadline
            # (V487), and an explicit override must read back verbatim
            # (never folded away).
            keep_ack = os.environ.pop("TP_MQ_ACK_TIMEOUT_MS", None)
            try:
                assert consumer_timeout_ms() >= 2 * 7200 * 1000, \
                    "factory ack deadline must stay >= 2x the extraction deadline"
                os.environ["TP_MQ_ACK_TIMEOUT_MS"] = "60000"
                assert consumer_timeout_ms() == 60000, \
                    "an explicit override reads back verbatim"
            finally:
                if keep_ack is not None:
                    os.environ["TP_MQ_ACK_TIMEOUT_MS"] = keep_ack
                else:
                    os.environ.pop("TP_MQ_ACK_TIMEOUT_MS", None)

            # a released claim must not keep the slot occupied for the guard
            (envdir / "ghost.slot5.env").write_text("DB_URL=x\n")
            assert drop_guard(5) == "", "a fresh claim with no holder is free to manage"
            (envdir / "ghost.slot5.env").rename(envdir / "ghost.slot5.env.released")
            assert drop_guard(5) == "", "a released claim no longer blocks"

            # retirement gate, fourth proof = this slot's vhost has zero backlog.
            # Two-sided with an injected account so the fixture never touches a broker.
            stuck = {"unknown": False, "total": 9, "queues": [
                {"name": "tp.dispatch", "ready": 7, "unacked": 2,
                 "consumers": 1, "channels": ["<0.1.2>"]}]}
            drained = {"unknown": False, "total": 0, "queues": [
                {"name": "tp.dispatch", "ready": 0, "unacked": 0,
                 "consumers": 0, "channels": []}]}
            blind = {"unknown": True, "reason": "HTTP 401", "total": 0, "queues": []}
            blocked = backlog_gate(3, "/tp-slot3", account=stuck)
            assert "tp.dispatch ready=7 unacked=2" in blocked, \
                f"non-zero backlog must name queue+counts: {blocked}"
            assert "<0.1.2>" in blocked, \
                f"the block message must carry the consumer attribution hint: {blocked}"
            assert backlog_gate(3, "/tp-slot3", account=drained) == "", \
                "a drained vhost must open the gate"
            unreadable = backlog_gate(3, "/tp-slot3", account=blind)
            assert "unreadable" in unreadable, \
                f"fail-closed: an unreadable backlog must NOT read as zero: {unreadable}"
            assert backlog_gate(3, "/", account=stuck) == "", \
                "the main shared vhost is never judged or touched by a slot drop"
            # Absent-vhost judgement, two-sided: the management API 404 on a
            # never-created vhost must read as trivially drained (measured
            # 2026-11-12: it was folded into unknown and blocked a clean
            # retirement), while any other HTTP error stays fail-closed.
            import urllib.request as _ur, urllib.error as _ue
            from email.message import Message as _Msg
            _real_urlopen = _ur.urlopen
            def _raise_http(code):
                def _fake(*_a, **_k):
                    raise _ue.HTTPError("http://mgmt/api/queues", code, "denied", _Msg(), None)
                return _fake
            try:
                _ur.urlopen = _raise_http(404)
                absent = vhost_backlog("/tp-slot9")
                assert absent["unknown"] is False and absent["total"] == 0 \
                    and absent["queues"] == [], \
                    f"404 on an absent vhost must read drained: {absent}"
                assert backlog_gate(9, "/tp-slot9") == "", \
                    "an absent vhost opens the retirement gate"
                _ur.urlopen = _raise_http(503)
                broken = vhost_backlog("/tp-slot9")
                assert broken["unknown"] is True, \
                    "a non-404 HTTP error must stay unknown (fail-closed)"
            finally:
                _ur.urlopen = _real_urlopen
            try:
                purge_slot_queues(0)
                raise AssertionError("slot 0 purge must be refused outright")
            except RuntimeError as e:
                assert "never purge" in str(e), e

            # step 2, two-sided: an issued slot env must pin the tooling base-URL
            # family to this slot's own band (a probe run from the tree otherwise derives
            # from port keys alone, and one missing export silently falls to the shipped
            # main-slot literal — the cross-chain side-channel the isolation batch
            # registered), and the issue checklist must catch a base that drifted onto
            # another slot's port while the raw port keys stay correct.
            tmpl = env_template_text(3)
            ports3 = {k: _topology.slot_port(_topology.base_port(k, b), 3)
                      for k, b in _topology.SERVICE_PORTS}
            for key, port_key in _topology.BASE_URL_FAMILY:
                want = f"{key}=http://localhost:{ports3[port_key]}"
                assert want in tmpl.splitlines(), \
                    f"slot env must export the derived base {want}: {tmpl}"
            fx = Path(workdir) / "envcheck"
            fx.mkdir()
            lines = [f"TP_CHAIN_SLOT=3",
                     f"DB_URL=jdbc:postgresql://localhost:15432/{_topology.slot_database(3)}",
                     f"REDIS_DATABASE={_topology.redis_db_for(3)}",
                     f"MQ_VHOST={_topology.vhost_for(3)}",
                     f"S3_BUCKET={_topology.bucket_for(3)}",
                     f"SWF_META_DB_NAME={_topology.swf_meta_database(3)}"]
            lines += [f"{k}={ports3[k]}" for k, _ in _topology.SERVICE_PORTS]
            lines += [f"{k}=http://localhost:{ports3[pk]}"
                      for k, pk in _topology.BASE_URL_FAMILY]
            (fx / ".env").write_text("\n".join(lines) + "\n", encoding="utf-8")
            assert verify_chain_env(fx) == [], \
                f"a base family on the slot's own band must pass: {verify_chain_env(fx)}"
            (fx / ".env").write_text(
                (fx / ".env").read_text(encoding="utf-8")
                .replace(f"TP_ADMIN_BASE=http://localhost:{ports3['ADMIN_PORT']}",
                         "TP_ADMIN_BASE=http://localhost:8081"),
                encoding="utf-8")
            bad = verify_chain_env(fx)
            assert any("TP_ADMIN_BASE" in m for m in bad), \
                f"a base pointing at another slot's port must be caught: {bad}"
        finally:
            slot_occupancy.listen_table, slot_occupancy.db_slot_present = keep_listen, keep_probe
            if keep_env is None:
                os.environ.pop("TP_SLOT_ENV_DIR", None)
            else:
                os.environ["TP_SLOT_ENV_DIR"] = keep_env
            _topology.reset_env_cache()

    # Tree-scan invariant, two-sided: every tree of the repo appears exactly once and the
    # scan set does not depend on which tree invokes it. The old shape self-collided (worktree
    # listed twice, main tree missing) whenever the tool ran from a worktree, so the
    # distinctness gate was permanently red there — this case is what proves the fix executes.
    from_main = _sibling_trees()
    resolved = [str(p.resolve()) for p in from_main]
    assert len(resolved) == len(set(resolved)), f"tree scan must list each tree once: {resolved}"
    others = [p for p in resolved if p != str(ROOT.resolve())]
    if others:
        from_other = [str(p.resolve()) for p in _sibling_trees(others[0])]
        assert sorted(from_other) == sorted(resolved), \
            f"scan set must be invocation-independent: from {others[0]} -> {from_other} vs {resolved}"
    # consumer-timeout pin, two-sided: a slot vhost must reach set_policy AND
    # prove it by a matching read-back (2xx alone never passes — the silent-204
    # shape of this broker is measured); a missing or mismatched read-back refuses
    # the pin; the shared main vhost is refused before any transport call.
    pin_calls = []
    import types

    def _PinR(out=""):
        return types.SimpleNamespace(returncode=0, stdout=out, stderr="")

    def runner_ok(cmd, timeout=60):
        pin_calls.append(list(cmd))
        return _PinR(f"{POLICY_NAME}\t" + json.dumps({"consumer-timeout": 60000}) + "\n")

    got = _pin_vhost_consumer_timeout(_topology.vhost_for(7), runner=runner_ok, ms=60000)
    assert got["consumer_timeout_ms"] == 60000, got
    joined = " ".join(" ".join(str(c) for c in cmd) for cmd in pin_calls)
    assert "set_policy" in joined and f"-p {_topology.vhost_for(7)}" in joined, joined
    assert json.dumps({"consumer-timeout": 60000}) in joined, joined
    main_calls = len(pin_calls)

    for label, fake in (("empty", lambda cmd, timeout=60: _PinR("")),
                        ("mismatch", lambda cmd, timeout=60:
                         _PinR(f"{POLICY_NAME}\t" + json.dumps({"consumer-timeout": 30})))):
        try:
            _pin_vhost_consumer_timeout(_topology.vhost_for(8), runner=fake, ms=60000)
            raise AssertionError(f"a {label} read-back must refuse the pin")
        except RuntimeError as exc:
            assert "read-back" in str(exc), exc

    try:
        _pin_vhost_consumer_timeout("/", runner=runner_ok, ms=60000)
        raise AssertionError("the main vhost must never be pinned")
    except RuntimeError as exc:
        assert "shared main vhost" in str(exc), exc
    assert len(pin_calls) == main_calls, "the '/' refusal must not touch the transport"

    # The compose app tier reaches the database through APP_DB_URL (inside the stack the
    # server is addressed by container name, so the host-side DB_URL value cannot cross
    # that boundary). A slot env exporting only DB_URL lets every slot chain's containers
    # silently boot on the shipped main-slot database while the chain's own tooling probes
    # the slot database — measured live 2026-11-22 (startup seed no-op branch ran against
    # the main database). The alias must join the isolated key set, derived per slot.
    tmpl_alias = env_template_text(3)
    alias_line = next((l for l in tmpl_alias.splitlines() if l.startswith("APP_DB_URL=")), "")
    assert alias_line.endswith("/" + _topology.slot_database(3)), \
        f"slot env must export the compose DB alias on its own database: {alias_line!r}"
    assert _topology.container("pg") in alias_line, \
        f"the alias must address the stack's pg container name: {alias_line!r}"
    assert "APP_DB_URL" in slot_env_keys(), \
        "the compose alias must join the slot key set the main chain strips"

    # The compose app tier's host mappings (APP_*_HOST_PORT) must join the same
    # per-slot export: unset, `compose up` silently binds the shipped defaults
    # and collides with another plane's ports on the shared host (measured live
    # 2026-11-22: slot4 ai-service bind 18084 address-in-use, app tier stuck in
    # Created). Values are pinned to this slot's band from the same single source.
    host_map = {"APP_ADMIN_HOST_PORT": "ADMIN_PORT", "APP_API_HOST_PORT": "API_PORT",
                "APP_CORE_HOST_PORT": "CORE_PORT", "APP_AISERVICE_HOST_PORT": "AISERVICE_PORT"}
    base_of = dict(_topology.SERVICE_PORTS)
    exported = dict(l.split("=", 1) for l in tmpl_alias.splitlines()
                    if "=" in l and l.split("=", 1)[0] in host_map)
    for compose_key, port_key in host_map.items():
        want = _topology.slot_port(_topology.base_port(port_key, base_of[port_key]), 3)
        assert exported.get(compose_key, "").strip() == str(want), \
            f"slot env must export {compose_key}={want} (this slot's band), got {exported.get(compose_key)!r}"
        assert compose_key in slot_env_keys(), \
            f"{compose_key} must join the slot key set the main chain strips"

    print("[selftest] two-sided PASS (foreign holder blocks and names it, own holder passes, "
          "double claim without ownership blocks, unheld slot is free, released claim ignored, "
          "consumer-timeout pin needs read-back proof and never touches '/', "
          f"tree scan lists {len(resolved)} trees once from any invocation)")
    return 0


def slot_value(key: str, default: str = "", env_file: str | None = None, cwd=None) -> str:
    """One answer for "what does this chain's slot say about <key>".

    Recipes used to grep the slot env themselves, so every copy could drift. Keys are read
    from the resolved slot env; DB_NAME is derived from DB_URL there, because the database
    name lives inside the URL rather than in its own key.
    """
    path = Path(env_file) if env_file else None
    if path is None:
        try:
            path = env_path_for(cwd=cwd, create=False)
        except Exception:
            path = None
    values = {}
    if path and path.is_file():
        for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                values[k.strip()] = v.strip().strip('"').strip("'")
    if key == "DB_NAME":
        url = values.get("DB_URL", "")
        return url.rsplit("/", 1)[-1] if url else default
    return values.get(key) or default




def current_slot(cwd=None, default_main: int = 0) -> int:
    """Which slot this checkout runs in: 0 for the main tree, N for a claimed slot env.

    A worktree with no resolvable slot env raises with the reason instead of returning 0:
    guessing slot 0 makes a slot chain probe the main chain's units and report them healthy.
    """
    base = Path(cwd) if cwd else Path.cwd()
    try:
        path = env_path_for(cwd=base, create=False)
    except Exception as exc:
        if not _topology.is_worktree(cwd=base):
            return default_main
        raise RuntimeError(
            f"failed to resolve this chain slot env ({type(exc).__name__}: {str(exc)[:80]}); "
            f"run python3 scripts/chain_slot.py env-path to claim a slot first, never guess slot0") from exc
    if not path:
        return default_main
    m = re.search(r"\.slot(\d+)\.env", str(path))
    return int(m.group(1)) if m else default_main



def check_chain_name_style(token: str, cwd=None) -> str:
    """New chains are named by purpose, not by a ticket number.

    Bookkeeping codes in a chain name leak into paths, unit names and log anchors, which is the
    same defect the identifier rule bans inside code. Existing trees are only warned about -
    renaming a live chain's directory would sever the recipes that call it by path.
    """
    import re as _re
    strict = os.environ.get("TP_STRICT_CHAIN_NAMES", "0") == "1"
    looks_like_ticket = bool(_re.search(r"(?:^|[-_])t\d{2,4}", (token or "").lower()))
    if not looks_like_ticket:
        return ""
    msg = (f"chain-name-style: chain 「{token}」 is named after a ticket number, leaking bookkeeping into paths and unit names; "
           "name new chains by purpose (e.g. audit-rebuild / i18n-gate); existing directories get renamed when their chain retires")
    if strict:
        raise RuntimeError(msg + " (creation refused: TP_STRICT_CHAIN_NAMES=1)")
    print(f"[WARN] {msg}", file=sys.stderr)
    return msg

def env_path_for(chain: str | None = None, create: bool = True,
                 registry: Path = REGISTRY, cwd=None) -> Path:
    """Where one chain's slot env lives, allocating a host-free slot if it has none.

    Recipes must resolve their slot through here instead of hard-coding a slot number
    or another chain's absolute worktree: every hard-coded `.env.slot1` in tools/ was a
    latent collision, and a script pointing at someone else's worktree is a cross-chain
    side effect waiting to happen.

    The chain token comes from the topology (which reads the repository name from the
    environment), never from a prefix typed into this file. The main chain is refused on
    purpose: it IS slot 0, so "resolve my slot env" must not quietly issue it a slot of
    its own - an earlier version did exactly that and built an unused database, vhost,
    bucket and cache index for the base checkout.

    Layout: <slot env dir>/<chain>.slot<N>.env (N from host truth).
    """
    if chain is None:
        chain = _topology.chain_token(cwd)
    if not _topology.is_worktree(cwd):
        raise RuntimeError(
            f"{chain!r} resolves outside a worktree ({_topology.repo_name()} is the main "
            "chain): it runs on slot 0, the resident baseline, and must not be issued a "
            "slot env. Only a worktree chain may allocate one.")
    env_dir = _topology.slot_env_dir()
    env_dir.mkdir(parents=True, exist_ok=True)
    live = sorted(env_dir.glob(f"{chain}.slot*.env"))
    live = [f for f in live if not f.name.endswith(".released")]
    # Identity wins over glob order. A double-issued chain leaves sibling files behind and
    # sorted()[0] silently resolved the chain onto its stale slot (measured 2026-11-06:
    # slot2 beat slot3 alphabetically while the tree's own .env declared slot 3), which is
    # the cross-slot misread the isolation rule bans. The declared slot lives in this
    # tree's .env — the single configuration source.
    tree_env = (Path(cwd) if cwd else Path.cwd()) / ".env"
    declared = None
    if tree_env.is_file() and not tree_env.is_symlink():
        m = re.search(r"^TP_CHAIN_SLOT=(\d+)",
                      tree_env.read_text(encoding="utf-8", errors="ignore"), re.M)
        declared = int(m.group(1)) if m else None
    if declared:
        wanted = env_dir / f"{chain}.slot{declared}.env"
        stale = [f for f in live if f != wanted]
        if stale:
            print("[WARN] chain " + repr(chain) + f" declares slot {declared} but stale slot env leftovers exist: "
                  + ", ".join(f.name for f in stale)
                  + " -- resolution follows the identity slot; delete the leftovers", file=sys.stderr)
        if wanted.is_file():
            return wanted
        if not create:
            raise RuntimeError(
                f"chain {chain!r} declares slot {declared} but its slot env is missing ({wanted.name}); "
                "run chain_slot.py configure --tree <this chain tree> or env-path to re-register, never fall back to another slot")
        wanted.write_text(env_template_text(declared), encoding="utf-8")
        return wanted
    if live:
        if len(live) > 1:
            print("[WARN] chain " + repr(chain) + " has no declared identity slot and multiple slot env files exist: "
                  + ", ".join(f.name for f in live)
                  + " -- using the newest mtime this run; write TP_CHAIN_SLOT into .env to pin the identity first", file=sys.stderr)
            return max(live, key=lambda f: f.stat().st_mtime)
        return live[0]
    if not create:
        raise RuntimeError(f"no slot env registered for chain {chain!r}")
    slot = allocate_free_slot(registry)
    if slot is None:
        raise RuntimeError("slot pool exhausted by host truth; retire a slot first")
    target = _topology.slot_env_path(chain, slot)
    target.write_text(env_template_text(slot), encoding="utf-8")
    init_slot(slot)
    return target


def main() -> int:
    summary = (__doc__ or "chain slot tooling").splitlines()[0]
    ap = argparse.ArgumentParser(description=summary)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p_init = sub.add_parser("init", help="create/repair one chain slot (idempotent)")
    p_init.add_argument("slot", type=int)
    p_init.add_argument("--env-out", type=Path, default=None, help="write the slot env template here")
    p_init.add_argument("--tree", type=Path, default=None,
                        help="also materialise this chain's own .env (single config source)")
    p_drop = sub.add_parser("drop", help="destroy one chain slot and prove zero residue")
    p_drop.add_argument("slot", type=int)
    p_drop.add_argument("--force", metavar="REASON", default=None,
                        help="override the ownership guard, with a recorded reason")
    p_backlog = sub.add_parser("backlog",
                               help="read one slot's vhost queue backlog (ready+unacked) via the mgmt API")
    p_backlog.add_argument("slot", type=int)
    p_purge = sub.add_parser("purge",
                             help="drain ONLY this slot's vhost queues (legit channel before drop)")
    p_purge.add_argument("slot", type=int)
    p_purge.add_argument("--force", metavar="REASON", required=True,
                         help="why the queues are being discarded (recorded)")
    sub.add_parser("plan", help="print the slot allocation table (registry view)")
    p_conf = sub.add_parser("configure",
                            help="write this chain's own .env (single configuration source)")
    p_conf.add_argument("--tree", type=Path, default=Path.cwd(),
                        help="chain checkout to configure (default: current directory)")
    p_conf.add_argument("--chain", default=None, help="chain token (default: from the tree name)")
    p_conf.add_argument("--slot", type=int, default=None,
                        help="slot to bind (default: allocate by live occupancy)")
    p_conf.add_argument("--dry-run", action="store_true", help="print the plan, write nothing")
    p_conf.add_argument("--force", metavar="REASON", default=None,
                        help="re-assign a file already registered to another chain")
    sub.add_parser("distinct", help="show each chain's own .env keys and report collisions")
    p_ver = sub.add_parser("verify-env", help="check a chain's own .env is self-consistent")
    p_ver.add_argument("--tree", type=Path, default=Path.cwd())
    sub.add_parser("live", help="print slot occupancy as the host actually reports it")
    sub.add_parser("selftest", help="two-sided guard test (no docker side effects)")
    p_env = sub.add_parser("env-path",
                           help="print this chain's slot env path (allocating one if absent)")
    p_env.add_argument("--chain", default=None,
                       help="chain token (default: derived from the working directory)")
    p_env.add_argument("--no-create", action="store_true",
                       help="fail instead of allocating a new slot")
    p_env.add_argument(
        "--optional-main", action="store_true",
        help="print nothing and exit 0 when this tree IS the main chain (slot 0): "
             "the resident baseline loads its own .env, there is no slot overlay to "
             "resolve. Windows/recipes use this instead of hard-failing on slot 0.")
    p_val = sub.add_parser("slot-value",
                           help="print one key of this chain's slot env (or the given default)")
    p_val.add_argument("key")
    p_val.add_argument("--default", default="", help="value to use when there is no slot env")
    p_val.add_argument("--env", default=None, help="explicit slot env file (default: resolve)")
    args = ap.parse_args()
    if args.cmd == "verify-env":
        bad = verify_chain_env(args.tree)
        if bad:
            for b in bad:
                print(f"  ✗ {b}")
            return 1
        print(f"[verify-env] {args.tree} .env is self-consistent (identity slot = database slot = port band)")
        return 0
    args = ap.parse_args()
    if args.cmd == "configure":
        rep = configure_chain_env(args.tree, args.chain, args.slot, args.dry_run,
                                  force=bool(args.force))
        if args.force:
            print(f"[configure] --force reason: {args.force}")
        print(json.dumps(rep, ensure_ascii=False, indent=1))
        if rep.get("conflicts"):
            print("[configure] ✗ resources still overlap other chains:", rep["conflicts"])
            return 1
        print(f"CHAIN_ENV_OK tree={rep['tree']} slot={rep['slot']}")
        return 0
    if args.cmd == "distinct":
        bad = check_env_distinctness()
        for t in _sibling_trees():
            f = t / ".env"
            text = f.read_text(encoding="utf-8", errors="ignore") if f.is_file() else ""
            get = lambda k: (re.search(rf"^{k}=(.*)$", text, re.M) or [None, "-"])[1]
            print(f"  {t.name:<34} slot={get('TP_CHAIN_SLOT') or '0'} db={get('DB_URL').split('/')[-1] if get('DB_URL') != '-' else '-':<18} "
                  f"admin={get('ADMIN_PORT') or '-':<6} core={get('CORE_PORT') or '-':<6} "
                  f"redis={get('REDIS_DATABASE') or '-':<3} vhost={get('MQ_VHOST') or '-':<10} "
                  f"bucket={get('S3_BUCKET') or '-':<14} "
                  f"swfdb={get('SWF_META_DB_NAME') or '-':<16} {'SYMLINK✗' if f.is_symlink() else ''}")
        if bad:
            print("[distinct] ✗", "; ".join(bad))
            return 1
        print("[distinct] every chain .env is a real file and resource keys are pairwise distinct")
        return 0
    if args.cmd == "slot-value":
        print(slot_value(args.key, args.default, args.env))
        return 0
    if args.cmd == "env-path":
        if args.optional_main and not _topology.is_worktree():
            # Main chain = slot 0: no overlay file exists and none should be
            # created, so an empty stdout with rc 0 is the honest answer.
            return 0
        print(env_path_for(args.chain, create=not args.no_create))
        return 0

    if args.cmd == "live":
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        import slot_occupancy
        for row in slot_occupancy.probe():
            print(f"slot {row['slot']:>1} [{'OCCUPIED' if row['occupied'] else 'free':>8}] "
                  f"ports={row['live_ports'] or '-'} db={slot_occupancy.db_label(row)} "
                  f"env={row['env_files'] or '-'} holders={row['holders'] or '-'}")
        return 0
    if args.cmd == "selftest":
        return selftest()
    if args.cmd == "backlog":
        vhost = _topology.vhost_for(args.slot)
        acct = vhost_backlog(vhost)
        print(json.dumps(acct, ensure_ascii=False, indent=1))
        if acct["unknown"]:
            print(f"BACKLOG_UNKNOWN slot={args.slot} vhost={vhost} ({acct['reason']}) — "
                  f"fail-closed, drop/purge refuse until readable")
            return 1
        print(f"BACKLOG slot={args.slot} vhost={vhost} total={acct['total']}")
        return 1 if acct["total"] > 0 else 0
    if args.cmd == "purge":
        print(f"[purge] reason recorded: {args.force}")
        rep = purge_slot_queues(args.slot)
        print(json.dumps(rep, ensure_ascii=False, indent=1))
        after = rep["after"]
        if after["unknown"] or after["total"] > 0:
            print(f"SLOT_PURGE_INCOMPLETE slot={args.slot} after={after}")
            return 1
        print(f"SLOT_PURGE_OK slot={args.slot} vhost={rep['vhost']} drained queues={len(rep['purged_queues'])}")
        return 0
    if args.cmd == "plan":
        for row in plan_table():
            print(f"slot {row['slot']:>1} [{row['status']:>10}] db={row['database']} redis={row['redis_db']} "
                  f"vhost={row['mq_vhost']} bucket={row['s3_bucket']} swfdb={row['swf_meta_db']} port+{row['port_base']}")
        return 0
    if args.cmd == "init":
        result = init_slot(args.slot, args.env_out)
        if getattr(args, "tree", None) is not None:
            # a chain that has resources but no own .env would still read the main slot's file
            rep = configure_chain_env(args.tree, slot=args.slot)
            result["chain_env"] = {"tree": rep["tree"], "written": rep["written"],
                                   "conflicts": rep["conflicts"]}
        print(json.dumps(result, ensure_ascii=False, indent=1))
        print(f"SLOT_INIT_OK slot={args.slot}")
        return 0
    if args.force:
        print(f"[WARN] ownership guard overridden for slot {args.slot}; recorded reason: {args.force}")
        result = drop_slot(args.slot, force_reason=args.force)
    else:
        result = drop_slot(args.slot)
    print(json.dumps(result, ensure_ascii=False, indent=1))
    if result["residue"]:
        print(f"SLOT_DROP_RESIDUE={result['residue']}")
        return 1
    print(f"SLOT_DROP_OK slot={args.slot}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except RuntimeError as e:
        print(f"SLOT_TOOLING_ERROR: {e}", file=sys.stderr)
        sys.exit(1)
