"""Real-chain spectrum for the process-tail drawer's stop door.

What this pins for the operator: a real script-regeneration run opens the
tail drawer, one click on the stop door settles the task as cancelled in the
ledger, and every late writeback door of the stopped run (running / done /
failed) answers a state-machine rejection, so nothing generated after the
stop can attach to the task or rewrite its history. The drawer keeps the
failure reason readable through the red anchor, and the stop door itself
leaves the header once the task is settled. All doors are real:
admin 8281 -> core 8283 -> PostgreSQL ledger on this chain's slot; no mocks.

Ledger proof of the three refused writeback branches is mirrored against the
same task row the browser stopped, so the UI half and the core half close on
one run (the L2 AiTaskCancelDoorTest owns the unit-level branches; this
spectrum owns the browser + internal-HTTP road end to end).

The regen POST is real (a real task row is created and the model run
starts); no LLM result is awaited — the run keeps going until the stop door
settles it. Extra ledger rows are seeded by SQL against that live taskId so
the error anchor is deterministic without burning a second run; the browser
still rides the real endpoints. Every assertion is unconditional (iron law
5d: no if-count wrappers). Fixture data lives in a scratch project swept by
the single-source helper in finally, plus a zero-residue ledger check (R37:
shipped tables are never truncated).
"""

import httpx
import os
import sys

import pytest
from conftest import assert_no_errors
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect

# Single-source helpers (R40): the shared verification sweeper and the
# script-seed doors, not a second copy of the FK walk or the seeding inside
# this spec.
sys.path.insert(0, os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..", "tools", "verification")))
from _l3_db import sweep_scratch_project  # noqa: E402
from script_seed import CORE, base_doc, mk_case, mk_project, open_detail, save_doc  # noqa: E402
from test_f2_extraction import sql  # noqa: E402  (shared psql single source)

# BizErrorCode.STATE_MACHINE_REJECT — the honest refusal a stopped run gets
# from every late writeback door (single source: common BizErrorCode).
STATE_MACHINE_REJECT = "40902"


def _seed(task_id, rows):
    """rows: list of (level, summary, detail) appended in order (cursor = id).

    run_token is the task id, exactly as the production writer stamps it
    (same contract the steps-gen tail drawer spectrum pins: any other token
    is filtered out by the read door and the drawer would never grow).
    """
    for level, summary, detail in rows:
        detail_sql = "NULL" if detail is None else f"'{detail}'"
        sql("INSERT INTO tp_ai_task_event"
            " (job_kind, job_ref, run_token, level, summary, detail)"
            f" VALUES ('steps-gen', '{task_id}', '{task_id}', '{level}',"
            f" '{summary}', {detail_sql})")


def _status(task_id):
    return sql(f"SELECT status FROM tp_ai_task WHERE id={task_id}").strip()


def _case_col(task_id):
    return sql(
        f"SELECT COALESCE(case_id::text, 'NULL') FROM tp_ai_task WHERE id={task_id}").strip()


def _post_door(client, task_id, door, body=None):
    """One internal writeback door probe; returns the envelope code."""
    resp = client.post(f"/internal/ai-tasks/{task_id}/{door}",
                       json=body if body is not None else {})
    return resp.json().get("code")


@pytest.fixture()
def _hard_headless():
    # The suite launcher pins --headless=new (iron law 5c: old headless
    # reports zero Audits issues = fake monitoring). Anchor kept for the
    # report line.
    yield


def test_tail_drawer_stop_door(logged_in, api_client, collectors, uniq, _hard_headless):
    """logged_in is the single-source browser door: real API login, token
    injection, page left on the app origin (see the steps-gen tail drawer
    spectrum for the about:blank failure this avoids)."""
    page = logged_in
    pid = None
    task_id = None
    try:
        pid = mk_project(api_client, uniq, "stoptail")
        case_id = mk_case(api_client, pid, uniq, "stoptail")
        # The script section (and the regeneration door) only renders once
        # the case owns a script: seed one through the core internal write
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
        resp = resp_info.value
        body = resp.json()
        task_id = (body.get("data") or {}).get("taskId") or (body.get("data") or {}).get("id")
        assert task_id, f"regen door returned no task handle: {body}"
        assert _status(task_id) in ("1", "2"), \
            f"a fresh regen run must be stoppable (1/2), got {_status(task_id)}"

        # --- attach: the drawer binds the moment the task is ---
        drawer = page.locator('[data-test="task-tail-drawer"]')
        expect(drawer).to_be_visible(timeout=10000)

        # --- failure reason stays readable on the drawer error face ---
        _seed(task_id, [
            ("step", "分组 1/3 推理确认环结束", None),
            ("error", "模型响应超时，已按预算回退", "cause=timeout after 900s"),
        ])
        anchor = page.locator('[data-test="task-tail-error-anchor"]')
        expect(anchor).to_be_visible(timeout=10000)
        anchor.click()
        error_row = page.locator(".task-tail-row.level-error")
        expect(error_row).to_have_count(1)
        assert "模型响应超时" in error_row.first.inner_text()

        # --- the stop door is on the live drawer, one click away ---
        stop = page.locator('[data-test="task-tail-stop"]')
        expect(stop).to_be_visible(timeout=10000)
        expect(stop).to_be_enabled(timeout=5000)

        # --- one click on the real road: admin -> core settles status 5 ---
        with page.expect_response(
                lambda r: f"/ai/tasks/{task_id}/cancel" in r.url and r.status == 200,
                timeout=20000) as cancel_info:
            stop.click()
        cancel_body = cancel_info.value.json()
        # The framework Response contract pins code as a string ("0" = success).
        assert cancel_body.get("code") == "0", \
            f"the cancel door answered a failure envelope: {cancel_body}"
        # Only the stop toast carries this text; the regen kickoff toast is
        # still fading, so a bare .el-message--success locator is ambiguous.
        expect(page.locator(".el-message--success").filter(
                has_text="任务已停止")).to_be_visible(timeout=8000)

        # --- ledger truth: the row is cancelled, not rewritten later ---
        assert _status(task_id) == "5", \
            f"stop must settle the row as cancelled(5), got {_status(task_id)}"

        # --- the stopped drawer honestly removes its stop door ---
        expect(page.locator('[data-test="task-tail-stop"]')).to_have_count(0, timeout=15000)

        # --- three late writeback doors all refuse, on the real HTTP road ---
        # A steps-regen task is created already bound to its source case
        # (case_id comes from the create door, not from the done writeback),
        # so the honest post-refusal invariant is "the row never moved":
        # same status, same case binding as before the probes.
        case_before = _case_col(task_id)
        with httpx.Client(base_url=CORE, timeout=10) as client:
            assert _post_door(client, task_id, "running") == STATE_MACHINE_REJECT, \
                "progress writeback on a stopped run must be refused"
            assert _post_door(client, task_id, "done", {"caseId": int(case_id)}) == \
                STATE_MACHINE_REJECT, \
                "a generated case must never attach to a stopped task"
            assert _post_door(client, task_id, "failed",
                              {"errorCode": "SERVICE_ERROR",
                               "errorMsg": "late failure after stop"}) == \
                STATE_MACHINE_REJECT, \
                "a late failure must not rewrite the stopped row"

        # ...and the refusals left the row untouched: still cancelled, still
        # bound to the very case it was created for (the done probe bounced).
        assert _status(task_id) == "5", "refused writebacks must leave status=5"
        assert _case_col(task_id) == case_before, \
            f"refused writebacks must not move the case binding: " \
            f"before={case_before} after={_case_col(task_id)}"

        # --- stopping twice is honest: the first result is kept (idempotent) ---
        with httpx.Client(base_url=CORE, timeout=10) as client:
            assert _post_door(client, task_id, "cancel") == "0", \
                "a repeated stop must answer success, not an error"
        assert _status(task_id) == "5", "a repeated stop must not move the row"

        assert_no_errors(collectors, "tail drawer stop door")
        assert_no_browser_issues(collectors["browser_issues"], "tail drawer stop door")
    finally:
        if task_id is not None:
            sql(f"DELETE FROM tp_ai_task_event WHERE job_kind='steps-gen' AND job_ref='{task_id}'")
        if pid is not None:
            residue = sql(
                "SELECT count(*) FROM tp_ai_task_event WHERE job_ref IN"
                f" (SELECT id::text FROM tp_ai_task WHERE project_id={pid})").strip()
            assert residue == "0", f"stop-door fixture residue left in the event ledger: {residue}"
            sweep_scratch_project(pid)
