
from __future__ import annotations

import base64
import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

from app_worker import run_app_task
from protocol import ExecutorProtocolClient, ProtocolError

PATH = "/internal/appauto/visual-locate"


class _VisionServer:
    pass

    def __init__(self, reply: dict | None = None) -> None:
        self.requests: list[dict] = []
        self.reply = reply if reply is not None else {"code": "0", "data": {}}
        self.server = HTTPServer(("127.0.0.1", 0), self._handler_factory())
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def _handler_factory(self):
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0))
                raw = self.rfile.read(length) if length else b"{}"
                outer.requests.append({"path": self.path,
                                       "body": json.loads(raw)})
                self._reply(outer.reply)

            def _reply(self, body: dict) -> None:
                data = json.dumps(body, ensure_ascii=False).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, format: str, *args) -> None:
                pass

        return Handler

    def stop(self) -> None:
        self.server.shutdown()


class _LocatorFailAdapter:
    pass

    def __init__(self) -> None:
        self._clicked = False

    def prepare(self, ctx):
        pass

    def execute(self, step):
        if step.get("x") is not None and step.get("y") is not None:
            # coordinate retry rides the adapters' shared top-level x/y face
            return {"result": "pass"}
        return {"result": "fail", "errorType": 2, "detail": "selector 未命中"}

    def collect(self, artifact):
        import os
        d = artifact["artifactDir"]
        os.makedirs(d, exist_ok=True)
        shot = os.path.join(d, f"shot-{artifact['stepNo']}.png")
        tree = os.path.join(d, f"tree-{artifact['stepNo']}.json")
        with open(shot, "wb") as f:
            f.write(b"PNGBYTES")
        with open(tree, "w", encoding="utf-8") as f:
            json.dump([{"x": 90, "y": 190, "w": 60, "h": 40, "label": "提交"}], f)
        return [{"kind": "screenshot", "localPath": shot},
                {"kind": "dom_snapshot", "localPath": tree}]

    def teardown(self):
        pass


_ON = {"ai.vision.enabled": "1", "ai.vision.appauto-locate": "1",
       "ai.appauto.locate.confidence": "0.85,0.6"}


def _task(server_port: int, api_base=True):
    envelope = {"kind": "app-auto-run", "runId": 1,
                "engine": {"code": "STUB"},
                "visualFallback": _ON,
                "steps": [{"action": "app.tap",
                           "selector": {"testTag": "submit"}}]}
    task = {"taskId": 902, "actionJson": json.dumps(envelope),
            "_adapterOverride": _LocatorFailAdapter()}
    if api_base:
        task["_apiBaseUrl"] = f"http://127.0.0.1:{server_port}"
        task["_executorId"] = "7"
    return task


def _row(outcome):
    return next(r for r in outcome.steps_timeline if r.get("stepNo") == 1)



def test_visual_locate_posts_protocol_shape():
    pass
    srv = _VisionServer({"code": "0", "data": {"x": 110.0, "y": 205.0,
                                               "label": "提交按钮",
                                               "confidence": 0.95}})
    try:
        client = ExecutorProtocolClient(f"http://127.0.0.1:{srv.port}")
        client.executor_id = 7
        got = client.visual_locate({"taskId": 902, "runId": 1, "stepNo": 1,
                                    "action": "app.tap", "verb": "tap",
                                    "a11yCandidates": [],
                                    "screenshotB64": "aGk=",
                                    "screenshotMime": "image/png"})
        assert got["x"] == 110.0 and got["confidence"] == 0.95
        sent = srv.requests[0]
        assert sent["path"] == PATH
        assert sent["body"]["taskId"] == 902
        assert sent["body"]["screenshotB64"] == "aGk="
    finally:
        srv.stop()


def test_visual_locate_business_error_raises_protocol_error():
    pass
    srv = _VisionServer({"code": "50001", "msg": "视觉模型通道不可用"})
    try:
        client = ExecutorProtocolClient(f"http://127.0.0.1:{srv.port}")
        client.executor_id = 7
        import pytest
        with pytest.raises(ProtocolError):
            client.visual_locate({"taskId": 1})
    finally:
        srv.stop()



def test_real_channel_wired_via_protocol_flips_step_green():
    pass
    srv = _VisionServer({"code": "0", "data": {"x": 110.0, "y": 205.0,
                                               "label": "提交", "confidence": 1.0}})
    try:
        outcome = run_app_task(_task(srv.port))
        assert outcome.result == "pass", outcome.error_detail
        row = _row(outcome)
        assert row["aiLocate"]["tier"] == "direct"
        req = srv.requests[0]["body"]
        assert base64.b64decode(req["screenshotB64"]) == b"PNGBYTES"
        assert req["screenshotMime"] == "image/png"
        assert any(c.get("label") == "提交" for c in req["a11yCandidates"])
        assert req["taskId"] == 902 and req["stepNo"] == 1
    finally:
        srv.stop()


def test_channel_service_error_annotated_step_kept_fail():
    pass
    srv = _VisionServer({"code": "50001", "msg": "视觉模型网关超时"})
    try:
        outcome = run_app_task(_task(srv.port))
        assert outcome.result == "fail"
        assert "视觉兜底通道异常" in (_row(outcome).get("detail") or "")
    finally:
        srv.stop()


def test_legacy_payload_without_injection_stays_unwired():
    pass
    srv = _VisionServer()
    try:
        outcome = run_app_task(_task(srv.port, api_base=False))
        assert outcome.result == "fail"
        assert "通道未接线" in (_row(outcome).get("detail") or "")
        assert not srv.requests
    finally:
        srv.stop()


def test_switch_off_never_builds_channel_request():
    pass
    srv = _VisionServer()
    try:
        task = _task(srv.port)
        envelope = json.loads(task["actionJson"])
        del envelope["visualFallback"]
        task["actionJson"] = json.dumps(envelope)
        outcome = run_app_task(task)
        assert outcome.result == "fail"
        assert not srv.requests
    finally:
        srv.stop()


def test_explicit_callable_channel_takes_precedence():
    pass
    srv = _VisionServer()
    try:
        task = _task(srv.port)
        task["_visualLocateChannel"] = (
            lambda req: {"x": 110.0, "y": 205.0, "label": "提交",
                         "confidence": 1.0})
        outcome = run_app_task(task)
        assert outcome.result == "pass"
        assert not [r for r in srv.requests if r["path"] == PATH]
    finally:
        srv.stop()
