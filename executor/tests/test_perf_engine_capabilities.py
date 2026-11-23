# -*- coding: utf-8 -*-
"""Perf-engine capability probing .

The load engines k6 and Gatling are user-installed at the process boundary
(AGPL bodies never shipped in this repo). Dispatch correctness depends on the
executor reporting honestly whether this machine can actually run each engine:

- K6 : present iff the k6 binary is on PATH (no language runtime gate).
- GATLING: present iff java >= 17 is on PATH *and* GATLING_LIB points at an
 existing directory (the engine jars root). Missing either = engine absent
 from the report — a false claim would mis-dispatch a run onto a machine
 that can only fail it.

Component families (heartbeat components[]) mirror the same facts:
engine-runtime.k6 (binary version) and engine-runtime.gatling (version read
from the gatling-app jar inside GATLING_LIB). Probe failure = family omitted,
never a None placeholder row.

Stub note: the injected which/run_version fixtures are the probe recipe
itself under test (same posture as the app-auto capability tests); the
live-probe section runs the real detection unmocked.
"""
from __future__ import annotations

import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from capabilities import (ENGINE_REQUIREMENTS, collect_components,
                          probe_capabilities)

GATLING_LIB = "GATLING_LIB"


def _fake_probe(tools=None, versions=None, host_os="linux", exists=(), env=None):
    """Inject the probe surface: tools=set of binaries on PATH,
    versions={exe: version text}, exists=extra paths treated as present,
    env={name: value} overlaid onto os.environ for this probe."""
    tools = set(tools or [])
    versions = versions or {}
    present = set(exists)

    def which(name):
        return f"/usr/bin/{name}" if name in tools else None

    def run_version(exe):
        return versions.get(exe)

    import capabilities as cap
    real_exists = cap.os.path.exists
    real_isdir = cap.os.path.isdir
    real_environ = cap.os.environ
    real_java_text = cap._java_version_text
    cap.os.path.exists = lambda p: p in present or real_exists(p)
    cap.os.path.isdir = lambda p: p in present or real_isdir(p)
    cap._java_version_text = lambda exe: versions.get("java")
    base_env = dict(real_environ)
    base_env.pop(GATLING_LIB, None)
    base_env.update(env or {})
    cap.os.environ = base_env
    try:
        return probe_capabilities(which=which, run_version=run_version,
                                  host_os=host_os)
    finally:
        cap.os.path.exists = real_exists
        cap.os.path.isdir = real_isdir
        cap.os.environ = real_environ
        cap._java_version_text = real_java_text


# -- engine report surface --------------------------------------------------

def test_k6_present_reports_engine():
    """k6 on PATH -> K6 enters engines[] (claim filtering consumes this)."""
    caps = _fake_probe(tools=["k6"])
    assert "K6" in caps["engines"]
    assert caps["tools"]["k6"] is True


def test_k6_missing_omits_engine():
    """No k6 binary -> K6 absent from engines[] (claiming it would
    mis-dispatch a load run onto a machine that cannot start the engine)."""
    caps = _fake_probe(tools=["node"])
    assert "K6" not in caps["engines"]
    assert caps["tools"]["k6"] is False


def test_gatling_needs_java_and_lib_dir():
    """GATLING gates on java>=17 AND an existing GATLING_LIB directory;
    each missing precondition alone must keep the engine unreported."""
    ok = _fake_probe(tools=["java"], versions={"java": "openjdk 21.0.2"},
                     exists=["/opt/gatling/lib"],
                     env={GATLING_LIB: "/opt/gatling/lib"})
    assert "GATLING" in ok["engines"]

    no_lib = _fake_probe(tools=["java"], versions={"java": "openjdk 21.0.2"},
                         env={GATLING_LIB: "/opt/gatling/lib"})
    assert "GATLING" not in no_lib["engines"]

    no_env = _fake_probe(tools=["java"], versions={"java": "openjdk 21.0.2"},
                         exists=["/opt/gatling/lib"])
    assert "GATLING" not in no_env["engines"]

    old_java = _fake_probe(tools=["java"], versions={"java": "11.0.2"},
                           exists=["/opt/gatling/lib"],
                           env={GATLING_LIB: "/opt/gatling/lib"})
    assert "GATLING" not in old_java["engines"]


def test_perf_engines_declared_in_requirements():
    """Both load engines carry machine-side prerequisites in the table
    (HYPIUM-style halted engines must never appear here at all)."""
    assert "K6" in ENGINE_REQUIREMENTS
    assert "GATLING" in ENGINE_REQUIREMENTS


# -- live probe (real machine, unmocked) -------------------------------------

def test_live_probe_k6_matches_binary_presence():
    caps = probe_capabilities()
    if shutil.which("k6"):
        assert "K6" in caps["engines"]
    else:
        assert "K6" not in caps["engines"]


def test_live_probe_gatling_honest_without_lib_dir():
    """GATLING_LIB unset on this machine -> engine must be absent, never a
    guess based on java presence alone."""
    if GATLING_LIB not in os.environ:
        assert "GATLING" not in probe_capabilities()["engines"]


# -- heartbeat component families --------------------------------------------

def test_component_k6_family_reports_version():
    which = lambda name: "/usr/bin/k6" if name == "k6" else None
    run_version = lambda exe, flag="--version": (
        "k6 v0.55.0 (commit/90bb9415d0, go1.23.3, linux/amd64)" if exe == "k6" else "")
    comps = collect_components(which=which, run_version=run_version)
    rows = {c["family"]: c["currentVersion"] for c in comps}
    assert rows.get("engine-runtime.k6") == "0.55.0"


def test_component_k6_family_absent_when_probe_dirty():
    """Binary on PATH but version text unparsable -> family omitted
    (no None placeholder rows, same honesty as every other family)."""
    which = lambda name: "/usr/bin/k6" if name == "k6" else None
    comps = collect_components(which=which, run_version=lambda exe, flag="--version": "")
    assert "engine-runtime.k6" not in {c["family"] for c in comps}


def test_component_gatling_family_reads_jar_version(monkeypatch):
    """Version source = gatling-app-<ver>.jar inside GATLING_LIB (the jar
    names are the only trustworthy version stamp; missing dir/jar = absent)."""
    import capabilities as cap
    monkeypatch.setenv(GATLING_LIB, "/opt/gatling/lib")
    real_isdir = cap.os.path.isdir
    cap.os.path.isdir = lambda p: True if p == "/opt/gatling/lib" else real_isdir(p)
    real_scandir = os.scandir
    files = ["/opt/gatling/lib/gatling-app-3.11.6.jar",
             "/opt/gatling/lib/gatling-core-3.11.6.jar"]

    class _Entry:
        def __init__(self, path):
            self.path = path

    def scandir(path):
        if path == "/opt/gatling/lib":
            return iter([_Entry(f) for f in files])
        return real_scandir(path)

    cap.os.scandir = scandir
    try:
        comps = collect_components(which=lambda name: None,
                                   run_version=lambda exe, flag="--version": "")
    finally:
        cap.os.scandir = real_scandir
    rows = {c["family"]: c["currentVersion"] for c in comps}
    assert rows.get("engine-runtime.gatling") == "3.11.6"


def test_component_gatling_family_absent_without_lib_dir(monkeypatch):
    import capabilities as cap
    monkeypatch.delenv(GATLING_LIB, raising=False)
    comps = collect_components(which=lambda name: None,
                               run_version=lambda exe, flag="--version": "")
    assert "engine-runtime.gatling" not in {c["family"] for c in comps}
