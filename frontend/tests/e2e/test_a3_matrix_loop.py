
import re
import time

from playwright.sync_api import expect
import pytest

from conftest import pick_project_in_header, BASE_URL, assert_no_errors, nav_to, sql_query


GEN_SETTLE_TIMEOUT = 300000


def _mk_project(api, uniq):
    name = f"A3闭环_{uniq}"
    code = f"A3C{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"], name


def _mk_requirement(api, pid, title, chapter, criteria):
    r = api.post(f"/projects/{pid}/requirements", json={
        "source": 3, "title": title, "acceptanceCriteria": criteria, "chapter": chapter,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建需求失败: {r.text[:300]}"
    data = r.json()["data"]
    return data["id"] if isinstance(data, dict) else data


def _to_coverage(page):
    pass
    # merge: the matrix now rides the reports page as a tab.
    nav_to(page, "统计报表", "/report", '[data-test="report-page-tabs"]', submenu="结果与缺陷")
    page.locator('[data-test="report-tab-coverage"]').first.click()
    expect(page.locator('[data-test="coverage-page"]')).to_be_visible(timeout=15000)


def _select_project(page, name):
    expect(page.locator('[data-test="coverage-page"]')).to_be_visible(timeout=15000)
    pick_project_in_header(page, name)
    expect(page.locator('[data-test="matrix-total"]')).to_have_text("1", timeout=15000)


def _retry3(fn, timeout=20):
    pass
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            return fn()
        except AssertionError as e:
            last = e
            time.sleep(1)
    raise last


@pytest.mark.timeout(480)
def test_a3_matrix_generate_loop(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    req_title = f"用户登录功能校验-{uniq}"
    rid = _mk_requirement(api_client, pid, req_title, "认证",
                          "输入正确账号密码可登录成功")

    try:
        page.reload(wait_until="domcontentloaded")
        _to_coverage(page)
        _select_project(page, pname)
        expect(page.locator('[data-test="matrix-covered"]')).to_have_text("0")
        expect(page.locator('[data-test="matrix-uncovered"]')).to_have_text("1")

        uncovered_row = page.locator(f'[data-test="matrix-table"] tbody tr:has-text("{req_title}")')
        gen_btn = uncovered_row.locator('[data-test="matrix-generate-btn"]').first
        expect(gen_btn).to_be_visible(timeout=10000)
        gen_btn.click()
        page.wait_for_url("**/#/ai/generate**", timeout=15000)
        expect(page.locator('[data-test="ai-gen-form"]')).to_be_visible(timeout=10000)
        # T396b: deep link landed the header context (selector retired) — reload
        # refreshes the visible-domain snapshot, then the echo carries the name.
        page.reload(wait_until="domcontentloaded")
        echo = page.locator('[data-test="owner-echo"]').first
        expect(echo).to_be_visible(timeout=10000)
        expect(echo.locator('[data-test="owner-echo-value"]')).to_have_text(pname, timeout=10000)
        expect(page.locator('[data-test="ai-gen-requirement-select"]')).to_contain_text(req_title, timeout=10000)

        submit = page.locator('[data-test="ai-gen-submit"]')
        expect(submit).to_be_enabled(timeout=10000)
        submit.click()
        expect(page.locator(".el-message").filter(has_text="生成任务已提交")).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="ai-gen-task-status"]')).to_contain_text("已完成",
                                                                                 timeout=GEN_SETTLE_TIMEOUT)
        expect(page.locator('[data-test="ai-gen-open-case"]')).to_be_visible(timeout=10000)

        back_btn = page.locator('[data-test="ai-gen-back-matrix"]')
        expect(back_btn).to_be_visible(timeout=10000)

        link = _retry3(lambda: _requirement_case_link(rid))
        case_id, case_no = link

        # merge: /coverage now resolves to the reports page coverage tab.
        back_btn.click()
        page.wait_for_url("**/#/report**", timeout=15000)
        expect(page).to_have_url(re.compile(rf".*/#/report\?projectId={pid}&tab=coverage$"))
        _select_project_no_drift(page, pid, pname)
        expect(page.locator('[data-test="matrix-covered"]')).to_have_text("1", timeout=20000)
        expect(page.locator('[data-test="matrix-uncovered"]')).to_have_text("0", timeout=20000)
        expect(page.locator('[data-test="matrix-table"]')).to_contain_text(case_no, timeout=15000)
        row = page.locator(f'[data-test="matrix-table"] tbody tr:has-text("{req_title}")')
        check = row.locator('[data-test="matrix-cell-check"]').first
        expect(check).to_be_visible(timeout=10000)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="coverage-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="coverage-page"]')).to_contain_text(pname, timeout=10000)
        expect(page.locator('[data-test="matrix-covered"]')).to_have_text("1", timeout=15000)
        expect(row.locator('[data-test="matrix-cell-check"]').first).to_be_visible(timeout=10000)

        tid = _task_id_of_case(case_id)
        page.goto(f"{BASE_URL}/#/ai/generate?task={tid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="ai-gen-task-status"]')).to_contain_text("已完成", timeout=15000)
        expect(page.locator('[data-test="ai-gen-open-case"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="ai-gen-back-matrix"]')).to_have_count(0)

        assert_no_errors(collectors, "A3 覆盖补齐回跳闭环")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")


def _requirement_case_link(req_id):
    pass
    out = sql_query(
        f"SELECT c.id || '|' || c.case_no FROM tp_case_requirement cr "
        f"JOIN tp_test_case c ON c.id = cr.case_id "
        f"WHERE cr.requirement_id = {req_id} AND c.deleted = 0"
    ).strip()
    rows = [line for line in out.splitlines() if "|" in line]
    assert len(rows) == 1, f"自动建链应恰 1 行，实得: {out!r}"
    cid, cno = rows[0].split("|")
    return int(cid), cno


def _select_project_no_drift(page, pid, pname):
    pass
    expect(page.locator('[data-test="coverage-page"]')).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="coverage-page"]')).to_contain_text(pname, timeout=10000)


def _task_id_of_case(case_id):
    pass
    out = sql_query(f"SELECT id FROM tp_ai_task WHERE case_id = {case_id} ORDER BY id DESC LIMIT 1").strip()
    assert out.isdigit(), f"用例 {case_id} 未反查到生成任务: {out!r}"
    return int(out)
