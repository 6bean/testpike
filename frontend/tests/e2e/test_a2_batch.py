
import uuid

import httpx
import pytest
from playwright.sync_api import expect

from conftest import nav_to, assert_no_errors, assert_detail_opened, API_BASE, BASE_URL, ADMIN_USERNAME, ADMIN_PASSWORD


# Terminal wait for the 3-task real-LLM batch queue plus stage-2 tail.
# Measured 2026-11-05: a single task can exceed 10min on provider latency
# (task 747 took 12.4min from creation to terminal state), so the progress
# window is raised 600s -> 900s and pytest-timeout to 1500 (two serial 900s
# waits plus page overhead).
# Measured again this round: the batch rerun window died at the 902s boundary
# with 0/3 done while a single healthy task took 939s (task 755) — three
# near-serial tasks finish around 940-1050s, just past the old cap. Progress
# assertion raised 900s -> 1500s and pytest-timeout to 1800 accordingly.
pytestmark = pytest.mark.timeout(1800)


def _mk_project(api, uniq):
    name = f"A2项目_{uniq}"
    code = f"A2{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200, f"创建项目失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"创建项目业务失败: {body.get('msg')}"
    return body["data"], name


def _login() -> str:
    r = httpx.post(f"{API_BASE}/auth/login", json={"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}, timeout=10)
    assert r.status_code == 200, f"登录失败: {r.status_code} {r.text[:300]}"
    return r.json()["data"]["accessToken"]


def _open_ai_gen_page(page):
    nav_to(page, "AI 生成", "/ai/generate", '[data-test="ai-gen-form"]', submenu="设计用例")


def _select_project(page, pid, project_name):
    """T396b posture: batch wizard rides the header project context via deep link
    (in-page selector retired); reload refreshes the visible-domain snapshot."""
    page.goto(f"{BASE_URL}/#/ai/generate?projectId={pid}", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    echo = page.locator('[data-test="owner-echo"]').first
    expect(echo).to_be_visible(timeout=10000)
    expect(echo.locator('[data-test="owner-echo-value"]')).to_have_text(project_name, timeout=10000)


def _switch_batch_mode(page):
    page.locator('[data-test="ai-gen-mode"]').get_by_text("批量生成").click()
    expect(page.locator('[data-test="ai-gen-batch-form"]')).to_be_visible(timeout=5000)


def _fill_batch_rows(page, texts):
    for idx, text in enumerate(texts):
        if idx > 0:
            page.locator('[data-test="ai-gen-batch-add"]').click()
        page.locator(f'[data-test="ai-gen-batch-input-{idx}"]').fill(text)
    expect(page.locator('[data-test="ai-gen-batch-count"]')).to_contain_text(f"{len(texts)}/500", timeout=5000)


def test_a2_batch_full_flow(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    page.reload(wait_until="domcontentloaded")
    # T396b: land the header context first (goto resets mode), then switch batch.
    _select_project(page, pid, pname)

    _switch_batch_mode(page)
    # Concept single-word (2026-11-05 wording rectification): the batch textarea
    expect(page.locator('[data-test="ai-gen-batch-form"]')).to_contain_text("需求点列表")
    k1 = f"用户登录功能_{uniq}"
    k2 = f"订单支付功能_{uniq}"
    k3 = f"库存查询功能_{uniq}"
    _fill_batch_rows(page, [
        f"{k1}：输入正确账号密码可登录成功；密码错误提示「账号或密码错误」",
        f"{k2}：选择商品加入购物车，结算支付成功，订单状态变为已支付",
        f"{k3}：输入商品编码查询库存数量，无库存时提示补货",
    ])

    page.locator('[data-test="ai-gen-batch-submit"]').click()
    expect(page.locator(".el-message-box")).to_be_visible(timeout=10000)
    box = page.locator(".el-message-box")
    expect(box).to_contain_text("确认批量生成成本")
    expect(box).to_contain_text("3")
    expect(box).to_contain_text("预估消耗约")
    expect(box).to_contain_text("tokens")
    expect(box).to_contain_text("¥")

    page.locator('.el-message-box__btns button:has-text("确认生成")').click()
    expect(page.locator(".el-message-box")).to_be_hidden(timeout=5000)
    expect(page.locator('[data-test="ai-gen-batch-progress-count"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="ai-gen-batch-progress-count"]')).to_contain_text("已完成 3/3", timeout=1500000)
    panel = page.locator('[data-test="ai-gen-batch-panel"]')
    if "成功 3 · 失败 0" not in panel.inner_text():
        retry_btn = page.locator('[data-test="ai-gen-batch-retry-failed"]')
        expect(retry_btn).to_be_visible(timeout=5000)
        retry_btn.click()
    expect(panel).to_contain_text("成功 3 · 失败 0", timeout=1500000)

    items = page.locator('[data-test="ai-gen-batch-items"]')
    expect(items).to_contain_text("用户登录功能", timeout=10000)
    expect(items).to_contain_text("订单支付功能")
    expect(items).to_contain_text("库存查询功能")
    expect(page.locator('[data-test="ai-gen-batch-open-0"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="ai-gen-batch-open-2"]')).to_be_visible(timeout=10000)

    assert page.locator('[data-test="ai-gen-batch-retry-failed"]').count() == 0, "全成功时不应显示重试失败项按钮"

    page.locator('[data-test="ai-gen-batch-open-0"]').click()
    expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=10000)
    assert_detail_opened(page)
    case_no0 = page.locator('[data-test="detail-case-no"]').inner_text().strip()

    token = _login()
    h = {"Authorization": f"Bearer {token}"}
    r = httpx.get(f"{API_BASE}/cases?projectId={pid}&keyword={case_no0}&size=10", headers=h, timeout=10)
    assert r.status_code == 200 and r.json().get("code") == "0", f"用例列表查询失败: {r.text[:300]}"
    rows = r.json()["data"]["list"] or []
    assert any(c.get("caseNo") == case_no0 for c in rows), \
        f"批量生成用例未持久化: {[c.get('caseNo') for c in rows]}"

    assert_no_errors(collectors, "A2 批量生成完整链路")


def test_a2_batch_validation(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    page.reload(wait_until="domcontentloaded")
    _open_ai_gen_page(page)

    # T396b rule 2: with no header context the batch submit stays disabled
    # (clear the persisted context first — a leftover primary would open the gate).
    page.evaluate("() => { for (const k of Object.keys(localStorage)) "
                  "{ if (k.startsWith('tp_proj_ctx_')) localStorage.removeItem(k); } }")
    page.goto(f"{BASE_URL}/#/ai/generate", wait_until="domcontentloaded")
    _switch_batch_mode(page)
    submit = page.locator('[data-test="ai-gen-batch-submit"]')
    expect(submit).to_be_disabled()

    # deep-link the context (mode resets on goto), then switch batch again
    _select_project(page, pid, pname)
    _switch_batch_mode(page)

    expect(submit).to_be_disabled()

    page.locator('[data-test="ai-gen-batch-input-0"]').fill(f"批量校验需求_{uniq}")
    expect(submit).to_be_enabled()
    page.locator('[data-test="ai-gen-batch-add"]').click()
    expect(page.locator('[data-test="ai-gen-batch-count"]')).to_contain_text("1/500")

    page.locator('[data-test="ai-gen-batch-remove"]').first.click()
    expect(submit).to_be_disabled()

    assert_no_errors(collectors, "A2 批量入口校验")
