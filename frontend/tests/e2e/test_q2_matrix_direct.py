
import uuid

from playwright.sync_api import expect

from conftest import pick_project_in_header, API_BASE, BASE_URL, assert_no_errors, nav_to



def _mk_project(api):
    uniq = uuid.uuid4().hex[:8]
    name = f"Q2矩阵直达_{uniq}"
    code = f"Q2D{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code, "language": "zh-CN", "priority": "P1"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"], name


def _mk_requirement(api, pid, title, chapter):
    r = api.post(f"/projects/{pid}/requirements", json={
        "source": 3, "title": title, "acceptanceCriteria": f"{title}验收标准", "chapter": chapter,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建需求失败: {r.text[:300]}"
    data = r.json()["data"]
    return data["id"] if isinstance(data, dict) else data


def _mk_case(api, pid, title):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "前置", "expectedResult": "通过",
        "priority": 0, "confidence": 2, "endType": "web", "steps": [],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    data = r.json()["data"]
    return data if isinstance(data, dict) else {"id": data}


def _link_case(api, req_id, case_id):
    r = api.post(f"/requirements/{req_id}/cases", json={"caseIds": [case_id]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"关联用例失败: {r.text[:300]}"


def _select_project(page, name):
    pass
    expect(page.locator('[data-test="coverage-page"]')).to_be_visible(timeout=15000)
    pick_project_in_header(page, name)
    expect(page.locator('[data-test="matrix-total"]')).to_have_text("2", timeout=15000)


def _to_coverage(page):
    pass
    # merge: the matrix now rides the reports page as a tab.
    nav_to(page, "统计报表", "/report", '[data-test="report-page-tabs"]', submenu="结果与缺陷")
    page.locator('[data-test="report-tab-coverage"]').first.click()
    expect(page.locator('[data-test="coverage-page"]')).to_be_visible(timeout=15000)


def test_q2_green_cell_to_case_list(logged_in, api_client, collectors):
    pass
    page = logged_in
    pid, name = _mk_project(api_client)
    req_a = _mk_requirement(api_client, pid, "需求A-登录直达", "认证")
    req_b = _mk_requirement(api_client, pid, "需求B-结算直达", "交易")
    case = _mk_case(api_client, pid, "用例-手机号登录直达")
    _link_case(api_client, req_a, case["id"])
    case_no = case["caseNo"]

    try:
        page.reload(wait_until="domcontentloaded")
        _to_coverage(page)
        _select_project(page, name)

        covered_row = page.locator('[data-test="matrix-table"] tbody tr:has-text("需求A-登录直达")')
        check = covered_row.locator('[data-test="matrix-cell-check"]').first
        expect(check).to_be_visible(timeout=10000)

        check.click()
        page.wait_for_url("**/#/cases**", timeout=15000)
        expect(page.locator('[data-test="case-filter-keyword"]')).to_have_value(case_no, timeout=10000)
        rows = page.locator('[data-test="case-table"] tbody tr')
        expect(rows.filter(has_text=case_no)).to_have_count(1, timeout=15000)

        assert_no_errors(collectors, "Q2 绿格直达用例列表")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_q2_red_cell_and_rowhead_direct(logged_in, api_client, collectors):
    pass
    page = logged_in
    pid, name = _mk_project(api_client)
    req_a = _mk_requirement(api_client, pid, "需求A-登录直达", "认证")
    _mk_requirement(api_client, pid, "需求B-结算直达", "交易")
    case = _mk_case(api_client, pid, "用例-手机号登录直达")
    _link_case(api_client, req_a, case["id"])

    try:
        page.reload(wait_until="domcontentloaded")
        _to_coverage(page)
        _select_project(page, name)

        uncovered_row = page.locator('[data-test="matrix-table"] tbody tr:has-text("需求B-结算直达")')
        red_cell = uncovered_row.locator('[data-test="matrix-cell-generate"]').first
        expect(red_cell).to_be_visible(timeout=10000)
        red_cell.click()
        page.wait_for_url("**/#/ai/generate**", timeout=15000)
        expect(page.locator('[data-test="ai-gen-form"]')).to_be_visible(timeout=10000)
        # T396b: deep link lands the header context (selector retired) — reload
        # refreshes the visible-domain snapshot before echoing the name.
        page.reload(wait_until="domcontentloaded")
        echo = page.locator('[data-test="owner-echo"]').first
        expect(echo).to_be_visible(timeout=10000)
        expect(echo.locator('[data-test="owner-echo-value"]')).to_have_text(name, timeout=10000)
        expect(page.locator('[data-test="ai-gen-requirement-select"]')).to_contain_text("需求B-结算直达", timeout=10000)

        page.goto(f"{BASE_URL}/#/coverage", wait_until="domcontentloaded")
        _select_project(page, name)
        req_link = page.locator('[data-test="matrix-req-link"]:has-text("需求A-登录直达")').first
        expect(req_link).to_be_visible(timeout=10000)
        req_link.click()
        page.wait_for_url("**/#/requirements**", timeout=15000)
        expect(page.locator('[data-test="req-filter-keyword"]')).to_have_value("需求A-登录直达", timeout=10000)
        expect(page.locator('[data-test="req-table"] tbody tr').filter(has_text="需求A-登录直达")).to_have_count(1, timeout=15000)
        expect(page.locator('[data-test="req-table"] tbody tr').filter(has_text="需求B-结算直达")).to_have_count(0)

        assert_no_errors(collectors, "Q2 红格+行头直达")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
