
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, nav_to, sql_query

PAGE_ROOT = '[data-test="prompt-tpl-page"]'
TABLE = '[data-test="prompt-tpl-table"]'


def _query_family(page, name, domain):
    pass
    name_i = page.locator('[data-test="prompt-tpl-name-input"]')
    domain_i = page.locator('[data-test="prompt-tpl-domain-input"]')
    name_i.click(click_count=3)
    name_i.fill(name)
    domain_i.click(click_count=3)
    domain_i.fill(domain)
    page.locator('[data-test="prompt-tpl-search"]').click()


def _row(page, version):
    return page.locator(f'{TABLE} tbody tr.el-table__row').filter(
        has=page.locator('td', has_text=f"v{version}")).first


def _save_version(page, name, domain, content):
    page.locator('[data-test="prompt-tpl-new"]').click()
    dlg = page.locator('[data-test="prompt-tpl-save-dialog"]')
    expect(dlg).to_be_visible()
    dlg.locator('[data-test="prompt-tpl-save-name"]').fill(name)
    dlg.locator('[data-test="prompt-tpl-save-domain"]').fill(domain)
    dlg.locator('[data-test="prompt-tpl-save-content"]').fill(content)
    dlg.locator('[data-test="prompt-tpl-save-submit"]').click()
    expect(page.locator(".el-message--success").first).to_be_visible(timeout=8000)
    expect(dlg).to_be_hidden(timeout=8000)


def _confirm_box(page):
    box = page.locator('.el-message-box')
    expect(box).to_be_visible()
    box.locator('.el-message-box__btns button.el-button--primary').click()


def test_d4_template_family_first_screen(logged_in, collectors, uniq):
    pass
    page = logged_in
    name = f"e2e-tplfam-{uniq}"
    domain = f"用例生成E2E{uniq}"
    try:
        nav_to(page, "Prompt 模板管理", "/prompt-templates",
               PAGE_ROOT, submenu="AI 能力")

        fam_table = '[data-test="prompt-tpl-family-table"]'
        expect(page.locator(fam_table)).to_be_visible()
        fam_rows = page.locator(f'{fam_table} tbody tr.el-table__row')
        # Plural-safe: strict mode rejects a multi-row locator for
        # to_be_visible(); "families exist" is asserted as a count bound.
        expect(fam_rows).not_to_have_count(0)
        db_families = sql_query(
            "SELECT count(DISTINCT (domain, name)) FROM tp_prompt_template")
        assert int(db_families.strip()) == fam_rows.count(), \
            "family enumeration disagrees with DB distinct families"

        page.locator('[data-test="prompt-tpl-name-input"]').fill(name)
        expect(fam_rows).to_have_count(0)
        _save_version(page, name, domain, f"族首屏骨架 v1 {{{{requirement}}}} {uniq}")
        page.locator('[data-test="prompt-tpl-back-family"]').click()
        expect(page.locator(fam_table)).to_be_visible()
        row = page.locator(f'{fam_table} tbody tr.el-table__row').filter(
            has=page.locator('td', has_text=name)).first
        expect(row).to_be_visible()
        expect(row).to_contain_text(domain)
        expect(row).to_contain_text("v1")
        expect(row.locator('[data-test="prompt-tpl-fam-status-tag"]')).to_have_text("灰度")

        row.locator('[data-test="prompt-tpl-open-family"]').click()
        expect(page.locator('[data-test="prompt-tpl-table"]')).to_be_visible()
        ctx = page.locator('[data-test="prompt-tpl-family-context"]')
        expect(ctx).to_contain_text(name)
        expect(ctx).to_contain_text(domain)

        page.locator('[data-test="prompt-tpl-back-family"]').click()
        expect(page.locator(fam_table)).to_be_visible()

        assert_no_errors(collectors, "d4 family first screen")
    finally:
        sql_query(f"DELETE FROM tp_prompt_template WHERE name = '{name}'")


def test_d4_template_version_rollback_promote(logged_in, collectors, uniq):
    pass
    page = logged_in
    name = f"e2e-tpl-{uniq}"
    domain = f"用例生成E2E{uniq}"
    try:
        nav_to(page, "Prompt 模板管理", "/prompt-templates",
               PAGE_ROOT, submenu="AI 能力")

        _save_version(page, name, domain, "骨架甲 v1 {{requirement}}")
        expect(_row(page, 1).locator('[data-test="prompt-tpl-status-tag"]')).to_have_text("灰度")

        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        nav_to(page, "Prompt 模板管理", "/prompt-templates",
               PAGE_ROOT, submenu="AI 能力")
        _query_family(page, name, domain)
        expect(page.locator(f'{TABLE} tbody tr.el-table__row')).to_have_count(1)

        _save_version(page, name, domain, "骨架乙 v2 {{requirement}} {{fewshot}}")
        expect(page.locator(f'{TABLE} tbody tr.el-table__row')).to_have_count(2)
        expect(page.locator(f'{TABLE} tbody tr.el-table__row').first).to_contain_text("v2")

        _row(page, 1).locator('[data-test="prompt-tpl-rollback"]').click()
        _confirm_box(page)
        expect(page.locator(".el-message--success").first).to_be_visible(timeout=8000)
        expect(page.locator(f'{TABLE} tbody tr.el-table__row')).to_have_count(3)
        expect(_row(page, 3).locator('[data-test="prompt-tpl-status-tag"]')).to_have_text("灰度")
        rb = sql_query(
            "SELECT rollback_to = (SELECT id FROM tp_prompt_template "
            f"WHERE name='{name}' AND domain='{domain}' AND version=1) "
            "FROM tp_prompt_template "
            f"WHERE name='{name}' AND domain='{domain}' AND version=3")
        assert rb.strip() == "t", f"回滚来源库面取证失败: rollback_to≠v1 行 id（{rb}）"

        _row(page, 2).locator('[data-test="prompt-tpl-promote"]').click()
        _confirm_box(page)
        expect(_row(page, 2).locator('[data-test="prompt-tpl-status-tag"]')).to_have_text("全量")

        _row(page, 2).locator('[data-test="prompt-tpl-view"]').click()
        drawer = page.locator('[data-test="prompt-tpl-detail-drawer"]')
        expect(drawer).to_be_visible()
        body = drawer.locator('[data-test="prompt-tpl-detail-content"]').inner_text()
        assert "骨架乙 v2" in body and "{{fewshot}}" in body, f"抽屉正文不完整: {body[:120]}"
        page.keyboard.press("Escape")

        assert_no_errors(collectors, "D4 模板管理页")
    finally:
        sql_query(f"DELETE FROM tp_prompt_template WHERE name='{name}'")
        residue = sql_query(
            f"SELECT count(*) FROM tp_prompt_template WHERE name='{name}'")
        assert int(residue) == 0, f"模板族残留未清: {residue}"
