
import httpx
from playwright.sync_api import expect

from conftest import (API_BASE, ADMIN_PASSWORD, ADMIN_USERNAME, BASE_URL,
                      add_member, api_login, assert_no_errors, attach_monitors,
                      inject_user_token, mk_tester)


PWD = "Test@12345"


def _mk_project_with_coverage(api, uniq, tag, covered=True):
    pass
    r = api.post("/projects", json={"name": f"P6cmp{tag}_{uniq}", "code": f"P6C{tag}{uniq}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    r = api.post(f"/projects/{pid}/requirements",
                 json={"title": f"P6需求{tag}_{uniq}", "source": 3})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建需求失败: {r.text[:300]}"
    req_id = r.json()["data"]["id"]
    r = api.post("/cases", json={
        "projectId": pid, "title": f"P6用例{tag}_{uniq}",
        "precondition": "前置：对比夹具", "expectedResult": "预期：覆盖率非零",
        "priority": 0, "confidence": 1, "endType": "web", "status": 1,
        "steps": [{"stepNo": 1, "description": "打开对比页", "isAssert": 0}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    case_id = r.json()["data"]["id"]
    if covered:
        r = api.post(f"/requirements/{req_id}/cases", json={"caseIds": [case_id]})
        assert r.status_code == 200 and r.json().get("code") == "0", f"关联失败: {r.text[:300]}"
    return pid


def _pick_option(page, name_text):
    pass
    page.locator('[data-test="compare-project-select"]').click()
    popper = page.locator(".el-select-dropdown:visible")
    expect(popper).to_be_visible(timeout=5000)
    page.keyboard.type(name_text)
    item = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible", has_text=name_text).first
    item.wait_for(timeout=8000)
    item.click()
    page.keyboard.press("Escape")


def test_h6_compare_render(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in

    pid_a = _mk_project_with_coverage(api_client, uniq, "A")
    pid_b = _mk_project_with_coverage(api_client, uniq, "B")
    name_a = f"P6cmpA_{uniq}"
    name_b = f"P6cmpB_{uniq}"
    pid_c = _mk_project_with_coverage(api_client, uniq, "C", covered=False)
    name_c = f"P6cmpC_{uniq}"
    r = api_client.delete(f"/projects/{pid_c}")
    assert r.status_code == 200 and r.json().get("code") == "0", f"归档失败: {r.text[:300]}"

    inject_user_token(page, ADMIN_USERNAME, ADMIN_PASSWORD)
    page.goto(f"{BASE_URL}/#/report?projectId={pid_a}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="report-tabs"]')).to_be_visible(timeout=10000)

    page.locator('[data-test="report-tabs"] [role="tab"]', has_text="跨项目对比").click()
    expect(page.locator('[data-test="compare-toolbar"]')).to_be_visible(timeout=5000)
    expect(page.locator("text=选择至少 2 个项目")).to_be_visible(timeout=3000)

    _pick_option(page, name_a)
    _pick_option(page, name_b)
    _pick_option(page, f"{name_c}（已归档）")

    with page.expect_response(
        lambda r: "/reports/compare" in r.url and r.request.method == "GET",
        timeout=15000,
    ) as cmp_info:
        page.locator('[data-test="compare-run-btn"]').click()
    assert cmp_info.value.status == 200, f"合法对比应 200，实际 {cmp_info.value.status}"
    table = page.locator('[data-test="compare-table"]')
    expect(table).to_be_visible(timeout=8000)
    expect(page.locator('[data-test="compare-project-name"]', has_text=name_a)).to_be_visible()
    expect(page.locator('[data-test="compare-project-name"]', has_text=name_b)).to_be_visible()
    row_c = page.locator(".el-table__row", has_text=name_c)
    expect(row_c).to_be_visible()
    expect(row_c.locator('[data-test="compare-archived-tag"]')).to_be_visible()

    row_a = page.locator(".el-table__row", has_text=name_a)
    sub_a = row_a.locator(".compare-sub").first.inner_text()  # "1 / 1"
    assert "1 / 1" in sub_a, f"A 覆盖率分数应 1/1，实际 {sub_a!r}"
    rate_a = row_a.locator('[data-test="compare-cov-rate"]').inner_text()
    assert rate_a == "100%", f"A 覆盖率应 100%，实际 {rate_a!r}"
    page.locator('[data-test="report-tabs"] [role="tab"]', has_text="覆盖率").click()
    page.locator('[data-test="project-ctx-select"]').click()
    page.keyboard.type(name_a)
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible", has_text=name_a).first.click()
    expect(page.locator('[data-test="cov-covered"]')).to_have_text("1", timeout=8000)
    expect(page.locator('[data-test="cov-total"]')).to_have_text("1", timeout=3000)
    expect(page.locator('[data-test="cov-rate"]')).to_have_text("100%", timeout=3000)

    assert_no_errors(collectors, "TC-H6-01 compare render")

    for pid in (pid_a, pid_b):
        api_client.delete(f"/projects/{pid}")
    for pid in (pid_a, pid_b, pid_c):
        r = httpx.request("DELETE", f"{API_BASE}/projects/{pid}/purge?force=true",
                          headers=api_client.headers, timeout=10)
        assert r.status_code == 200, f"purge {pid} 失败: {r.text[:200]}"


def test_h6_compare_denied_403_and_audit(api_client, page, collectors, uniq):
    pass
    pid_visible = _mk_project_with_coverage(api_client, uniq, "V")
    pid_visible2 = _mk_project_with_coverage(api_client, uniq, "V2")
    pid_secret = _mk_project_with_coverage(api_client, uniq, "S")
    t_id, _nick = mk_tester(api_client, uniq, prefix="h6")
    add_member(api_client, pid_visible, t_id)
    add_member(api_client, pid_visible2, t_id)
    username = f"h6tester_{uniq}"

    token = api_login(username, PWD)
    h = {"Authorization": f"Bearer {token}"}
    r = httpx.get(f"{API_BASE}/reports/compare",
                  params={"projectIds": f"{pid_visible},{pid_secret}"}, headers=h, timeout=15)
    assert r.status_code == 403, f"越权对比应 HTTP 403，实际 {r.status_code}: {r.text[:200]}"
    body = r.json()
    assert body.get("code") == "40301", f"业务码应 40301: {body}"
    assert "无权" in (body.get("msg") or "") and str(pid_secret) in body.get("msg", ""), \
        f"403 应人话点名越权项目 ID: {body.get('msg')}"

    inject_user_token(page, username, PWD)
    page.goto(f"{BASE_URL}/#/report?projectId={pid_visible}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="report-tabs"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="report-tabs"] [role="tab"]', has_text="跨项目对比").click()
    expect(page.locator('[data-test="compare-toolbar"]')).to_be_visible(timeout=5000)
    page.locator('[data-test="compare-project-select"]').click()
    page.keyboard.type(f"P6cmpS_{uniq}")
    expect(page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible", has_text=f"P6cmpS_{uniq}")).to_have_count(0, timeout=5000)
    page.keyboard.press("Escape")
    _pick_option(page, f"P6cmpV_{uniq}")
    _pick_option(page, f"P6cmpV2_{uniq}")

    def _tamper_add_secret(route):
        url = route.request.url
        marker = f"projectIds={pid_visible}"
        if marker in url and str(pid_secret) not in url:
            url = url.replace(marker, f"{marker},{pid_secret}")
        route.continue_(url=url)

    page.route("**/reports/compare**", _tamper_add_secret)
    with page.expect_response(
        lambda r: "/reports/compare" in r.url and r.request.method == "GET",
        timeout=15000,
    ) as deny_info:
        page.locator('[data-test="compare-run-btn"]').click()
    page.unroute("**/reports/compare**", _tamper_add_secret)
    assert deny_info.value.status == 403, f"UI 请求应 403，实际 {deny_info.value.status}"
    banner = page.locator('[data-test="compare-error"]')
    expect(banner).to_be_visible(timeout=5000)
    expect(banner).to_contain_text("无权")
    expect(banner).to_contain_text(str(pid_secret))
    expect(page.locator('[data-test="compare-table"]')).to_have_count(0)
    assert "/report" in page.url, f"403 后应停留报表页，实际 {page.url}"

    admin_h = api_client.headers
    ra = httpx.get(f"{API_BASE}/audit-logs",
                   params={"objectType": "report", "action": "ACCESS_DENIED",
                           "objectId": "compare", "page": 1, "size": 50},
                   headers=admin_h, timeout=10)
    assert ra.status_code == 200, f"审计查询应 200: {ra.text[:200]}"
    logs = ra.json()["data"]["list"]
    mine = [x for x in logs if str(pid_secret) in (x.get("beforeJson") or "")
            and x.get("operatorId") == t_id]
    assert len(mine) >= 1, \
        f"应存在 tester(id={t_id}) 对 P3={pid_secret} 的 ACCESS_DENIED 审计行: {len(logs)} 行未见"

    collectors["network_4xx"] = [
        e for e in collectors["network_4xx"]
        if not (e["status"] == 403 and ("/reports/compare" in e["url"]
                                        or "/reports/team-board" in e["url"]))
    ]
    collectors["console_errors"] = [
        e for e in collectors["console_errors"] if "status of 403" not in e
    ]
    assert_no_errors(collectors, "TC-H6-02 compare denied")

    for pid in (pid_visible, pid_visible2, pid_secret):
        api_client.delete(f"/projects/{pid}")
    for pid in (pid_visible, pid_visible2, pid_secret):
        r = httpx.request("DELETE", f"{API_BASE}/projects/{pid}/purge?force=true",
                          headers=admin_h, timeout=10)
        assert r.status_code == 200, f"purge {pid} 失败: {r.text[:200]}"
    r = httpx.delete(f"{API_BASE}/users/{t_id}",
                     headers=admin_h, timeout=10)
    assert r.status_code == 200, f"删 tester 失败: {r.text[:200]}"
