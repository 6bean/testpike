""" close-ticket: live process-event tail drawer on the extracting dialog.

The user-facing defect this segment closes: long AI jobs (extraction, steps
generation) ran with no process surface at all — the operator could not watch
per-step progress, could not see why a run failed, and a dropped wire left the
drawer frozen. Spectrum pins, in one real browser session against real doors
(admin 8081 → core 8083 → PostgreSQL ledger table):

* a dialog opened on a running extraction auto-attaches the tail (segment state
 3) and a dialog that later observes the run start attaches as well (the
 loadPreview 1→3 gap closed by the state-change guard);
* a row landed in the database while the drawer is open reaches the DOM
 within 3s through the SSE road (no click, no reopen);
* an error event is always reachable through the red anchor chip;
* a broken stream wire degrades to the cursor-window poll inside the shared
 client and the ledger keeps growing (seen frames ∪ cursor continuation);
* worst-scale fixture (120 rows, cap 500) renders with hard counts — every
 assertion below is unconditional (iron law 5d: no if-count wrappers).

Seeding is SQL-only (status=3 + run token + append-only event rows) so no real
LLM run is burned; the browser rides the real endpoints either way (L5 with
real chain). Fixture data lives in a scratch project, cleaned in finally with
a zero-residue check (R37: shipped tables are never truncated).
"""

import uuid

import pytest
from conftest import ADMIN_PASSWORD, api_login, assert_no_errors
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect
from test_extraction_preview_review_gap import _wait_parsed  # noqa: F401  (shared parse warm-up)
from test_f2_extraction import (
    doc_page,
    make_project,
    open_extract_dialog,
    sql,
    upload_doc,
)

TAIL_ROWS_WORST = 120


def _seed_events(doc_id, token, rows):
    """rows: list of (level, summary, detail) appended in order (cursor = id)."""
    for level, summary, detail in rows:
        detail_sql = "NULL" if detail is None else f"'{detail}'"
        sql("INSERT INTO tp_ai_task_event"
            " (job_kind, job_ref, run_token, level, summary, detail)"
            f" VALUES ('doc-extract', '{doc_id}', '{token}', '{level}',"
            f" '{summary}', {detail_sql})")


def _enter_running(page, api_client, uniq, tag, token):
    """Scratch project + uploaded doc flipped to status=3 with the run token."""
    pid = make_project(api_client, uniq, tag)
    doc_id, name, _sha = upload_doc(api_client, pid, uniq, tag,
                                    content="第一条需求：登录需要验证码。\n第二条需求：导出支持CSV。\n")
    sql(f"UPDATE tp_document SET status=3, extract_token='{token}' WHERE id={doc_id}")
    return pid, doc_id, name


@pytest.fixture()
def _hard_headless():
    # The suite launcher pins --headless=new (iron law 5c: old headless reports
    # zero Audits issues = fake monitoring). Guard kept as an assertion anchor
    # in the report line, not a runtime switch.
    yield


def test_extraction_process_tail_drawer(page, collectors, api_client, uniq, _hard_headless):
    token = uuid.uuid4().hex[:12]
    pid = doc_id = None
    try:
        pid, doc_id, name = _enter_running(page, api_client, uniq, "tail", token)
        _seed_events(doc_id, token, [
            ("step", "提取启动", None),
            ("thinking", "分组 1/2 分析中", "chunks=3 tokens=1840"),
            ("error", "模型响应超时，已按预算回退", "cause=timeout after 300s"),
        ])

        doc_page(page, pid)
        dialog = open_extract_dialog(page, name)

        # --- auto-attach on a running document (state 3 → tail opens itself) ---
        drawer = dialog.locator('[data-test="task-tail-drawer"]')
        expect(drawer).to_be_visible(timeout=10000)
        tail_list = dialog.locator('[data-test="task-tail-list"]')
        expect(tail_list).to_be_visible()
        # hard count of the three seeded frames — never a conditional wrapper
        expect(dialog.locator('[data-test^="task-tail-row-"]')).to_have_count(3, timeout=10000)

        # --- live road: one row landed after the drawer opened, ≤3s to DOM ---
        _seed_events(doc_id, token, [("step", "封组 2/2 完成", None)])
        late = dialog.locator('[data-test="task-tail-row-3"]')
        expect(late).to_be_visible(timeout=3000)
        assert "封组 2/2 完成" in late.inner_text()

        # --- red anchor: the error frame is one click away and lands visible ---
        anchor = dialog.locator('[data-test="task-tail-error-anchor"]')
        expect(anchor).to_be_visible()
        anchor.click()
        error_row = dialog.locator('.task-tail-row.level-error')
        expect(error_row).to_have_count(1)
        assert "模型响应超时" in error_row.first.inner_text()

        # --- level chip filter keeps the ledger honest (hiding thinking drops the
        #     tail by exactly its one row; the second click restores the full four) ---
        dialog.locator('[data-test="task-tail-chip-thinking"]').click()
        expect(dialog.locator('[data-test^="task-tail-row-"]')).to_have_count(3)
        expect(dialog.locator('.task-tail-row.level-thinking')).to_have_count(0)
        dialog.locator('[data-test="task-tail-chip-thinking"]').click()
        expect(dialog.locator('[data-test^="task-tail-row-"]')).to_have_count(4)
        expect(dialog.locator('.task-tail-row.level-thinking')).to_have_count(1)

        # --- worst scale: 120-row ledger replays fully on reopen (cap 500) ---
        bulk = [("step", f"分片 {i} 解析完成", None) for i in range(TAIL_ROWS_WORST - 3)]
        _seed_events(doc_id, token, bulk)
        dialog.locator('[data-test="extract-close-btn"]').click()
        expect(dialog).to_be_hidden(timeout=10000)
        page.goto(f"{page.url.split('#')[0]}#/documents?projectId={pid}",
                  wait_until="domcontentloaded")
        dialog = open_extract_dialog(page, name)
        # seeded window replays from cursor 0: all 121 frames present under the cap
        expect(dialog.locator('[data-test^="task-tail-row-"]')).to_have_count(
            3 + 1 + (TAIL_ROWS_WORST - 3), timeout=15000)

        # --- degraded road: abort the stream door, the poll road keeps growing ---
        page.route("**/extraction-events/stream**", lambda route: route.abort())
        _seed_events(doc_id, token, [("step", "断线后仍可送达", None)])
        recovered = dialog.locator('[data-test^="task-tail-row-"]')
        expect(recovered).to_have_count(TAIL_ROWS_WORST + 2, timeout=15000)
        page.unroute("**/extraction-events/stream**")

        assert_no_errors(collectors, "T577 process tail drawer")
        assert_no_browser_issues(collectors["browser_issues"], "T577 process tail drawer")
    finally:
        if doc_id is not None:
            sql(f"DELETE FROM tp_ai_task_event WHERE job_kind='doc-extract' AND job_ref='{doc_id}'")
            sql(f"DELETE FROM tp_document WHERE id={doc_id}")
        if pid is not None:
            token_admin = api_login("admin", ADMIN_PASSWORD)
            residue = sql(
                f"SELECT count(*) FROM tp_ai_task_event WHERE job_ref IN"
                f" (SELECT id::text FROM tp_document WHERE project_id={pid})").strip()
            assert residue == "0", f"tail fixture residue left in the event ledger: {residue}"
