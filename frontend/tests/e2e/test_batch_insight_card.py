""" AO-7 stage 3b close-out E2E: batch failure-insight card on the report page.

Real chain end to end (no mocks): batches created through the admin API, tasks
seeded as data prep (status=2 claimed rows so the live runner never touches
them — same posture as the internal batch-insight L3 chain), failure results
posted through the real core result door, the finalize event drives the
ai-service clustering consumer, which lands tp_batch_insight; the browser then
reads the card through the admin insight door.

Coverage (TC ledger in docs/17):
1. worst scale (30 failures: 1 AI-attributed + 29 executor-category across two
 categories) -> insight card renders: summary line, 3 ranked cluster rows,
 honest unattributed line naming 29;
2. RELOAD persistence — the card is a direct read of the landed row, not
 frontend state;
3. "ask root cause" button deep-links /ai/chat with the question prefilled in
 the composer and NEVER auto-sent (ask query consumed, no user bubble);
4. honest absence: an all-pass terminal batch (status 6, no insight row) hides
 the card entirely (v-if, zero residual DOM);
5. zero console error / zero 4xx / zero browser Issues (--headless=new via
 conftest, iron rule 5c).

Cleanup: seeded tasks / insight rows / notifications purged per project,
project deleted through the real API purge (audit rows stay by design).
"""
import json
import os
import time

import httpx
import pytest
from playwright.sync_api import expect

from conftest import (
    ADMIN_PASSWORD,
    ADMIN_USERNAME,
    BASE_URL,
    assert_no_errors,
    inject_user_token,
    sql_query,
)

pytestmark = pytest.mark.timeout(420)

CORE = os.environ.get("TP_CORE_BASE", "http://localhost:8083")


def _inject(page):
    """Real login + token injection via the shared conftest helper (zero local copy)."""
    inject_user_token(page, ADMIN_USERNAME, ADMIN_PASSWORD)


def _mk_project(api_client, uniq, tag):
    r = api_client.post("/projects", json={"name": f"洞察{tag}项目{uniq}", "code": f"IN{tag}{uniq[:5]}"})
    assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    return int(r.json()["data"])


def _mk_case(api_client, pid, uniq, n):
    r = api_client.post("/cases", json={
        "projectId": pid, "title": f"洞察用例{n}_{uniq}", "precondition": "无",
        "expectedResult": "符合预期", "priority": 1, "confidence": 2,
        "endType": "web", "status": 3,
        "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
    assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    d = r.json()["data"]
    return int(d["id"] if isinstance(d, dict) else d)


def _mk_batch_running(api_client, pid, name, case_ids):
    """Batch via the real API, then flipped to running (4) as data prep — the
    live executor never sees it because tasks are seeded already-claimed."""
    r = api_client.post("/batches", json={"projectId": pid, "name": name, "caseIds": case_ids})
    assert r.json().get("code") == "0", f"建批失败: {r.text[:300]}"
    bid = int(r.json()["data"]["id"])
    sql_query(f"UPDATE tp_exec_batch SET status = 4, started_at = now() WHERE id = {bid}")
    return bid


def _seed_task(pid, cid, bid, token):
    out = sql_query("INSERT INTO tp_exec_task (project_id, case_id, batch_id, status, claim_token)"
                    f" VALUES ({pid}, {cid}, {bid}, 2, '{token}') RETURNING id")
    return int(out.splitlines()[0])


def _post_fail(task_id, token, error_type, uniq):
    body = {"taskId": task_id, "claimToken": token, "result": "fail",
            "stepsTimeline": [], "screenshots": [], "durationMs": 1000,
            "log": "e2e-batch-insight-card", "errorType": error_type,
            "errorDetail": f"e2e failure #{uniq} type {error_type}"}
    return httpx.post(f"{CORE}/internal/executors/tasks/result", timeout=20, json=body).status_code


def _post_pass(task_id, token):
    body = {"taskId": task_id, "claimToken": token, "result": "pass",
            "stepsTimeline": [], "screenshots": [], "durationMs": 1000,
            "log": "e2e-batch-insight-card"}
    return httpx.post(f"{CORE}/internal/executors/tasks/result", timeout=20, json=body).status_code


def _poll_insight(bid, timeout=180):
    deadline = time.time() + timeout
    while time.time() < deadline:
        row = sql_query("SELECT fail_count || '|' || unattributed_count || '|' "
                        f"|| coalesce(clusters_json,'') FROM tp_batch_insight WHERE batch_id={bid}")
        if row:
            return row
        time.sleep(3)
    return ""


def test_batch_insight_card_worst_scale_and_ask(page, collectors, api_client, uniq):
    """Real chain, 30-failure worst scale: card renders with ranked cluster rows
    and the honest unattributed line, survives RELOAD, and the ask button lands
    on the chat page with the composer prefilled but never auto-sent."""
    pid = _mk_project(api_client, uniq, "A")
    made_tasks = []
    bid = None
    try:
        case_ids = [_mk_case(api_client, pid, uniq, i) for i in range(1, 31)]
        bid = _mk_batch_running(api_client, pid, f"洞察30失败批{uniq}", case_ids)
        # Seed ALL tasks first, then post results (same posture as
        # the internal batch-insight L3 chain): a partial seed would let the first
        # failure result finalize the batch while the rest are still invisible.
        # 30 failures: 1 pre-set AI attribution class=1 (ai:1 cluster, attributed),
        # 15 executor category 2 + 14 category 4 (both unattributed → exec:N clusters).
        planned = []
        for i, cid in enumerate(case_ids):
            tok = f"in-{uniq}-{i}"
            tid = _seed_task(pid, cid, bid, tok)
            made_tasks.append(tid)
            if i == 0:
                sql_query(f"UPDATE tp_exec_task SET ai_failure_class = 1, ai_failure_note = 'E2E 预置归因'"
                          f" WHERE id = {tid}")
                et = 2
            else:
                et = 2 if i <= 15 else 4
            planned.append((tid, tok, et))
        for tid, tok, et in planned:
            assert _post_fail(tid, tok, et, uniq) == 200, "失败回传必须 200（真实协议口）"
        assert sql_query(f"SELECT status FROM tp_exec_batch WHERE id={bid}") == "7", "批次应收口 7"

        got = _poll_insight(bid)
        assert got, "180s 内洞察行未落账（检查 ai-service 消费者与队列）"
        fc, un, clusters = got.split("|", 2)
        assert fc == "30" and un == "29", f"账目应 30 失败/29 未归因，实为 {fc}/{un}"
        arr = json.loads(clusters)
        keys = {c.get("class") for c in arr}
        assert keys == {"ai:1", "exec:2", "exec:4"}, f"三簇齐全应 ai:1/exec:2/exec:4，实为 {keys}"

        _inject(page)
        page.goto(f"{BASE_URL}/#/batches/{bid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=15000)
        card = page.locator('[data-test="batch-insight-card"]')
        expect(card).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-insight-title"]')).to_contain_text("失败根因洞察")
        expect(page.locator('[data-test="batch-insight-summary"]')).to_contain_text("共 30 条失败")
        rows = page.locator('[data-test="batch-insight-clusters"] .insight-cluster-row')
        expect(rows).to_have_count(3)
        # ranked by count: the 15-strong locator-error cluster leads the card.
        expect(rows.first).to_contain_text("定位错误（执行器分类）")
        expect(rows.first.locator('.insight-cluster-count')).to_have_text("15")
        expect(page.locator('[data-test="batch-insight-unattributed"]')).to_contain_text("29")

        # RELOAD persistence: card is a direct read of the landed row.
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-insight-card"]')).to_be_visible(timeout=15000)
        expect(rows).to_have_count(3, timeout=15000)

        # Ask-root-cause deep link: composer prefilled, never auto-sent.
        page.locator('[data-test="batch-insight-ask"]').click()
        page.wait_for_url(f"**/#/ai/chat**", timeout=15000)
        expect(page.locator('[data-test="ai-chat-page"]')).to_be_visible(timeout=15000)
        composer = page.locator('[data-test="chat-dock-input"]')
        expect(composer).to_have_value(f"请结合本批次（batchId={bid}）的失败根因聚类，分析主要原因并给出下一步排查建议",
                                        timeout=15000)
        assert "ask=" not in page.url, "ask query must be consumed once prefilled"
        bubbles = page.locator('[data-test="chat-msg"][data-role="user"]')
        for i in range(bubbles.count()):
            assert "请结合本批次" not in (bubbles.nth(i).text_content() or ""), \
                "预填问题绝不允许被自动发送"

        assert_no_errors(collectors, "batch insight card worst scale + ask link")
    finally:
        if made_tasks:
            sql_query(f"DELETE FROM tp_exec_task WHERE id IN ({','.join(map(str, made_tasks))})")
        if bid is not None:
            sql_query(f"DELETE FROM tp_batch_insight WHERE batch_id={bid}")
        sql_query(f"DELETE FROM tp_notification WHERE project_id={pid}")
        try:
            api_client.delete(f"/projects/{pid}/purge?force=true")
        except Exception:
            pass


def test_all_pass_batch_hides_insight_card(page, collectors, api_client, uniq):
    """Honest absence: an all-pass terminal batch (status 6, no insight row)
    renders no card and never errors."""
    pid = _mk_project(api_client, uniq, "B")
    made_tasks = []
    bid = None
    try:
        cid = _mk_case(api_client, pid, uniq, 1)
        bid = _mk_batch_running(api_client, pid, f"洞察全通过批{uniq}", [cid])
        tok = f"in-ok-{uniq}"
        tid = _seed_task(pid, cid, bid, tok)
        made_tasks.append(tid)
        assert _post_pass(tid, tok) == 200
        assert sql_query(f"SELECT status FROM tp_exec_batch WHERE id={bid}") == "6"
        # Give the consumer a chance to (not) write: the clustering skipped
        # honestly, so after the finalize settles there must be zero rows.
        time.sleep(8)
        assert sql_query(f"SELECT count(*) FROM tp_batch_insight WHERE batch_id={bid}") == "0", \
            "全通过批不得落洞察行"

        _inject(page)
        page.goto(f"{BASE_URL}/#/batches/{bid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-insight-card"]')).to_have_count(0)

        assert_no_errors(collectors, "batch insight honest absence")
    finally:
        if made_tasks:
            sql_query(f"DELETE FROM tp_exec_task WHERE id IN ({','.join(map(str, made_tasks))})")
        sql_query(f"DELETE FROM tp_notification WHERE project_id={pid}")
        try:
            api_client.delete(f"/projects/{pid}/purge?force=true")
        except Exception:
            pass
