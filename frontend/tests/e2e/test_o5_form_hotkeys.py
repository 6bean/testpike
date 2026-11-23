
from playwright.sync_api import expect

from conftest import assert_no_errors, inject_token, nav_to, pick_project_in_header


DEFECT_MENU = "缺陷库"


def _mk_project(api, uniq):
    pass
    r = api.post("/projects", json={"name": f"O5E2E_{uniq}", "code": f"O5E{uniq.upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _select_project(page, test_id, uniq):
    # Assertion fix : the defect page lost its per-page project
    # selector in the IA pass (header context is the single switch there),
    # while the cases page still carries its own anchor — branch by anchor.
    if page.locator(f"[data-test={test_id}]").count() > 0:
        page.locator(f"[data-test={test_id}]").click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"O5E2E_{uniq}").first.click()
    else:
        pick_project_in_header(page, f"O5E2E_{uniq}")


def _go_cases(page, uniq):
    nav_to(page, "用例管理", "/cases", '[data-test="case-table"]', submenu="设计用例")
    _select_project(page, "project-ctx-select", uniq)
    rows_or_empty = page.locator('[data-test="case-table"] tbody tr, [data-test="case-empty"]').first
    expect(rows_or_empty).to_be_visible(timeout=10000)


def _open_case_create(page):
    page.locator('[data-test="case-create"]').click()
    expect(page.locator('[data-test="case-create-dialog"]')).to_be_visible(timeout=10000)


def _title_form_item(page):
    return page.locator('[data-test="case-create-title"]').locator(
        "xpath=ancestor::div[contains(@class,'el-form-item')][1]"
    )


def test_o5_esc_closes(page, api_client, collectors, uniq):
    pass
    pid = _mk_project(api_client, uniq)
    inject_token(page)
    _go_cases(page, uniq)

    _open_case_create(page)
    page.keyboard.press("Escape")
    expect(page.locator('[data-test="case-create-dialog"]:visible')).to_have_count(0)
    expect(page.locator(".el-message-box:visible")).to_have_count(0)

    _open_case_create(page)
    page.locator('[data-test="case-create-title"]').fill(f"草稿_{uniq}")
    page.keyboard.press("Escape")
    confirm = page.locator(".el-message-box:visible")
    expect(confirm).to_be_visible(timeout=5000)
    confirm.locator("button").filter(has_text="丢弃").click()
    expect(page.locator('[data-test="case-create-dialog"]:visible')).to_have_count(0)

    assert_no_errors(collectors, "o5 esc closes")
    api_client.delete(f"/projects/{pid}")


def test_o5_ctrl_s_submits(page, api_client, collectors, uniq):
    pass
    pid = _mk_project(api_client, uniq)
    inject_token(page)
    nav_to(page, DEFECT_MENU, "/defects", '[data-test="defect-list-page"]', submenu="结果与缺陷")
    _select_project(page, "defect-project-select", uniq)

    page.locator('[data-test="defect-create-btn"]').click()
    expect(page.locator('[data-test="defect-create-dialog"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="defect-form-title"]').fill(f"O5缺陷_{uniq}")
    page.locator('[data-test="defect-form-severity"]').click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").first.click()

    posts = []
    page.on("request", lambda r: posts.append(r) if "/api/v1/defect-library" in r.url and r.method == "POST" else None)
    with page.expect_response(
        lambda r: "/api/v1/defect-library" in r.url and r.request.method == "POST", timeout=10000
    ) as resp_info:
        page.keyboard.press("Control+s")
        page.keyboard.press("Control+s")
    body = resp_info.value.json()
    assert body.get("code") == "0", f"Ctrl+S 提交失败: {body}"
    expect(page.locator(".el-message--success").last).to_be_visible(timeout=10000)
    page.wait_for_timeout(1200)
    assert len(posts) == 1, f"提交防重复失效，发出 {len(posts)} 个 POST: {[r.url for r in posts]}"

    nav_to(page, "工作台", "/dashboard", '[data-test="my-project-table"]', submenu="工作台")
    nav_to(page, DEFECT_MENU, "/defects", '[data-test="defect-table"]', submenu="结果与缺陷")
    expect(page.locator('[data-test="defect-table"]').locator("text=O5缺陷").first).to_be_visible(timeout=10000)

    assert_no_errors(collectors, "o5 ctrl+s submits")
    api_client.delete(f"/projects/{pid}")


def test_o5_scroll_to_error(page, api_client, collectors, uniq):
    pass
    pid = _mk_project(api_client, uniq)
    inject_token(page)
    _go_cases(page, uniq)
    _open_case_create(page)

    for _ in range(30):
        page.locator('[data-test="case-add-step"]').click()
    scroller = page.locator(".el-overlay-dialog:visible").last
    scroller.evaluate("el => el.scrollTop = el.scrollHeight")

    title_item = _title_form_item(page)
    expect(title_item).not_to_be_in_viewport(timeout=5000)

    reqs = []
    page.on("request", lambda r: reqs.append(r) if "/api/v1/cases" in r.url and r.method == "POST" else None)
    page.keyboard.press("Control+s")
    expect(title_item).to_be_in_viewport(timeout=5000)
    expect(page.locator('[data-test="case-create-dialog"] .el-form-item.is-error')).to_have_count(1)
    page.wait_for_timeout(1000)
    assert len(reqs) == 0, f"校验失败仍发出了创建请求: {[r.url for r in reqs]}"

    assert_no_errors(collectors, "o5 scroll to error")
    api_client.delete(f"/projects/{pid}")
