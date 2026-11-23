

from playwright.sync_api import expect

from conftest import pick_project_in_header, assert_no_errors, sql_query, _set_sys_config_value, get_sys_config_value, BASE_URL


def _make_project(api, uniq: str) -> int:
    r = api.post("/projects", json={"name": f"B9M探索_{uniq[:10]}", "code": f"BM{uniq[:8].upper()}"})
    assert r.status_code == 200, f"创建项目失败: {r.text[:200]}"
    return r.json()["data"]


def _make_env(api, pid: int, name: str, env_type: int) -> int:
    r = api.post(f"/projects/{pid}/envs", json={"name": name, "envType": env_type, "baseUrl": "http://localhost:8899"})
    assert r.status_code == 200, f"创建环境失败: {r.text[:200]}"
    data = r.json()["data"]
    return data["id"] if isinstance(data, dict) else data


def _cleanup(pid: int) -> None:
    rows = sql_query(f"SELECT id FROM tp_explore_session WHERE project_id = {pid}").strip()
    for sid in [s.strip() for s in rows.splitlines() if s.strip() and s.strip().isdigit()]:
        sql_query(f"DELETE FROM tp_explore_step WHERE session_id = {sid}")
        sql_query(f"DELETE FROM tp_explore_session WHERE id = {sid}")
    sql_query(f"DELETE FROM tp_project_env WHERE project_id = {pid}")
    sql_query(f"DELETE FROM tp_user_project WHERE project_id = {pid}")
    sql_query(f"DELETE FROM tp_project WHERE id = {pid}")


def _pick_option(page, anchor: str, keyword: str | None = None):
    pass
    page.locator(anchor).click()
    opts = page.locator(".el-select-dropdown:visible .el-select-dropdown__item")
    expect(opts.first).to_be_visible(timeout=5000)
    if keyword:
        opts.filter(has_text=keyword).first.click()
    else:
        opts.first.click()



def test_explore_app_mode_form(logged_in, collectors, api_client, uniq):
    page = logged_in
    pid = _make_project(api_client, uniq)
    try:
        env_id = _make_env(api_client, pid, f"DEV{uniq[:6]}", 1)
        _make_env(api_client, pid, f"STG{uniq[:6]}", 3)

        page.goto(f"{BASE_URL}/#/explore", wait_until="domcontentloaded")
        expect(page.locator('[data-test="explore-list-page"]')).to_be_visible(timeout=10000)
        pick_project_in_header(page, f"B9M探索_{uniq[:10]}")

        page.locator('[data-test="explore-create"]').click()
        dialog = page.locator('[data-test="explore-create-dialog"]')
        expect(dialog).to_be_visible()

        expect(page.locator('[data-test="explore-start-url"]')).to_be_visible()
        assert page.locator('[data-test="explore-env"]').count() == 0

        dialog.get_by_text("App（移动形态）").click()
        expect(page.locator('[data-test="explore-app-package"]')).to_be_visible()
        expect(page.locator('[data-test="explore-env"]')).to_be_visible()
        expect(page.locator('[data-test="explore-cred"]')).to_be_visible()
        assert page.locator('[data-test="explore-start-url"]').count() == 0

        page.locator('[data-test="explore-env"]').click()
        menu = page.locator(".el-select-dropdown:visible")
        expect(menu.locator(f'.el-select-dropdown__item:has-text("DEV{uniq[:6]}")')).to_be_visible(timeout=5000)
        assert menu.locator(f'.el-select-dropdown__item:has-text("STG{uniq[:6]}")').count() == 0, \
            "预发环境不该出现在 App 探索环境白名单下拉"
        menu.locator(".el-select-dropdown__item").first.click()
        expect(page.locator('[data-test="explore-env"]')).to_contain_text(f"DEV{uniq[:6]}")

        assert_no_errors(collectors, "explore App mode form")
        assert env_id > 0
    finally:
        _cleanup(pid)



def test_explore_app_gate_reject_message(logged_in, collectors, api_client, uniq):
    page = logged_in
    pid = _make_project(api_client, uniq)
    # The gate-rejection leg asserts OFF-state wording, so the switch is pinned
    # closed inside the case: the factory seed flipped to ON with the AI
    # default-on batch and relying on that implicit shape is a debt .
    switch_pre = get_sys_config_value("ai.explore.appauto") or "true"
    _set_sys_config_value("ai.explore.appauto", "false")
    try:
        _make_env(api_client, pid, f"DEV{uniq[:6]}", 1)

        page.goto(f"{BASE_URL}/#/explore", wait_until="domcontentloaded")
        expect(page.locator('[data-test="explore-list-page"]')).to_be_visible(timeout=10000)
        pick_project_in_header(page, f"B9M探索_{uniq[:10]}")

        page.locator('[data-test="explore-create"]').click()
        dialog = page.locator('[data-test="explore-create-dialog"]')
        expect(dialog).to_be_visible()
        dialog.get_by_text("App（移动形态）").click()
        pkg_input = page.locator('[data-test="explore-app-package"] input')
        pkg_input.click()
        pkg_input.fill("com.example.demo")
        created_opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item:has-text("com.example.demo")').first
        expect(created_opt).to_be_visible(timeout=5000)
        created_opt.click()
        _pick_option(page, '[data-test="explore-env"]', f"DEV{uniq[:6]}")
        page.locator('[data-test="explore-goal"]').fill(f"移动形态关态闸拒验证_{uniq[:6]}")

        # Submitting the estimate hits the access gate's closed-state denial and
        # must keep the dialog open. The closed state ships with guidance: the
        # switch name plus the page path to re-open it, so assert on both.
        page.locator('[data-test="explore-estimate-btn"]').click()
        toast = page.locator(".el-message")
        expect(toast).to_be_visible(timeout=8000)
        expect(toast).to_contain_text("ai.explore.appauto", timeout=8000)
        expect(toast).to_contain_text("系统", timeout=8000)
        assert dialog.is_visible(), "闸拒后弹窗不该谎报关闭"

        page.keyboard.press("Escape")
        cnt = sql_query(f"SELECT count(*) FROM tp_explore_session WHERE project_id = {pid}").strip()
        assert cnt == "0", f"关态提交不该落会话行（count={cnt}）"
    finally:
        _set_sys_config_value("ai.explore.appauto", switch_pre)
        _cleanup(pid)
