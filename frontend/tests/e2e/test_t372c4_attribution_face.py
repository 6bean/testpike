
import pytest
from playwright.sync_api import expect

from conftest import (  # anchor migration helpers (header context + rail picks)
    pick_apitest_collection,
    pick_project_in_header,
)

from conftest import (
    BASE_URL,
    assert_no_errors,
    nav_to,
    sql_query,
)

pytestmark = pytest.mark.timeout(300)

HTTP_TWO_ASSERTS = ('[{"type":"status","op":"equals","expect":200},'
                    '{"type":"extract","path":"$.data.login","saveAs":"loginok"}]')


def _api_ok(r):
    assert r.status_code == 200 and str(r.json().get("code")) == "0", f"API 失败: {r.text[:300]}"
    return r.json()["data"]


def test_t372_attribution_drawer_three_faces(logged_in, collectors, api_client, uniq):
    """Drawer shows per-protocol honest attribution for passed rows only."""
    page = logged_in

    # ── Arrange: project + collection + three requests (real API) ─────────────
    data = _api_ok(api_client.post("/projects", json={
        "name": f"T372归因项目{uniq}", "code": f"ATT{uniq.upper()}"}))
    pid = data["id"] if isinstance(data, dict) else data
    coll = _api_ok(api_client.post("/apitest/collections", json={
        "projectId": pid, "parentId": 0, "name": f"归因集合{uniq}", "tags": ""}))
    col_id = coll["id"]

    def save_req(name, method, path, assertions, protocol=None):
        payload = {"collectionId": col_id, "name": name, "method": method,
                   "urlPath": path, "headersJson": "[]", "bodyText": "",
                   "authJson": "{}", "assertionsJson": assertions}
        if protocol:
            payload["protocol"] = protocol
        return _api_ok(api_client.post("/apitest/requests", json=payload))

    http_row = save_req("带断言http跳", "POST", "/api/login", HTTP_TWO_ASSERTS)
    ws_row = save_req("无断言ws跳", "GET", "/ws/quotes", "[]", protocol="ws")
    plain_row = save_req("无断言http跳", "GET", "/api/ping", "[]")

    # ── Open a ledger without an env: children stay pending, no executor race ─
    created = _api_ok(api_client.post("/apitest/run-sets", json={
        "collectionId": col_id, "name": f"归因一轮{uniq}",
        "interruptionMode": "CONTINUE_AND_SUMMARY"}))
    set_id = created["id"]

    # Land terminal results directly (SQL posture mirrors ApiRunSetDetailTest:
    # the READ model + drawer wording are the subject under test, served live
    # through admin→core→DB — nothing mocked on that chain).
    sql_query(
        "UPDATE tp_apitest_run SET status = 1, assert_detail = "
        "'[{\"type\":\"status\",\"result\":\"pass\"},{\"type\":\"extract\",\"result\":\"pass\"}]'::jsonb "
        f"WHERE run_set_id = {set_id} AND request_id = {http_row['id']}")
    sql_query(
        "UPDATE tp_apitest_run SET status = 1, assert_detail = '[]'::jsonb "
        f"WHERE run_set_id = {set_id} AND request_id IN ({ws_row['id']}, {plain_row['id']})")

    # The fixture creates the project after token injection — reload re-runs
    # loadMe so the header visible-project snapshot includes it.
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    nav_to(page, "API 测试", "/api-test", '[data-test="api-test-page"]', submenu="执行测试")
    pick_project_in_header(page, f"T372归因项目{uniq}")
    pick_apitest_collection(page, f"归因集合{uniq}")

    # ── Act: open the run drawer (collection card entry) ──────────────────────
    page.locator('[data-test="apitest-runset-history-btn"]').click()
    expect(page.locator('[data-test="apitest-runset-drawer"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="apitest-runset-detail"]')).to_be_visible(timeout=15000)

    # ── Assert: three honest attribution lines, exact wording per spectrum ────
    attrs = page.locator('[data-test="apitest-runset-attribution"]')
    expect(attrs).to_have_count(3, timeout=20000)
    texts = attrs.all_inner_texts()

    assert any("断言 2 项全过" in t for t in texts), \
        f"http 带断言行应显示「断言 2 项全过」，实际 {texts}"
    assert any("连接可达 · 未配断言" in t for t in texts), \
        f"ws 无断言行应显示「连接可达 · 未配断言」，实际 {texts}"
    assert any("请求已返回 · 未配断言" in t for t in texts), \
        f"http 无断言行应显示「请求已返回 · 未配断言」，实际 {texts}"
    # Honesty gate: only the row that actually landed 2 assertions may claim
    # a full assertion pass; the two no-assert rows must use the fallback lines.
    assert sum(1 for t in texts if "项全过" in t) == 1, \
        f"仅带断言行可声称断言全过，实际 {texts}"

    # DOM cross-check anchor for the vision close-out: every attribution span
    # carries a real box (anti-hallucination posture).
    boxes = [a.bounding_box() for a in attrs.all()]
    assert all(b and b["width"] > 20 and b["height"] > 0 for b in boxes), boxes

    # ── API reconciliation: read model carries protocol + assertCount ─────────
    detail = _api_ok(api_client.get(f"/apitest/run-sets/{set_id}"))
    by_id = {s["requestId"]: s for s in detail["steps"]}
    assert by_id[http_row["id"]]["protocol"] == "http"
    assert by_id[http_row["id"]]["assertCount"] == 2
    assert by_id[ws_row["id"]]["protocol"] == "ws"
    assert by_id[ws_row["id"]]["assertCount"] == 0

    assert_no_errors(collectors, "T372 slice-4 attribution drawer E2E")

    # ── Teardown: purge the throwaway project, reconcile residue to zero ──────
    r = api_client.delete(f"/projects/{pid}/purge?force=true")
    assert r.json().get("code") == "0", f"purge 失败: {r.text[:200]}"
    for table, where in (
        ("tp_apitest_run_set", f"collection_id IN (SELECT id FROM tp_apitest_collection WHERE project_id={pid})"),
        ("tp_apitest_request", f"collection_id IN (SELECT id FROM tp_apitest_collection WHERE project_id={pid})"),
    ):
        assert sql_query(f"SELECT count(*) FROM {table} WHERE {where}") == "0", table
    residue = sql_query(f"SELECT count(*) FROM tp_apitest_run WHERE run_set_id = {set_id}")
    assert residue == "0", "集合运行子行应随 purge 一并清账"
