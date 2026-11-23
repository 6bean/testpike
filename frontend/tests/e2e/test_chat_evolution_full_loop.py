"""Self-evolution full-loop E2E .

Proves the whole AF-i loop in a REAL browser against the REAL chain
(browser -> admin 8081 -> core 8083 -> DB), on a scratch project seeded with
its own complaint evidence:

1) one streamed answer gets a real 👎 + complaint sentence (the observation
 intake lands in the message ledger — zero evidence would mean zero cards);
2) generating the month's proposals opens a run session whose cards fold back
 on a FRESH page read (the stage-5 proof point: the sealed-history read path
 carries the draft rows; the card is never observed via a live-stream echo);
3) confirming + applying the prompt-family card through the two-click ticket
 door appends a NEW template version through the human save chain (the
 ledger is asserted, not a toast);
4) one-click rollback copies the predecessor forward through the prompt
 version chain — the new row carries rollback_to, the draft row is stamped
 rolledBackFrom (once-only mark);
5) a reload keeps the card visible showing the rolled-back line, and the
 rollback door is gone (never offered a second time).

Cleanup honesty: the three template rows this spectrum grows (seed + applied
+ rolled) are removed by id in the finally clause — the family table is a
shipped factory asset and must read exactly as it did before the run.

Iron rules: exec gate hot-enabled and restored to the factory value; scratch
project purged by the shared sweep; every phase ends with the 4xx/5xx +
console + Issues-panel zero-tolerance check. Pinned to the shared exec-switch
worker group like the rest of the AI-chat family.
"""
from datetime import date

import pytest
from conftest import (
    BASE_URL,
    _set_sys_config_value,
    assert_no_errors,
    inject_token,
    purge_project,
    seed_project,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = [pytest.mark.timeout(300), pytest.mark.xdist_group("ai-chat-exec-switch")]

PROMPT_NAME = "dialog-answer-prompt"
PROMPT_DOMAIN = "对话"
# Receipt wording singles (zh locale, layout.chatDock.draft.*): appliedEvolution
# names the moved version, rollbackDone/landedRolledBack are the undo faces.
# Match the stable capability phrases, not a whole re-wordable sentence.
APPLIED_PHRASE = "已更新到第"
ROLLBACK_DONE_PHRASE = "已回滚：模板"
LANDED_ROLLED_BACK_PHRASE = "已回滚：该提案的应用已被撤销"


def _family_max_version() -> int:
    """Highest version of the prompt family (every status: core's nextVersion
    reads the family max without a status filter, same denominator)."""
    return int(sql_query(
        "SELECT coalesce(max(version),0) FROM tp_prompt_template "
        f"WHERE name='{PROMPT_NAME}' AND domain='{PROMPT_DOMAIN}'"))


def _assistant_message_id(pid: int) -> int:
    sid = sql_query(f"SELECT id FROM tp_ai_chat_session WHERE project_id={pid} ORDER BY id DESC LIMIT 1")
    assert sid, "the streamed turn must have opened a session row"
    mid = sql_query(f"SELECT id FROM tp_ai_chat_message WHERE session_id={sid} "
                    f"AND role='assistant' ORDER BY id DESC LIMIT 1")
    assert mid, "the sealed assistant row must exist in the ledger"
    return int(mid)


def _reveal_first_card(page, marker: str) -> None:
    """Windowed-transcript reach: hold the scroll at the top until the card.

    The run's prompt-family card is the session's FIRST sealed message, while
    the chat body auto-anchors to the bottom after a history load (the
    established S-group shape), so an off-window card is a scroll reach — not
    a missing render — exactly how a human finds it. The hold loop re-pins the
    top on every tick because the mount-time bottom anchor may re-fire while
    the window recomputes.
    """
    body = page.locator('[data-test="chat-dock-body"]')
    expect(body).to_be_visible(timeout=10000)
    card = page.locator('[data-test="chat-draft-card"]').filter(has_text=marker)
    for _ in range(30):
        body.evaluate("el => el.scrollTop = 0")
        if card.count() > 0:
            return
        page.wait_for_timeout(250)
    raise AssertionError(f"the '{marker}' card never entered the window while pinned to the top")



def test_evolution_propose_confirm_apply_rollback_reload(page, collectors, api_client, uniq):
    """Complaint -> proposal cards -> two-click apply -> rollback -> reload keeps the undo truth."""
    pid = seed_project(api_client, uniq, "EVOLOOP")
    token = uniq[:6].lower()
    month = date.today().strftime("%Y-%m")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    seeded_row_id = 0
    applied_row_id = 0
    rolled_row_id = 0
    run_session_id = ""
    try:
        # Pre-condition through the human save door: the family gets a first
        # version. Rollback of a proposal applied as version 1 must refuse —
        # the undo door needs a predecessor, so the test seeds one honestly.
        saved = api_client.post("/prompt-templates", json={
            "name": PROMPT_NAME, "domain": PROMPT_DOMAIN,
            "content": f"baseline dialog answer prompt {token}",
        })
        assert saved.status_code == 200 and saved.json().get("code") == "0", \
            f"seeding the family via the save door failed: {saved.text[:200]}"
        seeded_row_id = int(saved.json()["data"]["id"])
        base_version = _family_max_version()
        assert base_version >= 1, "the seeded row must carry a family version"

        # 1) A real streamed turn + 👎 with a sentence: the observation intake.
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        inp = page.locator('[data-test="chat-dock-input"]')
        inp.fill(f"只回答两个字：评价{token}")
        inp.press("Enter")
        expect(page.locator('[data-test="chat-dock-stop"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=180000)

        mid = _assistant_message_id(pid)
        fb = api_client.post(f"/ai/chat/messages/{mid}/feedback",
                             json={"feedback": -1, "reason": f"引用过期{token}"})
        assert fb.status_code == 200 and fb.json().get("code") == "0", \
            f"recording the complaint failed: {fb.text[:200]}"
        assert sql_query(f"SELECT feedback FROM tp_ai_chat_message WHERE id={mid}") == "-1", \
            "the complaint must land in the message ledger"
        assert_no_errors(collectors, "complaint intake")

        # 2) Generate the month: the run session carries the evidence-backed cards.
        gen = api_client.post(f"/ai/chat/evolution/proposals?month={month}&projectId={pid}")
        assert gen.status_code == 200 and gen.json().get("code") == "0", \
            f"proposal generation failed: {gen.text[:300]}"
        data = gen.json()["data"]
        run_session_id = str(data["sessionId"])
        subkinds = {p["subKind"] for p in data["proposals"]}
        assert "PROMPT_TPL" in subkinds, f"the prompt-family card is missing: {subkinds}"
        assert sql_query(
            f"SELECT count(*) FROM tp_ai_chat_draft WHERE session_id={run_session_id}"
        ) == str(len(data["proposals"])), "every proposal row must exist in the draft ledger"

        # 3) The stage-5 proof: reach the run session through a FRESH list
        #    mount (leave the page and return — a same-URL goto keeps the
        #    mounted panel and its already-fetched list) and the cards fold
        #    back from the ledger (pending doors, not a stream echo).
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-page-side"]')).to_be_visible(timeout=10000)
        title_row = page.locator('[data-test="session-row"]').filter(has_text=f"自进化提案 {month}")
        expect(title_row).to_have_count(1, timeout=15000)
        title_row.click()
        _reveal_first_card(page, "提示词模板修订提案")
        prompt_card = page.locator('[data-test="chat-draft-card"]').filter(
            has_text="提示词模板修订提案")
        expect(prompt_card).to_have_count(1, timeout=15000)
        expect(prompt_card.locator('[data-test="chat-draft-evolution-fields"]')).to_have_count(1)
        expect(prompt_card.locator('[data-test="chat-draft-confirm"]')).to_be_visible()
        assert_no_errors(collectors, "proposal cards fold back onto the reload read path")

        # 4) Two-click door: confirm opens the one-shot ticket, apply lands the
        #    proposal as a NEWER template version (ledger + receipt agree).
        prompt_card.locator('[data-test="chat-draft-confirm"]').click()
        apply_btn = prompt_card.locator('[data-test="chat-draft-apply"]')
        expect(apply_btn).to_be_visible(timeout=10000)
        apply_btn.click()
        receipt = prompt_card.locator('[data-test="chat-draft-receipt"]')
        expect(receipt).to_contain_text(APPLIED_PHRASE, timeout=15000)

        draft_row = sql_query(
            "SELECT id || '|' || status || '|' || target_id FROM tp_ai_chat_draft "
            f"WHERE session_id={run_session_id} AND payload_json LIKE '%PROMPT_TPL%' "
            "ORDER BY id LIMIT 1")
        draft_id, status, applied_row_id_s = draft_row.split("|")
        assert status == "2", f"the applied draft row must read status=2, got {status}"
        applied_row_id = int(applied_row_id_s)
        landed_version = sql_query(f"SELECT version FROM tp_prompt_template WHERE id={applied_row_id}")
        assert int(landed_version) > base_version, \
            f"apply must append a newer version than the seeded {base_version}, got {landed_version}"
        expect(receipt).to_contain_text(landed_version)
        assert sql_query(
            f"SELECT coalesce(applied_version,0) || '|' || coalesce(target_id,0) "
            f"FROM tp_ai_chat_draft WHERE id={draft_id}") == f"{landed_version}|{applied_row_id}", \
            "the draft ledger must record the applied version and its target row"
        assert_no_errors(collectors, "confirm + apply appends a template version")

        # 5) One-click rollback: the predecessor copies FORWARD (never an
        #    in-place rewrite), the rolled row anchors its source, and the
        #    draft carries the once-only mark.
        rollback_btn = prompt_card.locator('[data-test="chat-draft-rollback"]')
        expect(rollback_btn).to_be_visible(timeout=5000)
        rollback_btn.click()
        expect(receipt).to_contain_text(ROLLBACK_DONE_PHRASE, timeout=15000)

        prev_row_id = sql_query(
            "SELECT id FROM tp_prompt_template "
            f"WHERE name='{PROMPT_NAME}' AND domain='{PROMPT_DOMAIN}' "
            f"AND version = {landed_version} - 1 LIMIT 1")
        assert prev_row_id, "the applied version must have a predecessor row in the chain"
        rolled_ids = sql_query(
            f"SELECT id FROM tp_prompt_template WHERE rollback_to = {prev_row_id} "
            "ORDER BY version DESC LIMIT 1")
        assert rolled_ids, "the rollback must copy the predecessor forward as a new row"
        rolled_row_id = int(rolled_ids)
        rolled_version = sql_query(f"SELECT version FROM tp_prompt_template WHERE id={rolled_row_id}")
        assert int(rolled_version) > int(landed_version), \
            f"rollback must move forward, got version {rolled_version}"
        assert sql_query(
            "SELECT coalesce(context_json ->> 'rolledBackFrom','~') FROM tp_ai_chat_draft "
            f"WHERE id={draft_id}") == landed_version, \
            "the draft row must carry the once-only rollback mark"
        assert_no_errors(collectors, "rollback copies the predecessor forward")

        # 6) Reload truth: the card still renders, states the undo, and offers
        #    no second rollback door (the once-only rule is the screen's too).
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-page-side"]')).to_be_visible(timeout=10000)
        reopened = page.locator('[data-test="session-row"]').filter(has_text=f"自进化提案 {month}")
        expect(reopened).to_have_count(1, timeout=15000)
        reopened.click()
        _reveal_first_card(page, "提示词模板修订提案")
        reloaded_card = page.locator('[data-test="chat-draft-card"]').filter(
            has_text="提示词模板修订提案")
        expect(reloaded_card).to_have_count(1, timeout=15000)
        expect(reloaded_card.locator('[data-test="chat-draft-receipt"]')).to_contain_text(
            LANDED_ROLLED_BACK_PHRASE, timeout=15000)
        expect(reloaded_card.locator('[data-test="chat-draft-rollback"]')).to_have_count(0)
        expect(reloaded_card.locator('[data-test="chat-draft-confirm"]')).to_have_count(0)
        assert_no_errors(collectors, "the reloaded card keeps the rolled-back truth")

        # Version history accumulates honestly: nothing this loop produced was
        # rewritten in place (seed + applied + rolled rows all still queryable).
        assert sql_query(
            f"SELECT count(*) FROM tp_prompt_template WHERE id IN ({seeded_row_id},"
            f"{applied_row_id},{rolled_row_id})") == "3", \
            "the version chain keeps every row it grew"
    finally:
        purge_project(api_client, pid)
        # Factory asset stays untouched: the three rows this spectrum grew
        # (seed + applied + rolled) are removed by id, newest first.
        for row_id in (rolled_row_id, applied_row_id, seeded_row_id):
            if row_id:
                sql_query(f"DELETE FROM tp_prompt_template WHERE id={row_id}")
        _set_sys_config_value("ai.chat.exec-enabled", "true")
