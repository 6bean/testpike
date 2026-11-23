
import json
import os

import pytest
from conftest import (BASE_URL, _set_sys_config_value, assert_no_errors,
                      inject_token, land_draft_frame, pick_project_in_header,
                      sql_query)
from conftest import purge_project as _purge_project
from conftest import seed_project as _seed_project
from playwright.sync_api import expect
from test_ai_chat_page import _wait_streaming_message

pytestmark = [pytest.mark.timeout(600), pytest.mark.xdist_group("ai-chat-exec-switch")]

CORE_BASE = os.environ.get("TP_E2E_CORE_BASE", "http://localhost:8083")
ROUNDS = 5


def test_chat_five_round_edit_one_accept_per_round(page, collectors, api_client, uniq):
    """5 rounds x (send -> streamed turn -> parked edit draft -> accept) = 5 accepts."""
    pid = _seed_project(api_client, uniq, "FVEDIT")
    pname = sql_query(f"SELECT name FROM tp_project WHERE id={pid}")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    _set_sys_config_value("ai.chat.case-draft.enabled", "true")
    case_title = f"五连改靶用例{uniq}"
    try:
        # The target object: one real case, v1, created through the API.
        created = api_client.post("/cases", json={
            "projectId": int(pid),
            "title": case_title,
            "precondition": "存在有效测试账号",
            "expectedResult": "基线预期",
            "priority": 2, "confidence": 3, "endType": "web",
            "status": 1,
            "steps": [{"stepNo": 1, "description": "打开登录页", "isAssert": 0},
                      {"stepNo": 2, "description": "首页工作台可见", "isAssert": 1}],
        })
        assert created.status_code == 200 and created.json().get("code") == "0", created.text[:200]
        data = created.json().get("data") or {}
        case_id = data["id"] if isinstance(data, dict) else data
        assert int(sql_query(
            f"SELECT version FROM tp_test_case WHERE id={case_id}")) == 1, "the target starts at v1"

        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        inp = page.locator('[data-test="chat-dock-input"]')

        accepts = 0
        for r in range(1, ROUNDS + 1):
            expected = f"五连改第{r}轮预期-{uniq}"
            if r == 1:
                text = f"把用例「{case_title}」的预期结果改为 {expected}"
            else:
                text = f"再把预期结果改为 {expected}"
                # Zero re-stating: only the first turn names the object.
                assert case_title not in text
            inp.fill(text)
            inp.press("Enter")
            sid, msg_id = _wait_streaming_message(90, pid)

            meta = {"executionId": 0, "objectType": "CASE_WRITE",
                    "caseId": int(case_id), "targetId": int(case_id),
                    "baseVersion": 0,  # V330 server-side anchor overwrites the stale echo
                    "toolName": "case.update", "model": "e2e-probe-model",
                    "schemaVersion": "v2",
                    "actionJson": json.dumps({"expectedResult": expected}, ensure_ascii=False)}
            draft_id = int(land_draft_frame(
                CORE_BASE, sid, msg_id, f"修改用例 #{case_id}：预期结果（第{r}轮）", meta))

            card = page.locator('[data-test="chat-draft-card"]').filter(has_text=f"第{r}轮")
            expect(card).to_be_visible(timeout=30000)
            # One-line summary, no wrapping dump, on every round's card.
            summary = card.locator('[data-test="chat-draft-summary"]').inner_text()
            assert "\n" not in summary.strip(), f"round {r} card summary is not one line: {summary}"

            # The door is honest while the turn streams: accept only after the
            # done frame seals the message (web-first, same as the group door).
            confirm = card.locator('[data-test="chat-draft-confirm"]')
            expect(confirm).to_be_enabled(timeout=90000)
            confirm.click()
            accepts += 1
            apply_btn = card.locator('[data-test="chat-draft-apply"]')
            expect(apply_btn).to_be_visible(timeout=15000)
            apply_btn.click()
            receipt = card.locator('[data-test="chat-draft-receipt"]')
            expect(receipt).to_be_visible(timeout=25000)
            assert "版" in receipt.inner_text(), \
                f"round {r} receipt lost the case-centric version line: {receipt.inner_text()}"

            # Ledger truth without any page refresh: base anchor re-read live
            # (round r lands against version r) and the row rolled to r+1.
            assert int(sql_query(
                f"SELECT base_version FROM tp_ai_chat_draft WHERE id={draft_id}")) == r, \
                f"round {r} anchor must be the server-side live version"
            assert int(sql_query(
                f"SELECT applied_version FROM tp_ai_chat_draft WHERE id={draft_id}")) == r + 1
            assert int(sql_query(
                f"SELECT version FROM tp_test_case WHERE id={case_id}")) == r + 1
            # The session keeps all r cards on the live DOM (never a reload).
            expect(page.locator('[data-test="chat-draft-card"]')).to_have_count(r)

        assert accepts == 5, "exactly five accept actions across five rounds"

        # Final values straight from the DB (still zero refresh so far).
        assert int(sql_query(
            f"SELECT version FROM tp_test_case WHERE id={case_id}")) == ROUNDS + 1
        final_row = json.loads(sql_query(
            f"SELECT row_to_json(t) FROM (SELECT expected_result, version "
            f"FROM tp_test_case WHERE id={case_id}) t"))
        assert final_row["expected_result"] == f"五连改第{ROUNDS}轮预期-{uniq}", final_row

        # Reload AFTER the run proves persistence (the rounds themselves
        # needed zero refreshes; the audit trail keeps every step).
        page.goto(f"{BASE_URL}/#/cases", wait_until="domcontentloaded")
        pick_project_in_header(page, pname)
        table = page.locator('[data-test="case-table"]')
        expect(table).to_be_visible(timeout=15000)
        expect(table).to_contain_text(case_title, timeout=15000)
        assert int(sql_query(
            f"SELECT version FROM tp_test_case WHERE id={case_id}")) == ROUNDS + 1, \
            "five rolled versions must survive the reload"

        assert_no_errors(collectors, "chat five-round edit spectrum")
    finally:
        sql_query("DELETE FROM tp_ai_chat_draft WHERE session_id IN "
                  "(SELECT id FROM tp_ai_chat_session WHERE project_id=%s)" % pid)
        _purge_project(api_client, pid)
