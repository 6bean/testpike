

import time

from playwright.sync_api import expect

from conftest import (
    BASE_URL,
    api_login,  # noqa: F401  (kept for parity with explore specs)
    assert_no_errors,
    pick_project_in_header,
    seed_project,
    sql_query,
)

# Poll ceiling for the async assembly chain (MQ -> ai-service -> submitAct).
_ACT_WAIT_S = 150


def _cleanup_project_rows(pid: int) -> None:
    sql_query(
        f"DELETE FROM tp_exec_task WHERE project_id = {pid}"
        " AND schema_version = 'explore-v1'"
    )
    sql_query(
        "DELETE FROM tp_explore_step WHERE session_id IN"
        f" (SELECT id FROM tp_explore_session WHERE project_id = {pid})"
    )
    sql_query(f"DELETE FROM tp_explore_session WHERE project_id = {pid}")
    # the ledger door mints a vault credential beside the state row; deleting
    # the state alone leaves it and blocks the project cleanup (FK, observed
    # on the first window run) — clear it here with the rest of the segment.
    sql_query(f"DELETE FROM tp_credential WHERE project_id = {pid}")
    sql_query(f"DELETE FROM tp_user_project WHERE project_id = {pid}")


def test_explore_login_state_journey(logged_in, api_client, collectors, uniq):
    page = logged_in
    project_name = f"探索选态旅程{uniq[:8]}"
    pid = seed_project(api_client, uniq, "ELS", display_name=project_name)
    state_name = f"journey-token-{uniq[:8]}"
    state_id = None
    try:
        # 1. register the identity through the same ledger door as the UI (no SQL insert)
        r = api_client.post("/session-states", json={
            "projectId": pid,
            "stateName": state_name,
            "kind": "token",
            "credKey": f"els_key_{uniq[:6]}",
            "secret": f"jwt.{uniq}.placeholder",
            "accountLabel": f"acct-{uniq[:6]}",
        })
        assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
        state_id = r.json()["data"]

        # 2. dialog journey inside the project segment carried by the global header
        pick_project_in_header(page, project_name)
        page.goto(f"{BASE_URL}/#/explore", wait_until="domcontentloaded")
        expect(page.locator('[data-test="explore-list-page"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="explore-create"]').click()
        dialog = page.locator('[data-test="explore-create-dialog"]')
        expect(dialog).to_be_visible()
        # first kind option = web (shape that owns the login-state dropdown)
        dialog.locator('[data-test="explore-kind"] .el-radio-button').first.click()
        dialog.locator('[data-test="explore-goal"]').fill("打开本地购物车页并确认标题可见")
        dialog.locator('[data-test="explore-start-url"]').fill(f"{BASE_URL}/e2e-cart")
        # the login-state select must list the row registered above and accept it
        state_option = page.locator(
            f'.el-select-dropdown__item:has-text("{state_name}")'
        )
        dialog.locator('[data-test="explore-login-state"]').click()
        expect(state_option.first).to_be_visible(timeout=5000)
        state_option.first.click()
        expect(
            dialog.locator('[data-test="explore-login-state"]')
        ).to_contain_text(state_name, timeout=5000)
        # estimate gate then confirm (dialog stays the only creation door)
        expect(
            dialog.locator('[data-test="explore-confirm-btn"]')
        ).to_be_disabled()
        dialog.locator('[data-test="explore-estimate-btn"]').click()
        expect(dialog.locator('[data-test="explore-estimate"]')).to_be_visible(timeout=20000)
        dialog.locator('[data-test="explore-confirm-btn"]').click()

        # 3. detail echo of the chosen identity
        detail = page.locator('[data-test="explore-detail-page"]')
        expect(detail).to_be_visible(timeout=20000)
        sid_row = sql_query(
            "SELECT id FROM tp_explore_session WHERE project_id ="
            f" {pid} ORDER BY id DESC LIMIT 1"
        ).strip()
        assert sid_row.isdigit(), f"session row missing after confirm (got {sid_row!r})"
        sid = int(sid_row)
        echo = page.locator('[data-test="explore-detail-login-state"]')
        expect(echo).to_be_visible(timeout=10000)
        expect(echo).to_contain_text(state_name)

        # 4. claim-side assembly over the real chain: envelope carries sessionState{name}
        like = f'%"sessionState":{{"name":"{state_name}"}}%'
        seen = "0"
        deadline = time.time() + _ACT_WAIT_S
        while time.time() < deadline:
            seen = sql_query(
                "SELECT count(*) FROM tp_exec_task WHERE project_id ="
                f" {pid} AND schema_version = 'explore-v1'"
                f" AND action_json::text LIKE '{like}'"
            ).strip()
            if seen != "0":
                break
            time.sleep(3)  # non-Playwright async: DB-side poll of the MQ consumer chain
        assert seen != "0", (
            "explore-act envelope never carried sessionState{name} within "
            f"{_ACT_WAIT_S}s (async assembly chain silent)"
        )
        # the credential reference namespace must not leak into the declaration
        leaked = sql_query(
            "SELECT count(*) FROM tp_exec_task WHERE project_id ="
            f" {pid} AND schema_version = 'explore-v1'"
            ' AND action_json::text LIKE \'%"sessionState":{"name":"els_key%\''
        ).strip()
        assert leaked == "0", f"credKey leaked into sessionState.name ({leaked} rows)"

        # 5. cancel the session from the detail door (soft terminate, honest UI path)
        page.locator('[data-test="explore-detail-cancel"]').click()
        box = page.locator('.el-message-box')
        expect(box).to_be_visible(timeout=5000)
        box.locator('.el-button--primary').click()
        expect(page.locator('[data-test="explore-detail-status"]')).to_be_visible(timeout=10000)

        assert_no_errors(collectors, "explore login-state journey")
    finally:
        if state_id is not None:
            api_client.delete(f"/session-states/{state_id}")
        _cleanup_project_rows(pid)
        sql_query(
            f"DELETE FROM tp_project WHERE id = {pid}"
        )
