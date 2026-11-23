
from __future__ import annotations

import json

import httpx
import pytest

from testpike_cli.cli import (
    EXIT_ERROR,
    EXIT_FAIL,
    EXIT_PASS,
    exit_code_for_batch_status,
    main,
)
from testpike_cli.client import CiClient, CiError

BASE = "http://api.test:8082"


def _ok(data):
    pass
    return httpx.Response(200, json={"code": "0", "msg": None, "data": data})


def _fail(status, code, msg):
    return httpx.Response(status, json={"code": code, "msg": msg, "data": None})


class Recorder:
    pass

    def __init__(self, responses):
        self.requests = []
        self._responses = list(responses)

    def handler(self, request):
        self.requests.append(request)
        item = self._responses.pop(0) if len(self._responses) > 1 else self._responses[0]
        if isinstance(item, Exception):
            raise item
        return item


def make_client(recorder):
    return CiClient(BASE, "tok-1", transport=httpx.MockTransport(recorder.handler))



def test_run_post_contract():
    pass
    rec = Recorder([_ok({"id": 5, "status": 4, "statusLabel": "执行中"})])
    client = make_client(rec)
    data = client.run_batch({"batch": 5})
    assert data["id"] == 5
    req = rec.requests[0]
    assert req.method == "POST"
    assert req.url.path == "/open/ci/runs"
    assert req.headers["authorization"] == "Bearer tok-1"
    key = req.headers["idempotency-key"]
    assert len(key) == 32 and int(key, 16) >= 0  # uuid4().hex
    assert json.loads(req.content) == {"batch": 5}


def test_run_explicit_idempotency_key_not_regenerated():
    rec = Recorder([_ok({"id": 5})])
    make_client(rec).run_batch({"batch": 5}, idempotency_key="key-abc")
    assert rec.requests[0].headers["idempotency-key"] == "key-abc"


def test_status_and_report_paths():
    rec = Recorder([_ok({"status": 6}), _ok({"status": 6, "passRate": 1.0})])
    client = make_client(rec)
    client.get_status(77)
    client.get_report(77)
    assert rec.requests[0].url.path == "/open/ci/runs/77"
    assert rec.requests[1].url.path == "/open/ci/runs/77/report"


def test_error_envelope_human_message_no_traceback():
    pass
    rec = Recorder([_fail(401, "401", "Token 已过期或已吊销")])
    with pytest.raises(CiError) as ei:
        make_client(rec).get_status(1)
    text = str(ei.value)
    assert "Token 已过期或已吊销" in text
    assert "Traceback" not in text


def test_network_error_maps_to_cierror():
    rec = Recorder([httpx.ConnectError("connection refused")])
    with pytest.raises(CiError) as ei:
        make_client(rec).get_status(1)
    assert "无法访问" in str(ei.value) or "不可达" in str(ei.value)



@pytest.mark.parametrize(
    ("status", "expected"),
    [(6, EXIT_PASS), (7, EXIT_FAIL), (8, EXIT_FAIL), (3, EXIT_ERROR), (4, EXIT_ERROR)],
)
def test_exit_code_matrix(status, expected):
    assert exit_code_for_batch_status(status) == expected



def env_with_token():
    return {"TESTPIKE_CI_TOKEN": "tok-1"}


def run_main(argv, recorder, *, environ=None, sleeps=None):
    factory = lambda base, token: CiClient(base, token, transport=httpx.MockTransport(recorder.handler))
    return main(argv, environ=environ if environ is not None else env_with_token(),
                client_factory=factory,
                sleeper=(sleeps.append if sleeps is not None else None))


def main_argv(command, *tail):
    return [command, "--base-url", BASE, *tail]


def test_main_run_batch_exit0(capsys):
    rec = Recorder([_ok({"id": 5, "status": 4, "statusLabel": "执行中"})])
    assert run_main(main_argv("run", "--batch", "5"), rec) == EXIT_PASS
    assert rec.requests[0].url.path == "/open/ci/runs"
    assert "5" in capsys.readouterr().out


def test_main_status_command_prints_human_line(capsys):
    pass
    rec = Recorder([_ok({"status": 6, "statusLabel": "已完成"})])
    assert run_main(main_argv("status", "77"), rec) == EXIT_PASS
    out = capsys.readouterr().out
    assert "77" in out and "已完成" in out


def test_main_run_requires_batch_or_profile(capsys):
    rec = Recorder([_ok({})])
    assert run_main(main_argv("run"), rec) == EXIT_ERROR
    assert "batch" in capsys.readouterr().err


def test_main_run_profile_without_project_exit2():
    rec = Recorder([_ok({})])
    assert run_main(main_argv("run", "--profile", "smoke"), rec) == EXIT_ERROR


def test_main_run_env_flags_map_to_payload():
    rec = Recorder([_ok({"id": 9})])
    rc = run_main(main_argv("run", "--project", "3", "--profile", "smoke",
                            "--env-id", "2", "--env-url", "http://fixture.test",
                            "--env-account-ref", "acct-a"), rec)
    assert rc == EXIT_PASS
    body = json.loads(rec.requests[0].content)
    assert body["projectId"] == 3 and body["profile"] == "smoke" and body["envId"] == 2
    assert body["envOverrides"] == {"url": "http://fixture.test", "accountRef": "acct-a"}


def test_main_watch_polls_until_terminal_respecting_interval(capsys):
    rec = Recorder([_ok({"id": 5}),
                    _ok({"status": 4, "statusLabel": "执行中"}),
                    _ok({"status": 7, "statusLabel": "部分失败"})])
    sleeps: list[float] = []
    rc = run_main(main_argv("run", "--batch", "5", "--watch", "--interval", "2"),
                  rec, sleeps=sleeps)
    assert rc == EXIT_FAIL
    assert sleeps == [2.0]
    assert "部分失败" in capsys.readouterr().out


def test_main_watch_exit0_when_batch_done():
    rec = Recorder([_ok({"id": 5}), _ok({"status": 6, "statusLabel": "已完成"})])
    sleeps: list[float] = []
    assert run_main(main_argv("run", "--batch", "5", "--watch"), rec, sleeps=sleeps) == EXIT_PASS
    assert sleeps == []


def test_main_missing_token_env_exit2_human(capsys):
    rec = Recorder([_ok({"id": 5})])
    rc = run_main(main_argv("run", "--batch", "5"), rec, environ={})
    assert rc == EXIT_ERROR
    err = capsys.readouterr().err
    assert "TESTPIKE_CI_TOKEN" in err and "Traceback" not in err


def test_main_token_revoked_exit2(capsys):
    rec = Recorder([_fail(403, "403", "Token 已吊销")])
    assert run_main(main_argv("status", "5"), rec) == EXIT_ERROR
    assert "已吊销" in capsys.readouterr().err


def test_main_report_writes_json_and_exit_by_status(tmp_path):
    rec = Recorder([_ok({"batchId": 5, "status": 7, "statusLabel": "部分失败",
                         "passRate": 0.8, "passRatePhrase": "通过率 80%"})])
    out = tmp_path / "report.json"
    rc = run_main(main_argv("report", "5", "--out", str(out)), rec)
    assert rc == EXIT_FAIL
    doc = json.loads(out.read_text(encoding="utf-8"))
    assert doc["passRate"] == 0.8 and doc["status"] == 7


def test_main_report_default_stdout(tmp_path, capsys):
    rec = Recorder([_ok({"batchId": 5, "status": 6, "passRate": 1.0})])
    assert run_main(main_argv("report", "5"), rec) == EXIT_PASS
    assert json.loads(capsys.readouterr().out)["status"] == 6
