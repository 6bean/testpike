
import os
import re
import time

import httpx
import pytest
from playwright.sync_api import expect

from conftest import (
    ADMIN_PASSWORD,
    ADMIN_USERNAME,
    API_BASE,
    BASE_URL,
    assert_detail_opened,
    assert_no_errors,
    nav_to,
)

ENUM_KEY = "ai.gen.batch.auto-enumerate.enabled"

# 90min per test: enumeration (LLM, per-row) + queue waits + one A2-style retry.
pytestmark = [pytest.mark.xdist_group("wt-auto-enum"), pytest.mark.timeout(5400)]


def _set_switch(value: str) -> None:
    """Hot-update the global auto-enumerate key through the admin config API.

    Module-level single source shared by all three journeys (was nested in C
    only). Explicit entry reset (observed 2026-11-20): a window SIGKILLed
    mid-C bypasses the finally reset and leaves the key false, so the next
    run's A/B read the legacy shape and failed -- every key-on journey now
    re-asserts true at entry, making ambient residue unable to flip the
    expectation.
    """
    token = _login()
    headers = {"Authorization": f"Bearer {token}"}
    rows = httpx.get(
        f"{API_BASE}/configs", params={"groupName": "系统"}, headers=headers, timeout=10
    ).json()["data"]
    hit = [c for c in rows if c["configKey"] == ENUM_KEY]
    assert hit, f"开关键缺失（V613 种子未落库？）: {ENUM_KEY}"
    cfg = hit[0]
    r = httpx.put(
        f"{API_BASE}/configs/{cfg['id']}",
        json={
            "configKey": ENUM_KEY,
            "configValue": value,
            "groupName": cfg.get("groupName") or "系统",
            "valueType": cfg.get("valueType") or "bool",
        },
        headers=headers,
        timeout=10,
    )
    assert r.status_code == 200 and r.json().get("code") == "0", f"开关热更失败: {r.text[:300]}"


def _login_pair() -> dict:
    """Real login, returning the full token payload (access + refresh).

    The r9 window (2026-11-20) proved a 30-40 min wait crosses the 1800s
    access-token TTL: the fixture never injected tp_refresh_token, so the
    app's single-flight silent renewal could not run, pollers hit 401 and
    the route guard bounced the page to #/login -- the collector then failed
    the zero-tolerance gate even though the functional journey had passed.
    """
    r = httpx.post(
        f"{API_BASE}/auth/login",
        json={"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD},
        timeout=10,
    )
    assert r.status_code == 200, f"登录失败: {r.status_code} {r.text[:300]}"
    return r.json()["data"]


_TOKEN_FRESH_SINCE = {"t": 0.0}
# Refresh well inside the 1800s factory TTL (hot-updatable server-side; the
# fixture stays conservative and never relies on the ceiling).
_RENEW_AFTER_S = 600.0


def _login() -> str:
    token = _login_pair()["accessToken"]
    _TOKEN_FRESH_SINCE["t"] = time.monotonic()
    return token


def _mk_project(api, uniq, tag):
    r = api.post("/projects", json={"name": f"WT{tag}项目_{uniq}", "code": f"WT{tag}{uniq[:5].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _open_ai_gen_page(page):
    nav_to(page, "AI 生成", "/ai/generate", '[data-test="ai-gen-form"]', submenu="设计用例")


def _select_project(page, pid, project_name):
    page.goto(f"{BASE_URL}/#/ai/generate?projectId={pid}", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    echo = page.locator('[data-test="owner-echo"]').first
    expect(echo).to_be_visible(timeout=10_000)
    expect(echo.locator('[data-test="owner-echo-value"]')).to_have_text(project_name, timeout=10_000)


def _switch_batch_mode(page):
    page.locator('[data-test="ai-gen-mode"]').get_by_text("批量生成").click()
    expect(page.locator('[data-test="ai-gen-batch-form"]')).to_be_visible(timeout=5_000)


def _fill_batch_rows(page, texts):
    for idx, text in enumerate(texts):
        if idx > 0:
            page.locator("[data-test='ai-gen-batch-add']").click()
        page.locator(f"[data-test='ai-gen-batch-input-{idx}']").fill(text)
    expect(page.locator('[data-test="ai-gen-batch-count"]')).to_contain_text(
        f"{len(texts)}/500", timeout=5_000
    )


def _dialog_count(page) -> int:
    pass
    body = page.locator(".el-message-box").inner_text()
    m = re.search(r"生成\s*(\d+)\s*条用例", body)
    assert m, f"成本弹窗未读出条数句: {body[:200]}"
    return int(m.group(1))


def _submit_and_capture(page, submit_selector):
    pass
    page.locator(submit_selector).click()
    box = page.locator(".el-message-box")
    expect(box).to_be_visible(timeout=600_000)
    return box


def _confirm_capture_items(page):
    pass
    with page.expect_response(
        lambda r: "/api/v1/ai/batch-generate" in r.url and r.request.method == "POST",
        timeout=120_000,
    ) as info:
        page.locator('.el-message-box__btns button:has-text("确认生成")').click()
    resp = info.value
    assert resp.status == 200, f"批量提交失败: {resp.status} {resp.text()[:300]}"
    body = resp.request.post_data_json
    return body.get("items") or [], resp.json()["data"]["batchId"]


def _recover_session(page, page_url: str) -> None:
    pass
    if "/#/login" not in page.url:
        return
    token = _login()
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.evaluate(
        f"() => {{ localStorage.setItem('tp_access_token', '{token}');"
        " localStorage.setItem('tp_onboarding_dismissed', '1'); }"
    )
    page.goto(page_url, wait_until="domcontentloaded")


def _keep_session_live(page, target_url: str) -> None:
    """Proactively swap a fresh access+refresh pair into localStorage mid-wait.

    The app attaches the token per request from localStorage, so a silent
    swap (no reload, no state loss) keeps background pollers green across the
    whole long wait. Also injects tp_refresh_token so the product's own
    single-flight renewal works as a second layer. A no-op while the token
    is younger than _RENEW_AFTER_S; if the page was already bounced to
    #/login, _recover_session owns the restore path.
    """
    if time.monotonic() - _TOKEN_FRESH_SINCE["t"] < _RENEW_AFTER_S:
        return
    if "/#/login" in page.url:
        _recover_session(page, target_url)
        return
    data = _login_pair()
    page.evaluate(
        f"() => {{ localStorage.setItem('tp_access_token', '{data['accessToken']}');"
        f" localStorage.setItem('tp_token_type', '{data.get('tokenType') or 'Bearer'}'); }}"
    )
    if data.get("refreshToken"):
        page.evaluate(
            f"() => localStorage.setItem('tp_refresh_token', '{data['refreshToken']}')"
        )


def _tolerant_wait(panel, page, expect_text, budget_ms, page_url: str = ""):
    pass
    target = page_url or ("" if "/#/login" in page.url else page.url)
    deadline = time.monotonic() + budget_ms / 1000.0
    segment_s = max(30, int(os.environ.get("TP_E2E_WAIT_SEGMENT_S", "180")))
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        _keep_session_live(page, target)
        try:
            expect(panel).to_contain_text(expect_text, timeout=min(segment_s, remaining) * 1000)
            return
        except AssertionError:
            _recover_session(page, target)
    _recover_session(page, target)
    try:
        expect(panel).to_contain_text(expect_text, timeout=15_000)
        return
    except AssertionError:
        pass
    retry_btn = page.locator('[data-test="ai-gen-batch-retry-failed"]')
    expect(retry_btn).to_be_visible(timeout=5_000)
    retry_btn.click()
    expect(panel).to_contain_text(expect_text, timeout=budget_ms)


def _case_persisted(api_client, pid, case_no):
    token = _login()
    h = {"Authorization": f"Bearer {token}"}
    r = httpx.get(
        f"{API_BASE}/cases", params={"projectId": pid, "keyword": case_no, "size": 10}, headers=h, timeout=10
    )
    assert r.status_code == 200 and r.json().get("code") == "0", f"用例列表查询失败: {r.text[:300]}"
    rows = r.json()["data"]["list"] or []
    assert any(c.get("caseNo") == case_no for c in rows), f"生成用例未持久化: {[c.get('caseNo') for c in rows]}"


def test_wt_batch_auto_enumerate_journey(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    _set_switch("true")  # entry normalization: ambient residue from a killed C cannot flip A
    pid = _mk_project(api_client, uniq, "A")
    pname = api_client.get(f"/projects/{pid}").json()["data"]["name"]
    page.reload(wait_until="domcontentloaded")
    _select_project(page, pid, pname)
    _switch_batch_mode(page)

    rows = [
        f"优惠券核销功能_{uniq}：收银台输入券码核销成功，券失效不可重复使用",
        f"会员积分兑换功能_{uniq}：积分兑换商品，余额不足时提示且订单不生成",
    ]
    _fill_batch_rows(page, rows)
    hint = page.locator('[data-test="ai-gen-auto-enum-hint"]')
    expect(hint).to_be_visible(timeout=10_000)
    expect(hint).to_contain_text("自动枚举五型场景", timeout=10_000)

    _submit_and_capture(page, '[data-test="ai-gen-batch-submit"]')
    box = page.locator(".el-message-box")
    n = _dialog_count(page)
    assert n > len(rows), f"自动枚举未展开：弹窗条数 {n} 未大于行数 {len(rows)}"
    expect(box).to_contain_text("已自动枚举五型场景并默认全选", timeout=10_000)

    items, _batch_id = _confirm_capture_items(page)
    assert len(items) == n, f"POST items {len(items)} 与弹窗条数 {n} 不一致（禁二次换算）"
    expect(page.locator('[data-test="ai-gen-batch-progress-count"]')).to_contain_text(
        f"/{n}", timeout=10_000
    )

    # Queue discipline: wait until 2 items SUCCEED (the LLM decides the expanded
    # total; waiting for all would flood the dev queue).
    # Anchor correction (r13 window, 2026-11-21): the old wait targeted the
    # 8 failed) and the immediately following count() saw a single button --
    # a fixture anchor bug, not a product gap. Now wait on the panel
    # subProgress success counter (same batch state as the per-row buttons),
    # so the wait target and the assertion mean the same thing.
    panel = page.locator('[data-test="ai-gen-batch-panel"]')
    _tolerant_wait(
        panel,
        page,
        re.compile(r"成功 (?:[2-9]|[1-9]\d) ·"),
        2_400_000,
        page_url=f"{BASE_URL}/#/ai/generate?projectId={pid}",
    )
    # Parallel generation completes out of row order (r9 window evidence
    # 2026-11-20: row 0 still generating while rows 1-2 finished), so bind
    # the assertion to "two open-case buttons exist" instead of index 0
    # (assertion-lag correction, product behavior unchanged).
    open_btns = page.locator('[data-test^="ai-gen-batch-open-"]')
    expect(open_btns.first).to_be_visible(timeout=10_000)
    n_open = open_btns.count()
    assert n_open >= 2, f"成功≥2 但查看用例钮仅 {n_open} 枚（成功态渲染缺口）"
    open_btns.first.click()
    expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=10_000)
    assert_detail_opened(page)
    case_no0 = page.locator('[data-test="detail-case-no"]').inner_text().strip()
    _case_persisted(api_client, pid, case_no0)

    assert_no_errors(collectors, "WT A 批量默认完整枚举真旅程")


def test_wt_single_line_rides_batch_door(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    _set_switch("true")  # entry normalization (same residue defense as A)
    pid = _mk_project(api_client, uniq, "B")
    pname = api_client.get(f"/projects/{pid}").json()["data"]["name"]
    page.reload(wait_until="domcontentloaded")
    _select_project(page, pid, pname)
    hint = page.locator('[data-test="ai-gen-single-auto-hint"]')
    expect(hint).to_be_visible(timeout=10_000)
    expect(hint).to_contain_text("自动枚举五型场景", timeout=10_000)

    page.locator('[data-test="ai-gen-requirement"]').fill(
        f"快递单号识别_{uniq}：粘贴单号自动识别快递公司并展示物流轨迹"
    )
    _submit_and_capture(page, '[data-test="ai-gen-submit"]')
    box = page.locator(".el-message-box")
    n = _dialog_count(page)
    assert n > 1, f"单条未走自动枚举门：弹窗条数 {n}（应展开为一行以上）"
    expect(box).to_contain_text("已自动枚举五型场景并默认全选", timeout=10_000)

    items, _batch_id = _confirm_capture_items(page)
    assert len(items) == n and n > 1, f"单条并入 items {len(items)} != 弹窗 {n} 或未展开"

    prog = page.locator('[data-test="ai-gen-batch-progress-count"]')
    _tolerant_wait(
        prog,
        page,
        re.compile(r"已完成 (?:[1-9]|[1-9]\d)/"),
        2_700_000,
        page_url=f"{BASE_URL}/#/ai/generate?projectId={pid}",
    )
    assert_no_errors(collectors, "WT B 单条并入自动枚举门")


def test_wt_key_off_legacy_one_per_row(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = _mk_project(api_client, uniq, "C")
    pname = api_client.get(f"/projects/{pid}").json()["data"]["name"]

    _set_switch("false")
    try:
        page.reload(wait_until="domcontentloaded")
        _select_project(page, pid, pname)
        _switch_batch_mode(page)
        rows = [
            f"门店自提核销_{uniq}：扫码核销自提订单，已核销二次扫码提示无效",
            f"运费模板计价_{uniq}：按重量与地区组合计算运费，超重提示补差价",
        ]
        _fill_batch_rows(page, rows)
        hint = page.locator('[data-test="ai-gen-auto-enum-hint"]')
        expect(hint).to_be_visible(timeout=10_000)
        expect(hint).to_contain_text("自动枚举已关闭", timeout=10_000)
        expect(hint).to_contain_text("系统设置", timeout=10_000)
        expect(hint).to_contain_text(ENUM_KEY, timeout=10_000)

        _submit_and_capture(page, '[data-test="ai-gen-batch-submit"]')
        n = _dialog_count(page)
        assert n == len(rows), f"关态必须整行旧形态：弹窗 {n} != 行数 {len(rows)}"
        body = page.locator(".el-message-box").inner_text()
        assert "已自动枚举" not in body, "关态弹窗不得出现自动枚举承诺句（假路违例）"

        items, _batch_id = _confirm_capture_items(page)
        assert len(items) == len(rows), f"关态 items {len(items)} != 行数 {len(rows)}"

        panel = page.locator('[data-test="ai-gen-batch-panel"]')
        _tolerant_wait(
            panel,
            page,
            "成功 2 · 失败 0",
            2_700_000,
            page_url=f"{BASE_URL}/#/ai/generate?projectId={pid}",
        )
        assert_no_errors(collectors, "WT C 关态整行旧形态")
    finally:
        _set_switch("true")
