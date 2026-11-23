
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from conftest import assert_no_errors, nav_to, row_cmd, sql_query
from playwright.sync_api import expect


_HITS: list = []


class _FakeHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n).decode("utf-8", "replace")
        _HITS.append((self.path, body))
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


def _create_exec_tool(api_client, name, url, input_schema):
    r = api_client.post("/tools", {
        "projectId": None, "name": name, "type": 2, "category": "e2e",
        "inputSchemaJson": json.dumps(input_schema),
        "execKind": 1, "execSpecJson": json.dumps({"url": url}),
        "timeoutMs": 10000, "submitNow": True,
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
    row = page.locator('[data-test="tool-table"] tbody tr').filter(has_text=name).first
    expect(row).to_be_visible(timeout=10000)
    return row


def _open_invoke(page, row):
    row_cmd(page, row, "invoke")
    expect(page.locator('[data-test="tool-invoke-dialog"]')).to_be_visible(timeout=5000)


def _trace_cnt(tid, caller="ai"):
    return sql_query(
        f"SELECT count(*) FROM tp_tool_invocation "
        f"WHERE tool_id={tid} AND caller='{caller}' AND success=1").strip()


def _audit_issue_cnt(tid):
    return sql_query("SELECT count(*) FROM tp_audit_log WHERE object_type='tool' "
                     f"AND object_id='{tid}' AND action='confirm-issue'").strip()


def test_write_tool_confirm_roundtrip(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    name = f"t169w{uniq}"
    fake, port = _start_fake()
    tid = _create_exec_tool(api_client, name, f"http://127.0.0.1:{port}/ok",
                            {"type": "object", "x-write": True})
    try:
        nav_to(page, "工具管理", "/tools", '[data-test="tool-list-page"]', submenu="AI 能力")
        row = _search_row(page, name)
        expect(row.locator('[data-test="tool-write-tag"]')).to_have_count(1)

        _open_invoke(page, row)
        page.locator('[data-test="tool-invoke-input"]').fill('{"q":"ui-1"}')

        with page.expect_response(
                lambda r: f"/tools/{tid}/invoke" in r.url and r.status == 400,
                timeout=30000) as gate_info:
            page.click('[data-test="tool-invoke-exec"]')
        gate_body = (gate_info.value.json() or {})
        assert gate_body.get("code") == "40001" and "人工确认" in (gate_body.get("msg") or ""), \
            f"未确认应 40001 人话「人工确认」: {gate_body}"
        confirm = page.locator('[data-test="tool-confirm-dialog"]')
        expect(confirm).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="tool-confirm-body"]')).to_contain_text(name)
        expect(page.locator('[data-test="tool-confirm-body"]')).to_contain_text("写语义")
        expect(page.locator('[data-test="tool-confirm-countdown"]')).to_contain_text("秒")
        assert len(_HITS) == 0, f"未确认不得打目标: {_HITS}"
        assert _trace_cnt(tid) == "0", "未确认不得有留痕"

        page.click('[data-test="tool-confirm-cancel"]')
        expect(confirm).to_be_hidden(timeout=5000)
        assert len(_HITS) == 0 and _trace_cnt(tid) == "0", "取消后仍零执行零留痕"

        page.click('[data-test="tool-invoke-exec"]')
        expect(confirm).to_be_visible(timeout=10000)
        with page.expect_response(
                lambda r: f"/tools/{tid}/invoke" in r.url and r.status == 200,
                timeout=30000) as ok_info:
            page.click('[data-test="tool-confirm-exec"]')
        req_body = json.loads(ok_info.value.request.post_data or "{}")
        token = req_body.get("confirmToken")
        assert token, f"携票重发必带 confirmToken: {req_body}"
        ok = (ok_info.value.json() or {}).get("data") or {}
        assert ok.get("success") is True, f"确认后应执行成功: {ok}"
        result = page.locator('[data-test="tool-invoke-result"]')
        expect(result).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="tool-invoke-success-tag"]')).to_contain_text("执行成功")
        expect(page.locator('[data-test="tool-invoke-output"]')).to_contain_text("pong")
        assert any('"ui-1"' in b for _, b in _HITS), f"假目标未收到入参: {_HITS}"
        assert _trace_cnt(tid) == "1", "确认后留痕 caller=ai 恰 1"
        assert int(_audit_issue_cnt(tid)) >= 2, "confirm-issue 审计≥2（取消+确认各签发一次）"

        rr = api_client.post(f"/tools/{tid}/invoke",
                             {"input": '{"q":"ui-1"}', "confirmToken": token})
        rb = rr.json()
        assert rb.get("code") == "40001" and "无效或已过期" in (rb.get("msg") or ""), \
            f"重放同票应拒: {rb}"
        assert _trace_cnt(tid) == "1", "重放拒后留痕不涨"

        collectors["network_4xx"] = [
            e for e in collectors["network_4xx"]
            if not (e["status"] == 400 and f"/tools/{tid}/invoke" in e["url"])
        ]
        collectors["console_errors"] = [
            e for e in collectors["console_errors"] if "status of 400" not in e
        ]
        assert_no_errors(collectors, "T169 写确认弹窗消费链路")
    finally:
        fake.shutdown()
        sql_query(f"DELETE FROM tp_tool_invocation WHERE tool_id={tid}")
        _cleanup_tool(api_client, tid)

    assert sql_query(f"SELECT count(*) FROM tp_tool WHERE id={tid}").strip() == "0"
    assert sql_query(
        f"SELECT count(*) FROM tp_tool_invocation WHERE tool_id={tid}").strip() == "0"


def test_readonly_tool_no_confirm(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    name = f"t169r{uniq}"
    fake, port = _start_fake()
    tid = _create_exec_tool(api_client, name, f"http://127.0.0.1:{port}/ok",
                            {"type": "object"})
    try:
        nav_to(page, "工具管理", "/tools", '[data-test="tool-list-page"]', submenu="AI 能力")
        row = _search_row(page, name)
        expect(row.locator('[data-test="tool-write-tag"]')).to_have_count(0)

        _open_invoke(page, row)
        page.locator('[data-test="tool-invoke-input"]').fill('{"q":"ro-1"}')
        with page.expect_response(
                lambda r: f"/tools/{tid}/invoke" in r.url and r.status == 200,
                timeout=30000) as ok_info:
            page.click('[data-test="tool-invoke-exec"]')
        ok = (ok_info.value.json() or {}).get("data") or {}
        assert ok.get("success") is True, f"只读工具应直发成功: {ok}"
        expect(page.locator('[data-test="tool-confirm-dialog"]')).to_have_count(0)
        expect(page.locator('[data-test="tool-invoke-success-tag"]')).to_contain_text("执行成功")
        assert _trace_cnt(tid) == "1"

        assert_no_errors(collectors, "T169 只读零弹窗")
    finally:
        fake.shutdown()
        sql_query(f"DELETE FROM tp_tool_invocation WHERE tool_id={tid}")
        _cleanup_tool(api_client, tid)

    assert sql_query(f"SELECT count(*) FROM tp_tool WHERE id={tid}").strip() == "0"
