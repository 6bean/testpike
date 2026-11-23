"""Group draft door E2E .

One streamed answer carries three pending draft cards, so the page folds them
into ONE group door (ChatDraftGroup). The human still clicks twice: tick a
subset, one click requests a single group ticket, the second click applies
exactly the ticked rows in one call. The unchecked card keeps its own single
door after reload — the group never swallows it and never auto-applies
anything (red line 10c stays unimplemented on purpose).

Spectra (hard assertions, no conditional skips — iron law 5d):
 1. folding: 3 drafts on one message render exactly one group container with
 3 item rows, every row ticked by default and the header count at 3/3;
 2. de-selection: pulling one tick changes the count to 2/3 — the label on
 the apply button carries the number, so the human always sees how many
 rows one click will write;
 3. two-click group door: confirm -> ticket + countdown -> apply -> a
 line-by-line receipt (2 written with versions, 1 not written) naming the
 object of each line;
 4. the DB is the arbiter: exactly the two ticked proposals became real
 tp_test_case rows (status=2), the unchecked draft stays pending
 (status=1) and its single door is reachable after a full reload;
 5. the case list under the header project segment shows the two landed titles
 and not the untouched one.

Pinned pitfalls: a) source the chain env before running (load_chain_env.sh) —
every base comes from TP_E2E_* / slot env, never a hard-coded main-slot port;
b) drafts are journaled through the core frame door (land_draft_frame), no SQL
insert on the test side; c) data lives in a scratch project purged in finally
— the two demo projects are never touched; d) the group-ticket key space is
the messageId, the single-card ticket stays on draftId (L3 piece 2b proved the
two spaces do not interoperate).
"""
import json
import os

import httpx
import pytest
from conftest import (BASE_URL, _set_sys_config_value, assert_no_errors,
                      inject_token, land_draft_frame, pick_project_in_header,
                      sql_query)
from conftest import purge_project as _purge_project
from conftest import seed_project as _seed_project
from playwright.sync_api import expect
from test_ai_chat_page import _wait_streaming_message

pytestmark = [pytest.mark.timeout(300), pytest.mark.xdist_group("ai-chat-exec-switch")]

CORE_BASE = os.environ.get("TP_E2E_CORE_BASE", "http://localhost:8083")
TITLES = ["组门一键批量验收甲", "组门一键批量验收乙", "组门一键批量验收丙"]


def _create_body(title: str) -> str:
    return json.dumps({
        "title": title,
        "precondition": "存在有效测试账号",
        "expectedResult": f"{title}：登录后 3 秒内进入首页工作台",
        "priority": 2, "confidence": 3, "endType": "web",
        "steps": [{"stepNo": 1, "description": "打开登录页", "isAssert": 0},
                  {"stepNo": 2, "description": "输入账号密码并提交", "isAssert": 0},
                  {"stepNo": 3, "description": "首页工作台可见", "isAssert": 1}],
    }, ensure_ascii=False)


def test_chat_group_door_one_click_batch_accept_with_deselection(
        page, collectors, api_client, uniq):
    """3 cards -> one group door -> untick 1 -> two clicks -> 2 land, 1 stays."""
    pid = _seed_project(api_client, uniq, "GRPDOOR")
    pname = sql_query(f"SELECT name FROM tp_project WHERE id={pid}")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    _set_sys_config_value("ai.chat.case-draft.enabled", "true")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        # One live streamed turn (real orchestrator) = one assistant message
        # that will carry the whole group.
        page.locator('[data-test="chat-dock-input"]').fill(f"组门批量探针{uniq}")
        page.locator('[data-test="chat-dock-input"]').press("Enter")
        sid, msg_id = _wait_streaming_message(60, pid)

        # Park three create proposals onto the same answer through the core's
        # own frame door (the ledger grouping itself is under test).
        draft_ids = []
        for title in TITLES:
            meta = {"executionId": 0, "objectType": "CASE_WRITE",
                    "caseId": 0, "baseVersion": 0,
                    "toolName": "case.create", "model": "e2e-probe-model",
                    "actionJson": _create_body(title)}
            draft_ids.append(int(land_draft_frame(
                CORE_BASE, sid, msg_id, f"新建用例草案：{title}", meta)))
        assert len(set(draft_ids)) == 3, f"three distinct draft rows expected: {draft_ids}"

        # Spectrum 1: folding into ONE group container, default all-ticked.
        group = page.locator('[data-test="chat-draft-group"]')
        expect(group).to_have_count(1, timeout=30000)
        expect(group.locator('[data-test="draft-group-item"]')).to_have_count(3)
        expect(group.locator('[data-test="draft-group-count"]')).to_contain_text("3/3")

        # Spectrum 2: untick the third card — the count and the button label
        # must both re-follow the ticked subset.
        group.locator('[data-test="draft-group-pick"]').nth(2).click()
        expect(group.locator('[data-test="draft-group-count"]')).to_contain_text("2/3")

        # Spectrum 2.5: while the live turn is still streaming the door is
        # honest — the confirm stays disabled until the done frame binds the
        # message id (never a client-side guess). Wait for the seal web-first.
        expect(group.locator('[data-test="draft-group-confirm"]')) \
            .to_be_enabled(timeout=90000)

        # Click one: a single ticket over the ticked subset + countdown.
        group.locator('[data-test="draft-group-confirm"]').click()
        apply_btn = group.locator('[data-test="draft-group-apply"]')
        expect(apply_btn).to_be_visible(timeout=15000)
        expect(apply_btn).to_contain_text("2")
        expect(group.locator('[data-test="draft-group-ttl"]')).to_be_visible()

        # Click two: the whole subset writes in one call; the receipt answers
        # line by line (2 landed with a version, 1 untouched — never a bare ok).
        apply_btn.click()
        receipt = group.locator('[data-test="draft-group-receipt"]')
        expect(receipt.locator('[data-outcome="landed"]')).to_have_count(2, timeout=20000)
        # Every row of the group is answered — the untouched one is named too
        # (never silently dropped from the receipt).
        expect(receipt.locator('.group-receipt-line')).to_have_count(3)
        expect(group.locator('[data-test="draft-group-receipt-summary"]')) \
            .to_contain_text("2 项已写入，1 项未写入")

        # Spectrum 4: the DB is the arbiter — exactly the ticked rows landed.
        landed_titles = sql_query(
            "SELECT title FROM tp_test_case WHERE project_id=" + str(pid) +
            " ORDER BY title")
        assert sorted(landed_titles.splitlines()) == sorted(TITLES[:2]), landed_titles
        statuses = sql_query(
            "SELECT status FROM tp_ai_chat_draft WHERE id IN ("
            + ",".join(str(d) for d in draft_ids) + ") ORDER BY id")
        # third parked draft was unticked, so it must still be pending (1)
        assert statuses == "2\n2\n1", f"draft ledger {statuses} != 2/2/1"

        # Full reload: the group ledger persists, and the unticked card keeps
        # its own single door (the group did not swallow it).
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-draft-group"]')).to_be_visible(timeout=20000)
        single = page.locator('[data-test="chat-draft-card"]') \
            .filter(has_text=TITLES[2])
        expect(single).to_be_visible(timeout=15000)
        expect(single.locator('[data-test="chat-draft-confirm"]')).to_be_visible()

        # Spectrum 5: the case list under the header project segment shows the
        # two landed titles, not the untouched one.
        page.goto(f"{BASE_URL}/#/cases", wait_until="domcontentloaded")
        pick_project_in_header(page, pname)
        table = page.locator('[data-test="case-table"]')
        expect(table).to_be_visible(timeout=15000)
        expect(table).to_contain_text(TITLES[0], timeout=15000)
        expect(table).to_contain_text(TITLES[1], timeout=15000)
        assert TITLES[2] not in (table.inner_text() or ""), \
            "the unticked proposal must not have been written"

        assert_no_errors(collectors, "chat group door one-click batch accept")
    finally:
        _purge_project(api_client, pid)
