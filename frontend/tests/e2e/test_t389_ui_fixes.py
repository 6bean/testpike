
from playwright.sync_api import expect

from conftest import pick_project_in_header  # anchor migration

from conftest import BASE_URL, assert_no_errors, nav_to

DRAWER = '[data-test="help-drawer"]'


def _drawer_body_title(drawer):
    pass
    return drawer.locator('h2.help-doc-title').first


def test_t389_upgrade_help_anchor(logged_in, collectors):
    pass
    page = logged_in
    page.goto(f"{BASE_URL}/#/cluster/upgrade", wait_until="domcontentloaded")
    expect(page.locator('[data-test="upgrade-help-btn"]')).to_be_visible(timeout=10000)

    page.locator('[data-test="upgrade-help-btn"]').click()
    drawer = page.locator(DRAWER)
    expect(drawer).to_be_visible(timeout=8000)
    body = _drawer_body_title(drawer)
    expect(body).to_contain_text("升级中心", timeout=8000)
    expect(body).not_to_contain_text("管理员：权限、配置与平台保障")

    assert_no_errors(collectors, "T389 升级中心帮助锚跳专章")


def test_t389_configs_help_anchor(logged_in, collectors):
    pass
    page = logged_in
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    nav_to(page, "系统配置", "/configs", '[data-test="config-page"]', submenu="系统管理")
    help_icon = page.locator('[data-test="menu-configs-help"]')
    expect(help_icon).to_be_visible(timeout=10000)
    help_icon.click()
    drawer = page.locator(DRAWER)
    expect(drawer).to_be_visible(timeout=8000)
    expect(_drawer_body_title(drawer)).to_contain_text("管理员", timeout=8000)

    assert_no_errors(collectors, "T389 系统配置帮助锚跳")


def test_t389_apitest_widescreen(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    name = f"T389宽屏项目_{uniq}"
    r = api_client.post("/projects", json={"name": name, "code": f"T389{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    rc = api_client.post("/apitest/collections", json={"projectId": pid, "name": f"宽屏集合_{uniq}"})
    assert rc.status_code == 200 and rc.json().get("code") == "0", f"创建集合失败: {rc.text[:300]}"
    col = rc.json()["data"]
    col_id = col["id"] if isinstance(col, dict) else col

    try:
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        # anchor migration: page-level project selector retired — the header
        # global context picks the project; the rail card chain auto-opens the
        # collection's first request (empty collection = newRequest), so the
        # workspace .ap-form renders for the streaming-width measurement.
        nav_to(page, "API 测试", "/api-test", '[data-test="apitest-collection-list"]', submenu="执行测试")
        pick_project_in_header(page, name)

        form = page.locator(".ap-form").first
        expect(form).to_be_visible(timeout=10000)

        for width in (1920, 1280):
            page.set_viewport_size({"width": width, "height": 1080})
            expect(form).to_be_visible(timeout=8000)
            gap = page.evaluate("""() => {
                const f = document.querySelector('.ap-form');
                if (!f) return -1;
                const fr = f.getBoundingClientRect();
                // 主内容滚动容器右缘=表单所在内容区可用宽度基准
                let c = f.parentElement;
                while (c && c !== document.body) {
                    const cs = getComputedStyle(c);
                    if (['auto', 'scroll'].includes(cs.overflowX) || c.classList.contains('page-root')) break;
                    c = c.parentElement;
                }
                const cr = (c || document.body).getBoundingClientRect();
                return Math.round(cr.right - fr.right);
            }""")
            assert 0 <= gap <= 48, (
                f"viewport={width}: 表单右缘距内容区右缘 {gap}px（>48=仍有硬上限未流式；负值=溢出）。"
                "修复前 1920 实测空 ~600px（max-width:1080）。"
            )

        assert_no_errors(collectors, "T389 api-test 宽屏流式")
    finally:
        api_client.delete(f"/apitest/collections/{col_id}")
