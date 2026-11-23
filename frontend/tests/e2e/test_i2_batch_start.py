
import re
import subprocess

from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, nav_to

BATCH_MENU = "执行批次"


def sql(q):
    subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)


def inject_token(page):
    pass
    from conftest import ADMIN_PASSWORD, api_login
    for attempt in (1, 2):
        token = api_login("admin", ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        try:
            expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
            return page
        except AssertionError:
            if attempt == 2:
                raise AssertionError("inject_token 注入后未进入工作台（/users/me 疑似网络抖动被踢回登录）")
    raise AssertionError("unreachable")


def create_start_dataset(api, uniq):
    pass
    r = api.post("/projects", json={"name": f"I2启动项目{uniq}", "code": f"I2S{uniq[:6]}"})
    assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    r = api.post(f"/projects/{pid}/envs", json={
        "name": f"I2启动环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8899", "endTypes": "web",
        "versionSnapshot": f"build-{uniq[:6]}"})
    assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
    env_id = r.json()["data"]

    def mk_case(title, status):
        r = api.post("/cases", json={
            "projectId": pid, "title": f"{title}{uniq[:6]}", "precondition": "无",
            "expectedResult": "符合预期", "priority": 1, "confidence": 2,
            "endType": "web", "status": status, "phase": 2,
            "steps": [{"stepNo": 1, "description": "打开演示页", "isAssert": 1}]})
        assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
        return r.json()["data"]["id"]

    case_script = mk_case("I2有脚本", 3)
    case_noscript = mk_case("I2无脚本", 3)
    case_uneval = mk_case("I2未评审", 2)
    case_flaky = mk_case("I2隔离用例", 3)
    sql(f"UPDATE tp_test_case SET flaky = 1 WHERE id = {case_flaky}")

    action = {"steps": [
        {"action": "goto", "url": "http://localhost:8899/demo_page.html"},
        {"action": "input", "target": {"css": "#username"}, "value": "e2e-i2"},
        {"action": "click", "target": {"css": "#login-btn"}},
        {"action": "expect", "text": "欢迎"}]}
    import json as _json
    sql(f"INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by) "
        f"VALUES ({case_script}, 'v2', '{_json.dumps(action, ensure_ascii=False)}', 1, 1)")

    r = api.post("/batches", json={
        "projectId": pid, "name": f"I2启动批{uniq[:6]}", "envId": env_id,
        "caseIds": [case_script, case_noscript, case_uneval, case_flaky]})
    assert r.json().get("code") == "0", f"建批失败: {r.text[:300]}"
    return pid, r.json()["data"]["id"]


def cleanup(api, pid):
    pass
    try:
        sql(f"UPDATE tp_exec_batch SET status = 1 WHERE project_id = {pid}")
    except subprocess.CalledProcessError:
        pass
    api.delete(f"/projects/{pid}")
    api.delete(f"/projects/{pid}/purge?force=true")


def test_i2_batch_start_flow(page, collectors, api_client, uniq):
    pass
    pid, bid = create_start_dataset(api_client, uniq)
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/batches/{bid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-detail-status"]')).to_contain_text("草稿")
        expect(page.locator('[data-test="batch-case-list"] tbody tr')).to_have_count(4, timeout=10000)
        expect(page.locator('[data-test="batch-start-btn"]')).to_be_visible()

        page.locator('[data-test="batch-start-btn"]').click()
        box = page.locator('.el-message-box')
        expect(box).to_be_visible(timeout=5000)
        expect(box).to_contain_text("确认启动批次")
        expect(box).to_contain_text("不可再编辑")
        box.locator('.el-message-box__btns button.el-button--primary').click()

        dlg = page.locator('[data-test="batch-start-result-dialog"]')
        expect(dlg).to_be_visible(timeout=10000)
        msg = page.locator('[data-test="batch-start-message"]')
        expect(msg).to_contain_text("已生成 1 个执行任务")
        expect(msg).to_contain_text("1 条用例无脚本跳过")
        expect(msg).to_contain_text("未通过评审用例")
        expect(msg).to_contain_text("不稳定隔离用例")
        expect(dlg).to_contain_text("待执行")
        expect(dlg.locator('.start-meta')).to_contain_text(re.compile(r"第 \d+ 位"))
        skip_rows = page.locator('[data-test="batch-skip-table"] .el-table__body tbody tr')
        expect(skip_rows).to_have_count(2, timeout=5000)
        expect(page.locator('[data-test="batch-skip-table"]')).to_contain_text("已隔离（不稳定）")

        expect(page.locator('.el-message--success').first).to_contain_text("批次已启动，进入排队执行", timeout=10000)
        page.locator('[data-test="batch-start-result-close"]').click()

        expect(page.locator('[data-test="batch-detail-status"]')).to_contain_text(
            re.compile("待执行|执行中|已完成|部分失败"), timeout=10000)
        expect(page.locator('[data-test="batch-start-btn"]')).to_have_count(0)
        expect(page.locator('[data-test="batch-add-case-btn"]')).to_have_count(0)
        expect(page.locator('[data-test="batch-draft-tip"]')).to_contain_text("不可再编辑")

        detail_url = page.url
        page.goto(detail_url, wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-detail-status"]')).to_contain_text(
            re.compile("待执行|执行中|已完成|部分失败"))
        expect(page.locator('[data-test="batch-start-btn"]')).to_have_count(0)
        expect(page.locator('[data-test="batch-case-list"] tbody tr')).to_have_count(4, timeout=10000)

        assert_no_errors(collectors, "I2 批次启动+结果对话框+reload 待执行持久化")
    finally:
        cleanup(api_client, pid)
