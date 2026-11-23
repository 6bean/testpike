"""R67 revisit journey for the qa-gen question-generation door.

Real chain, zero stubs (R3): the preview submits over admin -> ai-service
with the grouped-intake door open and the sync cap at its factory value,
so a document that seals into two or more groups takes the accepted
branch: a run token plus a stored trail in tp_ai_task_event under kind
'qa-gen'. The journey proves the revisit contract after the run seals:

 1. the accepted receipt carries a run token and the trail lands in the
    ledger (DB readback on the run_token),
 2. after leaving the evalset page entirely and re-entering through the
    deep link, the run-history door still lists the sealed run and its
    replay button (terminal state archives the live window, never the
    visibility),
 3. replaying through the shared tail window shows the stored frames
    (DOM rows bounded by the DB event count), the cursor channel is read
    monotonically without frame loss and the door is never re-fetched
    as a whole (R67 lazy-load semantics).

Three zero-tolerance checks (4xx/5xx + console error/warning + browser
Issues) close the journey; --headless=new posture comes from conftest.
"""
import hashlib
import re
import time

import psycopg2
import pytest
from conftest import (
    BASE_URL,
    assert_no_errors,
    seed_project,
)
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect
from test_f2_extraction import sql
from web_element_ledger_kit import dsn

QA_GEN_JOB_KIND = "qa-gen"
GROUP_TOKENS_KEY = "judge.qa-gen.group-max-tokens"
GROUP_TOKENS_VALUE = "256"
TERMINAL_STATUSES = ("done", "failed", "partial", "stopped", "cancelled")
RUN_WAIT_S = 240


def _insert_id(statement):
    """Parse the generated key of one INSERT ... RETURNING statement
    (psql -tA prints the row then the command tag: first line only)."""
    return int(sql(statement).splitlines()[0])


def _set_group_tokens():
    """Pin the per-group token ceiling to the window minimum so a
    two-chunk document seals into two groups and the accepted branch
    (run token + background job) is the only outcome. The row is not
    factory-seeded: absent before this fixture, deleted by it after."""
    existed = sql(
        "SELECT count(*) FROM tp_sys_config WHERE "
        f"config_key='{GROUP_TOKENS_KEY}'").splitlines()[0].strip()
    if existed == "0":
        sql("INSERT INTO tp_sys_config (config_key, config_value, group_name,"
            " value_type, encrypt_flag, description) VALUES"
            f" ('{GROUP_TOKENS_KEY}', '{GROUP_TOKENS_VALUE}', '系统', 'string', 0,"
            " 'e2e revisit journey fixture')")
    else:
        sql("UPDATE tp_sys_config SET config_value='" + GROUP_TOKENS_VALUE +
            f"' WHERE config_key='{GROUP_TOKENS_KEY}'")


def _reset_group_tokens():
    sql(f"DELETE FROM tp_sys_config WHERE config_key='{GROUP_TOKENS_KEY}'")


def _seed_two_chunk_doc(pid, uniq):
    """Fixture-plane document with two long chunks — the preview submit
    itself travels the real admin -> ai-service chain."""
    digest = hashlib.sha256(f"qgv-{uniq}".encode()).hexdigest()
    filler = "qa gen revisit filler sentence " * 120
    doc_id = _insert_id(
        "INSERT INTO tp_document (project_id, name, file_type, file_hash,"
        " status, chunk_count)"
        f" VALUES ({pid}, 'QGV-{uniq[:8]}.md', 'md', '{digest}', 1, 2)"
        " RETURNING id")
    for seq in (1, 2):
        _insert_id(
            "INSERT INTO tp_document_chunk (document_id, chapter, seq,"
            " content, token_est)"
            f" VALUES ({doc_id}, 'revisit chapter {seq}', {seq},"
            f" '{filler}', 900) RETURNING id")
    return doc_id


def _cleanup_doc(doc_id):
    if doc_id is None:
        return
    sql(f"DELETE FROM tp_document_chunk WHERE document_id={doc_id}")
    sql(f"DELETE FROM tp_qa_gen_job WHERE document_id={doc_id}")
    sql(f"DELETE FROM tp_ai_task_event WHERE job_kind='{QA_GEN_JOB_KIND}'"
        f" AND run_token IN (SELECT run_token FROM tp_qa_gen_job"
        f" WHERE document_id={doc_id})")


def _stored_events(run_token):
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT id, level, summary FROM tp_ai_task_event"
            " WHERE job_kind = %s AND run_token = %s ORDER BY id",
            (QA_GEN_JOB_KIND, run_token))
        return cur.fetchall()


@pytest.mark.timeout(540)
def test_qa_gen_revisit_journey(logged_in, collectors, api_client, uniq):
    """Preview accepted -> leave the page entirely -> re-enter the
    evalset deep link -> replay the sealed run with a continuous cursor."""
    page = logged_in
    pid = seed_project(api_client, uniq, "QGV", display_name=f"出题回放{uniq}")
    doc_id = None
    try:
        _set_group_tokens()
        doc_id = _seed_two_chunk_doc(pid, uniq)

        # The shared api_client posts a body only; query values ride the URL.
        r = api_client.post(f"/eval-sets/qa-preview?projectId={pid}&documentId={doc_id}")
        assert r.status_code == 200, r.text[:300]
        body = r.json()
        assert body.get("code") == "0", f"preview rejected: {body.get('msg')}"
        outcome = body["data"]
        assert outcome.get("mode") == "accepted", (
            f"expected the accepted branch with a two-group document, got "
            f"{outcome.get('mode')} — grouped-intake/sync-cap fixture lost")
        run_token = outcome.get("runToken")
        assert run_token, f"accepted receipt carries no run token: {outcome}"

        events = []
        deadline = time.time() + RUN_WAIT_S
        while time.time() < deadline:
            runs_r = api_client.get("/eval-sets/qa-gen-runs",
                                    params={"documentId": doc_id, "limit": 10})
            runs = runs_r.json().get("data") or []
            mine = [x for x in runs if x.get("runToken") == run_token]
            events = _stored_events(run_token)
            if mine and str(mine[0].get("status", "")).lower() in TERMINAL_STATUSES:
                break
            time.sleep(3)
        assert events, "accepted qa-gen run stored no trail frames (door silent)"

        evalset_url = f"{BASE_URL}/#/judge/evalset?projectId={pid}"
        # Leave the page entirely, then re-enter through the deep link:
        # the sealed run must stay replayable from the run-history door.
        page.goto(f"{BASE_URL}/#/projects", wait_until="domcontentloaded")
        page.goto(evalset_url, wait_until="domcontentloaded")
        expect(page.locator('[data-test="evalset-qaruns-btn"]')).to_be_visible(
            timeout=20000)

        cursor_reads = []

        def on_request(request):
            m = re.search(r"qa-gen-events\?.*cursor=([^&\s]+)", request.url)
            if m:
                cursor_reads.append(m.group(1))

        page.on("request", on_request)
        page.locator('[data-test="evalset-qaruns-btn"]').click()
        dialog = page.locator('[data-test="qagen-runs-dialog"]')
        expect(dialog).to_be_visible(timeout=15000)

        doc_sel = dialog.locator('[data-test="qagen-runs-doc"]')
        doc_sel.click()
        doc_sel.locator("input").fill(f"QGV-{uniq[:8]}")
        option = page.locator(".el-select-dropdown__item:visible",
                              has_text=f"QGV-{uniq[:8]}")
        expect(option.first).to_be_visible(timeout=15000)
        option.first.click()

        replay_btn = page.locator(f'[data-test="qagen-run-tail-{run_token}"]')
        expect(replay_btn.first).to_be_visible(timeout=20000)
        replay_btn.first.click()

        window = page.locator('[data-test="qagen-runs-tail-window"]')
        expect(window).to_be_visible(timeout=15000)
        page.wait_for_selector(".task-tail-empty, .task-tail-row", timeout=25000)
        dom_rows = 0
        deadline = time.time() + 30
        while time.time() < deadline:
            dom_rows = window.locator(".task-tail-row").count()
            if dom_rows >= 1:
                break
            page.wait_for_timeout(500)
        assert dom_rows > 0, "replay tail window renders no stored frames"
        assert dom_rows <= len(events) + 1, (
            f"replay shows {dom_rows} rows for {len(events)} stored events — "
            "phantom frames on the revisit channel")

        # Cursor continuity: every read carries a cursor argument, values
        # never rewind, and the door is never re-fetched as a whole.
        assert cursor_reads, "replay never touched the lazy cursor door"
        numeric = [int(x) for x in cursor_reads if re.fullmatch(r"\d+", x)]
        assert len(numeric) == len(cursor_reads), (
            f"non-numeric cursor leaked into reads: {cursor_reads}")
        assert numeric == sorted(numeric), (
            f"cursor went backwards (frame loss risk): {cursor_reads}")
        whole_refetches = [x for x in cursor_reads if x == "0"]
        assert len(whole_refetches) <= 1, (
            f"tail refetched the whole run {len(whole_refetches)} times")

        assert_no_errors(collectors, "qa-gen revisit journey")
        assert_no_browser_issues(
            collectors.get("browser_issues", []), "qa-gen revisit journey")
    finally:
        _cleanup_doc(doc_id)
        _reset_group_tokens()
