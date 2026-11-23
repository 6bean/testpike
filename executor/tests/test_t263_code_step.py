
from __future__ import annotations

import json
import os
import tempfile
import time

import pytest

from driver.code_step_contract import validate_code_step
from driver.webdriver import run_action_json

DEMO_URL = "file://" + os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "fixtures", "demo_page.html"))


def _doc(*steps: dict) -> str:
    return json.dumps({"version": "v2", "steps": [
        {"action": "goto", "target": {"url": DEMO_URL}}, *steps]},
        ensure_ascii=False)


def _code(**kw) -> dict:
    step = {"action": "code.step", "language": "python"}
    step.update(kw)
    return step


def _step_row(outcome, index: int = 1) -> dict:
    pass
    return outcome.steps_timeline[index]


def _run(doc: str):
    with tempfile.TemporaryDirectory() as shots:
        return run_action_json(doc, headless=True, screenshots_dir=shots)


class TestContractMirror:
    pass

    def test_合法片段_不抛(self):
        validate_code_step({"language": "python", "code": "result = 1 + 1"})

    def test_禁网词点名(self):
        with pytest.raises(Exception) as cm:
            validate_code_step({"language": "python", "code": "import socket"})
        assert "网络访问" in str(cm.value) and "socket" in str(cm.value)
        with pytest.raises(Exception) as cm:
            validate_code_step({"language": "python",
                                "code": "requests.get('http://x')"})
        assert "requests" in str(cm.value)

    def test_禁子进程词点名(self):
        with pytest.raises(Exception) as cm:
            validate_code_step({"language": "python", "code": "import subprocess"})
        assert "子进程" in str(cm.value)
        with pytest.raises(Exception) as cm:
            validate_code_step({"language": "python", "code": "os.system('ls')"})
        assert "os.system" in str(cm.value)

    def test_禁文件访问词点名(self):
        with pytest.raises(Exception) as cm:
            validate_code_step({"language": "python", "code": "open('/etc/passwd')"})
        assert "文件访问" in str(cm.value)
        with pytest.raises(Exception) as cm:
            validate_code_step({"language": "python", "code": "import pathlib"})
        assert "pathlib" in str(cm.value)

    def test_逃逸面词点名(self):
        for frag, word in (("eval('1')", "eval"), ("__import__('os')", "__import__")):
            with pytest.raises(Exception) as cm:
                validate_code_step({"language": "python", "code": frag})
            assert "沙箱逃逸" in str(cm.value)
            assert word in str(cm.value)

    def test_js语言_沙箱未开放人话拒(self):
        with pytest.raises(Exception) as cm:
            validate_code_step({"language": "js", "code": "var a=1"})
        assert "暂未开放" in str(cm.value)

    def test_language缺失或未知_人话拒(self):
        with pytest.raises(Exception) as cm:
            validate_code_step({"code": "x=1"})
        assert "language" in str(cm.value)
        with pytest.raises(Exception) as cm:
            validate_code_step({"language": "ruby", "code": "puts 1"})
        assert "不在支持列表" in str(cm.value)

    def test_code为空_人话拒(self):
        with pytest.raises(Exception) as cm:
            validate_code_step({"language": "python", "code": "  "})
        assert "code" in str(cm.value)

    def test_timeoutMs越域_人话拒(self):
        for bad in (0, 30001):
            with pytest.raises(Exception) as cm:
                validate_code_step({"language": "python", "code": "x=1",
                                    "timeoutMs": bad})
            assert "timeoutMs" in str(cm.value)

    def test_timeoutMs域内合法(self):
        validate_code_step({"language": "python", "code": "x=1", "timeoutMs": 5000})


class TestExecutorRealRun:
    pass

    def test_成功链路_stdout单层JSON回注VarStore可消费(self):
        doc = _doc(
            _code(code="print('{\"total\": 42}')", saveAs="cs"),
            {"action": "input", "target": {"css": "#username"},
             "value": "{{VAR:cs.total}}"})
        outcome = _run(doc)
        assert outcome.result == "pass", f"应通过: {outcome.error_detail}"
        row = _step_row(outcome)
        assert row["status"] == "ok"
        assert "saveAs" in (row["detail"] or "")
        assert _step_row(outcome, 2)["status"] == "ok"

    def test_stdout非JSON_带saveAs时步骤fail人话(self):
        doc = _doc(_code(code="print('hello not json')", saveAs="cs"))
        outcome = _run(doc)
        assert outcome.result == "fail"
        assert "JSON" in _step_row(outcome)["detail"]

    def test_无saveAs_stdout进步骤detail留痕(self):
        doc = _doc(_code(code="print('tick-done')"))
        outcome = _run(doc)
        assert outcome.result == "pass", f"{outcome.error_detail}"
        assert "tick-done" in (_step_row(outcome)["detail"] or "")

    def test_脚本非零退出_fail含stderr人话(self):
        doc = _doc(_code(
            code="import sys\nsys.stderr.write('boom-internal')\nsys.exit(2)"))
        outcome = _run(doc)
        assert outcome.result == "fail"
        detail = _step_row(outcome)["detail"]
        assert "boom-internal" in detail

    def test_超预算_限时kill定向终止(self):
        doc = _doc(_code(code="import time\nwhile True: time.sleep(0.1)",
                         timeoutMs=1000))
        started = time.monotonic()
        outcome = _run(doc)
        elapsed = time.monotonic() - started
        assert outcome.result == "fail"
        assert "未退出" in _step_row(outcome)["detail"]
        assert elapsed < 15, f"预算 1s 应秒级 kill，实际 {elapsed:.1f}s（守卫失效？）"

    def test_违规片段_执行前复检独立拒不落沙箱(self):
        doc = _doc(_code(code="import socket\nsocket.socket()"))
        outcome = _run(doc)
        assert outcome.result == "fail"
        detail = _step_row(outcome)["detail"]
        assert "网络访问" in detail and "socket" in detail

    def test_js片段_执行侧独立拒(self):
        doc = json.dumps({"version": "v2", "steps": [
            {"action": "goto", "target": {"url": DEMO_URL}},
            {"action": "code.step", "language": "js", "code": "alert(1)"}]},
            ensure_ascii=False)
        outcome = _run(doc)
        assert outcome.result == "fail"
        assert "暂未开放" in _step_row(outcome)["detail"]

    def test_逃逸词片段_镜像词表拒(self):
        doc = _doc(_code(code="m = __import__('os')"))
        outcome = _run(doc)
        assert "沙箱逃逸" in (_step_row(outcome)["detail"] or "")


class TestToolWorkerStillGreen:
    pass

    def test_tool_worker_成功链路仍走同一公共件(self):
        from tool_worker import run_tool_task
        env = json.dumps({"kind": "tool-test-run", "toolId": 1,
                          "scriptEntry": "main.py",
                          "script": "print('tool-ok')", "input": "",
                          "timeoutMs": 5000})
        outcome = run_tool_task(env)
        assert outcome.result == "pass"
        assert "tool-ok" in "\n".join(outcome.console_logs)

    def test_tool_worker_超时链路公共件同口径(self):
        from tool_worker import run_tool_task
        env = json.dumps({"kind": "tool-test-run", "toolId": 1,
                          "scriptEntry": "main.py",
                          "script": "import time; time.sleep(30)", "input": "",
                          "timeoutMs": 800})
        outcome = run_tool_task(env)
        assert outcome.result == "fail"
        assert "未退出" in outcome.error_detail or "超时" in outcome.error_detail
