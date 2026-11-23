
from __future__ import annotations

import json
import os
import tempfile

from driver.webdriver import run_action_json


def _shot_action(name: str | None) -> str:
    step = {"action": "screenshot", "desc": "命名截图"}
    if name is not None:
        step["name"] = name
    return json.dumps({
        "version": "v2",
        "caseId": "TC-T130-001",
        "title": "screenshot name 扩展名归一",
        "endType": "web",
        "setup": [],
        "steps": [
            {"action": "goto", "target": {"url": "about:blank"}},
            step,
        ],
    }, ensure_ascii=False)


class TestScreenshotNameExtension:
    pass

    def test_name_无扩展名_补png_步骤通过(self):
        with tempfile.TemporaryDirectory() as shots:
            outcome = run_action_json(_shot_action("setup-login-page"),
                                      headless=True, screenshots_dir=shots)
            assert outcome.result == "pass", \
                f"无扩展名 name 应补 .png 正常截图: {outcome.error_detail}"
            names = {os.path.basename(s) for s in outcome.screenshots}
            assert "setup-login-page.png" in names, \
                f"命名截图落盘名应补扩展名: {names}"
            assert os.path.exists(os.path.join(shots, "setup-login-page.png")), \
                "文件须真实落盘"

    def test_name_已带png_原样保留(self):
        with tempfile.TemporaryDirectory() as shots:
            outcome = run_action_json(_shot_action("done.png"),
                                      headless=True, screenshots_dir=shots)
            assert outcome.result == "pass", \
                f"带 .png 的 name 不应二次扩展: {outcome.error_detail}"
            names = {os.path.basename(s) for s in outcome.screenshots}
            assert "done.png" in names, f"已有扩展名应原样: {names}"
            assert "done.png.png" not in names, "禁二次追加扩展名"

    def test_name_缺省_落默认shot_png(self):
        with tempfile.TemporaryDirectory() as shots:
            outcome = run_action_json(_shot_action(None),
                                      headless=True, screenshots_dir=shots)
            assert outcome.result == "pass", \
                f"无 name 应走默认 shot.png: {outcome.error_detail}"
            names = {os.path.basename(s) for s in outcome.screenshots}
            assert "shot.png" in names, f"默认名应为 shot.png: {names}"
