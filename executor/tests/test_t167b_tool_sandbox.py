
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time
from unittest import mock

from config import ExecutorConfig
from runner import execute_task


_EXECUTOR_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_TOOL_WORKER = os.path.join(_EXECUTOR_DIR, "tool_worker.py")
_SYS_TIMEOUT = 60


def _envelope(script: str, *, entry: str = "main.py", input_json: str = "",
               budget_ms: int = 5000, kind: str = "tool-test-run") -> str:
    env = {"kind": kind, "toolId": 42, "scriptEntry": entry,
           "script": script, "input": input_json, "timeoutMs": budget_ms}
    return json.dumps(env, ensure_ascii=False)


def _task(action_json: str, task_type: int | None = 2) -> dict:
    t = {"taskId": 900, "claimToken": "tok-t167", "caseId": 0, "actionJson": action_json}
    if task_type is not None:
        t["taskType"] = task_type
    return t


class _CapturingClient:
    def __init__(self) -> None:
        self.calls: list[dict] = []
        self.executor_id = 7
        self.api_base_url = "http://127.0.0.1:0"

    def submit_result(self, task_id, claim_token, result, **kwargs) -> dict:
        self.calls.append({"taskId": task_id, "claimToken": claim_token,
                           "result": result, **kwargs})
        return {"code": "0"}


def _run_worker(action_json: str) -> dict:
    pass
    with tempfile.TemporaryDirectory(prefix="tp-t167b-") as td:
        task_f = os.path.join(td, "task.json")
        out_f = os.path.join(td, "outcome.json")
        with open(task_f, "w", encoding="utf-8") as f:
            json.dump(_task(action_json), f, ensure_ascii=False)
        proc = subprocess.run([sys.executable, _TOOL_WORKER, task_f, out_f],
                              capture_output=True, text=True, timeout=_SYS_TIMEOUT,
                              check=False)
        assert proc.returncode == 0, f"worker 非零退出: rc={proc.returncode} stderr={proc.stderr[:500]}"
        assert os.path.exists(out_f), f"结果文件未写出: stderr={proc.stderr[:500]}"
        with open(out_f, "r", encoding="utf-8") as f:
            return json.load(f)


class TestToolWorkerSandbox:
    pass

    def test_成功链路_stdout进outcome且读stdin入参(self) -> None:
        script = ("import json, sys\n"
                  "d = json.load(sys.stdin)\n"
                  "print('got', d['a'])\n")
        outcome = _run_worker(_envelope(script, input_json='{"a": 7}'))
        assert outcome["result"] == "pass", f"stdout 正常退出应为 pass: {outcome}"
        joined = "\n".join(outcome["console_logs"])
        assert "got 7" in joined, f"stdout 未回传样例入参回显: {joined!r}"

    def test_非零退出_fail且stderr进error_detail(self) -> None:
        script = ("import sys\n"
                  "sys.stderr.write('boom-脚本内部错误')\n"
                  "sys.exit(2)\n")
        outcome = _run_worker(_envelope(script))
        assert outcome["result"] == "fail"
        assert "boom-脚本内部错误" in outcome["error_detail"]
        assert "2" in outcome["error_detail"], "退出码须进人话 detail"

    def test_超预算挂死_kill限时终止(self) -> None:
        script = "import time; time.sleep(30)\n"
        started = time.monotonic()
        outcome = _run_worker(_envelope(script, budget_ms=1000))
        elapsed = time.monotonic() - started
        assert outcome["result"] == "fail"
        assert "超时" in outcome["error_detail"] or "未退出" in outcome["error_detail"]
        assert outcome["error_type"] == 3, f"环境问题分类（kill）: {outcome}"
        assert elapsed < 10, f"预算 1s 应秒级 kill，实际 {elapsed:.1f}s（守卫失效？）"

    def test_非零退出_stderr截4KB(self) -> None:
        script = ("import sys\n"
                  "sys.stderr.write('x' * 9000)\n"
                  "sys.exit(1)\n")
        outcome = _run_worker(_envelope(script))
        assert outcome["result"] == "fail"
        assert len(outcome["error_detail"]) <= 4096 + 200, \
            f"stderr 未截 4KB: len={len(outcome['error_detail'])}"

    def test_信封kind非法_fail人话不落裸异常(self) -> None:
        outcome = _run_worker(_envelope("print(1)", kind="other-thing"))
        assert outcome["result"] == "fail"
        assert "信封" in outcome["error_detail"] or "kind" in outcome["error_detail"]

    def test_scriptEntry带路径分隔符_拒绝(self) -> None:
        outcome = _run_worker(_envelope("print(1)", entry="../evil.py"))
        assert outcome["result"] == "fail"
        assert "scriptEntry" in outcome["error_detail"]

    def test_脚本正文空_fail人话(self) -> None:
        outcome = _run_worker(_envelope(""))
        assert outcome["result"] == "fail"
        assert "脚本" in outcome["error_detail"]


class TestRunnerDispatch:
    pass

    def _capture_spawn(self, task: dict):
        cfg = ExecutorConfig()
        captured: list[list] = []

        def _fake_popen(args, **kwargs):  # noqa: ARG001
            captured.append(list(args))
            fake = mock.Mock()
            fake.communicate.return_value = ("", "no-outfile")
            fake.returncode = 1
            return fake

        client = _CapturingClient()
        with mock.patch("runner.subprocess.Popen", side_effect=_fake_popen), \
                mock.patch("runner.subprocess.run"):
            execute_task(client, task, cfg)
        return captured, client

    def test_taskType2_走tool_worker子进程(self) -> None:
        captured, client = self._capture_spawn(_task(_envelope("print(1)"), task_type=2))
        assert captured, "subprocess.run 未被调用"
        assert os.path.basename(captured[0][1]) == "tool_worker.py", \
            f"taskType=2 必须分流到 tool_worker：{captured[0]}"
        assert client.calls, "分流支仍须回传结果（禁让 core 空烧预算）"
        assert client.calls[0]["result"] == "fail"

    def test_缺省taskType_走存量webdriver支零漂移(self) -> None:
        captured, _ = self._capture_spawn(_task('{"steps": []}', task_type=None))
        assert captured
        assert os.path.basename(captured[0][1]) == "task_worker.py", \
            f"缺省类型须走存量浏览器支：{captured[0]}"
