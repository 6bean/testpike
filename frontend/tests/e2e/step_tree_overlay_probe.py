"""Shared overlay-expansion probe for the step-editor asset reference surface.

Single-source for the whole "select the input step -> open the asset-ref
overlay -> expand the lazy catalog popper -> prove the seeded fragment option
and its kind groups rendered" walk (R40: the E2E spec and the vision capture
harness are two consumers of one DOM contract; neither may keep a private
copy). Hard assertions only — no conditional wrapping (iron rule 5d): the
option, its owner line and the >=3 group titles must all render or the walk
fails loudly.

Consumers:
  - frontend/tests/e2e/test_step_tree_asset_ref.py (behaviour spec)
  - tools/vision_shots_step_tree_overlay.py (closure vision capture lane)
"""
from playwright.sync_api import expect


def expand_asset_ref_overlay(page, dialog, rows, frag_id, frag_name):
    """Drive the overlay open and return (popper, option-locator).

    ``rows`` is the step-tree row locator of an already-open dialog with the
    seeded case script loaded; row index 1 is the input step whose form hosts
    the overlay. The catalog loads lazily on first dropdown open (worst-case
    fixture: 33+ published heads across three kinds, server page cap 200), so
    the fragment option appearing is the proof the fetch has landed and the
    group headers render together with it.
    """
    rows.nth(1).click()
    overlay = dialog.locator('[data-test="step-tree-asset-ref"]')
    expect(overlay).to_be_visible(timeout=8000)
    overlay.click()
    popper = page.locator(".el-select-dropdown:visible").last
    expect(popper).to_be_visible(timeout=10000)
    opt = popper.locator(f'[data-test="step-tree-asset-opt-{frag_id}"]').first
    expect(opt).to_be_visible(timeout=10000)
    expect(opt).to_contain_text(frag_name)
    expect(popper.locator(".ste-asset-ref-opt-owner").first).to_be_visible()
    titles = popper.locator(".el-select-group__title")
    assert titles.count() >= 3, (
        f"three seeded kinds must group, got {titles.count()} titles")
    return popper, opt
