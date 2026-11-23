
from conftest import (
    BASE_URL,
    assert_no_errors,
    nav_to,
    sql_query,
)
from playwright.sync_api import expect


def _create_pending_skill(api_client, name: str) -> dict:
    pass
    r = api_client.post("/skills", {
        "projectId": None, "name": name, "type": 1,
        "body": f"## 检查规则\n{name} 造数正文（每步骤须可断言）\n",
        "submitNow": True,
    })
    body = r.json()
    assert body["code"] == "0", f"创建 SKILL 失败: {body}"
    assert body["data"]["status"] == 2, f"应为待审批: {body['data']}"
    return body["data"]


def _cleanup_skill(api_client, sid: int):
    pass
    cur = api_client.get(f"/skills/{sid}").json()["data"]
    if cur["status"] == 2:
        cur = api_client.post(
            f"/skills/{sid}/approve?expectedVersion={cur['version']}").json()["data"]
    if cur["status"] == 3:
        cur = api_client.post(
            f"/skills/{sid}/retire?expectedVersion={cur['version']}").json()["data"]
    dr = api_client.delete(f"/skills/{sid}?expectedVersion={cur['version']}").json()
    assert dr["code"] == "0", f"delete 失败: {dr}"


def _search_row(page, name):
    page.fill('[data-test="skill-keyword"]', name)
    page.click('[data-test="skill-search"]')
    row = page.locator('[data-test="skill-table"] tbody tr').filter(has_text=name).first
    expect(row).to_be_visible(timeout=8000)
    return row



def test_skill_admin_page_smoke(logged_in, collectors):
    page = logged_in
    nav_to(page, "SKILL 管理", "/skills", '[data-test="skill-list-page"]', submenu="AI 能力")
    expect(page.locator('[data-test="skill-table"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="skill-keyword"]')).to_be_visible()
    expect(page.locator('[data-test="skill-create-btn"]')).to_be_visible()
    assert_no_errors(collectors, "SKILL 管理页冒烟")



def test_skill_admin_full_chain(logged_in, collectors, api_client, uniq):
    page = logged_in
    name = f"e2e-skill-{uniq}"
    sid = None
    try:
        nav_to(page, "SKILL 管理", "/skills", '[data-test="skill-list-page"]', submenu="AI 能力")

        page.click('[data-test="skill-create-btn"]')
        dialog = page.locator('[data-test="skill-form-dialog"]')
        expect(dialog).to_be_visible(timeout=5000)
        dialog.locator('[data-test="skill-form-name"]').fill(name)
        page.click('[data-test="skill-form-submit"]')
        expect(page.locator(".el-form-item__error").first).to_contain_text("请输入正文", timeout=3000)

        dialog.locator('[data-test="skill-form-body"]').fill(
            "## 检查规则\n步骤粒度须可独立断言，禁复合动作\n")
        dialog.locator('[data-test="skill-form-frontmatter"]').fill(
            '{"description": "步骤粒度检查"}')
        page.click('[data-test="skill-form-submit-now"]')
        with page.expect_response(
            lambda r: r.request.method == "POST"
            and r.url.startswith(f"{BASE_URL}/api/v1/skills") and r.status == 200
        ):
            page.click('[data-test="skill-form-submit"]')
        expect(page.locator(".el-message--success").first).to_contain_text(
            "已创建并提交审批", timeout=8000)

        page.goto(f"{BASE_URL}/#/skills", wait_until="domcontentloaded")
        expect(page.locator('[data-test="skill-list-page"]')).to_be_visible(timeout=10000)
        row = _search_row(page, name)
        assert "待审批" in row.inner_text(), f"reload 后状态未持久化: {row.inner_text()}"
        sid = int(sql_query(f"SELECT id FROM tp_skill WHERE name='{name}'"))
        assert sql_query(
            f"SELECT frontmatter->>'description' FROM tp_skill WHERE id={sid}"
        ) == "步骤粒度检查", "frontmatter 未落库"

        row.locator('[data-test="ra-approve"]').click()
        box = page.locator(".el-message-box")
        expect(box).to_be_visible(timeout=5000)
        with page.expect_response(
            lambda r: r.request.method == "POST" and f"/api/v1/skills/{sid}/approve" in r.url
        ):
            box.locator('button:has-text("确定")').click()
        expect(row.locator('[data-test="skill-status-tag"]')).to_contain_text(
            "已发布", timeout=8000)

        row.locator('[data-test="ra-edit"]').click()
        edit_dialog = page.locator('[data-test="skill-form-dialog"]')
        expect(edit_dialog).to_be_visible(timeout=5000)
        body_input = edit_dialog.locator('[data-test="skill-form-body"]')
        expect(body_input).to_have_value(
            "## 检查规则\n步骤粒度须可独立断言，禁复合动作\n", timeout=8000)
        body_input.fill("## 检查规则 v2\n步骤粒度须可独立断言；新增：断言须含期望值\n")
        with page.expect_response(
            lambda r: r.request.method == "PUT" and f"/api/v1/skills/{sid}" in r.url
            and r.status == 200
        ):
            edit_dialog.locator('[data-test="skill-form-submit"]').click()
        expect(page.locator(".el-message--success").first).to_contain_text("已保存", timeout=8000)

        assert sql_query(f"SELECT status FROM tp_skill WHERE id={sid}") == "2", \
            "published 改正文应自动重审回 pending"
        assert sql_query(
            f"SELECT count(*) FROM tp_skill_version WHERE skill_id={sid}") == "2", \
            "版本链应新增一条快照"
        assert sql_query(
            f"SELECT current_version FROM tp_skill WHERE id={sid}") == "2"

        cur = api_client.get(f"/skills/{sid}").json()["data"]
        api_client.post(f"/skills/{sid}/approve?expectedVersion={cur['version']}")
        page.goto(f"{BASE_URL}/#/skills", wait_until="domcontentloaded")
        expect(page.locator('[data-test="skill-list-page"]')).to_be_visible(timeout=10000)
        row = _search_row(page, name)
        row.locator('[data-test="ra-more"]').click()
        page.locator('.el-dropdown-menu__item:visible', has_text="版本历史").first.click()
        drawer = page.locator('[data-test="skill-version-drawer"]')
        expect(drawer).to_be_visible(timeout=5000)
        vtable = drawer.locator('[data-test="skill-version-table"]')
        expect(vtable.locator("tbody tr")).to_have_count(2, timeout=8000)
        vtable.locator('[data-test="skill-ver-view-2"]').click()
        expect(drawer.locator('[data-test="skill-version-detail"]')).to_contain_text(
            "断言须含期望值", timeout=5000)
        page.keyboard.press("Escape")

        page.goto(f"{BASE_URL}/#/skills", wait_until="domcontentloaded")
        expect(page.locator('[data-test="skill-list-page"]')).to_be_visible(timeout=10000)
        row = _search_row(page, name)
        row.locator('[data-test="ra-retire"]').click()
        box = page.locator(".el-message-box")
        expect(box).to_be_visible(timeout=5000)
        with page.expect_response(
            lambda r: r.request.method == "POST" and f"/api/v1/skills/{sid}/retire" in r.url
        ):
            box.locator('button:has-text("确定")').click()
        expect(row.locator('[data-test="skill-status-tag"]')).to_contain_text(
            "已下架", timeout=8000)
        _cleanup_skill(api_client, sid)
        assert sql_query(
            f"SELECT deleted FROM tp_skill WHERE id={sid}") == "1", "软删后 deleted 未置 1"
        sid = None
        assert_no_errors(collectors, "SKILL 管理全链")
    finally:
        if sid is not None:
            _cleanup_skill(api_client, sid)
