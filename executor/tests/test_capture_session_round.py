
from __future__ import annotations

import logging
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

import pytest  # noqa: E402

from capture import capture_round  # noqa: E402
from capture.capture_core import capture_candidates  # noqa: E402
from capture.capture_round import (  # noqa: E402
    CaptureRoundResult,
    poll_and_run_capture_round,
    run_capture_round,
    to_metadata_rows,
)

SECRET = "SEC-value-never-leaves-9137"
ENDPOINT = "http://127.0.0.1:59999/devtools/browser/dead-beef"
DEAD_ENDPOINT = "http://127.0.0.1:1/devtools/browser/x"


from capture_stub import CaptureRoundStubClient as _StubClient  # noqa: E402


def _session(**over):
    payload = {"sessionId": "sc-1", "projectId": 7, "stateName": "甲账号",
               "scopeOrigins": ["https://app.example"], "anchorKind": "url-prefix",
               "anchorValue": "https://app.example/home", "debugEndpoint": ENDPOINT,
               "mode": "local-direct", "validateTimeoutMs": 1500, "attempt": 1}
    payload.update(over)
    return payload


class TestMetadataProjection:
    def test_投影只留元数据_值字段零命中(self):
        rows = to_metadata_rows([
            {"kind": "cookie", "name": "SESSION", "value": SECRET,
             "origin": "https://app.example", "expires_at": 1_893_456_000.0,
             "http_only": True},
            {"kind": "local_storage", "name": "auth", "value": SECRET,
             "origin": "https://app.example", "expires_at": None},
        ])
        assert rows[0] == {"kind": "cookie", "name": "SESSION",
                           "origin": "https://app.example",
                           "expiresAtEpochSecond": 1_893_456_000.0}
        assert "expiresAtEpochSecond" not in rows[1]
        assert SECRET not in repr(rows), "候选值禁止出现在回写形态（红线3）"

    def test_无名条目与坏形态丢弃(self):
        broken: list = [{"kind": "cookie"}, {"name": "", "value": SECRET}, "not-a-mapping"]
        assert to_metadata_rows(broken) == []


class TestHonestFailure:
    def test_死端点走真读取路径_必须报拒因而非空成功(self):
        result = run_capture_round(_session(debugEndpoint=DEAD_ENDPOINT),
                                   capture=capture_candidates)
        assert isinstance(result, CaptureRoundResult)
        assert result.rows == []
        assert result.failure_reason, "连不上必须给拒因，禁把失败伪装成「没有登录态」"
        assert result.succeeded is False
        assert DEAD_ENDPOINT not in result.failure_reason, "拒因句禁止携带调试端点串（红线4）"

    def test_参数不完整_不外呼不假成功(self):
        result = run_capture_round(_session(debugEndpoint=""), capture=capture_candidates)
        assert result.failure_reason and not result.rows

    def test_白名单不合法_归因为参数错误(self):
        result = run_capture_round(_session(scopeOrigins=["not a url"],
                                            debugEndpoint=DEAD_ENDPOINT),
                                   capture=capture_candidates)
        assert result.failure_reason and "白名单" in result.failure_reason
        assert DEAD_ENDPOINT not in result.failure_reason

    def test_失败日志零端点命中(self, caplog):
        with caplog.at_level(logging.DEBUG, logger="testpike.executor"):
            run_capture_round(_session(debugEndpoint=DEAD_ENDPOINT), capture=capture_candidates)
        assert DEAD_ENDPOINT not in caplog.text, "调试端点串禁止落日志（红线4）"


class TestRoundWiring:
    def test_领到才回写_请求体只有元数据(self, monkeypatch):
        client = _StubClient(_session())
        monkeypatch.setattr(capture_round, "run_capture_round", lambda session, **kw:
                            CaptureRoundResult(session_id=session["sessionId"],
                                               rows=to_metadata_rows([
                                                   {"kind": "cookie", "name": "S",
                                                    "value": SECRET,
                                                    "origin": "https://app.example"}])))

        out = poll_and_run_capture_round(client)

        assert out is not None and out.session_id == "sc-1"
        assert client.reports == [{"sessionId": "sc-1",
                                   "candidates": [{"kind": "cookie", "name": "S",
                                                   "origin": "https://app.example"}],
                                   "failureReason": None}]
        assert SECRET not in repr(client.reports)

    def test_无待领会话_零回写零惊(self):
        client = _StubClient(None)
        assert poll_and_run_capture_round(client) is None
        assert client.claims == 1 and client.reports == []

    def test_真失败态同样回写_向导必须知情(self):
        client = _StubClient(_session(debugEndpoint=DEAD_ENDPOINT))
        out = poll_and_run_capture_round(client)
        assert out is not None and out.failure_reason and not out.rows
        assert client.reports[0]["candidates"] == []
        assert client.reports[0]["failureReason"]

    def test_超时值取自会话载荷_执行侧禁硬编码(self, monkeypatch):
        seen: dict = {}

        def _spy(endpoint, origins, *, timeout_s):
            seen["timeout_s"] = timeout_s
            return []

        monkeypatch.setattr(capture_round, "capture_candidates", _spy, raising=False)
        run_capture_round(_session(validateTimeoutMs=2500), capture=_spy)
        assert seen["timeout_s"] == pytest.approx(2.5)
