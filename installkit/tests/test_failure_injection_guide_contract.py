# -*- coding: utf-8 -*-
"""T293d failure-injection contracts: known install failures must be detected
from captured output and answered with an actionable recovery note (cause with
the offending value named, a literal fix entry, and the resume command).

Failure classes covered by the batch definition: network egress death, host
port already taken, target database already exists, insufficient disk space.
L2 unit face: pure classification/teaching functions plus the preflight flag
that consumes them; real clean-machine install runs land with the matrix
package later in this batch.
"""
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
PKG_PROBE = ROOT / "pkg_probe.py"
sys.path.insert(0, str(ROOT))

import failure_guide  # noqa: E402

# Internal governance wording must never reach installer stdout (screen rule).
BANNED_ON_SCREEN = ("人话", "拒件", "裁决", "单源", "棘轮", "收口", "出账")


def _lines(*parts):
    joined = "\n".join(parts)
    for word in BANNED_ON_SCREEN:
        assert word not in joined, "screen wording must not carry %r" % word
    return joined


# ---------- classify ----------

def test_classify_detects_each_known_failure_family():
    assert failure_guide.classify_failure(
        "write /var/lib/docker/...: no space left on device") == "disk_short"
    assert failure_guide.classify_failure(
        'ERROR: database "seaweedfs" already exists') == "pg_existing"
    assert failure_guide.classify_failure(
        "dial tcp: lookup registry-1.docker.io: "
        "Temporary failure in name resolution") == "egress_dead"
    assert failure_guide.classify_failure(
        "bind for 0.0.0.0:15432: address already in use") == "port_conflict"
    assert failure_guide.classify_failure(
        "Error response from daemon: port is already allocated") == "port_conflict"


def test_classify_unknown_output_returns_none():
    assert failure_guide.classify_failure("totally unrelated message") is None


# ---------- teach (three elements: named value / fix entry / rerun) ----------

def test_teach_port_conflict_names_port_probe_and_env_key():
    text = failure_guide.teach("port_conflict", port=15432, key="PG_HOST_PORT")
    _lines(text)
    assert "15432" in text
    assert "ss -ltnp" in text           # how to find the current listener
    assert "PG_HOST_PORT" in text       # literal key to change
    assert ".env" in text
    assert "install.sh --scenario=" in text  # resume entry, never "refresh"


def test_teach_disk_short_names_numbers_and_capacity_entry():
    text = failure_guide.teach("disk_short", required_mb=10240, available_mb=800)
    _lines(text)
    assert "10240" in text and "800" in text
    assert "df -h" in text
    assert "docker system prune" in text  # concrete reclaim entry
    assert "install.sh --scenario=" in text


def test_teach_pg_existing_offers_keep_or_rename_and_warns_before_drop():
    text = failure_guide.teach("pg_existing", db="seaweedfs")
    _lines(text)
    assert "seaweedfs" in text
    assert "DB_URL" in text or "SWF_META_DB_NAME" in text  # rename entry
    assert text and "kept" in text and "rename" in text  # two real paths
    assert "sure" in text                               # do not drop blindly
    assert "install.sh --scenario=" in text


def test_teach_egress_dead_names_target_and_mirror_fallback():
    text = failure_guide.teach("egress_dead", target="registry-1.docker.io")
    _lines(text)
    assert "registry-1.docker.io" in text
    assert "--mirror-base-url" in text   # proven alternate source flag
    assert "install.sh --scenario=" in text


def test_teach_unknown_kind_degrades_to_none():
    assert failure_guide.teach(None) is None


# ---------- preflight wiring ----------

def _run_preflight(*args):
    return subprocess.run(
        [sys.executable, str(PKG_PROBE), "preflight", *args],
        capture_output=True, text=True, timeout=30)


def test_preflight_disk_flag_reports_and_blocks_when_short(tmp_path):
    ok = _run_preflight("--require-disk-mb=1", "--disk-root=%s" % tmp_path)
    assert ok.returncode == 0, ok.stderr
    assert "disk:" in ok.stdout

    # 2^40 MB can never be available: fail closed with the recovery note.
    bad = _run_preflight("--require-disk-mb=1099511627776",
                         "--disk-root=%s" % tmp_path)
    assert bad.returncode == 3
    joined = _lines(bad.stdout, bad.stderr)
    assert "df -h" in joined and "install.sh --scenario=" in joined


def test_preflight_without_disk_flag_keeps_legacy_report(tmp_path):
    r = _run_preflight("--ports=45999", "--disk-root=%s" % tmp_path)
    assert r.returncode == 0, r.stderr
    assert "45999" in r.stdout
