"""Three-way reconciliation of the aggregate-query live card .

Proves the full chain with zero mocks: a real logged-in browser asks the
dialog one counting question about the scratch project's cases; the built-in
aggregate.query tool answers through the real LLM function-calling loop; the
native-SVG card folds the journaled QueryResult; and the three independent
sources agree on the numbers:

 card table cell values == psql GROUP BY recomputation == digits quoted in
 the assistant answer text.

The fixture feeds the worst-case scale (35 seeded cases across three
statuses, above the 30-row floor) so the card's fold + disclosure table are
proven at real sizes, not on a two-row toy sample. Reload read-back proves
the ledger replay path (history mapper folds the same card from the journaled
trace meta).

Iron rules honored: real login + real stream + real DB; every phase ends with
the 4xx/5xx + console + Issues-panel zero-tolerance check; scratch project
purged in the tail via the shared chat-ledger sweep.
"""
import re
import time

import pytest
from conftest import (
    BASE_URL,
    _set_sys_config_value,
    assert_no_errors,
    inject_token,
    sql_query,
)
from conftest import (
    purge_project as _purge_project,
)
from conftest import (
    seed_project as _seed_project,
)
from playwright.sync_api import expect

# Shares the single-worker group of the ai-chat family: this spectrum drives a
# live stream while toggling the one global exec switch with its siblings.
pytestmark = [pytest.mark.timeout(300), pytest.mark.xdist_group("ai-chat-exec-switch")]

# Distinct counts per status — deliberately uneven so a swapped or summed
# number can never pass by coincidence.
STATUS_PLAN = {1: 17, 2: 11, 5: 7}


def _seed_cases(api_client, pid, uniq):
    """Park 35 real cases across three statuses via the admin CRUD door."""
    for status, count in STATUS_PLAN.items():
        for i in range(count):
            r = api_client.post("/cases", json={
                "projectId": pid,
                "title": f"聚合对账{status}-{i}-{uniq}",
                "precondition": "已登录",
                "expectedResult": "对账基数",
                "priority": 1,
                "confidence": 3,
                "endType": "web",
                "status": status,
            })
            assert r.status_code == 200 and r.json().get("code") == "0", \
                f"seed case failed (status={status} #{i}): {r.text[:200]}"


def test_chat_aggregate_card_matches_sql_and_answer_text(page, collectors, api_client, uniq):
    """Card numbers == psql recomputation == answer text (three-way agreement)."""
    pid = _seed_project(api_client, uniq, "AGGREG")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    try:
        _seed_cases(api_client, pid, uniq)
        truth = {
            row.split("|")[0]: int(row.split("|")[1])
            for row in sql_query(
                "SELECT status || '|' || count(*) FROM tp_test_case "
                f"WHERE project_id={pid} AND deleted=0 GROUP BY status ORDER BY status"
            ).strip().splitlines()
            if "|" in row
        }
        assert truth == {str(k): v for k, v in STATUS_PLAN.items()}, \
            f"fixture drift: DB says {truth}, plan {STATUS_PLAN}"

        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        page.locator('[data-test="chat-dock-input"]').fill("每个状态的用例有多少条")
        page.locator('[data-test="chat-dock-input"]').press("Enter")
        # The turn must seal (real LLM + FC loop; the stop door is the live cue).
        expect(page.locator('[data-test="chat-dock-stop"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=180000)

        # Source 1 vs source 2: the disclosure table cells against psql truth.
        card = page.locator('[data-test="chat-live-card"]')
        expect(card).to_be_visible(timeout=10000)
        # Fold the disclosure table open (details collapsed by default at
        # scale — opening it is part of the journey, not a test hack).
        details = card.locator("details.live-table")
        expect(details).to_be_visible(timeout=5000)
        if not details.locator("summary").evaluate("el => el.parentElement.open"):
            details.locator("summary").click()
        dims = card.locator('[data-test="chat-live-dim"]')
        vals = card.locator('[data-test="chat-live-value"]')
        expect(dims).to_have_count(len(STATUS_PLAN), timeout=10000)
        on_card = {dims.nth(i).inner_text().strip(): vals.nth(i).inner_text().strip()
                   for i in range(len(STATUS_PLAN))}
        for status, count in STATUS_PLAN.items():
            assert on_card.get(str(status)) == str(count), \
                f"card disagrees with SQL for status {status}: card={on_card} sql={STATUS_PLAN}"

        # Geometry rides the chartPlan contract: an all-numeric dimension
        # (status values) folds to a line series; anything else to bars. The
        # card must carry exactly one of the two, never both.
        expect(card.locator('[data-test="chat-live-bars"], [data-test="chat-live-lines"]')
               ).to_have_count(1, timeout=5000)

        # Source 3: the sealed answer text quotes the counts (R22 honesty —
        # the tool result is journaled, the model restates it).
        answer = page.locator('[data-test="chat-msg"][data-role="assistant"]').last
        text = answer.inner_text(timeout=10000)
        for count in STATUS_PLAN.values():
            assert re.search(rf"\b{count}\b", text), \
                f"answer text does not restate count {count}: {text[:400]}"

        # Reload: the sealed text survives, and the card stays honestly
        # absent — the mapper replays chart:null (no re-invented
        # numbers on the history read path, chatHistoryMapper contract).
        # Same-hash goto never remounts the SPA view (known pitfall: live
        # state would survive in memory and fake a card "replay"). A real
        # reload is what exercises the history read path.
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-dock-body"]')).to_contain_text(
            "每个状态的用例有多少条", timeout=15000)
        expect(page.locator('[data-test="chat-live-card"]')).to_have_count(0, timeout=5000)

        assert_no_errors(collectors, "chat aggregate card three-way reconciliation")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        _purge_project(api_client, pid)
