

from playwright.sync_api import expect

from conftest import (
    ADMIN_PASSWORD,
    api_login,
    assert_no_errors,
    inject_user_token,
    pick_project_in_header,
    sql_query,
)


def _make_project(api, uniq: str) -> tuple[int, str]:
    name = f"B9探索_{uniq[:10]}"
    r = api.post("/projects", json={"name": name, "code": f"EXP{uniq[:8].upper()}"})
    assert r.status_code == 200, f"创建项目失败: {r.text[:200]}"
    return r.json()["data"], name


def _sql_one(q: str) -> str:
    return sql_query(q).strip()


def _cleanup_session(sid: int) -> None:
    pass
    sql_query(
        "DELETE FROM tp_case_step WHERE case_id IN"
        f" (SELECT id FROM tp_test_case WHERE source_ref = 'explore:{sid}')"
    )
    sql_query(
        "DELETE FROM tp_script WHERE case_id IN"
        f" (SELECT id FROM tp_test_case WHERE source_ref = 'explore:{sid}')"
    )
    sql_query(
        "DELETE FROM tp_training_sample WHERE case_id IN"
        f" (SELECT id FROM tp_test_case WHERE source_ref = 'explore:{sid}')"
    )
    sql_query(f"DELETE FROM tp_test_case WHERE source_ref = 'explore:{sid}'")
    sql_query(f"DELETE FROM tp_explore_step WHERE session_id = {sid}")
    sql_query(f"DELETE FROM tp_explore_session WHERE id = {sid}")


def _cleanup_project(pid: int) -> None:
    sql_query(f"DELETE FROM tp_user_project WHERE project_id = {pid}")
    sql_query(f"DELETE FROM tp_project WHERE id = {pid}")



def test_explore_list_create_dialog(logged_in, collectors, api_client, uniq):
    page = logged_in
    pid, name = _make_project(api_client, uniq)
    try:
        page.goto("http://localhost:5173/#/explore", wait_until="domcontentloaded")
        expect(page.locator('[data-test="explore-list-page"]')).to_be_visible(timeout=10000)

        pick_project_in_header(page, name)
        expect(page.locator('[data-test="explore-table"]')).to_be_visible()

        page.locator('[data-test="explore-create"]').click()
        dialog = page.locator('[data-test="explore-create-dialog"]')
        expect(dialog).to_be_visible()
        confirm_btn = dialog.locator('[data-test="explore-confirm-btn"]')
        expect(confirm_btn).to_be_disabled()

        dialog.locator('[data-test="explore-goal"]').fill(f"E2E 探索目标 {uniq}")
        dialog.locator('[data-test="explore-start-url"]').fill("http://localhost:5173/e2e-login")
        dialog.locator('[data-test="explore-estimate-btn"]').click()
        expect(dialog.locator('[data-test="explore-estimate"]')).to_be_visible(timeout=15000)
        text = dialog.locator('[data-test="explore-estimate"]').inner_text()
        assert "tokens" in text, f"预估块内容异常: {text}"
        expect(confirm_btn).to_be_enabled()

        cnt = _sql_one(f"SELECT COUNT(*) FROM tp_explore_session WHERE project_id = {pid}")
        assert cnt == "0", f"预估链路不应落库，实查 {cnt} 行"

        dialog.locator('.el-dialog__headerbtn').click()
        assert_no_errors(collectors, "探索列表+预估门")
    finally:
        _cleanup_project(pid)



def test_explore_detail_to_cases_and_cancel(logged_in, collectors, api_client, uniq):
    page = logged_in
    pid, _name = _make_project(api_client, uniq)
    sid = None
    try:
        r = api_client.post("/explore/sessions", json={
            "projectId": pid,
            "goalText": f"E2E 转用例 {uniq[:10]}",
            "startUrl": "http://localhost:5173/e2e-cart",
            "confirm": True,
        })
        assert r.status_code == 200, f"创建探索失败: {r.text[:200]}"
        body = r.json()["data"]
        assert body.get("session"), f"confirm=true 应返会话: {body}"
        sid = body["session"]["id"]

        sql_query(
            "INSERT INTO tp_explore_step (session_id, seq, step_action, step_result, llm_think_md)"
            f" VALUES ({sid}, 1, '{{\"name\":\"goto\",\"args\":{{\"url\":\"http://localhost:5173/e2e-cart\"}}}}',"
            " '{\"ok\":true,\"observation\":\"e2e obs\"}', 'e2e think')"
        )

        page.goto(f"http://localhost:5173/#/explore/{sid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="explore-detail-page"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="explore-meta"]')).to_be_visible()

        expect(page.locator('[data-test="explore-step-ok-1"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="explore-step-ok-1"]')).to_contain_text("跑通")

        page.locator('[data-test="explore-steps-table"] .el-table__header .el-checkbox').first.click()
        to_cases = page.locator('[data-test="explore-to-cases"]')
        expect(to_cases).to_be_enabled()
        to_cases.click()
        box = page.locator(".el-message-box")
        expect(box).to_be_visible()
        box.locator("button:has-text('确定')").click()

        expect(page.locator(".el-message--success").first).to_be_visible(timeout=10000)
        cols = _sql_one(
            "SELECT COUNT(*) FROM tp_test_case WHERE source_ref = "
            f"'explore:{sid}' AND source = 5 AND status = 1 AND deleted = 0"
        )
        assert cols == "1", f"转用例落库待评审应 1 行，实查 {cols}"

        page.goto(f"http://localhost:5173/#/explore/{sid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="explore-meta"]')).to_be_visible(timeout=10000)
        cnt2 = _sql_one(f"SELECT COUNT(*) FROM tp_test_case WHERE source_ref = 'explore:{sid}'")
        assert cnt2 == "1", f"reload 后用例应持久，实查 {cnt2}"

        del_btn = page.locator('[data-test="explore-detail-cancel"]')
        expect(del_btn).to_be_visible()
        del_btn.click()
        box = page.locator(".el-message-box")
        expect(box).to_be_visible()
        assert "终止" in box.inner_text(), f"running 会话应提示软终止: {box.inner_text()}"
        box.locator("button:has-text('确定')").click()
        expect(page.locator(".el-message--success").first).to_be_visible(timeout=10000)
        st = _sql_one(f"SELECT status FROM tp_explore_session WHERE id = {sid}")
        assert st == "5", f"running 首删应软终止=5 cancelled，实查 {st}"

        page.goto(f"http://localhost:5173/#/explore/{sid}", wait_until="domcontentloaded")
        del2 = page.locator('[data-test="explore-detail-cancel"]')
        expect(del2).to_contain_text("删除", timeout=10000)
        del2.click()
        box = page.locator(".el-message-box")
        expect(box).to_be_visible()
        box.locator("button:has-text('确定')").click()
        remain = _sql_one(f"SELECT COUNT(*) FROM tp_explore_step WHERE session_id = {sid}")
        sess = _sql_one(f"SELECT COUNT(*) FROM tp_explore_session WHERE id = {sid}")
        assert remain == "0" and sess == "0", f"终态删除应清行: step={remain} session={sess}"

        assert_no_errors(collectors, "探索详情转用例+删除")
    finally:
        if sid is not None:
            _cleanup_session(sid)
        _cleanup_project(pid)



def test_explore_cancel_by_non_creator_deny(page, collectors, api_client, uniq):
    import httpx

    pid, _name = _make_project(api_client, uniq)
    uname = f"e2e_b9_{uniq[:8]}"
    pwd = "E2eB9@12345"
    sid = None
    try:
        r = api_client.post("/users", json={
            "username": uname, "password": pwd, "displayName": f"B9成员{uniq[:6]}",
            "email": f"{uname}@test.local", "phone": "", "role": 1,
        })
        assert r.status_code == 200 and r.json().get("code") == "0", f"创建用户失败: {r.text[:300]}"
        uid = r.json()["data"]
        sql_query(f"INSERT INTO tp_user_project (project_id, user_id, role) VALUES ({pid}, {uid}, 1)")

        r = api_client.post("/explore/sessions", json={
            "projectId": pid, "goalText": f"E2E 拒删 {uniq[:10]}",
            "startUrl": "http://localhost:5173/e2e-x", "confirm": True,
        })
        sid = r.json()["data"]["session"]["id"]

        inject_user_token(page, uname, pwd)
        token = api_login(uname, pwd)
        page.goto("http://localhost:5173/#/explore", wait_until="domcontentloaded")
        expect(page.locator('[data-test="explore-list-page"]')).to_be_visible(timeout=10000)
        page.goto(f"http://localhost:5173/#/explore/{sid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="explore-detail-page"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="explore-detail-cancel"]')).to_have_count(0)

        rh = httpx.delete(
            "http://localhost:8081/api/v1/explore/sessions/" + str(sid),
            headers={"Authorization": f"Bearer {token}"}, timeout=10,
        )
        assert rh.status_code == 403, f"非创建人 DELETE 应 403，实查 {rh.status_code}"
        assert "创建人" in rh.json().get("msg", ""), f"403 应人话点名创建人: {rh.text[:200]}"

        admin_token = api_login("admin", ADMIN_PASSWORD)
        page.goto("http://localhost:5173/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{admin_token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"http://localhost:5173/#/explore/{sid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        cancel_btn = page.locator('[data-test="explore-detail-cancel"]')
        expect(cancel_btn).to_be_visible(timeout=10000)
        cancel_btn.click()
        box = page.locator(".el-message-box")
        expect(box).to_be_visible()
        box.locator("button:has-text('确定')").click()
        expect(page.locator(".el-message--success").first).to_be_visible(timeout=10000)
        st = _sql_one(f"SELECT status FROM tp_explore_session WHERE id = {sid}")
        assert st == "5", f"创建人/ADMIN 终止应软终止=5，实查 {st}"

        assert_no_errors(collectors, "探索非创建人拒删")
    finally:
        if sid is not None:
            _cleanup_session(sid)
        sql_query(f"DELETE FROM tp_user_role WHERE user_id IN (SELECT id FROM tp_user WHERE username = '{uname}')")
        sql_query(f"DELETE FROM tp_user WHERE username = '{uname}'")
        _cleanup_project(pid)
