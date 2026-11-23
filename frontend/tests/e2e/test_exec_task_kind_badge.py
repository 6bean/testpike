"""Task-kind badge + pressure-run deep link on the execution-center trial table.

Closes the read line shipped by the backend segment (core surfaces taskType and,
for a pressure row, the pinned perfRunId re-read from the dispatch envelope) and
the front segment (ExecTaskTypeBadge fed by the single-source EXEC_TASK_TYPE
vocabulary). Three spectra:

- A task_type=5 row whose envelope pins an existing run renders a clickable
 badge that lands on /perf/run/:id carrying the ledger-shaped projectId query
 handoff (the run detail page must actually open).
- A dirty type-5 envelope (no positive runId) renders the plain badge — honest
 absence, never a broken link (zero click target).
- A non-pressure row (task_type=4) shows its own wording from the same
 dictionary and is never link-shaped (vocabulary single source across kinds).

Rows are SQL-built fixtures (data prep only; the surface under test is the real
admin->core exec-task list endpoint plus the browser). Terminal statuses keep
the live executor out of the race. Cleanup purges the throwaway project.
"""

import json
import re
import time

import pytest
from conftest import (assert_no_errors, inject_token, nav_to,
                      pick_project_in_header, sql_query)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(180)

# Pressure envelope shaped like the perf dispatch assembly (kind+runId+engine).
_ENVELOPE_OK = '{"kind":"perf-run","runId":@RUN@,"engine":"K6","artifact":"a.zip"}'
_ENVELOPE_DIRTY = '{"kind":"perf-run","engine":"K6"}'


def _mk_task(pid, case_id, *, task_type, action_json, schema="perf-v1"):
    """Insert one terminal exec-task row (fixture path; not the tested chain)."""
    escaped = action_json.replace("'", "''")
    sql_query(
        "INSERT INTO tp_exec_task (project_id, case_id, status, task_type, "
        "schema_version, action_json, created_at, updated_at) VALUES ("
        f"{pid}, {case_id}, 3, {task_type}, '{schema}', '{escaped}', now(), now())")
    return int(sql_query(
        f"SELECT id FROM tp_exec_task WHERE project_id = {pid} "
        "ORDER BY id DESC LIMIT 1"))


def _mk_perf_run(pid, scenario_id, snapshot):
    """Terminal pressure run row (report-side reads it; cleanup via project purge)."""
    return int(sql_query(
        "INSERT INTO tp_perf_run (scenario_id, project_id, status, pressure_snapshot, "
        "stop_reason, started_at, finished_at) VALUES ("
        f"{scenario_id}, {pid}, 4, '{snapshot.replace(chr(39), chr(39) * 2)}'::jsonb, "
        "'徽标链路夹具运行', now() - interval '20 seconds', now()) RETURNING id"
    ).splitlines()[0])


def test_exec_task_kind_badge_and_perf_deep_link(logged_in, api_client, collectors, uniq):
    page = logged_in
    pid = None
    try:
        # ---- real-API project/env/scenario, SQL run/task fixtures ----
        pid = api_client.post("/projects", json={
            "name": f"任务类型徽标_{uniq}", "code": f"KB{uniq.upper()[:6]}"}).json()["data"]
        eid = api_client.post(f"/projects/{pid}/envs", json={
            "name": f"徽标链路环境_{uniq}", "envType": 2,
            "baseUrl": "http://localhost:8083"}).json()["data"]
        sid = api_client.post(f"/perf/scenarios?projectId={pid}", json={
            "projectId": pid, "name": f"徽标链路场景_{uniq}", "envId": eid,
            "pressureModel": "CONSTANT_RPS",
            "pressureConfig": {"targetRps": 5, "durationSeconds": 15},
            "loginStrategy": "PER_VUSER", "driverLimit": None,
            "steps": [{"action": "http.request", "method": "GET",
                       "url": "http://localhost:8083/actuator/health"}],
            "slaJson": None}).json()["data"]
        # Launch-shaped pinned snapshot (same posture as the ceiling-card fixture).
        snapshot = json.dumps({
            "name": f"徽标链路夹具{uniq}",
            "pressureModel": "CONSTANT_RPS",
            "pressureConfig": json.dumps({"targetRps": 5, "durationSeconds": 15}),
            "loginStrategy": "PER_VUSER",
            "steps": '[{"action":"http.request","method":"GET",'
                     '"url":"http://localhost:8083/actuator/health"}]',
            "sla": {"p95Ms": 100},
        }).replace("'", "''")
        run_id = _mk_perf_run(pid, sid, snapshot)

        t_link = _mk_task(pid, 0, task_type=5,
                          action_json=_ENVELOPE_OK.replace("@RUN@", str(run_id)))
        t_dirty = _mk_task(pid, 0, task_type=5, action_json=_ENVELOPE_DIRTY)
        t_api = _mk_task(pid, 0, task_type=4, schema="apitest-v1",
                         action_json='{"kind":"api-test-run","runId":1}')

        # ---- open the execution center on this project segment ----
        inject_token(page)
        nav_to(page, "执行与试运行", "/execution", '[data-test="execution-root"]',
               submenu="执行测试")
        pick_project_in_header(page, f"任务类型徽标_{uniq}")
        table = page.locator('[data-test="exec-task-table"]')
        expect(table).to_be_visible(timeout=15000)
        # The throwaway project carries exactly these three rows — count is the
        # web-first wait for the load round-trip (no hard sleep, per E2E law).
        expect(table.locator("tbody tr")).to_have_count(3, timeout=15000)

        # ---- Spectrum 1: clean pressure row = clickable kind badge ----
        # Row anchor = the id cell's plain text (substring; ids are unique per row —
        # a regex \b boundary is unreliable against concatenated DOM text).
        row = table.locator("tbody tr").filter(has_text=f"#{t_link}").first
        expect(row).to_be_visible(timeout=10000)
        link = row.locator('[data-test="exec-task-type-perf-link"]')
        expect(link).to_be_visible(timeout=5000)
        expect(link).to_contain_text("压测")
        link.click()
        expect(page).to_have_url(
            re.compile(rf"#/perf/run/{run_id}\?projectId={pid}"), timeout=15000)
        expect(page.locator('[data-test="perf-run-page"]')).to_be_visible(timeout=15000)

        # ---- Spectrum 2: dirty pressure envelope = plain badge, zero click target ----
        page.go_back(wait_until="domcontentloaded")
        expect(table).to_be_visible(timeout=15000)
        expect(table.locator("tbody tr")).to_have_count(3, timeout=15000)
        row_dirty = table.locator("tbody tr").filter(has_text=f"#{t_dirty}").first
        expect(row_dirty).to_be_visible(timeout=10000)
        badge = row_dirty.locator('[data-test="exec-task-type-badge"]')
        expect(badge).to_be_visible(timeout=5000)
        expect(badge).to_contain_text("压测")
        assert row_dirty.locator('[data-test="exec-task-type-perf-link"]').count() == 0, \
            "dirty pressure envelope rendered a deep link (broken target?)"

        # ---- Spectrum 3: non-pressure kind from the same dictionary, never linked ----
        row_api = table.locator("tbody tr").filter(has_text=f"#{t_api}").first
        expect(row_api).to_be_visible(timeout=10000)
        expect(row_api.locator('[data-test="exec-task-type-badge"]')).to_contain_text("API 运行")
        assert row_api.locator('[data-test="exec-task-type-perf-link"]').count() == 0

        # ---- reload persistence: badges come off the real admin->core read line ----
        page.reload(wait_until="domcontentloaded")
        expect(table).to_be_visible(timeout=15000)
        expect(table.locator("tbody tr")).to_have_count(3, timeout=15000)
        expect(table.locator("tbody tr").filter(has_text=f"#{t_link}").first
               .locator('[data-test="exec-task-type-perf-link"]')).to_be_visible(timeout=10000)

        assert_no_errors(collectors, "任务类型徽标与压测深链")
    finally:
        if pid:
            try:
                api_client.delete(f"/projects/{pid}/purge?force=true")
            except Exception:
                pass
            deadline = time.time() + 30
            while time.time() < deadline:
                if int(sql_query(
                        f"SELECT count(*) FROM tp_exec_task WHERE project_id = {pid}") or 0) == 0:
                    break
                time.sleep(2)
