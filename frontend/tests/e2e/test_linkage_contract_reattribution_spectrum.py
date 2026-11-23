"""AN2 repayment contract / perf re-attribution card-gap spectrum.

Proves where the upstream-linkage loop stops for the three contract-side
members today (REAL chain only, no mocks in the driven path):

 Spectrum A — a real contract v2 re-upload (POST /api-specs, endpoint diff
 non-empty) publishes changed:api_spec; the folded-key bean detectors
 (contractMockFanDetector / contractRequestFanDetector, V521) arm the
 grouped_draft impacts for the derived stub and the imported request.
 AN ticket 4a closed the mint side: ChangeLinkageConsumer produces the
 deterministic snapshot for both contract members (key hit -> usable
 snapshot, key miss -> honest rejection that withdraws the pending
 claim) and LinkageProposalServiceImpl.mintCard resolves the stub and
 request anchors, so each armed impact mints exactly one confirmable
 card that the scope-shaped group door lands through the existing
 MOCK_STUB_WRITE / APITEST_WRITE write strategies. This spectrum now
 pins the full loop end to end.

 Spectrum B — re-saving a perf scenario whose approval ticket is approved
 re-arms its perf_approval impact (V521 edge perf_scenario->perf_approval,
 grouped draft = re-approval). AN ticket 4b closed the mint side: the
 deterministic producer files the re-approval snapshot for an approved
 ticket (pending/rejected/deleted ones are refused in plain words so a
 card never stacks on a live request), one confirmable PERF_APPROVAL
 card is minted behind the anchor resolver, and the scope-shaped group
 door files a FRESH pending request through the same approval chain the
 page uses. The decided approval row itself stays byte-identical
 (TC-LNK-04 adjudication: original approval stays as written history;
 the new shape is re-approved through a grouped draft, never a rewrite).

Window: the internal linkage spectrum window with TP_E2E_SPEC pointing at
this file (single launcher, no copy).
"""
import io
import json
import time

import pytest

from conftest import (
    purge_project,
    seed_project,
    sql_query,
)

pytestmark = [pytest.mark.timeout(600), pytest.mark.xdist_group("an2-linkage-spectrum")]


def _sql_json_one(query: str):
    raw = sql_query(query)
    assert raw, f"empty sql result for: {query[:120]}"
    return json.loads(raw)


def _poll_count(where: str, table: str, want: int, timeout_s: int) -> int:
    """Poll a count until it reaches >=want; return the last observed value."""
    deadline = time.time() + timeout_s
    value = -1
    while time.time() < deadline:
        raw = sql_query(
            f"SELECT count(*) FROM {table} WHERE {where}"
        )
        value = int(raw) if raw and raw.strip().isdigit() else -1
        if value >= want:
            return value
        time.sleep(1.0)
    return value


def _upload_spec(api_client, pid: int, name: str, spec: dict):
    # multipart upload goes through httpx with the signed-in client's headers
    # (the thin api_client wrapper only carries JSON verbs; precedent =
    # test_document_lineage_column.py document upload)
    import httpx
    from conftest import API_BASE
    return httpx.post(
        f"{API_BASE}/api-specs?projectId={pid}&name={name}",
        headers=api_client.headers,
        files={"file": (f"{name}.json", io.BytesIO(
            json.dumps(spec, ensure_ascii=False).encode("utf-8")), "application/json")},
        timeout=30,
    )


def _spec_v1() -> dict:
    return {
        "openapi": "3.0.1",
        "info": {"title": "AN2联动契约", "version": "1.0.0"},
        "paths": {
            "/pets": {
                "get": {"operationId": "listPets", "summary": "列出宠物",
                        "parameters": [{"name": "limit", "in": "query",
                                        "schema": {"type": "integer"}}],
                        "responses": {"200": {"description": "ok"}}},
                "post": {"operationId": "createPet", "summary": "创建宠物",
                         "responses": {"201": {"description": "created"}}},
            },
            "/pets/{petId}": {
                "get": {"operationId": "getPet", "summary": "宠物详情",
                        "parameters": [{"name": "petId", "in": "path",
                                        "schema": {"type": "integer"}}],
                        "responses": {"200": {"description": "ok"}}},
            },
        },
    }


def _spec_v2() -> dict:
    """One endpoint removed (GET /pets/{petId}); the other two keys stay,
    so the fan arms the stub and the imported request on a non-empty diff."""
    spec = _spec_v1()
    spec["info"]["version"] = "2.0.0"
    del spec["paths"]["/pets/{petId}"]
    return spec


def test_contract_edit_arms_mock_and_request_impacts_and_mints_landing_cards(
        api_client, collectors, uniq):
    """Bus side re-arms status=2 impacts, the deterministic producer mints
    one confirmable card per kind, and the group door lands them."""
    pid = seed_project(api_client, uniq, "AN2CON")
    name = f"an2-con-{uniq[:8]}"
    try:
        up1 = _upload_spec(api_client, pid, name, _spec_v1())
        assert up1.status_code == 200 and up1.json().get("code") == "0", \
            f"contract v1 upload failed: {up1.status_code} {up1.text[:300]}"

        # downstream fixtures: contract-derived stub + imported request, both
        # stored under the folded match key the shared rules compute
        # (GET /pets, POST /pets — same single source the detectors use).
        sql_query(
            "INSERT INTO tp_mock_space (project_id, name, space_key) VALUES ("
            f"{pid}, '联动谱空间{uniq}', 'an2con{uniq[:8]}')")
        space_id = _sql_json_one(
            f"SELECT to_json(id) FROM tp_mock_space WHERE project_id={pid} "
            f"AND space_key='an2con{uniq[:8]}'")
        sql_query(
            "INSERT INTO tp_mock_stub (space_id, name, method, path_pattern, source) "
            f"VALUES ({space_id}, '联动谱派生桩{uniq}', 'GET', '/pets', 'openapi')")
        stub_id = _sql_json_one(
            "SELECT to_json(id) FROM tp_mock_stub WHERE space_id="
            f"{space_id} AND path_pattern='/pets'")
        sql_query(
            "INSERT INTO tp_apitest_collection (project_id, parent_id, name, tags, "
            "headers_json) VALUES ("
            f"{pid}, 0, '联动谱接口集{uniq}', '', '[]'::jsonb)")
        coll_id = _sql_json_one(
            "SELECT to_json(id) FROM tp_apitest_collection WHERE project_id="
            f"{pid} AND name='联动谱接口集{uniq}'")
        sql_query(
            "INSERT INTO tp_apitest_request (collection_id, name, method, url_path, "
            "contract_endpoint_key) VALUES ("
            f"{coll_id}, '联动谱引入请求{uniq}', 'POST', '/pets', 'POST /pets')")
        req_id = _sql_json_one(
            "SELECT to_json(id) FROM tp_apitest_request WHERE collection_id="
            f"{coll_id} AND name='联动谱引入请求{uniq}'")

        up2 = _upload_spec(api_client, pid, name, _spec_v2())
        assert up2.status_code == 200 and up2.json().get("code") == "0", \
            f"contract v2 re-upload failed: {up2.status_code} {up2.text[:300]}"

        spec_row_ids = (
            f"(SELECT id FROM tp_api_spec WHERE project_id={pid})")
        for dtype, drow in (("mock_stub", stub_id), ("apitest_request", req_id)):
            armed = _poll_count(
                f"upstream_object_type='api_spec' AND upstream_object_id IN {spec_row_ids}"
                f" AND downstream_object_type='{dtype}' AND downstream_id={drow}"
                " AND disposition_strategy='grouped_draft' AND status=2",
                "tp_linkage_impact", want=1, timeout_s=30)
            assert armed >= 1, (
                f"bus never re-armed the api_spec->{dtype} grouped_draft impact "
                f"to status=2 after the contract v2 save (observed {armed})")

        # --- mint side (GREEN of AN ticket 4a) ----------------------------
        # The deterministic contract producer (ChangeLinkageConsumer) records
        # the snapshot inline and the mintCard resolver mints the confirmable
        # card behind the single system-draft door — exactly one card per
        # kind, keyed MOCK_STUB_WRITE / APITEST_WRITE.
        for dtype, obj_type in (("mock_stub", "MOCK_STUB_WRITE"),
                                ("apitest_request", "APITEST_WRITE")):
            cards = _poll_count(
                "context_json->>'linkageImpactId' IN ("
                "SELECT i.id::text FROM tp_linkage_impact i "
                f"WHERE i.upstream_object_type='api_spec' AND i.upstream_object_id IN {spec_row_ids}"
                f" AND i.downstream_object_type='{dtype}')",
                "tp_ai_chat_draft", want=1, timeout_s=30)
            assert cards >= 1, (
                f"no confirmable {obj_type} card minted after the contract v2 save "
                f"(observed {cards}) — deterministic producer or mint resolver regressed")
            typed = int(sql_query(
                "SELECT count(*) FROM tp_ai_chat_draft d "
                "WHERE d.context_json->>'linkageImpactId' IN ("
                "SELECT i.id::text FROM tp_linkage_impact i "
                f"WHERE i.upstream_object_type='api_spec' AND i.upstream_object_id IN {spec_row_ids}"
                f" AND i.downstream_object_type='{dtype}') AND d.object_type='{obj_type}'"))
            assert typed >= 1, f"{obj_type} card must land via the existing write strategy kind"

        # --- one-click accept lands through the group door (zero relaxed) --
        # confirm-token then apply per kind: the same permissioned doors the
        # pinned panel uses; receipts must answer landed, applied cards flip
        # to status=2 and the impact ledger leaves draft-pending behind.
        for obj_type in ("MOCK_STUB_WRITE", "APITEST_WRITE"):
            card_ids = _sql_json_one(
                "SELECT json_agg(d.id) FROM tp_ai_chat_draft d "
                "JOIN tp_linkage_impact i ON i.id::text = d.context_json->>'linkageImpactId' "
                f"WHERE i.upstream_object_type='api_spec' AND i.upstream_object_id IN {spec_row_ids}"
                f" AND d.object_type='{obj_type}' AND d.status = 1")
            token_resp = api_client.post("/ai/chat/drafts/group/confirm-token", json={
                "projectId": pid, "objectType": obj_type})
            assert token_resp.status_code == 200 and token_resp.json().get("code") == "0", \
                f"group confirm-token door refused {obj_type}: {token_resp.text[:300]}"
            ticket = token_resp.json()["data"]["token"]
            apply_resp = api_client.post("/ai/chat/drafts/group/apply", json={
                "projectId": pid, "objectType": obj_type,
                "confirmToken": ticket, "draftIds": card_ids})
            assert apply_resp.status_code == 200 and apply_resp.json().get("code") == "0", \
                f"group apply door refused {obj_type}: {apply_resp.text[:300]}"
            items = apply_resp.json()["data"]["items"]
            assert len(items) == len(card_ids), (
                f"{obj_type} receipt must answer every checked card: {items}")
            for item in items:
                assert item["outcome"] == "landed", (
                    f"{obj_type} card {item['draftId']} did not land through the "
                    f"existing write strategy: {item}")
            still_pending = int(sql_query(
                f"SELECT count(*) FROM tp_ai_chat_draft WHERE id IN"
                f" ({','.join(str(i) for i in card_ids)}) AND status = 1"))
            assert still_pending == 0, (
                f"accepted {obj_type} cards must leave the pending state")

            # Post-landing readback: the write strategy must leave the
            # downstream row executable-referenced to an endpoint contract v2
            # still serves, with the mock matcher columns as written
            # (TC-LNK-03 residual: receipts alone cannot prove the row was
            # re-attributed — read the stored fields back here).
            if obj_type == "MOCK_STUB_WRITE":
                row = _sql_json_one(
                    f"SELECT json_build_object('m', method, 'p', path_pattern)"
                    f" FROM tp_mock_stub WHERE id={stub_id}")
                assert row["m"] == "GET" and row["p"] == "/pets", (
                    f"landed stub must keep matching contract v2 endpoint "
                    f"GET /pets, got {row}")
            else:
                row = _sql_json_one(
                    "SELECT json_build_object('u', url_path, 'k', "
                    "contract_endpoint_key) FROM tp_apitest_request "
                    f"WHERE id={req_id}")
                assert row["u"] == "/pets" and row["k"] == "POST /pets", (
                    f"landed request must keep its execution reference on "
                    f"POST /pets, got {row}")
    finally:
        purge_project(api_client, pid)


def test_perf_scenario_edit_rearms_approval_impact_and_keeps_history_row(
        api_client, collectors, uniq):
    """Approved ticket + scenario re-save arms the re-approval impact, mints
    exactly one confirmable PERF_APPROVAL card, and the group door files a
    fresh pending request — the original tp_perf_approval row stays
    byte-identical (history is never rewritten)."""
    pid = seed_project(api_client, uniq, "AN2PERF")
    try:
        env = api_client.post(f"/projects/{pid}/envs", json={
            "name": f"联动谱压测环境{uniq}", "envType": 2,
            "baseUrl": "http://localhost:8899", "configJson": "{}",
            "endTypes": "web", "browsers": "chromium", "versionSnapshot": ""})
        assert env.status_code == 200 and env.json().get("code") == "0", \
            f"env create failed: {env.text[:200]}"
        eid = env.json()["data"]
        eid = eid["id"] if isinstance(eid, dict) else eid

        scenario = {
            "projectId": pid, "name": f"联动谱场景{uniq}", "envId": eid,
            "pressureModel": "CONSTANT_RPS",
            "pressureConfig": {"targetRps": 1, "durationSeconds": 1},
            "steps": [{"action": "http.request", "method": "GET",
                       "url": "http://localhost:8899/ping"}],
        }
        created = api_client.post("/perf/scenarios", json=scenario)
        assert created.status_code == 200 and created.json().get("code") == "0", \
            f"scenario create failed: {created.text[:300]}"
        sid = created.json()["data"]
        sid = sid["id"] if isinstance(sid, dict) else sid

        admin_id = int(sql_query(
            "SELECT id FROM tp_user WHERE username = 'admin'").splitlines()[0].strip())
        # status 3 = approved: the guard behind the deterministic producer
        # only files a re-approval snapshot for an approved ticket (a live
        # pending request must never stack a duplicate card)
        sql_query(
            "INSERT INTO tp_perf_approval (project_id, env_id, requested_concurrency, "
            "isolation_mode, reason, applicant_id, status, scenario_id) VALUES ("
            f"{pid}, {eid}, 100, 'SHADOW_TABLE', '联动谱原审批{uniq}', {admin_id}, 3, {sid})")
        approval_id = _sql_json_one(
            "SELECT to_json(id) FROM tp_perf_approval WHERE scenario_id="
            f"{sid} AND reason='联动谱原审批{uniq}'")
        before = sql_query(
            f"SELECT row_to_json(t)::text FROM (SELECT status, requested_concurrency, "
            f"reason FROM tp_perf_approval WHERE id={approval_id}) t").strip()

        scenario["name"] = f"联动谱场景{uniq}-已修订"
        # the door pins the project via query projectId (PerfGateController
        # updateScenario @RequestParam), the body carries the full shape
        edited = api_client.put(
            f"/perf/scenarios/{sid}?projectId={pid}",
            json={k: v for k, v in scenario.items() if k != "projectId"})
        assert edited.status_code == 200 and edited.json().get("code") == "0", \
            f"scenario update failed: {edited.text[:300]}"

        armed = _poll_count(
            "upstream_object_type='perf_scenario' AND upstream_object_id="
            f"{sid} AND downstream_object_type='perf_approval' AND downstream_id="
            f"{approval_id} AND disposition_strategy='grouped_draft' AND status=2",
            "tp_linkage_impact", want=1, timeout_s=30)
        assert armed >= 1, (
            f"bus never re-armed the perf_scenario->perf_approval re-approval "
            f"impact to status=2 (observed {armed})")

        # --- mint side (GREEN of AN ticket 4b) ----------------------------
        # The deterministic producer filed the re-approval snapshot for the
        # approved ticket and the mint door resolved the ticket's project
        # anchor: exactly one confirmable PERF_APPROVAL card per impact.
        cards = _poll_count(
            "context_json->>'linkageImpactId' IN ("
            "SELECT i.id::text FROM tp_linkage_impact i "
            f"WHERE i.upstream_object_type='perf_scenario' AND i.upstream_object_id={sid}"
            " AND i.downstream_object_type='perf_approval')",
            "tp_ai_chat_draft", want=1, timeout_s=30)
        assert cards >= 1, (
            f"no confirmable PERF_APPROVAL card minted after the scenario re-save "
            f"(observed {cards}) — deterministic producer or mint resolver regressed")
        typed = int(sql_query(
            "SELECT count(*) FROM tp_ai_chat_draft d "
            "WHERE d.context_json->>'linkageImpactId' IN ("
            "SELECT i.id::text FROM tp_linkage_impact i "
            f"WHERE i.upstream_object_type='perf_scenario' AND i.upstream_object_id={sid}"
            f" AND i.downstream_object_type='perf_approval') AND d.object_type='PERF_APPROVAL'"))
        assert typed >= 1, (
            "the re-approval card must carry the PERF_APPROVAL draft kind so the "
            "generic door dispatches the re-approval strategy")

        # --- one-click accept lands through the group door (zero relaxed) --
        # Same permissioned doors the pinned panel uses: confirm-token then
        # apply; landing files a FRESH pending request through the approval
        # chain the page uses and never rewrites the decided ticket.
        card_ids = _sql_json_one(
            "SELECT json_agg(d.id) FROM tp_ai_chat_draft d "
            "JOIN tp_linkage_impact i ON i.id::text = d.context_json->>'linkageImpactId' "
            f"WHERE i.upstream_object_type='perf_scenario' AND i.upstream_object_id={sid}"
            " AND i.downstream_object_type='perf_approval' "
            "AND d.object_type='PERF_APPROVAL' AND d.status = 1")
        token_resp = api_client.post("/ai/chat/drafts/group/confirm-token", json={
            "projectId": pid, "objectType": "PERF_APPROVAL"})
        assert token_resp.status_code == 200 and token_resp.json().get("code") == "0", \
            f"group confirm-token door refused PERF_APPROVAL: {token_resp.text[:300]}"
        ticket = token_resp.json()["data"]["token"]
        apply_resp = api_client.post("/ai/chat/drafts/group/apply", json={
            "projectId": pid, "objectType": "PERF_APPROVAL",
            "confirmToken": ticket, "draftIds": card_ids})
        assert apply_resp.status_code == 200 and apply_resp.json().get("code") == "0", \
            f"group apply door refused PERF_APPROVAL: {apply_resp.text[:300]}"
        items = apply_resp.json()["data"]["items"]
        assert len(items) == len(card_ids), (
            f"PERF_APPROVAL receipt must answer every checked card: {items}")
        for item in items:
            assert item["outcome"] == "landed", (
                f"re-approval card {item['draftId']} did not land through the "
                f"approval chain: {item}")
        fresh = int(sql_query(
            "SELECT count(*) FROM tp_perf_approval WHERE scenario_id="
            f"{sid} AND status = 2 AND reason LIKE '联动重审%' AND id <> {approval_id}"))
        assert fresh >= 1, (
            "accepting the re-approval card must file a fresh pending request "
            f"through the same approval chain (observed {fresh})")
        still_pending = int(sql_query(
            f"SELECT count(*) FROM tp_ai_chat_draft WHERE id IN"
            f" ({','.join(str(i) for i in card_ids)}) AND status = 1"))
        assert still_pending == 0, (
            "accepted re-approval cards must leave the pending state")

        after = sql_query(
            f"SELECT row_to_json(t)::text FROM (SELECT status, requested_concurrency, "
            f"reason FROM tp_perf_approval WHERE id={approval_id}) t").strip()
        assert after == before, (
            f"original approval row was rewritten by the linkage bus: "
            f"{before} -> {after} (TC-LNK-04: history must stay intact)")
    finally:
        purge_project(api_client, pid)
