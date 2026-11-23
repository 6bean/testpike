
from __future__ import annotations

import json
import os
import pathlib

import app_worker
from driver.mobile import perf_session
from driver.mobile.perf_session import PERF_SAMPLES_FILENAME

_HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = _HERE.parent

SHIM_SRC = '''#!/bin/sh
case "$*" in
  *"dumpsys gfxinfo"*) printf '%b\\n' "Total frames rendered: 100\\nJanky frames: 10 (10.00%)"\\n;;
  *"dumpsys meminfo"*) printf '%b\\n' "TOTAL PSS: 123456\\n";;
  *"dumpsys cpuinfo"*) printf '%b\\n' "12.3%TOTAL: 100 user";;
  *"dumpsys battery"*) printf '%b\\n' "temperature: 435";;
  *"pidof"*) exit 1;;
esac
exit 0
'''


def _install_shim(tmp_path, monkeypatch):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir(exist_ok=True)
    shim = bin_dir / "adb"
    shim.write_text(SHIM_SRC, encoding="utf-8")
    os.chmod(shim, 0o755)
    monkeypatch.setenv("PATH", str(bin_dir))


def _use_tmp_roots(tmp_path, monkeypatch):
    pass
    perf_root = tmp_path / "perf-root"
    monkeypatch.setattr(perf_session, "ART_DIR_BASE", str(perf_root))
    art_root = tmp_path / "tmp-root"
    monkeypatch.setattr(app_worker.tempfile, "gettempdir", lambda: str(art_root))
    return perf_root, art_root / "testpike-appauto"


class _FakeAdapter:
    SUPPORTED_PLATFORMS = ("ANDROID",)
    SUPPORTED_DEVICE_KINDS = ("EMULATOR",)
    CAPABILITIES = ()

    def __init__(self, fail_at=99):
        self.fail_at = fail_at
        self.n = 0
        self.teared = False

    def prepare(self, ctx):
        pass

    def execute(self, step):
        self.n += 1
        if self.n == self.fail_at:
            return {"result": "fail", "errorType": 1, "detail": "控件没找到"}
        return {"result": "pass"}

    def collect(self, step):
        return None

    def teardown(self):
        self.teared = True


def _drive(adapter, task_id):
    envelope = {"leaseRef": {"deviceId": 1, "serial": "em-1"},
                "envProfile": {"appId": "com.demo"}, "steps": []}
    return app_worker._drive(adapter, [
        {"action": "app.launch", "pkg": "com.demo"},
        {"action": "app.tap"},
    ], {"taskId": task_id, "leaseRef": envelope["leaseRef"]}, envelope)



def test_pass任务末步挂perf_timeline件(tmp_path, monkeypatch):
    _install_shim(tmp_path, monkeypatch)
    monkeypatch.setenv("EXECUTOR_PERF_SAMPLE_INTERVAL", "0.05")
    perf_root, art_root = _use_tmp_roots(tmp_path, monkeypatch)
    outcome = _drive(_FakeAdapter(), 941)
    assert outcome.result == "pass"
    perf = [x for x in outcome.pending_artifacts
            if x.get("kind") == "perf_timeline"]
    assert len(perf) == 1, "pass 任务同出一件（全程曲线，勿只抄失败口径）"
    item = perf[0]
    assert item["slot"] == "perf_timeline"
    assert item["contentType"] == "text/plain"
    assert item["fileName"] == PERF_SAMPLES_FILENAME
    last = outcome.steps_timeline[-1]
    assert item["stepNo"] == last["stepNo"], "挂载行=时间线末行"
    art_dir = str(art_root / "941")
    assert str(item["localPath"]).startswith(art_dir), \
        "件必指 art_dir 拷贝件（runner 删 perf_dir 早于上传=指源必静默假丢）"
    assert not str(item["localPath"]).startswith(str(perf_root))
    assert os.path.isfile(item["localPath"])
    assert "localPath" not in json.dumps(outcome.steps_timeline,
                                         ensure_ascii=False)



def test失败任务末步挂perf_timeline件(tmp_path, monkeypatch):
    _install_shim(tmp_path, monkeypatch)
    monkeypatch.setenv("EXECUTOR_PERF_SAMPLE_INTERVAL", "0.05")
    _use_tmp_roots(tmp_path, monkeypatch)
    outcome = _drive(_FakeAdapter(fail_at=2), 942)
    assert outcome.result == "fail"
    perf = [x for x in outcome.pending_artifacts
            if x.get("kind") == "perf_timeline"]
    assert len(perf) == 1
    assert perf[0]["stepNo"] == 2
    assert str(perf[0]["localPath"]).startswith(str(_tmp_art(942, tmp_path)))


def _tmp_art(task_id, tmp_path):
    return tmp_path / "tmp-root" / "testpike-appauto" / str(task_id)



def test样行缺位_诚实零第六槽件(tmp_path, monkeypatch):
    monkeypatch.setenv("PATH", str(tmp_path / "empty-bin"))
    _use_tmp_roots(tmp_path, monkeypatch)
    outcome = _drive(_FakeAdapter(), 943)
    assert outcome.result == "pass"
    assert not [x for x in outcome.pending_artifacts
                if x.get("kind") == "perf_timeline"]



def test_attach_二次调用只一件(tmp_path):
    perf = tmp_path / "perf"
    perf.mkdir()
    (perf / PERF_SAMPLES_FILENAME).write_text('{"ts":1}\n', encoding="utf-8")
    art = tmp_path / "art"
    ctx: dict = {}
    out: list[dict] = []
    row = {"stepNo": 5, "result": "fail"}
    app_worker._attach_perf_timeline(row, ctx, out, str(perf), str(art))
    app_worker._attach_perf_timeline(row, ctx, out, str(perf), str(art))
    assert len(out) == 1, "一任务一份=ctx 去重旗标只登记一件"
    assert ctx.get("_perfTimelineAttached") is True
    item = out[0]
    assert item["slot"] == "perf_timeline"
    assert item["contentType"] == "text/plain"
    assert item["fileName"] == PERF_SAMPLES_FILENAME
    assert os.path.isfile(item["localPath"])
    assert str(item["localPath"]).startswith(str(art))


def test_attach_文件缺位_诚实零件_不置旗标(tmp_path):
    ctx: dict = {}
    out: list[dict] = []
    app_worker._attach_perf_timeline(
        {"stepNo": 3, "result": "fail"}, ctx, out,
        str(tmp_path / "nope"), str(tmp_path / "art"))
    assert out == []
    assert "_perfTimelineAttached" not in ctx, "缺位不置旗标（留后续触达点机会）"



def test_两表第六键perf_timeline登记():
    assert app_worker._EVIDENCE_SLOTS["perf_timeline"] == "perf_timeline"
    assert app_worker._ARTIFACT_DEFAULT_CONTENT_TYPES["perf_timeline"] \
        == "text/plain"
