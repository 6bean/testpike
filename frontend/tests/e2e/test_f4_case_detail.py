
import uuid

import pytest
from playwright.sync_api import expect

from conftest import assert_no_errors



def _mk_project(api, uniq):
    name = f"F4项目_{uniq}"
    code = f"F4{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200, f"创建项目失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"创建项目业务失败: {body.get('msg')}"
    return body["data"], name


def _mk_case(api, pid, uniq, title="登录功能验证"):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"{title}_{uniq}",
        "precondition": f"前置：已登录系统（{uniq}）",
        "expectedResult": f"预期：操作成功（{uniq}）",
        "priority": 0,
        "confidence": 1,
        "endType": "web",
        "status": 1,
        "steps": [
            {"stepNo": 1, "description": "打开页面 /login", "isAssert": 0},
            {"stepNo": 2, "description": "页面出现登录表单", "isAssert": 1},
        ],
    })
    assert r.status_code == 200, f"创建用例失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"创建用例业务失败: {body.get('msg')}"
    return body["data"]


def _cleanup(api, pid):
    pass
    api.delete(f"/projects/{pid}/purge?force=true")


def _open_detail(page, case_id):
    pass
    page.goto(f"{page.url.split('#')[0]}#/cases/{case_id}", wait_until="domcontentloaded")


def test_f4_detail_render(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, _ = _mk_project(api_client, uniq)
    c = _mk_case(api_client, pid, uniq, "登录功能验证")

    try:

        _open_detail(page, c["id"])

        expect(page.locator('[data-test="case-detail-root"]')).to_be_visible()
        expect(page.locator('[data-test="detail-back"]')).to_be_visible()

        expect(page.locator('[data-test="detail-title"]')).to_contain_text(f"登录功能验证_{uniq}")
        expect(page.locator('[data-test="detail-case-no"]')).to_contain_text(c["caseNo"])

        expect(page.locator('[data-test="detail-status"]')).to_contain_text("待评审")
        expect(page.locator('[data-test="detail-priority"]')).to_contain_text("P0")
        expect(page.locator('[data-test="detail-confidence"]')).to_contain_text("高")

        expect(page.locator('[data-test="detail-precondition"]')).to_contain_text(f"前置：已登录系统（{uniq}）")
        expect(page.locator('[data-test="detail-expected"]')).to_contain_text(f"预期：操作成功（{uniq}）")

        expect(page.locator('[data-test="detail-steps"] tbody tr.el-table__row')).to_have_count(2)
        expect(page.locator('[data-test="detail-steps"]')).to_contain_text("打开页面 /login")
        expect(page.locator('[data-test="detail-steps"]')).to_contain_text("页面出现登录表单")
        expect(page.locator('[data-test="detail-steps"]')).to_contain_text("操作")
        expect(page.locator('[data-test="detail-steps"]')).to_contain_text("断言")

        expect(page.locator('[data-test="detail-version"]')).to_contain_text("v1")

        assert_no_errors(collectors, "F-4 详情渲染")
    finally:
        _cleanup(api_client, pid)


def test_f4_review_pass_persist(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, _ = _mk_project(api_client, uniq)
    c = _mk_case(api_client, pid, uniq, "待评详情")

    try:

        _open_detail(page, c["id"])
        expect(page.locator('[data-test="detail-status"]')).to_contain_text("待评审")

        page.locator('[data-test="detail-review-pass"]').click()
        expect(page.locator(".el-message-box")).to_be_visible()
        page.locator('.el-message-box__btns button:has-text("通过")').click()
        expect(page.locator(".el-message").filter(has_text="已通过")).to_be_visible(timeout=5000)
        r = api_client.get(f"/cases/{c['id']}")
        assert r.json()["data"]["status"] == 3, "用例状态应为已通过(3)"

        _open_detail(page, c["id"])
        expect(page.locator('[data-test="detail-status"]')).to_contain_text("已通过")
        expect(page.locator('[data-test="detail-version"]')).to_contain_text("v2")

        assert_no_errors(collectors, "F-4 详情评审通过")
    finally:
        _cleanup(api_client, pid)


def test_f4_edit_draft_keep_status(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, _ = _mk_project(api_client, uniq)
    c = _mk_case(api_client, pid, uniq, "草稿用例")

    try:

        _open_detail(page, c["id"])
        expect(page.locator('[data-test="detail-status"]')).to_contain_text("待评审")

        page.locator('[data-test="detail-edit"]').click()
        expect(page.locator('[data-test="detail-save-draft"]')).to_be_visible()

        title_input = page.locator('[data-test="detail-edit-title"]')
        title_input.fill(f"草稿用例_{uniq}_已改")
        page.locator('[data-test="detail-edit-add-step"]').click()
        new_step = page.locator('[data-test="detail-edit-steps"] .step-row').last
        new_step.locator('input[type="text"]').fill("点击登录按钮")
        new_step.locator("text=断言").click()

        page.locator('[data-test="detail-save-draft"]').click()
        expect(page.locator(".el-message").filter(has_text="已保存")).to_be_visible(timeout=5000)
        r = api_client.get(f"/cases/{c['id']}")
        data = r.json()["data"]
        assert data["status"] == 1, f"保存草稿不应改变状态，实际 {data['status']}"
        assert f"已改" in data["title"], f"标题未保存: {data['title']}"
        assert data["version"] == c["version"] + 1, f"版本应 +1，实际 {data['version']}"

        _open_detail(page, c["id"])
        expect(page.locator('[data-test="detail-title"]')).to_contain_text("已改")
        expect(page.locator('[data-test="detail-status"]')).to_contain_text("待评审")
        expect(page.locator('[data-test="detail-steps"]')).to_contain_text("点击登录按钮")

        assert_no_errors(collectors, "F-4 详情保存草稿")
    finally:
        _cleanup(api_client, pid)


def test_f4_review_409_conflict(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, _ = _mk_project(api_client, uniq)
    c = _mk_case(api_client, pid, uniq, "并发用例")

    try:

        _open_detail(page, c["id"])
        expect(page.locator('[data-test="detail-case-no"]')).to_contain_text(c["caseNo"])

        r = api_client.put(f"/cases/{c['id']}", json={
            "title": f"并发用例_{uniq}_他人改",
            "version": c["version"],
        })
        assert r.json()["code"] == "0", f"他人更新失败: {r.text[:300]}"

        page.locator('[data-test="detail-review-pass"]').click()
        expect(page.locator(".el-message-box")).to_be_visible()
        page.locator('.el-message-box__btns button:has-text("通过")').click()

        expect(page.locator(".el-message").filter(has_text="他人修改")).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="detail-title"]')).to_contain_text("他人改")
        expect(page.locator('[data-test="detail-version"]')).to_contain_text("v2")

        conflict_url = f"http://localhost:5173/api/v1/cases/{c['id']}/review"
        collectors["network_4xx"] = [
            e for e in collectors["network_4xx"]
            if not (e["url"] == conflict_url and e["status"] == 409)
        ]
        collectors["console_errors"] = [
            e for e in collectors["console_errors"] if "status of 409" not in e
        ]

        assert_no_errors(collectors, "F-4 详情 409 乐观锁")
    finally:
        _cleanup(api_client, pid)
