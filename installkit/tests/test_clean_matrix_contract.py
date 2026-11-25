# -*- coding: utf-8 -*-
"""Contract tests for the clean-machine matrix window and the demo greenlight proof.

T293d segment two: the one-off scratch greenlight tool becomes a repo consumer
artifact (targets resolved by demo seed codes, never magic numeric ids), the
profile×tier matrix becomes a declarable, plan-printable runner, and the
"database already exists" branch in the S1 bootstrap must route through the
failure-guide single source instead of dumping raw stderr only.
"""
import importlib.util
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOOLS = os.path.join(ROOT, "tools", "verification")
sys.path.insert(0, os.path.join(ROOT, "installkit"))

import resource_tiers  # noqa: E402


def _load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _matrix():
    return _load(os.path.join(TOOLS, "clean_matrix_window.py"), "clean_matrix_window")


def _proof():
    return _load(os.path.join(TOOLS, "greenlight_demo_proof.py"), "greenlight_demo_proof")


class _Resp:
    def __init__(self, data):
        self._d = {"code": "0", "msg": "ok", "data": data}
        self.status_code = 200

    def json(self):
        return self._d


class _StubClient:
    """ canned admin-API walk for the demo fixture targets """

    def __init__(self):
        import demo_seed
        self.rows = {
            "/projects": [{"id": 77, "code": "demo-order"}, {"id": 78, "code": "demo-inventory"}],
            "/projects/77/envs": [{"id": 66, "name": demo_seed.DEMO_ENV_NAME}],
            "/apitest/collections": [{"id": 55, "name": demo_seed.DEMO_CENTER_COLLECTION_NAME,
                                      "projectId": 77}],
            "/apitest/requests": [{"id": 44, "collectionId": 55,
                                   "name": demo_seed.center_request_plan(
                                       next(s for s in demo_seed.DEMO_CENTER_PROJECTS
                                            if s["code"] == "demo-order"))["name"]}],
        }

    def get(self, url, headers=None, params=None):
        return _Resp(self.rows[url])


def test_matrix_declares_three_profiles_with_annotated_wsl_and_extracted_tiers():
    m = _matrix()
    assert set(m.PROFILES) == {"alpine", "ubuntu2204", "wsl"}
    # tier value domain is the installer single source, not a second list
    assert set(m.TIERS) == set(resource_tiers.TIERS)
    cells = m.cells()
    runnable = {(c["profile"], c["tier"]) for c in cells if c.get("runnable")}
    blocked = [c for c in cells if not c.get("runnable")]
    assert runnable == {("alpine", "lightweight"), ("ubuntu2204", "standard")}
    assert blocked and all(c.get("blocked_note") for c in blocked)
    assert any(c["profile"] == "wsl" for c in blocked)


def test_runnable_cell_command_is_unattended_idempotent_and_secret_free():
    m = _matrix()
    for c in m.cells():
        if not c.get("runnable"):
            continue
        cmd = m.cell_command(c)
        assert "install.sh" in cmd and "--unattended" in cmd
        assert "--tier=%s" % c["tier"] in cmd
        # credentials travel as env pass-through names, never as inline values
        assert "ADMIN_INIT_PASSWORD " in cmd and "ADMIN_INIT_PASSWORD=" not in cmd
        assert not re.search(r"(?i)password\s*=\s*\S", cmd)
        # acceptance probe is wired into the same cell: greenlight proof step
        assert "greenlight_demo_proof" in cmd
        # host networking is an explicit machine-level workaround, annotated not silent
        assert "--network=host" in cmd and c.get("net_note")


def test_cell_command_bootstraps_bare_image_prerequisites_via_os_package_line():
    # r39 live-window finding: bare alpine:3.20 ships no bash/python3, so the
    # inner command died on "sh: bash: not found" before the installer ran.
    # Each runnable profile must declare its documented OS-line prerequisite
    # bootstrap ahead of the installer; everything above stays install.sh's job.
    m = _matrix()
    for c in m.cells():
        if not c.get("runnable"):
            continue
        prereq = m.PROFILES[c["profile"]].get("prereq")
        assert prereq, "bare profile images need a documented prereq bootstrap line"
        cmd = m.cell_command(c)
        inner = cmd.split("sh -lc '", 1)[1]
        assert inner.index(prereq) < inner.index("installkit/install.sh"), (
            "installer cannot start before its interpreter/tool prerequisites exist")
        if c["profile"] == "alpine":
            assert "apk add" in prereq and "bash" in prereq and "python3" in prereq
        else:
            assert "apt-get install" in prereq and "docker" in prereq
        assert "ADMIN_INIT_PASSWORD" not in prereq  # bootstrap line is secret-free


def test_every_runnable_prereq_line_covers_installer_interpreter_surface():
    # r49 live-window finding: the bare ubuntu:22.04 image ships bash but NOT
    # python3 (the prereq comment wrongly assumed it did), so every install.sh
    # step in the ubuntu cell died on "python3: command not found". Package
    # names follow the installer's own repair lines in installkit/pkg_probe.py
    # (interpreter + pip + fetch + docker CLI and compose plugin); the check is
    # manager-agnostic: each required tool token must appear inside a package
    # name of the declared prereq line.
    m = _matrix()
    required_tools = ("python3", "pip", "git", "curl", "docker", "compose")
    for c in m.cells():
        if not c.get("runnable"):
            continue
        prereq = m.PROFILES[c["profile"]]["prereq"]
        for tool in required_tools:
            assert re.search(r"[\w.-]*%s[\w.-]?" % re.escape(tool), prereq), (
                "%s prereq line misses installer requirement token %r: %s"
                % (c["profile"], tool, prereq))


def test_cell_command_starts_app_tier_waits_ready_and_reseeds_before_greenlight():
    # r44 live-window finding: the installer deliberately does not run the app
    # tier (the wizard seeds against services that are down and honestly
    # degrades with rc=0), so the greenlight proof hit Connection refused.
    # The cell recipe must close that loop: bring the compose app profile up,
    # wait for admin readiness with a bounded retry, re-run the seed channel
    # against the live stack, and only then run the proof.
    m = _matrix()
    for c in m.cells():
        if not c.get("runnable"):
            continue
        inner = m.cell_command(c).split("sh -lc '", 1)[1]
        assert "docker compose --profile app up -d" in inner
        assert "/actuator/health" in inner
        # readiness wait is bounded and the cap is hot-changeable via env
        assert "${TP_MATRIX_READY_TRIES:-" in inner
        assert "s1_bootstrap.py seed-demo --base-url" in inner
        # order: app tier up -> readiness wait -> reseed -> proof
        up = inner.index("--profile app up -d")
        wait = inner.index("/actuator/health")
        seed = inner.index("seed-demo --base-url")
        proof = inner.index("greenlight_demo_proof")
        assert up < wait < seed < proof
        # the reseed target is the cell's own admin host mapping, never a literal
        ports = m.cell_port_env(c["profile"], c["tier"])
        assert ("seed-demo --base-url http://127.0.0.1:%s"
                % ports["APP_ADMIN_HOST_PORT"]) in inner


def test_cell_command_tears_down_every_profile_on_green_and_keeps_scene_on_red():
    # r51 live-window finding: after a green cell the host daemon kept the
    # five app-tier containers alive because a plain `compose down` only
    # removes services whose profiles are enabled in that invocation — the
    # sweep had to be re-run naming every profile, and rounds before r51 paid
    # the same manual teardown each time. The recipe must self-clean on green
    # (naming app/executor/observability profiles) and keep the scene on red,
    # because triage reads in-cell service logs before any relaunch.
    m = _matrix()
    for c in m.cells():
        if not c.get("runnable"):
            continue
        inner = m.cell_command(c).split("sh -lc '", 1)[1]
        assert "docker compose" in inner and "down -v" in inner
        teardown = inner[inner.index("down -v") - 200:inner.index("down -v")]
        for profile in ("app", "executor", "observability"):
            assert "--profile %s" % profile in teardown
        # green-only: teardown is chained with && AFTER the proof, never before
        proof = inner.index("greenlight_demo_proof")
        assert proof < inner.index("down -v")
        # `&&` not `;` — a red proof must skip the teardown (r50 lesson shape)
        assert inner[proof:inner.index("down -v")].count("&& ") >= 1
        assert "; docker compose --profile app --profile executor" not in inner


def test_cell_command_mounts_tree_at_identical_host_path_out_of_docker():
    # r41 live-window finding: compose inside the cell container talks to the
    # HOST docker daemon, which resolves bind sources against the host
    # filesystem. The old mount (-v {root}:/opt/testpike) turned the relative
    # source ./db/schema-init.sql into /opt/testpike/db/schema-init.sql —
    # absent on the host, docker auto-created an empty directory there and
    # postgres initdb died on "Is a directory". The mount must be identity
    # (host path == container path) so every host-visible bind source points
    # at the real file; a genuine clean machine never takes this route.
    m = _matrix()
    for c in m.cells():
        if not c.get("runnable"):
            continue
        cmd = m.cell_command(c)
        root = m._ROOT
        assert "-v %s:%s " % (root, root) in cmd, (
            "tree must be mounted at its own path, or the host daemon cannot "
            "see any bind source compose resolves inside the cell")
        assert "-v %s:/opt/testpike" % root not in cmd
        inner = cmd.split("sh -lc '", 1)[1]
        assert "cd %s &&" % root in inner
        assert "/opt/testpike" not in inner, (
            "installer and proof must run from the identity-mounted path")
        # companion check: the daemon socket stays an identity mount too
        assert "-v /var/run/docker.sock:/var/run/docker.sock" in cmd


def test_greenlight_proof_resolves_targets_by_demo_codes_not_numeric_ids():
    g = _proof()
    req_id, env_id = g.resolve_targets(_StubClient(), {})
    assert (req_id, env_id) == (44, 66)
    src = open(os.path.join(TOOLS, "greenlight_demo_proof.py"), encoding="utf-8").read()
    # the scratch tool hardcoded requestId=9/envId=5; the consumer artifact must not
    assert not re.search(r"requestId.{0,4}=\s*9\b", src)
    assert not re.search(r"envId.{0,4}=\s*5\b", src)


def test_greenlight_proof_reads_operator_password_from_environment_only():
    g = _proof()
    src = open(os.path.join(TOOLS, "greenlight_demo_proof.py"), encoding="utf-8").read()
    assert "ADMIN_INIT_PASSWORD" in src
    # no default fallback for the secret and no printing of its value
    assert not re.search(r"ADMIN_INIT_PASSWORD.{0,20},\s*[\"']", src)


def test_pg_existing_failure_routes_through_failure_guide_single_source():
    src = open(os.path.join(ROOT, "installkit", "s1_bootstrap.py"), encoding="utf-8").read()
    assert "failure_guide" in src and "note_for_failure" in src
    # Classify+teach pairing belongs inside the guide: a caller that re-pairs it
    # is a second route that future families would silently miss.
    assert "classify_failure" not in src, "consumers call the guide, not its internals"
    # the create-database failure branch must classify its stderr (raw tail alone = defect)
    branch = re.search(r"metadata database .* creation failed[\s\S]{0,400}?", src)
    assert branch and "note_for_failure" in src[branch.start():branch.start() + 600]


def test_compose_stage_failures_route_through_the_failure_guide():
    """The bring-up stages used to print only 'the docker output above is the reason'.

    Streaming (rather than capturing) is what makes classification possible at
    all, so the runner contract is pinned here too: a plain subprocess.run()
    with inherited stdio loses the text.
    """
    src = open(os.path.join(ROOT, "installkit", "s1_bootstrap.py"), encoding="utf-8").read()
    assert "def _execute_tee" in src and "stderr=subprocess.STDOUT" in src, \
        "stage output must be echoed live and kept for classification"
    wiring = re.search(r"def cmd_compose[\s\S]{0,2500}", src)
    assert wiring and "stage_failure_note" in wiring.group(0) \
        and "_run_stage(stages[0]" in wiring.group(0)


def test_apk_profile_registered_and_matrix_prereq_matches_installer_lines():
    # r39b live-window finding: the prereq bootstrap made bash/python3 exist,
    # knows apt/dnf families, so Alpine never reaches its repair lines.
    import pkg_probe
    alpine_text = 'ID="alpine"\nNAME="Alpine Linux"\n'
    assert pkg_probe.pkg_manager_for("linux", alpine_text) == "apk"
    table = pkg_probe.PREREQ_CMD.get("apk")
    assert table, "apk image must share the single prereq command table"
    assert set(table) == set(pkg_probe.PREREQS)
    for cmd in table.values():
        assert "apk" in cmd
    m = _matrix()
    alpine_prereq = m.PROFILES["alpine"]["prereq"]
    # the bootstrap line must leave the host in a state pkg_probe can repair
    # from: apk itself is the prerequisite of the prerequisite lines
    assert "apk" in alpine_prereq


def test_installer_third_party_imports_covered_by_requirements_file():
    # r39b live-window finding 2: fresh images die in s1_bootstrap at
    # "ModuleNotFoundError: No module named 'httpx'" — the installer runs its
    # own Python modules but never declared or installed their third-party
    # imports anywhere (executor/requirements.txt covers the executor only).
    req = os.path.join(ROOT, "installkit", "requirements.txt")
    assert os.path.exists(req), "installer must declare its own runtime requirements"
    declared = {ln.split("#")[0].strip().split(">=")[0].split("==")[0].strip()
                for ln in open(req, encoding="utf-8")
                if ln.strip() and not ln.strip().startswith("#")}
    kit = os.path.join(ROOT, "installkit")
    local_modules = {f[:-3] for f in os.listdir(kit) if f.endswith(".py")}
    external = set()
    for fn in os.listdir(kit):
        if not fn.endswith(".py"):
            continue
        for line in open(os.path.join(kit, fn), encoding="utf-8"):
            m2 = re.match(r"\s*(?:import|from)\s+([a-zA-Z_][\w.]*)", line)
            if not m2:
                continue
            top = m2.group(1).split(".")[0]
            if top in sys.stdlib_module_names or top in local_modules:
                continue
            external.add(top)
    assert external == {"httpx"}, "new third-party imports must land in the declared set"
    assert {p.replace("-", "_") for p in declared} >= external


def test_install_sh_bootstraps_its_own_python_deps_before_s1():
    # The requirements file is dead weight unless install.sh installs it before
    # the first step that imports them (s1_bootstrap compose-seed), idempotently
    # and through the INSTALLKIT_CMD_BIN test-stub channel like other recipes.
    src = open(os.path.join(ROOT, "installkit", "install.sh"), encoding="utf-8").read()
    assert "installkit/requirements.txt" in src, "installer must pip-install its own declared deps"
    assert "$PY -m pip" in src or "python3 -m pip" in src
    # anchor on the actual compose-seed invocation (s1_bootstrap imports httpx),
    # not the first textual mention of the module name in comments
    invoke = src.index('\n        run_step compose-seed')
    assert src.index("requirements.txt") < invoke, \
        "deps must exist before the first step that imports them"


def _host_ports():
    return _load(os.path.join(ROOT, "installkit", "host_ports.py"), "host_ports")


def test_host_port_band_keys_single_source():
    # r42 cell red: compose resolved the injected band key (ui-auth bound
    # 25406) while the installer check probed the .env shipped default (9000)
    # because the two consumers kept separate key lists. One band, one source.
    hp = _host_ports()
    matrix = _matrix()
    assert tuple(matrix._HOST_PORT_KEYS) == hp.HOST_PORT_KEYS
    compose = open(os.path.join(ROOT, "docker-compose.yml"), encoding="utf-8").read()
    for key in hp.HOST_PORT_KEYS:
        assert "${" + key in compose, "band key without a compose host mapping: %s" % key


def test_overlay_mirrors_compose_precedence():
    # compose reads ${KEY:-shipped default} with the process environment above
    # the repo .env; installer-side checks must resolve the same value through
    # the same order, and empty/absent environment values must not mask .env.
    hp = _host_ports()
    env = {"SWF_UI_FILER_PROXY_HOST_PORT": "9000", "S3_BUCKET": "tp-bucket"}
    got = hp.overlay(env, {"SWF_UI_FILER_PROXY_HOST_PORT": "25406",
                           "SWF_UI_MASTER_PROXY_HOST_PORT": "",
                           "PATH": "/usr/bin"})
    assert got["SWF_UI_FILER_PROXY_HOST_PORT"] == "25406"
    assert "SWF_UI_MASTER_PROXY_HOST_PORT" not in got  # empty never overrides
    assert got["S3_BUCKET"] == "tp-bucket"
    assert "PATH" not in got  # non-band keys are never copied in
    assert hp.overlay(env, {}) == env


def test_storage_check_resolves_band_keys_via_overlay():
    # pin the wiring line itself: cmd_storage must read .env through
    # host_ports.overlay, else the fix can silently regress to file-only reads
    src = open(os.path.join(ROOT, "installkit", "s1_bootstrap.py"),
               encoding="utf-8").read()
    assert "import host_ports" in src
    assert "host_ports.overlay(" in src
