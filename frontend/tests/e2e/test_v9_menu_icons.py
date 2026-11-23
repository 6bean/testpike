# -*- coding: utf-8 -*-

import re

import pytest
from conftest import assert_no_errors
from playwright.sync_api import expect

# Journey group indexes (group-<index> data-test anchors); after the two-level
# restructure the APP automation domain is its own top-level group (nine groups)
EXPAND_GROUPS = ["workbench", "design", "aiCapabilities", "execute", "appauto", "mock", "results", "dataAssets", "system"]
ROUTES_10 = ["/dashboard", "/cases", "/review", "/requirements", "/batches",
             "/projects", "/tags", "/skills", "/tools", "/audit-logs",
             "/web-elements"]


def _icon_frame_issues(page, root):
    pass
    bad = []
    items = page.locator(f'{root} .el-icon')
    n = items.count()
    for i in range(n):
        el = items.nth(i)
        if not el.is_visible():
            continue
        svg = el.locator("svg")
        if svg.count() == 0:
            bad.append(f"{root}:svg-missing@{i}")
            continue
        box = svg.first.bounding_box()
        if not box or box["width"] < 6 or box["height"] < 6:
            bad.append(f"{root}:zero-size@{i} box={box}")
    return bad


def _set_group_open(page, box, open_it):
    pass
    want = "true" if open_it else "false"
    title = box.locator("> .el-sub-menu__title").first
    if box.get_attribute("aria-expanded") != want:
        title.click()
        expect(box).to_have_attribute("aria-expanded", want, timeout=5000)


def test_v9_menu_icons_20_expand_10_routes(logged_in, collectors):
    pass
    page = logged_in
    issues = []

    token = page.evaluate("() => localStorage.getItem('tp_access_token')")
    for r in ROUTES_10:
        page.goto("http://localhost:5173/#/login", wait_until="domcontentloaded")
        page.evaluate("(t) => localStorage.setItem('tp_access_token', t)", token)
        page.goto(f"http://localhost:5173/#{r}", wait_until="domcontentloaded")
        expect(page.locator(".layout-menu")).to_be_visible()
        issues += _icon_frame_issues(page, ".layout-menu")

    grp_titles = page.locator('.layout-menu > .el-sub-menu > .el-sub-menu__title')
    expect(grp_titles).to_have_count(len(EXPAND_GROUPS), timeout=10000)

    for cycle in range(20):
        for g in EXPAND_GROUPS:
            box = page.locator(f'[data-test="group-{g}"]').first
            title = box.locator("> .el-sub-menu__title").first
            expect(title).to_be_visible()
            issues += _icon_frame_issues(page, f'[data-test="group-{g}"] > .el-sub-menu__title')
            _set_group_open(page, box, True)
            popup_items = page.locator(f'[data-test="group-{g}"] .el-menu-item')
            expect(popup_items.first).to_be_attached()
            issues += _icon_frame_issues(page, f'[data-test="group-{g}"]')
            _set_group_open(page, box, False)
        if cycle in (7, 15):
            dark_before = page.evaluate("() => document.documentElement.classList.contains('dark')")
            page.locator(".header-icon").first.click()
            page.wait_for_function(
                "(d) => document.documentElement.classList.contains('dark') !== d",
                arg=dark_before, timeout=5000)
            issues += _icon_frame_issues(page, ".layout-menu")

    page.locator(".collapse-btn").first.click()
    expect(page.locator(".el-menu--collapse")).to_be_visible()
    issues += _icon_frame_issues(page, ".el-menu--collapse")

    assert not issues, f"V9 图标缺帧 {len(issues)} 处: {issues[:20]}"
    assert_no_errors(collectors, "TC-V9-01 menu icons")
