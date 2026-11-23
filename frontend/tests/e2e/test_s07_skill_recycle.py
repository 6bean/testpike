
import httpx
from conftest import (
    API_BASE,
    BASE_URL,
    api_login,
    assert_no_errors,
    inject_user_token,
    mk_tester,
    nav_to,
    row_cmd,
    sql_query,
)
from playwright.sync_api import expect


def _cleanup_skill(api, sid):
    pass
    body = api.get(f"/skills/{sid}").json()
    cur = body.get("data")
    if cur is None:
        return
    if cur["status"] == 2:
        cur = api.post(f"/skills/{sid}/approve?expectedVersion={cur['version']}").json()["data"]
    if cur["status"] == 3:
        cur = api.post(f"/skills/{sid}/retire?expectedVersion={cur['version']}").json()["data"]
    api.delete(f"/skills/{sid}?expectedVersion={cur['version']}")


def _search_row(page, name):
    page.fill('[data-test="skill-keyword"]', name)
    page.click('[data-test="skill-search"]')
    row = page.locator('[data-test="skill-table"] tbody tr').filter(has_text=name).first
    expect(row).to_be_visible(timeout=8000)
    return row


def _open_recycle_and_search(page, name):
    page.click('[data-test="skill-recycle-open"]')
    drawer = page.locator('[data-test="skill-recycle-drawer"]')
    expect(drawer).to_be_visible(timeout=8000)
    drawer.locator('[data-test="skill-recycle-search"]').fill(name)
    drawer.locator('[data-test="skill-recycle-search"]').press("Enter")
    row = drawer.locator('[data-test="skill-recycle-table"] tbody tr').filter(has_text=name).first
    expect(row).to_be_visible(timeout=8000)
    return row



def test_skill_recycle_restore_chain(logged_in, collectors, api_client, uniq):
    page = logged_in
    name = f"s07-recycle-{uniq}"
    sid = api_client.post("/skills", {
        "projectId": None, "name": name, "type": 1,
        "body": f"## 回收站夹具\n{name} T222 造数正文\n",
    }).json()["data"]["id"]
    try:
        nav_to(page, "SKILL 管理", "/skills", '[data-test="skill-list-page"]', submenu="AI 能力")
        row = _search_row(page, name)

        row_cmd(page, row, "remove")
        box = page.locator(".el-message-box")
        expect(box).to_be_visible(timeout=5000)
        expect(box).to_contain_text("进回收站", timeout=3000)
        box.locator('button:has-text("确定")').click()
        expect(page.locator(".el-message--success").first).to_be_visible(timeout=8000)

        page.goto(f"{BASE_URL}/#/skills", wait_until="domcontentloaded")
        expect(page.locator('[data-test="skill-list-page"]')).to_be_visible(timeout=10000)
        page.fill('[data-test="skill-keyword"]', name)
        page.click('[data-test="skill-search"]')
        gone = page.locator('[data-test="skill-table"] tbody tr').filter(has_text=name)
        expect(gone).to_have_count(0, timeout=8000)
        assert sql_query(f"SELECT deleted FROM tp_skill WHERE id={sid}") == "1", "DB 未钉 deleted=1"

        recycle_row = _open_recycle_and_search(page, name)
        expect(recycle_row).to_contain_text("系统管理员", timeout=5000)

        recycle_row.locator('[data-test^="skill-recycle-restore-"]').click()
        rbox = page.locator(".el-message-box")
        expect(rbox).to_be_visible(timeout=5000)
        expect(rbox).to_contain_text("确认还原", timeout=3000)
        with page.expect_response(
            lambda r: r.request.method == "PUT" and f"/api/v1/skills/{sid}/restore" in r.url
            and r.status == 200
        ):
            rbox.locator('button:has-text("还原")').click()
        expect(page.locator(".el-message--success").first).to_contain_text("已还原", timeout=8000)
        assert sql_query(f"SELECT deleted FROM tp_skill WHERE id={sid}") == "0", "restore 后 deleted 未翻转"

        page.keyboard.press("Escape")
        page.goto(f"{BASE_URL}/#/skills", wait_until="domcontentloaded")
        expect(page.locator('[data-test="skill-list-page"]')).to_be_visible(timeout=10000)
        back = _search_row(page, name)
        assert "草稿" in back.inner_text(), f"还原后状态应恢复草稿: {back.inner_text()}"
        assert_no_errors(collectors, "SKILL 回收站还原全链")
    finally:
        _cleanup_skill(api_client, sid)



def test_skill_recycle_member_denied(logged_in, collectors, api_client, uniq):
    page = logged_in
    user_id, _nick = mk_tester(api_client, uniq, prefix="s07")
    username = f"s07tester_{uniq}"
    token = api_login(username, "Test@12345")
    h = {"Authorization": f"Bearer {token}"}

    r = httpx.get(f"{API_BASE}/skills/recycle-bin", headers=h,
                  params={"page": 1, "size": 20}, timeout=15)
    assert r.status_code == 403, f"无键 recycle-bin 应 403: {r.status_code} {r.text[:200]}"
    assert "perm:tool:tool-market" in r.text or "tools 注册与市场" in r.text, \
        f"403 应人话点名键: {r.text[:200]}"
    r = httpx.put(f"{API_BASE}/skills/1/restore", headers=h,
                  params={"expectedVersion": 1}, timeout=15)
    assert r.status_code == 403, f"无键 restore 应 403: {r.status_code} {r.text[:200]}"

    inject_user_token(page, username, "Test@12345")
    page.reload(wait_until="domcontentloaded")
    nav_to(page, "SKILL 管理", "/skills", '[data-test="skill-list-page"]', submenu="AI 能力")
    expect(page.locator('[data-test="skill-list-page"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="skill-recycle-open"]')).to_have_count(0)
    assert_no_errors(collectors, "SKILL 回收站跨权限负例")

    dr = api_client.delete(f"/users/{user_id}")
    assert dr.json().get("code") == "0", f"测试用户清理失败: {dr.text[:200]}"
