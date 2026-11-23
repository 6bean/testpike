
from __future__ import annotations

import json
import subprocess
import tempfile
import time
from unittest import mock

import pytest

from config import ExecutorConfig
from runner import execute_task


class _FakePopen:
    pass

    def __init__(self, args, communicate_fn, timeout_capture: list | None = None) -> None:
        self.args = args
        self.returncode = 0
        self._communicate_fn = communicate_fn
        self._timeout_capture = timeout_capture

    def communicate(self, timeout=None):
        if self._timeout_capture is not None:
            self._timeout_capture.append(timeout)
        return self._communicate_fn(self)

    def terminate(self) -> None:
        self.returncode = -15

    def kill(self) -> None:
        self.returncode = -9


def _popen_factory(communicate_fn, timeout_capture: list | None = None):
    def _factory(args, **kwargs):  # noqa: ARG001
        return _FakePopen(args, communicate_fn, timeout_capture)
    return _factory



class _RecorderClient:
    pass

    def __init__(self) -> None:
        self.submitted: list[dict] = []
        self.executor_id = 7
        self.api_base_url = "http://127.0.0.1:0"

    def submit_result(self, task_id: int, claim_token: str, result: str,
                      error_type: int | None = None, error_detail: str | None = None,
                      steps_timeline: list | None = None, screenshots: list | None = None,
                      duration_ms: int = 0, log: str | None = None,
                      console_violations: list | None = None,
                      net_hits: list | None = None,
                      db_digest: list | None = None,
                      protocol_snapshots: list | None = None,
                      extracted_vars: dict | None = None,
                      gen_seeds: list | None = None,
                      perf_frames: list | None = None,
                      perf_events: list | None = None,
                      perf_status: int | None = None,
                      record_events: dict | None = None,
                      mock_notes: list | None = None) -> dict:
        self.submitted.append({
            "taskId": task_id, "claimToken": claim_token, "result": result,
            "error_type": error_type, "error_detail": error_detail,
            "duration_ms": duration_ms,
        })
        return {"code": "0"}


def _task(task_id: int = 1) -> dict:
    return {"taskId": task_id, "claimToken": f"tok-{task_id}",
            "actionJson": "{}", "caseId": 100}


class TestTaskWatchdogProcessIsolation:
    pass

    def test_子进程卡死_超时kill后回传fail(self) -> None:
        client = _RecorderClient()
        cfg = ExecutorConfig()
        cfg.task_timeout_ms = 1500

        def _timeout_comm(popen: _FakePopen):  # noqa: ARG001
            raise subprocess.TimeoutExpired(cmd=["python3"], timeout=1.5)

        with mock.patch("runner.subprocess.Popen", side_effect=_popen_factory(_timeout_comm)), \
                mock.patch("runner.subprocess.run"):
            t0 = time.monotonic()
            execute_task(client, _task(task_id=1), cfg)
            elapsed = time.monotonic() - t0

        assert elapsed < 20, f"卡死路径应在数秒内返回，实际 {elapsed:.1f}s"
        assert len(client.submitted) == 1
        sub = client.submitted[0]
        assert sub["taskId"] == 1
        assert sub["result"] == "fail"
        assert sub["error_type"] == 3
        assert "卡死" in (sub["error_detail"] or ""), f"失败原因应人话标注卡死: {sub['error_detail']}"

    def test_子进程正常_读结果文件回传pass(self) -> None:
        client = _RecorderClient()
        cfg = ExecutorConfig()
        cfg.task_timeout_ms = 600000

        def _ok_comm(popen: _FakePopen):
            out_f = popen.args[3]
            with open(out_f, "w", encoding="utf-8") as f:
                json.dump({"result": "pass", "error_type": None, "error_detail": None,
                           "steps_timeline": [], "screenshots": [],
                           "duration_ms": 42, "console_logs": ["[info] hello"]}, f)
            popen.returncode = 0
            return ("", "")

        with mock.patch("runner.subprocess.Popen", side_effect=_popen_factory(_ok_comm)), \
                mock.patch("runner.subprocess.run"):
            execute_task(client, _task(task_id=2), cfg)

        assert len(client.submitted) == 1
        sub = client.submitted[0]
        assert sub["taskId"] == 2
        assert sub["result"] == "pass"
        assert sub["duration_ms"] == 42

    def test_子进程异常退出_无结果文件_回传fail含stderr(self) -> None:
        client = _RecorderClient()
        cfg = ExecutorConfig()
        cfg.task_timeout_ms = 600000

        def _bad_exit(popen: _FakePopen):
            popen.returncode = 2
            return ("", "Traceback: boom")

        with mock.patch("runner.subprocess.Popen", side_effect=_popen_factory(_bad_exit)), \
                mock.patch("runner.subprocess.run"):
            execute_task(client, _task(task_id=3), cfg)

        assert len(client.submitted) == 1
        sub = client.submitted[0]
        assert sub["result"] == "fail"
        assert sub["error_type"] == 3
        assert "boom" in (sub["error_detail"] or ""), sub["error_detail"]

    def test_结果文件损坏_回传fail解析失败(self) -> None:
        client = _RecorderClient()
        cfg = ExecutorConfig()
        cfg.task_timeout_ms = 600000

        def _corrupt(popen: _FakePopen):
            with open(popen.args[3], "w", encoding="utf-8") as f:  # args[3]=outcome.json
                f.write("{not-json")
            popen.returncode = 0
            return ("", "")

        with mock.patch("runner.subprocess.Popen", side_effect=_popen_factory(_corrupt)), \
                mock.patch("runner.subprocess.run"):
            execute_task(client, _task(task_id=4), cfg)

        assert len(client.submitted) == 1
        assert client.submitted[0]["result"] == "fail"
        assert "解析失败" in (client.submitted[0]["error_detail"] or "")
