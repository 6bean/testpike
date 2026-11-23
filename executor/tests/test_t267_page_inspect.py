
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile

import pytest

from explore_worker import BUILTIN_ACTIONS, build_script_doc

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
              arguments: dict | None = None) -> str:
    env = {"kind": "explore-act", "action": action, "url": url,
           "arguments": arguments or {}}
    return json.dumps(env, ensure_ascii=False)


def _run_worker(action_json: str) -> dict:
    pass
    with tempfile.TemporaryDirectory(prefix="tp-t267-") as td:
        task_f = os.path.join(td, "task.json")
        out_f = os.path.join(td, "outcome.json")
        with open(task_f, "w", encoding="utf-8") as f:
            json.dump({"taskId": 904, "claimToken": "tok-t267", "caseId": 0,
                       "taskType": 3, "actionJson": action_json},
                      f, ensure_ascii=False)
        proc = subprocess.run([sys.executable, _WORKER, task_f, out_f],
                              capture_output=True, text=True, timeout=_SYS_TIMEOUT,
                              check=False, cwd=_EXECUTOR_DIR)
        assert proc.returncode == 0, \
            f"worker 非零退出: rc={proc.returncode} stderr={proc.stderr[:500]}"
        assert os.path.exists(out_f), f"结果文件未写出: stderr={proc.stderr[:500]}"
        with open(out_f, "r", encoding="utf-8") as f:
            return json.load(f)


class TestElementCandidatesEnvelopeMapping:
    pass

    def test_白名单含element_candidates(self) -> None:
        assert "element-candidates" in BUILTIN_ACTIONS, \
            f"采集动作须入探索白名单，实际={BUILTIN_ACTIONS}"

    def test_信封映射单步采集动作_setup带url(self) -> None:
        doc = build_script_doc({"kind": "explore-act", "action": "element-candidates",
                                "url": LOGIN_URL, "arguments": {}})
        assert doc["steps"] == [{"action": "element-candidates"}], \
            f"须映射驱动采集原语单步，实际={doc['steps']}"
        assert doc["setup"][0]["target"]["url"] == LOGIN_URL


class TestSerializeCandidates:
    pass

    def test_超量裁尾_truncated标记且合法JSON(self) -> None:
        from driver.element_candidates import serialize_candidates
        items = [{"tag": "button", "id": f"b{i}", "testAttr": None, "testId": None,
                  "name": None, "placeholder": None, "text": "按钮" + "字" * 50,
                  "visible": True} for i in range(300)]
        payload_text = serialize_candidates(items)
        parsed = json.loads(payload_text)
        assert parsed["truncated"] is True
        assert parsed["count"] == len(parsed["candidates"])
        assert len(payload_text) <= 6000, "回传预算：detail 不得撑爆 result 载荷"

    def test_不可见元素排除_不可定位元素不冒充候选(self) -> None:
        from driver.element_candidates import serialize_candidates
        items = [
            {"tag": "div", "id": None, "testAttr": None, "testId": None,
             "name": None, "placeholder": None, "text": "隐藏块", "visible": False},
            {"tag": "span", "id": None, "testAttr": None, "testId": None,
             "name": None, "placeholder": None, "text": "无定位文本", "visible": True},
        ]
        parsed = json.loads(serialize_candidates(items))
        assert parsed["candidates"] == [] and parsed["count"] == 0 \
            and parsed["truncated"] is False


class TestElementCandidatesRealPage:
    pass

    def test_夹具页候选含可原样定位的登录元素(self) -> None:
        outcome = _run_worker(_envelope("element-candidates"))
        assert outcome["result"] == "pass", f"采集应 pass: {outcome}"
        payloads = []
        for step in outcome["steps_timeline"]:
            detail = str(step.get("detail") or "")
            if detail.strip().startswith("{"):
                payloads.append(json.loads(detail))
        assert payloads, f"时间线须含候选 JSON detail，实际={outcome['steps_timeline']}"
        parsed = payloads[-1]
        targets = [c["target"] for c in parsed["candidates"]]
        css_targets = {t.get("css") for t in targets if "css" in t}
        assert {"#username", "#password", "#login-btn"} <= css_targets, \
            f"夹具登录三元素须成候选（验证过再写定位），实际={css_targets}"
        for cand in parsed["candidates"]:
            assert set(cand["target"]) <= {"css", "text", "xpath", "role"}, \
                f"target 必须是驱动可解析形态: {cand}"
        btns = [c for c in parsed["candidates"] if c["tag"] == "button"]
        assert btns and any("登" in (c["text"] or "") for c in btns), \
            f"登录按钮候选应携可读文本: {btns}"
