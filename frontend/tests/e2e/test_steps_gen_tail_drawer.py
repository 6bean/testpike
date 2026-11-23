""" S4 segment D close-ticket: process-event tail drawer on the script face.

What this pins for the operator: when a script-generation task is (re)generated
from a case, the same live tail drawer that already serves document extraction
attaches to the case script section immediately and keeps growing while the run
proceeds — per-group reasoning summaries, the merged-write confirmation and any
failure reason — over the real doors (admin 8081 -> core 8083 -> PostgreSQL
ledger tp_ai_task_event), with the poll road taking over when the SSE wire dies.

Server-side production of those frames for job_kind='steps-gen' is proven by the
L3 live window (the internal script-regen L3 window, task
2883 GREEN). This spectrum owns the browser half: attach timing, live growth,
red error anchor, level chips, worst-scale replay and the degraded road.

The regen POST is real (a real task row is created and the model run starts);
no LLM result is awaited. Extra ledger rows are seeded by SQL against that live
taskId so growth is deterministic without burning a second run — the browser
still rides the real endpoints. Every assertion below is unconditional (iron
law 5d: no if-count wrappers). Fixture data lives in a scratch project swept by
the single-source helper in finally, plus a zero-residue ledger check (R37:
shipped tables are never truncated).
"""

import os
import sys

import pytest
from conftest import assert_no_errors
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect

# Single-source scratch-project sweeper (R40): the shared verification helper,
# not a second copy of the FK walk inside this spec.
sys.path.insert(0, os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..", "tools", "verification")))
from _l3_db import sweep_scratch_project  # noqa: E402
from script_seed import base_doc, mk_case, mk_project, open_detail, save_doc  # noqa: E402
from test_f2_extraction import sql  # noqa: E402  (shared psql single source)

TAIL_ROWS_WORST = 120


def _seed(task_id, rows):
    """rows: list of (level, summary, detail) appended in order (cursor = id).

    run_token is the task id, exactly as the production writer stamps it
    (AiTaskServiceImpl.processEvents reads back
    since('steps-gen', taskId, taskId, ...) and the live ledger of the GREEN
    L3 window shows token=job_ref=2883). Any other token is filtered out by
    the read door and the drawer would never grow.
    """
    for level, summary, detail in rows:
        detail_sql = "NULL" if detail is None else f"'{detail}'"
        sql("INSERT INTO tp_ai_task_event"
            " (job_kind, job_ref, run_token, level, summary, detail)"
            f" VALUES ('steps-gen', '{task_id}', '{task_id}', '{level}',"
            f" '{summary}', {detail_sql})")


@pytest.fixture()
def _hard_headless():
    # The suite launcher pins --headless=new (iron law 5c: old headless reports
    # zero Audits issues = fake monitoring). Anchor kept for the report line.
    yield


def test_steps_gen_tail_drawer(logged_in, api_client, collectors, uniq, _hard_headless):
    """logged_in is the single-source browser door: real API login, token
    injection, page left on the app origin — which is what
    script_seed.open_detail derives the case URL from. A bare `page` sits on
    about:blank, so the "navigation" lands on about:blank#/cases/N: the app
    never boots, the script door is absent and the collectors stay empty
    (both red rounds of 2026-11-11, artifact stamps 215120/215337)."""
    page = logged_in
    pid = None
    task_id = None
    try:
        pid = mk_project(api_client, uniq, "steptail")
        case_id = mk_case(api_client, pid, uniq, "steptail")
        # The script section (and therefore the regeneration door) only renders
        # once the case owns a script: seed one through the core internal write
        # door (zero LLM, same helper the shell-editor spectrum uses).
        save_doc(case_id, base_doc(case_id, uniq))
        open_detail(page, case_id)

        # --- act on the real door: the regen POST hands back the taskId ---
        regen = page.locator('[data-test="detail-script-regen"]')
        expect(regen).to_be_visible(timeout=15000)
        expect(regen).to_be_enabled(timeout=15000)
        with page.expect_response(
                lambda r: "/script/regenerate" in r.url and r.status == 200,
                timeout=20000) as resp_info:
            regen.click()
        # The overwrite guard fires only for a hand-edited script
        # (generated_by=4, pinned by test_b12_regen_overwrite_guard_dialog).
        # This fixture seeds the AI initial state (generated_by=1), so the
        # dialog staying away IS the contract — asserted, not wrapped in a
        # conditional (iron law 5d).
        expect(page.locator(".el-message-box")).to_have_count(0, timeout=3000)
        resp = resp_info.value
        body = resp.json()
        task_id = (body.get("data") or {}).get("taskId") or (body.get("data") or {}).get("id")
        assert task_id, f"regen door returned no task handle: {body}"

        # --- attach: the drawer is bound the moment the task is ---
        drawer = page.locator('[data-test="task-tail-drawer"]')
        expect(drawer).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="task-tail-list"]')).to_be_visible()

        # --- live road: rows landed after the attach reach the DOM within 3s ---
        _seed(task_id, [
            ("step", "步骤组 1/3 推理确认环结束：共 1 轮、模型调用 2 次，已收敛", None),
            ("thinking", "分组 2/3 分析中", "chunks=4 tokens=2210"),
            ("error", "模型响应超时，已按预算回退", "cause=timeout after 900s"),
        ])
        late = page.locator('[data-test="task-tail-row-2"]')
        expect(late).to_be_visible(timeout=6000)

        # --- red anchor: the error frame is one click away and lands visible ---
        anchor = page.locator('[data-test="task-tail-error-anchor"]')
        expect(anchor).to_be_visible(timeout=10000)
        anchor.click()
        error_row = page.locator(".task-tail-row.level-error")
        expect(error_row).to_have_count(1)
        assert "模型响应超时" in error_row.first.inner_text()

        # --- level chips keep the ledger honest (hiding thinking drops exactly
        #     its own rows; the second click restores them) ---
        before = page.locator('[data-test^="task-tail-row-"]').count()
        page.locator('[data-test="task-tail-chip-thinking"]').click()
        expect(page.locator(".task-tail-row.level-thinking")).to_have_count(0)
        assert page.locator('[data-test^="task-tail-row-"]').count() < before
        page.locator('[data-test="task-tail-chip-thinking"]').click()
        expect(page.locator(".task-tail-row.level-thinking")).to_have_count(1)

        # --- worst scale: the hundred-row window renders in full, order kept
        _seed(task_id, [("step", f"分片 {i} 解析完成", None)
                        for i in range(TAIL_ROWS_WORST)])
        rows = page.locator('[data-test^="task-tail-row-"]')
        expect(rows).to_have_count(
            TAIL_ROWS_WORST + 3, timeout=20000)
        assert "分片 119 解析完成" in rows.last.inner_text()

        # --- degraded road: kill the stream door, the poll road keeps growing ---
        stream = f"**/api/v1/ai/tasks/{task_id}/process-events/stream**"
        page.route(stream, lambda route: route.abort())
        _seed(task_id, [("step", "断线后仍可送达", None)])
        expect(page.locator('[data-test^="task-tail-row-"]')).to_have_count(
            TAIL_ROWS_WORST + 4, timeout=20000)
        assert "断线后仍可送达" in page.locator(".task-tail-row").last.inner_text()
        page.unroute(stream)

        # --- reload: the seeded script is really persisted (reload half of the
        #     face). The drawer binds to the in-session task handle, so a reload
        #     unbinds it on purpose — cursor-0 replay on this face is covered by
        #     the L2 cursor-idempotency contract and the GREEN L3 window, not by
        #     pretending the handle survives a boot. Booked as a follow-up
        # candidate in PROGRESS .
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="detail-script-json"]')).to_be_visible(
            timeout=15000)
        assert "goto" in page.locator('[data-test="detail-script-json"]').inner_text()
        expect(page.locator('[data-test="task-tail-drawer"]')).to_have_count(0)

        assert_no_errors(collectors, "T594 steps-gen tail drawer")
        assert_no_browser_issues(collectors["browser_issues"], "T594 steps-gen tail drawer")
    finally:
        if task_id is not None:
            sql(f"DELETE FROM tp_ai_task_event WHERE job_kind='steps-gen' AND job_ref='{task_id}'")
        if pid is not None:
            residue = sql(
                "SELECT count(*) FROM tp_ai_task_event WHERE job_ref IN"
                f" (SELECT id::text FROM tp_ai_task WHERE project_id={pid})").strip()
            assert residue == "0", f"tail fixture residue left in the event ledger: {residue}"
            sweep_scratch_project(pid)
