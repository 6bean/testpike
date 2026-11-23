
import pytest
from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    pick_apitest_collection,
    pick_project_in_header,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

HOP_NAMES = ["登录跳", "查询跳", "清理跳"]


def _seed(api_client, uniq, hops=True):
    r = api_client.post("/projects", json={"name": f"T414c升格项目{uniq}",
                                           "code": f"T414C{uniq[:6].upper()}", "tags": ""})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    data = r.json()["data"]
    pid = data["id"] if isinstance(data, dict) else data

    def coll(name):
        r = api_client.post("/apitest/collections", json={
            "projectId": pid, "parentId": 0, "name": f"{name}{uniq}", "tags": ""})
        assert r.json().get("code") == "0", f"建接口集失败: {r.text[:300]}"
        return r.json()["data"]["id"]

    cid = coll("T414c升格流程")
    if hops:
        for i, name in enumerate(HOP_NAMES):
            r = api_client.post("/apitest/requests", json={
                "collectionId": cid, "name": f"{name}{uniq}", "method": "POST",
                "protocol": "http", "urlPath": f"/promote/{i}", "headersJson": "[]",
                "bodyText": '{"i":%d}' % i, "authJson": "{}",
                "assertionsJson": '[{"type":"status","op":"equals","expect":200}]'})
            assert r.json().get("code") == "0", f"建请求失败 {name}: {r.text[:300]}"
    return pid, coll("T414c空流程")


def test_t414c_flow_promote_happy_and_gate(page, collectors, api_client, uniq):
    """Entry enabled -> confirm -> toast with caseNo -> case detail jump;
    empty collection -> entry disabled with guidance."""
    pid, empty_cid = _seed(api_client, uniq)
    flow_coll = f"T414c升格流程{uniq}"
    empty_coll = f"T414c空流程{uniq}"
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/api-test", wait_until="domcontentloaded")
        pick_project_in_header(page, f"T414c升格项目{uniq}")
        pick_apitest_collection(page, flow_coll)

        # --- A. flow mode opens with the entry enabled (rows are loaded).
        page.locator('[data-test="apitest-flow-flow-btn"]').click()
        pane = page.locator('[data-test="apitest-flow-pane"]')
        expect(pane).to_be_visible()
        btn = page.locator('[data-test="apitest-flow-promote-btn"]')
        expect(btn).to_be_visible()
        expect(btn).to_be_enabled(timeout=15000)

        #     honest preview of what gets frozen).
        btn.click()
        dialog = page.locator(".el-message-box")
        expect(dialog).to_be_visible()
        expect(dialog).to_contain_text(flow_coll)
        expect(dialog).to_contain_text("3 个请求")
        dialog.locator("button", has_text="保存").click()

        # --- success: toast carries the created case number, then the page
        #     lands on the promoted case detail (body-level toast survives the
        #     SPA router push, so both asserts are race-free).
        toast = page.locator(".el-message")
        expect(toast).to_contain_text("已生成回归用例", timeout=20000)
        expect(toast).to_contain_text("3 个请求步骤")
        page.wait_for_function("() => location.hash.startsWith('#/cases/')", timeout=15000)
        case_detail = page.locator("body")
        expect(case_detail).to_contain_text("回归", timeout=20000)

        # --- lineage: back to the rail, every member row now carries the
        #     promoted badge pointing at the SAME case.
        page.goto(f"{BASE_URL}/#/api-test", wait_until="domcontentloaded")
        pick_project_in_header(page, f"T414c升格项目{uniq}")
        pick_apitest_collection(page, flow_coll)
        page.locator('[data-test="apitest-flow-flow-btn"]').click()
        expect(page.locator('[data-test="apitest-flow-row-1"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="apitest-flow-row-1"]').click()
        expect(page.locator('[data-test="apitest-promoted-badge"]')).to_be_visible(timeout=15000)

        # --- B. empty collection: entry rendered but disabled, and the
        #     guidance sentence is the tooltip content (prevention posture).
        pick_apitest_collection(page, empty_coll)
        expect(page.locator('[data-test="apitest-flow-empty"]')).to_be_visible(timeout=15000)
        btn = page.locator('[data-test="apitest-flow-promote-btn"]')
        expect(btn).to_be_disabled()
        # tooltip is lazy — trigger it the user way via hover on the wrapper
        page.locator('[data-test="apitest-flow-promote-btn"]').hover(force=True)
        tip = page.locator(".el-tooltip__popper, [role='tooltip']")
        expect(tip.filter(has_text="先新建或导入请求")).to_be_visible(timeout=8000)

        assert_no_errors(collectors, "T414c 整流程升格入口/确认/跳转/空集门闸")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
