"""Gatling execution-slice tests .

Context: perf_worker previously refused GATLING dispatch with an honest
"integration not built" message. This segment turns it into a real subprocess
execution path (user-installed gatling CLI, AGPL body never enters the repo)
plus attribution of the engine's binary simulation.log.

Ground truth for the log format (javap on gatling-core 3.13.5, ticks 1475-1477,
class io.gatling.core.stats.writer.*):
- one file, records = 1 header byte + payload; Run=0 Request=1 User=2 Group=3 Error=4;
- writeInt/writeLong = big-endian (ByteBuffer default);
- writeString = int byte-length + raw UTF-8 bytes (length 0 = no bytes);
- writeCachedString = int: >=0 means new string (index starts at 1, followed by the
 raw string) and negative means a back-reference to cache slot -index;
- writeBoolean = 1 byte (0/1);
- Request payload = group hierarchy (int count + that many cached strings),
 request name (cached), start (int, ms relative to run start), end (int, same),
 OK flag (boolean), message (cached, empty string when absent);
- Run payload = version string, simulation class name string, absolute start (long),
 description string, config map size (int) + that many key strings, assertion
 count (int) + boopickle blobs (int length + bytes, opaque to us);
- User payload = scenario id (int), start flag (boolean), timestamp (int, relative);
- Group payload = group name (cached), hierarchy, start/end/cumulated (ints), OK;
- Error payload = message (cached) + timestamp (int).

The fixture writer below mirrors that byte layout exactly, so the parser is
exercised against format-true material rather than an invented one.

Honest boundaries: the real Gatling CLI is absent on this machine (the live
end-to-end chain is verified in the next stage); a PATH-isolated fake CLI shim
provides subprocess truth in L2 (shim = material, not a whitelist). Missing-
binary/artifact paths use the real local environment.
"""
from __future__ import annotations

import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

HEADER_RUN = 0
HEADER_REQUEST = 1
HEADER_USER = 2
HEADER_GROUP = 3
HEADER_ERROR = 4


class LogBuilder:
    """Mirrors BufferedFileChannelWriter byte-for-byte (shared string cache)."""

    def __init__(self):
        self.buf = bytearray()
        self._cache: dict[str, int] = {}
        self._next_index = 1  # gatling starts the cache index at 1

    def _i32(self, v: int):
        self.buf += struct.pack(">i", v)

    def _i64(self, v: int):
        self.buf += struct.pack(">q", v)

    def string(self, s: str):
        # 3.13.5 BufferedFileChannelWriter.writeString: empty = putInt(0) only;
        # else putInt(len) + raw bytes + 1 trailing coder byte (JDK String coder,
        # 0 for the LATIN1 fixtures used here)
        if s == "":
            self._i32(0)
            return
        raw = s.encode("utf-8")
        self._i32(len(raw))
        self.buf += raw
        self.buf += bytes([0])

    def cached(self, s: str):
        idx = self._cache.get(s)
        if idx is None:
            self._i32(self._next_index)
            self._cache[s] = self._next_index
            self._next_index += 1
            self.string(s)
        else:
            self._i32(-idx)

    def header(self, b: int):
        self.buf += bytes([b])

    def request(self, name: str, start_ms: int, end_ms: int, ok: bool,
                message: str = "", groups=()):
        self.header(HEADER_REQUEST)
        self._i32(len(groups))
        for g in groups:
            self.cached(g)
        self.cached(name)
        self._i32(start_ms)
        self._i32(end_ms)
        self.buf += bytes([1 if ok else 0])
        self.cached(message)

    def run(self, version: str, simulation: str, start_millis: int,
            description: str, config_keys=()):
        self.header(HEADER_RUN)
        self.string(version)
        self.string(simulation)
        self._i64(start_millis)
        self.string(description)
        self._i32(len(config_keys))
        for k in config_keys:
            self.string(k)
        self._i32(0)  # no assertion blobs in fixtures

    def user(self, scenario_id: int, start: bool, timestamp_ms: int):
        self.header(HEADER_USER)
        self._i32(scenario_id)
        self.buf += bytes([1 if start else 0])
        self._i32(timestamp_ms)

    def group(self, start_ms: int, end_ms: int, ok: bool):
        # 3.13.5 GroupMessageSerializer: hierarchy (count + cached names),
        # start/end/cumulated ints, OK flag — no leading label
        self.header(HEADER_GROUP)
        self._i32(0)  # empty hierarchy
        self._i32(start_ms)
        self._i32(end_ms)
        self._i32(end_ms - start_ms)  # cumulated response time
        self.buf += bytes([1 if ok else 0])

    def error(self, message: str, timestamp_ms: int):
        self.header(HEADER_ERROR)
        self.cached(message)
        self._i32(timestamp_ms)

    def bytes(self) -> bytes:
        return bytes(self.buf)


def _write(path, data: bytes):
    with open(path, "wb") as f:
        f.write(data)


# ---------------------------------------------------------------- parse layer


def test_run_record_meta_extracted_and_noise_records_skipped(tmp_path):
    """Run header carries version/simulation/start; User/Group/Error records are
    skipped without derailing the request stream (parser must know their size)."""
    from perf_worker import parse_gatling_log

    b = LogBuilder()
    b.run("3.13.5", "PerfGen42T123", 1_700_000_000_000, "run-42", config_keys=("a",))
    b.user(1, True, 0)
    b.request("home", 10, 60, True)
    b.group(5, 100, True)
    b.request("home", 1100, 1190, False, "status 500")
    b.error("boom", 2000)
    b.user(1, False, 2500)
    log = tmp_path / "simulation.log"
    _write(log, b.bytes())

    parsed = parse_gatling_log(str(log))
    assert parsed["gatlingVersion"] == "3.13.5"
    assert parsed["simulationClassName"] == "PerfGen42T123"
    assert parsed["runStartMillis"] == 1_700_000_000_000
    assert parsed["truncated"] is False
    pts = parsed["points"]
    assert len(pts) == 2, "noise records must not leak into the points stream"
    assert pts[0]["name"] == "home" and pts[0]["ok"] is True
    assert pts[1]["name"] == "home" and pts[1]["ok"] is False
    assert pts[1]["message"] == "status 500"
    assert pts[1]["startMs"] == 1100 and pts[1]["endMs"] == 1190


def test_cached_string_backreferences_share_one_cache_across_records(tmp_path):
    """Repeated names must be back-references (negative int); a parser that
    forgets the shared cache would read garbage on the second record."""
    from perf_worker import parse_gatling_log

    b = LogBuilder()
    b.run("3.13.5", "PerfGen7T1", 0, "d")
    for i in range(5):
        b.request("login", i * 100, i * 100 + 50, True)
    raw_bytes = b.bytes()
    log = tmp_path / "simulation.log"
    _write(log, raw_bytes)

    parsed = parse_gatling_log(str(log))
    assert len(parsed["points"]) == 5
    assert all(p["name"] == "login" for p in parsed["points"])
    # The literal name bytes must appear exactly once — later records are pointers
    assert raw_bytes.count(b"login") == 1, "repeat names must use cache back-references"


def test_group_hierarchy_names_cacheable_in_request(tmp_path):
    """Hierarchy entries are cached strings: same group in two requests = pointer."""
    from perf_worker import parse_gatling_log

    b = LogBuilder()
    b.run("3.13.5", "PerfGen8T1", 0, "d")
    b.request("r1", 0, 10, True, groups=["scenario", "flow"])
    b.request("r2", 0, 10, False, "x", groups=["scenario", "flow"])
    log = tmp_path / "simulation.log"
    _write(log, b.bytes())

    parsed = parse_gatling_log(str(log))
    assert [p["name"] for p in parsed["points"]] == ["r1", "r2"]
    assert parsed["points"][0]["groups"] == ["scenario", "flow"]
    assert parsed["points"][1]["groups"] == ["scenario", "flow"]


def test_truncated_tail_keeps_complete_records_only(tmp_path):
    """Tailing a live run cuts mid-record: parser must stop cleanly, flag it,
    and never invent a half point."""
    from perf_worker import parse_gatling_log

    b = LogBuilder()
    b.run("3.13.5", "PerfGen9T1", 0, "d")
    b.request("a", 0, 9, True)
    b.request("b", 1000, 1009, True)
    full = b.bytes()
    log = tmp_path / "simulation.log"
    _write(log, full[:-3])  # last request loses its tail bytes

    parsed = parse_gatling_log(str(log))
    assert parsed["truncated"] is True
    assert [p["name"] for p in parsed["points"]] == ["a"]


def test_empty_file_yields_no_points(tmp_path):
    from perf_worker import parse_gatling_log

    log = tmp_path / "simulation.log"
    _write(log, b"")
    parsed = parse_gatling_log(str(log))
    assert parsed["points"] == []
    assert parsed["truncated"] is False


# ---------------------------------------------------------------- frame layer


def test_normalize_gatling_frames_second_offsets_three_lines(tmp_path):
    """Attribution reuses the k6 semantic frame keys: rps sum, p95 nearest-rank,
    errorRate = failed/requests per whole second of the run-relative clock."""
    from perf_worker import normalize_gatling_frames, parse_gatling_log

    b = LogBuilder()
    b.run("3.13.5", "PerfGenA1", 0, "d")
    # second 0: 3 requests, durations 10/20/100, one failed
    b.request("x", 10, 20, True)
    b.request("x", 10, 30, True)
    b.request("x", 10, 110, False, "boom")
    # second 1: 1 request
    b.request("x", 1000, 1010, True)
    log = tmp_path / "simulation.log"
    _write(log, b.bytes())

    frames = normalize_gatling_frames(parse_gatling_log(str(log))["points"])
    assert [(f["secondOffset"], f["rps"]) for f in frames] == [(0, 3.0), (1, 1.0)]
    assert frames[0]["p95Ms"] == 100.0  # ceil(0.95*3)=3rd of sorted [10,20,100]
    assert frames[0]["errorRate"] == 1 / 3
    assert frames[1]["errorRate"] == 0.0
    assert "authCount" not in frames[0]  # D5 bucket not collected on this path


def test_normalize_gatling_frames_clamps_negative_offsets(tmp_path):
    """Clock drift may put a request before run start: clamp to 0, never drop."""
    from perf_worker import normalize_gatling_frames

    frames = normalize_gatling_frames(
        [{"name": "x", "startMs": -40, "endMs": 10, "ok": True,
          "message": "", "groups": []}])
    assert frames[0]["secondOffset"] == 0 and frames[0]["rps"] == 1.0


def test_breaker_trip_consumes_gatling_frames(tmp_path):
    """Runtime breaker is frame-list based: a Gatling frame second crossing the
    rate trips exactly like the k6 line (single judgment source reused)."""
    from perf_worker import _breaker_trip, normalize_gatling_frames

    points = []
    # second 0: clean; second 1: all failed; second 2: latest (left unjudged)
    points.append({"name": "x", "startMs": 100, "endMs": 110, "ok": True,
                   "message": "", "groups": []})
    points.append({"name": "x", "startMs": 1000, "endMs": 1010, "ok": False,
                   "message": "500", "groups": []})
    points.append({"name": "x", "startMs": 2000, "endMs": 2010, "ok": True,
                   "message": "", "groups": []})
    frames = normalize_gatling_frames(points)
    assert _breaker_trip(frames, 0.5) == (1, 1.0)


# ---------------------------------------------------------------- dispatch layer


def test_gatling_engine_dispatches_to_run_gatling(tmp_path, monkeypatch):
    """run_perf_task no longer refuses GATLING: it delegates to the engine branch
 (subprocess form) with the snapshot breaker rate, like the k6 branch. The
 artifact handle is the compile manifest (Java DSL route, segment3)."""
    import perf_worker

    # A-10 compatibility read passes an existing local path through untouched —
    # the manifest must exist on disk (a machine-wide /tmp leftover would make
    # this test pass by accident; tmp_path keeps it hermetic).
    manifest = tmp_path / "manifest.json"
    manifest.write_text('{"fqcn": "sim.Sim"}', encoding="utf-8")

    calls = {}

    def fake_run_gatling(artifact, budget_ms, breaker_rate=None, partial_shipper=None,
                         tls_policy=None, events=None):
        # B-10 kwargs wired (tls tier injection reaches the branch)
        calls["tls_wired"] = isinstance(tls_policy, dict)
        calls["args"] = (artifact, budget_ms, breaker_rate)
        calls["shipper_absent_ok"] = partial_shipper is None  # b2 kwarg wired
        return perf_worker.RunOutcome(result="pass", perf_status=2), []

    monkeypatch.setattr(perf_worker, "_run_gatling", fake_run_gatling)
    o = perf_worker.run_perf_task({"taskId": 1, "timeoutMs": 60000, "perf": {
        "runId": 42, "engine": "GATLING", "artifact": str(manifest),
        "pressureSnapshot": '{"sla": {"breakerErrorRate": 0.2}}'}})
    assert o.result == "pass", o.error_detail
    assert calls["args"] == (str(manifest), 60000.0, 0.2)
    # no _perfPartialSink injected = legacy shape (shipper None, zero side effect)
    assert calls["shipper_absent_ok"] is True
    # B-10: claim without a tlsPolicy key = normalized strict default still
    # reaches the branch (zero drift for old core).
    assert calls["tls_wired"] is True


def test_gatling_missing_lib_honest_naming_no_engine_substitution(tmp_path, monkeypatch):
    """Missing Gatling lib dir (GATLING_LIB unset/absent) = human-name the gap,
    error_type 3, and never silently fall back to k6 (mutual-exclusion discipline)."""
    import perf_worker
    import json

    art = tmp_path / "manifest.json"
    art.write_text(json.dumps({"fqcn": "com.sixbean.testpike.perf.gen.PerfGen12",
                               "classesDir": str(tmp_path / "classes"),
                               "sourceFile": str(tmp_path / "PerfGen12.java")}),
                   encoding="utf-8")
    (tmp_path / "classes").mkdir()
    # a k6 shim IS present: substitution would be detectable via the marker file
    marker = tmp_path / "k6-ran"
    shim = tmp_path / "k6"
    shim.write_text(f"#!/bin/sh\ntouch {marker}\n", encoding="utf-8")
    shim.chmod(0o755)
    monkeypatch.setenv("GATLING_LIB", "")  # empty = lib root not provisioned
    old = os.environ.get("PATH", "")
    os.environ["PATH"] = str(tmp_path)  # k6 yes, gatling lib no
    try:
        o = perf_worker.run_perf_task({"taskId": 2, "perf": {
            "runId": 9, "engine": "GATLING", "artifact": str(art),
            "pressureSnapshot": "{}"}})
    finally:
        os.environ["PATH"] = old
    assert o.result == "fail" and o.error_type == 3
    assert "Gatling" in (o.error_detail or "") and "缺" in (o.error_detail or "")
    assert not marker.exists(), "silent k6 substitution is a red-line violation"


def test_gatling_artifact_missing_named_before_lib(tmp_path):
    """Artifact absence is a dispatch-chain break and must be named before the
    environment gap (same ordering as the k6 branch)."""
    import perf_worker

    o = perf_worker.run_perf_task({"taskId": 3, "perf": {
        "runId": 9, "engine": "GATLING", "artifact": str(tmp_path / "gone.json"),
        "pressureSnapshot": "{}"}})
    assert o.result == "fail" and o.error_type == 3
    assert "编译产物" in (o.error_detail or "")


def _fake_gatling_shim(tmp_path, payload: bytes, exit_code: int = 0):
    """PATH-isolated fake `java` launcher: honors the classpath-form invocation
    (`-cp ... io.gatling.app.Gatling -s <fqcn> -rf <results> ...`), records argv
    for command-shape assertions, writes the binary simulation.log under
    <results>/<simulation-lowercase>/<stamp>/ as a finished run would, prints an
    engine banner. Real subprocess, real scan, real parse (shim = material for
    the engine the machine lacks, not a whitelist)."""
    import base64

    b64 = base64.b64encode(payload).decode("ascii")
    # Absolute tool paths: the test isolates PATH to tmp dir (missing-lib
    # scenario), so a bare `mkdir`/`base64` inside the shim would not resolve.
    shim = tmp_path / "java"
    shim.write_text(
        "#!/bin/sh\n"
        "SIM=\"\"\nRF=\"\"\n"
        f"printf '%s\\n' \"$@\" > '{tmp_path}/argv.txt'\n"
        "while [ $# -gt 0 ]; do\n"
        "  case \"$1\" in\n"
        "    -s) shift; SIM=\"$1\";;\n"
        "    -rf) shift; RF=\"$1\";;\n"
        "  esac\n"
        "  shift\n"
        "done\n"
        "echo \"Gatling the load testing tool\"\n"
        f"/bin/mkdir -p \"$RF/$(echo \"$SIM\" | /usr/bin/tr '[:upper:]' '[:lower:]')/run-1\"\n"
        f"printf '%s' '{b64}' | /usr/bin/base64 -d > \"$RF/$(echo \"$SIM\" | /usr/bin/tr '[:upper:]' '[:lower:]')/run-1/simulation.log\"\n"
        f"exit {exit_code}\n",
        encoding="utf-8")
    shim.chmod(0o755)
    return shim


def _manifest_for(tmp_path, simulation: str) -> str:
    """Canonical compile-handle manifest the core GatlingAdapter writes
    (fqcn/classesDir/sourceFile keys, Java DSL route)."""
    import json

    classes = tmp_path / "classes"
    classes.mkdir(exist_ok=True)
    art = tmp_path / "manifest.json"
    art.write_text(json.dumps({
        "fqcn": f"com.sixbean.testpike.perf.gen.{simulation}",
        "classesDir": str(classes),
        "sourceFile": str(tmp_path / f"{simulation}.java")}), encoding="utf-8")
    return str(art)


def _gatling_lib_for(tmp_path) -> str:
    lib = tmp_path / "gatling-lib"
    lib.mkdir(exist_ok=True)
    (lib / "dummy.jar").write_bytes(b"PK\x03\x04fake")
    return str(lib)


def test_run_gatling_real_subprocess_end_to_end(tmp_path, monkeypatch):
    """Fake java launcher (real subprocess via PATH shim) writes the binary log;
    _run_gatling must read the manifest, launch the classpath form, locate the log
    under the results root, attribute frames, and return pass with perf_frames
    populated (lines echoed for tail-style reuse)."""
    import perf_worker

    art = _manifest_for(tmp_path, "PerfGen13T5")
    monkeypatch.setenv("GATLING_LIB", _gatling_lib_for(tmp_path))

    b = LogBuilder()
    b.run("3.13.5", "com.sixbean.testpike.perf.gen.PerfGen13T5", 1_700_000_000_000, "d")
    b.request("home", 0, 40, True)
    b.request("home", 500, 560, False, "500")
    _fake_gatling_shim(tmp_path, b.bytes())

    old = os.environ.get("PATH", "")
    os.environ["PATH"] = str(tmp_path)
    try:
        outcome, _lines = perf_worker._run_gatling(art, 30000.0)
    finally:
        os.environ["PATH"] = old
    assert outcome.result == "pass"
    assert outcome.perf_status == 2
    frames = outcome.perf_frames or []
    assert [f["secondOffset"] for f in frames] == [0]
    assert frames[0]["rps"] == 2.0
    assert frames[0]["errorRate"] == 0.5
    # Command-shape contract (tick 1480 live probe): 3.x CLI has no -sf/-sd —
    # the compiled classes ride the classpath, main class + -s fqcn + -rf + -nr.
    argv = (tmp_path / "argv.txt").read_text(encoding="utf-8").splitlines()
    assert "--add-opens" in argv and "java.base/java.lang=ALL-UNNAMED" in argv
    cp_idx = argv.index("-cp")
    cp = argv[cp_idx + 1]
    assert "classes" in cp and "gatling-lib" in cp
    assert "io.gatling.app.Gatling" in argv
    assert "-nr" in argv
    s_idx = argv.index("-s")
    assert argv[s_idx + 1] == "com.sixbean.testpike.perf.gen.PerfGen13T5"
    # Live-flush contract: the engine buffers simulation.log in an 8 KB
    # FileDataWriter buffer by default, so at low RPS nothing hits the file
    # until exit — the in-press breaker and the kill-switch would then judge
    # an empty log forever. The launcher must shrink the buffer via sys-prop
    # so frames stream while the run is alive (proven by live engine probes).
    buf_prop = [a for a in argv if a.startswith("-Dgatling.data.file.bufferSize=")]
    assert buf_prop and int(buf_prop[0].split("=")[-1]) <= 512, argv


def test_run_gatling_nonzero_exit_engine_tail(tmp_path, monkeypatch):
    """Engine exits non-zero (compile/simulation failure inside CLI): fail with
    error_type 3, engine-exit event carrying the output tail for root-causing."""
    import perf_worker

    art = _manifest_for(tmp_path, "PerfGen14T5")
    monkeypatch.setenv("GATLING_LIB", _gatling_lib_for(tmp_path))
    _fake_gatling_shim(tmp_path, b"", exit_code=7)

    old = os.environ.get("PATH", "")
    os.environ["PATH"] = str(tmp_path)
    try:
        outcome, _ = perf_worker._run_gatling(art, 30000.0)
    finally:
        os.environ["PATH"] = old
    assert outcome.result == "fail" and outcome.error_type == 3
    assert "Gatling" in (outcome.error_detail or "")
    kinds = [e.get("eventKind") for e in (outcome.perf_events or [])]
    assert "engine-exit" in kinds


def test_run_gatling_missing_log_attributed(tmp_path, monkeypatch):
    """Engine exit 0 but no simulation.log (result dir empty): attribute the product
    gap in human words — an engine that ran and recorded nothing must not pass
    as an empty-but-green run (silent failure P-form)."""
    import perf_worker

    art = _manifest_for(tmp_path, "PerfGen15T5")
    monkeypatch.setenv("GATLING_LIB", _gatling_lib_for(tmp_path))

    shim = tmp_path / "java"
    shim.write_text("#!/bin/sh\necho 'Gatling'\nexit 0\n", encoding="utf-8")
    shim.chmod(0o755)

    old = os.environ.get("PATH", "")
    os.environ["PATH"] = str(tmp_path)
    try:
        outcome, _ = perf_worker._run_gatling(art, 30000.0)
    finally:
        os.environ["PATH"] = old
    assert outcome.result == "fail" and outcome.error_type == 3
    assert "simulation.log" in (outcome.error_detail or "") or "结果文件" in (outcome.error_detail or "")
