
from __future__ import annotations

import json
import os
import signal
import stat
import subprocess
import sys
import time

import pytest

PERF_WORKER = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           "perf_worker.py")

_HOT_LINES = [
    '{"type":"Point","metric":"http_reqs","data":{"time":"2026-10-01T00:00:01.000Z","value":10,"tags":{}}}',
    '{"type":"Point","metric":"http_req_failed","data":{"time":"2026-10-01T00:00:01.100Z","value":9,"tags":{}}}',
    '{"type":"Point","metric":"http_req_duration","data":{"time":"2026-10-01T00:00:01.200Z","value":100,"tags":{}}}',
    '{"type":"Point","metric":"http_reqs","data":{"time":"2026-10-01T00:00:02.000Z","value":1,"tags":{}}}',
]

_COOL_LINES = [
    '{"type":"Point","metric":"http_reqs","data":{"time":"2026-10-01T00:00:01.000Z","value":10,"tags":{}}}',
    '{"type":"Point","metric":"http_req_failed","data":{"time":"2026-10-01T00:00:01.100Z","value":1,"tags":{}}}',
    '{"type":"Point","metric":"http_req_duration","data":{"time":"2026-10-01T00:00:01.200Z","value":100,"tags":{}}}',
    '{"type":"Point","metric":"http_reqs","data":{"time":"2026-10-01T00:00:02.000Z","value":1,"tags":{}}}',
]


def _make_shim(tmp_path, lines: list[str], mode: str) -> str:
    pass
    shim = tmp_path / "k6"
    literal = json.dumps(lines)
    shim.write_text(
        f"#!{sys.executable}\n"
        "import sys, json, time\n"
        "args = sys.argv\n"
        "out = None\n"
        "for i, a in enumerate(args):\n"
        "    if a == '--out' and i + 1 < len(args): out = args[i + 1]\n"
        "if out and out.startswith('json='):\n"
        f"    lines = json.loads({literal!r})\n"
        "    open(out[5:], 'w').write('\\n'.join(lines) + '\\n')\n"
        + ("    time.sleep(120)\n" if mode == "hold" else "sys.exit(0)\n"),
        encoding="utf-8")
    shim.chmod(shim.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)
    return str(tmp_path)


def _write_task(tmp_path, shim_dir: str, snapshot, mode: str) -> tuple[str, str, dict]:
    art = tmp_path / "PerfGenK6_f2.js"
    art.write_text("// compiled", encoding="utf-8")
    lines = _HOT_LINES if mode.startswith("hot") else _COOL_LINES
    path = _make_shim(tmp_path, lines, "hold" if mode == "hot-hold" else "exit0")
    task = {"taskId": 90, "timeoutMs": 60000, "perf": {
        "runId": 9, "engine": "K6", "artifact": str(art),
        "pressureSnapshot": snapshot}}
    task_f = tmp_path / "task.json"
    out_f = tmp_path / "outcome.json"
    task_f.write_text(json.dumps(task), encoding="utf-8")
    return str(task_f), str(out_f), {"PATH": path + os.pathsep + os.environ.get("PATH", "")}


def _spawn_worker(task_f: str, out_f: str, env: dict, budget_wait: float) -> dict | None:
    pass
    proc = subprocess.Popen([sys.executable, PERF_WORKER, task_f, out_f],
                            env=env, start_new_session=True,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        proc.wait(timeout=budget_wait)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
        except Exception:
            proc.kill()
        proc.wait(timeout=10)
        return None
    if not os.path.exists(out_f):
        return None
    with open(out_f, "r", encoding="utf-8") as f:
        return json.load(f)


class TestBreakerTrip:
    pass

    def test_阈值超穿_运行中熔断_停因入结果(self, tmp_path):
        snap = json.dumps({"sla": {"breakerErrorRate": 0.05}})
        task_f, out_f, env = _write_task(tmp_path, "", snap, "hot-hold")
        o = _spawn_worker(task_f, out_f, env, budget_wait=20.0)
        assert o is not None, "熔断应在预算内秒级命中并回传结果（旧实现阻塞式=无回传）"
        assert o["result"] == "fail"
        assert o["perf_status"] == 3, "熔断终态必须明示 perf_status=3（core 明示优先单源）"
        kinds = [e["eventKind"] for e in (o.get("perf_events") or [])]
        assert "engine-abort" in kinds, f"熔断必须带 engine-abort 事件，实得 {kinds}"
        detail = o.get("error_detail") or ""
        assert "已熔断" in detail and "超阈值" in detail and "自动停止" in detail, detail
        assert "90.0%" in detail and "5.0%" in detail, detail
        frames = o.get("perf_frames") or []
        assert frames, "熔断前已采集秒帧必须随结果回传（禁丢采样）"
        assert frames[0]["errorRate"] == pytest.approx(0.9)

    def test_熔断后引擎进程已被定向终止(self, tmp_path):
        snap = json.dumps({"sla": {"breakerErrorRate": 0.05}})
        task_f, out_f, env = _write_task(tmp_path, "", snap, "hot-hold")
        t0 = time.monotonic()
        o = _spawn_worker(task_f, out_f, env, budget_wait=20.0)
        assert o is not None and o["perf_status"] == 3
        assert time.monotonic() - t0 < 15, "熔断命中即停，不得拖到预算耗尽"

    def test_快照为dict形态直给_同样熔断(self, tmp_path):
        task_f, out_f, env = _write_task(tmp_path, "",
                                         {"sla": {"breakerErrorRate": 0.05}}, "hot-hold")
        o = _spawn_worker(task_f, out_f, env, budget_wait=20.0)
        assert o is not None and o["perf_status"] == 3

    def test_熔断不误伤worker自身_结果必落盘(self, tmp_path):
        snap = json.dumps({"sla": {"breakerErrorRate": 0.05}})
        task_f, out_f, env = _write_task(tmp_path, "", snap, "hot-hold")
        o = _spawn_worker(task_f, out_f, env, budget_wait=20.0)
        assert o is not None, "熔断回传缺失=worker 自组连坐自杀（定向杀引擎纪律被破坏）"


class TestBreakerIdentity:
    pass

    def _run_hot_exit0(self, tmp_path, snapshot):
        task_f, out_f, env = _write_task(tmp_path, "", snapshot, "hot")
        o = _spawn_worker(task_f, out_f, env, budget_wait=30.0)
        assert o is not None
        return o

    def test_阈值零_不熔断恒等(self, tmp_path):
        o = self._run_hot_exit0(tmp_path, json.dumps({"sla": {"breakerErrorRate": 0}}))
        assert o["result"] == "pass", o.get("error_detail")
        assert o["perf_status"] == 2
        assert not any(e["eventKind"] == "engine-abort" for e in (o.get("perf_events") or []))

    def test_缺sla键_不熔断恒等(self, tmp_path):
        o = self._run_hot_exit0(tmp_path, json.dumps({"name": "x"}))
        assert o["perf_status"] == 2

    def test_脏快照非JSON_不熔断恒等(self, tmp_path):
        o = self._run_hot_exit0(tmp_path, "{not-json")
        assert o["perf_status"] == 2

    def test_错误率等于阈值_不熔断_严格大于判定(self, tmp_path):
        o = self._run_hot_exit0(tmp_path, json.dumps({"sla": {"breakerErrorRate": 0.9}}))
        assert o["perf_status"] == 2

    def test_sla值脏类型_不熔断恒等(self, tmp_path):
        o = self._run_hot_exit0(tmp_path,
                                json.dumps({"sla": {"breakerErrorRate": "abc"}}))
        assert o["perf_status"] == 2

    def test_低错误率低于阈值_正常跑完(self, tmp_path):
        task_f, out_f, env = _write_task(tmp_path, "",
                                         json.dumps({"sla": {"breakerErrorRate": 0.5}}), "cool")
        o = _spawn_worker(task_f, out_f, env, budget_wait=30.0)
        assert o is not None and o["result"] == "pass" and o["perf_status"] == 2


class TestBreakerParsing:
    pass

    def test_解析_嵌套对象取键(self):
        from perf_worker import _breaker_rate_from_snapshot
        assert _breaker_rate_from_snapshot(
            json.dumps({"sla": {"breakerErrorRate": 0.05}})) == 0.05

    def test_解析_dict形态直取(self):
        from perf_worker import _breaker_rate_from_snapshot
        assert _breaker_rate_from_snapshot({"sla": {"breakerErrorRate": 0.2}}) == 0.2

    def test_解析_零与负值拒绝(self):
        from perf_worker import _breaker_rate_from_snapshot
        assert _breaker_rate_from_snapshot(
            json.dumps({"sla": {"breakerErrorRate": 0}})) is None
        assert _breaker_rate_from_snapshot(
            json.dumps({"sla": {"breakerErrorRate": -0.1}})) is None

    def test_解析_非有限数拒绝(self):
        from perf_worker import _breaker_rate_from_snapshot
        assert _breaker_rate_from_snapshot(
            json.dumps({"sla": {"breakerErrorRate": float("nan")}})) is None
        assert _breaker_rate_from_snapshot(
            json.dumps({"sla": {"breakerErrorRate": float("inf")}})) is None

    def test_解析_脏输入一律None不抛(self):
        from perf_worker import _breaker_rate_from_snapshot
        assert _breaker_rate_from_snapshot("{not-json") is None
        assert _breaker_rate_from_snapshot(None) is None
        assert _breaker_rate_from_snapshot("[]") is None
        assert _breaker_rate_from_snapshot({"sla": "also-not-json"}) is None
