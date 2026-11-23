
import http.server
import json
import os
import socket
import ssl
import subprocess
import tempfile
import threading
import time

import pytest
from conftest import (
    assert_no_errors,
    inject_token,
    nav_to,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(600)

FIXTURES_URL = "http://localhost:8899/"
P12 = os.path.normpath(os.path.join(
    os.path.dirname(__file__), "..", "..", "..", "core", "src", "test",
    "resources", "tls", "selfsigned.p12"))


def _free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


@pytest.fixture(scope="module")
def https_fixture():
    pass
    d = tempfile.mkdtemp(prefix="e2e-w1a-hint-tls-")
    key_p = os.path.join(d, "key.pem")
    crt_p = os.path.join(d, "cert.pem")
    subprocess.run(["openssl", "pkcs12", "-in", P12, "-passin", "pass:changeit",
                    "-nocerts", "-nodes", "-out", key_p], check=True, capture_output=True)
    subprocess.run(["openssl", "pkcs12", "-in", P12, "-passin", "pass:changeit",
                    "-clcerts", "-nokeys", "-out", crt_p], check=True, capture_output=True)

    port = _free_port()
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", port), _OkHandler)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(crt_p, key_p)
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"https://127.0.0.1:{port}/"
    httpd.shutdown()


class _OkHandler(http.server.BaseHTTPRequestHandler):
    pass

    def do_GET(self):
        body = b"ok"
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass


def _mk_case(api, pid, title, action_json):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "无", "expectedResult": "OK",
        "priority": 1, "confidence": 2, "endType": "web", "status": 3, "phase": 2,
        "steps": [{"stepNo": 1, "description": "黄标链", "isAssert": 1}]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    case_id = r.json()["data"]["id"]
    esc = action_json.replace("\\", "\\\\").replace("'", "''")
    sql_query("INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by) "
              f"VALUES ({case_id}, 'v2', '{esc}', 1, 4) "
              "ON CONFLICT (case_id) DO UPDATE SET action_json = EXCLUDED.action_json")
    return case_id


def _trial_run_and_wait(api, pid, case_id, timeout=300):
    envs = api.get(f"/projects/{pid}/envs").json()["data"]
    assert envs, "项目无环境"
    r = api.post(f"/cases/{case_id}/trial-run", json={"envId": envs[0]["id"]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"试运行失败: {r.text[:300]}"
    tid = r.json()["data"]["taskId"]
    deadline = time.time() + timeout
    while time.time() < deadline:
        t = api.get(f"/executors/tasks/{tid}").json()["data"]
        if t["status"] >= 3:
            return t
        time.sleep(2)
    raise AssertionError(f"任务 {tid} {timeout}s 未到终态（活体执行器未消费？）")


def _select_project(page, proj_name):
    pass
    nav_to(page, "执行与试运行", "/execution", '[data-test="execution-root"]', submenu="执行测试")
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=proj_name).first
    expect(option).to_be_visible(timeout=5000)
    option.click()
    table = page.locator('[data-test="exec-task-table"]')
    expect(table).to_be_visible(timeout=15000)
    return table


def _open_task_detail(page, proj_name, tid):
    inject_token(page)
    table = _select_project(page, proj_name)
    row = table.locator("tbody tr").filter(has_text=f"#{tid}").first
    expect(row).to_contain_text("失败", timeout=30000)
    row.locator('[data-test="exec-task-detail"]').click()
    dlg = page.locator('[data-test="exec-detail-dialog"]')
    expect(dlg).to_be_visible(timeout=10000)
    return dlg


def test_w1a_snapshot_insecure_hint(logged_in, api_client, collectors, uniq, https_fixture):
    pass
    page = logged_in
    pid = None
    try:
        pid = api_client.post("/projects", json={
            "name": f"W1a黄标{uniq}", "code": f"W1AH{uniq.upper()}"}).json()["data"]
        data = pid
        if isinstance(data, dict):
            pid = data["id"]
        r = api_client.post(f"/projects/{pid}/envs", json={
            "name": f"w1a-insecure-{uniq}", "envType": 2, "baseUrl": https_fixture,
            "configJson": json.dumps({"tls": {"mode": "insecure", "reason": "E2E 自签夹具"}},
                                     ensure_ascii=False),
            "endTypes": "web", "browsers": "chromium"})
        assert str(r.json().get("code")) == "0", f"建 insecure 环境失败: {r.text[:300]}"
        hint_doc = json.dumps({"version": "v2", "caseId": "TC-W1A-HINT", "title": "黄标",
                               "endType": "web", "steps": [
            {"action": "http.request", "method": "GET", "url": https_fixture,
             "expect": {"status": 200}},
            {"action": "http.request", "method": "GET", "url": f"{https_fixture}nope",
             "expect": {"status": 418}},
        ]}, ensure_ascii=False)
        case_hint = _mk_case(api_client, pid, f"W1a黄标用例{uniq}", hint_doc)
        task = _trial_run_and_wait(api_client, pid, case_hint)
        assert task["status"] == 4, f"期望不符步应收口失败: {task['status']}"

        dlg = _open_task_detail(page, f"W1a黄标{uniq}", task["id"])
        snap = dlg.locator('[data-test="exec-snapshots"]')
        expect(snap).to_be_visible(timeout=10000)
        hint = dlg.locator('[data-test="snapshot-insecure-hint"]')
        expect(hint).to_be_visible(timeout=10000)
        expect(hint).to_contain_text("跳过证书校验")

        page.keyboard.press("Escape")
        expect(dlg).to_be_hidden(timeout=10000)

        strict_doc = json.dumps({"version": "v2", "caseId": "TC-W1A-STRICT", "title": "严格对照",
                                 "endType": "web", "steps": [
            {"action": "http.request", "method": "GET", "url": f"{FIXTURES_URL}demo_page.html",
             "expect": {"status": 200}},
            {"action": "http.request", "method": "GET", "url": f"{FIXTURES_URL}api/order/X",
             "expect": {"status": 418}},
        ]}, ensure_ascii=False)
        case_strict = _mk_case(api_client, pid, f"W1a严格对照{uniq}", strict_doc)
        r = api_client.post(f"/projects/{pid}/envs", json={
            "name": f"w1a-strict-{uniq}", "envType": 1, "baseUrl": FIXTURES_URL,
            "endTypes": "web", "browsers": "chromium"})
        assert str(r.json().get("code")) == "0", f"建 strict 环境失败: {r.text[:300]}"
        envs = api_client.get(f"/projects/{pid}/envs").json()["data"]
        strict_env = next(e for e in envs if "strict" in e["name"])
        r = api_client.post(f"/cases/{case_strict}/trial-run", json={"envId": strict_env["id"]})
        assert r.json().get("code") == "0", f"strict 试运行失败: {r.text[:300]}"
        tid2 = r.json()["data"]["taskId"]
        deadline = time.time() + 300
        while time.time() < deadline:
            t2 = api_client.get(f"/executors/tasks/{tid2}").json()["data"]
            if t2["status"] >= 3:
                break
            time.sleep(2)
        assert t2["status"] == 4, f"strict 对照应收口失败: {t2['status']}"

        dlg2 = _open_task_detail(page, f"W1a黄标{uniq}", tid2)
        expect(dlg2).to_be_visible(timeout=10000)
        expect(dlg2.locator('[data-test="exec-snapshots"]')).to_be_visible(timeout=10000)
        expect(dlg2.locator('[data-test="snapshot-insecure-hint"]')).to_have_count(0)

        assert_no_errors(collectors, "W1a 报告黄标链路")
    finally:
        if pid:
            try:
                api_client.delete(f"/projects/{pid}/purge?force=true")
            except Exception:  # noqa: BLE001,S110
                pass
