
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, nav_to


def _open_lang_menu(page):
    page.locator('[data-test="lang-switch"]').click()
    item = page.locator('.el-dropdown-menu__item:visible')
    expect(item.first).to_be_visible(timeout=5000)


def _pick_lang(page, label):
    _open_lang_menu(page)
    page.locator('.el-dropdown-menu__item:visible').filter(has_text=label).first.click()


def _open_design_group(page):
    pass
    box = page.locator('[data-test="group-design"]').first
    expect(box).to_be_visible(timeout=10000)
    if box.get_attribute("aria-expanded") != "true":
        box.locator("> .el-sub-menu__title").first.click()
        expect(box).to_have_attribute("aria-expanded", "true", timeout=5000)


def test_v10_lang_switch_roundtrip(logged_in, collectors):
    pass
    page = logged_in

    _open_design_group(page)
    cases_menu = page.locator('[data-test="menu-cases"], .el-menu-item:has-text("用例管理")').first
    expect(cases_menu).to_be_visible(timeout=10000)

    _pick_lang(page, "English")

    en_cases = page.locator('.el-menu-item:has-text("Test Cases")').first
    expect(en_cases).to_be_visible(timeout=5000)
    zh_leftover = page.locator('.el-menu .el-menu-item:has-text("用例管理")').count()
    assert zh_leftover == 0, "英文态侧边栏仍残留中文关键导航=未译"

    assert page.evaluate("() => document.documentElement.lang") == "en", "html lang 未随 locale 切换"

    page.reload(wait_until="domcontentloaded")
    _open_design_group(page)
    expect(en_cases).to_be_visible(timeout=10000)
    assert page.evaluate("() => localStorage.getItem('tp-locale')") == "en-US", "tp-locale 未持久化"

    for route in ("/dashboard", "/cases"):
        page.goto(f"{BASE_URL}/#{route}", wait_until="domcontentloaded")
        page.wait_for_selector(".layout-header, [data-test='project-ctx-select'], .el-table", timeout=10000)
        overflow = page.evaluate("""() => {
            const bad = [];
            for (const sel of ['.layout-header', '.layout-aside']) {
                const el = document.querySelector(sel);
                if (el && el.scrollWidth > el.clientWidth + 2) {
                    bad.push(`${sel}: scrollWidth=${el.scrollWidth} clientWidth=${el.clientWidth}`);
                }
            }
            return bad;
        }""")
        assert overflow == [], f"英文态 {route} 骨架容器横向溢出: {overflow}"

    _pick_lang(page, "简体中文")
    _open_design_group(page)
    expect(cases_menu).to_be_visible(timeout=5000)
    assert page.evaluate("() => document.documentElement.lang") == "zh-CN", "切回后 html lang 未复位"
    assert page.evaluate("() => localStorage.getItem('tp-locale')") == "zh-CN", "切回后持久化未更新"

    assert_no_errors(collectors, "TC-V10-01 语言切换往返")
