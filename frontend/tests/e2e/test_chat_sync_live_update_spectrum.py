""" close spectrum: landed dialog drafts converge the live business views.

Five shapes ride the real orchestrator (one streamed turn each) plus the
frame-door draft ledger (land_draft_frame, the single source for parking a
proposal — no SQL insert on the test side):
 1. case list, same tab — a landed CASE_WRITE create shows in the table;
 2. case detail (display mode) — a landed edit swaps the shown field;
 3. case detail (edit mode, dirty) — the untouched field takes the landed
 value and advances the version baseline, the user's dirty title
 survives, and saving afterwards succeeds without 40901;
 4. requirement list in ANOTHER tab — BroadcastChannel convergence: the
 new row appears with zero reload and zero prompt on the other tab;
 5. tag list, same tab — a landed TAG_WRITE create shows in the table;
 6. tag-write frame from a NON-MEMBER caller — the single ownership door
 refuses with 40301 before any ticket is issued and the write side stays
 empty (refusal spectrum of the filed gap, no UI involvement).

Silence is part of the claim (iron rule 2 of the ruling): no toast, no
reload, no page jump anywhere in the landing/re-read chain. Each page under
observation carries two probes installed before the first navigation: a
MutationObserver counting every .el-message mount (a toast that only lives
two seconds is still counted) and a window marker set after load that any
reload would wipe.
"""
import json
import os

import httpx
import pytest
from conftest import (API_BASE, BASE_URL, TESTER_PASSWORD,
                      _set_sys_config_value, assert_no_errors, api_login,
                      inject_token, land_draft_frame, mk_tester,
                      pick_project_in_header, sql_query)
from conftest import purge_project as _purge_project
from conftest import seed_project as _seed_project
from playwright.sync_api import expect
from test_ai_chat_page import _wait_streaming_message
# The case create-body shape rides the proven group-door single source (R40).
from test_chat_group_door_one_click_batch_accept import _create_body

pytestmark = [pytest.mark.timeout(300), pytest.mark.xdist_group("ai-chat-exec-switch")]

CORE_BASE = os.environ.get("TP_E2E_CORE_BASE", "http://localhost:8083")

# Count every toast mount from frame zero; a 2s flash still lands in the tally.
_PROBE_INIT = """
window.__tpToasts = 0;
new MutationObserver((records) => {
  for (const rec of records) {
    for (const node of rec.addedNodes) {
      if (node.nodeType === 1 && node.classList && node.classList.contains('el-message')) {
        window.__tpToasts += 1;
      }
    }
  }
}).observe(document.documentElement, { childList: true, subtree: true });
"""


def _arm_probes(page):
    page.add_init_script(_PROBE_INIT)


def _mark(page):
    """Set the reload witness AFTER the document exists (a reload wipes it)."""
    page.evaluate("() => { window.__tpMark = 'bg-sync' }")


def _assert_quiet(page, where):
    assert page.evaluate("() => window.__tpMark") == "bg-sync", \
        f"{where}: the view reloaded instead of converging silently"
    assert int(page.evaluate("() => window.__tpToasts")) == 0, \
        f"{where}: the landing/re-read chain surfaced a toast"


def _open_dock(page):
    expect(page.locator('[data-test="ai-chat-fab"]')).to_be_visible(timeout=15000)
    page.locator('[data-test="ai-chat-fab"]').click()
    inp = page.locator('[data-test="chat-dock-input"]')
    expect(inp).to_be_visible(timeout=10000)
    return inp


def _close_dock(page):
    """Dismiss the floating dock (Esc = keyboard parity of the close button) and wait
 for its panel to unmount before any further physical page click. Root cause
 : while the dock is open, the outside-click interceptor swallows the
 first real mouse click that lands on the page behind it (native listener tally
 0, no console output, DB row unchanged)."""
    page.keyboard.press("Escape")
    expect(page.locator('[data-test="ai-chat-dock"]')).not_to_be_visible(timeout=10000)


def _stream_turn(page, pid, tag, chat_page=False):
    """One real streamed turn; returns (sid, msg_id) of the sealed answer."""
    if chat_page:
        inp = page.locator('[data-test="chat-dock-input"]')
        expect(inp).to_be_visible(timeout=10000)
    else:
        inp = _open_dock(page)
    inp.fill(f"落地即时同步探针{tag}")
    inp.press("Enter")
    return _wait_streaming_message(90, pid)


def _accept_single_door(page):
    """confirm ticket -> apply -> receipt on the one visible draft card."""
    card = page.locator('[data-test="chat-draft-card"]')
    expect(card).to_be_visible(timeout=30000)
    confirm = card.locator('[data-test="chat-draft-confirm"]')
    expect(confirm).to_be_enabled(timeout=90000)
    confirm.click()
    apply_btn = card.locator('[data-test="chat-draft-apply"]')
    expect(apply_btn).to_be_visible(timeout=15000)
    apply_btn.click()
    expect(card.locator('[data-test="chat-draft-receipt"]')).to_be_visible(timeout=25000)


def _seed_case(api_client, pid, title, expected="基线预期"):
    created = api_client.post("/cases", json={
        "projectId": int(pid), "title": title,
        "precondition": "存在有效测试账号", "expectedResult": expected,
        "priority": 2, "confidence": 3, "endType": "web", "status": 1,
        "steps": [{"stepNo": 1, "description": "打开登录页", "isAssert": 0},
                  {"stepNo": 2, "description": "首页工作台可见", "isAssert": 1}],
    })
    assert created.status_code == 200 and created.json().get("code") == "0", created.text[:200]
    data = created.json().get("data") or {}
    return data["id"] if isinstance(data, dict) else data


def _park(meta, core_base, sid, msg_id, shown):
    return int(land_draft_frame(core_base, sid, msg_id, shown, meta))


def _sweep(api_client, pid):
    sql_query("DELETE FROM tp_ai_chat_draft WHERE session_id IN "
              "(SELECT id FROM tp_ai_chat_session WHERE project_id=%s)" % pid)
    _purge_project(api_client, pid)


def test_case_list_converges_silently_on_landed_create(page, collectors, api_client, uniq):
    """Spectrum 1: CASE_WRITE create -> the open list shows the row, zero reload, zero toast."""
    pid = _seed_project(api_client, uniq, "CSYNCLIST")
    pname = sql_query(f"SELECT name FROM tp_project WHERE id={pid}")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    _set_sys_config_value("ai.chat.case-draft.enabled", "true")
    _arm_probes(page)
    title = f"同步列表靶用例{uniq}"
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/cases", wait_until="domcontentloaded")
        pick_project_in_header(page, pname)
        table = page.locator('[data-test="case-table"]')
        expect(table).to_be_visible(timeout=15000)
        _mark(page)

        sid, msg_id = _stream_turn(page, pid, f"列表{uniq}")
        _park({"executionId": 0, "objectType": "CASE_WRITE", "caseId": 0,
               "baseVersion": 0, "toolName": "case.create", "model": "e2e-probe-model",
               "schemaVersion": "v2", "actionJson": _create_body(title)},
              CORE_BASE, sid, msg_id, f"新建用例草案：{title}")
        _accept_single_door(page)

        expect(table).to_contain_text(title, timeout=15000)
        _assert_quiet(page, "case list same-tab convergence")
        assert_no_errors(collectors, "chat sync case-list spectrum")
    finally:
        _sweep(api_client, pid)


def test_case_detail_display_field_swaps_on_landed_edit(page, collectors, api_client, uniq):
    """Spectrum 2: landed edit on the open detail view swaps the shown field, no reload."""
    pid = _seed_project(api_client, uniq, "CSYNCDET")
    pname = sql_query(f"SELECT name FROM tp_project WHERE id={pid}")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    _set_sys_config_value("ai.chat.case-draft.enabled", "true")
    _arm_probes(page)
    case_title = f"同步详情靶用例{uniq}"
    expected = f"落地换值预期-{uniq}"
    try:
        case_id = _seed_case(api_client, pid, case_title)
        inject_token(page)
        page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
        pick_project_in_header(page, pname)
        block = page.locator('[data-test="detail-expected"]')
        expect(block).to_be_visible(timeout=15000)
        _mark(page)

        sid, msg_id = _stream_turn(page, pid, f"详情{uniq}")
        _park({"executionId": 0, "objectType": "CASE_WRITE",
               "caseId": int(case_id), "targetId": int(case_id), "baseVersion": 0,
               "toolName": "case.update", "model": "e2e-probe-model", "schemaVersion": "v2",
               "actionJson": json.dumps({"expectedResult": expected}, ensure_ascii=False)},
              CORE_BASE, sid, msg_id, f"修改用例 #{case_id}：预期结果")
        _accept_single_door(page)

        expect(block).to_contain_text(expected, timeout=15000)
        _assert_quiet(page, "case detail display-state convergence")
        assert_no_errors(collectors, "chat sync detail-display spectrum")
    finally:
        _sweep(api_client, pid)


def test_detail_edit_state_keeps_dirty_and_saves_without_conflict(page, collectors, api_client, uniq):
    """Spectrum 3 (iron rule 3): landed edit while in edit mode keeps the dirty field,
    takes the landed value in the untouched field, and the later save rides the new
    baseline (no 40901 false conflict)."""
    pid = _seed_project(api_client, uniq, "CSYNCDIRTY")
    pname = sql_query(f"SELECT name FROM tp_project WHERE id={pid}")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    _set_sys_config_value("ai.chat.case-draft.enabled", "true")
    _arm_probes(page)
    case_title = f"脏字段靶用例{uniq}"
    dirty_title = f"脏字段手改保留{uniq}"
    expected = f"落地换值预期-脏{uniq}"
    try:
        case_id = _seed_case(api_client, pid, case_title)
        inject_token(page)
        page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
        pick_project_in_header(page, pname)
        expect(page.locator('[data-test="detail-edit"]')).to_be_visible(timeout=15000)
        _mark(page)
        page.locator('[data-test="detail-edit"]').click()
        title_inp = page.locator('[data-test="detail-edit-title"]')
        expect(title_inp).to_be_visible(timeout=10000)
        title_inp.fill(dirty_title)  # the one field the human is mid-edit on

        sid, msg_id = _stream_turn(page, pid, f"脏态{uniq}")
        _park({"executionId": 0, "objectType": "CASE_WRITE",
               "caseId": int(case_id), "targetId": int(case_id), "baseVersion": 0,
               "toolName": "case.update", "model": "e2e-probe-model", "schemaVersion": "v2",
               "actionJson": json.dumps({"expectedResult": expected}, ensure_ascii=False)},
              CORE_BASE, sid, msg_id, f"修改用例 #{case_id}：预期结果（编辑态）")
        _accept_single_door(page)

        # The dirty input survives; the untouched field silently takes the new value.
        expect(title_inp).to_have_value(dirty_title, timeout=5000)
        expect(page.locator('[data-test="detail-edit-expected"]')).to_have_value(
            expected, timeout=15000)
        _assert_quiet(page, "case detail edit-state convergence")

        # Saving on the advanced baseline must not surface the 40901 conflict.
        # The dock is still open from the draft door; dismiss it first so this
        # physical click is not eaten by its outside-click interceptor .
        _close_dock(page)
        page.locator('[data-test="detail-save-draft"]').click()
        deadline_ms = 20000
        landed = False
        while deadline_ms > 0:
            row = json.loads(sql_query(
                "SELECT row_to_json(t) FROM (SELECT title, expected_result "
                f"FROM tp_test_case WHERE id={case_id}) t"))
            if row["title"] == dirty_title and row["expected_result"] == expected:
                landed = True
                break
            page.wait_for_timeout(1000)
            deadline_ms -= 1000
        assert landed, "the merged save never reached the row (dirty kept + landed value)"
        assert int(sql_query(f"SELECT version FROM tp_test_case WHERE id={case_id}")) >= 3, \
            "land update + merged save must each roll the version chain"
        assert_no_errors(collectors, "chat sync detail-dirty spectrum")
    finally:
        _sweep(api_client, pid)


def test_requirement_list_other_tab_converges_without_reload(page, collectors, api_client, uniq):
    """Spectrum 4: the landing tab is a chat page; the requirement tab stays open
    beside it and picks the new row up through the same-origin channel — zero
    reload (window marker) and zero prompt (.el-message tally)."""
    pid = _seed_project(api_client, uniq, "CSYNCTAB")
    pname = sql_query(f"SELECT name FROM tp_project WHERE id={pid}")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    _set_sys_config_value("ai.chat.requirement-draft.enabled", "true")
    _arm_probes(page)
    title = f"跨标签同步需求{uniq}"
    tab2 = None
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/requirements", wait_until="domcontentloaded")
        pick_project_in_header(page, pname)
        table = page.locator('[data-test="req-table"]')
        expect(table).to_be_visible(timeout=15000)
        _mark(page)

        tab2 = page.context.new_page()
        tab2.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(tab2.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        sid, msg_id = _stream_turn(tab2, pid, f"跨标签{uniq}", chat_page=True)
        _park({"executionId": 0, "objectType": "REQUIREMENT_WRITE",
               "toolName": "requirement.write", "model": "e2e-probe-model", "schemaVersion": "v2",
               "actionJson": json.dumps({"title": title}, ensure_ascii=False)},
              CORE_BASE, sid, msg_id, f"新建需求草案：{title}")
        _accept_single_door(tab2)

        expect(table).to_contain_text(title, timeout=15000)
        _assert_quiet(page, "requirement list cross-tab convergence")
        assert_no_errors(collectors, "chat sync cross-tab spectrum")
    finally:
        if tab2 is not None:
            tab2.close()
        _sweep(api_client, pid)


def test_tag_list_converges_silently_on_landed_create(page, collectors, api_client, uniq):
    """Spectrum 5: TAG_WRITE create -> the open tag dictionary shows the row silently."""
    pid = _seed_project(api_client, uniq, "CSYNCTAG")
    pname = sql_query(f"SELECT name FROM tp_project WHERE id={pid}")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    _arm_probes(page)
    name = f"同步标签探针{uniq}"
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/tags", wait_until="domcontentloaded")
        pick_project_in_header(page, pname)
        table = page.locator('[data-test="tag-table"]')
        expect(table).to_be_visible(timeout=15000)
        _mark(page)

        sid, msg_id = _stream_turn(page, pid, f"标签{uniq}")
        _park({"executionId": 0, "objectType": "TAG_WRITE",
               "toolName": "tag.create", "model": "e2e-probe-model", "schemaVersion": "v2",
               "actionJson": json.dumps({"name": name}, ensure_ascii=False)},
              CORE_BASE, sid, msg_id, f"新建标签草案：{name}")
        _accept_single_door(page)

        expect(table).to_contain_text(name, timeout=15000)
        _assert_quiet(page, "tag list same-tab convergence")
        assert_no_errors(collectors, "chat sync tag-list spectrum")
    finally:
        _sweep(api_client, pid)


def test_tag_write_frame_from_non_member_is_refused(page, collectors, api_client, uniq):
    pass
    pid = _seed_project(api_client, uniq, "CSYNCTAGX")
    pname = sql_query(f"SELECT name FROM tp_project WHERE id={pid}")
    tester_id, _ = mk_tester(api_client, uniq, prefix="synctagx")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    name = f"越权标签探针{uniq}"
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/tags", wait_until="domcontentloaded")
        pick_project_in_header(page, pname)

        sid, msg_id = _stream_turn(page, pid, f"越权{uniq}")
        draft_id = _park({"executionId": 0, "objectType": "TAG_WRITE",
                          "toolName": "tag.create", "model": "e2e-probe-model", "schemaVersion": "v2",
                          "actionJson": json.dumps({"name": name}, ensure_ascii=False)},
                         CORE_BASE, sid, msg_id, f"新建标签草案：{name}")

        stranger = api_login(f"synctagxtester_{uniq}", TESTER_PASSWORD)
        refused = httpx.post(f"{API_BASE}/ai/chat/drafts/{draft_id}/confirm-token",
                             headers={"Authorization": f"Bearer {stranger}"},
                             timeout=15).json()
        assert str(refused.get("code")) == "40301", \
            f"non-member confirm must hit the ownership door: {refused}"
        assert int(sql_query(
            f"SELECT count(*) FROM tp_ai_chat_draft WHERE id={draft_id} AND status=1")) == 1, \
            "the refusal must leave the draft untouched in pending (no ticket, no burn)"
        assert int(sql_query(
            "SELECT count(*) FROM tp_tag_dict WHERE name='" + name + "'")) == 0, \
            "the refused frame must not leave any tag row behind"
        assert_no_errors(collectors, "chat sync tag-write cross-owner refusal")
    finally:
        _sweep(api_client, pid)
        api_client.delete(f"/users/{tester_id}")
