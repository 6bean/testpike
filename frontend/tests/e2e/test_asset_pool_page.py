"""Shared asset pool page E2E .

Chain: API-seeded owner/consumer projects and 34 published heads (server-paged,
worst-case scale) -> pin a stale version -> fail-closed withdrawal probe on the
API bridge (names the active referrer) -> browser list renders a paged window ->
kind filter -> reference roster drawer shows the pinned version with a stale
marker -> withdrawal door honest-disabled outside the owner project (R25) ->
force-withdraw dialog inside the owner segment clears the head from the pool ->
hard reload + DB status pin -> fixture purge with zero residue.

Field notes: a) run with `set -a && . ./.env` for ADMIN_INIT_PASSWORD; b) the
page lives on the header project segment for the owner fence only — the pool read
is global by product rule; c) the fail-closed refusal is probed through
api_client directly so the intentional 4xx never pollutes page collectors, the
browser then only walks the successful force path; d) every fixture name carries
the uniq suffix and every fixture row is cleaned by id in finally (R37).
"""
import pytest
from conftest import (
    ADMIN_PASSWORD,
    assert_no_errors,
    inject_user_token,
    pick_project_in_header,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(420)

POOL_URL = "asset-pool"


def _api_ok(r):
    assert r.status_code == 200 and str(r.json().get("code")) == "0", f"API failure: {r.text[:300]}"
    return r.json()["data"]


def _publish(api_client, project_id, kind, name, payload_json):
    return _api_ok(api_client.post("/shared-assets", json={
        "projectId": project_id, "kind": kind, "name": name, "payloadJson": payload_json}))


def _pool_row(page, asset_name):
    """Locate the named head row, walking at most two pages of the pager."""
    table = page.locator('[data-test="asset-table"]')
    for page_no in (1, 2):
        row = table.locator("tbody tr").filter(has_text=asset_name)
        if row.count() > 0:
            return row.first
        nxt = page.locator('[data-test="asset-pool-pagination"] button.btn-next')
        expect(nxt).to_be_visible(timeout=5000)
        nxt.click()
        expect(table.locator("tbody tr").first).to_be_visible(timeout=10000)
    raise AssertionError(f"head {asset_name} not found within the first two pages")


def test_asset_pool_page(page, collectors, api_client, uniq):
    owner_name = f"YPoolOwner{uniq}"
    cons_name = f"YPoolCons{uniq}"
    a_name = f"yfrag-{uniq[:6]}"
    oid = _api_ok(api_client.post("/projects", json={"name": owner_name, "code": f"YPO{uniq[:6]}"}))
    cid = _api_ok(api_client.post("/projects", json={"name": cons_name, "code": f"YPC{uniq[:6]}"}))
    v1_id = v2_id = None
    try:
        # ---- seed: one chain (two versions, stale pin) + worst-case scale ----
        v1 = _publish(api_client, oid, "case_fragment", a_name, "[]")
        v1_id = v1["id"]
        _api_ok(api_client.post("/shared-assets/references", json={
            "projectId": cid, "assetRowId": v1_id,
            "referrerKind": "tp_case", "referrerId": 987000000}))
        for i in range(29):
            _publish(api_client, oid, "case_fragment", f"yfill-{i:02d}-{uniq[:4]}", "[]")
        for i in range(4):
            _publish(api_client, oid, "login_recipe", f"ylogin-{i:02d}-{uniq[:4]}",
                     '{"label":"seed","assemblies":[]}')
        v2 = _publish(api_client, oid, "case_fragment", a_name,
                      '[{"step":"reworked"}]')  # republish -> v2 head + ASSET_UPDATED notice
        v2_id = v2["id"]
        assert v2["assetVersion"] == 2, f"republish did not advance the chain: {v2}"

        # ---- fail-closed probe (API bridge, off the page collectors) ----
        # the shared api_client bridge carries query state in the URL (conftest convention)
        refusal = api_client.post(f"/shared-assets/{v2_id}/withdraw?projectId={oid}&force=false")
        denied = refusal.status_code >= 400 or str(refusal.json().get("code")) != "0"
        assert denied, f"withdrawal with active pins must fail closed: {refusal.text[:200]}"
        assert cons_name in refusal.text, f"refusal must name the active referrer: {refusal.text[:200]}"

        # ---- pool page: paged worst-case window ----
        inject_user_token(page, "admin", ADMIN_PASSWORD)
        page.goto(f"{page.url.split('#')[0]}#/{POOL_URL}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        root = page.locator('[data-test="asset-pool-page"]')
        expect(root).to_be_visible(timeout=15000)
        table = page.locator('[data-test="asset-table"]')
        expect(table).to_be_visible(timeout=10000)
        page1_rows = table.locator("tbody tr").count()
        assert page1_rows == 20, f"server-paged window expected 20 rows, got {page1_rows}"
        expect(page.locator('[data-test="asset-pool-pagination"]')).to_contain_text("34", timeout=10000)

        # ---- kind filter narrows to the four login recipes ----
        page.locator('[data-test="asset-kind-select"]').click()
        # option order mirrors KIND_OPTIONS (case_fragment, action_component,
        # login_recipe, gen_function) — index-picked to stay locale-independent.
        option = page.locator(".tp-asset-kind-popper .el-select-dropdown__item").nth(2)
        expect(option).to_be_visible(timeout=5000)
        option.click()
        expect(table.locator("tbody tr")).to_have_count(4, timeout=10000)
        page.reload(wait_until="domcontentloaded")  # filter is view state, reload restores the window
        expect(table.locator("tbody tr")).to_have_count(20, timeout=15000)  # hard wait: never walk the pager against an empty mounting window

        # ---- reference roster drawer: stale pin visible against the v2 head ----
        row = _pool_row(page, a_name)
        expect(row).to_contain_text("v2")
        row.locator('[data-test="asset-refs-open"]').click()
        drawer = page.locator('[data-test="asset-refs-drawer"]')
        expect(drawer).to_be_visible(timeout=10000)
        expect(drawer.locator('[data-test="asset-ref-count"]')).to_contain_text("1", timeout=5000)
        expect(drawer.locator('[data-test="asset-pin-version"]')).to_have_text("v1", timeout=5000)
        expect(drawer.locator('[data-test="asset-refs-table"] tbody tr')).to_contain_text(cons_name)
        expect(drawer.locator('[data-test="asset-stale-tag"]')).to_be_visible(timeout=5000)
        # ---- version timeline under the roster: two rows, one head, pins on v1 ----
        # hard assertions only (iron law 5d); row order comes from core verbatim
        timeline = drawer.locator('[data-test="asset-timeline-table"]')
        expect(timeline.locator("tbody tr")).to_have_count(2, timeout=5000)
        v2_row = timeline.locator("tbody tr").filter(has_text="v2")
        v1_row = timeline.locator("tbody tr").filter(has_text="v1")
        expect(v2_row).to_have_count(1, timeout=5000)
        expect(v1_row).to_have_count(1, timeout=5000)
        expect(v2_row.locator('[data-test="asset-timeline-head-tag"]')).to_have_count(1, timeout=5000)
        expect(v2_row.locator('[data-test="asset-timeline-pinned"]')).to_contain_text("0", timeout=5000)
        expect(v1_row.locator('[data-test="asset-timeline-head-tag"]')).to_have_count(0, timeout=5000)
        expect(v1_row.locator('[data-test="asset-timeline-pinned"]')).to_contain_text("1", timeout=5000)
        page.keyboard.press("Escape")
        expect(drawer).not_to_be_visible(timeout=5000)

        # ---- R25 honest gate: outside the owner segment the door is disabled ----
        pick_project_in_header(page, cons_name)
        row = _pool_row(page, a_name)
        expect(row.locator('[data-test="asset-withdraw-disabled"]')).to_be_visible(timeout=5000)

        # ---- owner segment: force withdrawal through the dialog clears the head ----
        pick_project_in_header(page, owner_name)
        row = _pool_row(page, a_name)
        row.locator('[data-test="asset-withdraw-open"]').click()
        dialog = page.locator('[data-test="asset-withdraw-dialog"]')
        expect(dialog).to_be_visible(timeout=5000)
        expect(dialog).to_contain_text(a_name)
        dialog.locator('[data-test="asset-withdraw-force"]').click()
        dialog.locator('[data-test="asset-withdraw-confirm"]').click()
        expect(page.locator(".el-message--success")).to_be_visible(timeout=15000)
        table_rows = page.locator('[data-test="asset-table"] tbody tr')
        expect(table_rows.filter(has_text=a_name)).to_have_count(0, timeout=15000)
        page.reload(wait_until="domcontentloaded")
        expect(table_rows.filter(has_text=a_name)).to_have_count(0, timeout=15000)

        # ---- DB pins: whole chain withdrawn, pool count dropped ----
        assert sql_query(
            f"SELECT status FROM tp_asset WHERE id = {v1_id}") == "2", "v1 row must carry the withdrawn status"
        assert sql_query(
            f"SELECT status FROM tp_asset WHERE id = {v2_id}") == "2", "v2 row must carry the withdrawn status"
        assert sql_query(
            f"SELECT count(*) FROM tp_notification WHERE event_type LIKE 'ASSET_%' AND (dedup_key LIKE '%:{v1_id}:%' OR dedup_key LIKE '%:{v2_id}:%')"
        ) != "0", "update/withdraw notices must be on record"

        assert_no_errors(collectors, "shared asset pool page full chain")
    finally:
        sql_query(f"DELETE FROM tp_asset_reference WHERE referrer_project_id = {cid}")
        sql_query(f"DELETE FROM tp_asset WHERE project_id = {oid}")
        sql_query(
            f"DELETE FROM tp_notification WHERE event_type LIKE 'ASSET_%' AND (dedup_key LIKE '%:{v1_id}:%' OR dedup_key LIKE '%:{v2_id}:%')")
        for pid in (oid, cid):
            api_client.delete(f"/projects/{pid}/purge?force=true")
        assert sql_query(f"SELECT count(*) FROM tp_asset WHERE project_id IN ({oid},{cid})") == "0"
        assert sql_query(f"SELECT count(*) FROM tp_asset_reference WHERE referrer_project_id = {cid}") == "0"
