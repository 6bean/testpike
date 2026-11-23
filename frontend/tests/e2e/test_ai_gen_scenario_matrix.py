"""Stage0 scenario circle-selection E2E at worst-case scale (30 requirement rows).

Covers the acceptance chain declared for the batch-generation circle-selection
segment: start enumeration -> group read-back renders one row per source line ->
selection edits (start fully selected, clear, re-pick) -> submit expands exactly
the selected scenarios into real generation items (POST body and persisted batch
both assert the count), without the previewBatchId double-multiply.

Real chain only: admin 8081 -> core -> ai-service -> stage0 enumeration over the
configured LLM provider. No mocking of the request path. The selected scenario
count is kept at 3 on submit so the dev queue is not flooded by ~200 real tasks
while the expansion contract still gets a hard assertion.

Parallel safety: the project is created per test with a unique suffix.
"""
import json

import httpx
import pytest
from conftest import (
    ADMIN_PASSWORD,
    ADMIN_USERNAME,
    API_BASE,
    BASE_URL,
    assert_no_errors,
)
from playwright.sync_api import expect

# 30 sequential stage0 enumerations dominate the wall clock (deliberation ring
# runs per row); measured budgets put the worst case under an hour.
pytestmark = pytest.mark.timeout(3600)

ROW_COUNT = 30

# Scenario boxes only. The panel header carries sibling data-tests that share the
# "ai-gen-scenario-" prefix (count/all/none/panel), which the prefix-only selector
# swept into the total (w6 artifact 20261107-174456: 244 counted vs 240/240 shown
# - the 4 header elements, not scenarios). `.scenario-check` is the checkbox's own
# class on the el-checkbox root, so the count matches the panel contract exactly.
CHECKBOX = '.scenario-check'


def _login() -> str:
    r = httpx.post(
        f"{API_BASE}/auth/login",
        json={"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD},
        timeout=10,
    )
    assert r.status_code == 200, f"login failed: {r.status_code} {r.text[:300]}"
    return r.json()["data"]["accessToken"]


def _wait_scenario_panel(page, overall_s: int, chunk_s: int) -> None:
    """Hard-wait for the matrix panel in chunks, refreshing the injected session.

    Each chunk ends in expect().to_be_visible (auto-wait, no sleep); between chunks
    a fresh login token is written to localStorage (the axios interceptor reads it
    per request), so a >TTL enumeration never dies of a mid-run 401 redirect. On
    budget exhaustion the last chunk re-raises the visibility AssertionError.
    """
    waited = 0
    panel = page.locator('[data-test="ai-gen-scenario-panel"]')
    while True:
        budget = min(chunk_s, overall_s - waited)
        try:
            expect(panel).to_be_visible(timeout=budget * 1000)
            return
        except AssertionError:
            if waited + budget >= overall_s:
                raise
            waited += budget
            token = _login()
            page.evaluate(f"() => localStorage.setItem('tp_access_token', '{token}')")


def _mk_project(api, uniq):
    name = f"场景圈选_{uniq}"
    code = f"SCN{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200, f"创建项目失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"创建项目业务失败: {body.get('msg')}"
    return body["data"], name


def _enter_batch_rows(page, rows):
    """Fill `rows` source lines into the batch editor (worst-case scale fixture)."""
    page.locator('[data-test="ai-gen-batch-input-0"]').fill(rows[0])
    for idx in range(1, len(rows)):
        page.locator('[data-test="ai-gen-batch-add"]').click()
        page.locator(f'[data-test="ai-gen-batch-input-{idx}"]').fill(rows[idx])
    expect(page.locator('[data-test="ai-gen-batch-count"]')).to_contain_text(
        f"已输入 {len(rows)}/", timeout=10000
    )


def _expand_row(page, seq):
    """Collapse panes start folded; open one matrix row so its scenario boxes act."""
    row = page.locator(f'[data-test="ai-gen-preview-row-{seq}"]')
    row.locator(".el-collapse-item__header").click()
    return row


def test_scenario_matrix_readback_clear_repick_submit_expansion(logged_in, api_client, collectors, uniq):
    """30-row enumeration: read-back row count, selection state, submit expands to the picked count."""
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)

    # Deep link lands the project id (header segment is the only context channel),
    # reload refreshes the visible-domain snapshot so the owner echo resolves.
    page.goto(f"{BASE_URL}/#/ai/generate?projectId={pid}", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    echo = page.locator('[data-test="owner-echo"]').first
    expect(echo).to_be_visible(timeout=10000)
    expect(echo.locator('[data-test="owner-echo-value"]')).to_have_text(pname, timeout=10000)

    page.locator('[data-test="ai-gen-mode"]').get_by_text("批量生成").click()
    rows = [f"场景圈选需求行{i}：验证登录功能在密码错误、账号锁定、网络中断三种情况下的提示与恢复" for i in range(ROW_COUNT)]
    _enter_batch_rows(page, rows)

    # Start the stage0 enumeration: 30 POSTs run line by line, the group read-back
    # activates the matrix. This is the long leg - wait on the panel itself with a
    # session keep-alive: the auth token TTL (~30 min) is shorter than a worst-case
    # enumeration, and one mid-run 401 sends the router guard to /#/login, which
    # unmounts the view and aborts the in-page loop (window w2 failure shape).
    # Re-inject a fresh token between wait chunks WITHOUT navigating.
    page.locator('[data-test="ai-gen-preview-start"]').click()
    _wait_scenario_panel(page, overall_s=2700, chunk_s=240)

    # Hard assertions at worst-case scale (no conditional wrappers): one matrix
    # row per source line and at least one selectable scenario per row.
    row_headers = page.locator('[data-test^="ai-gen-preview-row-"]')
    expect(row_headers).to_have_count(ROW_COUNT, timeout=60_000)
    checkboxes = page.locator(CHECKBOX)
    total_scenarios = checkboxes.count()
    assert total_scenarios >= ROW_COUNT, (
        f"enumeration returned {total_scenarios} scenarios for {ROW_COUNT} rows - "
        "at least one scenario per row is the contract"
    )
    panel_count = page.locator('[data-test="ai-gen-scenario-count"]')
    expect(panel_count).to_contain_text(str(total_scenarios), timeout=10_000)

    # Default state is fully selected: clear everything (submit must go disabled
    # with zero expansion), then re-pick a small set across two rows.
    page.locator('[data-test="ai-gen-scenario-none"]').click()
    expect(page.locator('[data-test="ai-gen-batch-submit"]')).to_be_disabled(timeout=10_000)
    picked = 0
    row1 = _expand_row(page, 1)
    cbs1 = row1.locator(CHECKBOX)
    assert cbs1.count() >= 2, "row 1 must enumerate at least 2 scenarios for the pick fixture"
    cbs1.nth(0).click()
    cbs1.nth(1).click()
    picked += 2
    row2 = _expand_row(page, 2)
    cbs2 = row2.locator(CHECKBOX)
    assert cbs2.count() >= 1, "row 2 must enumerate at least 1 scenario for the pick fixture"
    cbs2.nth(0).click()
    picked += 1
    expect(page.locator('[data-test="ai-gen-batch-submit"]')).to_be_enabled(timeout=10_000)

    with page.expect_request(
        lambda r: "/ai/batch-generate" in r.url and r.method == "POST"
    ) as req_info:
        page.locator('[data-test="ai-gen-batch-submit"]').click()
        confirm = page.locator(".batch-confirm-dialog")
        expect(confirm).to_be_visible(timeout=30_000)
        confirm.get_by_role("button", name="确认生成").click()

    request = req_info.value
    payload = json.loads(request.post_data)
    assert len(payload["items"]) == picked, (
        f"submit expanded to {len(payload['items'])} items, expected {picked} "
        f"(picked scenarios) out of {total_scenarios} enumerated"
    )
    assert "previewBatchId" not in payload, (
        "circle-selected submit must not carry previewBatchId (double multiply)"
    )

    response = request.response()
    assert response.status == 200, f"batch submit http {response.status}"
    body = response.json()
    assert body.get("code") == "0", f"batch submit business failure: {body.get('msg')}"
    batch_id = body["data"]["batchId"]

    # The on-page progress head must show the same expanded total.
    expect(page.locator('[data-test="ai-gen-batch-progress-count"]')).to_contain_text(
        f"/{picked}", timeout=30_000
    )

    # Persistence read-back over the real API (DB side of the same contract).
    token = _login()
    got = httpx.get(
        f"{API_BASE}/ai/batches/{batch_id}",
        headers={"Authorization": f"Bearer {token}"},
        timeout=15,
    )
    assert got.status_code == 200, f"batch read-back http {got.status_code}"
    detail = got.json()["data"]
    assert len(detail["items"]) == picked, (
        f"persisted batch holds {len(detail['items'])} items, expected {picked}"
    )
    assert str(detail.get("projectId", pid)) == str(pid)

    assert_no_errors(collectors, "scenario circle-selection matrix (30 rows)")
