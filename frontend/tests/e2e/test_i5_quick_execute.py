
import json
import re
import subprocess
import time

from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, nav_to, pick_project_in_header


def sql(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


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


def create_quick_dataset(api, uniq):
    pass
    r = api.post("/projects", json={"name": f"I5快执项目{uniq}", "code": f"I5Q{uniq[:6]}"})
    assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    env_name = f"I5快执环境{uniq[:6]}"
    r = api.post(f"/projects/{pid}/envs", json={
        "name": env_name, "envType": 2,
        "baseUrl": "http://localhost:8899", "endTypes": "web",
        "versionSnapshot": f"build-{uniq[:6]}"})
    assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
    env_id = r.json()["data"]

    title = f"I5快执用例{uniq[:6]}"
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "无",
        "expectedResult": "符合预期", "priority": 1, "confidence": 2,
        "endType": "web", "status": 3, "phase": 2,
        "steps": [{"stepNo": 1, "description": "打开演示页验证欢迎语", "isAssert": 1}]})
    assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    case_id = r.json()["data"]["id"]

    action = {"steps": [
        {"action": "goto", "url": "http://localhost:8899/demo_page.html"},
        {"action": "expect", "text": "欢迎"}]}
    sql(f"INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by) "
        f"VALUES ({case_id}, 'v2', '{json.dumps(action, ensure_ascii=False)}', 1, 1)")
    return pid, env_id, case_id, title, env_name


def wait_batch_terminal(api, bid, timeout_sec=120):
    pass
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        st = sql(f"SELECT status FROM tp_exec_batch WHERE id = {bid}")
        if st in ("4", "5", "6", "7", "8"):
            return st
        time.sleep(2)
    return None


def cleanup(api, pid):
    pass
    try:
        sql(f"UPDATE tp_exec_batch SET status = 1 WHERE project_id = {pid}")
    except subprocess.CalledProcessError:
        pass
    api.delete(f"/projects/{pid}")
    api.delete(f"/projects/{pid}/purge?force=true")


def test_i5_quick_execute_flow(page, collectors, api_client, uniq):
    pass
    pid, env_id, case_id, title, env_name = create_quick_dataset(api_client, uniq)
    bid = None
    try:
        inject_token(page)

        page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="detail-script"]')).to_contain_text("已生成", timeout=15000)
        quick_btn = page.locator('[data-test="detail-quick-execute"]')
        expect(quick_btn).to_be_visible()
        expect(quick_btn).to_be_enabled()

        quick_btn.click()
        env_dlg = page.locator('[data-test="env-select-dialog"]')
        expect(env_dlg).to_be_visible(timeout=10000)
        expect(env_dlg).to_contain_text("选择快速执行环境")
        page.locator('[data-test="env-select"]').click()
        opt = page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{env_name}"):visible').first
        expect(opt).to_be_visible(timeout=5000)
        opt.click()
        confirm = page.locator('[data-test="env-select-confirm"]')
        expect(confirm).to_contain_text("开始快速执行")
        confirm.click()

        dlg = page.locator('[data-test="quick-result-dialog"]')
        expect(dlg).to_be_visible(timeout=10000)
        msg = dlg.locator('[data-test="quick-result-message"]')
        expect(msg).to_contain_text("1 个执行任务", timeout=5000)
        expect(dlg).to_contain_text(re.compile(r"第 \d+ 位"))
        expect(page.locator('.el-message--success:has-text("快速执行已提交")').first).to_be_visible(timeout=10000)

        dlg.locator('[data-test="quick-result-goto"]').click()
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-detail-title"]')).to_contain_text(f"快速执行-{title}")

        m = re.search(r"/batches/(\d+)", page.url)
        assert m, f"跳转后 URL 未含批次 ID: {page.url}"
        bid = int(m.group(1))
        assert sql(f"SELECT type FROM tp_exec_batch WHERE id = {bid}") == "2", \
            "快速执行未建临时批（type 应为 2）"
        assert sql(f"SELECT name FROM tp_exec_batch WHERE id = {bid}") == f"快速执行-{title}"
        assert sql(f"SELECT count(*) FROM tp_batch_case WHERE batch_id = {bid}") == "1"
        assert sql(f"SELECT count(*) FROM tp_exec_task WHERE batch_id = {bid}") == "1"
        assert sql(f"SELECT status FROM tp_exec_batch WHERE id = {bid}") != "1", \
            "临时批未启动（仍草稿态）——start 链失效"

        nav_to(page, "执行批次", "/batches", '[data-test="batch-table"]', submenu="执行测试")
        # Assertion fix : the per-page project selector was retired
        # with the IA pass — the global header context is the single switch.
        pick_project_in_header(page, f"I5快执项目{uniq}")
        expect(page.locator('[data-test="batch-search-btn"]')).to_be_visible()
        page.locator('[data-test="batch-search-btn"]').click()
        expect(page.locator('[data-test="batch-table"] tbody tr')).to_have_count(0, timeout=10000)

        page.goto(f"{BASE_URL}/#/batches/{bid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-detail-title"]')).to_contain_text(f"快速执行-{title}")
        expect(page.locator('[data-test="batch-case-list"] tbody tr')
               .filter(has_text=title).first).to_be_visible(timeout=10000)

        st = wait_batch_terminal(api_client, bid)
        assert st is not None, \
            "120s 内批次未离开草稿态——活体执行器未领取（检查 runner 活体/容量）"

        assert_no_errors(collectors, "I9 快速执行 详情→临时批→结果回显→列表排除 全链")
    finally:
        if pid:
            cleanup(api_client, pid)
        if bid:
            assert sql(f"SELECT count(*) FROM tp_exec_batch WHERE id = {bid}") == "0", \
                "purge 后临时批仍残留"
