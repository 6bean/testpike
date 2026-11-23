# -*- coding: utf-8 -*-

from playwright.sync_api import expect

from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    nav_to,
    sql_query,
)

FIRE_PAGE = '[data-test="schedule-fire-page"]'
FIRE_TABLE = '[data-test="schedule-fire-table"]'
TPL_TABLE = '[data-test="batch-template-table"]'
SCHEDULE_DLG = '[data-test="batch-schedule-dialog"]'
LONG_MSG = "补偿触发：服务重启期间错过 2 个触发点，窗口内补跑建批失败——环境参数缺失（人话全文超长文案不截断断言用）"


def _seed_dataset(api, uniq):
    pass
    r = api.post("/projects", json={"name": f"S254项目{uniq}", "code": f"S254E{uniq[:6]}"})
    assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    r = api.post(f"/projects/{pid}/envs", json={
        "name": f"S254环境{uniq}", "envType": 2,
        "baseUrl": f"https://s254-{uniq[:6]}.tp-local.test", "endTypes": "web"})
    assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
    env_id = r.json()["data"]
    case_ids = []
    for title in ("登录用例", "下单用例"):
        r = api.post("/cases", json={
            "projectId": pid, "title": f"{title}{uniq[:6]}", "precondition": "无",
            "expectedResult": "符合预期", "priority": 1, "confidence": 2,
            "endType": "web", "status": 3,
            "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
        assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
        case_ids.append(r.json()["data"]["id"])
    r = api.post("/batches", json={"projectId": pid, "name": f"S254批次{uniq[:6]}",
                                   "envId": env_id, "caseIds": case_ids})
    assert r.json().get("code") == "0", f"建批失败: {r.text[:300]}"
    batch_id = r.json()["data"]["id"] if isinstance(r.json()["data"], dict) else r.json()["data"]
    r = api.post(f"/batches/{batch_id}/save-as-template", json={"templateName": f"S254模板{uniq[:6]}"})
    assert r.json().get("code") == "0", f"存模板失败: {r.text[:300]}"
    tpl_id = r.json()["data"]["id"]
    return pid, env_id, tpl_id


def _seed_fire(pid, tpl_id):
    pass
    sql_query(
        "INSERT INTO tp_schedule_fire (kind, ref_id, project_id, fire_time, status, message) "
        f"VALUES ('template', {tpl_id}, {pid}, now(), 'failed', '{LONG_MSG}')")


def _select_option(page, text):
    page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{text}"):visible').first.click()


def _pick_header_project(page, project_name):
    pass
    page.locator('[data-test="project-ctx-select"]').click()
    page.locator(
        f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{project_name}"):visible'
    ).first.click()


def _goto_fire_page(page, uniq):
    # half-B merge: the fires leaf lives as the schedule-fires tab of
    # the batches page (menu leaf retired; /schedule/fires redirects there).
    # Project segment follows the header context .
    nav_to(page, "执行批次", "/batches", '[data-test="batch-table"]', submenu="执行测试")
    page.locator('[data-test="batch-tab-fires"]').first.click()
    expect(page.locator(FIRE_PAGE)).to_be_visible(timeout=10000)
    _pick_header_project(page, f"S254项目{uniq}")
    expect(page.locator(FIRE_TABLE)).to_be_visible(timeout=10000)


def _open_template_dialog(page, uniq):
    # : menu click lands on the plain /batches list tab (query cleared on
    # switch back); the segment follows the header project context.
    nav_to(page, "执行批次", "/batches", '[data-test="batch-table"]', submenu="执行测试")
    _pick_header_project(page, f"S254项目{uniq}")
    expect(page.locator('[data-test="batch-table"]')).to_be_visible()
    page.locator('[data-test="batch-template-btn"]').click()
    dlg = page.locator('[data-test="batch-template-dialog"]')
    expect(dlg).to_be_visible(timeout=10000)
    expect(page.locator(TPL_TABLE)).to_contain_text(f"S254模板{uniq[:6]}", timeout=10000)


def test_t254_schedule_fire_page_and_template_config(page, collectors, api_client, uniq):
    pass
    pid, env_id, tpl_id = _seed_dataset(api_client, uniq)
    _seed_fire(pid, tpl_id)
    try:
        inject_token(page)

        _goto_fire_page(page, uniq)
        row = page.locator(f'{FIRE_TABLE} tbody tr').first
        expect(row).to_be_visible(timeout=10000)
        expect(row.locator('[data-test="schedule-kind-tag"]')).to_have_text("批次模板")
        expect(row.locator('[data-test="schedule-status-tag"]')).to_have_text("失败")
        expect(row.locator('[data-test="schedule-message-cell"]')).to_contain_text(
            "窗口内补跑建批失败", timeout=5000)

        page.locator('[data-test="schedule-filter-status"]').click()
        _select_option(page, "成功")
        expect(page.locator(f'{FIRE_TABLE} .el-empty')).to_be_visible(timeout=10000)
        page.locator('[data-test="schedule-filter-status"]').click()
        _select_option(page, "失败")
        expect(page.locator(f'{FIRE_TABLE} tbody tr')).to_have_count(1, timeout=10000)
        page.locator('[data-test="schedule-filter-kind"]').click()
        _select_option(page, "回归计划")
        expect(page.locator(f'{FIRE_TABLE} .el-empty')).to_be_visible(timeout=10000)
        page.locator('[data-test="schedule-filter-kind"]').click()
        _select_option(page, "批次模板")

        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        _goto_fire_page(page, uniq)
        expect(page.locator(f'{FIRE_TABLE} tbody tr')).to_have_count(1, timeout=10000)

        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        _open_template_dialog(page, uniq)
        tpl_row = page.locator(TPL_TABLE).locator("tr", has_text=f"S254模板{uniq[:6]}").first
        expect(tpl_row.locator('[data-test="batch-tpl-schedule-off"]')).to_be_visible()
        tpl_row.locator('[data-test="batch-tpl-schedule-btn"]').click()
        expect(page.locator(SCHEDULE_DLG)).to_be_visible(timeout=5000)

        page.locator('[data-test="batch-schedule-enable"]').click()
        page.locator('[data-test="batch-schedule-submit"]').click()
        expect(page.locator(".el-message--warning").first).to_contain_text(
            "启用定时需填写 cron", timeout=8000)

        page.locator('[data-test="batch-schedule-cron"]').fill("bad cron 99")
        page.locator('[data-test="batch-schedule-env"]').click()
        _select_option(page, f"S254环境{uniq}")
        page.locator('[data-test="batch-schedule-submit"]').click()
        err = page.locator(".el-message--error").last
        expect(err).to_be_visible(timeout=10000)
        expect(err).to_contain_text("cron 表达式不合法", timeout=5000)
        expect(page.locator(SCHEDULE_DLG)).to_be_visible()

        page.locator('[data-test="batch-schedule-cron"]').click(click_count=3)
        page.locator('[data-test="batch-schedule-cron"]').fill("0 30 2 * * *")
        page.locator('[data-test="batch-schedule-submit"]').click()
        expect(page.locator(".el-message--success").first).to_contain_text(
            "定时配置已保存", timeout=10000)
        expect(page.locator(SCHEDULE_DLG)).to_be_hidden(timeout=8000)

        expect(page.locator('[data-test="batch-template-dialog"]')).to_be_visible(timeout=10000)
        tpl_row = page.locator(TPL_TABLE).locator("tr", has_text=f"S254模板{uniq[:6]}").first
        expect(tpl_row.locator('[data-test="batch-tpl-schedule-tag"]')).to_have_text("0 30 2 * * *")
        assert f"0 30 2 * * *\t1\t{env_id}" in sql_query(
            f"SELECT concat_ws(chr(9), cron_expr, enabled::text, schedule_env_id::text) "
            f"FROM tp_batch_template WHERE id={tpl_id}").strip(), "模板定时列未落库"

        collectors["network_4xx"] = [
            e for e in collectors["network_4xx"]
            if not (e["status"] == 400 and f"/batches/templates/{tpl_id}/schedule" in e["url"])
        ]
        collectors["console_errors"] = [
            e for e in collectors["console_errors"] if "status of 400" not in e
        ]
        assert_no_errors(collectors, "T254 定时调度前端链路")
    finally:
        sql_query(f"DELETE FROM tp_schedule_fire WHERE project_id={pid}")
        api_client.delete(f"/projects/{pid}")
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_t254_fire_page_empty_state(page, collectors, api_client, uniq):
    pass
    r = api_client.post("/projects", json={"name": f"S254空{uniq}", "code": f"S254F{uniq[:6]}"})
    pid = r.json()["data"]
    try:
        inject_token(page)
        # half-B: fires rides the batches page tab; header context slices.
        nav_to(page, "执行批次", "/batches", '[data-test="batch-table"]', submenu="执行测试")
        page.locator('[data-test="batch-tab-fires"]').first.click()
        expect(page.locator(FIRE_PAGE)).to_be_visible(timeout=10000)
        _pick_header_project(page, f"S254空{uniq}")
        expect(page.locator(f'{FIRE_TABLE} .el-empty')).to_be_visible(timeout=10000)
        expect(page.locator(f'{FIRE_TABLE} .el-empty')).to_contain_text("暂无定时触发记录")
        assert_no_errors(collectors, "T254 留痕空态")
    finally:
        api_client.delete(f"/projects/{pid}")
        api_client.delete(f"/projects/{pid}/purge?force=true")
