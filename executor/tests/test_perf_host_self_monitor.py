"""B-5 segment: load-engine host self-monitoring rides partial frames.

Caliber pinned (collection side single source; ingestion gates stay at core
PerfMetricService -- no second rule set here):
- collect_host_stats reads the linux procfs trio (loadavg / meminfo / cpu
 count) from an injectable root; every successful sample returns
 {load1, cores, loadRatio, memAvailMb} -- loadRatio = load1 / cores so the
 strain threshold stays machine-independent (a 4-core and a 64-core engine
 compare against the same ratio);
- non-linux host / unreadable proc files / dirty values = None (honest
 absence: no key rides the line file, never a 0-substituted fake sample --
 the D3 degradation disclosure reads this absence downstream);
- ship_partial_batch tails the hostStats key onto the row only when a sample
 exists (zero-drift trailing-key posture of monitorFrames/perfReports: old
 rows keep their exact shape);
- make_partial_shipper samples once per due flush window (not per frame:
 the flush cadence is already the K8-keyed heartbeat budget) and threads
 the sample through ship_partial_batch.
"""
from __future__ import annotations

import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from perf_worker import collect_host_stats, make_partial_shipper, ship_partial_batch  # noqa: E402


def _write_proc(root: pathlib.Path, loadavg: str, meminfo: str, n_cpu: int = 4) -> None:
    (root / "proc").mkdir(parents=True, exist_ok=True)
    (root / "proc" / "loadavg").write_text(loadavg, encoding="utf-8")
    (root / "proc" / "meminfo").write_text(meminfo, encoding="utf-8")
    # cpu count source: the collector must not trust os.cpu_count() blindly on
    # cgroup-limited hosts, but keeps the procfs/stat-free minimal
    # form -- a test host passes n_cpu via the mock below only where needed.


LIVE = (pathlib.Path("/nonexistent-proc-root"))


class TestCollectHostStats:
    """collect_host_stats: procfs trio -> normalized sample, honest None."""

    def test_reads_loadavg_meminfo_and_cores(self, tmp_path, monkeypatch):
        proc = tmp_path / "proc"
        proc.mkdir()
        (proc / "loadavg").write_text("3.50 2.10 1.05 1/200 12345\n", encoding="utf-8")
        (proc / "meminfo").write_text(
            "MemTotal:       16384 kB\nMemAvailable:    8192 kB\n", encoding="utf-8")
        monkeypatch.setattr("os.cpu_count", lambda: 4, raising=False)
        stats = collect_host_stats(str(proc))
        assert stats == {"load1": 3.5, "cores": 4, "loadRatio": 3.5 / 4,
                         "memAvailMb": 8.0}

    def test_dirty_loadavg_line_is_honest_absence(self, tmp_path, monkeypatch):
        proc = tmp_path / "proc"
        proc.mkdir()
        (proc / "loadavg").write_text("not-a-number\n", encoding="utf-8")
        (proc / "meminfo").write_text("MemAvailable: 1 kB\n", encoding="utf-8")
        monkeypatch.setattr("os.cpu_count", lambda: 2, raising=False)
        assert collect_host_stats(str(proc)) is None

    def test_missing_proc_root_is_honest_absence(self, tmp_path, monkeypatch):
        monkeypatch.setattr("os.cpu_count", lambda: 2, raising=False)
        assert collect_host_stats(str(tmp_path / "no-such-proc")) is None

    def test_zero_cores_never_divides(self, tmp_path, monkeypatch):
        proc = tmp_path / "proc"
        proc.mkdir()
        (proc / "loadavg").write_text("0.50 0.40 0.30 1/1 1\n", encoding="utf-8")
        (proc / "meminfo").write_text("MemAvailable: 512 kB\n", encoding="utf-8")
        monkeypatch.setattr("os.cpu_count", lambda: 0, raising=False)
        # cores unavailable = ratio cannot be computed honestly -> None sample
        assert collect_host_stats(str(proc)) is None


class TestShipRowHostStats:
    """ship_partial_batch: trailing hostStats key only when a sample exists."""

    def test_row_without_sample_keeps_legacy_shape(self, tmp_path):
        sink = str(tmp_path / "partial.jsonl")
        ship_partial_batch(sink, 7, 1, [{"secondOffset": 0, "rps": 1.0}],
                           host_stats=None)
        row = json.loads(pathlib.Path(sink).read_text(encoding="utf-8").strip())
        assert set(row.keys()) == {"runId", "seq", "frames"}

    def test_row_with_sample_tails_host_stats(self, tmp_path):
        sink = str(tmp_path / "partial.jsonl")
        sample = {"load1": 2.0, "cores": 4, "loadRatio": 0.5, "memAvailMb": 1024.0}
        ship_partial_batch(sink, 7, 2, [{"secondOffset": 1, "rps": 2.0}],
                           host_stats=sample)
        row = json.loads(pathlib.Path(sink).read_text(encoding="utf-8").strip())
        assert row["hostStats"] == sample


class TestShipperSamplesPerWindow:
    """make_partial_shipper: one procfs sample per due flush window."""

    def test_due_window_row_carries_sample(self, tmp_path, monkeypatch):
        proc = tmp_path / "proc"
        proc.mkdir()
        (proc / "loadavg").write_text("1.00 1.00 1.00 1/1 1\n", encoding="utf-8")
        (proc / "meminfo").write_text("MemAvailable: 2048 kB\n", encoding="utf-8")
        monkeypatch.setattr("os.cpu_count", lambda: 4, raising=False)
        monkeypatch.setattr("perf_worker.HOST_PROC_ROOT", str(proc), raising=False)
        sink = str(tmp_path / "partial.jsonl")
        ship = make_partial_shipper({"sink": sink, "runId": 9, "flushSeconds": 1})
        assert ship is not None
        ship([{"secondOffset": 0, "rps": 1.0}])
        rows = [json.loads(l) for l in pathlib.Path(sink).read_text(
            encoding="utf-8").strip().splitlines()]
        assert len(rows) == 1 and rows[0]["hostStats"]["load1"] == 1.0

    def test_no_proc_on_host_ships_frames_without_key(self, tmp_path, monkeypatch):
        monkeypatch.setattr("perf_worker.HOST_PROC_ROOT",
                            str(tmp_path / "missing-proc"), raising=False)
        sink = str(tmp_path / "partial.jsonl")
        ship = make_partial_shipper({"sink": sink, "runId": 10, "flushSeconds": 1})
        ship([{"secondOffset": 0, "rps": 1.0}])
        row = json.loads(pathlib.Path(sink).read_text(encoding="utf-8").strip())
        assert "hostStats" not in row
