
from __future__ import annotations

import json
import os
import pathlib

import app_worker
from driver.mobile.adb_verb_runner import NET_TIMELINE_FILENAME

_HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = _HERE.parent


def _use_tmp_root(monkeypatch, tmp_path):
    pass
    monkeypatch.setattr(app_worker.tempfile, "gettempdir", lambda: str(tmp_path))
    return tmp_path / "testpike-appauto"


class _Probe:
    pass

    def __init__(self, fail_at=99, write_timeline_at=None):
        self.fail_at = fail_at
        self.write_timeline_at = write_timeline_at
        self.n = 0
        self.seen_steps: list[dict] = []

    def prepare(self, ctx):
        pass

    def execute(self, step):
        self.n += 1
        self.seen_steps.append(dict(step))
        if (self.write_timeline_at is not None
                and self.n == self.write_timeline_at):
            d = step.get("artifactDir")
            assert d, "execute 派发点未注入 artifactDir（观测行落点断链）"
            os.makedirs(d, exist_ok=True)
            with open(os.path.join(d, NET_TIMELINE_FILENAME), "a",
                      encoding="utf-8") as f:
                f.write(json.dumps({"t": 1.0, "verb": "app.tap"}) + "\n")
        if self.n == self.fail_at:
            return {"result": "fail", "errorType": 1, "detail": "控件没找到"}
        return {"result": "pass"}

    def collect(self, artifact):
        return None

    def teardown(self):
        pass


def _run(probe, task_id):
    task = {"taskId": task_id, "_adapterOverride": probe,
            "actionJson": json.dumps({"kind": "app-auto-run", "runId": 1,
                                      "steps": [{"action": "app.tap"}] * 5})}
    return app_worker.run_app_task(task)



def test_execute_dispatch_injects_artifact_dir(monkeypatch, tmp_path):
    root = _use_tmp_root(monkeypatch, tmp_path)
    probe = _Probe()
    outcome = _run(probe, 931)
    assert outcome.result == "pass"
    expected = str(root / "931")
    assert all(s.get("artifactDir") == expected for s in probe.seen_steps), \
        "execute 收到的步必须带与任务 art_dir 同源的 artifactDir 键"



def test_failed_step_registers_net_timeline_artifact(monkeypatch, tmp_path):
    _use_tmp_root(monkeypatch, tmp_path)
    probe = _Probe(fail_at=3, write_timeline_at=2)
    outcome = _run(probe, 932)
    assert outcome.result == "fail"
    net = [x for x in outcome.pending_artifacts
           if x.get("kind") == "net_timeline"]
    assert len(net) == 1
    item = net[0]
    assert item["slot"] == "net_timeline"
    assert item["stepNo"] == 3
    assert item["fileName"] == NET_TIMELINE_FILENAME
    assert item["contentType"] == "text/plain"
    assert os.path.exists(item["localPath"])
    assert "localPath" not in json.dumps(outcome.steps_timeline,
                                         ensure_ascii=False)


def test_missing_timeline_file_honest_no_artifact(monkeypatch, tmp_path):
    pass
    _use_tmp_root(monkeypatch, tmp_path)
    probe = _Probe(fail_at=2)
    outcome = _run(probe, 933)
    assert outcome.result == "fail"
    assert not [x for x in outcome.pending_artifacts
                if x.get("kind") == "net_timeline"]


def test_pass_task_registers_no_net_timeline(monkeypatch, tmp_path):
    pass
    _use_tmp_root(monkeypatch, tmp_path)
    probe = _Probe(write_timeline_at=2)
    outcome = _run(probe, 934)
    assert outcome.result == "pass"
    assert not [x for x in outcome.pending_artifacts
                if x.get("kind") == "net_timeline"]



def test_attach_dedupes_per_task(tmp_path):
    art = tmp_path / "d"
    art.mkdir()
    (art / NET_TIMELINE_FILENAME).write_text('{"t":1}\n', encoding="utf-8")
    ctx: dict = {}
    out: list[dict] = []
    row = {"stepNo": 7, "result": "fail"}
    app_worker._attach_net_timeline(row, ctx, out, str(art))
    app_worker._attach_net_timeline(row, ctx, out, str(art))
    assert len(out) == 1, "多触达点共写一份文件=ctx 去重旗标只登记一件"
    assert ctx.get("_netTimelineAttached") is True


def test_attach_without_file_sets_no_flag(tmp_path):
    ctx: dict = {}
    out: list[dict] = []
    app_worker._attach_net_timeline({"stepNo": 1, "result": "fail"}, ctx, out,
                                    str(tmp_path / "nowhere"))
    assert out == []
    assert not ctx.get("_netTimelineAttached"), \
        "缺位不置旗标（后续触达点仍有机会判在）"



def test_evidence_slots_and_content_types_extended():
    assert app_worker._EVIDENCE_SLOTS["net_timeline"] == "net_timeline"
    assert app_worker._ARTIFACT_DEFAULT_CONTENT_TYPES["net_timeline"] == \
        "text/plain"
