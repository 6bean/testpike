
import pytest
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors


ENV_TYPES = ["开发环境", "测试环境", "预发环境", "生产环境（base_url 必填）"]
TAG_CLASS = {1: "el-tag--success", 2: "el-tag--primary", 3: "el-tag--warning", 4: "el-tag--danger"}
TAG_TEXT = {1: "开发环境", 2: "测试环境", 3: "预发环境", 4: "生产环境"}


@pytest.fixture
def project(api_client, uniq):
    pass
    name = f"环境集项目{uniq}"
    code = f"ENV{uniq.upper()}"
    resp = api_client.post("/projects", json={"name": name, "code": code, "description": "E2E v1.3 环境集合测试"})
    assert resp.status_code == 200, f"创建项目失败: {resp.status_code} {resp.text[:300]}"
    body = resp.json()
    assert body.get("code") == "0", f"创建项目业务失败: {body.get('msg')}"
    pid = body["data"]
    yield pid, name
    api_client.delete(f"/projects/{pid}")


def _open_env_dialog(page, pid):
    page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
    page.wait_for_selector('[data-test="env-table"]', timeout=8000)
    page.click('button[data-test="env-add"]')
    page.wait_for_selector('div[data-test="env-dialog"]', timeout=5000)
    expect(page.locator('[data-test="env-type-select"]')).to_be_visible(timeout=10000)


def _select_env_type(page, option_text):
    pass
    page.click('[data-test="env-type-select"]')
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible", has_text=option_text).first.wait_for(
        state="visible", timeout=5000)
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible", has_text=option_text).first.click()


def _submit_env(page):
    page.click('button[data-test="env-submit"]')


def _whitelist_409(collectors, env_url):
    pass
    collectors["network_4xx"] = [
        e for e in collectors["network_4xx"]
        if not (e["status"] == 409 and "/envs" in e["url"])
    ]
    collectors["console_errors"] = [
        e for e in collectors["console_errors"] if "status of 409" not in e
    ]


def test_env_v13_four_types_prod_required_and_tags(logged_in, collectors, project, uniq):
    pass
    page = logged_in
    pid, name = project
    base_url = f"https://e2e-{uniq}.example.com"

    _open_env_dialog(page, pid)
    page.click('[data-test="env-type-select"]')
    page.wait_for_selector(".el-select-dropdown__item:visible", timeout=5000)
    options = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").all_inner_texts()
    for t in ENV_TYPES:
        assert any(t in o for o in options), f"下拉应含 {t}: {options}"
    page.locator(".el-dialog__header").click()
    page.wait_for_timeout(300)

    _select_env_type(page, "生产环境（base_url 必填）")
    page.fill('input[data-test="env-name-input"]', f"prod-{uniq}")
    page.fill('input[data-test="env-url-input"]', "")
    before = len([r for r in collectors["requests"] if r["method"] == "POST" and "/envs" in r["url"]])
    _submit_env(page)
    expect(page.locator(".el-message--warning")).to_be_visible(timeout=10000)
    warn_text = page.locator(".el-message--warning").last.inner_text()
    assert "生产环境必须配置访问地址" in warn_text, f"人话提示: {warn_text}"
    after = [r for r in collectors["requests"] if r["method"] == "POST" and "/envs" in r["url"]]
    assert len(after) == before, "生产 base_url 为空时前端应拦截，不得发出 POST /envs"

    names = {1: f"dev-{uniq}", 2: f"test-{uniq}", 3: f"staging-{uniq}", 4: f"prod-{uniq}"}
    for idx, env_type in enumerate((1, 2, 3, 4)):
        if idx > 0:
            page.click('button[data-test="env-add"]')
            page.wait_for_selector('div[data-test="env-dialog"]', timeout=5000)
            expect(page.locator('[data-test="env-type-select"]')).to_be_visible(timeout=10000)
        _select_env_type(page, TAG_TEXT[env_type])
        page.fill('input[data-test="env-name-input"]', names[env_type])
        page.fill('input[data-test="env-url-input"]', base_url if env_type == 4 else f"http://{env_type}-{uniq}.example.com")
        with page.expect_response(lambda r: r.request.method == "POST" and "/envs" in r.url):
            _submit_env(page)
        page.wait_for_selector('div[data-test="env-dialog"]', state="detached", timeout=12000)
    page.keyboard.press("Escape")

    page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
    rows = page.locator('[data-test="env-table"] tbody tr')
    expect(rows).to_have_count(4, timeout=8000)
    texts = rows.all_inner_texts()
    for env_type in (1, 2, 3, 4):
        row = rows.filter(has_text=names[env_type])
        assert TAG_TEXT[env_type] in row.inner_text(), f"{names[env_type]} 行应显示类型标签 {TAG_TEXT[env_type]}: {texts}"
        expect(row.locator(f".{TAG_CLASS[env_type]}")).to_be_visible(timeout=5000), f"标签着色 {TAG_CLASS[env_type]}"

    env_url = f"{BASE_URL.replace('5173', '8081')}/api/v1/projects/{pid}/envs"
    _open_env_dialog(page, pid)
    _select_env_type(page, "开发环境")
    page.fill('input[data-test="env-name-input"]', f"dev2-{uniq}")
    page.fill('input[data-test="env-url-input"]', "http://dev2.example.com")
    with page.expect_response(lambda r: r.request.method == "POST" and "/envs" in r.url and r.status == 409):
        _submit_env(page)
    expect(page.locator(".el-message--error").last).to_be_visible(timeout=10000)
    err_text = page.locator(".el-message--error").last.inner_text()
    assert "该项目已存在开发环境" in err_text, f"409 人话提示: {err_text}"
    _whitelist_409(collectors, env_url)
    page.keyboard.press("Escape")
    page.wait_for_selector('div[data-test="env-dialog"]', state="detached", timeout=12000)

    row = page.locator('[data-test="env-table"] tbody tr').filter(has_text=f"dev-{uniq}")
    row.locator('button:has-text("编辑")').click()
    page.wait_for_selector('div[data-test="env-dialog"]', timeout=5000)
    expect(page.locator('[data-test="env-type-select"]')).to_be_visible(timeout=10000)
    _select_env_type(page, "测试环境")
    with page.expect_response(lambda r: r.request.method == "PUT" and "/envs/" in r.url and r.status == 409):
        page.click('button[data-test="env-submit"]')
    expect(page.locator(".el-message--error").last).to_be_visible(timeout=10000)
    err_edit = page.locator(".el-message--error").last.inner_text()
    assert "该项目已存在测试环境" in err_edit, f"编辑改类型 409 人话: {err_edit}"
    _whitelist_409(collectors, env_url)

    assert_no_errors(collectors, "F-1 v1.3 环境集合（4 类型/生产必填/标签/409）")


def test_env_v13_cross_project_collection_isolated(logged_in, collectors, project, uniq, api_client):
    pass
    page = logged_in
    pid_a, _ = project
    name_b = f"环境集B{uniq}"
    resp = api_client.post("/projects", json={"name": name_b, "code": f"ENVB{uniq.upper()}", "description": "E2E v1.3 项目B"})
    assert resp.status_code == 200 and resp.json().get("code") == "0", f"创建项目B失败: {resp.text[:300]}"
    pid_b = resp.json()["data"]

    try:
        for env_type, env_name in ((1, f"devA-{uniq}"), (2, f"testA-{uniq}"), (3, f"stagA-{uniq}"), (4, f"prodA-{uniq}")):
            r = api_client.post(f"/projects/{pid_a}/envs", json={
                "name": env_name, "envType": env_type,
                "baseUrl": f"https://a-{env_type}.example.com" if env_type == 4 else f"http://a-{env_type}.example.com",
                "configJson": "{}", "endTypes": "web", "browsers": "chromium", "versionSnapshot": "v1.0.0",
            })
            assert r.status_code == 200 and r.json().get("code") == "0", f"A 建 {env_type} 失败: {r.text[:300]}"
        for env_type, env_name in ((1, f"devB-{uniq}"), (2, f"testB-{uniq}"), (3, f"stagB-{uniq}")):
            r = api_client.post(f"/projects/{pid_b}/envs", json={
                "name": env_name, "envType": env_type,
                "baseUrl": f"http://b-{env_type}.example.com",
                "configJson": "{}", "endTypes": "web", "browsers": "chromium", "versionSnapshot": "v1.0.0",
            })
            assert r.status_code == 200 and r.json().get("code") == "0", f"B 建 {env_type} 失败: {r.text[:300]}"

        page.goto(f"{BASE_URL}/#/projects/{pid_a}", wait_until="domcontentloaded")
        rows_a = page.locator('[data-test="env-table"] tbody tr')
        expect(rows_a.filter(has_text=f"prodA-{uniq}")).to_be_visible(timeout=8000)
        expect(rows_a).to_have_count(4, timeout=8000)
        text_a = rows_a.all_inner_texts()
        assert any("prodA-" in t for t in text_a), "A 应含生产环境"
        assert not any("devB-" in t for t in text_a), f"A 不应看到 B 的环境（H1 隔离）: {text_a}"

        page.goto(f"{BASE_URL}/#/projects/{pid_b}", wait_until="domcontentloaded")
        rows_b = page.locator('[data-test="env-table"] tbody tr')
        expect(rows_b.filter(has_text=f"devB-{uniq}")).to_be_visible(timeout=8000)
        expect(rows_b).to_have_count(3, timeout=8000)
        text_b = rows_b.all_inner_texts()
        assert not any("生产" in t for t in text_b), f"B 应只有 3 套（无生产）: {text_b}"
        assert not any("devA-" in t for t in text_b), f"B 不应看到 A 的环境（H1 隔离）: {text_b}"

        _open_env_dialog(page, pid_b)
        _select_env_type(page, "生产环境（base_url 必填）")
        page.fill('input[data-test="env-name-input"]', f"prodB-{uniq}")
        page.fill('input[data-test="env-url-input"]', f"https://b-prod-{uniq}.example.com")
        with page.expect_response(lambda r: r.request.method == "POST" and "/envs" in r.url and r.status == 200):
            page.click('button[data-test="env-submit"]')
        page.goto(f"{BASE_URL}/#/projects/{pid_b}", wait_until="domcontentloaded")
        rows_b2 = page.locator('[data-test="env-table"] tbody tr')
        expect(rows_b2).to_have_count(4, timeout=8000)
        text_b2 = rows_b2.all_inner_texts()
        assert any("prodB-" in t for t in text_b2), "B 建生产成功后应 4 套（项目级环境集合可扩展）"

        assert_no_errors(collectors, "F-1 v1.3 跨项目环境集合隔离")
    finally:
        api_client.delete(f"/projects/{pid_b}")
