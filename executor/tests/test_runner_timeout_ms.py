
from __future__ import annotations

import subprocess
from unittest import mock

from config import ExecutorConfig
from runner import execute_task



class _NoopClient:
    pass

    executor_id = 7
    api_base_url = "http://127.0.0.1:0"

    def submit_result(self, *args, **kwargs) -> dict:
        return {"code": "0"}


def _task(timeout_ms: int | None = None) -> dict:
    task = {"taskId": 1, "claimToken": "tok-1", "actionJson": "{}", "caseId": 100}
    if timeout_ms is not None:
        task["timeoutMs"] = timeout_ms
    return task


def _capture_timeout(task: dict, local_ms: int) -> float:
    pass
    cfg = ExecutorConfig()
    cfg.task_timeout_ms = local_ms
    captured: list[float] = []

    def _fake_communicate(timeout=None):
        captured.append(timeout)
        return ("", "stop-here")

    fake = mock.Mock()
    fake.communicate.side_effect = _fake_communicate
    fake.returncode = 1
    fake.terminate.side_effect = lambda: None
    fake.__iter__ = lambda *_a: iter(("", "stop-here"))

    with mock.patch("runner.subprocess.Popen", return_value=fake), \
            mock.patch("runner.subprocess.run"):
        execute_task(_NoopClient(), task, cfg)
    assert captured, "communicate 未被调用（超时参数无从断言）"
    return captured[0]


class TestClaimTimeoutMsConsumed:
    pass

    def test_服务端下发timeoutMs_覆盖本地超时(self) -> None:
        got = _capture_timeout(_task(timeout_ms=300000), local_ms=600000)
        assert got == 300.0 + 30, f"应按下发值 300s+30 缓冲，实际 {got}s"

    def test_缺timeoutMs_回退本地配置(self) -> None:
        got = _capture_timeout(_task(timeout_ms=None), local_ms=120000)
        assert got == 120.0 + 30, f"缺字段应回退本地 120s+30，实际 {got}s"
