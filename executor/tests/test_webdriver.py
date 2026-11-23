
from __future__ import annotations

import json
import os
import tempfile

import pytest

from driver.webdriver import WebDriverExecutor, run_action_json

DEMO_URL = "file://" + os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "fixtures", "demo_page.html"))

LOGIN_SERVER = os.environ.get("F8_FIXTURES_URL", "http://localhost:8899/")


def _action_json(url: str) -> str:
    return json.dumps({
        "version": "v2",
        "caseId": "TC-DEMO-001",
        "title": "登录成功校验",
        "endType": "web",
        "setup": [
            {"action": "goto", "target": {"url": url}}
        ],
        "steps": [
            {"action": "input", "target": {"css": "#username"}, "value": "tester01"},
            {"action": "input", "target": {"css": "#password"}, "value": "Test@123"},
            {"action": "click", "target": {"css": "#login-btn"}},
            {"action": "expect", "target": {"text": "欢迎，tester01！登录成功"}}
        ]
    }, ensure_ascii=False)


class TestBasicActions:
    pass

    def test_登录全链路_pass_含步骤时间戳(self):
        with tempfile.TemporaryDirectory() as shots:
            outcome = run_action_json(_action_json(DEMO_URL), headless=True, screenshots_dir=shots)

            assert outcome.result == "pass", f"执行应通过: {outcome.error_detail}"
            assert len(outcome.steps_timeline) == 5, "setup 1 步 + steps 4 步"
            assert outcome.steps_timeline[0]["action"] == "goto"
            assert all(s["status"] == "ok" for s in outcome.steps_timeline)
            assert outcome.steps_timeline[0]["startAt"] <= outcome.steps_timeline[-1]["endAt"]
            assert outcome.duration_ms >= 0
            assert len(outcome.screenshots) >= 4, "每步一张截图（T22 产物）"

    def test_expect_文本定位_精确匹配(self):
        action = json.dumps({
            "version": "v2",
            "steps": [
                {"action": "goto", "target": {"url": DEMO_URL}},
                {"action": "expect", "target": {"text": "测试门户登录"}}
            ]
        }, ensure_ascii=False)

        outcome = run_action_json(action, headless=True)

        assert outcome.result == "pass", f"页面标题断言应通过: {outcome.error_detail}"

    def test_click_定位不存在_失败归定位错误(self):
        action = json.dumps({
            "version": "v2",
            "steps": [
                {"action": "goto", "target": {"url": DEMO_URL}},
                {"action": "click", "target": {"css": "#not-exist-btn"}}
            ]
        })

        outcome = run_action_json(action, headless=True)

        assert outcome.result == "fail"
        assert outcome.error_type == 2, "定位失败必须归为 2=定位错误（TC-EXEC-05 失败分类）"
        assert outcome.error_detail and "步骤 2" in outcome.error_detail
        assert outcome.steps_timeline[-1]["status"] == "fail"

    def test_expect_文本不存在_失败(self):
        action = json.dumps({
            "version": "v2",
            "steps": [
                {"action": "goto", "target": {"url": DEMO_URL}},
                {"action": "expect", "target": {"text": "不存在的文案 404"}}
            ]
        })

        outcome = run_action_json(action, headless=True)

        assert outcome.result == "fail"
        assert outcome.error_type == 2

    def test_非法动作JSON_格式错误(self):
        outcome = run_action_json("{not-json", headless=True)

        assert outcome.result == "fail"
        assert "解析失败" in (outcome.error_detail or "")

    def test_env占位符_替换为baseUrl(self):
        pass
        action = json.dumps({
            "version": "v2",
            "steps": [
                {"action": "goto", "target": {"url": "{{ENV:URL}}"}},
                {"action": "expect", "target": {"text": "测试门户登录"}}
            ]
        }, ensure_ascii=False)

        outcome = run_action_json(action, env_base_url=DEMO_URL, headless=True)

        assert outcome.result == "pass", f"占位符替换应成功: {outcome.error_detail}"

    def test_runner模式_共享executor实例run传env_base_url_占位符生效(self):
        pass
        action = json.dumps({
            "version": "v2",
            "setup": [{"action": "goto", "target": {"url": "{{ENV:URL}}/login"}}],
            "steps": [
                {"action": "input", "target": {"css": "#username"}, "value": "f8tester"},
                {"action": "click", "target": {"css": "#login-btn"}},
                {"action": "expect", "target": {"text": "登录成功显示欢迎信息"}}
            ]
        }, ensure_ascii=False)

        ex = WebDriverExecutor(headless=True)
        try:
            ex.start()
            outcome = ex.run(action, env_base_url=LOGIN_SERVER)
        finally:
            ex.stop()

        assert outcome.result == "pass", f"共享实例 per-run 传 env 应生效: {outcome.error_detail}"
        assert len(outcome.steps_timeline) == 4, f"步骤数异常: {outcome.steps_timeline}"
        assert all(s["status"] == "ok" for s in outcome.steps_timeline)
