
from playwright.sync_api import expect

from conftest import ADMIN_PASSWORD, ADMIN_USERNAME, API_BASE, assert_no_errors



def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"Q1版本_{uniq}", "code": f"Q1V{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, pid, uniq):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"版本历史用例_{uniq}",
        "precondition": "打开登录页",
        "expectedResult": "登录成功进入首页",
        "priority": 1, "confidence": 2, "endType": "web", "phase": 1,
        "steps": [
            {"stepNo": 1, "description": "输入账号密码", "isAssert": 0},
            {"stepNo": 2, "description": "页面出现登录成功提示", "isAssert": 1},
        ],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]


def _open_detail(page, case_id):
    page.goto(f"{page.url.split('#')[0]}#/cases/{case_id}", wait_until="domcontentloaded")


def test_q1_versions_diff_rollback(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = _mk_project(api_client, uniq)
    case = _mk_case(api_client, pid, uniq)
    original_title = case["title"]

    try:
        _open_detail(page, case["id"])
        expect(page.locator('[data-test="case-version-block"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="case-version-count"]')).to_contain_text("1 版")

        page.locator('[data-test="detail-edit"]').click()
        page.locator('[data-test="detail-edit-title"]').fill(f"版本历史用例_{uniq}_已改")
        page.locator('[data-test="detail-save-draft"]').click()
        expect(page.locator(".el-message").filter(has_text="已保存")).to_be_visible(timeout=5000)

        rows = page.locator('[data-test="case-version-table"] tbody tr')
        expect(rows).to_have_count(2)
        expect(rows.nth(0).locator('[data-test="version-no"]')).to_contain_text("v2")
        expect(rows.nth(0).locator('[data-test="version-type"]')).to_contain_text("编辑")
        expect(rows.nth(0).locator('[data-test="version-operator"]')).not_to_contain_text("—")
        expect(rows.nth(1).locator('[data-test="version-no"]')).to_contain_text("v1")

        page.locator('[data-test="version-diff-2"]').click()
        dialog = page.locator('[data-test="version-diff-dialog"]')
        expect(dialog).to_be_visible()
        expect(dialog).to_contain_text("v1 → v2")
        diff_rows = dialog.locator('[data-test="version-diff-table"] tbody tr')
        expect(diff_rows.filter(has_text="测试目标")).to_have_count(1)
        title_row = diff_rows.filter(has_text="测试目标")
        expect(title_row.locator('[data-test="diff-before"]').first).to_contain_text(original_title)
        expect(title_row.locator('[data-test="diff-after"]').first).to_contain_text("已改")
        page.locator('[data-test="version-diff-dialog"] header button').click()
        expect(dialog).to_be_hidden()

        expect(page.locator('[data-test="version-rollback-2"]')).to_be_disabled()
        page.locator('[data-test="version-rollback-1"]').click()
        page.locator('.el-popconfirm button:has-text("确认回滚")').click()
        expect(page.locator(".el-message").filter(has_text="已回滚到 v1")).to_be_visible(timeout=5000)

        expect(rows).to_have_count(3)
        expect(rows.nth(0).locator('[data-test="version-no"]')).to_contain_text("v3")
        expect(rows.nth(0).locator('[data-test="version-type"]')).to_contain_text("回滚")
        expect(rows.nth(1).locator('[data-test="version-no"]')).to_contain_text("v2")
        expect(rows.nth(2).locator('[data-test="version-no"]')).to_contain_text("v1")
        expect(page.locator('[data-test="detail-title"]')).to_contain_text(original_title)

        r = api_client.get(f"/cases/{case['id']}")
        assert r.json()["data"]["version"] == 3, "回滚后用例 version 应为 3"
        vs = api_client.get(f"/cases/{case['id']}/versions").json()["data"]
        assert [v["version"] for v in vs] == [3, 2, 1], f"历史不篡改: {vs}"
        assert vs[0]["changeType"] == 4 and vs[1]["changeType"] == 1 and vs[2]["changeType"] == 1

        _open_detail(page, case["id"])
        expect(rows).to_have_count(3)
        expect(page.locator('[data-test="detail-title"]')).to_contain_text(original_title)

        assert_no_errors(collectors, "Q1 版本历史 diff 回滚")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_q1_review_snapshot(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = _mk_project(api_client, uniq)
    case = _mk_case(api_client, pid, uniq)

    try:
        r = api_client.post(f"/cases/{case['id']}/review",
                            json={"action": "pass", "version": case["version"]})
        assert r.status_code == 200 and r.json().get("code") == "0", f"评审失败: {r.text[:300]}"

        _open_detail(page, case["id"])
        rows = page.locator('[data-test="case-version-table"] tbody tr')
        expect(rows).to_have_count(2)
        expect(rows.nth(0).locator('[data-test="version-type"]')).to_contain_text("评审")

        page.locator('[data-test="version-diff-2"]').click()
        dialog = page.locator('[data-test="version-diff-dialog"]')
        expect(dialog).to_be_visible()
        diff_rows = dialog.locator('[data-test="version-diff-table"] tbody tr')
        expect(diff_rows).to_have_count(1)
        expect(diff_rows.first).to_contain_text("评审状态")
        expect(diff_rows.first.locator('[data-test="diff-before"]').first).to_contain_text("待评审")
        expect(diff_rows.first.locator('[data-test="diff-after"]').first).to_contain_text("已通过")
        page.locator('[data-test="version-diff-dialog"] header button').click()
        expect(dialog).to_be_hidden()

        assert_no_errors(collectors, "Q1 评审快照 diff")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
