#!/usr/bin/env python3
"""Chain-slot topology: the one place that knows how a chain's environment is named.

Why this file exists: chain identity was hard-coded in four places - a concrete chain
token literal, the repository name used as a worktree prefix, the database family name and
the port band (base + N*band) - and the band arithmetic was implemented twice (here and
the occupancy probe). Hard-coded names do not generalize: the next chain gets a different
token, a differently named repo, and the rule silently stops applying, which is exactly how
five chains ended up handed the same slot. Every value below is an environment override
with today's measured default, so another project or repository can reuse the mechanism
without editing code.

No other module may define a port band, a slot database name or a chain-token prefix:
tools/slot_isolation_gate.py enforces that.

Usage:
  python3 scripts/slot_topology.py            # print the resolved topology
  python3 scripts/slot_topology.py --selftest
"""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

DEFAULTS = {
    "TP_REPO_NAME": "testPike",          # base checkout directory name; worktrees are <repo>-wt-<chain>
    "TP_WORKTREE_INFIX": "-wt-",
    "TP_DB_BASE": "testpike",            # main slot database name, also the slot name prefix
    "TP_SLOT_INFIX": "_slot",            # main_slot3 == slot 3 of database family testpike
    "TP_TEMPLATE_SUFFIX": "_tpl",        # factory template = <db_base><slot_infix><tpl_suffix>
    "TP_DB_HOST": "localhost",
    "TP_DB_PORT": "15432",
    "TP_PORT_BAND": "100",               # slot N owns [base + N*band, base + N*band + band-1]
    "TP_MAIN_BUCKET": "testpike-exec",
    "TP_SWF_DB_BASE": "seaweedfs",         # SeaweedFS filer metadata database family (same PG instance)
    "TP_SWF_NETWORK": "testpike_default",  # dev compose network slot weed containers join
    "TP_SLOT_ENV_DIR": str(Path.home() / ".hermes" / "cache" / "slot_env"),
    # middleware containers the slots live inside (compose service containers, shared
    # across chains: isolation is per database / index / vhost / bucket, not per container).
    # Object storage is the exception: SeaweedFS metadata is a per-slot database plus a
    # per-slot gateway container (see swf_container_for), because one weed process binds
    # exactly one metadata store — shared-bucket isolation would make two chains fight
    # over the same namespace.
    "TP_CONTAINER_PG": "testpike-postgres",
    "TP_CONTAINER_REDIS": "testpike-redis",
    "TP_CONTAINER_RABBIT": "testpike-rabbitmq",
    "TP_CONTAINER_SEAWEEDFS": "testpike-seaweedfs",
}


def container(kind: str) -> str:
    return value(f"TP_CONTAINER_{kind.upper()}")

# env key -> base port on the main slot. Order matters only for printing.
# One row per listen port a shipped descriptor publishes: the file, the environment key the
# descriptor actually reads, and how to find its fallback. Both the drift check here and
# tools/install_config_gate.py read this table - a second copy of the pairing is how the
# mock plane once carried two names - one exported by the slot tool, one read by the
# descriptor - which pinned a slot chain's mock server onto the main chain's port.
DESCRIPTOR_PORTS = (
    ("admin/src/main/resources/application.yml", "ADMIN_PORT",
     r"port:\s*\$\{ADMIN_PORT:(\d+)\}"),
    ("api/src/main/resources/application.yml", "API_PORT",
     r"port:\s*\$\{API_PORT:(\d+)\}"),
    ("core/src/main/resources/application.yml", "CORE_PORT",
     r"port:\s*\$\{CORE_PORT:(\d+)\}"),
    ("ai-service/src/main/resources/application.yml", "AISERVICE_PORT",
     r"port:\s*\$\{AISERVICE_PORT:(\d+)\}"),
    ("mock-server/src/main/resources/application.yml", "MOCK_SERVER_MANAGEMENT_PORT",
     r"port:\s*\$\{MOCK_SERVER_MANAGEMENT_PORT:(\d+)\}"),
    ("mock-server/src/main/resources/application.yml", "MOCK_SERVER_TRAFFIC_PORT",
     r"traffic-port:\s*\$\{MOCK_SERVER_TRAFFIC_PORT:(\d+)\}"),
    ("frontend/vite.config.ts", "VITE_PORT",
     r"process\.env\.VITE_PORT\s*\|\|\s*'(\d+)'"),
)

SERVICE_PORTS = (
    ("ADMIN_PORT", 8081),
    ("API_PORT", 8082),
    ("CORE_PORT", 8083),
    ("SERVER_PORT", 8081),
    ("AISERVICE_PORT", 18084),
    ("MOCK_SERVER_MANAGEMENT_PORT", 18085),
    ("MOCK_SERVER_TRAFFIC_PORT", 18086),
    ("VITE_PORT", 5173),
    # SeaweedFS host-side published ports (dev compose + per-slot containers). The
    # container always listens on the defaults inside its own network namespace; only the
    # host mapping moves with the slot band, so a slot chain's S3_* endpoint and its
    # gateway are provably the same instance.
    ("SWF_S3_HOST_PORT", 8333),
    ("SWF_MASTER_HOST_PORT", 9333),
    ("SWF_FILER_HOST_PORT", 8888),
)
# Ports the occupancy probe attributes to slots (deduped by value). The shared fixture
# site (8899) and unrelated services are deliberately absent: they belong to no slot, and
# treating them as slot-owned would make a foreign listener look like a chain's own.
BASE_PORTS = tuple(sorted({base for _, base in SERVICE_PORTS}))

# Tooling base-URL family : the explicit-override keys that
# tools/verification/_l3_config.base_url resolves probes through. A slot env that
# exports only the raw port keys leaves each base to fall back to the shipped
# main-slot literal whenever a port export is missing — one wrong environment and a
# chain's traffic lands in another chain's data plane. The family is declared once
# here so the issuing tool, the issue checklist and the templates share one source.
BASE_URL_FAMILY = (
    ("TP_ADMIN_BASE", "ADMIN_PORT"),
    ("TP_API_BASE", "API_PORT"),
    ("TP_CORE_BASE", "CORE_PORT"),
    ("TP_AIS_BASE", "AISERVICE_PORT"),
    ("TP_MOCK_MGMT_BASE", "MOCK_SERVER_MANAGEMENT_PORT"),
    ("S3_ENDPOINT", "SWF_S3_HOST_PORT"),
)

# App-level URL keys scripts/chain_slot.py exports per slot (the service
# descriptors resolve inter-service routing from these, not from the raw port
# keys). A shared shell that keeps a foreign slot's value silently re-points
# this chain's read paths — measured 2026-11-08: the main-chain session ran
# with AISERVICE_BASE_URL=18284 / CORE_BASE_URL=8283 / STORAGE_LOCAL_PUBLIC_
# BASE_URL=8281 inherited from the AQ slot. tools/ops/load_chain_env.sh
# scrubs any of these its tree .env does not declare, and
# scripts/selftest_unit_execstart_chain_env.py pins the scrub list against
# this constant so the two cannot drift.
SLOT_EXPORT_URL_KEYS = (
    "CORE_BASE_URL",
    "AISERVICE_BASE_URL",
    "STORAGE_LOCAL_PUBLIC_BASE_URL",
    "S3_ENDPOINT",
)


# Names the older tooling already reads from the environment. Kept as aliases so an
# existing install stays valid while the topology becomes the only place with defaults.
LEGACY_KEYS = {"TP_DB_BASE": ("TP_MAIN_DB",),
               "TP_CONTAINER_PG": ("TP_PG_CONTAINER",)}


_ENV_FILES_CACHE = None
_LOOKUP_NESTED = False


def reset_env_cache() -> None:
    """Drop the cached env-file list (used when the directory moves, e.g. in tests)."""
    global _ENV_FILES_CACHE
    _ENV_FILES_CACHE = None


def _repo_files() -> list:
    """.env of the running chain, then its slot env: the installer's own values.

    A tool started without sourcing .env must still respect what the user configured,
    otherwise a re-targeted port would be silently re-defaulted here and the slot env
    generated from it would point at the shipped port instead of the user's. Only the
    non-secret keys this module knows about are read; values are never printed.

    The list is computed once behind a re-entrancy guard: resolving the slot env
    directory itself calls value(), and without the guard the two would recurse.
    """
    global _ENV_FILES_CACHE, _LOOKUP_NESTED
    if _ENV_FILES_CACHE is not None:
        return _ENV_FILES_CACHE
    if _LOOKUP_NESTED:
        return []
    _LOOKUP_NESTED = True
    try:
        root = Path(__file__).resolve().parents[1]
        files = [root / ".env"]
        env_dir = Path(os.environ.get("TP_SLOT_ENV_DIR", DEFAULTS["TP_SLOT_ENV_DIR"]))
        if env_dir.is_dir():
            files += [c for c in sorted(env_dir.glob("*.slot*.env"))
                      if not c.name.endswith(".released")]
        _ENV_FILES_CACHE = [f for f in files if f.is_file()]
    finally:
        _LOOKUP_NESTED = False
    return _ENV_FILES_CACHE


def dotenv_value(key: str):
    for path in _repo_files():
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue
        for line in text.splitlines():
            line = line.strip()
            if line.startswith(key + "="):
                got = line.split("=", 1)[1].strip().strip('"').strip("'")
                if got:
                    return got
    return None


def value(key: str) -> str:
    """Precedence: real environment > this chain's env files > shipped default."""
    for candidate in (key,) + LEGACY_KEYS.get(key, ()):
        got = os.environ.get(candidate) or dotenv_value(candidate)
        if got:
            return got
    return DEFAULTS[key]


def repo_name() -> str:
    return value("TP_REPO_NAME")


def worktree_prefix() -> str:
    return f"{repo_name()}{value('TP_WORKTREE_INFIX')}"


def main_database() -> str:
    return value("TP_DB_BASE")


def slot_database(slot: int) -> str:
    """Database name for one slot (slot 0 = the resident main database)."""
    if slot <= 0:
        return main_database()
    return f"{main_database()}{value('TP_SLOT_INFIX')}{slot}"


def template_database() -> str:
    return (f"{main_database()}{value('TP_SLOT_INFIX')}"
            f"{value('TP_TEMPLATE_SUFFIX')}")


def slot_of_database(dbname: str):
    """Slot encoded in a database name; None when the name is outside the family."""
    if not dbname:
        return None
    if dbname == main_database() or dbname == template_database():
        return 0
    m = re.match(rf"^{re.escape(main_database())}{re.escape(value('TP_SLOT_INFIX'))}(\d+)$",
                 dbname)
    return int(m.group(1)) if m else None


def port_band() -> int:
    return int(value("TP_PORT_BAND"))


def slot_port(base_port: int, slot: int) -> int:
    return int(base_port) + int(slot) * port_band()


def slot_of_port(port) -> int | None:
    """Which slot owns a port, or None when it is not on a slot boundary."""
    try:
        port = int(port)
    except (TypeError, ValueError):
        return None
    for base in BASE_PORTS:
        offset = port - base
        if offset == 0:
            return 0
        if offset > 0 and offset % port_band() == 0:
            return offset // port_band()
    return None


JDBC_RE = re.compile(r"jdbc:postgresql://([^:/]+):?(\d*)/([\w-]+)")


def installer_env_file() -> Path:
    """The install's own .env - what the user edits at install time (tests may point elsewhere).

    A linked worktree is not an install: its .env is a materialization derived from the
    install file with the slot band already added. Reading that as the base re-adds the
    band (8181 + 100 = 8281, a slot env pointing at nobody's port) and copies a stale key
    set instead of the installer's current one. A .env declaring a nonzero TP_CHAIN_SLOT
    is such a derived file; its install lives behind the common git dir, and that root's
    .env is the base.
    """
    override = os.environ.get("TP_INSTALL_ENV_FILE")
    if override:
        return Path(override)
    own = Path(__file__).resolve().parents[1] / ".env"
    slot = (_read_key(own, "TP_CHAIN_SLOT") or "").strip() if own.is_file() else ""
    if slot.isdigit() and int(slot) != 0:
        derived = _install_env_for_worktree(own.parent)
        if derived is not None:
            return derived
    return own


def _install_env_for_worktree(root: Path) -> Path | None:
    """The main checkout's .env that ``root`` was linked from, or None when unprovable.

    A worktree's ``.git`` is a file naming ``<install>/.git/worktrees/<name>``; walking up
    to that ``.git`` component gives the install root. A directory ``.git`` (this root IS
    an install), an unparseable pointer, or no .env there returns None and keeps the
    caller's own file, so single-tree installs and stripped checkouts behave as before.
    """
    marker = root / ".git"
    if not marker.is_file():
        return None
    try:
        text = marker.read_text(encoding="utf-8-sig").strip()
    except OSError:
        return None
    if not text.startswith("gitdir:"):
        return None
    node = (root / text[7:].strip()).resolve()
    for _ in range(8):
        if node.name == ".git":
            if node.parent == root:
                return None
            env = node.parent / ".env"
            return env if env.is_file() else None
        node = node.parent
    return None


def _read_key(path: Path, key: str):
    try:
        text = path.read_text(encoding="utf-8", errors="ignore")
    except Exception:
        return None
    for line in text.splitlines():
        line = line.strip()
        if line.startswith(key + "="):
            got = line.split("=", 1)[1].strip().strip('"').strip("'")
            if got:
                return got
    return None


def installer_value(key: str):
    """What THIS INSTALL configured, read from its .env only - never from the process env.

    Two different questions need two different answers, and mixing them was a real defect:
    a slot chain's shell already carries ADMIN_PORT=8181, so treating the process env as the
    base and adding the band again produced 8281 - a slot env pointing at nobody's port.
    Issuing a slot therefore reads the installer's file (the same file the user is told to
    edit), and only its value counts; absent means the shipped default.
    """
    path = installer_env_file()
    if not path.is_file():
        return None
    for candidate in (key,) + LEGACY_KEYS.get(key, ()):
        got = _read_key(path, candidate)
        if got:
            return got
    return None


def db_target():
    """(host, port, database) from the chain's own DB_URL when it exists.

    DB_URL is what the installer actually sets, so the slot database URL is rebuilt from
    the same host and port instead of a second constant that could disagree with it.
    Falls back to the shipped defaults for a fresh install with no .env yet.
    """
    raw = installer_value("DB_URL") or ""
    m = JDBC_RE.search(raw)
    if m:
        return m.group(1), int(m.group(2) or 5432), m.group(3)
    return value("TP_DB_HOST"), int(value("TP_DB_PORT")), main_database()


def base_port(env_key: str, default: int) -> int:
    """The port a service actually listens on in THIS install, or its shipped default.

    Keys are the ones application.yml reads (${ADMIN_PORT:8081} and friends). Only the
    installer's own .env can re-target a base: the process env is deliberately not consulted
    here because inside a slot chain it already carries the offset value.
    """
    raw = installer_value(env_key)
    if not raw:
        for _, base in SERVICE_PORTS:
            if _ == env_key:
                return int(base)
        return int(default)
    try:
        return int(str(raw).strip())
    except ValueError:
        raise RuntimeError(f"{env_key}={raw!r} is not a number (check the chain's .env)")


def shipped_base(env_key: str) -> int:
    for key, base in SERVICE_PORTS:
        if key == env_key:
            return int(base)
    raise KeyError(f"{env_key} is not a registered service port")


def bucket_for(slot: int) -> str:
    return main_bucket() if slot <= 0 else f"tp-slot{slot}"


def swf_meta_database(slot: int) -> str:
    """SeaweedFS filer metadata database for one slot (same PG instance, different DB).

    slot 0 = the shared dev metadata database; chain slots get seaweedfs_slotN, mirroring
    the business database family so `drop` can prove both were retired together."""
    base = value("TP_SWF_DB_BASE")
    return base if slot <= 0 else f"{base}{value('TP_SLOT_INFIX')}{slot}"


def swf_container_for(slot: int) -> str:
    """Gateway container name; one weed process binds one metadata store, so chains own
    a container instead of sharing a bucket namespace on one server."""
    base = container("seaweedfs")
    return base if slot <= 0 else f"{base}-slot{slot}"


def swf_volume_for(slot: int) -> str:
    """Named docker volume backing one slot's SeaweedFS volume servers (data blobs)."""
    return f"{repo_name().lower()}-swf-slot{slot}-data" if slot > 0 else "testpike_swf_data"


def main_bucket() -> str:
    return value("TP_MAIN_BUCKET")


def vhost_for(slot: int) -> str:
    return "/" if slot <= 0 else f"/tp-slot{slot}"


def redis_db_for(slot: int) -> str:
    return str(max(int(slot), 0))


def slot_env_dir() -> Path:
    return Path(value("TP_SLOT_ENV_DIR"))


def slot_env_path(chain: str, slot: int) -> Path:
    return slot_env_dir() / f"{chain}.slot{slot}.env"


def chain_env_files(chain: str) -> list:
    """Slot env files claiming one chain (the chain's own record of its slot)."""
    d = slot_env_dir()
    return sorted(d.glob(f"{chain}.slot*.env")) if d.is_dir() else []


def slot_claims(env_dir: Path = None) -> list:
    """Live slot env files (a `.released` claim has been retired and no longer occupies)."""
    d = Path(env_dir) if env_dir else slot_env_dir()
    if not d.is_dir():
        return []
    return sorted(f for f in d.glob("*.slot*.env") if not f.name.endswith(".released"))


def chain_from_env_name(env_path) -> str:
    """Chain token as recorded by the file name: <chain>.slot<N>.env[.released]."""
    name = Path(str(env_path)).name
    for suffix in (".env.released", ".env"):
        if name.endswith(suffix):
            name = name[: -len(suffix)]
            break
    m = re.search(r"\.slot(\d+)$", name)
    return name[: m.start()] if m else name


def chain_slot_of(env_path: Path):
    m = re.search(r"\.slot(\d+)\.env", env_path.name)
    return int(m.group(1)) if m else None


def chain_token(cwd=None, default_main: str = "main") -> str:
    """Chain identity from a directory name, without naming any concrete chain.

    <repo>            -> main chain
    <repo>-wt-<token> -> slot chain <token>
    anything else     -> that directory name (a checkout with another layout is its own chain)
    """
    name = Path(cwd or os.getcwd()).name
    prefix = worktree_prefix()
    if name.startswith(prefix):
        return name[len(prefix):] or default_main
    if name == repo_name():
        return default_main
    return name


def is_worktree(cwd=None) -> bool:
    return Path(cwd or os.getcwd()).name.startswith(worktree_prefix())


def normalize(token: str) -> str:
    return re.sub(r"[^a-z0-9]", "", (token or "").lower())


def registry_path() -> Path:
    return Path(__file__).resolve().parents[1] / "docs" / "parallel_readiness_registry.json"


def known_chain_tokens(registry: Path = None) -> list:
    """Real chain identifiers only: the registry's `chain` fields plus the tokens that
    actually own slot env files.

    Domain *titles* are deliberately excluded: they are prose ("stock repayment rollup"),
    and matching prose against source code produces false accusations, which would train
    everyone to ignore the gate. A chain becomes protected here the moment it is issued a
    slot, which is exactly when hard-coding its name starts to matter.
    """
    import json
    tokens = set()
    # Scheduling-status words can never be protected chain identities. Found the
    # hard way (11-18): a registry entry wrote the queue marker into the `chain`
    # field, the generic word became a forbidden token, and every ordinary
    # "queue" line in tooling read as a cross-chain leak - the gate went
    # permanently red over prose, exactly the P18 over-match form that trains
    # everyone to ignore it.
    status_words = {"queue", "pending", "planned", "ready", "blocked",
                    "waiting", "todo", "backlog", "none", "done"}
    path = Path(registry) if registry else registry_path()
    if path.is_file():
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            data = {}
        for domain in data.get("domains", []):
            # Full registered chain names only: hyphen-split fragments would turn
            # ordinary capability vocabulary into forbidden identities - the
            # behaviour-kernel family legitimately writes "kernel" in tooling that
            # proves the feature itself, and flagging that trains everyone to
            # ignore the gate (P18 over-match form). A hard-coded cross-chain
            # reference always carries the full name (worktree path, chain env
            # stem, TP_CHAIN_NAME value), which this token set still matches.
            name = str(domain.get("chain") or "").strip().lower()
            if len(name) >= 3 and name != "main" and name not in status_words:
                tokens.add(name)
    env_dir = slot_env_dir()
    # the base checkout is not a slot chain: its own name (repository or main database)
    # appears in the topology as a configurable default, so it must never be a forbidden
    # token - only names of chains that were issued a slot are.
    main_names = {normalize(repo_name()), normalize(main_database())}
    if env_dir.is_dir():
        for f in env_dir.glob("*.slot*.env"):
            token = re.sub(r"\.slot\d+\.env(\.released)?$", "", f.name).lower()
            if len(token) >= 3 and normalize(token) not in main_names:
                tokens.add(token)
    return sorted(tokens)


def chain_identity_leaks(source: str, tokens) -> list:
    """Normalized membership test: case, quotes and separators cannot hide a name."""
    haystack = normalize(source)
    return sorted({t for t in tokens if t and normalize(t) in haystack})


def shipped_default_drift(repo_root=None) -> list:
    """Where an application.yml (or vite/compose) fallback disagrees with this table.

    Ports are configurable through the environment, but the shipped default now lives in
    two layers: `${ADMIN_PORT:8081}` in the service descriptor and this table. If someone
    changes one and not the other, a slot chain boots on a port its own tooling then
    probes somewhere else - silent cross-chain failure. Compared by reading, never guessed.
    """
    from pathlib import Path as _P
    root = _P(repo_root) if repo_root else _P(__file__).resolve().parents[1]
    findings = []
    descriptors = DESCRIPTOR_PORTS
    for rel, key, pattern in descriptors:
        path = root / rel
        if not path.is_file():
            findings.append(f"{rel}: descriptor missing for {key}")
            continue
        m = re.search(pattern, path.read_text(encoding="utf-8", errors="ignore"))
        if not m:
            findings.append(f"{rel}: {key} is not env-overridable (plain literal?)")
            continue
        shipped = shipped_base(key)
        if int(m.group(1)) != shipped:
            findings.append(f"{key}: descriptor default {m.group(1)} != topology {shipped}")
    return findings


def selftest() -> int:
    """Two-sided, and generality is the thing under test: after an override that renames
    the repository, the database family and the band, nothing may still resolve with the
    project's own vocabulary, and no chain name appears anywhere in the logic."""
    keep = dict(os.environ)
    try:
        # current project defaults resolve as before
        assert slot_database(0) == "testpike" and slot_database(7) == "testpike_slot7"
        assert slot_of_database("testpike_slot7") == 7
        assert slot_of_database("testpike") == 0
        assert slot_of_database(template_database()) == 0
        assert slot_of_database("unrelated_db") is None
        assert slot_of_port(8081) == 0 and slot_of_port(8181) == 1 and slot_of_port(8283) == 2
        assert slot_of_port(18091) is None, "off-band port is not a slot's"
        assert slot_of_port("nope") is None
        assert chain_token("/x/testPike") == "main"
        assert chain_token("/x/testPike-wt-anyname") == "anyname"
        assert is_worktree("/x/testPike-wt-anyname") and not is_worktree("/x/testPike")

        # another project: same code, different vocabulary, via env only
        os.environ.update({"TP_REPO_NAME": "OtherProj", "TP_WORKTREE_INFIX": "-slot-",
                           "TP_DB_BASE": "otherdb", "TP_SLOT_INFIX": "__slot",
                           "TP_PORT_BAND": "50", "TP_DB_PORT": "5432"})
        assert slot_database(3) == "otherdb__slot3", slot_database(3)
        assert slot_of_database("otherdb__slot3") == 3
        assert slot_of_database("testpike_slot3") is None, "old vocabulary must stop matching"
        assert slot_of_port(8081 + 3 * 50) == 3
        assert slot_of_port(8131) == 1, "with band 50 the first slot port is base+50"
        assert slot_of_port(8181) == 2, "under band 50 the old slot-1 port is now slot 2"
        assert slot_of_port(8156) is None, "an offset that is not a band multiple owns no slot"
        assert chain_token("/x/OtherProj-slot-demo") == "demo"
        assert is_worktree("/x/OtherProj-slot-demo") and not is_worktree("/x/OtherProj")
        assert template_database() == "otherdb__slot_tpl"

        # the module's own text must carry no concrete chain identity: the forbidden
        # tokens come from the readiness registry, not from a literal typed here (a
        # literal would make this very check self-referential and unfixable).
        tokens = known_chain_tokens()
        assert tokens, "the token set must not be empty, or the guard is a no-op"
        hit = chain_identity_leaks(Path(__file__).read_text(encoding="utf-8"), tokens)
        assert not hit, f"concrete chain identities in the topology source: {hit}"
        # and the detector itself is proven on both sides
        assert chain_identity_leaks("slot = 'demochain'  # planted", ["demochain"]) == ["demochain"]
        assert chain_identity_leaks("slot = env('TP_SLOT_ENV_DIR')", ["demochain"]) == []
        assert chain_identity_leaks("DemoChain appears only as prose", ["demochain"]) == ["demochain"], \
            "case and punctuation must not hide a leak"

        # registry hygiene, both sides: a real chain name is protected, a status
        # word written into the chain field never becomes a forbidden identity
        import json as _json
        import tempfile as _tempfile
        with _tempfile.TemporaryDirectory() as sandbox_reg:
            reg = Path(sandbox_reg) / "registry.json"
            reg.write_text(_json.dumps({"domains": [
                {"id": "x1", "chain": "queue"},
                {"id": "x2", "chain": "demochainX"},
            ]}), encoding="utf-8")
            reg_tokens = known_chain_tokens(reg)
            assert "demochainx" in reg_tokens, \
                f"a real chain name must stay protected: {reg_tokens}"
            assert "queue" not in reg_tokens, \
                f"a status word must never become a forbidden identity: {reg_tokens}"

        # the shipped default must stay identical across the two layers that carry it
        drift = shipped_default_drift()
        assert not drift, f"port defaults drifted between descriptor and topology: {drift}"
        # and a descriptor that lost its env form must be reported, not silently passed
        import tempfile
        with tempfile.TemporaryDirectory() as sandbox:
            fake = Path(sandbox)
            rels = ("admin/src/main/resources/application.yml",
                    "api/src/main/resources/application.yml",
                    "core/src/main/resources/application.yml",
                    "ai-service/src/main/resources/application.yml",
                    "mock-server/src/main/resources/application.yml")
            for rel in rels:
                (fake / rel).parent.mkdir(parents=True, exist_ok=True)
                (fake / rel).write_text("server:\n  port: ${X_KEY:1}\n", encoding="utf-8")
            (fake / "admin/src/main/resources/application.yml").write_text(
                "server:\n  port: 8081\n", encoding="utf-8")          # env form reverted
            (fake / "frontend").mkdir(parents=True, exist_ok=True)
            (fake / "frontend/vite.config.ts").write_text("port: 5173,", encoding="utf-8")
            caught = shipped_default_drift(fake)
            assert any("admin" in c and "not env-overridable" in c for c in caught), \
                f"a reverted literal must be named: {caught}"
            assert any("vite" in c for c in caught), f"the vite literal must be named too: {caught}"

        # DB host/port follows the chain's own DB_URL, not a second constant
        # the installer's file decides: an inherited slot value in the shell must not move it
        with tempfile.TemporaryDirectory() as sandbox2:
            install = Path(sandbox2) / ".env"
            install.write_text("DB_URL=jdbc:postgresql://db.internal:6543/testpike\n"
                               "ADMIN_PORT=9000\n", encoding="utf-8")
            keep_env_file = os.environ.get("TP_INSTALL_ENV_FILE")
            keep_shell = os.environ.get("ADMIN_PORT")
            os.environ["TP_INSTALL_ENV_FILE"] = str(install)
            os.environ["ADMIN_PORT"] = "8181"          # a polluted slot shell
            try:
                assert db_target() == ("db.internal", 6543, "testpike"), db_target()
                assert base_port("ADMIN_PORT", 8081) == 9000, \
                    "the installer's re-targeted port must win over the shell"
                issued = slot_port(base_port("ADMIN_PORT", 8081), 1)
                assert issued == 9000 + port_band(), \
                    f"slot 1 must be one band above the installer's own port: {issued}"
                assert issued != 8281, \
                    "re-offsetting an inherited slot value (8181+band) is the defect this blocks"
            finally:
                if keep_env_file is None:
                    os.environ.pop("TP_INSTALL_ENV_FILE", None)
                else:
                    os.environ["TP_INSTALL_ENV_FILE"] = keep_env_file
                if keep_shell is None:
                    os.environ.pop("ADMIN_PORT", None)
                else:
                    os.environ["ADMIN_PORT"] = keep_shell
    finally:
        os.environ.clear()
        os.environ.update(keep)
        reset_env_cache()
    print("[selftest] two-sided PASS (project defaults resolve as measured; renaming repo/db "
          "family/band through env re-targets every rule; off-band ports and foreign database "
          "names are rejected; chain identities from the ledger cannot appear in the source; "
          "port defaults agree with every descriptor and a reverted literal is named; DB "
          "host/port follows the chain's own DB_URL)")
    return 0


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    print(f"repo={repo_name()} worktree_prefix={worktree_prefix()} db={main_database()} "
          f"template={template_database()} db_port={value('TP_DB_PORT')} band={port_band()}")
    for slot in (0, 1):
        r = (f"slot{slot}: db={slot_database(slot)} redis={redis_db_for(slot)} "
             f"vhost={vhost_for(slot)} bucket={bucket_for(slot)} "
             + " ".join(f"{k}={slot_port(b, slot)}" for k, b in SERVICE_PORTS if k != "SERVER_PORT"))
        print(r)
