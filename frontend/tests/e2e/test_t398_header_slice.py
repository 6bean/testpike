""" header single-select journey: header pick re-slices the mock pages.

Ruling-doc acceptance (2026-10-15, single-select contract, verify item 5):
1. pick P in the header -> #/mock/spaces lists the P space row; the create
 dialog's ownership echo shows P's name (echo follows the context);
2. switch the header to Q (no spaces) WITHOUT leaving the page -> the segment
 follows (P's row gone, honest empty), the docs page drops to the "no
 space" empty of Q with URL write-back carrying projectId=Q, and the echo
 re-opens showing Q's name;
3. the retired '+N' summary never appears on the trigger.

Real admin 8081 -> core 8083 chain (no mocks); throwaway uniq projects
purged in finally, demo fleet untouched.
"""
import json

import pytest
from conftest import (
    ADMIN_PASSWORD,
    ADMIN_USERNAME,
    BASE_URL,
    assert_no_errors,
    inject_user_token,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(240)

SPACES_URL = f"{BASE_URL}/#/mock/spaces"


def _mk_project(api, name, code):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"create project failed: {r.text[:300]}"
    return r.json()["data"]


def _mk_space(api, pid, name, key):
    r = api.post("/mock/spaces", json={"projectId": pid, "name": name, "spaceKey": key})
    assert r.status_code == 200 and r.json().get("code") == "0", f"create space failed: {r.text[:300]}"
    return r.json()["data"]


def _pick_in_header(page, project_name):
    """Single-select header pick: a click on the option lands the selection and
 closes the dropdown immediately (multiple posture retired by )."""
    page.locator('[data-test="project-ctx-select"]').click()
    page.locator(
        f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{project_name}"):visible'
    ).first.click()


def _dialog_echo_value(page, expect_name):
    """Open the create dialog, assert the read-only ownership echo equals the
    header project name, close it. Echo single source = header context."""
    page.locator('[data-test="mock-spaces-new"]').click()
    dialog = page.locator('[data-test="mock-spaces-create-dialog"]')
    expect(dialog).to_be_visible(timeout=10000)
    expect(dialog.locator('[data-test="owner-echo-value"]')).to_have_text(expect_name, timeout=10000)
    # rule 1: echo is read-only text, never a second selector inside the dialog
    expect(dialog.locator('[data-test="owner-echo"] select, [data-test="owner-echo"] .el-select')).to_have_count(0)
    page.keyboard.press("Escape")
    expect(dialog).to_be_hidden(timeout=10000)


def test_t398_header_switch_reslices_mock_pages(logged_in, collectors, api_client, uniq):
    page = logged_in
    name_p = f"T398甲项目{uniq}"
    name_q = f"T398乙项目{uniq}"
    space_name = f"T398空间{uniq}"
    pid = _mk_project(api_client, name_p, f"T398P{uniq[:6].upper()}")
    qid = _mk_project(api_client, name_q, f"T398Q{uniq[:6].upper()}")
    # spaceKey must be lowercase (space-key gate proven in the suite)
    _mk_space(api_client, pid, space_name, f"t398{uniq.lower()}")
    try:
        inject_user_token(page, ADMIN_USERNAME, ADMIN_PASSWORD)

        # --- header pick P takes effect immediately; no '+N' anywhere ---
        _pick_in_header(page, name_p)
        trigger = page.locator('[data-test="project-ctx-trigger"]')
        expect(trigger).to_contain_text(name_p, timeout=10000)
        expect(trigger).not_to_contain_text("+1")

        # --- spaces page on P: P's space row + dialog echo = P ---
        page.goto(SPACES_URL, wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-spaces-page"]')).to_be_visible(timeout=15000)
        table = page.locator('[data-test="mock-spaces-table"]')
        expect(table.get_by_text(space_name).first).to_be_visible(timeout=15000)
        _dialog_echo_value(page, name_p)

        # --- docs screen on P: AN-4 opens the space workbench from the
        # row (the tab host keeps the docs surface + its filter row) ---
        page.locator('[data-test="mock-spaces-open-workbench"]').first.click()
        expect(page.locator('[data-test="mock-workbench-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="mock-docs-page"]')).to_be_visible(timeout=15000)

        # --- switch the header to Q (no spaces): the segment follows in place —
        # the workbench space id no longer exists in Q, honest missing state ---
        _pick_in_header(page, name_q)
        expect(trigger).to_contain_text(name_q, timeout=10000)
        expect(page.locator('[data-test="mock-workbench-space-missing"]')).to_be_visible(timeout=15000)
        # URL write-back follows the new context
        assert f"projectId={qid}" in page.url, f"URL 未随上下文回写: {page.url}"

        # --- spaces page re-slice: P's row gone, echo follows to Q ---
        page.goto(SPACES_URL, wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-spaces-empty"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="mock-spaces-table"]').get_by_text(space_name)).to_have_count(0)
        _dialog_echo_value(page, name_q)

        # the on-disk single-select context is exactly [Q]
        me = api_client.get("/users/me").json()["data"]
        uid = me["user"]["userId"]
        stored = page.evaluate(f"() => localStorage.getItem('tp_proj_ctx_{uid}')")
        assert json.loads(stored) == [qid], f"单选持久化异常: {stored!r}"

        assert_no_errors(collectors, "T398 头部单选切换切片跟变")
    finally:
        # purge both throwaway projects (space rows drop with the project purge)
        api_client.delete(f"/projects/{qid}/purge")
        api_client.delete(f"/projects/{pid}/purge")
