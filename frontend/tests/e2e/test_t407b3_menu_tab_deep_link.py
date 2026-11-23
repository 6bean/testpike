""" E2E gate: retired menu paths and command-palette synonyms
must land on their host tab in a real browser (R24 zero-loss, live half).

Covers:
1. /skill-market deep link redirects to /skills?tab=market with the market
 tab active and the embedded market page rendered.
2. /tool-market deep link redirects to /tools?tab=market likewise.
3. /suites deep link redirects to /cases?tab=suites with the suites tab
 active and the embedded suite list rendered.
4. Ctrl+K palette: typing the retired-leaf word for regression suites and
 pressing Enter navigates to /cases?tab=suites (synonym tab carried by
 the menu hit, not the host default).

Old-path bookmarks are the regression surface: after the AN-5 merges the
redirect must keep the tab query, the tab component must switch, and the
embedded page root must mount. ``--headless=new`` is enforced in conftest
(iron law 5c); any 4xx / console warning / browser issue fails the run.
"""
import re

import pytest
from conftest import BASE_URL, assert_no_errors, inject_token
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)


def _assert_tab_active(page, tabs_selector, tab_anchor):
    """The active el-tabs item must be the one carrying the expected tab label
    anchor (locale independent - matches the DOM slot, not translated text)."""
    expect(
        page.locator(f"{tabs_selector} .el-tabs__item.is-active {tab_anchor}")
    ).to_have_count(1, timeout=10000)


def test_skill_market_legacy_path_lands_on_market_tab(page, collectors):
    """TC--3b-01: /skill-market redirects to /skills?tab=market, tab active."""
    inject_token(page)
    page.goto(f"{BASE_URL}/#/skill-market", wait_until="domcontentloaded")
    expect(page).to_have_url(re.compile(r"/#/skills\?.*tab=market"), timeout=15000)
    expect(page.locator('[data-test="skill-page-tabs"]')).to_be_visible(timeout=10000)
    _assert_tab_active(page, '[data-test="skill-page-tabs"]', '[data-test="skill-tab-market"]')
    expect(page.locator('[data-test="skill-market-page"]').first).to_be_visible(timeout=10000)
    assert_no_errors(collectors, "T407 skill-market legacy deep link")


def test_tool_market_legacy_path_lands_on_market_tab(page, collectors):
    """TC--3b-02: /tool-market redirects to /tools?tab=market, tab active."""
    inject_token(page)
    page.goto(f"{BASE_URL}/#/tool-market", wait_until="domcontentloaded")
    expect(page).to_have_url(re.compile(r"/#/tools\?.*tab=market"), timeout=15000)
    expect(page.locator('[data-test="tool-page-tabs"]')).to_be_visible(timeout=10000)
    _assert_tab_active(page, '[data-test="tool-page-tabs"]', '[data-test="tool-tab-market"]')
    expect(page.locator('[data-test="tool-market-page"]').first).to_be_visible(timeout=10000)
    assert_no_errors(collectors, "T407 tool-market legacy deep link")


def test_suites_legacy_path_lands_on_suites_tab(page, collectors):
    """TC--3b-03: /suites redirects to /cases?tab=suites, tab active."""
    inject_token(page)
    page.goto(f"{BASE_URL}/#/suites", wait_until="domcontentloaded")
    expect(page).to_have_url(re.compile(r"/#/cases\?.*tab=suites"), timeout=15000)
    expect(page.locator('[data-test="case-page-tabs"]')).to_be_visible(timeout=10000)
    _assert_tab_active(page, '[data-test="case-page-tabs"]', '[data-test="case-tab-suites"]')
    expect(page.locator('[data-test="suite-list-page"]').first).to_be_visible(timeout=10000)
    assert_no_errors(collectors, "T407 suites legacy deep link")


def test_command_palette_synonym_opens_suites_tab(page, collectors):
    """TC--3b-04: Ctrl+K search of the retired word opens /cases?tab=suites."""
    inject_token(page)
    page.keyboard.press("Control+k")
    expect(page.locator('[data-test="global-search-dialog"]')).to_be_visible(timeout=5000)
    inp = page.locator('[data-test="global-search-input"]')
    inp.fill("回归集")
    body = page.locator('[data-test="global-search-body"]')
    menu_row = body.locator('[data-test="global-search-row-menu"]').first
    expect(menu_row).to_be_visible(timeout=10000)
    page.keyboard.press("Enter")
    expect(page).to_have_url(re.compile(r"/#/cases\?.*tab=suites"), timeout=15000)
    expect(page.locator('[data-test="suite-list-page"]').first).to_be_visible(timeout=10000)
    assert_no_errors(collectors, "T407 command-palette synonym tab")
