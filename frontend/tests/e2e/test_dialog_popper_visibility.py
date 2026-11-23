# -*- coding: utf-8 -*-

from playwright.sync_api import expect

from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    pick_project_in_header,
    purge_project,
    seed_project,
)


def _open_select_in(page, sel):
    """Toggle an EP2 select via its inner wrapper and return the visible items.

    Clicking the component root raced the teleported dropdown and read zero
    items (measured in the sibling document tests), so the wrapper is preferred
    whenever it exists.
    """
    wrapper = sel.locator(".el-select__wrapper")
    if wrapper.count():
        wrapper.first.click()
    else:
        sel.first.click()
    items = page.locator(".el-select-dropdown:visible .el-select-dropdown__item")
    expect(items.first).to_be_visible(timeout=5000)
    return items


def test_upload_dialog_category_options_all_visible(page, collectors, api_client, uniq):
    name = f"弹窗量测{uniq[:6]}"
    pid = seed_project(api_client, uniq, "DPOP", display_name=name)
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/extraction?tab=documents")
        page.wait_for_selector('[data-test="doc-list-page"]', timeout=15000)
        pick_project_in_header(page, name)
        page.locator('[data-test="doc-upload-btn"]').first.click()
        dialog = page.locator('[data-test="file-upload-dialog"]')
        expect(dialog).to_be_visible(timeout=10000)

        # Gap single source: the category row and the drop zone must not be
        # flush against each other (report face 2 of the screenshot).
        metrics = dialog.evaluate(
            """(root) => {
                 const row = root.querySelector('[data-test="doc-upload-category-row"]');
                 const drop = root.querySelector('[data-test="file-upload-drop"]');
                 if (!row || !drop) return null;
                 return { rowBottom: row.getBoundingClientRect().bottom,
                          dropTop: drop.getBoundingClientRect().top };
               }"""
        )
        assert metrics, "upload dialog inner blocks not found"
        gap = metrics["dropTop"] - metrics["rowBottom"]
        assert gap >= 8, f"category row and drop zone flush (gap={gap:.1f}px)"

        items = _open_select_in(page, dialog.locator('[data-test="doc-category-select"]'))
        total = items.count()
        assert total >= 4, f"category option source shrank: total={total}"
        visible = page.locator(
            ".el-select-dropdown:visible .el-select-dropdown__item:visible"
        ).count()
        assert visible >= total, f"clipped dropdown: visible={visible} total={total}"

        evidence = page.evaluate(
            """(dialogSel) => {
                 const dialogEl = document.querySelector(dialogSel);
                 const popperWrap = [...document.querySelectorAll('.el-popper')].find(
                     p => p.querySelector('.el-select-dropdown__item') && p.offsetParent !== null
                       && getComputedStyle(p).display !== 'none');
                 const dropdown = popperWrap ? popperWrap.querySelector('.el-select-dropdown') : null;
                 const overlay = dialogEl.closest('.el-overlay') || dialogEl;
                 const vh = window.innerHeight;
                 const rects = [...(dropdown ? dropdown.querySelectorAll('.el-select-dropdown__item') : [])]
                   .map(el => { const r = el.getBoundingClientRect();
                                 const inner = el.querySelector('.doc-cat-option');
                                 const ir = inner ? inner.getBoundingClientRect() : null;
                                 const desc = inner ? inner.querySelector('.doc-cat-option-desc') : null;
                                 return { text: (el.innerText || '').split('\\n')[0],
                                          top: r.top, bottom: r.bottom, h: r.height,
                                          innerBottom: ir ? ir.bottom : r.bottom,
                                          desc: desc ? desc.innerText : '' }; });
                 return {
                   dialogContainsPopper: popperWrap ? dialogEl.contains(popperWrap) : null,
                   popperZ: popperWrap ? Number(getComputedStyle(popperWrap).zIndex) : -1,
                   dialogZ: Number(getComputedStyle(overlay).zIndex),
                   rects, vh,
                 };
               }""",
            '[data-test="file-upload-dialog"]',
        )
        print("[dialog-popper] evidence:", evidence)
        assert evidence["dialogContainsPopper"] is False, (
            "select popper stayed inside the dialog container (clipping root cause)"
        )
        assert evidence["popperZ"] > evidence["dialogZ"], (
            f"popper z-index {evidence['popperZ']} not above dialog overlay {evidence['dialogZ']}"
        )
        vh = evidence["vh"]
        clipped = [r for r in evidence["rects"] if r["top"] < -0.5 or r["bottom"] > vh + 0.5]
        assert not clipped, f"options outside viewport: {clipped} vh={vh}"
        # Row height must fit the two-line shape (EP pins rows to 34px with
        # overflow:hidden; before the fix the effect line lost ~6px every row,
        # worst on the last option against the panel bottom edge).
        misfit = [r for r in evidence["rects"] if r["innerBottom"] > r["bottom"] + 0.5]
        assert not misfit, f"option rows clip their content: {misfit}"
        # Every category names its effect sentence — a missing line reads as a
        # hidden option (user report 2026-11-11 second-screen screenshot).
        no_desc = [r["text"] for r in evidence["rects"] if not r["desc"]]
        assert not no_desc, f"category options missing effect sentence: {no_desc}"
        page.keyboard.press("Escape")
        expect(dialog).to_be_visible(timeout=5000)
        assert_no_errors(collectors, "dialog-popper-visibility")
    finally:
        purge_project(api_client, pid)
