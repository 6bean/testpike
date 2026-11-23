
import json

import httpx
import pytest
from conftest import assert_no_errors, nav_to, pick_project_in_header
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(180)

CORE = "http://localhost:8083"


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} 业务失败: {body.get('msg')}"
    return body["data"]


@pytest.fixture()
def x1_case(api_client, uniq):
    pass
    pid = _ok(api_client.post("/projects", json={
        "name": f"X1角色项目{uniq}", "code": f"X1U{uniq[:5].upper()}"}), "建项目")
    cid = _ok(api_client.post("/cases", json={
        "projectId": pid, "title": f"X1角色用例{uniq}", "precondition": "无",
        "expectedResult": "符合预期", "priority": 1, "confidence": 2,
        "endType": "web", "status": 3,
        "steps": [{"stepNo": 1, "description": "打开首页填单", "isAssert": 1}]}), "建用例")["id"]
    action = {
        "version": "v2", "caseId": str(cid), "endType": "web",
        "steps": [{"action": "input", "target": {"css": "#a", "chain": ["css"]},
                   "value": "x", "saveAs": "订单号"}],
        "env": {"ENV": "x"},
    }
    r = httpx.post(f"{CORE}/internal/scripts", timeout=30,
                   json={"caseId": cid, "schemaVersion": "v2",
                         "actionJson": json.dumps(action, ensure_ascii=False),
                         "generatedBy": 1})
    assert r.status_code == 200 and r.json().get("code") == "0", f"预置脚本失败: {r.text[:200]}"
    yield pid, cid
    api_client.delete(f"/projects/{pid}/purge?force=true")


def _pick_case(page, title):
    pass
    pick_project_in_header(page, "X1角色项目")
    case = page.locator('[data-test="role-card-case-select"]')
    case.click()
    case.locator("input").fill(title)
    case_opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item', has_text=title)
    expect(case_opt.first).to_be_visible(timeout=10000)
    case_opt.first.click()


def test_x1_role_card_render_persist(logged_in, collectors, api_client, uniq, x1_case):
    pass
    page = logged_in
    _, cid = x1_case
    title = f"X1角色用例{uniq}"

    nav_to(page, "角色与绑定", "/role-bindings",
           '[data-test="role-card-page"]', submenu="造数与资产")

    for anchor in ("role-card-ref-group", "role-card-ref-role",
                   "role-card-ref-global", "role-card-ref-run"):
        expect(page.locator(f'[data-test="{anchor}"]')).to_be_visible()

    _pick_case(page, title)

    add_btn = page.locator('[data-test="role-card-add-btn"]')
    add_btn.click()
    add_btn.click()
    add_btn.click()
    expect(page.locator('[data-test="role-card-row-2"]')).to_be_visible()

    rows = [("买家", "每虚拟用户一份"), ("卖家", "批内共享一份"), ("池", "N 份轮转池")]
    for i, (name, binding_label) in enumerate(rows):
        card = page.locator(f'[data-test="role-card-row-{i}"]')
        card.locator('[data-test="role-card-name-input"]').fill(name)
        card.locator('[data-test="role-card-binding-select"]').click()
        popper = page.locator('.el-select-dropdown:visible').last
        expect(popper).to_be_visible()
        opt = popper.locator('.el-select-dropdown__item', has_text=binding_label).first
        expect(opt).to_be_visible()
        opt.click()
        expect(page.locator('.el-select-dropdown:visible')).to_have_count(0)
        ref = card.locator('[data-test^="role-card-ref-"]').first
        expect(ref).to_contain_text("{{" + name + ".token}}", timeout=5000)
    page.locator('[data-test="role-card-row-2"] [data-test="role-card-pool-size"] input').fill("3")

    page.locator('[data-test="role-card-save-btn"]').click()
    toast = page.locator('.el-message--success')
    expect(toast.first).to_be_visible(timeout=15000)
    expect(toast.first).to_contain_text("已保存")

    nav_to(page, "角色与绑定", "/role-bindings",
           '[data-test="role-card-page"]', submenu="造数与资产")
    _pick_case(page, title)
    for i, (name, _) in enumerate(rows):
        expect(page.locator(
            f'[data-test="role-card-row-{i}"] [data-test="role-card-name-input"]')
        ).to_have_value(name, timeout=15000)
    expect(page.locator(
        '[data-test="role-card-row-2"] [data-test="role-card-pool-size"] input')
    ).to_have_value("3")

    data = _ok(api_client.get(f"/cases/{cid}/script"), "读脚本")
    sj = json.loads(data["actionJson"])
    got = [(x["name"], x["binding"]) for x in sj.get("roles", [])]
    assert got == [("买家", "per-vu"), ("卖家", "shared"), ("池", "pool(3)")], \
        f"落库 roles 漂移: {got}"

    assert_no_errors(collectors, "X1 角色与绑定页渲染+持久化 E2E")
