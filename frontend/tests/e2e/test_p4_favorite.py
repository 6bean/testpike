
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, nav_to


def _expect_toast(page, text):
    pass
    expect(page.locator(".el-message").filter(has_text=text).last).to_be_visible(timeout=5000)




def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"P4项目_{uniq}", "code": f"P4T{uniq[:6].upper()}"})
    assert r.json().get("code") == "0", f"创建项目失败: {r.text[:200]}"
    return r.json()["data"], f"P4项目_{uniq}"


def _mk_case(api, pid, title):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "priority": 1, "confidence": 2, "endType": "web",
        "steps": [{"stepNo": 1, "description": "打开登录页", "isAssert": 0}],
    })
    assert r.json().get("code") == "0", f"创建用例失败: {r.text[:200]}"
    return r.json()["data"]


def _fav_count(api, case_id):
    r = api.get("/favorites?entityType=case")
    assert r.json().get("code") == "0", f"收藏列表失败: {r.text[:200]}"
    return sum(1 for i in r.json()["data"] if i["entityId"] == case_id)


def test_p4_favorite_detail_and_workbench(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, _pname = _mk_project(api_client, uniq)
    case = _mk_case(api_client, pid, f"收藏用例_{uniq}")

    try:
        page.goto(f"{BASE_URL}/#/cases/{case['id']}", wait_until="domcontentloaded")
        star = page.locator(f'[data-test="favorite-case-{case["id"]}"]')
        expect(star).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="favorite-star-off"]').first).to_be_visible(timeout=5000)
        star.click()
        _expect_toast(page, "已收藏")
        expect(page.locator('[data-test="favorite-star-on"]').first).to_be_visible(timeout=5000)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="favorite-star-on"]').first).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=10000)

        r = api_client.put(f"/favorites/case/{case['id']}")
        assert r.json().get("code") == "0", f"重复收藏应幂等成功: {r.text[:200]}"
        assert _fav_count(api_client, case["id"]) == 1, "重复收藏产生多行！"

        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        section = page.locator('[data-test="favorite-section"]')
        expect(section).to_be_visible(timeout=10000)
        fav_item = page.locator('[data-test="favorite-item"]').filter(
            has_text=f"收藏用例_{uniq}")
        expect(fav_item.first).to_be_visible(timeout=5000)
        fav_item.first.click()
        page.wait_for_url("**/cases/**", timeout=15000)
        expect(page.locator('[data-test="detail-title"]')).to_contain_text(
            f"收藏用例_{uniq}", timeout=10000)

        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        recent = page.locator('[data-test="recent-item"]').filter(has_text=f"收藏用例_{uniq}")
        expect(recent.first).to_be_visible(timeout=5000)

        page.goto(f"{BASE_URL}/#/cases/{case['id']}", wait_until="domcontentloaded")
        expect(star).to_be_visible(timeout=10000)
        star.click()
        _expect_toast(page, "已取消收藏")
        expect(page.locator('[data-test="favorite-star-off"]').first).to_be_visible(timeout=5000)
        assert _fav_count(api_client, case["id"]) == 0, "取消后收藏行仍存在！"
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        expect(page.locator('[data-test="favorite-section"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="favorite-item"]').filter(
            has_text=f"收藏用例_{uniq}")).to_have_count(0, timeout=5000)

        assert_no_errors(collectors, "P4 收藏详情+工作台直达")
    finally:
        api_client.delete(f"/favorites/case/{case['id']}")
        r = api_client.delete(f"/projects/{pid}/purge?force=true")
        assert r.status_code in (200, 204, 404) or r.json().get("success"), f"清理失败: {r.text[:200]}"


def test_p4_favorite_list_star_and_project_page(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    case = _mk_case(api_client, pid, f"列表收藏_{uniq}")

    try:
        page.reload(wait_until="domcontentloaded")
        expect(page.locator(".dashboard-root")).to_be_visible(timeout=15000)
        nav_to(page, "用例管理", "/cases", '[data-test="case-table"]', submenu="设计用例")
        page.locator('[data-test="project-ctx-select"]').click()
        option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(
            has_text=f"P4项目_{uniq}").first
        expect(option).to_be_visible(timeout=5000)
        option.click()
        row = page.locator("tbody tr.el-table__row").filter(
            has_text=f"列表收藏_{uniq}").first
        expect(row).to_be_visible(timeout=10000)

        row_star = row.locator(f'[data-test="favorite-case-{case["id"]}"]')
        expect(row_star).to_be_visible(timeout=5000)
        row_star.click()
        _expect_toast(page, "已收藏")
        assert _fav_count(api_client, case["id"]) == 1, "列表星标未落库！"

        page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
        proj_star = page.locator(f'[data-test="favorite-project-{pid}"]')
        expect(proj_star).to_be_visible(timeout=10000)
        proj_star.click()
        _expect_toast(page, "已收藏")

        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        expect(page.locator('[data-test="favorite-item"]').filter(
            has_text=f"列表收藏_{uniq}")).to_be_visible(timeout=5000)
        proj_item = page.locator('[data-test="favorite-item"]').filter(has_text=pname).first
        expect(proj_item).to_be_visible(timeout=5000)
        proj_item.click()
        page.wait_for_url("**/projects/**", timeout=15000)
        expect(page.locator('[data-test="favorite-star-on"]').first).to_be_visible(timeout=10000)

        assert_no_errors(collectors, "P4 列表星标+项目直达")
    finally:
        api_client.delete(f"/favorites/case/{case['id']}")
        api_client.delete(f"/favorites/project/{pid}")
        r = api_client.delete(f"/projects/{pid}/purge?force=true")
        assert r.status_code in (200, 204, 404) or r.json().get("success"), f"清理失败: {r.text[:200]}"
