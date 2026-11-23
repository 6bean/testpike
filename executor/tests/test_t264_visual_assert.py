
from __future__ import annotations

import http.server
import io
import json
import os
import tempfile
import threading

import pytest
from PIL import Image

from driver.visual_assert_contract import validate_visual_assert_step
from driver.visual_diff import diff_ratio, overlay_diff_png
from driver.webdriver import run_action_json

DEMO_URL = "file://" + os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "fixtures", "demo_page.html"))

_CASE_ID = 42


def _doc(setup_steps: list[dict], main_steps: list[dict]) -> str:
    return json.dumps({"version": "v2", "setup": setup_steps, "steps": main_steps},
                      ensure_ascii=False)


def _goto() -> dict:
    return {"action": "goto", "target": {"url": DEMO_URL}}


def _visual(**kw) -> dict:
    step = {"action": "visual.assert"}
    step.update(kw)
    return step


def _png_bytes(color: tuple[int, int, int], size=(40, 30)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", size, color).save(buf, format="PNG")
    return buf.getvalue()


class _BaselineHandler(http.server.BaseHTTPRequestHandler):
    pass

    payload: dict[str, bytes] = {}

    def do_GET(self):  # noqa: N802
        body = type(self).payload.get(self.path)
        if body is None:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "image/png")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


@pytest.fixture()
def baseline_server():
    pass
    srv = http.server.HTTPServer(("127.0.0.1", 0), _BaselineHandler)
    th = threading.Thread(target=srv.serve_forever, daemon=True)
    th.start()
    base = f"http://127.0.0.1:{srv.server_address[1]}"
    yield base, _BaselineHandler
    srv.shutdown()


def _run(doc: str, visual_baselines=None, shots_dir=None):
    if shots_dir is not None:
        return run_action_json(doc, headless=True, screenshots_dir=shots_dir,
                               visual_baselines=visual_baselines,
                               case_id=_CASE_ID)
    with tempfile.TemporaryDirectory() as shots:
        return run_action_json(doc, headless=True, screenshots_dir=shots,
                               visual_baselines=visual_baselines,
                               case_id=_CASE_ID)


def _row(outcome, step_no: int) -> dict:
    for row in outcome.steps_timeline:
        if row["stepNo"] == step_no:
            return row
    raise AssertionError(f"时间线无步骤 {step_no}: {outcome.steps_timeline}")


class TestStepKeyDualSideContract:
    pass

    def test_setup与steps线性序号与name缺省shot(self):
        outcome = _run(_doc([_goto(), _visual(name="home")], [_visual()]),
                       visual_baselines={})
        assert outcome.result == "pass", outcome.error_detail
        assert _row(outcome, 2)["stepKey"] == "case:42:step:2:home"
        assert _row(outcome, 3)["stepKey"] == "case:42:step:3:shot"
        assert _row(outcome, 2)["status"] == "baseline_pending"

    def test_if子步不编入_容器内visual人话拒(self):
        doc = _doc([], [_goto(), {"action": "if", "condition": "true",
                                  "steps": [_visual()]}])
        outcome = _run(doc, visual_baselines={})
        assert outcome.result == "fail"
        assert "顶层" in outcome.error_detail or "容器" in outcome.error_detail, \
            outcome.error_detail


class TestVisualThreeOutcomes:
    pass

    def test_无已确认基线_baseline_pending采首图(self, tmp_path):
        doc = _doc([], [_goto(), _visual()])
        outcome = _run(doc, visual_baselines=None, shots_dir=str(tmp_path))
        row = _row(outcome, 2)
        assert row["status"] == "baseline_pending", row
        assert row["stepKey"] == "case:42:step:2:shot"
        assert outcome.result == "pass"
        assert outcome.pending_artifacts, "首图必须随 result 回传供确认落库"
        art = outcome.pending_artifacts[0]
        assert art["kind"] == "visual-baseline-candidate"
        assert os.path.exists(art["localPath"])

    def test_尺寸不一致_宽高点名fail(self, baseline_server):
        base, handler = baseline_server
        handler.payload = {"/b": _png_bytes((255, 0, 0), size=(10, 8))}
        doc = _doc([], [_goto(), _visual()])
        outcome = _run(doc, visual_baselines={"case:42:step:2:shot": {
            "baselineKey": "bk-1", "baselineUrl": base + "/b"}})
        row = _row(outcome, 2)
        assert row["status"] == "fail", row
        assert "10" in row["detail"] and "8" in row["detail"], row["detail"]

    def test_容差内_通过(self, baseline_server, tmp_path):
        base, handler = baseline_server
        doc = _doc([], [_goto(), _visual()])
        first = _run(doc, visual_baselines=None, shots_dir=str(tmp_path))
        art = first.pending_artifacts[0]
        with open(art["localPath"], "rb") as f:
            handler.payload = {"/b": f.read()}
        outcome = _run(doc, visual_baselines={"case:42:step:2:shot": {
            "baselineKey": "bk-1", "baselineUrl": base + "/b"}})
        assert outcome.result == "pass", outcome.error_detail
        assert _row(outcome, 2)["status"] == "ok", _row(outcome, 2)

    def test_超阈值_fail带对比图回传(self, baseline_server, tmp_path):
        base, handler = baseline_server
        doc = _doc([], [_goto(), _visual(tolerance=0.001)])
        first = _run(doc, visual_baselines=None, shots_dir=str(tmp_path))
        cand = Image.open(first.pending_artifacts[0]["localPath"]).convert("RGB")
        red = Image.new("RGB", cand.size, (255, 0, 0))
        buf = io.BytesIO()
        red.save(buf, format="PNG")
        handler.payload = {"/b": buf.getvalue()}
        outcome = _run(doc, visual_baselines={"case:42:step:2:shot": {
            "baselineKey": "bk-1", "baselineUrl": base + "/b"}})
        row = _row(outcome, 2)
        assert row["status"] == "fail", row
        assert "差异" in row["detail"], row["detail"]
        assert any(a["kind"] == "visual-diff" for a in outcome.pending_artifacts), \
            outcome.pending_artifacts


class TestFailClosed:
    pass

    def test_显式baselineKey错配_不下发_hit人话fail(self, baseline_server):
        base, handler = baseline_server
        handler.payload = {"/b": _png_bytes((255, 0, 0))}
        doc = _doc([], [_goto(), _visual(baselineKey="wrong-key")])
        outcome = _run(doc, visual_baselines={})
        row = _row(outcome, 2)
        assert row["status"] == "fail", row
        assert "baselineKey" in row["detail"] or "基线" in row["detail"], row["detail"]
        assert not outcome.pending_artifacts, "显式错配禁采首图"

    def test_基线下载失败_人话fail归环境(self, baseline_server):
        base, _ = baseline_server
        doc = _doc([], [_goto(), _visual()])
        outcome = _run(doc, visual_baselines={"case:42:step:2:shot": {
            "baselineKey": "bk-1", "baselineUrl": base + "/missing"}})
        assert _row(outcome, 2)["status"] == "fail"


class TestContractMirror:
    pass

    def test_合法值_不抛(self):
        validate_visual_assert_step({"tolerance": 0.02,
                                     "region": {"x": 0, "y": 0, "w": 10, "h": 10},
                                     "baselineKey": "bk"})
        validate_visual_assert_step({})

    def test_tolerance越界_点名(self):
        with pytest.raises(ValueError, match="tolerance"):
            validate_visual_assert_step({"tolerance": 1.5})
        with pytest.raises(ValueError, match="tolerance"):
            validate_visual_assert_step({"tolerance": "0.1"})

    def test_region形状违例_点名(self):
        with pytest.raises(ValueError, match="region"):
            validate_visual_assert_step({"region": {"x": 0, "y": 0, "w": 0, "h": 5}})
        with pytest.raises(ValueError, match="region"):
            validate_visual_assert_step({"region": {"x": -1, "y": 0, "w": 5, "h": 5}})

    def test_步内坏值_分派支执行前拒(self):
        doc = _doc([], [_goto(), _visual(tolerance=2)])
        outcome = _run(doc, visual_baselines={})
        row = _row(outcome, 2)
        assert row["status"] == "fail", row
        assert "tolerance" in row["detail"], row["detail"]


class TestDiffAlgo:
    pass

    def test_全等_零差异(self):
        a = _png_bytes((10, 20, 30))
        assert diff_ratio(a, a) == 0.0

    def test_单像素差异_占比正确(self):
        a = Image.new("RGB", (10, 10), (0, 0, 0))
        b = a.copy()
        b.putpixel((3, 3), (255, 255, 255))
        buf_a, buf_b = io.BytesIO(), io.BytesIO()
        a.save(buf_a, format="PNG")
        b.save(buf_b, format="PNG")
        assert abs(diff_ratio(buf_a.getvalue(), buf_b.getvalue()) - 0.01) < 1e-9

    def test_region裁剪_区域外差异不计(self):
        a = Image.new("RGB", (10, 10), (0, 0, 0))
        b = a.copy()
        b.putpixel((9, 9), (255, 0, 0))
        buf_a, buf_b = io.BytesIO(), io.BytesIO()
        a.save(buf_a, format="PNG")
        b.save(buf_b, format="PNG")
        region = {"x": 0, "y": 0, "w": 5, "h": 5}
        assert diff_ratio(buf_a.getvalue(), buf_b.getvalue(), region) == 0.0
        assert diff_ratio(buf_a.getvalue(), buf_b.getvalue()) > 0.0

    def test_overlay_diff_产出合法png(self):
        a = _png_bytes((0, 0, 0), size=(8, 8))
        b = _png_bytes((255, 255, 255), size=(8, 8))
        out = overlay_diff_png(a, b)
        img = Image.open(io.BytesIO(out))
        assert img.size == (8, 8)

    def test_尺寸不一致_点名宽高(self):
        a = _png_bytes((0, 0, 0), size=(8, 8))
        b = _png_bytes((0, 0, 0), size=(9, 8))
        with pytest.raises(ValueError, match="8"):
            diff_ratio(a, b)
