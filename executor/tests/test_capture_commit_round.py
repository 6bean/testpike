""" U3b executor-side commit-round L2: checked projection both-ways, honest invalidation, wiring.

Calibre (authority = design doc round 3 + red lines 2/3/4):
- Unchecked values are never sent out — the projection keeps checked names with
 their freshly read values and drops unchecked entries wholesale, so an
 unchecked key name cannot even ride the request body as text;
- A browser session lost by commit time answers honestly (failure reason that
 points the wizard back to re-opening), never a fake empty success; the real
 CDP read path is exercised against a dead endpoint (transport not mocked);
- Values are only ever observed in memory and in the one commit-values request:
 no log line may contain them, no transport error text may echo them;
- Idle-round branch: a claim carrying commitSelection runs the commit round and
 answers on commit_capture_values; without one the metadata round runs on
 report_capture_candidates exactly as before.
"""
from __future__ import annotations

import logging

import pytest  # noqa: E402  (sys.path bootstrap lives in tests/conftest.py)

from capture_stub import CaptureRoundStubClient as _StubClient  # noqa: E402

from capture import capture_round  # noqa: E402
from capture.capture_core import capture_candidates  # noqa: E402
from capture.capture_round import (  # noqa: E402
    CaptureRoundResult,
    poll_and_run_capture_round,
    run_commit_round,
    to_committed_entries,
)
from protocol import ExecutorProtocolClient, ProtocolError  # noqa: E402

CHECKED = "COMMIT-checked-value-4471"
UNCHECKED = "COMMIT-unchecked-value-4472"
ENDPOINT = "http://127.0.0.1:59999/devtools/browser/dead-beef"
DEAD_ENDPOINT = "http://127.0.0.1:1/devtools/browser/x"


def _entries():
    """Raw CDP-shaped entries: two checked names, one unchecked, one expiry row."""
    return [
        {"kind": "cookie", "name": "sid", "origin": "https://app.example", "value": CHECKED},
        {"kind": "cookie", "name": "csrf", "origin": "https://app.example", "value": UNCHECKED},
        {"kind": "local_storage", "name": "token", "origin": "https://app.example",
         "value": CHECKED + "-ls", "expires_at": 1893456000},
    ]


def _session(**over):
    payload = {"sessionId": "sc-9", "projectId": 7, "stateName": "甲账号",
               "scopeOrigins": ["https://app.example"], "anchorKind": "url-prefix",
               "anchorValue": "https://app.example/home", "debugEndpoint": ENDPOINT,
               "mode": "local-direct", "validateTimeoutMs": 1500, "attempt": 1,
               "commitSelection": ["sid", "token"]}
    payload.update(over)
    return payload


class TestCommittedProjection:
    def test_勾选名带值外发_未勾选名连名字零外发(self):
        rows = to_committed_entries(_entries(), ["sid", "token"])
        assert {r["name"] for r in rows} == {"sid", "token"}
        body = repr(rows)
        assert UNCHECKED not in body and "csrf" not in body, \
            "unchecked entry must not ride the request body at all (red line 2)"
        sid = next(r for r in rows if r["name"] == "sid")
        assert sid["value"] == CHECKED and sid["origin"] == "https://app.example"
        token = next(r for r in rows if r["name"] == "token")
        assert token["expiresAtEpochSecond"] == 1893456000

    def test_空勾选子集_不外呼不假成功(self):
        calls: list = []

        def _spy(*a, **k):
            calls.append(a)
            return []

        result = run_commit_round(_session(commitSelection=[]), capture=_spy)
        assert calls == [] and not result.succeeded
        assert "重新发起" in result.failure_reason


class TestCommitRoundHonesty:
    def test_死端点走真读取路径_报作废拒因非空成功(self):
        result = run_commit_round(_session(debugEndpoint=DEAD_ENDPOINT),
                                  capture=capture_candidates)
        assert not result.succeeded and result.rows == []
        assert "重新发起" in result.failure_reason

    def test_提交回合日志零值命中(self, caplog):
        with caplog.at_level(logging.DEBUG, logger="testpike.executor"):
            out = run_commit_round(_session(), capture=lambda *a, **k: _entries())
        assert CHECKED not in caplog.text and UNCHECKED not in caplog.text
        assert len(out.rows) == 2  # values live in memory only (the one request)


class TestPollBranch:
    def test_带勾选清单走提交分支_经commit_values门回写(self, monkeypatch):
        client = _StubClient(_session())
        monkeypatch.setattr(capture_round, "run_commit_round", lambda session, **kw:
                            CaptureRoundResult(session_id=session["sessionId"],
                                               rows=[{"kind": "cookie", "name": "sid",
                                                      "origin": "https://app.example",
                                                      "value": CHECKED}]))

        out = poll_and_run_capture_round(client)

        assert out is not None and client.reports == []
        assert client.commits == [{"sessionId": "sc-9",
                                   "entries": [{"kind": "cookie", "name": "sid",
                                                "origin": "https://app.example",
                                                "value": CHECKED}],
                                   "failureReason": None}]

    def test_无勾选清单维持元数据分支(self, monkeypatch):
        client = _StubClient(_session(commitSelection=None))
        monkeypatch.setattr(capture_round, "run_capture_round", lambda session, **kw:
                            CaptureRoundResult(session_id=session["sessionId"], rows=[]))

        out = poll_and_run_capture_round(client)

        assert out is not None and client.commits == [] and len(client.reports) == 1

    def test_提交分支真死端点回写作废态_向导必须知情(self):
        client = _StubClient(_session(debugEndpoint=DEAD_ENDPOINT))

        out = poll_and_run_capture_round(client)

        assert out is not None and out.failure_reason and not out.rows
        assert client.commits[0]["entries"] == [] and client.commits[0]["failureReason"]


class TestProtocolCommitValues:
    def test_请求体只装勾选值_拒因非空才加键(self):
        client = ExecutorProtocolClient("http://127.0.0.1:9")
        client.executor_id = 3
        sent: dict = {}

        def _spy(path, payload):
            sent["path"], sent["payload"] = path, payload
            return 2

        client._post = _spy
        assert client.commit_capture_values("sc-9", entries=[{"name": "sid"}]) == 2
        assert sent["path"] == "/internal/session-capture/sc-9/commit-values"
        assert sent["payload"] == {"executorId": 3, "entries": [{"name": "sid"}]}

        client.commit_capture_values("sc-9", entries=[], failure_reason="浏览器会话已失效")
        assert sent["payload"]["failureReason"] == "浏览器会话已失效"

    def test_死端口真连接_错误句零值命中(self):
        client = ExecutorProtocolClient("http://127.0.0.1:1", timeout=1.0)
        client.executor_id = 3
        with pytest.raises(ProtocolError) as exc:
            client.commit_capture_values(
                "sc-9", entries=[{"kind": "cookie", "name": "sid", "value": CHECKED}])
        message = str(exc.value)
        assert "/internal/session-capture/sc-9/commit-values" in message
        assert CHECKED not in message, "transport error text must never echo captured values"
