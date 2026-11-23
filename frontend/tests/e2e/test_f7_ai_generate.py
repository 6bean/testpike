
import uuid

import httpx
import pytest
from playwright.sync_api import expect

from conftest import (
    nav_to,
    assert_no_errors,
    assert_detail_opened,
    wait_ai_task_terminal,
    API_BASE,
    BASE_URL,
    ADMIN_USERNAME,
    ADMIN_PASSWORD,
)


# AI terminal-wait shared helper deadline=1200s + UI-chain margin; pytest-timeout 1500
# (measured 2026-10-22: three concurrent generations behind the gateway took ~15min)
pytestmark = pytest.mark.timeout(1500)


def _mk_project(api, uniq):
    name = f"F7项目_{uniq}"
    code = f"F7{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200, f"创建项目失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"创建项目业务失败: {body.get('msg')}"
    return body["data"], name


def _trigger_generate_api(pid, requirement):
    pass
    token = _login()
    h = {"Authorization": f"Bearer {token}"}
    r = httpx.post(f"{API_BASE}/ai/generate", json={"projectId": pid, "requirement": requirement}, headers=h, timeout=15)
    assert r.status_code == 200, f"发起生成失败: {r.status_code} {r.text[:300]}"
    task_id = r.json()["data"]["taskId"]
    return wait_ai_task_terminal(token, task_id, require_success=False)


def _login() -> str:
    r = httpx.post(f"{API_BASE}/auth/login", json={"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}, timeout=10)
    assert r.status_code == 200, f"登录失败: {r.status_code} {r.text[:300]}"
    return r.json()["data"]["accessToken"]


def _open_ai_gen_page(page):
    nav_to(page, "AI 生成", "/ai/generate", '[data-test="ai-gen-form"]', submenu="设计用例")


def _select_project(page, pid, project_name):
    """T396b posture: the wizard inherits the header project context — the legacy
    in-page selector is retired. Deep link lands the id (URL priority chain) and a
    document reload refreshes the visible-domain snapshot so the echo can resolve
    the freshly created project name (known pitfall: stale pruning)."""
    page.goto(f"{BASE_URL}/#/ai/generate?projectId={pid}", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    echo = page.locator('[data-test="owner-echo"]').first
    expect(echo).to_be_visible(timeout=10000)
    expect(echo.locator('[data-test="owner-echo-value"]')).to_have_text(project_name, timeout=10000)


def test_f7_ai_generate_validation(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    page.reload(wait_until="domcontentloaded")
    # T396b: honestly reach the context-less state first (clear the persisted
    # header context) so "submit disabled without a project" is not a leftover-
    # primary false green; the empty-requirement gate is asserted after landing.
    page.evaluate("() => { for (const k of Object.keys(localStorage)) "
                  "{ if (k.startsWith('tp_proj_ctx_')) localStorage.removeItem(k); } }")
    _open_ai_gen_page(page)

    expect(page.locator('[data-test="ai-gen-budget-tip"]')).to_be_visible()
    expect(page.locator('[data-test="ai-gen-budget-tip"]')).to_contain_text("异步队列")
    expect(page.locator('[data-test="ai-gen-budget-tip"]')).to_contain_text("幂等拦截")

    submit = page.locator('[data-test="ai-gen-submit"]')
    expect(submit).to_be_disabled()

    _select_project(page, pid, pname)
    expect(submit).to_be_disabled()

    page.locator('[data-test="ai-gen-requirement"]').fill(f"用户登录功能校验 {uniq}：输入正确账号密码可登录成功")
    expect(submit).to_be_enabled()

    req_input = page.locator('[data-test="ai-gen-requirement"]')
    expect(req_input).to_have_attribute("maxlength", "5000")

    expect(page.locator('[data-test="ai-gen-task-panel"]')).to_contain_text("提交需求后，这里将实时展示生成进度")

    assert_no_errors(collectors, "F-7 生成入口校验")


def test_f7_ai_generate_full_flow(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    page.reload(wait_until="domcontentloaded")
    _open_ai_gen_page(page)

    _select_project(page, pid, pname)
    keyword = f"用户登录功能_{uniq}"
    page.locator('[data-test="ai-gen-requirement"]').fill(
        f"{keyword}：输入正确账号密码可登录成功；密码错误提示「账号或密码错误」；连续 5 次错误锁定账号 30 分钟"
    )
    page.locator('[data-test="ai-gen-submit"]').click()

    expect(page.locator(".el-message").filter(has_text="生成任务已提交")).to_be_visible(timeout=10000)

    expect(page.locator('[data-test="ai-gen-task-status"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="ai-gen-task-status"]')).to_contain_text("已完成", timeout=1440000)
    expect(page.locator('[data-test="ai-gen-open-case"]')).to_be_visible(timeout=10000)

    page.locator('[data-test="ai-gen-open-case"]').click()
    expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=10000)
    assert_detail_opened(page)

    assert_no_errors(collectors, "F-7 生成完整链路")


def test_f7_case_script_view(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    t = _trigger_generate_api(pid, f"订单支付功能_{uniq}：选择商品加入购物车，结算支付成功，订单状态变为已支付")
    assert t["status"] == 3 and t.get("caseId"), f"生成未成功: {t}"
    case_id = t["caseId"]

    token = _login()
    r = httpx.get(f"{API_BASE}/cases/{case_id}/script", headers={"Authorization": f"Bearer {token}"}, timeout=10)
    assert r.status_code == 200 and r.json()["code"] == "0", f"脚本查询失败: {r.text[:300]}"
    script = r.json()["data"]
    assert script is not None, f"用例 {case_id} 无脚本（生成链路应自动产出 stage2 动作 JSON）"
    assert script["schemaVersion"] == "v2", f"schema 版本异常: {script['schemaVersion']}"

    page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="detail-script"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="detail-script"]')).to_contain_text("schema v2")
    expect(page.locator('[data-test="detail-script"]')).to_contain_text("已生成")

    json_pre = page.locator('[data-test="detail-script-json"]')
    expect(json_pre).to_be_visible(timeout=10000)
    text = json_pre.inner_text()
    assert '"version"' in text, f"actionJson 缺少 version 字段: {text[:300]}"
    assert '"steps"' in text, f"actionJson 缺少 steps 字段: {text[:300]}"

    assert_no_errors(collectors, "F-7 脚本查看")


def test_f7_notifications_badge(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    t = _trigger_generate_api(pid, f"库存查询功能_{uniq}：输入商品编码查询库存数量，无库存时提示补货")
    assert t["status"] == 3, f"生成未成功: {t}"
    task_id = t["id"]

    token = _login()
    r = httpx.get(f"{API_BASE}/notifications?readFlag=0&size=50", headers={"Authorization": f"Bearer {token}"}, timeout=10)
    assert r.status_code == 200, f"通知查询失败: {r.text[:300]}"
    notifs = r.json()["data"]["list"]
    target = [n for n in notifs if n.get("dedupKey") == f"ai-gen-done:{task_id}"]
    assert target, f"未找到 ai-gen-done:{task_id} 通知（真实链路应产出站内信）"

    page.reload(wait_until="domcontentloaded")
    page.locator('[data-test="notif-bell"]').click()
    expect(page.locator('[data-test="notif-panel"]')).to_be_visible(timeout=10000)

    panel = page.locator('[data-test="notif-panel"]')
    expect(panel).to_contain_text("AI 生成完成", timeout=10000)
    expect(panel).to_contain_text("AI 用例生成完成")

    own = target[0]
    own_item = panel.locator(".notif-item", has_text=own["content"]).first
    expect(own_item).to_be_visible(timeout=10000)
    own_item.locator('[data-test="notif-detail"]').click()
    expect(page.locator(".el-message-box")).to_be_visible(timeout=10000)
    expect(page.locator(".el-message-box")).to_contain_text("AI 用例生成完成")
    page.locator(".el-message-box__btns button:has-text('知道了')").click()
    expect(page.locator(".el-message-box")).to_be_hidden(timeout=5000)

    r = httpx.get(f"{API_BASE}/notifications?size=50", headers={"Authorization": f"Bearer {token}"}, timeout=10)
    refreshed = [n for n in r.json()["data"]["list"] if n.get("dedupKey") == f"ai-gen-done:{task_id}"]
    assert refreshed and refreshed[0]["readFlag"] == 1, f"通知未持久化已读: {refreshed}"

    assert_no_errors(collectors, "F-7 站内信角标")

