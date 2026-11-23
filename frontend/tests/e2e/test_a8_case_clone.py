
import re
import subprocess
import time

import httpx
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, api_login, assert_no_errors, inject_token, inject_user_token


def sql(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def case_row(case_no):
    pass
    return sql(
        "SELECT c.project_id||'|'||c.status||'|'||c.version||'|'||c.source||'|'||c.source_ref||'|'||"
        "coalesce(c.assignee_id::text,'NULL')||'|'||"
        "(SELECT count(*) FROM tp_case_step s WHERE s.case_id=c.id)||'|'||"
        "(SELECT count(*) FROM tp_case_step s WHERE s.case_id=c.id AND s.is_assert=1)||'|'||"
        "(SELECT coalesce(string_agg(d.name, ',' ORDER BY d.name),'') FROM tp_case_tag t "
        "JOIN tp_tag_dict d ON d.id=t.tag_id WHERE t.case_id=c.id)||'|'||p.code "
        f"FROM tp_test_case c JOIN tp_project p ON p.id=c.project_id "
        f"WHERE c.case_no='{case_no}' AND c.deleted=0")


def dismiss_toasts(page):
    pass
    expect(page.locator('.el-message')).to_have_count(0, timeout=10000)


def do_clone_via_dialog(page, target_project_name=None):
    pass
    page.locator('[data-test="detail-clone"]').click()
    dlg = page.locator('[data-test="clone-dialog"]')
    expect(dlg).to_be_visible(timeout=10000)
    expect(dlg.locator('[data-test="clone-hint"]')).to_contain_text("待评审")
    if target_project_name is not None:
        dlg.locator('.clone-target-mode label:has-text("其他项目")').click()
        sel = dlg.locator('[data-test="clone-target-project"]')
        expect(sel).to_be_visible()
        sel.click()
        sel.locator("input").fill(target_project_name)
        opt = page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:visible:has-text("{target_project_name}")').first
        expect(opt).to_be_visible(timeout=8000)
        opt.click()
        expect(dlg).to_contain_text("编号按目标项目规则")
    dlg.locator('[data-test="clone-confirm"]').click()
    toast = page.locator('.el-message--success').first
    expect(toast).to_be_visible(timeout=15000)
    text = toast.inner_text()
    expect(toast).to_contain_text("已克隆为新用例（待评审）")
    return text


def test_a8_case_clone_flow(page, collectors, api_client, uniq):
    pass
    s = uniq[:6]
    proj_a_name, proj_b_name = f"A8克隆项目A{s}", f"A8克隆项目B{s}"
    pid_a = api_client.post("/projects", json={"name": proj_a_name, "code": f"A8X{s}"}).json()["data"]
    pid_b = api_client.post("/projects", json={"name": proj_b_name, "code": f"A8Y{s}"}).json()["data"]

    def mk_tag(pid, name):
        return api_client.post("/tags", json={"projectId": pid, "name": name, "type": 1}).json()["data"]["id"]

    tag_a1, tag_a2, tag_b1 = mk_tag(pid_a, f"冒烟{s}"), mk_tag(pid_a, f"仅A标签{s}"), mk_tag(pid_b, f"冒烟{s}")

    uname = f"a8t_{s}"
    upwd = "Passw0rd!a8"
    uid = api_client.post("/users", json={
        "username": uname, "password": upwd, "displayName": "A8克隆tester",
        "email": f"{uname}@testpike.local", "role": 1}).json()["data"]
    api_client.post(f"/projects/{pid_a}/members", json={"userId": uid, "role": 1})

    src = api_client.post("/cases", json={
        "projectId": pid_a, "title": f"克隆源场景{s}", "precondition": "账号已注册",
        "expectedResult": "登录成功进入首页", "priority": 2, "confidence": 3,
        "endType": "web", "status": 1, "tagIds": [tag_a1, tag_a2],
        "steps": [
            {"stepNo": 1, "description": "打开登录页", "isAssert": 0},
            {"stepNo": 2, "description": "输入账号密码", "isAssert": 0},
            {"stepNo": 3, "description": "点击登录", "isAssert": 1},
        ]}).json()["data"]
    src_id, src_no = src["id"], src["caseNo"]
    assert api_client.post(f"/cases/{src_id}/assign", json={"assigneeId": uid}).json().get("code") == "0"
    ver = api_client.get(f"/cases/{src_id}").json()["data"]["version"]
    assert api_client.post(f"/cases/{src_id}/review",
                           json={"action": "pass", "version": ver}).json().get("code") == "0"
    assert sql(f"SELECT status FROM tp_test_case WHERE id={src_id}") == "3", "源用例未进入已批准态"
    review_cnt_src = sql(f"SELECT count(*) FROM tp_review_log WHERE case_id={src_id}")

    no_a = no_b = None
    try:
        inject_token(page)

        page.goto(f"{BASE_URL}/#/cases/{src_id}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="detail-title"]')).to_contain_text(f"克隆源场景{s}", timeout=15000)
        expect(page.locator('[data-test="detail-status"]')).to_contain_text("已通过")
        clone_btn = page.locator('[data-test="detail-clone"]')
        expect(clone_btn).to_be_visible()
        expect(clone_btn).to_be_enabled()

        text = do_clone_via_dialog(page, target_project_name=None)
        m = re.search(r"TC-A8X" + s + r"-\d+", text)
        assert m, f"toast 未含 A 项目前缀新编号: {text!r}"
        no_a = m.group(0)
        assert no_a != src_no, "克隆编号与源相同（应为新编号）"
        dismiss_toasts(page)

        expect(page).to_have_url(re.compile(r"/cases\?.*keyword=" + re.escape(no_a)), timeout=10000)
        row_a = page.locator('[data-test="case-table"] tbody tr').filter(has_text=no_a)
        expect(row_a).to_have_count(1, timeout=10000)
        expect(row_a.first).to_contain_text("待评审")

        parts = case_row(no_a).split("|")
        assert (int(parts[0]), int(parts[1]), int(parts[2]), parts[3], parts[4]) == \
            (pid_a, 1, 1, "4", f"case:{src_id}"), f"同项目克隆 DB 面失配: {parts}"
        assert parts[5] == str(uid), f"同项目 assignee 应保留: {parts[5]}"
        assert (parts[6], parts[7]) == ("3", "1"), f"步骤复制失配: {parts[6]}/{parts[7]}"
        assert parts[8] == f"仅A标签{s},冒烟{s}", f"同项目标签应直拷: {parts[8]}"
        clone_a_id = sql(f"SELECT id FROM tp_test_case WHERE case_no='{no_a}' AND deleted=0")
        assert sql(f"SELECT count(*) FROM tp_review_log WHERE case_id={clone_a_id}") == "0"
        assert sql(f"SELECT count(*) FROM tp_exec_task WHERE case_id={clone_a_id}") == "0"
        assert sql(f"SELECT count(*) FROM tp_case_defect WHERE case_id={clone_a_id}") == "0"
        assert sql(f"SELECT count(*) FROM tp_review_log WHERE case_id={src_id}") == review_cnt_src, \
            "源用例评审记录被触碰（克隆应只读源）"

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-table"] tbody tr').filter(has_text=no_a)).to_have_count(1, timeout=10000)

        page.goto(f"{BASE_URL}/#/cases/{src_id}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="detail-title"]')).to_contain_text(f"克隆源场景{s}", timeout=15000)
        text = do_clone_via_dialog(page, target_project_name=proj_b_name)
        m = re.search(r"TC-A8Y" + s + r"-\d+", text)
        assert m, f"跨项目 toast 未含 B 前缀新编号: {text!r}"
        no_b = m.group(0)
        dismiss_toasts(page)

        expect(page).to_have_url(re.compile(r"/cases\?.*keyword=" + re.escape(no_b)), timeout=10000)
        row_b = page.locator('[data-test="case-table"] tbody tr').filter(has_text=no_b)
        expect(row_b).to_have_count(1, timeout=10000)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-table"] tbody tr').filter(has_text=no_b)).to_have_count(1, timeout=10000)

        parts = case_row(no_b).split("|")
        assert (int(parts[0]), int(parts[1]), parts[3], parts[4], parts[9]) == \
            (pid_b, 1, "4", f"case:{src_id}", f"A8Y{s}"), f"跨项目克隆 DB 面失配: {parts}"
        assert parts[5] == "NULL", f"跨项目不应挂分配人: {parts[5]}"
        assert parts[6] == "3", f"跨项目步骤应复制: {parts[6]}"
        assert parts[8] == f"冒烟{s}", f"跨项目标签应按 name 重映射仅同名: {parts[8]}"

        inject_user_token(page, uname, upwd)
        page.goto(f"{BASE_URL}/#/cases/{src_id}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="detail-clone"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="detail-clone"]').click()
        dlg = page.locator('[data-test="clone-dialog"]')
        expect(dlg).to_be_visible(timeout=10000)
        dlg.locator('.clone-target-mode label:has-text("其他项目")').click()
        sel = dlg.locator('[data-test="clone-target-project"]')
        sel.click()
        sel.locator("input").fill(proj_b_name)
        expect(page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:visible:has-text("{proj_b_name}")')).to_have_count(0, timeout=5000)
        page.keyboard.press("Escape")
        dlg.locator('[data-test="clone-cancel"]').click()

        t_ok = httpx.post(f"{API_BASE}/auth/login", json={"username": uname, "password": upwd}, timeout=10)
        t_token = t_ok.json()["data"]["accessToken"]
        b_cnt_before = sql(f"SELECT count(*) FROM tp_test_case WHERE project_id={pid_b} AND deleted=0")
        r403 = httpx.post(f"{API_BASE}/cases/{src_id}/clone",
                          json={"targetProjectId": pid_b},
                          headers={"Authorization": f"Bearer {t_token}"}, timeout=10)
        b_cnt_after = sql(f"SELECT count(*) FROM tp_test_case WHERE project_id={pid_b} AND deleted=0")
        assert r403.status_code == 403 and ("成员" in str(r403.json().get("msg")) or "权限" in str(r403.json().get("msg"))) \
            and b_cnt_after == b_cnt_before, \
            f"非成员跨项目克隆应 403 人话+零写入: {r403.status_code} {r403.text[:200]} {b_cnt_before}->{b_cnt_after}"

        assert_no_errors(collectors, "A8 用例克隆 同项目/跨项目/权限面 全链")
    finally:
        api_client.delete(f"/users/{uid}")
        for pid in (pid_a, pid_b):
            api_client.delete(f"/projects/{pid}")
            api_client.delete(f"/projects/{pid}/purge?force=true")
        deadline = time.time() + 15
        while time.time() < deadline:
            left_case = sql(f"SELECT count(*) FROM tp_test_case WHERE project_id IN ({pid_a},{pid_b}) AND deleted=0")
            left_tag = sql(f"SELECT count(*) FROM tp_tag_dict WHERE project_id IN ({pid_a},{pid_b})")
            if left_case == "0" and left_tag == "0":
                break
            time.sleep(1)
        assert sql(f"SELECT count(*) FROM tp_test_case WHERE project_id IN ({pid_a},{pid_b}) AND deleted=0") == "0", \
            f"purge 后用例残留（含克隆件 {no_a}/{no_b}）"
        assert sql(f"SELECT count(*) FROM tp_tag_dict WHERE project_id IN ({pid_a},{pid_b})") == "0", "purge 后标签残留"
        assert sql(f"SELECT count(*) FROM tp_user WHERE username='{uname}' AND deleted=0") == "0", "测试用户未清理"
