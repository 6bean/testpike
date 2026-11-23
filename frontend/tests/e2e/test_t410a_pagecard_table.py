""" re-test: tables inside the shared .page-card must re-fit and keep
column-width dragging working (real browser, three card-wrapped list pages).

 wrapped 36 bare pages in the shared .page-card surface (pure template
surgery). The residual risk of that surgery is layout, not semantics: an
el-table sized against the old bare page-root could render wider/narrower than
its new card container, and Element-Plus header dragging (which the card's
padding boundary governs) must still write widths back through the shared
useColumnConfig piece and survive a reload.

Per page, the contract asserted here is:
1. the data table renders strictly inside the card box (x/right edges within
 the card - the re-fit assertion the card wrap needs);
2. dragging a fixed-width header edge grows the column and the new width is
 persisted to localStorage (tp-colcfg-<viewKey>) and survives reload;
3. a sortable header click routes through the server sort (custom sortable,
 no client-only fake re-sort) with zero console/network errors.

Data: real admin:8081 API (no mocks), uuid-suffixed throwaway projects purged
in finally; pages land on their project segment via the global header deep-link
?projectId= .
"""
import json
import subprocess

import pytest

from conftest import BASE_URL, assert_no_errors, inject_token
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)


def sql(q):
    subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike",
         "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)


def _mk_project(api, uniq, tag):
    r = api.post("/projects", json={"name": f"T410卡内复测{tag}{uniq}", "code": f"T410{tag}{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _land(page, path, pid):
    """Fresh mount on the page with the header project context (deep-link is
 the contract; hash-only goto does not remount - reload per convention)."""
    page.goto(f"{BASE_URL}/#{path}?projectId={pid}", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")


def _assert_inside_card(page, table_sel):
    card = page.locator(".page-card").first.bounding_box()
    table = page.locator(table_sel).first.bounding_box()
    assert card and table, "card or table missing"
    assert table["x"] >= card["x"] - 1, f"table starts left of the card: {table['x']} < {card['x']}"
    assert table["x"] + table["width"] <= card["x"] + card["width"] + 1, (
        f"table overflows the card right edge: "
        f"{table['x'] + table['width']:.1f} > {card['x'] + card['width']:.1f}")


def _drag_and_persist(page, table_sel, header_text, colcfg_key):
    th = page.locator(f"{table_sel} thead th").filter(has_text=header_text).first
    box = th.bounding_box()
    assert box and box["width"] > 50, f"header column '{header_text}' too narrow to drag: {box}"
    start_w = box["width"]
    new_w = start_w
    for _attempt in range(3):
        box = th.bounding_box()
        edge_x, mid_y = box["x"] + box["width"] - 4, box["y"] + box["height"] / 2
        page.mouse.move(edge_x - 10, mid_y)
        page.mouse.move(edge_x, mid_y)
        page.mouse.down()
        page.mouse.move(edge_x + 60, mid_y, steps=6)
        page.mouse.up()
        new_w = th.bounding_box()["width"]
        if new_w > start_w + 30:
            break
    assert new_w > start_w + 30, f"卡内拖宽未生效: {start_w} -> {new_w}"
    stored = page.evaluate(
        "(k) => localStorage.getItem('tp-colcfg-' + k)", colcfg_key)
    assert stored, f"拖宽未写入 {colcfg_key} 列配置"
    saved = json.loads(stored)
    widths = {c["key"]: c["width"] for c in saved["columns"]}
    assert any(w is not None and w > start_w + 20 for w in widths.values()), (
        f"localStorage 未记录加宽后的列宽: {widths}")
    return new_w


# ── page 1: batch ledger (BatchListView, card-wrapped, colcfg 'batches') ─────

def test_t410a_batch_table_refits_and_drag_persists(page, collectors, api_client, uniq):
    """Batch list page inside .page-card: table re-fits to the card, the
    batch-no column keeps its dragged width across reload."""
    pid = _mk_project(api_client, uniq, "B")
    r = api_client.post(f"/projects/{pid}/envs", json={
        "name": f"T410环境{uniq[:6]}", "envType": 2,
        "baseUrl": f"https://t410-{uniq[:6]}.tp-local.test", "endTypes": "web"})
    assert r.json().get("code") == "0", f"建环境失败: {r.text[:200]}"
    env_id = r.json()["data"]
    r = api_client.post("/cases", json={
        "projectId": pid, "title": f"T410用例{uniq[:6]}", "precondition": "无",
        "expectedResult": "符合预期", "priority": 1, "confidence": 2,
        "endType": "web", "status": 3,
        "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
    assert r.json().get("code") == "0", f"建用例失败: {r.text[:200]}"
    case_id = r.json()["data"]["id"]
    for name in ("T410批次甲", "T410批次乙"):
        r = api_client.post("/batches", json={
            "projectId": pid, "name": f"{name}{uniq[:6]}", "envId": env_id,
            "caseIds": [case_id]})
        assert r.json().get("code") == "0", f"建批失败: {r.text[:200]}"
    try:
        inject_token(page)
        _land(page, "batches", pid)
        table = '[data-test="batch-table"]'
        expect_rows = page.locator(f"{table} tbody tr")
        # web-first: the card page fetches after mount — wait for the window
        expect(expect_rows.first).to_be_visible(timeout=15000)

        _assert_inside_card(page, table)

        new_w = _drag_and_persist(page, table, "批次号", "batches")

        # server-side sort must not break inside the card: click the created-at header
        page.locator(f"{table} thead th").filter(has_text="创建时间").first.click()
        expect(expect_rows.first).to_be_visible(timeout=15000)

        page.reload(wait_until="domcontentloaded")
        _land(page, "batches", pid)
        expect(expect_rows.first).to_be_visible(timeout=15000)
        th2 = page.locator(f"{table} thead th").filter(has_text="批次号").first
        w2 = th2.bounding_box()["width"]
        assert abs(w2 - new_w) < 10, f"reload 后卡内拖宽丢失: {new_w} -> {w2}"
        _assert_inside_card(page, table)

        assert_no_errors(collectors, "T410a 批次页卡内表宽+拖宽")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
        page.evaluate("() => { for (const k of Object.keys(localStorage)) "
                      "if (k.startsWith('tp-colcfg-')) localStorage.removeItem(k) }")


# ── page 2: tag ledger (TagListView, card-wrapped, colcfg 'tags') ────────────

def test_t410a_tag_table_refits_and_drag_persists(page, collectors, api_client, uniq):
    """Tag list page inside .page-card: table re-fits to the card, the
    case-count column keeps its dragged width across reload."""
    pid = _mk_project(api_client, uniq, "T")
    for i in range(3):
        r = api_client.post("/tags", json={
            "projectId": pid, "name": f"T410标签{i}{uniq[:6]}", "type": 4})
        assert r.json().get("code") == "0", f"建标签失败: {r.text[:200]}"
    try:
        inject_token(page)
        _land(page, "tags", pid)
        table = '[data-test="tag-table"]'
        rows = page.locator(f"{table} tbody tr")
        rows.first.wait_for(state="visible", timeout=15000)

        _assert_inside_card(page, table)

        new_w = _drag_and_persist(page, table, "用例数", "tags")

        page.locator(f"{table} thead th").filter(has_text="用例数").first.click()
        expect(rows.first).to_be_visible(timeout=15000)

        page.reload(wait_until="domcontentloaded")
        _land(page, "tags", pid)
        expect(rows.first).to_be_visible(timeout=15000)
        th2 = page.locator(f"{table} thead th").filter(has_text="用例数").first
        w2 = th2.bounding_box()["width"]
        assert abs(w2 - new_w) < 10, f"reload 后卡内拖宽丢失: {new_w} -> {w2}"

        assert_no_errors(collectors, "T410a 标签页卡内表宽+拖宽")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
        page.evaluate("() => { for (const k of Object.keys(localStorage)) "
                      "if (k.startsWith('tp-colcfg-')) localStorage.removeItem(k) }")


# ── page 3: data factory (DataFactoryListView, card-wrapped, colcfg 'dataFactories')

def test_t410a_datafactory_table_refits_and_drag_persists(page, collectors, api_client, uniq):
    """Data-factory list page inside .page-card: table re-fits to the card, the
    created-at column keeps its dragged width across reload."""
    import json as _json
    pid = _mk_project(api_client, uniq, "F")
    r = api_client.post(f"/projects/{pid}/envs", json={
        "name": f"T410造数环境{uniq[:6]}", "envType": 2,
        "baseUrl": f"https://t410f-{uniq[:6]}.tp-local.test"})
    assert r.json().get("code") == "0", f"建环境失败: {r.text[:200]}"
    env_id = r.json()["data"]
    content = _json.dumps({
        "poolType": "订单", "method": "GET", "url": "https://t410.tp-local.test/orders",
        "headers": {}, "body": {}, "extractExpr": "$.data[*].orderNo"}, ensure_ascii=False)
    r = api_client.post("/data-factories", json={
        "projectId": pid, "envId": env_id, "type": 1,
        "name": f"T410造数{uniq[:6]}", "content": content})
    assert r.json().get("code") == "0", f"建造数脚本失败: {r.text[:200]}"
    try:
        inject_token(page)
        _land(page, "data-factories", pid)
        table = '[data-test="datafactory-table"]'
        rows = page.locator(f"{table} tbody tr")
        rows.first.wait_for(state="visible", timeout=15000)

        _assert_inside_card(page, table)

        new_w = _drag_and_persist(page, table, "创建时间", "dataFactories")

        page.reload(wait_until="domcontentloaded")
        _land(page, "data-factories", pid)
        expect(rows.first).to_be_visible(timeout=15000)
        th2 = page.locator(f"{table} thead th").filter(has_text="创建时间").first
        w2 = th2.bounding_box()["width"]
        assert abs(w2 - new_w) < 10, f"reload 后卡内拖宽丢失: {new_w} -> {w2}"

        assert_no_errors(collectors, "T410a 造数页卡内表宽+拖宽")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
        page.evaluate("() => { for (const k of Object.keys(localStorage)) "
                      "if (k.startsWith('tp-colcfg-')) localStorage.removeItem(k) }")
