
from __future__ import annotations

import signal
import subprocess
from unittest import mock

from config import ExecutorConfig
import runner as runner_mod
from runner import apply_cancel_directives, cancel_task_process, execute_task


class _RecorderClient:
    pass

    def __init__(self) -> None:
        self.results: list[dict] = []
        self.executor_id = 7
        self.api_base_url = "http://127.0.0.1:0"

    def submit_result(self, task_id, claim_token, result, error_type=None,
                      error_detail=None, steps_timeline=None, screenshots=None,
                      duration_ms=None, log=None, console_violations=None,
                      net_hits=None, db_digest=None, protocol_snapshots=None,
                      extracted_vars=None, gen_seeds=None, perf_frames=None,
                      perf_events=None, perf_status=None,
                      record_events=None, mock_notes=None) -> dict:
        self.results.append({"taskId": task_id, "result": result,
                             "errorDetail": error_detail})
        return {"accepted": True}

    def grant_artifact(self, task_id, kind, file_name) -> dict:
        return {"uploadUrl": ""}


def _task(task_id: int = 701) -> dict:
    return {"taskId": task_id, "claimToken": "tok-x", "actionJson": "{}", "caseId": 3}


class _FakePopen:
    pass

    def __init__(self, cancel_during_run: bool = False) -> None:
        self.returncode = 0
        self.terminated = 0
        self.killed = 0
        self.stdout = ""
        self.stderr = ""
        self._cancel_during_run = cancel_during_run

    def communicate(self, timeout=None):
        return ("", "")

    def terminate(self) -> None:
        self.terminated += 1
        self.returncode = -15

    def kill(self) -> None:
        self.killed += 1


class TestActiveProcRegistry:
    pass

    def test_执行期登记_收尾注销(self) -> None:
        fake = _FakePopen()
        seen: dict = {}

        def _capture_popen(*args, **kwargs):
            seen.update(runner_mod._ACTIVE_PROCS)
            return fake

        with mock.patch("runner.subprocess.Popen", side_effect=_capture_popen), \
                mock.patch("runner.subprocess.run"):
            execute_task(_RecorderClient(), _task(701), ExecutorConfig())

        assert 701 in seen, "执行期 taskId 必须登记进 _ACTIVE_PROCS（kill 指令寻址源）"
        assert 701 not in runner_mod._ACTIVE_PROCS, "收尾必须注销登记表（防陈旧 proc 误 kill）"

    def test_Popen携带超时兜底_communicate带timeout(self) -> None:
        fake = _FakePopen()
        captured: dict = {}

        with mock.patch("runner.subprocess.Popen", return_value=fake), \
                mock.patch.object(fake, "communicate",
                                  side_effect=lambda timeout=None: captured.update(
                                      {"timeout": timeout}) or ("", "")), \
                mock.patch("runner.subprocess.run"):
            execute_task(_RecorderClient(), _task(702), ExecutorConfig())

        assert captured.get("timeout") is not None, \
            "communicate 必须带 timeout=下发预算+缓冲（改 Popen 后卡死防护不降级，T136 语义保留）"


class TestCancelTaskProcess:
    pass

    def test_在位任务_终止并回传cancelled带退出码(self) -> None:
        fake = _FakePopen()
        fake.returncode = -15
        runner_mod._ACTIVE_PROCS[703] = fake
        client = _RecorderClient()

        try:
            ok = cancel_task_process(client, 703, claim_token="tok-x")
        finally:
            runner_mod._ACTIVE_PROCS.pop(703, None)

        assert ok is True, "在位任务终止指令必须命中"
        assert fake.terminated >= 1, "必须 terminate 子进程本体"
        assert client.results, "kill 后必须回传结果（否则 core 侧任务悬 7 无退出码取证）"
        sent = client.results[0]
        assert sent["taskId"] == 703
        assert sent["result"] == "cancelled", "回传枚举=cancelled（03 v2.41，core 映射 7）"
        assert "-15" in (sent["errorDetail"] or ""), "退出码必须落 error_detail（TC-V11-01 取证面）"

    def test_不在位任务_返回False零副作用(self) -> None:
        client = _RecorderClient()
        ok = cancel_task_process(client, 999, claim_token="tok-x")
        assert ok is False, "未登记任务（已结束/非本执行器）=幂等 False"
        assert client.results == [], "不在位任务禁止回传（防污染 core 终态）"


class TestHeartbeatDirectiveLeg:
    pass

    def test_指令列表_对在位任务逐个终止(self) -> None:
        fake = _FakePopen()
        runner_mod._ACTIVE_PROCS[704] = fake
        client = _RecorderClient()
        resp = {"ok": True, "cancelTaskIds": [704, 705]}

        try:
            apply_cancel_directives(client, resp, claim_tokens={704: "tok-704"})
        finally:
            runner_mod._ACTIVE_PROCS.pop(704, None)

        assert fake.terminated >= 1, "cancelTaskIds 命中在位任务必须 kill"
        assert [r["taskId"] for r in client.results] == [704], "不在位任务静默跳过零异常"

    def test_响应无槽或空列表_零动作(self) -> None:
        client = _RecorderClient()
        apply_cancel_directives(client, {"ok": True}, claim_tokens={})
        apply_cancel_directives(client, {"cancelTaskIds": []}, claim_tokens={})
        assert client.results == []


class TestScopedTreeKill:
    pass

    def test_cancel链路_定向killpg_禁全局pkill(self) -> None:
        fake = _FakePopen()
        fake.pid = 4321
        fake.returncode = -15
        runner_mod._ACTIVE_PROCS[706] = fake
        client = _RecorderClient()
        try:
            with mock.patch("runner.os.killpg") as killpg, \
                    mock.patch("runner.subprocess.run") as srun:
                ok = cancel_task_process(client, 706, claim_token="tok-y")
        finally:
            runner_mod._ACTIVE_PROCS.pop(706, None)
        assert ok is True
        assert srun.call_count == 0, \
            "取消链路禁止 subprocess.run 全局 pkill（误杀并行任务/浏览器 driver）"
        killpg.assert_called_once_with(4321, signal.SIGKILL)

    def test_执行支worker独立会话(self) -> None:
        pass
        fake = _FakePopen()
        fake.pid = 4322
        with mock.patch("runner.subprocess.Popen", return_value=fake) as pm, \
                mock.patch("runner.subprocess.run"), \
                mock.patch("runner.os.killpg"):
            execute_task(_RecorderClient(), _task(707), ExecutorConfig())
        assert pm.call_args.kwargs.get("start_new_session") is True, \
            "task_worker 必须以独立会话启动（killpg 只杀本任务组）"
