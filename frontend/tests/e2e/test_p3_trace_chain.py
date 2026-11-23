
import uuid

from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, nav_to, row_cmd, sql_query



def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"P3项目_{uniq}", "code": f"P3T{uniq[:6].upper()}"})
    assert r.json().get("code") == "0", f"创建项目失败: {r.text[:200]}"
    return r.json()["data"], f"P3项目_{uniq}"


def _mk_requirement(api, pid, title):
    r = api.post(f"/projects/{pid}/requirements", json={"title": title})
    assert r.json().get("code") == "0", f"创建需求失败: {r.text[:200]}"
    return r.json()["data"]


def _mk_case(api, pid, title):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "priority": 1, "confidence": 2, "endType": "web",
        "steps": [{"stepNo": 1, "description": "打开登录页", "isAssert": 0}],
    })
    assert r.json().get("code") == "0", f"创建用例失败: {r.text[:200]}"
    return r.json()["data"]


def _link(api, req_id, case_id):
    r = api.post(f"/requirements/{req_id}/cases", json={"caseIds": [case_id]})
    assert r.json().get("code") == "0", f"关联失败: {r.text[:200]}"


def _mk_defect(api, pid, case_id, title, key):
    r = api.post("/defect-library", json={
        "projectId": pid, "caseId": case_id, "title": title, "severity": 1, "externalKey": key})
    assert r.json().get("code") == "0", f"创建缺陷失败: {r.text[:200]}"
    return r.json()["data"]


def _insert_batch_exec(api, pid, case_id, uniq):
    pass
    batch_name = f"冒烟批次_{uniq}"
    sql_query(
        f"INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type) "
        f"VALUES ({pid}, 'E2EP3-{uniq}', '{batch_name}', 3, 1)")
    sql_query(
        f"INSERT INTO tp_exec_task (project_id, case_id, batch_id, status, finished_at) "
        f"SELECT {pid}, {case_id}, id, 3, now() FROM tp_exec_batch "
        f"WHERE project_id = {pid} AND batch_no = 'E2EP3-{uniq}'")
    return batch_name


def _open_req_page(page):
    # Requirements leaf retired into the /extraction host tab (routes.ts AN-5
    # redirect); navigate the host leaf then switch tabs (k7 spectrum).
    nav_to(page, "需求提取", "/extraction", '[data-test="extraction-tabs"]', submenu="设计用例")
    page.locator('[data-test="extraction-tab-requirements"]').click()
    expect(page.locator('[data-test="project-ctx-select"]')).to_be_visible(timeout=10000)


def _select_req_project(page, project_name):
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=project_name).first
    expect(option).to_be_visible(timeout=5000)
    option.click()


def test_p3_trace_chain_forward_and_direct(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    req = _mk_requirement(api_client, pid, f"登录需求_{uniq}")
    orphan = _mk_requirement(api_client, pid, f"孤儿需求_{uniq}")
    case = _mk_case(api_client, pid, f"正向登录_{uniq}")
    _link(api_client, req["id"], case["id"])
    batch_name = _insert_batch_exec(api_client, pid, case["id"], uniq)
    defect = _mk_defect(api_client, pid, case["id"], f"登录崩溃_{uniq}", f"BUG-{uniq[:6].upper()}")
    case_no = case["caseNo"]

    try:
        page.reload(wait_until="domcontentloaded")
        _open_req_page(page)
        _select_req_project(page, pname)
        expect(page.locator('[data-test="req-table"]')).to_contain_text(f"登录需求_{uniq}", timeout=10000)

        orphan_row = page.locator('tbody tr.el-table__row').filter(has_text=f"孤儿需求_{uniq}").first
        row_cmd(page, orphan_row, "trace")
        expect(page.locator(".el-dialog:visible")).to_contain_text("追溯链", timeout=5000)
        expect(page.locator('[data-test="trace-empty"]').first).to_be_visible(timeout=5000)
        page.locator(".el-dialog:visible .el-dialog__headerbtn").first.click()

        row = page.locator('tbody tr.el-table__row').filter(has_text=f"登录需求_{uniq}").first
        row_cmd(page, row, "trace")
        panel = page.locator('[data-test="trace-panel"]').first
        expect(panel).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="trace-root-requirement"]').first).to_contain_text(
            f"登录需求_{uniq}", timeout=5000)
        case_node = page.locator('[data-test="trace-node-case"]').first
        expect(case_node).to_contain_text(case_no)
        expect(case_node).to_contain_text(batch_name)
        expect(case_node).to_contain_text("成功")
        defect_node = page.locator('[data-test="trace-node-defect"]').first
        expect(defect_node).to_contain_text(f"BUG-{uniq[:6].upper()}")
        expect(defect_node).to_contain_text("新建")

        defect_node.locator(".el-link").first.click()
        page.wait_for_url("**/defects**", timeout=15000)
        expect(page.locator('[data-test="defect-filter-keyword"]')).to_have_value(
            f"登录崩溃_{uniq}", timeout=10000)
        expect(page.locator('[data-test="defect-table"], .el-table').first).to_contain_text(
            f"BUG-{uniq[:6].upper()}", timeout=10000)

        page.go_back()
        expect(panel).to_be_visible(timeout=10000)
        expect(panel).to_contain_text(case_no, timeout=10000)
        page.locator('[data-test="trace-node-case"]').first.locator(".el-link").first.click()
        page.wait_for_url("**/cases/**", timeout=15000)
        expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="case-trace-block"]')).to_contain_text(
            f"登录需求_{uniq}", timeout=10000)
        expect(page.locator('[data-test="case-trace-block"]')).to_contain_text(
            f"BUG-{uniq[:6].upper()}")
        assert_no_errors(collectors, "P3 正向追溯链直达")
        del defect
    finally:
        sql_query(f"DELETE FROM tp_exec_task WHERE project_id={pid}")
        sql_query(f"DELETE FROM tp_exec_batch WHERE project_id={pid}")
        api_client.delete(f"/projects/{pid}/restore")
        r = api_client.delete(f"/projects/{pid}/purge?force=true")
        assert r.status_code in (200, 204, 404) or r.json().get("success"), f"清理失败: {r.text[:200]}"


def test_p3_trace_chain_reverse_case_page(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    req = _mk_requirement(api_client, pid, f"结算需求_{uniq}")
    case = _mk_case(api_client, pid, f"被链用例_{uniq}")
    _link(api_client, req["id"], case["id"])
    _insert_batch_exec(api_client, pid, case["id"], uniq)

    try:
        page.reload(wait_until="domcontentloaded")
        expect(page.locator(".dashboard-root")).to_be_visible(timeout=15000)
        page.goto(f"{BASE_URL}/#/cases/{case['id']}", wait_until="domcontentloaded")
        block = page.locator('[data-test="case-trace-block"]')
        expect(block).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="trace-root-case"]').first).to_contain_text(case["caseNo"])
        expect(page.locator('[data-test="trace-root-exec"]').first).to_contain_text("冒烟批次")
        req_node = page.locator('[data-test="trace-node-requirement"]').first
        expect(req_node).to_contain_text(f"结算需求_{uniq}")
        expect(req_node).to_contain_text("活跃")

        req_node.locator(".el-link").first.click()
        page.wait_for_url("**/requirements**", timeout=15000)
        expect(page.locator('[data-test="req-filter-keyword"]')).to_have_value(
            f"结算需求_{uniq}", timeout=10000)
        expect(page.locator('[data-test="req-table"]')).to_contain_text(
            f"结算需求_{uniq}", timeout=10000)
        assert_no_errors(collectors, "P3 反向追溯链直达")
    finally:
        sql_query(f"DELETE FROM tp_exec_task WHERE project_id={pid}")
        sql_query(f"DELETE FROM tp_exec_batch WHERE project_id={pid}")
        api_client.delete(f"/projects/{pid}/restore")
        r = api_client.delete(f"/projects/{pid}/purge?force=true")
        assert r.status_code in (200, 204, 404) or r.json().get("success"), f"清理失败: {r.text[:200]}"
