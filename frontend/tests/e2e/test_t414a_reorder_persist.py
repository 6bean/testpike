
import pytest
from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    pick_apitest_collection,
    pick_project_in_header,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(240)

NAMES = ["调序请求甲", "调序请求乙", "调序请求丙"]


def _seed(api_client, uniq):
    r = api_client.post("/projects", json={"name": f"T414a调序项目{uniq}", "code": f"T414A{uniq[:6].upper()}", "tags": ""})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    data = r.json()["data"]
    pid = data["id"] if isinstance(data, dict) else data
    r = api_client.post("/apitest/collections",
                        json={"projectId": pid, "parentId": 0, "name": f"T414a调序接口集{uniq}", "tags": ""})
    assert r.json().get("code") == "0", f"建接口集失败: {r.text[:300]}"
    cid = r.json()["data"]["id"]
    for name in NAMES:
        r = api_client.post("/apitest/requests", json={
            "collectionId": cid, "name": f"{name}{uniq}", "method": "GET",
            "protocol": "http", "urlPath": f"/reorder/{name[-1]}", "headersJson": "[]",
            "bodyText": "", "authJson": "{}", "assertionsJson": "[]"})
        assert r.json().get("code") == "0", f"建请求失败: {r.text[:300]}"
    return pid, cid


def _item(page, n):
    return page.locator(f'[data-test="flow-reorder-item-{n}"]')


def _drag_first_to_last(page):
    """Move row 1 behind row 3: native drag_to, event-trio fallback on no-op."""
    _item(page, 1).drag_to(_item(page, 3))
    if NAMES[0] in _item(page, 1).inner_text():
        page.evaluate("""() => {
            const items = document.querySelectorAll('.reorder-item');
            const dt = new DataTransfer();
            const fire = (el, type) => el.dispatchEvent(new DragEvent(type, {
                bubbles: true, cancelable: true, dataTransfer: dt }));
            fire(items[0], 'dragstart');
            fire(items[2], 'dragover');
            fire(items[2], 'drop');
            fire(items[0], 'dragend');
        }""")
    expect(_item(page, 1)).to_contain_text(NAMES[1], timeout=5000)
    expect(_item(page, 2)).to_contain_text(NAMES[2])
    expect(_item(page, 3)).to_contain_text(NAMES[0])


def _rail_row_names(page):
    return [row.inner_text() for row in page.locator('[data-test^="apitest-request-row-"]').all()]


def test_t414a_reorder_gate_drag_persist_three_way(page, collectors, api_client, uniq):
    """Filter-gated entry + drag -> save -> reload -> rail/list/preview agree."""
    pid, cid = _seed(api_client, uniq)
    proj = f"T414a调序项目{uniq}"
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/api-test", wait_until="domcontentloaded")
        pick_project_in_header(page, proj)
        pick_apitest_collection(page, f"T414a调序接口集{uniq}")
        rows = page.locator('[data-test^="apitest-request-row-"]')
        expect(rows).to_have_count(3, timeout=10000)

        # --- gate honesty: an active filter closes the entry with a named reason
        entry = page.locator('[data-test="apitest-reorder-btn"]')
        page.locator('[data-test="apitest-request-filter"]').fill(NAMES[0])
        expect(entry).to_be_disabled()
        entry.hover(force=True)
        tooltip = page.locator(".el-tooltip__popper:visible, .el-popper:visible").filter(has_text="先清空搜索再调序")
        expect(tooltip.first).to_be_visible(timeout=5000)
        page.locator('[data-test="apitest-request-filter"]').fill("")
        expect(entry).to_be_enabled()

        # --- reorder mode replaces the windowed list (mutual exclusion)
        entry.click()
        panel = page.locator('[data-test="apitest-reorder-panel"]')
        expect(panel).to_be_visible()
        expect(page.locator('[data-test="apitest-request-rows"]')).to_have_count(0)

        # save is disabled until something moves, enabled after the drag
        save_btn = page.locator('[data-test="apitest-reorder-save"]')
        expect(save_btn).to_be_disabled()
        _drag_first_to_last(page)
        expect(save_btn).to_be_enabled()
        save_btn.click()

        # --- persistence iron rule: reload, the rail shows the new order
        expect(page.locator('[data-test="apitest-reorder-panel"]')).to_have_count(0, timeout=10000)
        page.reload(wait_until="domcontentloaded")
        pick_project_in_header(page, proj)
        pick_apitest_collection(page, f"T414a调序接口集{uniq}")
        expect(rows).to_have_count(3, timeout=10000)
        texts = _rail_row_names(page)
        assert NAMES[1] in texts[0] and NAMES[2] in texts[1] and NAMES[0] in texts[2], \
            f"reload 后左栏序未保持: {texts}"

        # --- three-way ledger reconciliation: server list order = preview order
        # (conftest Client.get takes a full url — query strings go inline)
        listed = api_client.get(f"/apitest/requests?collectionId={cid}").json()["data"]
        assert [x["name"] for x in listed] == [f"{n}{uniq}" for n in (NAMES[1], NAMES[2], NAMES[0])], \
            f"服务端 list 序 != 拖后序: {[x['name'] for x in listed]}"
        preview = api_client.get(f"/apitest/run-sets/previews?collectionId={cid}").json()["data"]
        assert [p["name"] for p in preview] == [f"{n}{uniq}" for n in (NAMES[1], NAMES[2], NAMES[0])], \
            f"launch preview 序 != 拖后序（画的即跑破功）: {[p['name'] for p in preview]}"

        assert_no_errors(collectors, "T414a 拖序门闸+保存+reload 持久化+三账一致")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
