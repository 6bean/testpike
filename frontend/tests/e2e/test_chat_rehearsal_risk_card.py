"""Dialog-side proof of the rehearsal risk report face .

The L4 smoke (the internal L4 rehearsal smoke) already reconciles the card
numbers with the result ledger through the raw analysis door. This spectrum
closes the remaining face: a real logged-in browser asks the dialog about a
finished rehearsal batch, the real LLM selects the journaled analysis tool
whose name carries `rehearsalRiskReport`, the tool runs through the real
analysis door, and the answer restates the ledger numbers. Per the piece-4
adjudication there is NO new frame type: the assertion anchor is the
analysis-process (trace) surface, and the sealed-turn reload honestly shows
no trace replay (chatHistoryMapper folds `process: []`).

Zero mocks on the chain: the batch is really executed by the live executor
against the fixture demo site, and the dialog turn streams through the real
LLM function-calling loop. The three gate keys are hot-updated through the
admin CRUD door (real broadcast semantics) and restored to their captured
pre-run values in the tail; the scratch project is purged so the two demo
projects stay untouched.

Iron rules honored: real login + real stream + real DB; the tail runs the
4xx/5xx + console + Issues-panel zero-tolerance check; screenshots for the
vision scoring faces land under /tmp/t440p4-shots/.
"""
import os
import re
import time

import httpx
import pytest
from chat_spectrum_support import (
    BASE_URL,
    _set_sys_config_value,
    assert_no_errors,
    inject_token,
    purge_project,
    seed_project,
    sql_query,
)
from playwright.sync_api import expect

# Shares the single-worker group of the ai-chat family: this spectrum drives a
# live stream while toggling the global exec switch with its siblings.
pytestmark = [pytest.mark.timeout(420), pytest.mark.xdist_group("ai-chat-exec-switch")]

CORE_BASE = os.environ.get("TP_CORE_BASE", "http://localhost:8083")
DEMO_SITE = "http://localhost:8899"
SHOTS_DIR = "/tmp/t440p4-shots"

KEY_MASTER = "ai.chat.exec-enabled"
KEY_SUB = "ai.chat.rehearsal-run.enabled"
KEY_WL = "ai.chat.exec-project-whitelist"

# Uneven by design (7 = 4 passed + 3 failed): a swapped or summed number can
# never pass by coincidence, and the ledger re-read is the single truth the
# answer text must match.
PASS_COUNT, FAIL_COUNT = 4, 3

PASS_ACTION = ('{"version":"v2","steps":['
               '{"action":"goto","url":"http://localhost:8899/demo_page.html"},'
               '{"action":"input","target":{"css":"#username"},"value":"rehearsal-e2e"},'
               '{"action":"click","target":{"css":"#login-btn"}},'
               '{"action":"expect","text":"欢迎"}]}')
FAIL_ACTION = ('{"version":"v2","steps":['
               '{"action":"goto","url":"http://localhost:8899/demo_page.html"},'
               '{"action":"expect","text":"THIS_TEXT_NEVER_APPEARS_ON_PAGE"}]}')


def _cfg_value(key: str) -> str:
    rows = sql_query(f"SELECT config_value FROM tp_sys_config WHERE config_key='{key}'")
    return rows.strip()


def _seed_executed_batch(api_client, pid, uniq):
    """Draft batch + scripted cases through the real doors, then a real run.

    Mirrors the L4 recipe (env with versionSnapshot -> cases -> core script
    save -> batch draft -> rehearsal-runs door) so the browser face rides the
    same production path, not a faked ledger.
    """
    env = api_client.post(f"/projects/{pid}/envs", json={
        "name": f"演练E2E环境{uniq}", "envType": 2, "baseUrl": DEMO_SITE,
        "endTypes": "web", "versionSnapshot": f"build-{uniq}"}).json()["data"]
    env_id = env["id"] if isinstance(env, dict) else env

    case_ids = []
    for i in range(PASS_COUNT + FAIL_COUNT):
        title = f"演练E2E{'通过' if i < PASS_COUNT else '失败'}{i}-{uniq}"
        r = api_client.post("/cases", json={
            "projectId": pid, "title": title, "precondition": "夹具站点可达",
            "expectedResult": "按断言判定", "priority": 1, "confidence": 2,
            "endType": "web", "status": 3, "phase": 2,
            "steps": [{"stepNo": 1, "description": "打开演示页", "isAssert": 1}]})
        assert r.status_code == 200 and r.json().get("code") == "0", f"seed case: {r.text[:200]}"
        data = r.json()["data"]
        case_ids.append(data["id"] if isinstance(data, dict) else data)

    for idx, cid in enumerate(case_ids):
        action = PASS_ACTION if idx < PASS_COUNT else FAIL_ACTION
        saved = httpx.post(f"{CORE_BASE}/internal/scripts", json={
            "caseId": cid, "schemaVersion": "v2", "actionJson": action,
            "generatedBy": 1}, timeout=10).json()
        assert saved.get("code") == "0", f"script save case={cid}: {saved}"

    batch = api_client.post("/batches", json={
        "projectId": pid, "name": f"演练E2E批{uniq}", "envId": env_id,
        "caseIds": case_ids}).json()["data"]
    bid = batch["id"] if isinstance(batch, dict) else batch
    assert bid, f"batch draft creation failed: {batch}"
    return bid


def _launch_and_wait(bid: int) -> None:
    """Real rehearsal-runs door (all three gates open) + live executor drain."""
    admin_id = int(sql_query("SELECT id FROM tp_user WHERE username='admin'").strip())
    r = httpx.post(f"{CORE_BASE}/internal/ai/chat/rehearsal-runs",
                   json={"batchId": bid},
                   headers={"X-Operator-Id": str(admin_id)}, timeout=15).json()
    assert r.get("code") == "0", f"rehearsal launch refused: {r}"

    deadline = time.time() + 240
    while time.time() < deadline:
        status = sql_query(f"SELECT status FROM tp_exec_batch WHERE id={bid}").strip()
        if status in ("6", "7"):
            return
        # Non-browser async wait (executor claim/finalize loop): polling the
        # ledger is the only correct cue here, no Playwright auto-wait applies.
        time.sleep(4)
    raise AssertionError(f"batch {bid} never reached a terminal state within 240s")


def _number_quoted(text: str, number: int) -> bool:
    """Digit-group search safe for CJK adjacency (\\b fails between digit and Han)."""
    return re.search(rf"(?<![\d.]){number}(?![\d.])", text) is not None


def test_chat_dialog_faces_rehearsal_risk_report(page, collectors, api_client, uniq):
    """Trace surface carries the tool run, answer text matches the ledger, reload honest."""
    pid = seed_project(api_client, uniq, "RISKE2E")
    before = {k: _cfg_value(k) for k in (KEY_MASTER, KEY_SUB, KEY_WL)}
    try:
        _set_sys_config_value(KEY_MASTER, "true")
        _set_sys_config_value(KEY_SUB, "true")
        _set_sys_config_value(KEY_WL, str(pid))
        bid = _seed_executed_batch(api_client, pid, uniq)
        _launch_and_wait(bid)

        agg = sql_query(
            "SELECT count(*)||'|'||COALESCE(SUM(CASE WHEN result=1 THEN 1 ELSE 0 END),0)"
            f"||'|'||COALESCE(SUM(CASE WHEN result=2 THEN 1 ELSE 0 END),0) "
            f"FROM tp_batch_case WHERE batch_id={bid}").strip().split("|")
        total, passed, failed = (int(x) for x in agg)
        assert (total, passed, failed) == (PASS_COUNT + FAIL_COUNT, PASS_COUNT, FAIL_COUNT), \
            f"fixture drift: ledger says {total}/{passed}/{failed}"

        question = f"批次 {bid} 已经跑完了，用 rehearsalRiskReport 给我这个演练批次的风险报告"
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        page.locator('[data-test="chat-dock-input"]').fill(question)
        page.locator('[data-test="chat-dock-input"]').press("Enter")
        expect(page.locator('[data-test="chat-dock-stop"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=240000)

        # Face 1: the trace surface (not a new frame type — piece-4 anchor).
        tool_lines = page.locator(".chat-process-item", has_text="rehearsalRiskReport")
        expect(tool_lines).not_to_have_count(0)
        expect(page.locator(".chat-process-item", has_text="rehearsalRiskReport")
               .filter(has_text="执行完成")).not_to_have_count(0)

        # Face 2: the sealed answer restates the ledger numbers (same digits
        # the L4 reconciliation proved diff=0 server-side).
        answer = page.locator('[data-test="chat-msg"][data-role="assistant"]').last
        text = answer.inner_text(timeout=10000)
        for number in (total, passed, failed):
            assert _number_quoted(text, number), \
                f"answer does not restate {number}: {text[:400]}"

        # Screenshots for the three vision faces (collapsed / expanded / reload).
        # Contract anchor: a sealed turn auto-collapses its process area
        # (S-group default-collapsed-at-scale, ChatPanel.vue sealed-turn
        # handler), so the first state under test is collapsed.
        toggle = page.locator('[data-test="chat-process-toggle"]').last
        expect(toggle).to_have_attribute("aria-expanded", "false")
        os.makedirs(SHOTS_DIR, exist_ok=True)
        page.screenshot(path=f"{SHOTS_DIR}/risk-collapsed.png", full_page=True)
        toggle.click()
        expect(toggle).to_have_attribute("aria-expanded", "true")
        page.screenshot(path=f"{SHOTS_DIR}/risk-expanded.png", full_page=True)
        toggle.click()

        # Face 3: reload rides the sealed-row read path — the answer text is
        # rebuilt and the trace honestly does NOT replay (chatHistoryMapper
        # folds process: []; an absent zone is the contract, asserted hard).
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-dock-body"]')).to_contain_text(
            "风险报告", timeout=15000)
        expect(page.locator('[data-test="chat-dock-process"]')).to_have_count(0, timeout=5000)
        page.screenshot(path=f"{SHOTS_DIR}/risk-reload.png", full_page=True)

        assert_no_errors(collectors, "chat rehearsal risk report dialog face")
    finally:
        for key, value in before.items():
            _set_sys_config_value(key, value)
        purge_project(api_client, pid)
