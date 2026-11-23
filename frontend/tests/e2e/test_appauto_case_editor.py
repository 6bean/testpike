
import json
import subprocess

import httpx
import pytest
from conftest import ADMIN_PASSWORD, BASE_URL, api_login, assert_no_errors
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

CORE = "http://localhost:8083"


def sql(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def _mk_project(api, uniq, tag, code_prefix):
    r = api.post("/projects", json={"name": f"W6{tag}项目_{uniq}", "code": f"{code_prefix}{uniq[:6].upper()}"})
    assert r.status_code == 200, f"创建项目({tag})失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"创建项目({tag})业务失败: {body.get('msg')}"
    return body["data"]


def _mk_case(api, pid, uniq, title):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"{title}_{uniq}",
        "precondition": f"前置：设备已连接（{uniq}）",
        "expectedResult": f"预期：App 启动成功且首屏可交互（{uniq}）",
        "priority": 0,
        "confidence": 1,
        "endType": "web",
        "status": 1,
        "steps": [
            {"stepNo": 1, "description": f"启动被测应用主界面（{uniq}）", "isAssert": 0},
            {"stepNo": 2, "description": f"首屏出现登录入口控件（{uniq}）", "isAssert": 1},
        ],
    })
    assert r.status_code == 200, f"创建用例失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"创建用例业务失败: {body.get('msg')}"
    return body["data"]


def _seed_script(case_id, title, step_desc):
    pass
    doc = {
        "version": "v2", "caseId": case_id, "title": title, "endType": "web",
        "steps": [{"stepId": 1, "action": "goto", "url": "http://localhost:8899/login"},
                  {"stepId": 2, "action": "input", "target": {"text": step_desc, "chain": ["text"]},
                   "value": "seed"}],
        "env": {"ENV": "dev"},
    }
    r = httpx.post(f"{CORE}/internal/scripts", timeout=30,
                   json={"caseId": case_id, "schemaVersion": "v2",
                         "actionJson": json.dumps(doc, ensure_ascii=False), "generatedBy": 1})
    assert r.status_code == 200 and r.json().get("code") == "0", f"落脚本基线失败: {r.text[:200]}"


def _open_detail(page, case_id):
    page.goto(f"{page.url.split('#')[0]}#/cases/{case_id}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=15000)


def _inject_token(page):
    pass
    for attempt in (1, 2):
        token = api_login("admin", ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        try:
            expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
            return page, token
        except AssertionError:
            if attempt == 2:
                raise AssertionError("inject_token 注入后未进入工作台")
    raise AssertionError("unreachable")


def _open_tree_dropdown(page):
    page.locator('[data-test="step-tree-add"]').click()


def test_appauto_case_editor_target_and_vocab(page, api_client, collectors, uniq):
    pass
    _inject_token(page)
    pid = _mk_project(api_client, uniq, "目标", "W6T")
    pid_web = _mk_project(api_client, uniq, "对照", "W6B")
    app_case = _mk_case(api_client, pid, uniq, "App目标用例")
    web_case = _mk_case(api_client, pid_web, uniq, "网页对照用例")
    _seed_script(app_case["id"], f"App目标用例_{uniq}", "启动应用基线步")
    _seed_script(web_case["id"], f"网页对照用例_{uniq}", "打开页面基线步")
    try:
        _open_detail(page, app_case["id"])
        page.locator('[data-test="detail-edit"]').first.click()
        grp = page.locator('[data-test="detail-edit-automation-target"]')
        expect(grp).to_be_visible(timeout=10000)
        grp.locator('label:has-text("移动 App")').first.click()
        picker = page.locator('[data-test="appauto-engine-picker"]')
        expect(picker).to_be_visible(timeout=15000)
        mw_card = page.locator('[data-test="picker-card-MOBILEWRIGHT"]')
        expect(mw_card).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="picker-card-MINIPROGRAM_AUTOMATOR"]')).to_have_count(0)
        expect(page.locator('[data-test="picker-avail-green"], [data-test="picker-avail-gray"]').first)\
            .to_be_visible(timeout=10000)

        mw_card.click()
        page.locator('[data-test="detail-save-draft"]').click()
        toast = page.locator('.el-message')
        expect(toast.first).to_be_visible(timeout=15000)
        _open_detail(page, app_case["id"])
        echo = page.locator('[data-test="detail-automation-target"]')
        expect(echo).to_contain_text("移动 App", timeout=10000)
        expect(echo).to_contain_text("MOBILEWRIGHT")

        row = sql(f"SELECT automation_target, appauto_engine FROM tp_test_case WHERE id={app_case['id']}")
        assert row.split("|") == ["app", "MOBILEWRIGHT"], f"DB 对账失败: {row}"

        page.locator('[data-test="detail-script-tree-edit"]').click()
        dialog = page.locator('[data-test="step-tree-dialog"]')
        expect(dialog).to_be_visible(timeout=10000)
        _open_tree_dropdown(page)
        launch_item = page.locator('[data-test="step-tree-add-app.launch"]')
        expect(launch_item.first).to_be_visible(timeout=10000)
        expect(page.locator('[data-test^="step-tree-add-app."]')).to_have_count(29)
        for v in ("deepLink", "openUrl", "rotation", "home", "background",
                  "foreground", "clearData", "grantPermission", "wakeUnlock", "networkProfile"):
            expect(page.locator(f'[data-test="step-tree-add-app.{v}"]').first).to_be_visible(timeout=10000)
        page.locator('[data-test="step-tree-add-app.deepLink"]').first.click()
        page.get_by_text("深链唤起页面").last.click()
        form = page.locator('[data-test="step-tree-form"]')
        expect(form).to_be_visible(timeout=10000)
        # On-screen copy ban for internal document pointers: the contract
        # annotation row was removed (provenance lives in code only), so pin
        # its absence hard instead of asserting its text.
        expect(page.locator('[data-test="step-tree-contract"]')).to_have_count(0)
        assert "\u00a7" not in form.inner_text(), "document section code leaked on-screen"
        url_field = page.locator('[data-test="step-tree-field-url"]')
        expect(url_field).to_be_visible(timeout=10000)
        expect(url_field.locator("input").first).to_be_visible(timeout=10000)
        page.locator('[data-test="step-tree-cancel"]').click()
        page.locator('button:has-text("丢弃草稿")').click()
        expect(dialog).not_to_be_visible(timeout=10000)

        page.goto(f"{BASE_URL}/#/cases/{web_case['id']}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="detail-script-tree-edit"]').click()
        expect(dialog).to_be_visible(timeout=10000)
        _open_tree_dropdown(page)
        expect(page.locator('[data-test="step-tree-add-goto"]').first).to_be_visible(timeout=10000)
        app_items = page.locator('[data-test^="step-tree-add-app."]')
        expect(app_items).to_have_count(0)
        page.keyboard.press("Escape")
        page.keyboard.press("Escape")
        expect(dialog).not_to_be_visible(timeout=10000)

        assert_no_errors(collectors, "T303 用例编辑 App 目标区")
    finally:
        api_client.delete(f"/projects/{pid_web}/purge?force=true")
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_t342_create_harmony_target_auto_engine(page, api_client, collectors, uniq):
    pass
    _inject_token(page)
    pid = _mk_project(api_client, uniq, "三段", "W6S")
    base = page.url.split("#")[0]
    title = f"鸿蒙自动档用例_{uniq}"
    try:
        page.goto(f"{base}#/dashboard", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.get_by_text(f"W6三段项目_{uniq}").first).to_be_visible(timeout=15000)
        page.goto(f"{base}#/cases?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-table"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="case-create"]').first.click()
        expect(page.locator('[data-test="case-create-dialog"]')).to_be_visible()
        et = page.locator('[data-test="case-create-dialog"] .el-form-item').filter(has_text="端属性").first
        et.locator(".el-select").click()
        page.locator('.el-select-dropdown:visible .el-select-dropdown__item:visible:has-text("移动端")').first.click()
        grp = page.locator('[data-test="case-create-automation-target"]')
        expect(grp).to_be_visible(timeout=10000)
        expect(grp.locator('label:has-text("鸿蒙")').first).to_be_visible(timeout=10000)
        expect(grp.locator('label:has-text("移动 App")').first).to_be_visible()
        expect(grp.locator('label:has-text("小程序")').first).to_be_visible()

        picker = page.locator('[data-test="appauto-engine-picker"]').first
        expect(picker).to_be_visible(timeout=15000)
        auto_opt = page.locator('[data-test="picker-auto-option"]').first
        expect(auto_opt).to_be_visible(timeout=10000)
        expect(auto_opt).to_have_attribute("aria-pressed", "true")

        grp.locator('label:has-text("鸿蒙")').first.click()
        expect(page.locator('[data-test="picker-card-HMNEXTAUTO"]').first).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="picker-card-MOBILEWRIGHT"]')).to_have_count(0)
        expect(page.locator('[data-test="picker-card-APPIUM"]')).to_have_count(0)

        page.locator('[data-test="case-create-title"]').fill(title)
        page.locator('[data-test="case-create-submit"]').click()
        toast = page.locator(".el-message")
        expect(toast.first).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="case-create-dialog"]')).not_to_be_visible(timeout=15000)

        cid = sql(f"SELECT id FROM tp_test_case WHERE title='{title}'")
        assert cid.isdigit(), f"创建后 DB 未查到用例行: {cid}"
        _open_detail(page, int(cid))
        echo = page.locator('[data-test="detail-automation-target"]')
        expect(echo).to_contain_text("鸿蒙", timeout=10000)
        expect(echo).to_contain_text("执行引擎：自动")

        row = sql(f"""SELECT c.automation_target, coalesce(c.appauto_engine,'~NULL~')
            FROM tp_test_case c WHERE c.id={cid}""")
        assert row == "harmony|~NULL~", f"DB 对账失败（目标/引擎）: {row}"

        assert_no_errors(collectors, "T342 创建期鸿蒙目标+自动档")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
