# -*- coding: utf-8 -*-
"""First E2E for the space miss-policy column .

Real browser on the live chain (no mocks): deep-link ?projectId lands the
global header context, the create dialog now carries the miss-ladder select
with its per-rung plain-word hint, the table column edits the rung inline,
and a reload proves the rung persisted in the DB (never a toast-only lie).
The write key is perm:mock:write — the admin login holds it, so the editable
select (not the static label) is what must render.

Rows are created under a dedicated project (uniq isolation) and torn down for
real; assert_no_errors bundles the 4xx/5xx, console error/warn and browser
Issues zero-tolerance gates.
"""
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, sql_query

SPACES_URL = f"{BASE_URL}/#/mock/spaces"


def _mk_project(api, name, code):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"project create failed: {r.text[:300]}"
    return r.json()["data"]


def test_t366b_space_policy_dialog_and_inline_edit(logged_in, collectors, api_client, uniq):
    """Create with reject at birth -> inline switch to passthrough -> reload truth."""
    page = logged_in
    pid = _mk_project(api_client, f"PolicyE2E {uniq}", f"plc{uniq}")
    space_key = f"plc{uniq}".lower()
    try:
        page.goto(f"{SPACES_URL}?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-spaces-page"]')).to_be_visible(timeout=15000)

        # create dialog: the miss-ladder select exists with the server-default
        # rung pre-chosen, and its hint speaks in plain words about the gates
        page.locator('[data-test="mock-spaces-new"]').click()
        dialog = page.locator('[data-test="mock-spaces-create-dialog"]')
        expect(dialog).to_be_visible(timeout=8000)
        dialog.locator('[data-test="mock-spaces-f-name"]').fill(f"e2e-policy-{uniq}")
        dialog.locator('[data-test="mock-spaces-f-key"]').fill(space_key)
        policy_field = dialog.locator('[data-test="mock-spaces-f-misspolicy"]')
        expect(policy_field).to_be_visible()
        # birth default = AI fallback; switching the dialog model to reject also
        # proves the per-rung hint swaps with it
        expect(dialog.locator(".field-hint").last).to_contain_text("AI", timeout=5000)
        policy_field.click()
        page.locator(".el-select-dropdown__item").filter(has_text="直接拒绝").last.click()
        expect(dialog.locator(".field-hint").last).to_contain_text("404", timeout=5000)
        dialog.locator('[data-test="mock-spaces-create-submit"]').click()

        row = page.locator('[data-test="mock-spaces-table"] tbody tr')
        expect(row).to_have_count(1, timeout=10000)

        # the column renders the editable select for a write-permitted operator
        # and honestly shows the rung born at create time
        sel = row.locator('[data-test="mock-spaces-policy-select"]')
        expect(sel).to_be_visible(timeout=5000)
        expect(sel).to_contain_text("直接拒绝", timeout=5000)

        # inline switch: open the row select, pick the passthrough rung, toast.
        # Filter by wording — the create-success toast can still be on screen
        # (toast stacking), so a bare .el-message--success is strict-mode ambiguous.
        sel.click()
        page.locator(".el-select-dropdown__item").filter(has_text="透传到真实服务").last.click()
        expect(page.locator(".el-message--success").filter(has_text="策略已保存")).to_be_visible(timeout=8000)

        # reload keeps truth: the rung lives in the DB, not in component state
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-spaces-table"] tbody tr')).to_contain_text(
            "透传到真实服务", timeout=10000)

        assert_no_errors(collectors, "T366b space miss-policy journey")
    finally:
        sql_query(f"DELETE FROM tp_mock_stub WHERE space_id IN (SELECT id FROM tp_mock_space WHERE space_key = '{space_key}')")
        sql_query(f"DELETE FROM tp_mock_space WHERE space_key = '{space_key}'")
        api_client.delete(f"/projects/{pid}/purge")
