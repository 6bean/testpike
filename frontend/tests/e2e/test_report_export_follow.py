# -*- coding: utf-8 -*-
"""R62 evidence window for the report export-task auto-follow surface (G face).

Real chain, zero mocks on the business path: one export is filed through the
board-tab dropdown on a project seeded past the 5000-row async threshold, so
the admin -> core path takes the async branch (tp_export_task status=1 ->
background toExcel + artifact storage -> 2, or 3 on storage failure). The
panel must (a) show the following badge while the task is in flight, (b) walk
the live row to its honest terminal without a single manual reload click,
(c) self-stop the badge once nothing is pending, (d) pick up 30 worst-scale
terminal task rows purely on the beat. Shared doors (project seeding) are
imported, never copied (R40 single source).
"""
import time
import uuid

import pytest
from playwright.sync_api import expect

pytestmark = [pytest.mark.timeout(600), pytest.mark.xdist_group("report-plane")]

from conftest import BASE_URL, assert_no_errors, sql_query
from test_t365b_mock_ai_drafting import _mk_project

BADGE = '[data-test="report-export-following"]'
PANEL = '[data-test="export-tasks"]'
ROW = PANEL + ' .el-table__body tr'
FORBIDDEN_WORD = "刷新"
EXEC_TYPE_LABEL = "执行结果统计"
PROCESSING_LABEL = "处理中"


def _seed_exec_rows(project_id, count=5001):
    """Push the execution-stat dataset past the async export threshold."""
    sql_query("INSERT INTO tp_exec_task (project_id, case_id, status) "
              f"SELECT {project_id}, gs, 3 FROM generate_series(1, {count}) gs")


def _seed_done_tasks(project_id, count=30):
    """Worst-scale fixture rows, already terminal (never worker-consumed)."""
    sql_query("INSERT INTO tp_export_task (project_id, report_type, masked, "
              f"status, row_count, finished_at) SELECT {project_id}, 'ai_quality', 0, 2, "
              f"7, now() FROM generate_series(1, {count}) gs")


def _task_status(task_id):
    return int(sql_query(f"SELECT status FROM tp_export_task WHERE id = {task_id}").strip())


def _live_row(page):
    """The async execution_stat row stays unique by its type label: the
    worst-scale fixture rows carry the ai_quality label instead."""
    return page.locator(ROW).filter(has_text=EXEC_TYPE_LABEL)


def test_report_export_follow_badge_progression_and_self_stop(logged_in, api_client, collectors):
    """Badge on while pending -> auto progression -> self-stop -> re-entry."""
    page = logged_in
    uniq = uuid.uuid4().hex[:8]
    pid = _mk_project(api_client, f"RefeFollow {uniq}", f"re{uniq}")
    try:
        _seed_exec_rows(pid)
        # worst-scale rows first (30 terminal task rows): the async task
        # can finish within a second, and the follow beat self-stops on an
        # all-terminal list — seeding rows later would then never be picked
        # up. With them already present, every frame is worst-scale.
        _seed_done_tasks(pid)
        page.goto(f"{BASE_URL}/#/report?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="report-page"]')).to_be_visible(timeout=15000)
        expect(page.locator(PANEL)).to_be_visible(timeout=10000)

        # ---- file one real async export through the dropdown ----
        page.locator('[data-test="report-export-dropdown"]').click()
        page.locator('.el-dropdown-menu__item').filter(has_text=EXEC_TYPE_LABEL).click()
        task_id = None
        try:
            # ---- door A: badge on, honest non-imperative wording ----
            badge = page.locator(BADGE)
            expect(badge).to_be_visible(timeout=8000)
            badge_text = (badge.text_content() or "").strip()
            assert FORBIDDEN_WORD not in badge_text, \
                f"badge falls back to a refresh instruction (R62 ④): {badge_text}"
            deadline = time.time() + 15
            while time.time() < deadline:
                rows = sql_query("SELECT id FROM tp_export_task WHERE project_id = "
                                 f"{pid} AND report_type = 'execution_stat' ORDER BY id DESC LIMIT 1").strip()
                if rows:
                    task_id = int(rows)
                    break
                time.sleep(0.5)
            assert task_id is not None, "async export task never landed in the task list"

            # ---- door B: worst scale, zero interaction: the 30 seeded
            # terminal rows plus the live one are all on screen ----
            expect(page.locator(ROW)).to_have_count(31, timeout=25000)
            expect(_live_row(page).first).to_be_visible(timeout=8000)

            # ---- door C: the live row walks to its honest terminal purely
            # on the beat; the DB stays the cross-check truth ----
            deadline = time.time() + 240
            status = 1
            while time.time() < deadline:
                status = _task_status(task_id)
                if status in (2, 3):
                    break
                time.sleep(3)
            assert status in (2, 3), f"task {task_id} never reached a terminal state"
            expect(_live_row(page).first).to_contain_text(
                "失败" if status == 3 else "完成", timeout=25000)

            # ---- door D: badge self-stops on an all-terminal list ----
            expect(page.locator(BADGE)).to_have_count(0, timeout=25000)

            # ---- door E: re-entry keeps truth and stays idle ----
            page.reload(wait_until="domcontentloaded")
            expect(page.locator(ROW)).to_have_count(31, timeout=15000)
            expect(page.locator(BADGE)).to_have_count(0, timeout=8000)
            expect(_live_row(page).first).to_contain_text(
                "失败" if status == 3 else "完成", timeout=8000)
            assert PROCESSING_LABEL not in page.locator(PANEL).inner_text(), \
                "a stale processing row survived re-entry"

            assert_no_errors(collectors, "report export auto-follow R62 window")
        finally:
            if task_id is not None:
                # storage artifacts are project-scoped; sweeping the project
                # rows leaves no orphan task entry for the next suite run
                sql_query(f"DELETE FROM tp_export_task WHERE project_id = {pid}; "
                          f"DELETE FROM tp_exec_task WHERE project_id = {pid}")
    finally:
        try:
            sql_query(f"DELETE FROM tp_export_task WHERE project_id = {pid}; "
                      f"DELETE FROM tp_exec_task WHERE project_id = {pid}; "
                      f"DELETE FROM tp_user_project WHERE project_id = {pid}; "
                      f"DELETE FROM tp_project WHERE id = {pid};")
        except Exception as exc:
            print(f"!! fixture sweep failed (manual check): {exc}")
