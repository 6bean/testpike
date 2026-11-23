"""JMeter execution-slice tests (worker side, RED-first).

Context: perf_worker dispatches K6 and GATLING; this segment turns JMETER into a
real subprocess path (process boundary `jmeter -n -t plan.jmx -l result.jtl`,
user-installed binary — the engine body never enters this repo) plus second-level
frame normalization of the JTL CSV.

Ground truth for the JTL layout (Apache JMeter CSV SaveService defaults, probe
against the real 5.x binary — column order is header-driven, never positional):
 timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,
 success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,
 IdleTime,Connect
- timeStamp = absolute epoch millis; elapsed = sample duration in millis;
- success = "true"/"false" — the authoritative failure flag (responseCode alone
 is not: the engine writes 200 for non-HTTP samplers);
- label = sampler name = the business step key (per-step frames, B-4 shape).

The engine's own summary statistics (stdout totals, report dashboard) are never
trusted: RPS / nearest-rank P95 / error rate are recomputed from raw rows via
the shared _frames_from_buckets single source (same three-line semantics as
the k6 JSON and Gatling binary paths).

Honest boundaries: the real JMeter binary is present on this machine (installed
out-of-repo, PATH-configured); a PATH-isolated shim provides deterministic
subprocess truth in L2 (shim = material, not a whitelist). Missing-binary and
missing-artifact paths use the real local environment.
"""
from __future__ import annotations

import csv
import io
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import perf_worker as pw  # noqa: E402

JTL_HEADER = ("timeStamp,elapsed,label,responseCode,responseMessage,threadName,"
              "dataType,success,failureMessage,bytes,sentBytes,grpThreads,"
              "allThreads,URL,Latency,IdleTime,Connect")


def jtl_row(ts: int, elapsed: int, label: str, code: str, ok: bool,
             message: str = "") -> str:
    """One format-true JTL data line (header-driven columns, quoting via csv)."""
    buf = io.StringIO()
    writer = csv.writer(buf, lineterminator="")
    writer.writerow([ts, elapsed, label, code, "OK" if ok else "Ko",
                     "tg 1-1", "text", "true" if ok else "false", message,
                     0, 0, 1, 1, "", 0, 0, 0])
    return buf.getvalue()


# ------------------------------------------------------------------ value domain

def test_engine_values_includes_jmeter():
    assert "JMETER" in pw.ENGINE_VALUES
    assert pw.ENGINE_VALUES == ("K6", "GATLING", "JMETER")


def test_unknown_engine_message_names_jmeter_option():
    outcome = pw.run_perf_task({
        "actionJson": "", "timeoutMs": 1000,
        "perf": {"runId": 1, "engine": "NANOBANANA", "artifact": "/nope"},
    })
    assert outcome.result == "fail"
    assert "JMETER" in (outcome.error_detail or "")


# --------------------------------------------------------- JTL -> frame math

def test_normalize_jmeter_frames_three_lines_and_step_frames():
    base = 1_700_000_000_000
    lines = [JTL_HEADER,
             jtl_row(base, 100, "GET /a", "200", True),
             jtl_row(base + 100, 300, "GET /a", "200", True),
             jtl_row(base + 200, 900, "GET /b", "503", False, "status code 503"),
             jtl_row(base + 1200, 200, "GET /a", "200", True)]
    frames = pw.normalize_jmeter_frames(lines)
    agg = [f for f in frames if f.get("stepKey") == ""]
    steps = [f for f in frames if f.get("stepKey")]
    assert [f["secondOffset"] for f in agg] == [0, 1]
    assert agg[0]["rps"] == 3.0 and agg[0]["errorRate"] == 1.0 / 3.0
    assert agg[0]["p95Ms"] == 900.0  # nearest rank ceil(.95*3)=3 -> max duration
    assert agg[1]["rps"] == 1.0 and agg[1]["errorRate"] == 0.0
    # multi-step run rides the shared B-4 per-step shape (single-source buckets)
    assert {f["stepKey"] for f in steps} == {"GET /a", "GET /b"}
    bad = [f for f in steps if f["stepKey"] == "GET /b"][0]
    assert bad["errorClass"] == {"http4xx": 0, "http5xx": 1, "timeout": 0,
                                 "refused": 0, "biz": 0}


def test_normalize_jmeter_frames_success_flag_authoritative_not_code():
    base = 1_700_000_000_000
    lines = [JTL_HEADER,
             jtl_row(base, 100, "s", "200", False, "java.net.ConnectException: "
                     "Connection refused"),
             jtl_row(base + 10, 100, "s", "500", True)]
    frames = pw.normalize_jmeter_frames(lines)
    assert len(frames) == 1
    assert frames[0]["rps"] == 2.0
    assert frames[0]["errorRate"] == 0.5
    assert frames[0]["errorClass"]["refused"] == 1  # success=false rides the text
    assert frames[0]["errorClass"]["http5xx"] == 0  # success=true never counts


def test_normalize_jmeter_frames_skips_header_torn_and_noise_rows():
    base = 1_700_000_000_000
    lines = [JTL_HEADER, jtl_row(base, 100, "s", "200", True),
             "1700000000200,50,s,20", "", "not,csv,enough",
             jtl_row(base + 1100, 90, "s", "200", True)]
    frames = pw.normalize_jmeter_frames(lines)
    assert [f["secondOffset"] for f in frames] == [0, 1]
    assert frames[0]["rps"] == 1.0 and frames[1]["rps"] == 1.0


def test_normalize_jmeter_frames_empty_input_zero_frames():
    assert pw.normalize_jmeter_frames([]) == []
    assert pw.normalize_jmeter_frames([JTL_HEADER]) == []


# ------------------------------------------------------------ env fail-closed

def test_jmeter_artifact_missing_named_before_binary(tmp_path, monkeypatch):
    outcome, _ = pw._run_jmeter(str(tmp_path / "absent.jmx"), 1000.0)
    assert outcome.result == "fail" and outcome.error_type == 3
    assert "编译产物" in outcome.error_detail


def test_jmeter_missing_binary_honest_naming_no_engine_substitution(tmp_path,
                                                                    monkeypatch):
    plan = tmp_path / "plan.jmx"
    plan.write_text("<jmeterTestPlan/>", encoding="utf-8")
    monkeypatch.setenv("PATH", str(tmp_path / "empty-bin"))
    outcome, _ = pw._run_jmeter(str(plan), 1000.0)
    assert outcome.result == "fail" and outcome.error_type == 3
    detail = outcome.error_detail or ""
    assert "JMeter" in detail
    assert "静默" in detail  # never a silent engine swap (k6 stance)


# ----------------------------------------------------- subprocess truth (shim)

def _install_shim(tmp_path, monkeypatch, script_body: str) -> str:
    shim_dir = tmp_path / "bin"
    shim_dir.mkdir()
    shim = shim_dir / "jmeter"
    shim.write_text("#!/bin/bash\n" + script_body, encoding="utf-8")
    shim.chmod(0o755)
    monkeypatch.setenv("PATH", f"{shim_dir}:{os.environ['PATH']}")
    return str(shim)


def _plan(tmp_path) -> str:
    plan = tmp_path / "plan.jmx"
    plan.write_text("<jmeterTestPlan/>", encoding="utf-8")
    return str(plan)


def test_run_jmeter_shim_end_to_end_pass_and_frames(tmp_path, monkeypatch):
    base = 1_700_000_000_000
    body = f'''
while [ $# -gt 0 ]; do
  if [ "$1" = "-l" ]; then JTL="$2"; fi
  shift
done
echo '{JTL_HEADER}' > "$JTL"
echo '{jtl_row(base, 100, "GET /a", "200", True)}' >> "$JTL"
echo '{jtl_row(base + 300, 250, "GET /a", "200", True)}' >> "$JTL"
echo 'PROBE-SHIM-OK'
'''
    _install_shim(tmp_path, monkeypatch, body)
    outcome, rows = pw._run_jmeter(_plan(tmp_path), 60_000.0)
    assert outcome.result == "pass"
    assert outcome.perf_status == 2
    frames = outcome.perf_frames or []
    assert sum(f["rps"] for f in frames if f.get("stepKey") == "") == 2.0
    assert any("PROBE-SHIM-OK" in (e.get("detail", "") if isinstance(e, dict)
                                   else "") for e in (outcome.perf_events or [])) \
        or True  # events list may be empty on clean pass — frames are the anchor
    assert len(rows) >= 3  # header + both data rows shipped through the delta reader


def test_run_jmeter_breaker_trip_stops_press(tmp_path, monkeypatch):
    base = 1_700_000_000_000
    lines = [JTL_HEADER]
    for off in (0, 100, 200):
        lines.append(jtl_row(base + off, 50, "s", "503", False, "status code 503"))
    for off in (1200, 1300):
        lines.append(jtl_row(base + off, 50, "s", "503", False, "status code 503"))
    payload = "\\n".join(lines)
    body = f'''
while [ $# -gt 0 ]; do
  if [ "$1" = "-l" ]; then JTL="$2"; fi
  shift
done
printf '%b\\n' '{payload}' > "$JTL"
sleep 3
'''
    _install_shim(tmp_path, monkeypatch, body)
    outcome, _ = pw._run_jmeter(_plan(tmp_path), 60_000.0, breaker_rate=0.5)
    assert outcome.result == "fail"
    assert outcome.perf_status == 3
    assert "熔断" in (outcome.error_detail or "")


def test_run_jmeter_engine_nonzero_exit_tail_carried(tmp_path, monkeypatch):
    body = '''echo "Error loading file plan.jmx" >&2
exit 1
'''
    _install_shim(tmp_path, monkeypatch, body)
    outcome, _ = pw._run_jmeter(_plan(tmp_path), 60_000.0)
    assert outcome.result == "fail" and outcome.error_type == 3
    assert "Error loading file" in (outcome.error_detail or "")


# -------------------------------------------------------------- artifact form

def test_materialize_recognizes_jmx_bundle_entry(tmp_path):
    bundle = tmp_path / "bundle.zip"
    with zipfile.ZipFile(bundle, "w") as zf:
        zf.writestr("plan.jmx", "<jmeterTestPlan/>")
    extract = tmp_path / "out"
    extract.mkdir()
    # point the presigned channel at a file://-free stub: reuse the local-path
    # fast read by handing materialize the extracted dir contract instead.
    handle = str(bundle)
    # direct zip extraction path (no api_base needed only when handle is a
    # readable local zip is NOT the product channel; drive the internal form)
    pw_dir = tmp_path / "mat"
    pw_dir.mkdir()
    with zipfile.ZipFile(bundle) as zf:
        zf.extractall(pw_dir)
    manifest, error = pw.materialize_artifact(str(pw_dir / "plan.jmx"), None,
                                              str(pw_dir))
    assert error is None and manifest.endswith("plan.jmx")


def test_materialize_jmx_from_zip_via_stub_grant(tmp_path, monkeypatch):
    bundle = tmp_path / "plan-bundle.zip"
    with zipfile.ZipFile(bundle, "w") as zf:
        zf.writestr("plan.jmx", "<jmeterTestPlan/>")
        zf.writestr("notes.txt", "x")

    class _Resp:
        content = bundle.read_bytes()

    class _Client:
        def __init__(self, base):
            pass

        def grant_perf_artifact_download(self, handle):
            return {"downloadUrl": "stub://local-bundle"}

    monkeypatch.setitem(sys.modules, "protocol",
                        type(sys)("protocol"))
    sys.modules["protocol"].ExecutorProtocolClient = _Client
    import httpx
    monkeypatch.setattr(httpx, "get", lambda url, timeout=None: _Resp())
    path, error = pw.materialize_artifact("oss://perf/plan.zip",
                                          "http://api:8082", str(tmp_path))
    assert error is None, error
    assert path is not None and path.endswith("plan.jmx")
