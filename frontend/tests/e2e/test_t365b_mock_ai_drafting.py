# -*- coding: utf-8 -*-
"""First E2E for the AI mock-drafting surface .

Real chain, zero mocks on the business path: the filed job rides
admin -> core gate -> MQ -> ai-service -> live LLM provider -> preview ->
tick-confirm drawer -> tp_mock_stub with provenance source=nl-ai. The L3
script (the internal mock-draft L3 chain) already live-verified the provider;
if the model ever produced nothing the job seals failed = honest terminal
truth, but this journey asserts the preview door because the chain is real.

Assertion doors:
1. closed master gate -> filing refused in plain words naming ai.mock.enabled,
 zero job rows created (the refusal is designed product behaviour; the
 self-caused 403 sites are stripped exactly like the nl-step wizard
 precedent, everything else still fails the run);
2. empty prompt stops locally with the plain-word hint — no server round-trip;
3. open gates -> file through the dialog -> jobs drawer shows the card with
 the pinned creation-time estimate line (nothing burns before submit);
4. job walks queued -> preview on the real consumer chain (DB-polled truth);
5. drafts drawer lists the model's items, tick + confirm lands stubs with
 source=nl-ai, docs cards show the AI-generated badge;
6. reload keeps truth (DB rows survive) and the worst-scale fixture (30 extra
 rules) shows the collapsed-group + instant-search display-scale law.

Fixture project/space are dedicated (never the demo/sample ones) and swept in
finally; gates are restored to factory closed.
"""
import os
import time
import uuid

import pytest
from playwright.sync_api import expect

pytestmark = [pytest.mark.timeout(600), pytest.mark.xdist_group("mock-plane-live")]

from conftest import BASE_URL, assert_no_errors, sql_query

SPACES_URL = f"{BASE_URL}/#/mock/spaces"


def _wb(sid, pid, tab="docs"):
    """ AN-4: the docs screen rides the space workbench tab."""
    return f"{SPACES_URL}/{sid}?projectId={pid}&tab={tab}"
MASTER_KEY = "ai.mock.enabled"
SUB_KEY = "ai.mock.nl-stub"
PROMPT = (
    "起草订单服务 mock 桩：GET /orders/{id} 返回订单详情（字段 orderId、amount、status）；"
    "POST /orders 创建订单返回 201（字段 orderId）。只起草这两个端点。"
    "规则不绑定状态场景（scenarioName 一律留空），响应体用静态 JSON。"
)
# a bare skeleton machine — enough for the core save gate's scenario existence
# check when the model binds a scenario name anyway (non-deterministic output)
SCENARIO_SKELETON = ('{"states":["idle","done"],"initial":"idle","scope":"session",'
                     '"transitions":[{"from":"idle","to":"done"}]}')


def _mk_project(api, name, code):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"project create failed: {r.text[:300]}"
    return r.json()["data"]


def _mk_space(api, pid, name, key):
    r = api.post("/mock/spaces", json={"projectId": pid, "name": name, "spaceKey": key})
    assert r.status_code == 200 and r.json().get("code") == "0", f"space create failed: {r.text[:300]}"
    return r.json()["data"]


def _configs(api):
    rows = api.get("/configs").json().get("data") or []
    return {c["configKey"]: c for c in rows if c["configKey"] in (MASTER_KEY, SUB_KEY)}


def _set_gate(api, cfg, value):
    """Flip a K8 switch through the real admin config chain (hot reload, no restart)."""
    r = api.put(f"/configs/{cfg['id']}", json={
        "configKey": cfg["configKey"], "configValue": value,
        "groupName": cfg.get("groupName") or "AI 配置",
        "valueType": cfg.get("valueType") or "bool",
        "encryptFlag": cfg.get("encryptFlag", 0),
        "description": cfg.get("description") or ""})
    assert r.status_code == 200, f"gate flip failed: {r.status_code} {r.text[:200]}"


def _strip_expected_gate403(collectors):
    """Drop only this test's own closed-gate refusal sites (designed behaviour);
    the rest of the 4xx/console zero-tolerance gates stay armed."""
    collectors["network_4xx"] = [
        e for e in collectors["network_4xx"]
        if not ("/api/v1/mock/jobs" in e["url"] and e["status"] == 403)]
    collectors["console_errors"] = [
        c for c in collectors["console_errors"] if "status of 403" not in c]


def _job_status(job_id):
    return sql_query(f"SELECT status FROM tp_mock_ai_job WHERE id = {job_id}")


def test_t365b_ai_drafting_gate_refuse_file_confirm_persist(logged_in, api_client, collectors):
    """Closed-gate refusal -> empty-prompt local stop -> file -> preview on the
    real chain -> tick-confirm -> nl-ai stub persists across reload at worst scale."""
    page = logged_in
    uniq = uuid.uuid4().hex[:8]
    pid = _mk_project(api_client, f"T365b AI {uniq}", f"t365b{uniq}")
    space_key = f"t365b{uniq}".lower()
    space_id = _mk_space(api_client, pid, f"t365b-space-{uniq}", space_key)
    job_id = None
    try:
        cfgs = _configs(api_client)
        assert MASTER_KEY in cfgs and SUB_KEY in cfgs, "ai.mock gate keys missing from the config plane"

        page.goto(_wb(space_id, pid), wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-docs-page"]')).to_be_visible(timeout=15000)
        # AN-4: embedded tab contract — the page-level space combo is gone
        expect(page.locator('[data-test="mock-docs-space-select"]')).to_have_count(0)

        # ---- door 1: closed master gate refuses in plain words, zero rows ----
        page.locator('[data-test="mock-docs-ai-draft"]').click()
        dialog = page.locator('[data-test="mock-ai-dialog"]')
        expect(dialog).to_be_visible(timeout=5000)
        dialog.locator('[data-test="mock-ai-f-prompt"]').fill(PROMPT)

        # ---- door 2 (before opening): empty prompt stops locally, no request ----
        dialog.locator('[data-test="mock-ai-f-prompt"]').fill("")
        page.locator('[data-test="mock-ai-file"]').click()
        expect(page.get_by_text("请先填写需求描述")).to_be_visible(timeout=3000)
        rows_before = int(sql_query(f"SELECT count(*) FROM tp_mock_ai_job WHERE space_id = {space_id}"))
        dialog.locator('[data-test="mock-ai-f-prompt"]').fill(PROMPT)

        page.locator('[data-test="mock-ai-file"]').click()
        # Name the expected toast (strict mode: under -n load an earlier
        # error toast may still be fading out when this one appears).
        refuse = page.locator(".el-message--error").filter(has_text=MASTER_KEY)
        expect(refuse).to_be_visible(timeout=8000)
        assert int(sql_query(
            f"SELECT count(*) FROM tp_mock_ai_job WHERE space_id = {space_id}")) == rows_before, \
            "closed gate created a job row"
        _strip_expected_gate403(collectors)

        # ---- open both gates through the real config chain ----
        _set_gate(api_client, cfgs[MASTER_KEY], "1")
        _set_gate(api_client, cfgs[SUB_KEY], "1")

        # ---- door 3: file on the open gate -> drawer card + pinned estimate ----
        page.locator('[data-test="mock-ai-file"]').click()
        drawer = page.locator('[data-test="mock-ai-jobs-drawer"]')
        expect(page.locator(".el-message--success")).to_contain_text("已提交", timeout=8000)
        card = drawer.locator('[data-test="mock-ai-job-card"]').first
        expect(card).to_contain_text("排队中", timeout=8000)
        card.locator('[data-test="mock-ai-estimate-line"]').wait_for(timeout=8000)
        job_id = int(sql_query(
            f"SELECT id FROM tp_mock_ai_job WHERE space_id = {space_id} ORDER BY id DESC LIMIT 1"))

        # ---- door 4: the real consumer chain seals the job to preview ----
        deadline = time.time() + 240
        status = ""
        while time.time() < deadline:
            status = _job_status(job_id)
            if status in ("preview", "failed", "cancelled", "consumed"):
                break
            time.sleep(3)
        assert status == "preview", \
            f"job {job_id} ended at status={status} (live provider chain did not seal preview)"

        # ---- door 5: drafts drawer -> tick -> confirm -> nl-ai lands ----
        # Self-heal the fixture against non-deterministic model output: read the
        # drafted items through the real API and create any scenario they bind,
        # so the core save gate's scenario-existence check has honest truth.
        drafts = api_client.get(f"/mock/jobs/{job_id}/drafts").json().get("data") or []
        for d in drafts:
            scn = (d.get("scenarioName") or "").strip()
            if scn:
                r = api_client.post(f"/mock/spaces/{space_id}/scenarios",
                                    json={"name": scn, "definitionJson": SCENARIO_SKELETON})
                assert r.status_code == 200, f"scenario pre-create failed: {r.text[:150]}"
        page.keyboard.press("Escape")  # close the still-open drawer first
        page.locator('[data-test="mock-docs-ai-jobs"]').click()  # reload the job list
        card = drawer.locator('[data-test="mock-ai-job-card"]').first
        expect(card).to_contain_text("待勾选确认", timeout=10000)
        card.get_by_role("button", name="查看草稿").click()
        draft_drawer = page.locator('[data-test="mock-ai-drawer"]')
        expect(draft_drawer).to_be_visible(timeout=5000)
        draft_card = draft_drawer.locator('[data-test="mock-ai-draft-card"]').first
        expect(draft_card).to_be_visible(timeout=10000)
        # instant keyword search over the drafted window (SE law: one search reaches)
        draft_drawer.locator('[data-test="mock-ai-draft-search"]').fill("orders")
        expect(draft_drawer.locator('[data-test="mock-ai-draft-card"]')).not_to_have_count(0, timeout=5000)
        draft_card = draft_drawer.locator('[data-test="mock-ai-draft-card"]').first
        draft_card.locator(".el-checkbox").first.click()
        expect(page.locator('[data-test="mock-ai-selected-count"]')).to_contain_text("1", timeout=3000)
        draft_drawer.locator('[data-test="mock-ai-drawer-confirm"]').click()
        expect(page.locator(".el-message--success").last).to_contain_text("已入库", timeout=15000)
        expect(draft_drawer).to_be_hidden(timeout=10000)

        # docs cards show the AI-generated provenance badge (small space = auto-expanded)
        src_tag = page.locator('[data-test="mock-docs-source-tag"]').first
        expect(src_tag).to_contain_text("AI 生成", timeout=10000)
        # provenance is real data, not a sticker: the DB row carries nl-ai + job ref
        rows = sql_query(
            f"SELECT source, coalesce(source_ref,'') FROM tp_mock_stub "
            f"WHERE space_id = {space_id} AND source = 'nl-ai'")
        assert rows.startswith("nl-ai|job:") and str(job_id) in rows, \
            f"confirmed stub lost nl-ai provenance: {rows}"

        # ---- door 6: reload keeps truth + worst-scale display law ----
        for i in range(30):
            r = api_client.post(f"/mock/spaces/{space_id}/stubs", json={
                "name": f"bulk-{uniq}-{i}", "method": "GET",
                "pathMatchType": "exact", "pathPattern": f"/bulk/r{i%6}/item{i}",
                "responseJson": '{"status":200,"body":{"ok":true}}'})
            assert r.status_code == 200 and r.json().get("code") == "0", f"bulk stub {i}: {r.text[:150]}"

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-docs-page"]')).to_be_visible(timeout=15000)
        rows = sql_query(
            f"SELECT source, coalesce(source_ref,'') FROM tp_mock_stub "
            f"WHERE space_id = {space_id} AND source = 'nl-ai'")
        assert rows.startswith("nl-ai|job:") and str(job_id) in rows, \
            f"confirmed stub lost nl-ai provenance: {rows}"

        # worst scale: groups default collapsed (no flat card wall at 32 rules);
        # 1 search + 1 group click reaches the drafted rule (two-acts-one-search law)
        groups = page.locator('[data-test="mock-docs-stub-group"]')
        expect(groups).not_to_have_count(0, timeout=10000)
        expect(page.locator('[data-test="mock-docs-card"]')).to_have_count(0, timeout=5000)
        page.locator('[data-test="mock-docs-stub-search"]').fill("orders")
        page.locator('[data-test="mock-docs-stub-group-head-orders"]').click()
        visible = page.locator('[data-test="mock-docs-card"]')
        expect(visible.first).to_be_visible(timeout=5000)
        assert "orders" in (visible.first.text_content() or "").lower()
        expect(visible.first.locator('[data-test="mock-docs-source-tag"]')).to_contain_text("AI 生成")

        assert_no_errors(collectors, "t365b AI drafting journey")
    finally:
        # restore factory gates and sweep the dedicated fixture (jobs->stubs->
        # space->project), demo/sample projects never touched
        try:
            cfgs = _configs(api_client)
            for key in (MASTER_KEY, SUB_KEY):
                if key in cfgs and cfgs[key]["configValue"] != "0":
                    _set_gate(api_client, cfgs[key], "0")
        except Exception as e:
            print(f"!! gate restore failed (manual check): {e}")
        try:
            stmt = (f"DELETE FROM tp_mock_ai_job WHERE space_id = {space_id}; "
                    f"DELETE FROM tp_mock_stub WHERE space_id = {space_id}; "
                    f"DELETE FROM tp_mock_scenario WHERE space_id = {space_id}; "
                    f"DELETE FROM tp_mock_space WHERE id = {space_id}; "
                    f"DELETE FROM tp_user_project WHERE project_id = {pid}; "
                    f"DELETE FROM tp_project WHERE id = {pid};")
            sql_query(stmt)
        except Exception as e:
            print(f"!! fixture sweep failed (manual check): {e}")
