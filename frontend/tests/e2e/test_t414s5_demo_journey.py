# -*- coding: utf-8 -*-
""" E2E: the factory demo journey doors are live and honest.

Shapes under test (all against the seeded factory data — read-only, zero
fixture pollution of the demo project itself):

 1. Dashboard quick-journey row shows SIX cards (the new
 run-flow-demo card among them) with its progress bar rendering.
 2. Deep link /#/api-test?view=flow&projectCode=demo-first&collectionName=..
 lands the rail on the seeded four-hop orchestration collection:
 the flow pane replaces browse rows, rows #1..#4 carry the script
 names, hop3 shows the precondition badge (ORDER_NO) plus the
 until-loop badge with its delay chip, hop4 shows the count-loop
 badge — drawn-is-executed at the factory face.
 3. Empty-state pointer: an empty collection switched to the flow pane
 shows the door button; clicking it jumps the page context to the
 demo project and lands the same four hops (visible-domain
 resolution, no second selector).
 4. First-visit onboarding overlay lists the journey as its sixth item
 and clicking it navigates through the same deep link onto the flow
 rows.

Known traps: a) source .env before pytest (login lockout otherwise);
b) the seed must exist (installkit/demo_seed.py --journey; V220 demo
accounts) — this file deliberately reuses tp_demo_ rows and purges only
its own throwaway project; c) overlay interception is why conftest
inject_token sets dismissed=1 — case 4 clears the key on purpose.
"""
import pytest
from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    pick_apitest_collection,
    pick_project_in_header,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

FLOW_URL = (
    f"{BASE_URL}/#/api-test?view=flow&projectCode=demo-first"
    "&collectionName=tp_demo_%E6%A0%B7%E4%BE%8B%E7%BC%96%E6%8E%92%E6%B5%81%E7%A8%8B_%E4%B8%8B%E5%8D%95%E6%97%85%E7%A8%8B"
)
DEMO_COLL_NAME = "tp_demo_样例编排流程_下单旅程"


def _expect_four_hops(page):
    """The landed flow pane: exactly the seeded hops, badges from the contract."""
    pane = page.locator('[data-test="apitest-flow-pane"]')
    expect(pane).to_be_visible(timeout=20000)
    expect(page.locator('[data-test="apitest-request-rows"]')).to_have_count(0)
    expect(page.locator('[data-test="apitest-flow-row-1"]')).to_contain_text("剧本1_登录可达")
    expect(page.locator('[data-test="apitest-flow-row-2"]')).to_contain_text("剧本2_建单并提取单号")
    hop3 = page.locator('[data-test="apitest-flow-row-3"]')
    expect(hop3).to_contain_text("剧本3_轮询订单状态")
    expect(hop3.locator('[data-test="apitest-flow-pre-tag"]')).to_contain_text("ORDER_NO")
    expect(hop3.locator('[data-test="apitest-flow-loop-tag"]')).to_contain_text("直到")
    hop4 = page.locator('[data-test="apitest-flow-row-4"]')
    expect(hop4).to_contain_text("剧本4_分页拉取空态")
    expect(hop4.locator('[data-test="apitest-flow-loop-tag"]')).to_contain_text("循环 2 次")
    expect(page.locator('[data-test^="apitest-flow-row-"]')).to_have_count(4)


def test_t414s5_journey_row_shows_six_cards(page, collectors):
    """Dashboard journey row shows SIX cards (the factory demo flow card among them)."""
    inject_token(page)
    row = page.locator('[data-test="journey-cards-row"]')
    expect(row).to_be_visible(timeout=15000)
    expect(page.locator('[data-test^="journey-card-"]')).to_have_count(6, timeout=8000)
    expect(page.locator('[data-test="journey-card-run-flow-demo"]')).to_be_visible(timeout=8000)
    expect(page.locator('[data-test="journey-card-run-flow-demo"]')).to_contain_text("编排")
    assert_no_errors(collectors, "T414s5 六卡旅程行")


def test_t414s5_flow_demo_deep_link_lands_four_hops(page, collectors):
    """Deep link ?view=flow&projectCode&collectionName lands the seeded four hops."""
    inject_token(page)
    page.goto(FLOW_URL, wait_until="domcontentloaded")
    _expect_four_hops(page)
    # The header context moved with the deep link (page-context single source).
    header = page.locator('[data-test="project-ctx-trigger"]')
    expect(header).to_contain_text("testPike 体验样例", timeout=8000)
    assert_no_errors(collectors, "T414s5 深链直入出厂剧本")


def test_t414s5_flow_empty_state_door_jumps_to_demo(page, collectors, api_client, uniq):
    """Empty flow pane shows the pointer door; one click lands the seeded hops."""
    r = api_client.post("/projects", json={
        "name": f"T414s5门项目{uniq}", "code": f"T415S5{uniq[:6].upper()}", "tags": ""})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    data = r.json()["data"]
    pid = data["id"] if isinstance(data, dict) else data
    r = api_client.post("/apitest/collections", json={
        "projectId": pid, "parentId": 0, "name": f"T414s5空接口集{uniq}", "tags": ""})
    assert r.json().get("code") == "0", f"建接口集失败: {r.text[:300]}"
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/api-test", wait_until="domcontentloaded")
        pick_project_in_header(page, f"T414s5门项目{uniq}")
        pick_apitest_collection(page, f"T414s5空接口集{uniq}")
        page.locator('[data-test="apitest-flow-flow-btn"]').click()
        empty = page.locator('[data-test="apitest-flow-empty"]')
        expect(empty).to_be_visible(timeout=10000)
        door = page.locator('[data-test="apitest-flow-demo-btn"] [data-test="empty-action"]')
        expect(door).to_be_visible(timeout=5000)
        expect(door).to_contain_text("跑一遍出厂剧本")
        door.click()
        _expect_four_hops(page)
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_t414s5_onboarding_overlay_lists_demo_journey(page, collectors):
    """First-visit overlay lists the demo journey as item six and navigates in."""
    inject_token(page)
    page.evaluate("() => localStorage.removeItem('tp_onboarding_dismissed')")
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    overlay = page.locator('[data-test="onboarding-overlay"]')
    expect(overlay).to_be_visible(timeout=15000)
    expect(page.locator('[data-test^="onboarding-item-"]')).to_have_count(6, timeout=8000)
    item = page.locator('[data-test="onboarding-item-run-flow-demo"]')
    expect(item).to_be_visible(timeout=8000)
    item.click()
    _expect_four_hops(page)
    assert_no_errors(collectors, "T414s5 引导浮层第六项")
