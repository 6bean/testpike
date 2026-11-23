
import hashlib
import json
import os
import sys
import types

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import app_worker
import protocol

APK_BYTES = b"PK\x03\x04fake-apk-payload-for-t326-p3b"
APK_SHA = hashlib.sha256(APK_BYTES).hexdigest()


def _usage(free_bytes: int):
    return types.SimpleNamespace(total=1024 ** 4, used=1024 ** 3, free=free_bytes)



def test_protocol_grant_package_download_posts_internal_path(monkeypatch):
    captured = {}

    class Resp:
        status_code = 200

        def json(self):
            return {"code": 0, "data": {"downloadUrl": "http://s3obj/presigned",
                                        "sha256": APK_SHA}}

    def fake_post(url, json=None, timeout=None):
        captured["url"] = url
        captured["payload"] = json
        return Resp()

    monkeypatch.setattr(protocol.requests, "post", fake_post)
    client = protocol.ExecutorProtocolClient("http://api:8082")
    client.executor_id = 9
    data = client.grant_package_download({"taskId": 77, "artifactRef": "com.demo.app"})
    assert captured["url"] == "http://api:8082/internal/appauto/packages/download-grant"
    assert captured["payload"] == {"taskId": 77, "artifactRef": "com.demo.app"}
    assert data["sha256"] == APK_SHA



class RecordingAdapter:
    def __init__(self):
        self.executed = []

    def prepare(self, ctx):
        pass

    def execute(self, step):
        self.executed.append(dict(step))
        return {"result": "pass"}

    def collect(self, step):
        return None

    def teardown(self):
        pass


def _task(steps, grant):
    task = {
        "taskId": 77,
        "actionJson": json.dumps({"kind": "app-auto-run", "runId": "r-1",
                                  "engine": {"code": "MOBILEWRIGHT"}, "steps": steps}),
        "_adapterOverride": RecordingAdapter(),
    }
    if grant is not None:
        task["_packageGrantChannel"] = grant
    return task


def _install_steps():
    return [{"action": "app.install", "pkg": "com.demo.app", "artifactRef": 12}]



def test_install_step_gets_localPath_injected_and_cleaned_after(monkeypatch, tmp_path):
    downloaded = {}

    class FakeResp:
        def __init__(self, content):
            self._c = content

        def iter_content(self, chunk_size=8192):
            for i in range(0, len(self._c), chunk_size):
                yield self._c[i:i + chunk_size]

        def raise_for_status(self):
            pass

    def fake_get(url, **kw):
        downloaded["url"] = url
        return FakeResp(APK_BYTES)

    monkeypatch.setattr(protocol.requests, "get", fake_get)
    monkeypatch.setattr(app_worker.shutil, "disk_usage",
                        lambda p: _usage(64 * 1024 * 1024 * 1024))
    monkeypatch.setattr(app_worker.tempfile, "gettempdir", lambda: str(tmp_path))

    def grant(request):
        assert request["taskId"] == 77 and request["artifactRef"] == 12
        return {"downloadUrl": "http://s3obj/presigned-get", "sha256": APK_SHA,
                "sizeBytes": len(APK_BYTES), "fileName": "demo-1.0.apk",
                "packageName": "com.demo.app", "version": "1.0"}

    task = _task(_install_steps(), grant)
    outcome = app_worker.run_app_task(task)

    assert outcome.result == "pass", outcome.error_detail
    step_seen = task["_adapterOverride"].executed[0]
    assert step_seen.get("localPath"), "install 步未注入 localPath"
    assert downloaded["url"] == "http://s3obj/presigned-get"
    assert str(tmp_path) in step_seen["localPath"]
    assert "install-r-1" in step_seen["localPath"]
    assert not os.path.exists(step_seen["localPath"]), "残留包未即清"


def test_failed_task_still_cleans_downloaded_pkg(monkeypatch, tmp_path):
    class FakeResp:
        def iter_content(self, chunk_size=8192):
            yield APK_BYTES

        def raise_for_status(self):
            pass

    monkeypatch.setattr(protocol.requests, "get", lambda url, **kw: FakeResp())
    monkeypatch.setattr(app_worker.shutil, "disk_usage",
                        lambda p: _usage(64 * 1024 * 1024 * 1024))
    monkeypatch.setattr(app_worker.tempfile, "gettempdir", lambda: str(tmp_path))

    class FailAdapter(RecordingAdapter):
        def execute(self, step):
            self.executed.append(dict(step))
            return {"result": "fail", "detail": "设备掉线模拟"}

    task = _task(_install_steps(), lambda req: {
        "downloadUrl": "http://x/y", "sha256": APK_SHA,
        "sizeBytes": len(APK_BYTES), "fileName": "demo.apk"})
    task["_adapterOverride"] = FailAdapter()
    outcome = app_worker.run_app_task(task)
    assert outcome.result == "fail"
    injected = task["_adapterOverride"].executed[0]["localPath"]
    assert not os.path.exists(injected), "失败路径残留包未即清"



def test_missing_channel_fails_closed_with_human_detail(tmp_path):
    task = _task(_install_steps(), None)
    outcome = app_worker.run_app_task(task)
    assert outcome.result == "fail"
    assert "下载通道" in outcome.error_detail and "未接线" in outcome.error_detail


def test_step_with_localPath_skips_download(tmp_path):
    pre = tmp_path / "already-here.apk"
    pre.write_bytes(APK_BYTES)
    steps = [{"action": "app.install", "pkg": "com.demo.app", "localPath": str(pre)}]

    def boom(_req):
        raise AssertionError("已带 localPath 不应再取通道")

    task = _task(steps, boom)
    outcome = app_worker.run_app_task(task)
    assert outcome.result == "pass", outcome.error_detail
    assert pre.exists()



def test_low_disk_watermark_fails_closed(monkeypatch, tmp_path):
    monkeypatch.setattr(app_worker.shutil, "disk_usage",
                        lambda p: _usage(1024 * 1024))
    calls = []
    monkeypatch.setattr(protocol.requests, "get", lambda url, **kw: calls.append(url))
    monkeypatch.setattr(app_worker.tempfile, "gettempdir", lambda: str(tmp_path))
    task = _task(_install_steps(), lambda req: {
        "downloadUrl": "http://x/y", "sha256": APK_SHA})
    monkeypatch.setenv("EXECUTOR_PKG_MIN_FREE_MB", "200")
    outcome = app_worker.run_app_task(task)
    assert outcome.result == "fail"
    assert "磁盘剩余不足" in outcome.error_detail and "200" in outcome.error_detail
    assert not calls



def test_sha_mismatch_fails_closed_and_no_half_pkg(monkeypatch, tmp_path):
    class FakeResp:
        def iter_content(self, chunk_size=8192):
            yield b"tampered-bytes"

        def raise_for_status(self):
            pass

    monkeypatch.setattr(protocol.requests, "get", lambda url, **kw: FakeResp())
    monkeypatch.setattr(app_worker.shutil, "disk_usage",
                        lambda p: _usage(64 * 1024 * 1024 * 1024))
    monkeypatch.setattr(app_worker.tempfile, "gettempdir", lambda: str(tmp_path))
    task = _task(_install_steps(), lambda req: {
        "downloadUrl": "http://x/y", "sha256": APK_SHA, "fileName": "demo.apk"})
    outcome = app_worker.run_app_task(task)
    assert outcome.result == "fail"
    assert "摘要" in outcome.error_detail
    leftovers = []
    for root, _dirs, files in os.walk(tmp_path):
        leftovers += [os.path.join(root, f) for f in files if f.endswith(".apk")]
    assert not leftovers, f"半包残留未清: {leftovers}"
