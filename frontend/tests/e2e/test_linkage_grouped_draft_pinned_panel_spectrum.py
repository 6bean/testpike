"""AN2 end-to-end spectrum: one real case edit reaches the pinned panel.

Proves the full upstream-linkage loop on the REAL chain (admin door -> linkage
bus -> impact ledger -> proposal relay -> system draft card -> one-key accept),
no mocks anywhere in the driven path:

 1. seed project + tester member + one real case, then a downstream script
 row (tp_script is written by the generation chain only; inserting it via
 SQL here is fixture data for the downstream side, exactly as the L8 pool
 precedent does — the edge registration itself is V514 global data).
 2. PUT the case through the admin door: the save publishes changed:case,
 the bus fans the case->script edge out and UPSERTs a grouped_draft row
 into tp_linkage_impact (polled, never hand-written).
 3. relay one trustworthy repaired-action proposal through the internal
 door (the same ai-service calls): a SCRIPT_REPAIR system draft appears
 in tp_ai_chat_draft (polled).
 4. a REAL browser signs in as the tester, pins the case into the dock, and
 the pending-groups door lights up on the linkage panel; the human takes
 exactly one action set: check-all -> confirm (one-shot ticket) -> apply
 (the two-stage gate) -> receipt; then the enumeration read converges to
 the empty set and the impact row is consumed (status=4, consumed_at set).

Every phase ends with the 4xx/5xx + console + Issues-panel zero-tolerance
check. Projects are purged through the shared physical cascade.
"""
import json
import time

import httpx
import pytest
from playwright.sync_api import expect

from conftest import (
    API_BASE,
    BASE_URL,
    add_member,
    assert_no_errors,
    core_base_url,
    inject_user_token,
    mk_tester,
    purge_project,
    seed_project,
    sql_query,
)
from test_selection_ask_and_palette_ask import _seed_case

pytestmark = [pytest.mark.timeout(900), pytest.mark.xdist_group("an2-linkage-spectrum")]

PASSWORD = "Test@12345"


def _sql_json_one(query: str):
    """Run a json-producing SQL query and parse the single scalar/row result."""
    raw = sql_query(query)
    assert raw, f"empty sql result for: {query[:120]}"
    return json.loads(raw)


def _poll_json(where: str, table: str, cols: str, timeout_s: int):
    """Poll the ledger row the async chain writes; return the parsed row or None."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        raw = sql_query(
            f"SELECT json_agg(t) FROM (SELECT {cols} FROM {table} WHERE {where}) t"
        )
        if raw and raw != "NULL":
            rows = json.loads(raw)
            if rows:
                return rows[0]
        time.sleep(1.0)
    return None


def test_case_edit_fans_out_to_grouped_card_and_one_key_accept(page, collectors, api_client, uniq):
    pid = seed_project(api_client, uniq, "AN2SPEC")
    tester_id, _ = mk_tester(api_client, uniq)
    add_member(api_client, pid, tester_id)
    case_id = _seed_case(api_client, pid, f"AN2联动谱用例{uniq[:6]}")
    # The case list applies a member self-predicate (created_by OR assignee_id,
    # CaseFilters), so the seeded case must be assigned to the tester before the
    # browser leg can see its row; assignment does not publish changed:case and
    # therefore never annotates an extra linkage impact.
    assigned = api_client.post(
        f"/cases/{case_id}/assign", json={"assigneeId": tester_id}
    )
    assert assigned.status_code == 200 and assigned.json().get("code") == "0", \
        f"assign case to tester failed: {assigned.text[:200]}"
    try:
        # --- downstream fixture: the script row the repair chain targets -----
        sql_query(
            "INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by, version) "
            f"VALUES ({case_id}, 'v2', "
            f"'{{\"caseId\":{case_id},\"setup\":[],\"steps\":[{{\"no\":1,\"action\":\"navigate\",\"target\":\"/login\"}}],\"env\":{{}}}}'::jsonb, "
            "1, 2, 1)"
        )
        script_id = _sql_json_one(f"SELECT to_json(id) FROM tp_script WHERE case_id={case_id}")

        # --- upstream save: real admin door publishes changed:case -----------
        got = api_client.get(f"/cases/{case_id}")
        assert got.json().get("code") == "0", f"case detail failed: {got.text[:200]}"
        payload = got.json()["data"]
        payload["title"] = f"AN2联动谱用例{uniq[:6]}-已修订"
        put = api_client.put(f"/cases/{case_id}", json=payload)
        assert put.status_code == 200 and put.json().get("code") == "0", \
            f"case PUT failed: {put.text[:300]}"

        # --- bus annotation: grouped_draft impact row appears ----------------
        impact = _poll_json(
            f"upstream_object_type='case' AND upstream_object_id={case_id} "
            "AND downstream_object_type='script' AND disposition_strategy='grouped_draft'",
            "tp_linkage_impact", "id, downstream_id, status", timeout_s=20,
        )
        assert impact is not None, "linkage bus never annotated the case->script grouped_draft impact"
        assert int(impact["downstream_id"]) == int(script_id)
        impact_id = int(impact["id"])

        # --- proposal relay: the internal door ai-service uses ---------------
        repaired = {
            "caseId": int(case_id),
            "setup": [],
            "steps": [
                {"no": 1, "action": "navigate", "target": "/login"},
                {"no": 2, "action": "click", "target": "登录按钮（联动修复步）"},
            ],
            "env": {},
        }
        relay = httpx.post(
            f"{core_base_url()}/internal/linkage-proposals",
            json={
                "impactId": impact_id,
                "downstreamObjectType": "script",
                "downstreamObjectId": int(script_id),
                "proposalJson": json.dumps(repaired, ensure_ascii=False),
                "schemaVersion": "v2",
                "modelName": "e2e-linkage-relay",
                "baseScriptVersion": 1,
                "rejectReason": None,
            },
            timeout=30,
        )
        assert relay.status_code == 200 and relay.json().get("code") == "0", \
            f"proposal relay failed: {relay.status_code} {relay.text[:300]}"

        # --- system SCRIPT_REPAIR draft lands ---------------------------------
        draft = _poll_json(
            f"object_type='SCRIPT_REPAIR' AND target_id={case_id}",
            "tp_ai_chat_draft", "id, status", timeout_s=15,
        )
        assert draft is not None, "no SCRIPT_REPAIR system draft after the proposal relay"

        # --- real browser: tester pins the case, panel door lights up --------
        inject_user_token(page, f"ttester_{uniq}", PASSWORD)
        page.goto(f"{BASE_URL}/#/cases?projectId={pid}")
        # The page tab bar renders label-only panes and the case table lives
        # outside them (data-test=case-table is the stable anchor); scoping to
        # ".el-tab-pane:visible" could never match and hid the real failure.
        rows = page.locator("[data-test='case-table'] .el-table__row")
        expect(rows).to_have_count(1, timeout=15000)
        rows.first.locator(".el-checkbox").click()
        page.locator("[data-test='case-ask-ai']").click()
        assert_no_errors(collectors, "spectrum: case pinned into the dock")

        door = page.locator("[data-test='pinned-pending-groups']")
        expect(door).to_be_visible(timeout=15000)
        expect(page.locator("[data-test='chat-draft-group']")).to_have_count(1, timeout=15000)
        assert_no_errors(collectors, "spectrum: pending-groups door reaches the panel")

        # --- one-key accept: the group mounts fully ticked (one-key posture:
        # ChatDraftGroup starts every row checked, the human unticks to
        # reject). Clicking draft-group-all here would UNTICK the whole group
        # and disable the door (confirmBlocked = selectedIds 0) — the toggle
        # contract, not a defect. Prove the enabled door, then take the one
        # action the design allows: confirm.
        confirm = page.locator("[data-test='draft-group-confirm']")
        expect(confirm).to_be_enabled(timeout=10000)
        confirm.click()
        ttl = page.locator("[data-test='draft-group-ttl']")
        expect(ttl).to_be_visible(timeout=10000)
        assert_no_errors(collectors, "spectrum: one-shot ticket opened")
        page.locator("[data-test='draft-group-apply']").click()
        expect(page.locator("[data-test='draft-group-receipt']")).to_be_visible(timeout=20000)
        assert_no_errors(collectors, "spectrum: group applied, receipt shown")

        # --- server-side convergence: enumeration empty + impact consumed ---
        read = httpx.get(
            f"{API_BASE}/ai/chat/drafts/group/pending",
            params={"projectId": pid},
            headers=api_client.headers,
            timeout=30,
        )
        assert read.status_code == 200 and read.json().get("code") == "0", \
            f"pending enumeration read failed: {read.text[:200]}"
        assert read.json()["data"] in ([], {}, None) or not read.json()["data"], \
            f"pending door still lists groups after accept: {read.text[:300]}"
        consumed = _sql_json_one(
            f"SELECT to_json(status) FROM tp_linkage_impact WHERE id={impact_id}"
        )
        assert int(consumed) == 4, f"impact row not consumed (status={consumed})"
        consumed_at = sql_query(
            f"SELECT consumed_at IS NOT NULL FROM tp_linkage_impact WHERE id={impact_id}"
        )
        assert consumed_at == "t", "impact row consumed without a consumed_at stamp"
    finally:
        purge_project(api_client, pid)
