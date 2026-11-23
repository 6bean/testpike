
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
import pytest
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, nav_to


CORE_BASE = "http://localhost:8083"
KEY_ENABLED = "testpike.notify.webhook-enabled"
KEY_URL = "testpike.notify.webhook-url"
GROUP = "通知"

pytestmark = pytest.mark.xdist_group("n5-webhook")


class _HookHandler(BaseHTTPRequestHandler):
    pass
    received = []
    count = 0

    def do_POST(self):
        body = self._read_body()
        type(self).received.append(body)
        type(self).count += 1
        self.send_response(200)
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"ok")

    def _read_body(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            body = b""
            while True:
                line = self.rfile.readline().strip()
                if not line:
                    break
                size = int(line, 16)
                if size == 0:
                    self.rfile.readline()
                    break
                body += self.rfile.read(size)
                self.rfile.readline()
            return body
        length = int(self.headers.get("Content-Length", 0))
        return self.rfile.read(length)

    def log_message(self, *args):
        pass


@pytest.fixture(scope="module")
def hook_server():
    _HookHandler.received = []
    _HookHandler.count = 0
    srv = ThreadingHTTPServer(("127.0.0.1", 0), _HookHandler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_address[1]}/hook"
    srv.shutdown()


def _mk_config(api, key, value, vtype):
    r = api.post("/configs", json={
        "configKey": key, "configValue": value, "groupName": GROUP,
        "valueType": vtype, "encryptFlag": 0, "description": "E2E N5 webhook 渠道配置",
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建配置失败: {r.text[:300]}"
    return r.json()["data"]


SEED_ROWS = ((KEY_ENABLED, "false", "bool", "webhook 渠道开关"),
             (KEY_URL, "", "string", "webhook 渠道回调地址（空=未配置）"))


def _clear_webhook_keys(api):
    pass
    for x in api.get("/configs").json().get("data", []):
        if x.get("configKey") in (KEY_ENABLED, KEY_URL):
            api.delete(f"/configs/{x['id']}")


def _restore_seed(api):
    pass
    existing = {x.get("configKey") for x in api.get("/configs").json().get("data", [])}
    for key, value, vtype, desc in SEED_ROWS:
        if key not in existing:
            api.post("/configs", json={
                "configKey": key, "configValue": value, "groupName": GROUP,
                "valueType": vtype, "encryptFlag": 0, "description": desc})
        else:
            row = next(x for x in api.get("/configs").json().get("data", [])
                       if x.get("configKey") == key)
            api.put(f"/configs/{row['id']}", json={
                "configKey": key, "configValue": value, "groupName": GROUP,
                "valueType": row["valueType"], "encryptFlag": row["encryptFlag"],
                "description": row["description"]})


def _admin_user_id(api):
    pass
    r = api.get("/users/me")
    assert r.status_code == 200 and r.json().get("code") == "0", f"users/me 失败: {r.text[:200]}"
    return r.json()["data"]["user"]["userId"]


def _trigger_notification(tag, user_id):
    pass
    r = httpx.post(f"{CORE_BASE}/internal/notifications", json={
        "userId": user_id, "projectId": None, "eventType": "E2E_N5_HOOK",
        "title": "webhook E2E 通知", "content": f"渠道双通道验证 {tag}",
        "dedupKey": f"e2e-n5-{tag}",
    }, timeout=10)
    assert r.status_code == 200 and r.json().get("code") == "0", f"触发通知失败: {r.text[:300]}"


def test_n5_webhook_channel(logged_in, collectors, api_client, uniq, hook_server):
    pass
    page = logged_in
    tag = uniq
    _clear_webhook_keys(api_client)

    try:
        nav_to(page, "系统配置", "/configs", '[data-test="config-page"]', submenu="系统管理")
        expect(page.locator('[data-test="config-table"]').first).to_be_visible(timeout=10000)

        for key, value, vtype in ((KEY_ENABLED, "true", "bool"), (KEY_URL, hook_server, "string")):
            page.locator('[data-test="config-create"]').click()
            expect(page.locator('[data-test="config-dialog"]')).to_be_visible()
            page.locator('[data-test="config-key-input"]').fill(key)
            page.locator('[data-test="config-value-input"]').fill(value)
            page.locator('[data-test="config-submit"]').click()
            expect(page.locator(f'code:text-is("{key}")').first).to_be_visible(timeout=10000)

        page.goto(f"{BASE_URL}/#/configs", wait_until="domcontentloaded")
        expect(page.locator('[data-test="config-page"]')).to_be_visible()
        expect(page.locator(f'code:text-is("{KEY_ENABLED}")').first).to_be_visible(timeout=10000)
        expect(page.locator(f'code:text-is("{KEY_URL}")').first).to_be_visible(timeout=10000)

        _HookHandler.count = 0
        _HookHandler.received = []
        _trigger_notification(tag, _admin_user_id(api_client))
        deadline = 8000
        while _HookHandler.count < 1 and deadline > 0:
            page.wait_for_timeout(200)
            deadline -= 200
        assert _HookHandler.count == 1, "webhook 应收到 1 次 POST"
        payload = json.loads(_HookHandler.received[0].decode("utf-8"))
        assert payload.get("eventType") == "E2E_N5_HOOK", f"eventType: {payload}"
        assert payload.get("title") == "webhook E2E 通知", f"title: {payload}"
        assert tag in payload.get("content", ""), f"content: {payload}"
        r = api_client.get("/notifications")
        assert r.status_code == 200
        titles = [n.get("title") for n in r.json().get("data", {}).get("list", [])]
        assert "webhook E2E 通知" in titles, f"站内信列表应含该通知, got {titles[:5]}"

        row = page.locator(f'tr:has(code:text-is("{KEY_ENABLED}"))').first
        row.locator('[data-test="config-edit"]').click()
        expect(page.locator('[data-test="config-dialog"]')).to_be_visible()
        page.locator('[data-test="config-value-input"]').fill("false")
        page.locator('[data-test="config-submit"]').click()
        expect(page.locator(f'code:text-is("{KEY_ENABLED}")').first).to_be_visible(timeout=10000)

        _HookHandler.count = 0
        _trigger_notification(f"{tag}-off", _admin_user_id(api_client))
        page.wait_for_timeout(1500)
        assert _HookHandler.count == 0, "关闭渠道后 webhook 不应再收到"
        r = api_client.get("/notifications")
        titles = [n.get("title") for n in r.json().get("data", {}).get("list", [])]
        assert "webhook E2E 通知" in titles, "站内信主链路不受渠道关闭影响"

        assert_no_errors(collectors, "N5 webhook 渠道配置")
    finally:
        _restore_seed(api_client)


def test_n5_webhook_fail_retry(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    tag = uniq
    _clear_webhook_keys(api_client)
    _mk_config(api_client, KEY_ENABLED, "true", "bool")
    _mk_config(api_client, KEY_URL, "http://127.0.0.1:9/hook", "string")

    try:
        _trigger_notification(f"{tag}-fail", _admin_user_id(api_client))
        r = api_client.get("/notifications")
        assert r.status_code == 200
        titles = [n.get("title") for n in r.json().get("data", {}).get("list", [])]
        assert "webhook E2E 通知" in titles, "webhook 失败不应影响站内信（IT-N5-01 降级）"
        assert_no_errors(collectors, "N5 webhook 失败降级")
    finally:
        _restore_seed(api_client)
