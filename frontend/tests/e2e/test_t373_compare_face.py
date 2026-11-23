"""Run-history response compare + defect exit E2E .

Honesty contract under test: two dispatched collection runs of the same
fixture endpoint record genuinely different response snapshots; picking both
rows in the run drawer must open a JSON-mode diff table with at least one
changed path, and the defect exit must carry the draft into the built-in
defect library (reload persistence), not a parallel store.

Flow: 1) seed a real throwaway project + env(:8899) + collection with two
POST /api/order requests (distinct orderNo bodies — an empty body is a real
fixture 400, not a fixture bug) through the admin API; 2) open one collection
run WITH the env so the executor really claims and finalizes both child runs
(the compare read path needs landed snapshots — no SQL shortcut here, unlike
the attribution-face test); 3) reload + header project pick + rail collection
pick, open the run drawer, check the two compare boxes, run the compare,
assert the diff table; 4) open the defect dialog, set a unique title, submit,
reconcile tp_defect via SQL and re-find the row on the defects page after a
reload; 5) three zero-tolerance gates plus purge teardown back to zero.
"""
import time

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

pytestmark = pytest.mark.timeout(420)

STATUS_200_ASSERT = '[{"type":"status","op":"equals","expect":200}]'


def _api_ok(r):
    assert r.status_code == 200 and str(r.json().get("code")) == "0", f"API 失败: {r.text[:300]}"
    return r.json()["data"]


def test_t373_compare_diff_and_defect_exit(logged_in, collectors, api_client, uniq):
    """Two real dispatched runs diff in the drawer; the exit lands in the defect library."""
    page = logged_in
    proj_name = f"E2E比对项目{uniq}"

    # ── Arrange: project + env + collection + two orderNo-distinct requests ───
    data = _api_ok(api_client.post("/projects", json={
        "name": proj_name, "code": f"CMP{uniq.upper()}"}))
    pid = data["id"] if isinstance(data, dict) else data
    env = _api_ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"cmpenv{uniq}", "envType": 2, "baseUrl": "http://127.0.0.1:8899",
        "configJson": "{}", "endTypes": "web", "browsers": "chromium",
        "versionSnapshot": ""}))
    env_id = env["id"] if isinstance(env, dict) else env
    coll = _api_ok(api_client.post("/apitest/collections", json={
        "projectId": pid, "parentId": 0, "name": f"cmpcoll{uniq}", "tags": ""}))
    col_id = coll["id"]

    def save_req(tag):
        return _api_ok(api_client.post("/apitest/requests", json={
            "collectionId": col_id, "name": f"下单{tag}", "method": "POST",
            "urlPath": "/api/order",
            "headersJson": '[{"name":"Content-Type","value":"application/json"}]',
            "bodyText": f'{{"orderNo":"E2E{uniq}{tag}","userId":7}}',
            "authJson": "{}", "assertionsJson": STATUS_200_ASSERT}))

    save_req("A")
    save_req("B")

    # ── Real dispatch: env present => executor claims; poll to terminal ───────
    created = _api_ok(api_client.post("/apitest/run-sets", json={
        "collectionId": col_id, "envId": env_id, "name": f"cmpround{uniq}",
        "interruptionMode": "CONTINUE_AND_SUMMARY"}))
    set_id = created["id"]

    ledger = None
    deadline = time.time() + 180  # API-side poll, not a UI wait (no page involved yet)
    while time.time() < deadline:
        ledger = _api_ok(api_client.get(f"/apitest/run-sets/{set_id}"))["ledger"]
        if ledger["status"] not in (0, 1):
            break
        time.sleep(3)
    assert ledger is not None and ledger["status"] == 2, \
        f"集合运行应真派发至绿色终态，实际 {ledger}"

    # ── UI: header project pick + rail collection pick + drawer ───────────────
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    nav_to(page, "API 测试", "/api-test", '[data-test="api-test-page"]', submenu="执行测试")
    pick_project_in_header(page, proj_name)
    pick_apitest_collection(page, f"cmpcoll{uniq}")

    page.locator('[data-test="apitest-runset-history-btn"]').click()
    expect(page.locator('[data-test="apitest-runset-drawer"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="apitest-runset-detail"]')).to_be_visible(timeout=15000)

    # ── Act: pick both child runs, run the compare ────────────────────────────
    checks = page.locator('[data-test="apitest-runset-compare-check"]')
    expect(checks).to_have_count(2, timeout=20000)
    checks.nth(0).click()
    checks.nth(1).click()

    compare_btn = page.locator('[data-test="apitest-runset-compare-btn"]')
    expect(compare_btn).to_be_enabled(timeout=5000)
    compare_btn.click()

    panel = page.locator('[data-test="apitest-runset-compare-panel"]')
    expect(panel).to_be_visible(timeout=15000)
    err = page.locator('[data-test="apitest-runset-compare-error"]')
    assert err.count() == 0, f"比对不应报错: {err.first.inner_text()}"

    # Two runs carry different orderNo bodies -> JSON mode with >=1 changed path.
    diff_row = panel.locator(
        '[data-test="apitest-runset-diff-table"] .el-table__row').first
    expect(diff_row).to_be_visible(timeout=10000)
    n = panel.locator('[data-test="apitest-runset-diff-table"] .el-table__row').count()
    assert n >= 1, f"差分表应至少一条变更路径，实际 {n}"
    # The identical verdict must NOT show for genuinely different snapshots.
    assert page.locator('[data-test="apitest-runset-identical"]').count() == 0, \
        "两侧快照不同却声称完全一致"

    # ── Defect exit: dialog pre-filled, submit lands in the defect library ────
    defect_btn = page.locator('[data-test="apitest-runset-defect-btn"]')
    expect(defect_btn).to_be_enabled(timeout=5000)
    defect_btn.click()
    dialog = page.locator('[data-test="apitest-runset-defect-dialog"]')
    expect(dialog).to_be_visible(timeout=10000)
    marker = f"cmpdef{uniq}"
    page.locator('[data-test="apitest-runset-defect-title"]').fill(f"比对转缺陷-{marker}")
    page.locator('[data-test="apitest-runset-defect-submit"]').click()
    expect(dialog).to_be_hidden(timeout=15000)

    # DB reconciliation: the draft crossed the real defect-library chain.
    assert sql_query(
        f"SELECT count(*) FROM tp_defect WHERE project_id = {pid} "
        f"AND title LIKE '%{marker}%'") == "1", "缺陷应随比对出口落库一条"

    # ── Reload persistence on the defects page (no parallel store) ────────────
    page.reload(wait_until="domcontentloaded")
    nav_to(page, "缺陷库", "/defects", '[data-test="defect-list-page"]', submenu="结果与缺陷")
    pick_project_in_header(page, proj_name)
    expect(page.locator('[data-test="defect-title-link"]').filter(
        has_text=marker).first).to_be_visible(timeout=15000)

    assert_no_errors(collectors, "T373 compare face + defect exit E2E")

    # ── Teardown: purge the throwaway project, reconcile residue to zero ──────
    r = api_client.delete(f"/projects/{pid}/purge?force=true")
    assert r.json().get("code") == "0", f"purge 失败: {r.text[:200]}"
    for table, where in (
        ("tp_apitest_run_set", f"project_id = {pid}"),
        ("tp_defect", f"project_id = {pid}"),
        ("tp_apitest_request",
         f"collection_id IN (SELECT id FROM tp_apitest_collection WHERE project_id={pid})"),
        ("tp_apitest_run", f"run_set_id = {set_id}"),
        ("tp_exec_task", f"project_id = {pid}"),
    ):
        assert sql_query(f"SELECT count(*) FROM {table} WHERE {where}") == "0", table
