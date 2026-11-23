
import json
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from conftest import assert_no_errors, nav_to, row_cmd, sql_query
from playwright.sync_api import expect


_SEEN: list = []


class _FakeHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n).decode("utf-8", "replace")
        _SEEN.append((self.path, body))
        payload = json.dumps({"result": "pong", "echo": body}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def _start_fake():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), _FakeHandler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, srv.server_address[1]


def _closed_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def _create_exec_tool(api_client, name, url, timeout_ms=10000):
    r = api_client.post("/tools", {
        "projectId": None, "name": name, "type": 2, "category": "e2e",
        "execKind": 1, "execSpecJson": json.dumps({"url": url}),
        "timeoutMs": timeout_ms, "submitNow": True,
    })
    body = r.json()
    assert body["code"] == "0", f"创建可执行工具失败: {body}"
    tid, ver = body["data"]["id"], body["data"]["version"]
    ra = api_client.post(f"/tools/{tid}/approve?expectedVersion={ver}").json()
    assert ra["code"] == "0", f"approve 失败: {ra}"
    return tid


def _cleanup_tool(api_client, tid):
    cur = api_client.get(f"/tools/{tid}").json()["data"]
    if cur["status"] == 3:
        rr = api_client.post(f"/tools/{tid}/retire?expectedVersion={cur['version']}").json()
        assert rr["code"] == "0", f"retire 失败: {rr}"
        cur = rr["data"]
    dr = api_client.delete(f"/tools/{tid}?expectedVersion={cur['version']}").json()
    assert dr["code"] == "0", f"delete 失败: {dr}"


def _search_row(page, name):
    page.fill('[data-test="tool-keyword"]', name)
    page.click('[data-test="tool-search"]')
    return page.locator('[data-test="tool-table"] tbody tr').filter(has_text=name).first


def _open_test_run(page, row):
    row_cmd(page, row, "testRun")
    expect(page.locator('[data-test="tool-run-dialog"]')).to_be_visible(timeout=5000)


def test_tool_test_run_roundtrip(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    name = f"t167{uniq}"
    doc_name = f"t167doc{uniq}"
    fake, port = _start_fake()
    tid = _create_exec_tool(api_client, name, f"http://127.0.0.1:{port}/ok")
    rd = api_client.post("/tools", {
        "projectId": None, "name": doc_name, "type": 1,
        "body": "# t167 e2e doc", "submitNow": False,
    })
    doc_body = rd.json()
    assert doc_body["code"] == "0", f"建 DOC 夹具失败: {doc_body}"
    doc_tid = doc_body["data"]["id"]

    try:
        nav_to(page, "工具管理", "/tools", '[data-test="tool-list-page"]', submenu="AI 能力")

        _search_row(page, doc_name)
        expect(_search_row(page, doc_name)).to_be_visible(timeout=10000)
        doc_row = _search_row(page, doc_name)
        more = doc_row.locator('[data-test="ra-more"]')
        if more.count() > 0:
            more.click()
            expect(page.locator('[data-test="ra-remove"]:visible').first).to_be_visible(timeout=5000)
            expect(page.locator('[data-test="ra-testRun"]')).to_have_count(0)
            page.keyboard.press("Escape")

        _search_row(page, name)
        row = _search_row(page, name)
        _open_test_run(page, row)
        page.locator('[data-test="tool-run-input"]').fill('{"q":1}')
        with page.expect_response(
                lambda r: f"/tools/{tid}/test-run" in r.url and r.status == 200,
                timeout=30000) as resp_info:
            page.click('[data-test="tool-run-exec"]')
        api_body = (resp_info.value.json() or {}).get("data") or {}
        assert api_body.get("success") is True, f"成功链路接口体异常: {api_body}"
        result = page.locator('[data-test="tool-run-result"]')
        expect(result).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="tool-run-success-tag"]')).to_contain_text("执行成功")
        expect(page.locator('[data-test="tool-run-output"]')).to_contain_text("pong")
        assert any('"q":1' in b for _, b in _SEEN), f"假目标未收到入参: {_SEEN}"

        cnt = sql_query(
            f"SELECT count(*) FROM tp_tool_invocation "
            f"WHERE tool_id={tid} AND caller='user' AND success=1")
        assert cnt.strip() == "1", f"留痕应恰 1 条 caller=user，实查={cnt}"

        page.reload(wait_until="domcontentloaded")
        nav_to(page, "工具管理", "/tools", '[data-test="tool-list-page"]', submenu="AI 能力")
        _search_row(page, name)
        row_cmd(page, _search_row(page, name), "invocations")
        expect(page.locator('[data-test="tool-inv-dialog"]')).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="tool-inv-table"]')).to_contain_text("用户", timeout=10000)
        page.click('[data-test="tool-inv-dialog"] .el-dialog__headerbtn')

        bad_url_tid = _create_exec_tool(
            api_client, f"{name}-bad", f"http://127.0.0.1:{_closed_port()}/x",
            timeout_ms=5000)
        try:
            _search_row(page, f"{name}-bad")
            _open_test_run(page, _search_row(page, f"{name}-bad"))
            with page.expect_response(
                    lambda r: f"/tools/{bad_url_tid}/test-run" in r.url and r.status == 200,
                    timeout=30000) as resp_info2:
                page.click('[data-test="tool-run-exec"]')
            body2 = (resp_info2.value.json() or {}).get("data") or {}
            assert body2.get("success") is False and body2.get("errorCategory") == "network", \
                f"失败链路应 200 带 network 分类: {body2}"
            expect(page.locator('[data-test="tool-run-success-tag"]')).to_contain_text("执行失败")
            expect(page.locator('[data-test="tool-run-category"]')).to_contain_text("网络")
            expect(page.locator('[data-test="tool-run-output"]')).to_contain_text("无法连接")
        finally:
            _cleanup_tool(api_client, bad_url_tid)
            sql_query(f"DELETE FROM tp_tool_invocation WHERE tool_id={bad_url_tid}")

        assert_no_errors(collectors, "T167-a test-run E2E")
    finally:
        fake.shutdown()
        sql_query(f"DELETE FROM tp_tool_invocation WHERE tool_id IN ({tid}, {doc_tid})")
        _cleanup_tool(api_client, tid)
        _cleanup_tool(api_client, doc_tid)

    assert sql_query(
        f"SELECT count(*) FROM tp_tool WHERE id IN ({tid}, {doc_tid})").strip() == "0"
    assert sql_query(
        f"SELECT count(*) FROM tp_tool_invocation WHERE tool_id IN ({tid}, {doc_tid})"
    ).strip() == "0"

