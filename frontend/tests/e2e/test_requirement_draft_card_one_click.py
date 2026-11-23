
import json
import os
import time

import httpx
import pytest
from conftest import (BASE_URL, assert_no_errors, inject_token,
                      land_draft_frame, pick_project_in_header, sql_query)
from playwright.sync_api import expect
from test_ai_chat_page import (_purge_project, _seed_project,
                      _wait_streaming_message, wire_chatstream_stub)

pytestmark = [pytest.mark.timeout(300), pytest.mark.xdist_group("ai-chat-exec-switch")]

CORE_BASE = os.environ.get("TP_E2E_CORE_BASE", "http://localhost:8083")
DEDUP_SWITCH = "ai.dedup.enabled"
# Stubs started per spectrum; a survivor would hold the slot stub port and
# poison the next spectrum, so each test reaps them in its finally.
_LIVE_STUBS: list = []


def _reap_stubs():
    for stub in _LIVE_STUBS:
        try:
            stub.terminate()
        except Exception:  # noqa: BLE001
            pass
    _LIVE_STUBS.clear()


def _config_row(api_client, key):
    import urllib.parse
    r = api_client.get("/configs?groupName=" + urllib.parse.quote("系统"))
    rows = (r.json().get("data") or []) if r.status_code == 200 else []
    return next((c for c in rows if c.get("configKey") == key), None)


def _set_dedup_switch(api_client, value):
    row = _config_row(api_client, DEDUP_SWITCH)
    assert row, f"{DEDUP_SWITCH} row not listed through GET /api/v1/configs (group=系统)"
    r = api_client.put(f"/configs/{row['id']}", json={**row, "configValue": str(value)})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:200]
    after = _config_row(api_client, DEDUP_SWITCH)
    assert after and after.get("configValue") == str(value), \
        f"dedup switch readback {after.get('configValue') if after else None} != {value}"


def _mk_requirement(api_client, pid, title):
    r = api_client.post(f"/projects/{pid}/requirements", json={
        "source": 3, "sourceId": f"SRC-{title}", "title": title,
        "acceptanceCriteria": f"验收标准：{title}",
    })
    assert r.status_code == 200, f"创建需求点失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"创建需求点业务失败: {body.get('msg')}"
    return body["data"]


def _open_dual_card(page, api_client, collectors, uniq, branch_tag):
    """Shared front half: real turn + frame-door proposal over an exact hit.

    Returns (pid, hit_id, hit_version, draft_id). The card surface is left
    showing the two-choice door; each spectrum drives its own branch.
    """
    pid = _seed_project(api_client, uniq, branch_tag)
    _LIVE_STUBS.append(wire_chatstream_stub(pid, f"卡片{branch_tag}{uniq}"))
    pname = sql_query(f"SELECT name FROM tp_project WHERE id={pid}")
    title = f"E2E需求二选{branch_tag}{uniq}"
    created = _mk_requirement(api_client, pid, title)
    assert isinstance(created, dict) and "id" in created, f"requirement row shape: {created}"
    hit_id = int(created["id"])
    hit_version = int(created["version"])

    inject_token(page)
    page.goto(f"{BASE_URL}/#/ai/chat", wait_until="domcontentloaded")
    pick_project_in_header(page, pname)
    expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
    inp = page.locator('[data-test="chat-dock-input"]')
    inp.fill(f"需求二选卡探针{branch_tag}{uniq}")
    inp.press("Enter")
    sid, msg_id = _wait_streaming_message(90, pid)

    meta = {
        "executionId": 950000 + (int(uniq[:6], 16) % 10000),
        "objectType": "REQUIREMENT_WRITE",
        "toolName": "requirement.write",
        "model": "e2e-probe-model",
        "actionJson": json.dumps({"title": title,
                                  "acceptanceCriteria": f"验收标准（草案）：{uniq}"},
                                 ensure_ascii=False),
        "summary": f"需求变更草案：{title}",
        "schemaVersion": "v2",
    }
    draft_id = land_draft_frame(CORE_BASE, sid, msg_id, f"需求草案：{title}", meta)

    # ---- the two-choice door is burned into the card -------------------------
    card = page.locator('[data-test="chat-draft-card"]')
    expect(card).to_be_visible(timeout=30000)
    update_btn = card.locator('[data-test="chat-draft-branch-update"]')
    create_btn = card.locator('[data-test="chat-draft-branch-create"]')
    expect(update_btn).to_be_visible(timeout=10000)
    expect(create_btn).to_be_visible(timeout=10000)
    assert str(hit_id) in update_btn.inner_text(), \
        f"update branch does not name the hit #{hit_id}: {update_btn.inner_text()}"
    expect(card.locator('[data-test="chat-draft-dual-diff"]')).to_be_visible(timeout=10000)
    expect(card.locator('[data-test="chat-draft-dual-hint"]')).to_be_visible(timeout=10000)
    # exactly two branch doors, no plain single confirm competing with them
    assert card.locator('[data-test="chat-draft-branch-switch"]').count() == 0, \
        "branch switch belongs to the ticketed stage, not the idle two-choice door"
    return pid, int(hit_id), hit_version, int(draft_id)


def _sweep(api_client, pid, draft_id):
    sql_query("DELETE FROM tp_ai_chat_draft WHERE id=%s" % draft_id)
    sql_query("DELETE FROM tp_requirement WHERE project_id=%s" % pid)
    _purge_project(api_client, pid)
    time.sleep(0.3)
    assert sql_query(f"SELECT count(*) FROM tp_project WHERE id={pid}") == "0", \
        "scratch project must not survive the probe"


def test_requirement_dual_card_create_branch_lands_force_new(
        page, collectors, api_client, uniq):
    pass
    original = (_config_row(api_client, DEDUP_SWITCH) or {}).get("configValue")
    pid = hit_id = draft_id = None
    try:
        _set_dedup_switch(api_client, "1")
        pid, hit_id, hit_version, draft_id = _open_dual_card(
            page, api_client, collectors, uniq, "新建")
        card = page.locator('[data-test="chat-draft-card"]')
        card.locator('[data-test="chat-draft-branch-create"]').click()
        apply_btn = card.locator('[data-test="chat-draft-apply"]')
        expect(apply_btn).to_be_visible(timeout=20000)
        apply_btn.click()
        receipt = card.locator('[data-test="chat-draft-receipt"]')
        expect(receipt).to_contain_text("已应用", timeout=25000)

        rows = json.loads(sql_query(
            "SELECT json_agg(row_to_json(t)) FROM (SELECT id, version, source "
            f"FROM tp_requirement WHERE project_id={pid} AND title LIKE 'E2E需求二选新建%%') t"))
        assert rows and len(rows) == 2, f"create branch must land beside the hit: {rows}"
        produced = next(r for r in rows if int(r["id"]) != hit_id)
        assert int(produced["version"]) == 1 and int(produced["source"]) == 3, \
            f"new row is not a fresh manual requirement: {produced}"
        hit_after = sql_query(f"SELECT version FROM tp_requirement WHERE id={hit_id}")
        assert int(hit_after) == hit_version, "the create branch must not touch the hit row"
        assert sql_query("SELECT count(*) FROM tp_ai_chat_draft WHERE id=%s AND status=2"
                         % draft_id) == "1", "ledger row must flip to applied"
        assert_no_errors(collectors, "requirement dual card create branch")
    finally:
        if original is not None:
            try:
                _set_dedup_switch(api_client, original)
            except Exception:
                pass
        _reap_stubs()
        if pid:
            _sweep(api_client, pid, draft_id)


def test_requirement_dual_card_update_branch_advances_the_anchor(
        page, collectors, api_client, uniq):
    pass
    original = (_config_row(api_client, DEDUP_SWITCH) or {}).get("configValue")
    pid = hit_id = draft_id = None
    try:
        _set_dedup_switch(api_client, "1")
        pid, hit_id, hit_version, draft_id = _open_dual_card(
            page, api_client, collectors, uniq, "变更")
        card = page.locator('[data-test="chat-draft-card"]')
        card.locator('[data-test="chat-draft-branch-update"]').click()
        apply_btn = card.locator('[data-test="chat-draft-apply"]')
        expect(apply_btn).to_be_visible(timeout=20000)
        apply_btn.click()
        receipt = card.locator('[data-test="chat-draft-receipt"]')
        expect(receipt).to_contain_text("已应用", timeout=25000)
        assert str(hit_id) in receipt.inner_text(), \
            f"receipt does not name the updated requirement: {receipt.inner_text()}"

        row_count = sql_query("SELECT count(*) FROM tp_requirement WHERE project_id=%s" % pid)
        assert int(row_count) == 1, f"update branch must not create a second row: {row_count}"
        version_after = sql_query(f"SELECT version FROM tp_requirement WHERE id={hit_id}")
        assert int(version_after) == hit_version + 1, \
            f"optimistic anchor did not advance: {hit_version} -> {version_after}"
        assert sql_query("SELECT count(*) FROM tp_requirement WHERE id=%s AND deleted=0"
                         % hit_id) == "1", "updated row must stay live"
        assert sql_query("SELECT count(*) FROM tp_ai_chat_draft WHERE id=%s AND status=2"
                         % draft_id) == "1", "ledger row must flip to applied"
        assert_no_errors(collectors, "requirement dual card update branch")
    finally:
        if original is not None:
            try:
                _set_dedup_switch(api_client, original)
            except Exception:
                pass
        _reap_stubs()
        if pid:
            _sweep(api_client, pid, draft_id)
