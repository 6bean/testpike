
import pytest
from conftest import assert_no_errors, nav_to, pick_project_in_header
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(180)


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} 业务失败: {body.get('msg')}"
    return body["data"]


@pytest.fixture()
def mon_env(api_client, uniq):
    pass
    pid = _ok(api_client.post("/projects", json={
        "name": f"监控E2E项目{uniq}", "code": f"MU{uniq[:5].upper()}"}), "建项目")
    eid = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"监控E2E环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8083/actuator/health"}), "建环境")
    yield pid, eid, f"监控E2E项目{uniq}"
    api_client.delete(f"/projects/{pid}/purge?force=true")


def _pick_project(page, data_test, keyword):
    pass
    pick_project_in_header(page, keyword)


def test_monitor_wizard_create_persist_delete(logged_in, collectors, api_client, uniq, mon_env):
    pass
    page = logged_in
    pid, _eid, proj_name = mon_env
    target_name = f"健康检查{uniq}"

    nav_to(page, "监控中心", "/monitor", '[data-test="monitor-page"]', submenu="执行测试")

    _pick_project(page, "project-ctx-select", proj_name)

    page.locator('[data-test="monitor-create-btn"]').click()
    expect(page.locator('[data-test="monitor-wizard"]')).to_be_visible()

    expect(page.locator('[data-test="wizard-step-belong"]')).to_be_visible()
    env_sel = page.locator('[data-test="wizard-env"]')
    env_sel.click()
    env_opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item', has_text="监控E2E环境")
    expect(env_opt.first).to_be_visible(timeout=10000)
    env_opt.first.click()
    page.locator('[data-test="wizard-name"]').fill(target_name)
    page.locator('[data-test="wizard-next"]').click()

    expect(page.locator('[data-test="wizard-step-probe"]')).to_be_visible()
    page.locator('[data-test="wizard-url"]').fill("http://localhost:8083/actuator/health")
    page.locator('[data-test="wizard-next"]').click()

    expect(page.locator('[data-test="wizard-step-schedule"]')).to_be_visible()
    page.locator('[data-test="wizard-next"]').click()

    expect(page.locator('[data-test="wizard-step-confirm"]')).to_be_visible()
    page.locator('[data-test="wizard-submit"]').click()
    expect(page.locator('.el-message--success')).to_be_visible(timeout=10000)

    tile = page.locator('[data-test="monitor-tile"]', has_text=target_name)
    expect(tile.first).to_be_visible(timeout=10000)

    nav_to(page, "监控中心", "/monitor", '[data-test="monitor-page"]', submenu="执行测试")
    _pick_project(page, "project-ctx-select", proj_name)
    expect(page.locator('[data-test="monitor-tile"]', has_text=target_name).first).to_be_visible(timeout=10000)

    rows = _ok(api_client.get(f"/monitor/targets?projectId={pid}"), "目标列表")
    assert any(t["name"] == target_name for t in rows), f"服务端列表缺目标: {rows}"

    page.locator('[data-test="monitor-tile"]', has_text=target_name).first.click()
    expect(page.locator('[data-test="monitor-drawer"]')).to_be_visible(timeout=10000)
    page.keyboard.press("Escape")

    page.locator('[data-test="monitor-tile"]', has_text=target_name).first.locator(
        '[data-test="tile-delete"]').click()
    page.locator('.el-message-box button:has-text("确定")').click()
    expect(page.locator('[data-test="monitor-tile"]', has_text=target_name)).to_have_count(0, timeout=10000)

    from conftest import sql_query
    left = sql_query(
        "SELECT count(*) FROM tp_monitor_target WHERE project_id="
        f"{pid} AND name='{target_name}'")
    assert left == "0", f"DB 残留监控目标: {left}"

    assert_no_errors(collectors, "监控中心向导建目标+持久化+删除")


def test_monitor_wizard_cancel_exit_and_required_marks(logged_in, collectors, api_client, uniq, mon_env):
    pass
    page = logged_in
    _pid, _eid, proj_name = mon_env
    nav_to(page, "监控中心", "/monitor", '[data-test="monitor-page"]', submenu="执行测试")
    _pick_project(page, "project-ctx-select", proj_name)

    page.locator('[data-test="monitor-create-btn"]').click()
    wiz = page.locator('[data-test="monitor-wizard"]')
    expect(wiz).to_be_visible()

    step0 = page.locator('[data-test="wizard-step-belong"]')
    expect(step0.locator(".wizard__req")).to_have_count(2)
    cancel = page.locator('[data-test="wizard-cancel"]')
    expect(cancel).to_be_visible()
    expect(cancel).to_contain_text("取消创建")
    cancel.click()
    expect(wiz).not_to_be_visible()

    page.locator('[data-test="monitor-create-btn"]').click()
    env_sel = page.locator('[data-test="wizard-env"]')
    env_sel.click()
    page.locator('.el-select-dropdown:visible .el-select-dropdown__item',
                 has_text="监控E2E环境").first.click()
    page.locator('[data-test="wizard-name"]').fill(f"星号{uniq}")
    page.locator('[data-test="wizard-next"]').click()
    probe = page.locator('[data-test="wizard-step-probe"]')
    expect(probe.locator(".wizard__req")).to_have_count(1)
    expect(probe.locator('.wizard__field:has([data-test="wizard-url"]) .wizard__req')
           ).to_have_count(1)
    # el-radio-button hides its native input visually; click the visible label wrapper
    page.locator('[data-test="wizard-type"] .el-radio-button', has_text="DB").first.click()
    expect(probe.locator(".wizard__req")).to_have_count(1)
    expect(probe.locator('.wizard__field:has([data-test="wizard-conn"]) .wizard__req')
           ).to_have_count(1)

    assert_no_errors(collectors, "监控中心向导取消出口+必填星号联动")
