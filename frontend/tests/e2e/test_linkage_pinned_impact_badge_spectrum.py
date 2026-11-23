"""AN2 acceptance spectrum: pinned-impact badge full lifecycle.

Proves the badge contract on the REAL chain (admin save door -> linkage bus
-> impact ledger -> proposal relay -> system draft -> pinned panel -> group
door -> one-key accept -> server-owned re-read), no mocks in the driven path:

 1. seed project + tester + one real case + a downstream script row, then
 PUT the case so the bus annotates a pending grouped_draft impact.
 2. relay one trustworthy SCRIPT_REPAIR proposal: the draft card enters the
 same group door the assistant flow uses .
 3. a REAL browser signs in, pins the case, and the row grows the
 pending-impact badge (count owned by GET /linkage-impacts/pending).
 4. clicking the badge reveals the ONE group-card door area (the design
 forbids a second disposal surface, so exactly one chat-draft-group and
 one confirm control exist on the page).
 5. one-key accept (confirm -> one-shot ticket -> apply -> receipt) makes
 the badge disappear through the answered-event re-read, and the badge
 door stays empty on a direct HTTP re-read (server holds the number;
 the client never patches counts).
 6. ledger closure: impact row reaches status=4 with consumed_at stamped.

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

pytestmark = [pytest.mark.timeout(900), pytest.mark.xdist_group("an2-badge-spectrum")]

PASSWORD = "Test@12345"


def _sql_json_one(query: str):
    raw = sql_query(query)
    assert raw, f"empty sql result for: {query[:120]}"
    return json.loads(raw)


def _poll_json(where: str, table: str, cols: str, timeout_s: int):
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


def test_pinned_impact_badge_lights_reveals_door_and_zeroes_after_accept(
    page, collectors, api_client, uniq
):
    pid = seed_project(api_client, uniq, "AN2BADGE")
    tester_id, _ = mk_tester(api_client, uniq)
    add_member(api_client, pid, tester_id)
    case_id = _seed_case(api_client, pid, f"AN2角标谱用例{uniq[:6]}")
    # Case list applies the member self-predicate (created_by OR assignee_id,
    # CaseFilters), so assign the case to the tester before the browser leg;
    # assignment never publishes changed:case and annotates no extra impact.
    assigned = api_client.post(f"/cases/{case_id}/assign", json={"assigneeId": tester_id})
    assert assigned.status_code == 200 and assigned.json().get("code") == "0", \
        f"assign case to tester failed: {assigned.text[:200]}"
    try:
        # --- downstream fixture: the script row the repair chain targets ---
        sql_query(
            "INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by, version) "
            f"VALUES ({case_id}, 'v2', "
            f"'{{\"caseId\":{case_id},\"setup\":[],\"steps\":[{{\"no\":1,\"action\":\"navigate\",\"target\":\"/login\"}}],\"env\":{{}}}}'::jsonb, "
            "1, 2, 1)"
        )
        script_id = _sql_json_one(f"SELECT to_json(id) FROM tp_script WHERE case_id={case_id}")

        # --- upstream save fans the case->script edge into a pending row ---
        got = api_client.get(f"/cases/{case_id}")
        assert got.json().get("code") == "0", f"case detail failed: {got.text[:200]}"
        payload = got.json()["data"]
        payload["title"] = f"AN2角标谱用例{uniq[:6]}-已修订"
        put = api_client.put(f"/cases/{case_id}", json=payload)
        assert put.status_code == 200 and put.json().get("code") == "0", \
            f"case PUT failed: {put.text[:300]}"
        impact = _poll_json(
            "upstream_object_type='case' AND upstream_object_id=" + str(case_id)
            + " AND downstream_object_type='script' AND disposition_strategy='grouped_draft'",
            "tp_linkage_impact", "id, downstream_id, status", timeout_s=20,
        )
        assert impact is not None, "linkage bus never annotated the case->script impact"
        assert int(impact["downstream_id"]) == int(script_id)
        impact_id = int(impact["id"])

        # --- proposal relay -> system draft enters the shared group door ---
        repaired = {
            "caseId": int(case_id),
            "setup": [],
            "steps": [
                {"no": 1, "action": "navigate", "target": "/login"},
                {"no": 2, "action": "click", "target": "登录按钮（角标谱修复步）"},
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
                "modelName": "e2e-badge-relay",
                "baseScriptVersion": 1,
                "rejectReason": None,
            },
            timeout=30,
        )
        assert relay.status_code == 200 and relay.json().get("code") == "0", \
            f"proposal relay failed: {relay.status_code} {relay.text[:300]}"
        draft = _poll_json(
            f"object_type='SCRIPT_REPAIR' AND target_id={case_id}",
            "tp_ai_chat_draft", "id, status", timeout_s=15,
        )
        assert draft is not None, "no SCRIPT_REPAIR system draft after the relay"

        # --- real browser: pin the case; the row grows the impact badge ---
        inject_user_token(page, f"ttester_{uniq}", PASSWORD)
        page.goto(f"{BASE_URL}/#/cases?projectId={pid}")
        rows = page.locator("[data-test='case-table'] .el-table__row")
        expect(rows).to_have_count(1, timeout=15000)
        rows.first.locator(".el-checkbox").click()
        page.locator("[data-test='case-ask-ai']").click()
        assert_no_errors(collectors, "badge spectrum: case pinned into the dock")

        badge = page.locator("[data-test='pinned-impact-badge']")
        expect(badge).to_have_count(1, timeout=15000)
        expect(badge).to_contain_text("1", timeout=5000)
        assert_no_errors(collectors, "badge spectrum: pending impact badge lit")

        # --- clicking the badge reveals the ONE group-card door area ------
        badge.click()
        door = page.locator("[data-test='pinned-pending-groups']")
        expect(door).to_be_visible(timeout=15000)
        # Single disposal surface (red line): the badge must not mint a
        # second acceptance flow, so exactly one group and one confirm.
        expect(page.locator("[data-test='chat-draft-group']")).to_have_count(1, timeout=15000)
        expect(page.locator("[data-test='draft-group-confirm']")).to_have_count(1)
        assert_no_errors(collectors, "badge spectrum: badge click reveals the shared door")

        # --- one-key accept: the group mounts fully ticked; take the door --
        confirm = page.locator("[data-test='draft-group-confirm']")
        expect(confirm).to_be_enabled(timeout=10000)
        confirm.click()
        expect(page.locator("[data-test='draft-group-ttl']")).to_be_visible(timeout=10000)
        page.locator("[data-test='draft-group-apply']").click()
        expect(page.locator("[data-test='draft-group-receipt']")).to_be_visible(timeout=20000)
        assert_no_errors(collectors, "badge spectrum: group accepted through the door")

        # --- badge zeroes through the answered-event re-read (server-owned)
        expect(badge).to_have_count(0, timeout=15000)
        assert_no_errors(collectors, "badge spectrum: badge zeroed after disposal")

        # --- HTTP re-read of the very door the badge polls: stays empty ---
        read = httpx.get(
            f"{API_BASE}/linkage-impacts/pending",
            params={"upstreamType": "case", "upstreamId": case_id, "projectId": pid},
            headers=api_client.headers,
            timeout=30,
        )
        assert read.status_code == 200 and read.json().get("code") == "0", \
            f"pending badge door read failed: {read.text[:200]}"
        remaining = (read.json().get("data") or {}).get("impacts") or []
        assert remaining == [], f"badge door still lists pending rows: {read.text[:300]}"

        # --- ledger closure: consumed with a stamp ------------------------
        consumed = _sql_json_one(f"SELECT to_json(status) FROM tp_linkage_impact WHERE id={impact_id}")
        assert int(consumed) == 4, f"impact row not consumed (status={consumed})"
        consumed_at = sql_query(
            f"SELECT consumed_at IS NOT NULL FROM tp_linkage_impact WHERE id={impact_id}"
        )
        assert consumed_at == "t", "impact row consumed without a consumed_at stamp"
    finally:
        purge_project(api_client, pid)
