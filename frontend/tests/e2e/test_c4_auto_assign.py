
from playwright.sync_api import expect

from conftest import (
    add_member,
    assert_no_errors,
    nav_to,
)


def _mk_user(api, uniq, role, tag):
    pass
    username = f"c4user_{uniq}_{tag}"
    r = api.post("/users", json={
        "username": username, "password": "Test@12345",
        "displayName": f"C4-{tag}{uniq}", "email": f"{username}@test.local",
        "phone": "", "role": role,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用户失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, project_id, uniq, title, priority=1):
    r = api.post("/cases", json={
        "projectId": project_id, "title": title, "precondition": "无",
        "expectedResult": "符合预期", "priority": priority, "confidence": 2, "endType": "web",
        "steps": [{"stepNo": 1, "description": f"步骤{uniq}", "isAssert": 1}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def _select_project(page, project_name):
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=project_name).first
    expect(option).to_be_visible(timeout=10000)
    option.click()



def test_c4_auto_assign_flow(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in

    r = api_client.post("/projects", json={"name": f"C4E2E_{uniq}", "code": f"C4E{uniq}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    t1 = _mk_user(api_client, uniq, 1, "t1")
    t2 = _mk_user(api_client, uniq, 1, "t2")
    dev = _mk_user(api_client, uniq, 3, "dev")
    for uid in (t1, t2, dev):
        add_member(api_client, pid, uid)
    case_ids = [_mk_case(api_client, pid, uniq, f"C4 自动分配用例{uniq}-{i}") for i in (1, 2, 3)]
    extra = 0

    try:
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "评审工作台", "/review", '[data-test="review-page"]', submenu="设计用例")
        _select_project(page, f"C4E2E_{uniq}")

        hint = page.locator('[data-test="review-unassigned-hint"]')
        expect(hint).to_be_visible(timeout=10000)
        expect(hint).to_contain_text("3", timeout=5000)

        hint.locator('[data-test="review-auto-assign"]').click()
        dialog = page.locator('[data-test="auto-assign-dialog"]')
        expect(dialog).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="auto-assign-summary"]')).to_contain_text("3", timeout=10000)
        expect(page.locator('[data-test="auto-assign-summary"]')).to_contain_text("2", timeout=5000)
        body = page.locator('[data-test="auto-assign-body"]').inner_text()
        assert f"C4-t1{uniq}" in body and f"C4-t2{uniq}" in body, f"预览清单应含两名测试成员: {body}"
        assert f"C4-dev{uniq}" not in body, f"开发人员不应入候选池: {body}"

        page.locator('[data-test="auto-assign-confirm"]').click()
        expect(page.locator(".el-message:has-text(\"已自动分配\")").first).to_be_visible(timeout=10000)
        expect(hint).to_be_hidden(timeout=10000)

        page.reload(wait_until="domcontentloaded")
        nav_to(page, "评审工作台", "/review", '[data-test="review-page"]', submenu="设计用例")
        _select_project(page, f"C4E2E_{uniq}")
        expect(page.locator('[data-test="review-unassigned-hint"]')).to_be_hidden(timeout=10000)

        loads = {t1: 0, t2: 0, dev: 0}
        for cid in case_ids:
            r = api_client.get(f"/cases/{cid}")
            assert r.status_code == 200, f"用例详情失败: {r.text[:300]}"
            assignee = r.json()["data"]["assigneeId"]
            assert assignee in (t1, t2), f"承接人应为测试成员: {assignee}"
            loads[assignee] += 1
        assert max(loads[t1], loads[t2]) - min(loads[t1], loads[t2]) <= 1, \
            f"负载均衡违背（3 条 2 人应 2-1 均摊）: {loads}"
        assert loads[dev] == 0

        extra = _mk_case(api_client, pid, uniq, f"C4 列表入口用例{uniq}")
        nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
        page.locator('[data-test="project-ctx-select"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"C4E2E_{uniq}").first.click()
        expect(page.locator('[data-test="case-table"]')).to_be_visible(timeout=10000)
        btn = page.locator('[data-test="case-auto-assign"]')
        expect(btn).to_be_visible(timeout=10000)
        btn.click()
        expect(page.locator('[data-test="auto-assign-dialog"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="auto-assign-summary"]')).to_contain_text("1", timeout=10000)
        page.locator('[data-test="auto-assign-cancel"]').click()

        assert_no_errors(collectors, "C4 自动分配全链")
    finally:
        for cid in case_ids + [extra]:
            if cid:
                api_client.delete(f"/cases/{cid}")
        api_client.delete(f"/projects/{pid}")
        for uid in (t1, t2, dev):
            api_client.delete(f"/users/{uid}")



def test_c4_auto_assign_idempotent_api(api_client, uniq):
    pass
    r = api_client.post("/projects", json={"name": f"C4ID_{uniq}", "code": f"C4I{uniq}"})
    pid = r.json()["data"]
    t1 = _mk_user(api_client, uniq, 1, "t1")
    add_member(api_client, pid, t1)
    case_id = _mk_case(api_client, pid, uniq, f"C4 幂等用例{uniq}")
    try:
        r = api_client.post("/cases/auto-assign", json={"projectId": pid, "preview": True})
        assert r.status_code == 200 and r.json()["data"]["assignedCount"] == 1, \
            f"首次预览应为 1 条: {r.text[:300]}"
        r = api_client.post("/cases/auto-assign", json={"projectId": pid, "preview": False})
        assert r.status_code == 200 and r.json()["data"]["assignedCount"] == 1, \
            f"正式分配应为 1 条: {r.text[:300]}"
        r = api_client.post("/cases/auto-assign", json={"projectId": pid, "preview": False})
        assert r.status_code == 200 and r.json()["data"]["assignedCount"] == 0, \
            f"二次执行应幂等归零: {r.text[:300]}"
    finally:
        api_client.delete(f"/cases/{case_id}")
        api_client.delete(f"/projects/{pid}")
        api_client.delete(f"/users/{t1}")
