
from __future__ import annotations

import json
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from protocol import ExecutorProtocolClient, ProtocolError


class _FakeApi:
    pass

    def __init__(self) -> None:
        self.requests: list[dict] = []
        self.responses: dict[str, dict] = {}
        self.server = HTTPServer(("127.0.0.1", 0), self._handler_factory())
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def _handler_factory(self):
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802
                length = int(self.headers.get("Content-Length", 0))
                raw = self.rfile.read(length) if length else b"{}"
                outer.requests.append({"path": self.path, "body": json.loads(raw or b"{}")})
                resp = outer.responses.get(self.path)
                if resp is None:
                    for k, v in outer.responses.items():
                        if self.path.startswith(k):
                            resp = v
                            break
                self._reply(resp or {"code": "0", "data": {}})

            def _reply(self, body: dict) -> None:
                data = json.dumps(body, ensure_ascii=False).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args):
                pass

        return Handler

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def close(self) -> None:
        self.server.shutdown()


@pytest.fixture
def fake_api():
    srv = _FakeApi()
    yield srv
    srv.close()



def test_protocol_renew_device_lease_hits_api_with_executor_id(fake_api):
    fake_api.responses["/internal/appauto/devices/7/lease/renew?executorId=42"] = \
        {"code": "0", "data": {"renewed": True}}
    client = ExecutorProtocolClient(fake_api.base_url)
    client.executor_id = 42
    data = client.renew_device_lease(7)
    assert data.get("renewed") is True, "core 续租 REST 口径 data={renewed:...} Map 包装"


def test_protocol_release_device_lease_encodes_human_reason(fake_api):
    reason = urllib.parse.quote("正常完成，App 会话收口归还设备")
    path = f"/internal/appauto/devices/7/lease/release?executorId=42&reason={reason}"
    fake_api.responses[path] = {"code": "0", "data": {"released": True}}
    client = ExecutorProtocolClient(fake_api.base_url)
    client.executor_id = 42
    data = client.release_device_lease(7, reason="正常完成，App 会话收口归还设备")
    assert data.get("released") is True
    got = fake_api.requests[-1]["path"]
    assert "/lease/release" in got and "executorId=42" in got


def test_protocol_release_without_reason_omits_param(fake_api):
    client = ExecutorProtocolClient(fake_api.base_url)
    client.executor_id = 9
    client.release_device_lease(3)
    got = fake_api.requests[-1]["path"]
    assert got.startswith("/internal/appauto/devices/3/lease/release?executorId=9")
    assert "reason=" not in got, "空归因不出键（尾追兼容姿势）"


def test_protocol_lease_business_error_raises(fake_api):
    fake_api.responses["/internal/appauto/devices/1/lease/renew?executorId=5"] = \
        {"code": "40404", "msg": "设备不存在"}
    client = ExecutorProtocolClient(fake_api.base_url)
    client.executor_id = 5
    with pytest.raises(ProtocolError) as e:
        client.renew_device_lease(1)
    assert e.value.code == "40404"



def _app_task(steps: list[dict], lease_ref, fake_api, executor_id=42) -> dict:
    env = {"kind": "app-auto-run", "engine": {
        "code": "FAKE-L2", "platformCodes": ["ANDROID"],
        "deviceKindCodes": ["EMULATOR"]}, "steps": steps,
        "runId": 9101, "leaseRef": lease_ref}
    return {"taskId": 11, "actionJson": json.dumps(env, ensure_ascii=False),
            "_apiBaseUrl": fake_api.base_url, "_executorId": executor_id}


class _OkAdapter:
    def __init__(self):
        self.prepared = False

    def prepare(self, ctx):
        assert isinstance(ctx.get("leaseRef"), dict), \
            "信封消费面 leaseRef 契约=dict{deviceId,serial}（形态统一收口）"
        self.prepared = True

    def execute(self, step):
        return {"result": "pass"}

    def collect(self, artifact):
        return None

    def teardown(self):
        pass


def test_return_lease_success_reason_posts_release(fake_api):
    from app_worker import run_app_task
    lease = {"deviceId": 7, "serial": "em-1"}
    outcome = run_app_task(_app_task([{"action": "app.tap"}], lease, fake_api))
    assert outcome.result == "pass"
    posts = [r for r in fake_api.requests if "/lease/release" in r["path"]]
    assert len(posts) == 1, "会话收后必归还一次"
    assert "executorId=42" in posts[0]["path"]
    decoded = urllib.parse.unquote(posts[0]["path"])
    assert "7" in decoded and "正常完成" in decoded, "归因人话=正常完成收口"


def test_return_lease_first_error_attribution_carries_step_no(fake_api):
    from app_worker import run_app_task

    class FailOnSecond:
        def prepare(self, ctx):
            pass

        def execute(self, step):
            if step["action"] == "app.input":
                return {"result": "fail", "errorType": 2, "detail": "输入框未找到"}
            return {"result": "pass"}

        def collect(self, artifact):
            return None

        def teardown(self):
            pass

    lease = {"deviceId": 8, "serial": "em-2"}
    outcome = run_app_task({
        **_app_task([{"action": "app.tap"}, {"action": "app.input"}], lease, fake_api),
        "_adapterOverride": FailOnSecond()})
    assert outcome.result == "fail"
    posts = [urllib.parse.unquote(r["path"]) for r in fake_api.requests
             if "/lease/release" in r["path"]]
    assert len(posts) == 1
    assert "/internal/appauto/devices/8/lease/release" in posts[0]
    assert "第 2 步" in posts[0] and "首错终局" in posts[0], "归因含首错步号+终局人话"


def test_return_lease_prepare_failure_attribution(fake_api):
    from app_worker import run_app_task

    class BadPrepare:
        def prepare(self, ctx):
            raise RuntimeError("设备离线")

        def execute(self, step):
            raise AssertionError("prepare 失败后禁执行")

        def collect(self, artifact):
            return None

        def teardown(self):
            pass

    lease = {"deviceId": 9, "serial": "em-3"}
    outcome = run_app_task({
        **_app_task([{"action": "app.tap"}], lease, fake_api),
        "_adapterOverride": BadPrepare()})
    assert outcome.result == "fail"
    posts = [urllib.parse.unquote(r["path"]) for r in fake_api.requests
             if "/lease/release" in r["path"]]
    assert len(posts) == 1 and "会话准备失败" in posts[0]


def test_return_lease_skips_when_channel_missing(fake_api):
    pass
    from app_worker import run_app_task
    task = _app_task([{"action": "app.tap"}], {"deviceId": 7, "serial": "em-1"}, fake_api)
    task.pop("_apiBaseUrl")
    outcome = run_app_task(task)
    assert outcome.result == "pass"
    assert not [r for r in fake_api.requests if "/lease/release" in r["path"]]


def test_return_lease_non_dict_ref_skips_release(fake_api):
    pass
    from app_worker import run_app_task
    outcome = run_app_task(_app_task([{"action": "app.tap"}], "lease-legacy-1", fake_api))
    assert outcome.result == "pass"
    assert not [r for r in fake_api.requests if "/lease/release" in r["path"]]


def test_return_lease_release_error_leaves_trace_not_override(fake_api):
    from app_worker import run_app_task
    lease = {"deviceId": 10, "serial": "em-4"}
    path = "/internal/appauto/devices/10/lease/release"
    fake_api.responses[path] = {"code": "500", "msg": "core 暂时不可达"}
    outcome = run_app_task(_app_task([{"action": "app.tap"}], lease, fake_api))
    assert outcome.result == "pass", "归还通道炸不改判执行结果"
    assert "租约归还" in (outcome.error_detail or ""), "归还异常留痕不静默"


def test_envelope_lease_ref_dict_shape_flows_to_adapter_ctx(fake_api):
    pass
    from app_worker import run_app_task
    seen: dict = {}

    class Spy:
        def prepare(self, ctx):
            seen.update(ctx)

        def execute(self, step):
            return {"result": "pass"}

        def collect(self, artifact):
            return None

        def teardown(self):
            pass

    lease = {"deviceId": 12, "serial": "em-5"}
    run_app_task({**_app_task([{"action": "app.tap"}], lease, fake_api),
                  "_adapterOverride": Spy()})
    assert seen.get("leaseRef") == lease



def test_lease_of_task_extracts_dict_from_app_auto_envelope():
    from runner import _lease_of_task
    env = {"kind": "app-auto-run", "leaseRef": {"deviceId": 5, "serial": "em-1"},
           "steps": []}
    task = {"taskType": 6, "taskId": 1, "actionJson": json.dumps(env)}
    assert _lease_of_task(task) == {"deviceId": 5, "serial": "em-1"}


def test_lease_of_task_none_for_non_app_or_bad_envelope():
    from runner import _lease_of_task
    assert _lease_of_task({"taskType": 1, "actionJson": "{}"}) is None, "非 APP 任务零登记"
    assert _lease_of_task({"taskType": 6, "actionJson": "非JSON"}) is None
    assert _lease_of_task({"taskType": 6, "actionJson": json.dumps(
        {"kind": "app-auto-run", "leaseRef": "legacy-str"})}) is None, \
        "形态漂移（字符串）不进活跃表=主进程续租无从下手，交 TTL 兜底"
    assert _lease_of_task({"taskType": 6, "actionJson": json.dumps(
        {"kind": "app-auto-run", "leaseRef": {"serial": "no-id"}})}) is None, \
        "缺 deviceId 不可寻址=不登记"


def test_worker_task_payload_injects_lease_channel():
    from runner import _worker_task_payload

    class C:
        executor_id = 42

    cfg = type("Cfg", (), {"api_base_url": "http://api:8082"})()
    payload = _worker_task_payload({"taskId": 7, "actionJson": "{}"}, C(), cfg)
    assert payload["_executorId"] == 42 and payload["_apiBaseUrl"] == "http://api:8082"
    assert payload["taskId"] == 7, "原字段零丢失"



def test_renew_active_leases_calls_each_and_survives_errors(fake_api):
    from runner import _ACTIVE_LEASES, _ACTIVE_LOCK, renew_active_leases
    client = ExecutorProtocolClient(fake_api.base_url)
    client.executor_id = 42
    with _ACTIVE_LOCK:
        _ACTIVE_LEASES[101] = {"deviceId": 5, "serial": "em-1"}
        _ACTIVE_LEASES[102] = {"deviceId": 6, "serial": "em-2"}
    try:
        fake_api.responses["/internal/appauto/devices/6/lease/renew?executorId=42"] = \
            {"code": "500", "msg": "boom"}
        renew_active_leases(client)
        paths = [r["path"] for r in fake_api.requests if "/lease/renew" in r["path"]]
        assert any("/devices/5/" in p for p in paths)
        assert any("/devices/6/" in p for p in paths)
    finally:
        with _ACTIVE_LOCK:
            _ACTIVE_LEASES.pop(101, None)
            _ACTIVE_LEASES.pop(102, None)


def test_release_lease_for_task_releases_and_unregisters(fake_api):
    from runner import _ACTIVE_LEASES, _ACTIVE_LOCK, release_lease_for_task
    client = ExecutorProtocolClient(fake_api.base_url)
    client.executor_id = 42
    with _ACTIVE_LOCK:
        _ACTIVE_LEASES[201] = {"deviceId": 8, "serial": "em-9"}
    try:
        release_lease_for_task(client, 201, reason="执行超时终止，按超时归还设备")
        posts = [urllib.parse.unquote(r["path"]) for r in fake_api.requests
                 if "/lease/release" in r["path"]]
        assert len(posts) == 1 and "执行超时" in posts[0] and "/devices/8/" in posts[0]
        with _ACTIVE_LOCK:
            assert 201 not in _ACTIVE_LEASES, "归还后登记注销（禁陈旧续租）"
    finally:
        with _ACTIVE_LOCK:
            _ACTIVE_LEASES.pop(201, None)


def test_release_lease_for_task_quiet_when_no_lease(fake_api):
    from runner import release_lease_for_task
    client = ExecutorProtocolClient(fake_api.base_url)
    client.executor_id = 42
    release_lease_for_task(client, 999, reason="不该触网")
    assert fake_api.requests == [], "无活跃租约=零请求"
