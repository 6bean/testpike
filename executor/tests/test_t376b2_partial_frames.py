""" segment b2: partial frames ride heartbeats (03 §2.2 perfPartial trailing key).

Caliber pinned (collection side single source; ingestion gates stay at core
PerfMetricService — no second rule set here):
- select_new_frames = incremental watermark: only frames whose secondOffset is
 strictly greater than the shipped watermark leave the worker (replay guard);
 the new watermark = max offset shipped this batch (never regressed);
- ship_partial_batch writes one JSONL key row {runId, seq, frames} per flush
 window (sink absent / frames empty = zero write, zero key — the trailing-key
 zero-drift posture of monitorFrames/perfReports);
- runner.drain_perf_partial_rows reads each task line file incrementally
 (byte offset advances = never resend), skips broken lines honestly, caps one
 round at PERF_PARTIAL_DRAIN_MAX_ROWS rows;
- send_heartbeat tails the perfPartial key (empty/None = key absent, old core
 zero drift); a broken heartbeat channel requeues drained rows — frames must
 never silently evaporate;
- core reverse-lookup guards misdelivery (rows carry runId only — the executor
 never names the project), covered by the core-side ingestor test.
"""
from __future__ import annotations

import json
import pathlib
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import ClassVar

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from perf_worker import select_new_frames, ship_partial_batch  # noqa: E402


class TestIncrementalWatermark:
    """select_new_frames: replay guard by secondOffset watermark (pure function)."""

    def _frame(self, off: int, rps: float = 1.0) -> dict:
        return {"secondOffset": off, "rps": rps, "p95Ms": 10.0, "errorRate": 0.0}

    def test_only_frames_above_watermark_ship(self):
        frames = [self._frame(0), self._frame(1), self._frame(2), self._frame(3)]
        fresh, water = select_new_frames(frames, watermark=1)
        assert [f["secondOffset"] for f in fresh] == [2, 3]
        assert water == 3

    def test_watermark_never_regresses_on_empty_batch(self):
        fresh, water = select_new_frames([self._frame(1)], watermark=5)
        assert fresh == []
        assert water == 5  # old water stays — a late low-offset frame drags nothing back

    def test_unsorted_input_water_still_max_offset(self):
        frames = [self._frame(4), self._frame(2), self._frame(7)]
        fresh, water = select_new_frames(frames, watermark=1)
        assert len(fresh) == 3
        assert water == 7


class TestPartialBatchShipping:
    """ship_partial_batch: one {runId, seq, frames} JSONL row per flush window."""

    def test_key_row_shape_matches_protocol_contract(self, tmp_path):
        sink = str(tmp_path / "tp-perf-partial-77.jsonl")
        frames = [{"secondOffset": 0, "rps": 12.0, "p95Ms": 88.0, "errorRate": 0.0}]
        shipped = ship_partial_batch(sink, run_id=77, seq=1, frames=frames)
        assert shipped == 1
        rows = [json.loads(l) for l in
                pathlib.Path(sink).read_text(encoding="utf-8").splitlines()]
        assert rows == [{"runId": 77, "seq": 1, "frames": frames}]

    def test_empty_batch_writes_nothing(self, tmp_path):
        sink = str(tmp_path / "tp-perf-partial-78.jsonl")
        assert ship_partial_batch(sink, run_id=78, seq=1, frames=[]) == 0
        assert not pathlib.Path(sink).exists()

    def test_sink_absent_is_noop(self):
        assert ship_partial_batch(None, run_id=79, seq=1,
                                  frames=[{"secondOffset": 0}]) == 0


class TestChunkedShipping:
    """ : one shipped row never exceeds the core single-batch gate.

 The gate (PerfMetricService MAX_BATCH_FRAMES) refuses an over-limit batch
 whole — with multi-step runs (1 aggregate + N per-step frames per second) a
 raised flush cadence used to drop the entire window. The executor now chunks:
 every row <= max_ship_frames, seq increments per chunk, order preserved,
 total never lost; the limit is projected by core as perf.maxShipFrames
 (single source = the gate constant), executor falls back to the shipped
 default only for legacy envelopes."""

    def _frames(self, n: int, start: int = 0) -> list[dict]:
        return [{"secondOffset": start + i, "rps": 1.0, "errorRate": 0.0}
                for i in range(n)]

    def test_oversized_batch_splits_into_gate_sized_rows(self, tmp_path):
        sink = str(tmp_path / "tp-perf-partial-82.jsonl")
        shipped = ship_partial_batch(sink, run_id=82, seq=1,
                                     frames=self._frames(10), max_ship_frames=4)
        assert shipped == 10  # every frame accounted for, nothing dropped
        rows = [json.loads(l) for l in pathlib.Path(sink).read_text(
            encoding="utf-8").splitlines()]
        assert [r["seq"] for r in rows] == [1, 2, 3]
        assert [len(r["frames"]) for r in rows] == [4, 4, 2]
        flat = [f["secondOffset"] for r in rows for f in r["frames"]]
        assert flat == list(range(10))  # order preserved across chunks

    def test_batch_within_limit_stays_single_row(self, tmp_path):
        sink = str(tmp_path / "tp-perf-partial-83.jsonl")
        assert ship_partial_batch(sink, run_id=83, seq=5,
                                  frames=self._frames(4), max_ship_frames=4) == 4
        rows = pathlib.Path(sink).read_text(encoding="utf-8").splitlines()
        assert len(rows) == 1
        assert json.loads(rows[0])["seq"] == 5

    def test_dirty_limit_falls_back_to_default(self, tmp_path):
        sink = str(tmp_path / "tp-perf-partial-84.jsonl")
        from perf_worker import DEFAULT_MAX_SHIP_FRAMES
        assert DEFAULT_MAX_SHIP_FRAMES == 7200
        shipped = ship_partial_batch(sink, run_id=84, seq=1,
                                     frames=self._frames(3), max_ship_frames=0)
        assert shipped == 3
        row = json.loads(pathlib.Path(sink).read_text(encoding="utf-8").strip())
        assert len(row["frames"]) == 3  # default ceiling = no split needed

    def test_shipper_chunks_over_limit_window(self, tmp_path):
        from perf_worker import make_partial_shipper
        sink = str(tmp_path / "tp-perf-partial-85.jsonl")
        ship = make_partial_shipper({"sink": sink, "runId": 85,
                                     "flushSeconds": 0.001, "maxShipFrames": 3})
        assert ship is not None
        ship(self._frames(7))  # one due window, 7 fresh frames > 3 per row
        rows = [json.loads(l) for l in pathlib.Path(sink).read_text(
            encoding="utf-8").splitlines()]
        assert [len(r["frames"]) for r in rows] == [3, 3, 1]
        assert [r["seq"] for r in rows] == [1, 2, 3]

    def test_shipper_reads_projected_limit_from_perf_context(self, tmp_path):
        from perf_worker import make_partial_shipper
        sink = str(tmp_path / "tp-perf-partial-86.jsonl")
        ship = make_partial_shipper({"sink": sink, "runId": 86,
                                     "flushSeconds": 0.001})
        ship(self._frames(5))  # no projected limit = shipped default 7200, one row
        rows = pathlib.Path(sink).read_text(encoding="utf-8").splitlines()
        assert len(rows) == 1


class _RecorderHandler(BaseHTTPRequestHandler):
    """Real-socket recorder (pit 2 posture: HTTP/1.1 + explicit Content-Length)."""

    protocol_version = "HTTP/1.1"
    seen: ClassVar[list] = []

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length) if length else b"{}"
        type(self).seen.append(json.loads(raw or b"{}"))
        payload = b'{"code":0,"data":{"ok":true,"actions":[],"cancelTaskIds":[]}}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):  # silence
        pass


class TestRunnerDrainAndHeartbeat:
    """runner incremental drain + heartbeat trailing key + failure requeue."""

    def _reset_runner(self, runner, sinks: dict):
        with runner._ACTIVE_LOCK:
            runner._PERF_PARTIAL_SINKS.clear()
            runner._PERF_PARTIAL_SINKS.update(sinks)
            runner._PERF_PARTIAL_REQUEUE.clear()

    def test_drain_advances_offset_no_resend(self, tmp_path):
        import runner
        sink = tmp_path / "tp-perf-partial-80.jsonl"
        row = {"runId": 1, "seq": 1, "frames": [{"secondOffset": 0, "rps": 5.0}]}
        sink.write_text(json.dumps(row) + "\n", encoding="utf-8")
        self._reset_runner(runner, {80: (str(sink), 0)})

        got = runner.drain_perf_partial_rows()
        assert got == [row]
        # offset advanced: a second drain ships nothing until the worker appends
        assert runner.drain_perf_partial_rows() == []
        with sink.open("a", encoding="utf-8") as f:
            f.write(json.dumps({"runId": 1, "seq": 2,
                                "frames": [{"secondOffset": 1, "rps": 6.0}]}) + "\n")
        got2 = runner.drain_perf_partial_rows()
        assert len(got2) == 1 and got2[0]["seq"] == 2
        self._reset_runner(runner, {})

    def test_broken_line_skipped_honestly(self, tmp_path):
        import runner
        sink = tmp_path / "tp-perf-partial-81.jsonl"
        sink.write_text("{not-json\n" + json.dumps(
            {"runId": 2, "seq": 1, "frames": []}) + "\n", encoding="utf-8")
        self._reset_runner(runner, {81: (str(sink), 0)})
        got = runner.drain_perf_partial_rows()
        assert len(got) == 1 and got[0]["runId"] == 2
        self._reset_runner(runner, {})

    def test_heartbeat_tails_perfpartial_key_and_absent_when_empty(self):
        from protocol import ExecutorProtocolClient
        _RecorderHandler.seen = []
        server = ThreadingHTTPServer(("127.0.0.1", 0), _RecorderHandler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            client = ExecutorProtocolClient(
                f"http://127.0.0.1:{server.server_address[1]}")
            client.executor_id = 4242
            rows = [{"runId": 3, "seq": 1,
                     "frames": [{"secondOffset": 0, "rps": 7.0}]}]
            client.heartbeat(1, perf_partial=rows)
            assert _RecorderHandler.seen[-1]["perfPartial"] == rows
            client.heartbeat(1, perf_partial=None)
            assert "perfPartial" not in _RecorderHandler.seen[-1]
            client.heartbeat(1, perf_partial=[])
            assert "perfPartial" not in _RecorderHandler.seen[-1]
        finally:
            server.shutdown()
            server.server_close()

    def test_send_heartbeat_requeues_rows_on_channel_failure(self, tmp_path):
        import runner
        sink = tmp_path / "tp-perf-partial-82.jsonl"
        row = {"runId": 4, "seq": 1, "frames": [{"secondOffset": 0, "rps": 8.0}]}
        sink.write_text(json.dumps(row) + "\n", encoding="utf-8")
        self._reset_runner(runner, {82: (str(sink), 0)})

        class _Boom:
            executor_id = 4343

            def heartbeat(self, *a, **kw):
                raise RuntimeError("channel down")

        try:
            import pytest
            with pytest.raises(RuntimeError):
                runner.send_heartbeat(_Boom(), 1)
            # drained rows requeued, not silently dropped (evaporation forbidden)
            assert list(runner._PERF_PARTIAL_REQUEUE) == [row]
            again = runner.drain_perf_partial_rows()
            assert again == [row]
        finally:
            self._reset_runner(runner, {})
