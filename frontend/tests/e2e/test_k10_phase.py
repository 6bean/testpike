
import subprocess

from playwright.sync_api import expect

from conftest import pick_project_in_header, BASE_URL, assert_no_errors, nav_to, row_cmd


PHASE_LABEL = {0: '未归属', 1: '冒烟', 2: '功能', 3: '回归', 4: '发布验证'}


def _mk_project(api, uniq):
    name = f"K10项目_{uniq}"
    code = f"K10{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"], name


def _mk_case(api, pid, uniq, title, phase):
    payload = {
        "projectId": pid,
        "title": f"{title}_{uniq}",
        "precondition": f"前置：已登录系统（{uniq}）",
        "expectedResult": f"预期：操作成功（{uniq}）",
        "priority": 1,
        "confidence": 2,
        "endType": "web",
        "steps": [{"stepNo": 1, "description": "打开页面", "isAssert": 1}],
    }
    if phase is not None:
        payload["phase"] = phase
    r = api.post("/cases", json=payload)
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]


def _select_project(page, project_name):
    pass
    pick_project_in_header(page, project_name)



def test_k10_phase_list_filter(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    _mk_case(api_client, pid, uniq, "登录功能验证", 2)
    _mk_case(api_client, pid, uniq, "回归主流程验证", 3)
    _mk_case(api_client, pid, uniq, "未归属用例", None)

    try:
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
        _select_project(page, pname)
        table = page.locator('[data-test="case-table"]')
        expect(table).to_be_visible()
        expect(table.locator("tbody tr.el-table__row")).to_have_count(3)

        expect(table).to_contain_text("功能")
        expect(table).to_contain_text("回归")
        expect(table).to_contain_text("未归属")

        page.locator('[data-test="case-filter-phase"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="功能").first.click()
        expect(table.locator("tbody tr.el-table__row")).to_have_count(1)
        expect(table).to_contain_text("登录功能验证")

        page.locator('[data-test="case-filter-phase"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="未归属").first.click()
        expect(table.locator("tbody tr.el-table__row")).to_have_count(1)
        expect(table).to_contain_text("未归属用例")

        page.locator('[data-test="case-reset"]').click()
        expect(table.locator("tbody tr.el-table__row")).to_have_count(3)

        page.goto(f"{BASE_URL}/#/cases", wait_until="domcontentloaded")
        _select_project(page, pname)
        expect(table.locator("tbody tr.el-table__row")).to_have_count(3)
        assert_no_errors(collectors, "K10 阶段列表/筛选")
    finally:
        api_client.delete(f"/projects/{pid}")



def test_k10_phase_create_edit_persist(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    title = f"带阶段用例_{uniq}"

    try:
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
        _select_project(page, pname)

        page.locator('[data-test="case-create"]').click()
        expect(page.locator('[data-test="case-create-dialog"]')).to_be_visible()
        page.locator('[data-test="case-create-title"]').fill(title)
        page.locator('[data-test="case-create-phase"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="发布验证").first.click()
        page.locator('[data-test="case-create-submit"]').click()
        expect(page.locator('[data-test="case-table"]')).to_contain_text(title, timeout=10000)
        expect(page.locator('[data-test="case-table"]')).to_contain_text("发布验证")

        page.goto(f"{BASE_URL}/#/cases", wait_until="domcontentloaded")
        _select_project(page, pname)
        expect(page.locator('[data-test="case-table"]')).to_contain_text(title, timeout=10000)
        expect(page.locator('[data-test="case-table"]')).to_contain_text("发布验证")

        row_cmd(page, page.locator(f'tr:has-text("{title}")').first, "detail")
        expect(page.locator('[data-test="detail-phase"]')).to_contain_text("发布验证", timeout=10000)
        page.locator('[data-test="detail-edit"]').click()
        expect(page.locator('[data-test="detail-edit-phase"]')).to_be_visible()
        page.locator('[data-test="detail-edit-phase"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="冒烟").first.click()
        page.locator('[data-test="detail-save-draft"]').click()
        expect(page.locator('[data-test="detail-phase"]')).to_contain_text("冒烟", timeout=10000)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="detail-phase"]')).to_contain_text("冒烟", timeout=10000)

        cid = page.url.split("/").pop()
        r = api_client.get(f"/cases/{cid}")
        assert r.status_code == 200 and r.json()["data"].get("phase") == 1, f"API phase 应为 1: {r.text[:300]}"

        db_user = "testpike"
        out = subprocess.run(
            ["docker", "exec", "testpike-postgres", "psql", "-U", db_user, "-d", "testpike", "-tAc",
             f"SELECT phase FROM tp_test_case WHERE id={cid}"],
            capture_output=True, text=True).stdout.strip()
        assert out == "1", f"DB phase 应为 1，实际 {out}"
        assert_no_errors(collectors, "K10 创建/编辑阶段持久化")
    finally:
        api_client.delete(f"/projects/{pid}")



def test_k10_phase_report_dist(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    _mk_case(api_client, pid, uniq, "发布验证用例", 4)

    try:
        page.goto(f"{BASE_URL}/#/report", wait_until="domcontentloaded")
        expect(page.locator('[data-test="project-ctx-select"]')).to_be_visible(timeout=15000)
        pick_project_in_header(page, pname)
        tab = page.locator('.el-tabs__item:has-text("AI 质量")')
        if tab.count() > 0:
            tab.first.click()
        expect(page.locator('[data-test="ai-phase-dist"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="ai-phase-dist"]')).to_contain_text("发布验证")
        expect(page.locator('[data-test="ai-phase-dist"]')).to_contain_text("1")
        assert_no_errors(collectors, "K10 报表阶段分布")
    finally:
        api_client.delete(f"/projects/{pid}")
