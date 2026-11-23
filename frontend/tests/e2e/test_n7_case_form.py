
import pytest
from playwright.sync_api import expect

from conftest import assert_no_errors, inject_token, nav_to, row_cmd



def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"N7E2E_{uniq}", "code": f"N7E{uniq.upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_tag(api, pid, name):
    r = api.post("/tags", json={"projectId": pid, "name": name, "type": 4})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建标签失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_tester(api, uniq):
    r = api.post("/users", json={
        "username": f"n7t_{uniq}", "password": "Test@12345", "displayName": f"N7测试员_{uniq}",
        "email": f"n7t_{uniq}@test.local", "phone": "", "role": 1})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建 tester 失败: {r.text[:300]}"
    return r.json()["data"]


def _go_cases(page, tag):
    pass
    nav_to(page, "用例管理", "/cases", '[data-test="case-table"]', submenu="设计用例")
    page.locator("[data-test=project-ctx-select]").click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"N7E2E_{tag}").first.click()
    rows_or_empty = page.locator('[data-test="case-table"] tbody tr, [data-test="case-empty"]').first
    expect(rows_or_empty).to_be_visible(timeout=10000)


def _open_create(page):
    page.locator('[data-test="case-create"]').click()
    expect(page.locator('[data-test="case-create-dialog"]')).to_be_visible(timeout=10000)


def _submit_create(page):
    page.locator('[data-test="case-create-submit"]').click()
    expect(page.locator(".el-message--success").last).to_be_visible(timeout=10000)


def _row_by_title(page, title):
    return page.locator('[data-test="case-table"] tbody tr').filter(has_text=title)


def test_n7_create_form(page, collectors, api_client, uniq):
    pass
    api = api_client
    tag = uniq
    pid = _mk_project(api, tag)
    t1 = _mk_tag(api, pid, f"N7冒烟_{tag}")
    t2 = _mk_tag(api, pid, f"N7回归_{tag}")
    uid = _mk_tester(api, tag)
    r = api.post(f"/projects/{pid}/members", json={"userId": uid, "role": 1})
    assert r.status_code == 200 and r.json().get("code") == "0", f"加成员失败: {r.text[:300]}"
    title = f"N7表单新建_{tag}"
    try:
        inject_token(page)
        _go_cases(page, tag)

        _open_create(page)
        page.locator('[data-test="case-create-title"]').fill(title)
        page.locator('[data-test="case-create-tags"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"N7冒烟_{tag}").first.click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"N7回归_{tag}").first.click()
        page.keyboard.press("Escape")
        page.locator('[data-test="case-create-assignee"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"N7测试员_{tag}").first.click()
        page.keyboard.press("Escape")
        _submit_create(page)
        expect(page.locator('[data-test="case-create-dialog"]:visible')).to_have_count(0, timeout=5000)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-table"] tbody tr').first).to_be_visible(timeout=10000)
        row = _row_by_title(page, title)
        expect(row).to_be_visible(timeout=10000)
        row_text = row.inner_text()
        assert f"N7冒烟_{tag}" in row_text, f"reload 后标签缺失: {row_text}"
        assert f"N7回归_{tag}" in row_text, f"reload 后标签缺失: {row_text}"
        assert f"N7测试员_{tag}" in row_text, f"reload 后分配人缺失: {row_text}"

        assert_no_errors(collectors, "N7 新建表单")
    finally:
        api.delete(f"/projects/{pid}/purge?force=true")
        api.delete(f"/users/{uid}")


def test_n7_edit_prefill(page, collectors, api_client, uniq):
    pass
    api = api_client
    tag = uniq
    pid = _mk_project(api, tag)
    t1 = _mk_tag(api, pid, f"N7E冒烟_{tag}")
    t2 = _mk_tag(api, pid, f"N7E回归_{tag}")
    uid = _mk_tester(api, tag)
    r = api.post(f"/projects/{pid}/members", json={"userId": uid, "role": 1})
    assert r.status_code == 200 and r.json().get("code") == "0", f"加成员失败: {r.text[:300]}"
    title = f"N7编辑预填_{tag}"
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "前置", "expectedResult": "预期",
        "priority": 1, "confidence": 2, "endType": "web", "phase": 2,
        "steps": [{"stepNo": 1, "description": "步骤一", "isAssert": 1}],
        "tagIds": [t1["id"], t2["id"]], "assigneeId": uid})
    assert r.status_code == 200 and r.json().get("code") == "0", f"API 造用例失败: {r.text[:300]}"
    try:
        inject_token(page)
        _go_cases(page, tag)

        row = _row_by_title(page, title)
        expect(row).to_be_visible(timeout=10000)
        row_cmd(page, row, "edit")
        expect(page.locator('[data-test="case-create-dialog"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="case-create-dialog"] .el-dialog__title')).to_contain_text("编辑", timeout=5000)
        selected_tags = page.locator('[data-test="case-create-tags"] .el-tag')
        assert selected_tags.count() == 2, f"编辑预填标签数应为 2: {selected_tags.count()}"
        selected_text = page.locator('[data-test="case-create-tags"] .el-tag').all_inner_texts()
        assert any(f"N7E冒烟_{tag}" in s for s in selected_text), f"预填缺冒烟标签: {selected_text}"
        assert any(f"N7E回归_{tag}" in s for s in selected_text), f"预填缺回归标签: {selected_text}"
        assignee_display = page.locator('[data-test="case-create-assignee"] .el-select__selected-item').all_inner_texts()
        assert any(f"N7测试员_{tag}" in s for s in assignee_display), f"预填分配人缺失: {assignee_display}"

        page.locator('[data-test="case-create-tags"] .el-tag').filter(has_text=f"N7E冒烟_{tag}").locator(".el-tag__close").click()
        page.locator('[data-test="case-create-assignee"]').hover()
        page.locator('[data-test="case-create-assignee"] .el-select__clear').click()
        page.keyboard.press("Escape")
        page.locator('[data-test="case-create-submit"]').click()
        expect(page.locator(".el-message--success").last).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="case-create-dialog"]:visible')).to_have_count(0, timeout=5000)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-table"] tbody tr').first).to_be_visible(timeout=10000)
        row_text = _row_by_title(page, title).inner_text()
        assert f"N7E回归_{tag}" in row_text, f"reload 后应保留回归标签: {row_text}"
        assert f"N7E冒烟_{tag}" not in row_text, f"reload 后冒烟标签应已移除: {row_text}"
        assert "未分配" in row_text, f"reload 后应显示未分配: {row_text}"

        assert_no_errors(collectors, "N7 编辑预填")
    finally:
        api.delete(f"/projects/{pid}/purge?force=true")
        api.delete(f"/users/{uid}")


def test_n7_quick_create_tag(page, collectors, api_client, uniq):
    pass
    api = api_client
    tag = uniq
    pid = _mk_project(api, tag)
    uid = _mk_tester(api, tag)
    r = api.post(f"/projects/{pid}/members", json={"userId": uid, "role": 1})
    assert r.status_code == 200 and r.json().get("code") == "0", f"加成员失败: {r.text[:300]}"
    new_tag = f"N7快速_{tag}"
    title = f"N7快建用例_{tag}"
    try:
        inject_token(page)
        _go_cases(page, tag)

        _open_create(page)
        page.locator('[data-test="case-create-title"]').fill(title)
        page.locator('[data-test="case-create-tags"]').click()
        page.locator('[data-test="case-create-tags"] input').press_sequentially(new_tag)
        with page.expect_response(lambda r: "/api/v1/tags" in r.url and r.request.method == "POST", timeout=5000):
            page.locator('[data-test="case-create-tags"] input').press("Enter")
        expect(page.locator('[data-test="case-create-tags"] .el-tag')).to_have_count(1, timeout=5000)
        _submit_create(page)
        expect(page.locator('[data-test="case-create-dialog"]:visible')).to_have_count(0, timeout=5000)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-table"] tbody tr').first).to_be_visible(timeout=10000)
        row_text = _row_by_title(page, title).inner_text()
        assert new_tag in row_text, f"reload 后快速新建标签缺失: {row_text}"

        assert_no_errors(collectors, "N7 快速新建标签")
    finally:
        api.delete(f"/projects/{pid}/purge?force=true")
        api.delete(f"/users/{uid}")
