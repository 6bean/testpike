# -*- coding: utf-8 -*-

from conftest import assert_no_errors, inject_token, nav_to
from playwright.sync_api import expect


def test_t343_leaf_help_tooltip_and_anchor(page, collectors):
    pass
    inject_token(page)
    nav_to(page, "用例管理", "/cases", '[data-test="case-table"]', submenu="设计用例")

    leaf = page.locator('[data-test="menu-cases"]')
    leaf.locator(".menu-leaf-label").hover()
    tip = page.locator(".el-popper:visible", has_text="用例的统一管理")
    expect(tip).to_have_count(0, timeout=3000)

    leaf.locator('[data-test="menu-cases-help"]').click()
    drawer = page.locator('[data-test="help-drawer"]')
    expect(drawer).to_be_visible(timeout=8000)
    expect(drawer.locator(".help-doc-title")).to_be_visible(timeout=8000)
    active = drawer.locator('.help-doc-item.active')
    expect(active.first).to_be_visible(timeout=5000)
    page.keyboard.press("Escape")
    expect(page.locator('[data-test="help-drawer"]:visible')).to_have_count(0, timeout=5000)

    assert_no_errors(collectors, "T343 ? 悬浮 tooltip+锚跳")


def test_t343_journey_cards_on_dashboard(page, collectors):
    pass
    inject_token(page)
    row = page.locator('[data-test="journey-cards-row"]')
    expect(row).to_be_visible(timeout=15000)
    for jid in ("gen-from-doc", "run-sample-case", "batch-schedule",
                "reserve-device", "connect-executor"):
        expect(page.locator(f'[data-test="journey-card-{jid}"]')).to_be_visible(timeout=8000)
    expect(page.locator('[data-test^="journey-progress"]').first).to_be_visible(timeout=8000)
    assert_no_errors(collectors, "T343 旅程卡渲染")


def test_t343_onboarding_overlay_first_visit(page, collectors):
    pass
    inject_token(page)
    page.evaluate("() => localStorage.removeItem('tp_onboarding_dismissed')")
    page.reload(wait_until="domcontentloaded")
    overlay = page.locator('[data-test="onboarding-overlay"]')
    expect(overlay).to_be_visible(timeout=15000)
    expect(overlay.locator('[data-test^="onboarding-item-"]').first).to_be_visible(timeout=5000)

    overlay.locator('[data-test="onboarding-dismiss"]').click()
    expect(page.locator('[data-test="onboarding-overlay"]:visible')).to_have_count(0, timeout=5000)
    assert page.evaluate("() => localStorage.getItem('tp_onboarding_dismissed')") == "1", \
        "关闭后未落首登记忆键"

    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="onboarding-overlay"]:visible')).to_have_count(0, timeout=5000)

    assert_no_errors(collectors, "T343 引导浮层首访")
