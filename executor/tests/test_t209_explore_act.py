
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from unittest import mock

import pytest

from config import ExecutorConfig
from runner import execute_task


_EXECUTOR_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_WORKER = os.path.join(_EXECUTOR_DIR, "explore_worker.py")
_SYS_TIMEOUT = 120

_FIXTURES = os.environ.get("EXECUTOR_FIXTURES_BASE", "http://localhost:8899")
LOGIN_URL = _FIXTURES + "/login"


@pytest.fixture(scope="module", autouse=True)
def _require_fixture_server() -> None:
    pass
    import requests
    try:
        resp = requests.get(LOGIN_URL, timeout=5)
    except Exception as e:  # noqa: BLE001
        pytest.skip(f"环境前置缺失：夹具服务 {LOGIN_URL} 不可达（{e}）——"
                    f"起 executor/serve_fixtures.py 后复跑")
    assert resp.status_code == 200, f"夹具服务响应异常: {resp.status_code}"


def _envelope(action: str, *, url: str | None = LOGIN_URL,
              arguments: dict | None = None, kind: str = "explore-act") -> str:
    env = {"kind": kind, "action": action, "url": url, "arguments": arguments or {}}
    return json.dumps(env, ensure_ascii=False)


def _task(action_json: str, task_type: int | None = 3) -> dict:
    t = {"taskId": 903, "claimToken": "tok-t209", "caseId": 0, "actionJson": action_json}
    if task_type is not None:
        t["taskType"] = task_type
    return t


def _run_worker(action_json: str) -> dict:
    pass
    with tempfile.TemporaryDirectory(prefix="tp-t209-") as td:
        task_f = os.path.join(td, "task.json")
        out_f = os.path.join(td, "outcome.json")
        with open(task_f, "w", encoding="utf-8") as f:
            json.dump(_task(action_json), f, ensure_ascii=False)
        proc = subprocess.run([sys.executable, _WORKER, task_f, out_f],
                              capture_output=True, text=True, timeout=_SYS_TIMEOUT,
                              check=False, cwd=_EXECUTOR_DIR)
        assert proc.returncode == 0, \
            f"worker 非零退出: rc={proc.returncode} stderr={proc.stderr[:500]}"
        assert os.path.exists(out_f), f"结果文件未写出: stderr={proc.stderr[:500]}"
        with open(out_f, "r", encoding="utf-8") as f:
            return json.load(f)


class TestExploreWorkerActions:
    pass

    def test_page_snapshot_观测文本进时间线(self) -> None:
        outcome = _run_worker(_envelope("page-snapshot"))
        assert outcome["result"] == "pass", f"快照应 pass: {outcome}"
        timeline = outcome["steps_timeline"]
        assert timeline, "steps_timeline 必须非空（内核观测唯一载体）"
        details = " ".join(str(s.get("detail") or "") for s in timeline)
        assert "试运行演示门户" in details, \
            f"snapshot 观测须含夹具页正文标题，实际={details[:200]!r}"

    def test_input_path_真填值后_assert_观测到变化(self) -> None:
        outcome = _run_worker(_envelope(
            "input-path", arguments={"target": {"css": "#username"}, "value": "t209"}))
        assert outcome["result"] == "pass", f"输入应 pass: {outcome}"

    def test_assert_命中pass_缺失fail(self) -> None:
        hit = _run_worker(_envelope("assert", arguments={"text": "试运行演示门户"}))
        assert hit["result"] == "pass", f"断言命中应 pass: {hit}"
        miss = _run_worker(_envelope("assert", arguments={"text": "不可能出现的文案xyz"}))
        assert miss["result"] == "fail", f"断言缺失应 fail: {miss}"

    def test_截图留证_本地png路径回传(self) -> None:
        outcome = _run_worker(_envelope("page-snapshot"))
        shots = [p for p in outcome.get("screenshots") or [] if p]
        assert shots, f"探索步须留截图（objectKey 链路随 runner 上传）: {outcome}"
        assert all(os.path.exists(p) for p in shots), f"截图文件须真实存在: {shots}"


class TestExploreWorkerEnvelopeGuard:
    pass

    def test_kind非法_fail人话(self) -> None:
        outcome = _run_worker(_envelope("page-snapshot", kind="other-thing"))
        assert outcome["result"] == "fail"
        assert "信封" in outcome["error_detail"] or "kind" in outcome["error_detail"]

    def test_action白名单外_fail点名(self) -> None:
        outcome = _run_worker(_envelope("delete-everything"))
        assert outcome["result"] == "fail"
        assert "delete-everything" in outcome["error_detail"], \
            f"须点名非法动作: {outcome['error_detail']!r}"

    def test_url缺失_fail人话_执行器无会话语境(self) -> None:
        outcome = _run_worker(_envelope("click-path", url=None,
                                        arguments={"target": {"css": "#login-btn"}}))
        assert outcome["result"] == "fail"
        assert "url" in outcome["error_detail"].lower(), \
            f"执行器每任务全新浏览器=动作须自带 url，实际={outcome['error_detail']!r}"

    def test_非绝对址_fail人话(self) -> None:
        outcome = _run_worker(_envelope("page-snapshot", url="/relative/only"))
        assert outcome["result"] == "fail"


class _CapturingClient:
    def __init__(self) -> None:
        self.calls: list[dict] = []
        self.executor_id = 7
        self.api_base_url = "http://127.0.0.1:0"

    def submit_result(self, task_id, claim_token, result, **kwargs) -> dict:
        self.calls.append({"taskId": task_id, "claimToken": claim_token,
                           "result": result, **kwargs})
        return {"code": "0"}

    def grant_artifact(self, task_id, kind, file_name) -> dict:
        raise AssertionError("分流断言链路不应触网（假 run 无截图产物）")


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

    def test_taskType3_走explore_worker子进程(self) -> None:
        captured, client = self._capture_spawn(_task(_envelope("page-snapshot"), task_type=3))
        assert captured, "subprocess.run 未被调用"
        assert os.path.basename(captured[0][1]) == "explore_worker.py", \
            f"taskType=3 必须分流到 explore_worker：{captured[0]}"
        assert client.calls, "分流支仍须回传结果（禁让内核空烧预算）"
        assert client.calls[0]["result"] == "fail"

    def test_taskType2_存量沙箱支零漂移(self) -> None:
        captured, _ = self._capture_spawn(_task('{"kind":"tool-test-run"}', task_type=2))
        assert captured
        assert os.path.basename(captured[0][1]) == "tool_worker.py", \
            f"taskType=2 分流零漂移：{captured[0]}"
