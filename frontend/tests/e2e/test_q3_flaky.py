
import httpx
from playwright.sync_api import expect

from conftest import assert_no_errors, nav_to, row_cmd, sql_query


CORE_BASE = "http://localhost:8083"
CASES_MENU = "用例管理"


def _mk_project(api, uniq):
    name = f"Q3隔离_{uniq}"
    r = api.post("/projects", json={"name": name, "code": f"Q3F{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"], name


def _mk_case(api, pid, title):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "前置", "expectedResult": "通过",
        "priority": 1, "confidence": 2, "endType": "web", "phase": 2, "status": 3,
        "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]


def _seed_exec(pid, case_id, status, minutes_ago):
    pass
    sql_query(
        "INSERT INTO tp_exec_task (project_id, case_id, status, finished_at, created_at) "
        f"VALUES ({pid}, {case_id}, {status}, now() - interval '{minutes_ago} minutes', "
        f"now() - interval '{minutes_ago} minutes')")


def _detect():
    r = httpx.post(f"{CORE_BASE}/internal/cases/detect-flaky", timeout=30)
    assert r.status_code == 200 and r.json().get("code") == "0", f"检测触发失败: {r.text[:300]}"
    return r.json()["data"]


def _to_case_list(page, pid, project_name, keyword=None):
    pass
    page.reload(wait_until="domcontentloaded")
    expect(page.get_by_text(project_name).first).to_be_visible(timeout=15000)
    url = f"#/cases?projectId={pid}"
    if keyword:
        url += f"&keyword={keyword}"
    page.goto(f"{page.url.split('#')[0]}{url}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="case-table"]')).to_be_visible(timeout=15000)


def test_q3_detect_badge_and_tooltip(logged_in, api_client, collectors):
    pass
    page = logged_in
    import uuid
    uniq = uuid.uuid4().hex[:8]
    pid, pname = _mk_project(api_client, uniq)
    flips = _mk_case(api_client, pid, f"登录偶发失败_{uniq}")
    stable = _mk_case(api_client, pid, f"稳定通过用例_{uniq}")
    try:
        _seed_exec(pid, flips["id"], 3, 30)
        _seed_exec(pid, flips["id"], 4, 20)
        _seed_exec(pid, flips["id"], 3, 10)
        for m in (30, 20, 10):
            _seed_exec(pid, stable["id"], 3, m)

        result = _detect()
        assert result["marked"] >= 1, f"检测未标记: {result}"

        _to_case_list(page, pid, pname, flips["caseNo"])
        row = page.locator('[data-test="case-table"] tbody tr').filter(has_text=flips["caseNo"])
        expect(row).to_have_count(1, timeout=15000)
        badge = row.locator('[data-test="case-flaky-badge"]')
        expect(badge).to_be_visible(timeout=10000)
        expect(badge).to_contain_text("不稳定")

        badge.hover()
        tip = page.locator('.el-popper:has-text("翻转 2 次")').first
        expect(tip).to_be_visible(timeout=5000)
        expect(tip).to_contain_text("判定为不稳定用例")

        page.locator('[data-test="case-filter-keyword"]').fill(stable["caseNo"])
        page.locator('button:has-text("查询")').first.click()
        srow = page.locator('[data-test="case-table"] tbody tr').filter(has_text=stable["caseNo"])
        expect(srow).to_have_count(1, timeout=15000)
        expect(srow.locator('[data-test="case-flaky-badge"]')).to_have_count(0)

        assert_no_errors(collectors, "Q3 检测黄标+hover 理由")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_q3_one_click_isolate_release_persist(logged_in, api_client, collectors):
    pass
    page = logged_in
    import uuid
    uniq = uuid.uuid4().hex[:8]
    pid, pname = _mk_project(api_client, uniq)
    case = _mk_case(api_client, pid, f"待隔离用例_{uniq}")
    try:
        _to_case_list(page, pid, pname, case["caseNo"])
        row = page.locator('[data-test="case-table"] tbody tr').filter(has_text=case["caseNo"])
        expect(row).to_have_count(1, timeout=15000)

        row_cmd(page, row.first, "flaky-isolate")
        expect(page.locator(".el-message").filter(has_text="已隔离")).to_be_visible(timeout=5000)
        expect(row.locator('[data-test="case-flaky-badge"]')).to_be_visible(timeout=10000)

        _to_case_list(page, pid, pname, case["caseNo"])
        row = page.locator('[data-test="case-table"] tbody tr').filter(has_text=case["caseNo"])
        expect(row.locator('[data-test="case-flaky-badge"]')).to_be_visible(timeout=10000)
        d = api_client.get(f"/cases/{case['id']}").json()["data"]
        assert d["flaky"] == 1 and "不稳定" in (d["flakyReason"] or ""), f"API 隔离态: {d}"

        row_cmd(page, row.first, "flaky-release")
        expect(page.locator(".el-message").filter(has_text="已解除隔离")).to_be_visible(timeout=5000)
        _to_case_list(page, pid, pname, case["caseNo"])
        row = page.locator('[data-test="case-table"] tbody tr').filter(has_text=case["caseNo"])
        expect(row.locator('[data-test="case-flaky-badge"]')).to_have_count(0, timeout=10000)
        row.first.locator('[data-test="ra-more"]').click()
        expect(page.locator('[data-test="ra-flaky-isolate"]:visible').first).to_be_visible(timeout=10000)
        page.keyboard.press("Escape")
        d2 = api_client.get(f"/cases/{case['id']}").json()["data"]
        assert d2["flaky"] == 0 and d2["flakyReason"] is None, f"API 解除态: {d2}"

        assert_no_errors(collectors, "Q3 一键隔离/解除持久化")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
