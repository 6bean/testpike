
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, inject_token, nav_to, sql_query

pytestmark = pytest.mark.xdist_group("r1-env-health")


class _Target(BaseHTTPRequestHandler):
    status = 200

    def do_GET(self):
        self.send_response(self.status)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *args):
        pass


def _start_target():
    srv = HTTPServer(("127.0.0.1", 0), _Target)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, srv.server_address[1]


def _mk_project_envs(api, uniq):
    pass
    r = api.post("/projects", json={"name": f"R1E2E_{uniq}", "code": f"R1E{uniq.upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    srv, port = _start_target()
    r = api.post(f"/projects/{pid}/envs", json={
        "name": f"R1健康_{uniq}", "envType": 2, "baseUrl": f"http://127.0.0.1:{port}"})
    env_ok = r.json()["data"]
    r = api.post(f"/projects/{pid}/envs", json={
        "name": f"R1异常_{uniq}", "envType": 3, "baseUrl": "http://127.0.0.1:1/"})
    env_bad = r.json()["data"]
    return pid, env_ok, env_bad, srv, port


def _mk_case(api, pid, title):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "无", "expectedResult": "符合预期",
        "priority": 1, "confidence": 2, "endType": "web", "status": 3, "phase": 2,
        "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def _health_status(api, pid, env_id):
    r = api.get(f"/projects/{pid}/envs/{env_id}/health")
    assert r.status_code == 200 and r.json().get("code") == "0", f"GET health 失败: {r.text[:200]}"
    return r.json()["data"]


def _cleanup(api, pid, tester_id=None):
    pass
    api.delete(f"/projects/{pid}/purge?force=true")
    sql_query(f"DELETE FROM tp_notification WHERE event_type IN ('ENV_UNHEALTHY','ENV_RECOVERED') "
              f"AND project_id = {pid}")
    if tester_id:
        sql_query(f"DELETE FROM tp_notification WHERE user_id = {tester_id}")
        sql_query(f"DELETE FROM tp_user_role WHERE user_id = {tester_id}")
        sql_query(f"DELETE FROM tp_user_project WHERE user_id = {tester_id}")
        sql_query(f"DELETE FROM tp_user WHERE id = {tester_id}")


def test_r1_health_badges(page, collectors, api_client, uniq):
    pass
    api = api_client
    pid, env_ok, env_bad, srv, _port = _mk_project_envs(api, uniq)
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="env-table"]')).to_be_visible(timeout=15000)

        unknown_cell = page.locator(f'[data-test="env-health-{env_ok}"]')
        expect(unknown_cell.locator('[data-test="env-health-unknown"]')).to_be_visible(timeout=10000)

        row_ok = page.locator('[data-test="env-table"] .el-table__row').filter(has_text=f"R1健康_{uniq}")
        row_ok.locator('[data-test="env-health-probe"]').click()
        expect(row_ok.locator('[data-test="env-health-healthy"]')).to_be_visible(timeout=15000)
        expect(row_ok.locator('[data-test="env-health-healthy"]')).to_contain_text("健康")
        d = _health_status(api, pid, env_ok)
        assert d["status"] == "healthy" and d["checkedAt"], f"GET health 不一致: {d}"

        for _ in range(2):
            r = api.post(f"/projects/{pid}/envs/{env_bad}/health")
            assert r.status_code == 200 and r.json().get("code") == "0", f"POST health 失败: {r.text[:200]}"
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="env-table"]')).to_be_visible(timeout=15000)
        row_bad = page.locator('[data-test="env-table"] .el-table__row').filter(has_text=f"R1异常_{uniq}")
        badge_bad = row_bad.locator('[data-test="env-health-unhealthy"]')
        expect(badge_bad).to_be_visible(timeout=10000)
        expect(badge_bad).to_contain_text("异常")
        badge_bad.hover()
        tip = page.locator('.el-popper:has-text("不可达"):visible').first
        expect(tip).to_be_visible(timeout=5000)
        d = _health_status(api, pid, env_bad)
        assert d["status"] == "unhealthy" and "不可达" in d["msg"], f"异常态未持久化: {d}"

        assert_no_errors(collectors, "R1 健康徽标")
    finally:
        srv.shutdown()
        _cleanup(api, pid)


def test_r1_env_warning_on_generate_batches(page, collectors, api_client, uniq):
    pass
    api = api_client
    pid, _env_ok, env_bad, srv, _port = _mk_project_envs(api, uniq)
    plan_id = None
    try:
        for _ in range(2):
            r = api.post(f"/projects/{pid}/envs/{env_bad}/health")
            assert r.json().get("code") == "0", f"POST health 失败: {r.text[:200]}"
        from conftest import add_member, mk_tester, sql_query
        case_id = _mk_case(api, pid, f"R1用例_{uniq}")
        tester, _nick = mk_tester(api, uniq, prefix="r1t")
        add_member(api, pid, tester)
        sql_query(f"INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by) "
                  f"VALUES ({case_id}, 'v2', '{{\"caseId\": {case_id}, \"setup\": [], \"steps\": []}}', 1, 1) "
                  f"ON CONFLICT (case_id) DO NOTHING")
        r = api.post("/plans", json={
            "projectId": pid, "name": f"R1计划_{uniq}", "targetVersion": "v1.0.0",
            "envIds": [env_bad],
            "caseScope": {"phases": [2], "priorities": [0, 1], "keyword": None, "tagIds": []},
            "entryCriteria": {"requireSmokePass": False},
            "exitCriteria": {"minPassRate": 0.95, "noOpenDefects": False},
            "planStart": "2026-09-01", "planEnd": "2026-09-10"})
        body = r.json()
        assert r.status_code == 200 and body.get("code") == "0", f"建计划失败: {r.text[:300]}"
        plan_id = body["data"]["id"]
        r = api.post(f"/plans/{plan_id}/assign", json={"caseIds": [case_id], "assigneeId": tester})
        assert r.json().get("code") == "0", f"分配失败: {r.text[:200]}"
        r = api.post(f"/plans/{plan_id}/publish")
        assert r.json().get("code") == "0", f"发布失败: {r.text[:200]}"

        inject_token(page)
        # Header global context : deep-link ?projectId lands the plan list (n6 pattern).
        page.goto(f"{BASE_URL}/#/plans?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="plan-table"] .el-table__row').first).to_be_visible(timeout=10000)
        page.locator('[data-test="plan-name-link"]').first.click()
        expect(page.locator('[data-test="plan-detail-page"]')).to_be_visible(timeout=10000)

        page.locator('[data-test="plan-detail-genbatch"]').click()
        box = page.locator(".el-message-box").first
        expect(box).to_be_visible(timeout=5000)
        box.locator(".el-message-box__btns button:has-text('确定')").click()
        warn_box = page.locator(".el-message-box").filter(has_text="环境健康警告").first
        expect(warn_box).to_be_visible(timeout=5000)
        text = warn_box.locator(".el-message-box__message").inner_text()
        assert f"R1异常_{uniq}" in text and "仍要执行" in text, f"预检弹窗人话不完整: {text}"
        warn_box.locator(".el-message-box__btns button:has-text('仍要执行')").click()
        toast = page.locator(".el-message--warning").first
        expect(toast).to_contain_text("健康检查异常", timeout=10000)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="plan-detail-page"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="plan-case-table"] .el-table__row').first).to_be_visible(timeout=10000)
        gr = api.get(f"/plans/{plan_id}")
        assert gr.json().get("code") == "0", gr.text[:200]

        assert_no_errors(collectors, "R1 批次预检警告")
    finally:
        srv.shutdown()
        api.delete(f"/projects/{pid}/purge?force=true")
