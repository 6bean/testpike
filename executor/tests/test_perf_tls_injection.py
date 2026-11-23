"""B-10 executor-side TLS tier injection for load engines.

Background: the claim envelope now trails the resolved environment TLS tier
``tlsPolicy`` (core side landed in , commit a3887291). This package =
the executor consumption half: translate the tier into per-engine launcher
arguments, deciding nothing ourselves (single source = common
TlsContextResolver through EnvTlsSupport).

Pinned spectra (live-proven channels, fixture against a self-signed
endpoint):
- k6 insecure = CLI flag --insecure-skip-tls-verify (script options.tls.* and
 the K6_TLS_INSECURE_SKIP_VERIFY env var were both measured ineffective on
 k6 v0.55 — the flag is the ONLY working channel);
- k6 custom_ca = NO injection point (the CLI has no CA flag): the k6 branch
 keeps strict engine verification and NEVER silently relaxes to insecure;
- Gatling insecure = -Dgatling.http.ssl.disable-certification=true;
- Gatling custom_ca = CA material -> PKCS12 trust store via keytool (real
 binary, real cert) handed over with -Djavax.net.ssl.trustStore*;
- Gatling custom_ca without material = fail-closed human-worded, engine never
 launched (mirror of driver/tls_client.http_tls_kwargs stance);
- legacy shape: no tlsPolicy key on the claim = strict default, zero drift on
 both engine argvs; dirty mode value = fail-safe strict (mirror).

mock scope: only the engine subprocess spawn is stubbed (L2 isolation — we
assert the launcher argv, not engine behaviour); tier translation, keytool
trust-store build and normalization are real code paths.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import perf_worker  # noqa: E402
from driver.perf_tls import (  # noqa: E402
    GatlingTlsError,
    gatling_tls_properties,
    k6_tls_flags,
)
from driver.tls_client import normalize_policy  # noqa: E402

INSECURE_FLAG = "--insecure-skip-tls-verify"


class _StubProc:
    """Fake engine process: records argv, exits 0 immediately on poll()."""

    launched: list[list[str]] = []

    def __init__(self, argv, **_kwargs):
        _StubProc.launched.append(list(argv))
        self.returncode = 0

    def poll(self):
        return 0

    def kill(self):
        pass

    def wait(self, timeout=None):
        return 0


_real_popen = subprocess.Popen


@pytest.fixture(autouse=True)
def _stub_engine_popen(monkeypatch):
    """Stub only the ENGINE launch (argv[0] basename k6/java — the two engine
    launcher binaries): keytool/openssl materialization stays a real
    subprocess so the trust-store path is exercised for real."""
    _StubProc.launched = []

    def smart_popen(argv, **kwargs):
        exe = os.path.basename(str(argv[0]))
        if exe in ("k6", "java"):
            return _StubProc(argv, **kwargs)
        return _real_popen(argv, **kwargs)

    monkeypatch.setattr(subprocess, "Popen", smart_popen)
    return _StubProc


@pytest.fixture(scope="module")
def ca_pem(tmp_path_factory) -> str:
    """Real self-signed CA cert via openssl (keytool rejects fake PEM)."""
    out = tmp_path_factory.mktemp("ca") / "ca.pem"
    subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
         "-keyout", os.devnull, "-out", str(out), "-days", "1",
         "-subj", "/CN=tp-test-ca"],
        check=True, capture_output=True)
    return out.read_text(encoding="utf-8")


def _policy(mode, ca_pem=None):
    return normalize_policy({"mode": mode, "caPem": ca_pem})


# ------------------------------------------------------------------ pure maps
class TestTierTranslation:
    def test_k6_insecure_gets_cli_flag(self):
        assert k6_tls_flags(_policy("insecure")) == [INSECURE_FLAG]

    def test_k6_strict_gets_nothing(self):
        assert k6_tls_flags(_policy("strict")) == []

    def test_k6_custom_ca_never_silently_relaxes(self):
        # No k6 CA channel: custom_ca must NOT become insecure.
        assert INSECURE_FLAG not in k6_tls_flags(_policy("custom_ca", "PEM"))

    def test_dirty_mode_fails_safe_to_strict(self):
        assert normalize_policy({"mode": "INSECUER"})["mode"] == "strict"
        assert k6_tls_flags(normalize_policy(None)) == []


class TestGatlingProperties:
    def test_insecure_rides_engine_switch(self):
        assert gatling_tls_properties(_policy("insecure"), "/tmp") == [
            "-Dgatling.http.ssl.disable-certification=true"]

    def test_strict_adds_no_properties(self):
        assert gatling_tls_properties(_policy("strict"), "/tmp") == []

    def test_custom_ca_builds_real_trust_store(self, tmp_path, ca_pem):
        props = gatling_tls_properties(_policy("custom_ca", ca_pem), str(tmp_path))
        store = next(p.split("=", 1)[1] for p in props
                     if p.startswith("-Djavax.net.ssl.trustStore="))
        assert store != "null"
        assert any(p.startswith("-Djavax.net.ssl.trustStorePassword=") for p in props)
        assert "-Djavax.net.ssl.trustStoreType=PKCS12" in props

    def test_custom_ca_without_material_fails_closed(self, tmp_path):
        with pytest.raises(GatlingTlsError):
            gatling_tls_properties(_policy("custom_ca", None), str(tmp_path))


# ------------------------------------------------------------------ envelope wiring
def _k6_task(tmp_path, tls_policy=None):
    artifact = tmp_path / "sim.js"
    artifact.write_text("// compiled k6 script\n", encoding="utf-8")
    perf = {"runId": 1, "engine": "K6", "artifact": str(artifact),
            "pressureSnapshot": json.dumps({"sla": {}})}
    if tls_policy is not None:
        perf["tlsPolicy"] = tls_policy
    return {"taskId": 1, "actionJson": json.dumps({"kind": "perf-run"}),
            "timeoutMs": 5000, "perf": perf}


class TestRunPerfTaskWiring:
    def test_insecure_claim_reaches_k6_argv(self, tmp_path, monkeypatch):
        monkeypatch.setattr(perf_worker.shutil, "which",
                            lambda name: "/usr/local/bin/k6" if name == "k6" else None)
        outcome = perf_worker.run_perf_task(_k6_task(tmp_path, {"mode": "insecure"}))
        assert outcome.result == "pass"
        assert INSECURE_FLAG in _StubProc.launched[0]

    def test_legacy_claim_without_tls_key_zero_drift(self, tmp_path, monkeypatch):
        monkeypatch.setattr(perf_worker.shutil, "which",
                            lambda name: "/usr/local/bin/k6" if name == "k6" else None)
        outcome = perf_worker.run_perf_task(_k6_task(tmp_path, None))
        assert outcome.result == "pass"
        assert INSECURE_FLAG not in _StubProc.launched[0]

    def test_gatling_custom_ca_rides_jvm_trust_store(self, tmp_path, monkeypatch, ca_pem):
        lib = tmp_path / "gatling-lib"
        lib.mkdir()
        classes = tmp_path / "classes"
        classes.mkdir()
        manifest = tmp_path / "manifest.json"
        manifest.write_text(json.dumps({"fqcn": "sim.Sim", "classesDir": str(classes)}),
                            encoding="utf-8")
        monkeypatch.setenv("GATLING_LIB", str(lib))
        task = _k6_task(tmp_path, {"mode": "custom_ca", "caPem": ca_pem})
        task["perf"]["engine"] = "GATLING"
        task["perf"]["artifact"] = str(manifest)
        outcome = perf_worker.run_perf_task(task)
        argv = _StubProc.launched[0]
        assert any(a.startswith("-Djavax.net.ssl.trustStore=") for a in argv), \
            f"Gatling custom_ca 必须以 trustStore 注入 java 启动参数: {argv}"

    def test_gatling_custom_ca_missing_material_names_gap_and_never_launches(
            self, tmp_path, monkeypatch):
        lib = tmp_path / "gatling-lib"
        lib.mkdir()
        classes = tmp_path / "classes"
        classes.mkdir()
        manifest = tmp_path / "manifest.json"
        manifest.write_text(json.dumps({"fqcn": "sim.Sim", "classesDir": str(classes)}),
                            encoding="utf-8")
        monkeypatch.setenv("GATLING_LIB", str(lib))
        task = _k6_task(tmp_path, {"mode": "custom_ca"})
        task["perf"]["engine"] = "GATLING"
        task["perf"]["artifact"] = str(manifest)
        outcome = perf_worker.run_perf_task(task)
        assert outcome.result == "fail"
        assert "CA" in (outcome.error_detail or "")
        assert _StubProc.launched == []  # fail-closed before engine launch
