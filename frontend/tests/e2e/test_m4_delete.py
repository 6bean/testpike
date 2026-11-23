
import httpx
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, nav_to, row_cmd



def _mk_project(api, uniq, suffix=""):
    pass
    code = f"M4P{uniq[:6].upper()}{suffix}"
    r = api.post("/projects", json={"name": f"M4删除项目{uniq}{suffix}", "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_env(api, pid, uniq, suffix="", env_type=2):
    r = api.post(f"/projects/{pid}/envs", json={
        "name": f"环境{suffix}{uniq}", "envType": env_type,
        "baseUrl": f"https://m4-{suffix}-{uniq}.tp-local.test",
    })
    assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, pid, uniq, suffix=""):
    r = api.post("/cases", json={
        "projectId": pid, "title": f"M4用例{suffix}{uniq[:6]}", "precondition": "无",
        "expectedResult": "符合预期", "priority": 1, "confidence": 2,
        "endType": "web", "status": 3, "phase": 1,
        "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}],
    })
    assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def _mk_tester_member(api, pid, uniq):
    pass
    r = api.post("/users", json={
        "username": f"m4t{uniq[:6]}", "password": "Test@12345",
        "displayName": f"M4执行人{uniq[:4]}", "role": 1,
    })
    assert r.json().get("code") == "0", f"建用户失败: {r.text[:300]}"
    uid = r.json()["data"]
    r = api.post(f"/projects/{pid}/members", json={"userId": uid, "role": 1})
    assert r.json().get("code") == "0", f"加成员失败: {r.text[:300]}"
    return uid


def _whitelist_409(collectors, url_part):
    pass
    collectors["network_4xx"] = [
        e for e in collectors["network_4xx"] if not (e["status"] == 409 and url_part in e["url"])
    ]
    collectors["console_errors"] = [
        e for e in collectors["console_errors"] if "status of 409" not in e
    ]


def _click_row_button(page, table_test, row_text, btn_test):
    pass
    row = page.locator(f'[data-test="{table_test}"] tbody tr:has-text("{row_text}")').first
    expect(row).to_be_visible(timeout=10000)
    row_cmd(page, row, btn_test)


def _cleanup_purge(api, pid):
    pass
    r = api.delete(f"/projects/{pid}/purge?force=true")
    assert r.status_code in (200, 404), f"清理失败: {r.text[:200]}"



def test_m4_project_purge_guard(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    pid_a = _mk_project(api_client, uniq, "A")
    pid_b = _mk_project(api_client, uniq, "B")
    try:
        _mk_env(api_client, pid_b, uniq, "a", 2)
        _mk_env(api_client, pid_b, uniq, "b", 3)
        _mk_case(api_client, pid_b, uniq, "a")
        _mk_case(api_client, pid_b, uniq, "b")

        page.goto(f"{BASE_URL}/#/projects", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="project-table"]').first).to_be_visible(timeout=15000)

        _click_row_button(page, "project-table", f"M4删除项目{uniq}A", "purge")
        expect(page.locator(".el-message").filter(has_text="项目已彻底删除").first).to_be_visible(timeout=10000)
        expect(page.locator(f'[data-test="project-table"] tr:has-text("M4删除项目{uniq}A")')).to_have_count(0)

        _click_row_button(page, "project-table", f"M4删除项目{uniq}B", "purge")
        box = page.locator(".el-message-box").filter(has_text="删除确认")
        expect(box).to_be_visible(timeout=10000)
        expect(box).to_contain_text("2 套环境")
        expect(box).to_contain_text("2 条用例")
        expect(box).to_contain_text("不可恢复")
        box.get_by_role("button", name="确认级联删除").click()

        expect(page.locator(".el-message").filter(has_text="项目已彻底删除").first).to_be_visible(timeout=10000)
        expect(page.locator(f'[data-test="project-table"] tr:has-text("M4删除项目{uniq}B")')).to_have_count(0)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="project-table"]').first).to_be_visible(timeout=15000)
        expect(page.locator(f'[data-test="project-table"] tr:has-text("M4删除项目{uniq}B")')).to_have_count(0)

        r = api_client.get(f"/projects/{pid_b}")
        assert r.status_code == 404, f"purge 后项目应 404: {r.status_code} {r.text[:200]}"

        assert_no_errors(collectors, "M4 项目彻底删除")
    finally:
        _cleanup_purge(api_client, pid_a)
        _cleanup_purge(api_client, pid_b)



def test_m4_plan_delete_status(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    pid = _mk_project(api_client, uniq)
    draft_id = pub_id = None
    try:
        env_id = _mk_env(api_client, pid, uniq)
        case_id = _mk_case(api_client, pid, uniq)
        uid = _mk_tester_member(api_client, pid, uniq)

        r = api_client.post("/plans", json={
            "projectId": pid, "name": f"M4草稿计划{uniq[:6]}", "targetVersion": "v1.0",
            "envIds": [env_id],
            "caseScope": {"phases": [1], "priorities": [], "keyword": None,
                          "tagIds": [], "explicitCaseIds": []}})
        assert r.json().get("code") == "0", f"建草稿计划失败: {r.text[:300]}"
        draft_id = r.json()["data"]["id"]

        r = api_client.post("/plans", json={
            "projectId": pid, "name": f"M4发布计划{uniq[:6]}", "targetVersion": "v1.0",
            "envIds": [env_id],
            "caseScope": {"phases": [1], "priorities": [], "keyword": None,
                          "tagIds": [], "explicitCaseIds": []}})
        pub_id = r.json()["data"]["id"]
        r = api_client.post(f"/plans/{pub_id}/assign", json={"caseIds": [case_id], "assigneeId": uid})
        assert r.json().get("code") == "0", f"分配失败: {r.text[:300]}"
        r = api_client.post(f"/plans/{pub_id}/publish")
        assert r.json().get("code") == "0", f"发布失败: {r.text[:300]}"

        # Header global context : in-page project selector retired — deep-link
        # ?projectId lands the list on this project directly.
        page.goto(f"{BASE_URL}/#/plans?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="plan-list-page"]').first).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="plan-table"]').first).to_be_visible(timeout=15000)

        _click_row_button(page, "plan-table", f"M4草稿计划{uniq[:6]}", "delete")
        expect(page.locator(".el-message").filter(has_text="计划已删除").first).to_be_visible(timeout=10000)
        expect(page.locator(f'[data-test="plan-table"] tr:has-text("M4草稿计划{uniq[:6]}")')).to_have_count(0)

        row_pub = page.locator(f'[data-test="plan-table"] tbody tr:has-text("M4发布计划{uniq[:6]}")').first
        expect(row_pub).to_be_visible(timeout=10000)
        row_pub.locator('[data-test="ra-more"]').click()
        pub_del = page.locator('[data-test="ra-delete"]:visible').first
        expect(pub_del).to_be_visible(timeout=5000)
        expect(pub_del).to_have_attribute("aria-disabled", "true", timeout=5000)
        page.keyboard.press("Escape")
        r = api_client.post(f"/plans/{pub_id}/cancel")
        assert r.json().get("code") == "0", f"取消失败: {r.text[:200]}"
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="plan-list-page"]').first).to_be_visible(timeout=15000)
        # ?projectId persists in the hash URL across reload (header context single source)
        expect(page.locator('[data-test="plan-table"]').first).to_be_visible(timeout=15000)
        _click_row_button(page, "plan-table", f"M4发布计划{uniq[:6]}", "delete")
        expect(page.locator(".el-message").filter(has_text="计划已删除").first).to_be_visible(timeout=10000)
        expect(page.locator(f'[data-test="plan-table"] tr:has-text("M4发布计划{uniq[:6]}")')).to_have_count(0)

        # ③ reload persistence: deleted draft absent from the list (?projectId persists in URL)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="plan-list-page"]').first).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="plan-table"]').first).to_be_visible(timeout=15000)
        expect(page.locator(f'[data-test="plan-table"] tr:has-text("M4草稿计划{uniq[:6]}")')).to_have_count(0)

        assert_no_errors(collectors, "M4 计划删除状态机")
    finally:
        if draft_id:
            api_client.delete(f"/plans/{draft_id}")
        if pub_id:
            api_client.delete(f"/plans/{pub_id}")
        _cleanup_purge(api_client, pid)



def test_m4_role_tag_blocked(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    pid = _mk_project(api_client, uniq)
    role_id = None
    uid = None
    try:
        code = f"m4r{uniq[:8]}"
        r = api_client.post("/roles", json={
            "name": f"M4角色{uniq[:6]}", "code": code, "description": "M4",
            "dataScope": 2, "status": 1})
        assert r.json().get("code") == "0", f"建角色失败: {r.text[:300]}"
        role_id = r.json()["data"]
        r = api_client.post("/users", json={
            "username": f"m4ru{uniq[:6]}", "password": "Test@12345",
            "displayName": f"M4角色用户{uniq[:4]}", "role": 1})
        uid = r.json()["data"]
        r = api_client.put(f"/users/{uid}/roles", json={"roleIds": [role_id]})
        assert r.json().get("code") == "0", f"分配角色失败: {r.text[:300]}"

        r = api_client.post("/tags", json={"projectId": pid, "name": f"M4标签{uniq[:6]}", "type": 4})
        assert r.json().get("code") == "0", f"建标签失败: {r.text[:300]}"
        tag_id = r.json()["data"]["id"]
        case_id = _mk_case(api_client, pid, uniq, "t")
        r = api_client.put(f"/cases/{case_id}/tags", json={"projectId": pid, "tagIds": [tag_id]})
        assert r.json().get("code") == "0", f"打标签失败: {r.text[:300]}"

        page.goto(f"{BASE_URL}/#/roles", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="role-table"]').first).to_be_visible(timeout=15000)
        row = page.locator(f'[data-test="role-table"] tbody tr:has-text("M4角色{uniq[:6]}")').first
        expect(row).to_be_visible(timeout=10000)
        row.locator('button:has-text("删除")').first.click()
        box = page.locator(".el-message-box").filter(has_text="无法删除")
        expect(box).to_be_visible(timeout=10000)
        expect(box).to_contain_text("1 名用户")
        box.get_by_role("button", name="知道了").click()
        expect(page.locator(f'[data-test="role-table"] tbody tr:has-text("M4角色{uniq[:6]}")')).to_have_count(1)

        # ② Deleting a referenced tag → "cannot delete" dialog (merge first); the tag
        # page lands through the header-context deep link ?projectId
        page.goto(f"{BASE_URL}/#/tags?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="tag-table"]').first).to_be_visible(timeout=15000)
        row = page.locator(f'[data-test="tag-table"] tbody tr:has-text("M4标签{uniq[:6]}")').first
        expect(row).to_be_visible(timeout=10000)
        row.locator('[data-test="tag-delete-btn"]').first.click()
        box = page.locator(".el-message-box").filter(has_text="无法删除")
        expect(box).to_be_visible(timeout=10000)
        expect(box).to_contain_text("1 条用例")
        box.get_by_role("button", name="知道了").click()
        expect(page.locator(f'[data-test="tag-table"] tbody tr:has-text("M4标签{uniq[:6]}")')).to_have_count(1)

        r = api_client.put(f"/cases/{case_id}/tags", json={"projectId": pid, "tagIds": []})
        assert r.json().get("code") == "0", f"清标签失败: {r.text[:300]}"
        page.reload(wait_until="domcontentloaded")
        # ?projectId persists in the URL across reload — tag table reloads for the project
        expect(page.locator('[data-test="tag-table"]').first).to_be_visible(timeout=15000)
        row = page.locator(f'[data-test="tag-table"] tbody tr:has-text("M4标签{uniq[:6]}")').first
        row.locator('[data-test="tag-delete-btn"]').first.click()
        box = page.locator(".el-message-box").filter(has_text="无法删除")
        expect(box).to_have_count(0, timeout=3000)
        expect(page.locator(".el-message").filter(has_text="标签已删除").first).to_be_visible(timeout=10000)

        assert_no_errors(collectors, "M4 角色/标签引用阻止")
    finally:
        if role_id:
            if uid:
                api_client.put(f"/users/{uid}/roles", json={"roleIds": []})
            api_client.delete(f"/roles/{role_id}")
        _cleanup_purge(api_client, pid)
