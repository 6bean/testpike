
import httpx
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, nav_to, row_cmd



def _mk_dataset(api, uniq):
    pass
    code = f"M3E{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": f"M3标签项目{uniq}", "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]

    r = api.post(f"/projects/{pid}/envs", json={
        "name": f"测试环境{uniq}", "envType": 2, "baseUrl": f"https://t-{uniq}.tp-local.test",
    })
    assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
    env_ids = [r.json()["data"]]

    case_ids = []
    for i, (title, phase) in enumerate((("支付下单M3", 1), ("优惠券回归M3", 2))):
        r = api.post("/cases", json={
            "projectId": pid, "title": f"{title}{uniq[:6]}", "precondition": "无",
            "expectedResult": "符合预期", "priority": 1, "confidence": 2,
            "endType": "web", "status": 3, "phase": phase,
            "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}],
        })
        assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
        case_ids.append(r.json()["data"]["id"])
    return pid, env_ids, case_ids


def _mk_tag(api, pid, name):
    r = api.post("/tags", json={"projectId": pid, "name": name, "type": 4})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建标签失败: {r.text[:300]}"
    return r.json()["data"]


def _set_case_tags(api, case_id, pid, tag_ids):
    r = api.put(f"/cases/{case_id}/tags", json={"projectId": pid, "tagIds": tag_ids})
    assert r.status_code == 200 and r.json().get("code") == "0", f"打标签失败: {r.text[:300]}"


def _refresh_projects(page):
    pass
    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)


def _cleanup(api, pid):
    api.delete(f"/projects/{pid}")


def _whitelist_409(collectors):
    pass
    collectors["network_4xx"] = [
        e for e in collectors["network_4xx"] if not (e["status"] == 409 and "/tags" in e["url"])
    ]
    collectors["console_errors"] = [
        e for e in collectors["console_errors"] if "status of 409" not in e
    ]


def _select_project(page, select_test, project_name, expect_sel):
    pass
    page.locator(f'[data-test="{select_test}"]').click()
    page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{project_name}"):visible').first.click()
    expect(page.locator(expect_sel).first).to_be_visible(timeout=10000)



def test_m3_tag_manage(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    pid, env_ids, case_ids = _mk_dataset(api_client, uniq)
    proj_name = f"M3标签项目{uniq}"
    try:
        t1 = _mk_tag(api_client, pid, "支付核心")
        t2 = _mk_tag(api_client, pid, "回归范围")
        _set_case_tags(api_client, case_ids[0], pid, [t1["id"], t2["id"]])
        _set_case_tags(api_client, case_ids[1], pid, [t2["id"]])

        _refresh_projects(page)

        # ② Tags page: land the project segment through the global header context
        # (the page-level project selector was retired by the header IA ruling)
        page.goto(f"{BASE_URL}/#/tags?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="tag-table"] tbody tr').first).to_be_visible(timeout=10000)
        expect(page.locator(f'[data-test="tag-name-{t1["id"]}"]')).to_have_text("支付核心", timeout=10000)
        expect(page.locator(f'[data-test="tag-name-{t2["id"]}"]')).to_have_text("回归范围")

        page.locator('[data-test="tag-create-btn"]').click()
        expect(page.locator('[data-test="tag-create-name"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="tag-create-name"]').fill("冒烟标签")
        page.locator('[data-test="tag-create-submit"]').click()
        expect(page.locator('[data-test="tag-table"]').get_by_text("冒烟标签")).to_be_visible(timeout=10000)

        row = page.locator('[data-test="tag-table"] tbody tr', has_text="冒烟标签")
        row.locator('[data-test="tag-rename-btn"]').click()
        expect(page.locator('[data-test="tag-rename-input"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="tag-rename-input"]').fill("冒烟标签V2")
        page.locator('[data-test="tag-rename-submit"]').click()
        expect(page.locator('[data-test="tag-table"]').get_by_text("冒烟标签V2")).to_be_visible(timeout=10000)

        row2 = page.locator('[data-test="tag-table"] tbody tr', has_text="回归范围")
        row2.locator('[data-test="tag-delete-btn"]').click()
        expect(page.locator('.el-message-box')).to_be_visible(timeout=5000)
        expect(page.locator('.el-message-box')).to_contain_text("无法删除", timeout=5000)
        expect(page.locator('.el-message-box')).to_contain_text("该项目下还有 2 条用例", timeout=5000)
        page.locator('.el-message-box__btns button:has-text("知道了")').click()
        expect(page.locator('[data-test="tag-table"]').get_by_text("回归范围")).to_be_visible()

        row2.locator('[data-test="tag-merge-btn"]').click()
        expect(page.locator('[data-test="tag-merge-to"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="tag-merge-to"]').click()
        page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("支付核心"):visible').first.click()
        page.locator('[data-test="tag-merge-submit"]').click()
        expect(page.locator('[data-test="tag-table"]').get_by_text("回归范围")).to_have_count(0, timeout=10000)
        t1row = page.locator('[data-test="tag-table"] tbody tr', has_text="支付核心")
        expect(t1row).to_contain_text("2")

        _whitelist_409(collectors)

        page.locator('[data-test="tag-tabs"] .el-tabs__item', has_text="覆盖率统计").click()
        expect(page.locator('[data-test="tag-stats-table"] tbody tr').first).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="tag-stats-table"]').get_by_text("支付核心")).to_be_visible()

        # Context persists per user (localStorage) — no re-pick needed after reload
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="tag-table"]').get_by_text("支付核心")).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="tag-table"] tbody tr', has_text="支付核心")).to_contain_text("2")

        assert_no_errors(collectors, "M3 标签管理 CRUD")
    finally:
        _cleanup(api_client, pid)



def test_m3_case_tag_assign(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    pid, env_ids, case_ids = _mk_dataset(api_client, uniq)
    proj_name = f"M3标签项目{uniq}"
    try:
        t1 = _mk_tag(api_client, pid, "支付核心")
        t2 = _mk_tag(api_client, pid, "回归范围")

        _refresh_projects(page)

        nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
        _select_project(page, "project-ctx-select", proj_name, '[data-test="case-table"] tbody tr')

        row_cmd(page, page.locator('[data-test="case-table"] tbody tr').first, "tag")
        expect(page.locator('[data-test="case-tag-dialog"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="case-tag-select"]').click()
        tag_opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item:has-text("支付核心"):visible').first
        expect(tag_opt).to_be_visible(timeout=10000)
        tag_opt.click()
        reg_opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item:has-text("回归范围"):visible').first
        expect(reg_opt).to_be_visible(timeout=5000)
        reg_opt.click()
        page.locator('[data-test="case-tag-dialog"] .el-dialog__header').click()
        page.locator('[data-test="case-tag-submit"]').click()
        expect(page.locator('.el-message').get_by_text("用例标签已更新")).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="case-table"] tbody tr').first).to_contain_text("支付核心", timeout=10000)

        row_cmd(page, page.locator('[data-test="case-table"] tbody tr').first, "tag")
        expect(page.locator('[data-test="case-tag-dialog"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="case-tag-submit"]').click()
        expect(page.locator('.el-message').get_by_text("用例标签已更新")).to_be_visible(timeout=10000)

        page.locator('[data-test="case-filter-tag"]').click()
        page.locator('.el-select-dropdown:visible .el-select-dropdown__item:has-text("支付核心"):visible').first.click()
        page.keyboard.press("Escape")
        expect(page.locator('[data-test="case-table"] tbody tr').first).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="case-table"] tbody tr')).to_have_count(1, timeout=10000)

        page.goto(f"{BASE_URL}/#/cases", wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-page"]')).to_be_visible(timeout=10000)
        _select_project(page, "project-ctx-select", proj_name, '[data-test="case-table"] tbody tr')
        expect(page.locator('[data-test="case-table"] tbody tr').first).to_contain_text("支付核心", timeout=10000)

        assert_no_errors(collectors, "M3 用例打标签 + 筛选")
    finally:
        _cleanup(api_client, pid)



def test_m3_plan_tag_scope(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    pid, env_ids, case_ids = _mk_dataset(api_client, uniq)
    proj_name = f"M3标签项目{uniq}"
    plan_name = f"M3标签计划{uniq}"
    try:
        t1 = _mk_tag(api_client, pid, "回归范围")
        _set_case_tags(api_client, case_ids[0], pid, [t1["id"]])

        r = api_client.post("/plans", json={
            "projectId": pid, "name": plan_name, "targetVersion": "v2.0",
            "envIds": env_ids,
            "caseScope": {"phases": [1, 2], "priorities": [], "keyword": None, "tagIds": [t1["id"]]},
            "lockMode": 1,
        })
        assert r.status_code == 200 and r.json().get("code") == "0", f"建计划失败: {r.text[:300]}"
        plan_id = r.json()["data"]["id"]

        r = api_client.get(f"/plans/{plan_id}")
        assert r.json().get("code") == "0", f"计划详情失败: {r.text[:300]}"
        assert r.json()["data"]["caseCount"] == 1, \
            f"tag 范围规则应仅拉入 1 条带标签用例: caseCount={r.json()['data']['caseCount']}"

        # ② Frontend: plan detail caseCount matches (plan side)
        # The page-level project selector was retired by the header IA ruling —
        # a ?projectId deep link lands the segment directly
        page.goto(f"{BASE_URL}/#/plans?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="plan-name-link"]').first).to_be_visible(timeout=10000)
        page.locator('[data-test="plan-name-link"]').first.click()
        expect(page.locator('[data-test="plan-detail-page"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="plan-detail-casecount"]')).to_have_text("1", timeout=10000)

        assert_no_errors(collectors, "M3 计划 tag 范围规则")
    finally:
        _cleanup(api_client, pid)
