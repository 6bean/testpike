
import re

import httpx
import pytest
from conftest import (
    API_BASE,
    BASE_URL,
    assert_no_errors,
    inject_token,
    pick_project_in_header,
    sql_query,
)
from playwright.sync_api import expect
from test_ai_chat_page import (
    E2E_CASE_CREATE_BODY,
    _purge_project,
    _seed_project,
    _set_sys_config_value,
    _wait_streaming_message,
)

pytestmark = [pytest.mark.timeout(300), pytest.mark.xdist_group("ai-chat-exec-switch")]

CORE_BASE = "http://localhost:8083"


def _assembly_names(pid: int) -> set:
    """Names in the project FC assembly candidate set (core read door, same
    posture as the internal five-way tool spectrum Spectrum A)."""
    r = httpx.get(f"{CORE_BASE}/internal/tools/project/{pid}/assembly", timeout=15)
    assert r.status_code == 200, f"assembly door down: {r.status_code} {r.text[:200]}"
    return {i["name"] for i in (r.json().get("data") or [])}


def test_header_project_slice_lists_system_level_tools_with_tag(page, collectors, api_client, uniq):
    pass
    pid = _seed_project(api_client, uniq, "ARTOOL")
    pname = sql_query(f"SELECT name FROM tp_project WHERE id={pid}")
    expected = int(sql_query(
        "SELECT count(*) FROM tp_tool WHERE project_id IS NULL AND status=3"))
    assert expected >= 12, f"system-level seed account drifted: {expected} published rows"
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/tools", wait_until="domcontentloaded")
        pick_project_in_header(page, pname)
        table = page.locator('[data-test="tool-table"]')
        expect(table).to_be_visible(timeout=15000)
        # Zero-binding project segment must surface system-level rows tagged.
        tags = table.locator(".el-tag:has-text('系统级')")
        assert tags.count() >= 1, (
            f"system-level rows must surface tagged on the first page, "
            f"got {tags.count()}")
        # Calibration 2026-11-04: the seed publishes 48 system-level tools
        # while the management list pages at 20 rows (updated_at desc), so a
        # named tool can sit on a later page. Reach it through the name
        # filter — the intended worst-scale path — instead of asserting
        # first-page text.
        page.locator('[data-test="tool-keyword"]').fill("case.create")
        page.locator('[data-test="tool-search"]').click()
        expect(table).to_contain_text("case.create", timeout=15000)
        expect(table.locator(".el-tag:has-text('系统级')")).not_to_have_count(0)
        assert_no_errors(collectors, "AR system-level tool list on project slice")
    finally:
        _purge_project(api_client, pid)


def test_header_project_slice_lists_official_skill_templates(page, collectors, api_client, uniq):
    """Spectrum 2: the SKILL page lists the three official system-level
 templates on a zero-binding project segment, tagged as system-level."""
    pid = _seed_project(api_client, uniq, "ARSKILL")
    pname = sql_query(f"SELECT name FROM tp_project WHERE id={pid}")
    expected = int(sql_query(
        "SELECT count(*) FROM tp_skill WHERE project_id IS NULL"))
    assert expected >= 3, f"V51 official templates missing from tp_skill: {expected}"
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/skills", wait_until="domcontentloaded")
        pick_project_in_header(page, pname)
        table = page.locator('[data-test="skill-table"]')
        expect(table).to_be_visible(timeout=15000)
        tags = table.locator(".el-tag:has-text('系统级')")
        assert tags.count() >= 3, (
            f"the three official templates must be visible on any project slice, "
            f"got {tags.count()} tagged rows")
        assert_no_errors(collectors, "AR system-level skill list on project slice")
    finally:
        _purge_project(api_client, pid)


def test_zero_binding_project_assembles_then_optout_drops_one(page, collectors, api_client, uniq):
    """Spectra 3+4: the assembly door carries all 12 system-level tools with
    ZERO binding rows (the R34 default pass-through), an enabled=0 override row
    drops exactly case.update, and the dialog still serves the case.create
    draft card on that project (visible end-to-end proof of the non-empty
    assembly on the human-facing side)."""
    pid = _seed_project(api_client, uniq, "ARASM")
    pname = sql_query(f"SELECT name FROM tp_project WHERE id={pid}")
    assert sql_query(
        f"SELECT count(*) FROM tp_project_tool WHERE project_id={pid}") == "0", \
        "a fresh project must reach the assembly with zero binding rows"
    upd_id = sql_query(
        "SELECT id FROM tp_tool WHERE project_id IS NULL AND status=3 "
        "AND name='case.update' LIMIT 1")
    assert upd_id, "system-level case.update must exist (genkit seed account)"
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    _set_sys_config_value("ai.chat.case-draft.enabled", "true")
    try:
        # Spectrum 3: default pass-through — full system bucket in the candidate
        # set, five-way gates untouched (published gate already filters here).
        names = _assembly_names(pid)
        assert "case.create" in names and "case.update" in names, \
            f"system-level tools must reach assembly without bindings, got {sorted(names)}"
        sys_published = int(sql_query(
            "SELECT count(*) FROM tp_tool WHERE project_id IS NULL AND status=3"))
        assert len(names) >= sys_published, (
            f"assembly must carry every published system row "
            f"({sys_published}), got {len(names)}")

        # Spectrum 4: opt-out is the ONLY extra step — pin case.update disabled.
        r = httpx.put(f"{API_BASE}/projects/{pid}/tools",
                      json={"tools": [{"toolId": int(upd_id), "enabled": 0}],
                            "expectedVersion": None},
                      headers=api_client.headers, timeout=15)
        assert r.status_code == 200 and r.json().get("code") == "0", \
            f"override row save failed: {r.text[:200]}"
        after = _assembly_names(pid)
        assert "case.update" not in after, "enabled=0 override must drop the row from assembly"
        assert "case.create" in after, "the override must not poison sibling rows"

        # Human-facing proof: the dialog on this same project streams and the
        # case.create draft card lands (deterministic card posture from the
        # precedent — the assembly non-emptiness is pinned server-side
        # above, the card proves the write door is reachable from the page).
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat", wait_until="domcontentloaded")
        pick_project_in_header(page, pname)
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        question = f"系统级装配探针{uniq}"
        inp = page.locator('[data-test="chat-dock-input"]')
        inp.fill(question)
        inp.press("Enter")
        sid, msg_id = _wait_streaming_message(90, pid)
        import json as _json
        meta = _json.dumps({"executionId": 930000 + (int(uniq[:6], 16) % 10000),
                            "objectType": "CASE_WRITE", "caseId": 0, "baseVersion": 0,
                            "toolName": "case.create", "model": "e2e-probe-model",
                            "actionJson": E2E_CASE_CREATE_BODY}, ensure_ascii=False)
        landed = httpx.post(f"{CORE_BASE}/internal/ai-chat/sessions/{sid}/frames",
                            json={"messageId": msg_id, "frameType": "draft",
                                  "payload": "系统级默认可见草案：登录冒烟用例",
                                  "metaJson": meta}, timeout=30).json()
        assert landed.get("code") == "0", landed
        card = page.locator('[data-test="chat-draft-card"]')
        expect(card).to_be_visible(timeout=30000)
        expect(card.locator('[data-test="chat-draft-summary"]')).to_contain_text(
            "登录冒烟用例", timeout=10000)
        assert_no_errors(collectors, "AR assembly pass-through and opt-out override")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        sql_query(f"DELETE FROM tp_project_tool WHERE project_id={pid}; "
                  f"DELETE FROM tp_ai_chat_draft WHERE session_id IN "
                  f"(SELECT id FROM tp_ai_chat_session WHERE project_id={pid});")
        _purge_project(api_client, pid)
