"""Protocol-face E2E for the request editor: the ws/http switch filters the
assertion type dropdown, exposes the withinMs window field only on the ws face,
and the saved request keeps protocol + assertions through a full reload (API
reconciliation).

Flow: 1) switch to WebSocket and assert the type dropdown carries exactly the
three ws types; 2) a ws.receive row renders the optional withinMs field;
3) flipping back to HTTP restores the six http types and drops withinMs;
4) save then reload the whole page: the ws row survives in the DOM and the API
returns protocol=ws with the persisted assertion shape; 5) zero tolerance for
4xx/5xx/console/browser issues, teardown deletes the seeded project back to zero.

Project/collection are seeded through the real admin API (8081); login runs
the real logged_in chain — no mocks.
"""
import json

import pytest
from playwright.sync_api import expect

from conftest import (  # anchor migration helpers (header context + rail picks)
    pick_apitest_collection,
    pick_apitest_request,
    pick_project_in_header,
)

from conftest import (
    BASE_URL,
    assert_no_errors,
    nav_to,
)

pytestmark = pytest.mark.timeout(300)

WS_TYPE_LABELS = ["窗口内收到消息", "消息到达顺序", "消息频率（条数）"]
HTTP_ONLY_LABEL = "状态码"


def _api_ok(r):
    assert r.status_code == 200 and str(r.json().get("code")) == "0", f"API 失败: {r.text[:300]}"
    return r.json()["data"]


def _select(page, anchor, option_text):
    """el-select common pick: click the anchored root, hit a visible option by text."""
    page.locator(f'[data-test="{anchor}"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item").filter(has_text=option_text).first
    expect(option).to_be_visible(timeout=10000)
    option.click()


def _open_type_dropdown(page, row_index=0):
    page.locator(f'[data-test="assertion-type-{row_index}"]').click()
    return page.locator(".el-select-dropdown:visible .el-select-dropdown__item")


@pytest.fixture
def t372_project_collection(api_client, uniq):
    """Real API project + collection with uniq suffix (parallel-safe); teardown deletes back to zero."""
    data = _api_ok(api_client.post("/projects", json={
        "name": f"T372协议项目{uniq}", "code": f"T372P{uniq.upper()}"}))
    pid = data["id"] if isinstance(data, dict) else data
    coll = _api_ok(api_client.post("/apitest/collections", json={
        "projectId": pid, "parentId": 0, "name": f"协议集合{uniq}", "tags": ""}))
    yield pid, coll["id"]
    api_client.delete(f"/apitest/collections/{coll['id']}")
    api_client.delete(f"/projects/{pid}")


def test_t372_protocol_face_switch_and_persist(logged_in, collectors, api_client, uniq, t372_project_collection):
    """ws/http switch filters the type vocabulary; ws row persists through reload."""
    page = logged_in
    pid, coll_id = t372_project_collection

    # The fixture creates the project after token injection — reload re-runs
    # loadMe so the header visible-project snapshot includes it.
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    nav_to(page, "API 测试", "/api-test", '[data-test="api-test-page"]', submenu="执行测试")
    project_name = f"T372协议项目{uniq}"
    pick_project_in_header(page, project_name)
    pick_apitest_collection(page, f"协议集合{uniq}")

    # New request: name + path, protocol still http by default.
    page.locator('[data-test="apitest-new-request-btn"]').click()
    page.locator('[data-test="apitest-request-name"]').fill(f"行情推送{uniq}")
    page.locator('[data-test="apitest-url-path"]').fill("/ws/quotes")

    # ── 1. switch protocol to ws ───────────────────────────────────────────────
    _select(page, "apitest-protocol-select", "WebSocket")
    page.locator('[data-test="assertion-add-btn"]').click()

    # ── 2. ws face: type dropdown carries exactly the 3 ws types ──────────────
    opts = _open_type_dropdown(page)
    expect(opts).to_have_count(len(WS_TYPE_LABELS), timeout=5000)
    texts = opts.all_inner_texts()
    assert set(texts) == set(WS_TYPE_LABELS), f"ws 面型下拉应恰为 3 枚 ws 型，实际 {texts}"
    assert HTTP_ONLY_LABEL not in texts, "ws 面不应出现 http-only 型「状态码」"
    # Pick ws.receive and confirm the optional withinMs field renders.
    opts.filter(has_text="窗口内收到消息").first.click()
    expect(page.locator('[data-test="assertion-withinms-0"]')).to_be_visible(timeout=5000)
    page.locator('[data-test="assertion-expect-0"]').fill("pong")
    page.locator('[data-test="assertion-withinms-0"]').fill("2500")
    expect(page.locator('[data-test="assertion-error-0"]')).to_have_count(0)

    # ── 3. flip back to http: 6 types return, withinMs vanishes ───────────────
    _select(page, "apitest-protocol-select", "HTTP")
    page.locator('[data-test="assertion-type-0"]').click()
    opts = page.locator(".el-select-dropdown:visible .el-select-dropdown__item")
    expect(opts).to_have_count(6, timeout=5000)
    http_texts = opts.all_inner_texts()
    assert HTTP_ONLY_LABEL in http_texts, f"http 面应恢复「状态码」型，实际 {http_texts}"
    opts.filter(has_text=HTTP_ONLY_LABEL).first.click()
    expect(page.locator('[data-test="assertion-withinms-0"]')).to_have_count(0)

    # ── 4. back to ws + save, then reload persistence loop ────────────────────
    _select(page, "apitest-protocol-select", "WebSocket")
    page.locator('[data-test="assertion-type-0"]').click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item").filter(
        has_text="窗口内收到消息").first.click()
    page.locator('[data-test="assertion-expect-0"]').fill("pong")
    page.locator('[data-test="assertion-withinms-0"]').fill("2500")
    page.locator('[data-test="apitest-save-btn"]').click()
    expect(page.locator(".el-message:has-text(\"已保存\")").first).to_be_visible(timeout=10000)

    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    nav_to(page, "API 测试", "/api-test", '[data-test="api-test-page"]', submenu="执行测试")
    pick_project_in_header(page, project_name)
    pick_apitest_collection(page, f"协议集合{uniq}")
    pick_apitest_request(page, f"行情推送{uniq}")
    expect(page.locator('[data-test="assertion-row-0"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="assertion-withinms-0"]')).to_have_value("2500")

    # ── 5. API reconciliation: protocol=ws + assertionsJson single ws row ─────
    reqs = _api_ok(api_client.get(f"/apitest/requests?collectionId={coll_id}"))
    assert len(reqs) == 1, f"集合内应有 1 条请求，实际 {len(reqs)}"
    rid = reqs[0]["id"]
    detail = _api_ok(api_client.get(f"/apitest/requests/{rid}"))
    assert detail.get("protocol") == "ws", f"协议应持久化为 ws，实际 {detail.get('protocol')}"
    rows = json.loads(detail["assertionsJson"])
    assert len(rows) == 1 and rows[0]["type"] == "ws.receive", rows
    assert rows[0]["expect"] == "pong" and rows[0]["withinMs"] == 2500, rows

    _api_ok(api_client.delete(f"/apitest/requests/{rid}"))

    assert_no_errors(collectors, "T372 protocol face E2E")
