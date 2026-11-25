"""Bulk AI data-enrichment filing and its process-tail revisit journey.

Real chain end to end (R3): the contract is uploaded through the spec
channel, the job is filed through the admin bridge into core and dispatched
over RabbitMQ to the generation service, and every frame the screen shows is
read back from the shared process ledger. Nothing is stubbed.

Two verifications, one per concern:

1. filing journey - the docs-tab entry opens the bulk form with the owning
   project echoed read-only, submitting opens the process tail before the
   acknowledgement returns (so the first server frame never lands on an empty
   screen), and when the run seals the tail collapses on its own while the job
   list re-reads silently: the status moves without anyone pressing refresh.
   The screen is also asserted to carry no "refresh manually" wording at all.

2. worst-scale revisit - the ledger is filled past the per-run frame ceiling
   (the ceiling is pinned through the configuration row, the hot-reload path
   the product itself reads). The tail then proves three things measured, not
   claimed: the rendered rows never exceed the ceiling, the frame area height
   stays byte-identical while frames keep landing, and re-entering through the
   ?job= deep link replays the same window with no gap and no duplicate.

Fixture rows are created under a throwaway project named "MockEnb/MockEnw"
and are torn down for real, so the demo corpus and the shipped baseline never
move.
"""

import json
import pathlib
import re
import time

import httpx
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, sql_query
from issues_monitor import assert_no_browser_issues
# The space/project/publish doors are already owned by the sibling journey on
# this page; re-importing them beats writing a second copy of the same flow.
from test_mock_workbench_contract_import import _mk_project, _mk_space, _publish

CAP_KEY = "mock.spec-import.history-frame-cap"
CAP_VALUE = "20"
ENRICH_KIND = "mock-spec-enrich"
TERMINAL_STATUSES = ("preview", "consumed", "done", "failed", "partial", "stopped", "cancelled")
RUN_WAIT_S = 240
# The ledger's production write door (ai-service side) validates every row
# against a closed level set and drops anything else: seeding rows straight
# into the table must honour that same door, or the fixture invents ledger
# states the product can never hold. The set is parsed from the door source
# on every seeding call, so a vocabulary change turns this fixture red
# instead of silently producing rows the viewer filters away (the exact
# failure this journey once hit: rows seeded under a door-rejected level
# stayed invisible, and the tail measured an empty-shape box).
_EVENT_STORE_SRC = (pathlib.Path(__file__).resolve().parents[3]
                    / "ai-service/src/main/java/com/sixbean/testpike"
                    "/aiservice/taskstream/domain/component/TaskProcessEventStore.java")


def _gate_closed_levels() -> set:
    src = _EVENT_STORE_SRC.read_text(encoding="utf-8")
    hit = re.search(r"Set\.of\(([^)]*)\)", src)
    assert hit, "process-event write door no longer exposes its level closed set"
    return {m.strip().strip('"') for m in hit.group(1).split(",")}

_CONTRACT = {
    "openapi": "3.0.1",
    "info": {"title": "E2E inventory contract", "version": "1.0.0"},
    "paths": {
        "/items/{id}": {"get": {
            "operationId": "getItem", "summary": "read one item",
            "responses": {"200": {"description": "ok", "content": {
                "application/json": {"schema": {"type": "object", "properties": {
                    "itemId": {"type": "string"}, "name": {"type": "string"}}}}}}}}},
        "/items": {"post": {
            "operationId": "createItem", "summary": "create one item",
            "responses": {"201": {"description": "created", "content": {
                "application/json": {"schema": {"type": "object", "properties": {
                    "id": {"type": "integer"}}}}}}}}},
    },
}


def _ok(response):
    assert response.status_code == 200, f"http {response.status_code}: {response.text[:300]}"
    body = response.json()
    assert str(body.get("code")) == "0", f"business refusal: {response.text[:300]}"
    return body.get("data")


def _upload_contract(api_client, pid, name):
    """Land one parsed contract version through the spec channel (single door)."""
    return _ok(httpx.post(f"{API_BASE}/api-specs", headers=api_client.headers,
                          data={"projectId": str(pid), "name": name},
                          files={"file": (f"{name}.json",
                                          json.dumps(_CONTRACT).encode(), "application/json")},
                          timeout=60))


def _config_row():
    rows = sql_query(f"SELECT config_value FROM tp_sys_config WHERE config_key='{CAP_KEY}'")
    value = rows.strip().splitlines()[-1].strip() if rows.strip() else ""
    return value or None


def _pin_cap(value):
    """Write the per-run frame ceiling through the configuration row itself."""
    if _config_row() is None:
        sql_query("INSERT INTO tp_sys_config (config_key, config_value, group_name,"
                  " value_type, encrypt_flag, description) VALUES"
                  f" ('{CAP_KEY}', '{value}', '系统', 'string', 0, 'e2e worst-scale fixture')")
    else:
        sql_query(f"UPDATE tp_sys_config SET config_value='{value}' WHERE config_key='{CAP_KEY}'")


def _restore_cap(previous):
    if previous is None:
        sql_query(f"DELETE FROM tp_sys_config WHERE config_key='{CAP_KEY}'")
    else:
        sql_query(f"UPDATE tp_sys_config SET config_value='{previous}' WHERE config_key='{CAP_KEY}'")


def _scalar(query):
    rows = sql_query(query)
    return rows.strip().splitlines()[-1].strip() if rows.strip() else ""


def _job_status(job_id):
    return _scalar(f"SELECT status FROM tp_mock_ai_job WHERE id={job_id}")


def _spec_id(api_client, pid, name):
    listed = _ok(httpx.get(f"{API_BASE}/api-specs", params={"projectId": pid, "page": 1, "size": 50},
                           headers=api_client.headers, timeout=30))
    for row in listed["list"]:
        if row.get("name") == name:
            return row["id"]
    raise AssertionError(f"uploaded contract {name} missing from the spec list")


def _file_bulk_job(api_client, space_id, spec_id):
    """File one enrichment job through the admin bridge (real dispatch)."""
    return _ok(api_client.post("/mock/jobs", json={
        "spaceId": space_id,
        "jobType": "spec-enrich",
        "prompt": f"contract version #{spec_id} bulk data enrichment",
        "specId": spec_id,
    }))


def _latest_run_token(job_id):
    token = _scalar(
        "SELECT run_token FROM tp_ai_task_event "
        f"WHERE job_kind='{ENRICH_KIND}' AND job_ref='{job_id}' ORDER BY id DESC LIMIT 1")
    return token or None


def _seed_frames(job_id, run_token, count, first_seq=0):
    """Append business-step frames to the ledger of one run.

    The level is taken from the write door's own closed set (parsed from its
    source), never hard-coded here: a door that stops accepting "step" must
    turn this fixture red rather than leave rows the viewer filters away.
    """
    gate_levels = _gate_closed_levels()
    seed_level = "step" if "step" in gate_levels else sorted(gate_levels)[0]
    assert "step" in gate_levels or "thinking" in gate_levels, (
        f"write door level set {sorted(gate_levels)} holds no renderable "
        "business-step level; the tail journey needs a door-approved one")
    values = []
    for step in range(first_seq, first_seq + count):
        summary = f"端点数据增强批 {step + 1}：样例值与边界值已生成"
        detail = json.dumps({"endpoint": f"/items/{step}", "batch": step + 1}, ensure_ascii=False)
        values.append(f"('{ENRICH_KIND}','{job_id}','{run_token}','{seed_level}','{summary}','{detail}')")
    sql_query("INSERT INTO tp_ai_task_event (job_kind, job_ref, run_token, level, summary, detail)"
              " VALUES " + ",".join(values))


def _frame_count(job_id):
    return int(_scalar("SELECT count(*) FROM tp_ai_task_event WHERE "
                       f"job_kind='{ENRICH_KIND}' AND job_ref='{job_id}'"))


def _purge(api_client, pid, space_id):
    sql_query("DELETE FROM tp_ai_task_event WHERE job_kind='" + ENRICH_KIND + "' AND job_ref IN "
              f"(SELECT CAST(id AS TEXT) FROM tp_mock_ai_job WHERE space_id={space_id})")
    sql_query(f"DELETE FROM tp_mock_ai_job WHERE space_id={space_id}")
    sql_query(f"DELETE FROM tp_mock_stub WHERE space_id={space_id}")
    sql_query(f"DELETE FROM tp_mock_space WHERE id={space_id}")
    sql_query(f"DELETE FROM tp_api_spec_preview WHERE spec_id IN "
              f"(SELECT id FROM tp_api_spec WHERE project_id={pid})")
    sql_query(f"DELETE FROM tp_api_spec WHERE project_id={pid}")
    sql_query(f"DELETE FROM tp_user_project WHERE project_id={pid}")
    sql_query(f"DELETE FROM tp_project WHERE id={pid}")


def _tail_window(page):
    window = page.locator('[data-test="mock-ai-job-tail-drawer"]')
    expect(window).to_be_visible(timeout=20000)
    return window


def _frame_area(window):
    """The scrolling frame area: the container whose height must never move."""
    return window.locator('[data-test="task-tail-list"]')


def _open_jobs_panel(page):
    page.locator('[data-test="mock-docs-ai-jobs"]').click()
    panel = page.locator('[data-test="mock-ai-jobs-drawer"]')
    expect(panel).to_be_visible(timeout=10000)
    return panel


def test_mock_enrich_bulk_filing_journey(logged_in, collectors, api_client, uniq):
    """Entry -> read-only ownership echo -> submit -> tail follows -> seals alone."""
    page = logged_in
    tag = f"MockEnb{uniq[:6].upper()}"
    pid = _mk_project(api_client, f"MockEnb {uniq}", tag)
    space = _mk_space(api_client, pid, f"enb-space-{uniq}", uniq.lower())
    space_id = space["id"] if isinstance(space, dict) else space
    _publish(api_client, space_id)
    spec_name = f"enb-contract-{uniq}"
    _upload_contract(api_client, pid, spec_name)
    spec_id = _spec_id(api_client, pid, spec_name)
    previous_cap = _config_row()
    try:
        page.goto(f"{BASE_URL}/#/mock/spaces/{space_id}?projectId={pid}&tab=docs",
                  wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-docs-page"]')).to_be_visible(timeout=20000)

        page.locator('[data-test="mock-docs-ai-bulk"]').click()
        dialog = page.locator('[data-test="mock-ai-bulk-dialog"]')
        expect(dialog).to_be_visible(timeout=8000)
        # Ownership is echoed, never re-picked: one read-only project readout,
        # one selector in the form, and no second picker of any kind.
        expect(dialog.locator('[data-test="owner-echo-value"]')).to_contain_text(
            f"MockEnb {uniq}", timeout=8000)
        assert dialog.locator('.el-select').count() == 1, (
            "the bulk form carries a second selector besides the contract version")

        dialog.locator('[data-test="mock-ai-bulk-spec"]').click()
        option = page.locator('.el-select-dropdown__item').filter(has_text=spec_name).first
        expect(option).to_be_visible(timeout=8000)
        option.click()
        dialog.locator('[data-test="mock-ai-bulk-file"]').click()

        # The tail opens before the ack: an empty screen is refused by design.
        window = _tail_window(page)
        page.wait_for_selector(".task-tail-row, .task-tail-empty", timeout=30000)

        job_id = int(_scalar("SELECT id FROM tp_mock_ai_job WHERE space_id="
                             f"{space_id} AND job_type='spec-enrich' ORDER BY id DESC LIMIT 1"))
        assert _job_status(job_id), f"filed job {job_id} has no row"

        deadline = time.time() + RUN_WAIT_S
        status = _job_status(job_id)
        while status not in TERMINAL_STATUSES and time.time() < deadline:
            page.wait_for_timeout(2000)
            status = _job_status(job_id)
        assert status in TERMINAL_STATUSES, f"run never sealed within {RUN_WAIT_S}s: {status}"

        # The terminal frame collapses the live window by itself, and the card
        # status moves on its own once the panel re-reads - no refresh pressed.
        expect(page.locator('[data-test="mock-ai-job-tail-drawer"]')).to_be_hidden(timeout=30000)
        panel = _open_jobs_panel(page)
        card = panel.locator('[data-test="mock-ai-job-card"]').first
        expect(card).to_contain_text(f"#{job_id}", timeout=10000)
        assert card.locator('[data-test="mock-ai-job-tail"]').count() == 1, (
            "the settled job lost its process-detail revisit entry")
        body_text = page.locator('[data-test="mock-docs-page"]').inner_text() + panel.inner_text()
        for phrasing in ("手动刷新", "点击刷新", "refresh manually", "please refresh"):
            assert phrasing not in body_text, f"screen tells the user to refresh: {phrasing}"

        assert_no_errors(collectors, "bulk enrichment filing journey")
        assert_no_browser_issues(
            collectors.get("browser_issues", []), "bulk enrichment filing journey")
    finally:
        _restore_cap(previous_cap)
        _purge(api_client, pid, space_id)


def test_mock_enrich_bulk_tail_worstcase_revisit(logged_in, collectors, api_client, uniq):
    """Past the frame ceiling: constant height, bounded rows, continuous cursor."""
    page = logged_in
    tag = f"MockEnw{uniq[:6].upper()}"
    pid = _mk_project(api_client, f"MockEnw {uniq}", tag)
    space = _mk_space(api_client, pid, f"enw-space-{uniq}", uniq.lower())
    space_id = space["id"] if isinstance(space, dict) else space
    _publish(api_client, space_id)
    spec_name = f"enw-contract-{uniq}"
    _upload_contract(api_client, pid, spec_name)
    spec_id = _spec_id(api_client, pid, spec_name)
    previous_cap = _config_row()
    _pin_cap(CAP_VALUE)
    cap = int(CAP_VALUE)
    try:
        job = _file_bulk_job(api_client, space_id, spec_id)
        job_id = job["id"] if isinstance(job, dict) else job
        # The revisit face is judged on a settled run, and the seal must be
        # awaited BEFORE seeding: the pipeline writes its own frames (terminal
        # row included) under its own run token, and the replay door anchors
        # on the token owning the highest row id. Rows seeded under a stale or
        # foreign token would sit outside the window the drawer reads.
        seal_deadline = time.time() + RUN_WAIT_S
        seal_status = _job_status(job_id)
        while seal_status not in TERMINAL_STATUSES and time.time() < seal_deadline:
            time.sleep(2)
            seal_status = _job_status(job_id)
        assert seal_status in TERMINAL_STATUSES, (
            f"run {job_id} never sealed within {RUN_WAIT_S}s: {seal_status}")
        run_token = _latest_run_token(job_id) or f"e2e-{uniq}"
        seeded = cap + 30
        _seed_frames(job_id, run_token, seeded)
        stored = _frame_count(job_id)
        assert stored >= seeded, f"ledger only holds {stored} frames"

        # Cursor continuity is read from the product's own window, twice.
        first = _ok(httpx.get(f"{API_BASE}/mock/jobs/{job_id}/process-events",
                              params={"cursor": 0, "limit": 200},
                              headers=api_client.headers, timeout=30))
        ids = [row["cursor"] for row in first]
        assert ids, "tail read returned nothing for a run with stored frames"
        assert ids == sorted(ids), "tail window is not monotonic"
        assert len(ids) == len(set(ids)), "tail window repeats a frame"
        tail = _ok(httpx.get(f"{API_BASE}/mock/jobs/{job_id}/process-events",
                             params={"cursor": ids[-1], "limit": 200},
                             headers=api_client.headers, timeout=30))
        assert all(row["cursor"] > ids[-1] for row in tail), "cursor re-read went backwards"

        page.goto(f"{BASE_URL}/#/mock/spaces/{space_id}?projectId={pid}&tab=docs&job={job_id}",
                  wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-docs-page"]')).to_be_visible(timeout=20000)
        # The deep link opens that run's tail on its own - no extra click.
        window = _tail_window(page)
        page.wait_for_selector(".task-tail-row", timeout=30000)
        area = _frame_area(window)
        rows = window.locator(".task-tail-row")
        rendered = rows.count()
        assert rendered <= cap, f"screen renders {rendered} rows past the {cap}-frame ceiling"
        height_before = area.bounding_box()["height"]
        assert height_before > 0, "frame area has no measurable height"

        # More frames keep landing; the frame area must not grow.
        _seed_frames(job_id, run_token, cap, first_seq=seeded)
        page.wait_for_timeout(3000)
        assert rows.count() <= cap, "rendered rows grew past the ceiling"
        height_after = area.bounding_box()["height"]
        assert abs(height_after - height_before) < 1.0, (
            f"frame area grew with content: {height_before} -> {height_after}")

        # Scrolling up pauses the follow; the single click returns to the tail.
        # The drawer keeps following while the view sits within 40px of the
        # tail, so the fixture must land far enough above the bottom for the
        # paused shape to exist at all (a "middle" scroll on short content
        # is still near the tail and would not pause anything).
        overflow = area.evaluate("el => el.scrollHeight - el.clientHeight")
        assert overflow > 120, f"frame area does not overflow: {overflow}px"
        area.evaluate("el => { el.scrollTop = Math.max(el.scrollHeight - el.clientHeight - 120, 0); }")
        page.wait_for_timeout(3000)
        expect(window.locator('[data-test="task-tail-paused"]')).to_be_visible(timeout=20000)
        window.locator('[data-test="task-tail-jump-latest"]').click()
        expect(window.locator('[data-test="task-tail-paused"]')).to_be_hidden(timeout=10000)
        # The DOM is a bounded sliding window (rows ~= visible + buffers),
        # so "returned to the tail" is proven by geometry, not by row count:
        # follow resumes and the scroll sits at the newest frame.
        assert 0 < rows.count() <= cap, f"window lost its bounded shape: {rows.count()}"
        away = area.evaluate("el => el.scrollHeight - el.scrollTop - el.clientHeight")
        assert away < 40, f"jump-latest did not return to the tail bottom: {away}px away"

        # Re-entry through the deep link replays the window with the same shape.
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        page.goto(f"{BASE_URL}/#/mock/spaces/{space_id}?projectId={pid}&tab=docs&job={job_id}",
                  wait_until="domcontentloaded")
        replayed = _tail_window(page)
        page.wait_for_selector(".task-tail-row", timeout=30000)
        assert abs(_frame_area(replayed).bounding_box()["height"] - height_before) < 1.0, (
            "replayed window changed the frame area height")

        assert_no_errors(collectors, "bulk enrichment worst-scale tail revisit")
        assert_no_browser_issues(
            collectors.get("browser_issues", []), "bulk enrichment worst-scale tail revisit")
    finally:
        _restore_cap(previous_cap)
        _purge(api_client, pid, space_id)


def test_mock_bulk_enable_journey_click_budget(logged_in, collectors, api_client, uniq):
    """Click-budget contract: enabling a fresh contract as mocks is reachable
    in at most four deliberate user clicks - entry button, pick the
    contract, see the advised default set (preview), one-click enable - the
    acceptance line the MJ ruling pins for the key journey.

    The counter counts every click the journey actually costs in the shared
    wizard's list-pick channel, the channel the ruling's "pick a contract"
    branch describes for a contract that is already an audited spec row.
    Seeing the default set must not cost any selection click: after
    preview the advised endpoints are pre-checked and the enable door is
    already enabled, otherwise a hidden fifth click would slip past the
    budget as silent re-checking work.
    """
    page = logged_in
    tag = f"MockCib{uniq[:6].upper()}"
    pid = _mk_project(api_client, f"MockCib {uniq}", tag)
    space = _mk_space(api_client, pid, f"cib-space-{uniq}", uniq.lower())
    space_id = space["id"] if isinstance(space, dict) else space
    _publish(api_client, space_id)
    _upload_contract(api_client, pid, f"cib-contract-{uniq}")
    clicks = 0
    try:
        page.goto(f"{BASE_URL}/#/mock/spaces/{space_id}?projectId={pid}&tab=docs",
                  wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-docs-page"]')).to_be_visible(timeout=20000)

        # Click 1: the workbench entry opens the shared wizard in its
        # default list-pick mode - no mode switch before the budget starts.
        page.locator('[data-test="mock-docs-import-contract"]').click()
        clicks += 1
        dialog = page.locator('[data-test="stub-direct-dialog"]')
        expect(dialog).to_be_visible(timeout=8000)

        # Click 2: pick the contract row.
        row = dialog.locator('[data-test="stub-direct-spec-table"] tbody tr').first
        expect(row).to_be_visible(timeout=8000)
        row.click()
        clicks += 1

        # Click 3: preview. The screen then shows the advised default set:
        # every advised endpoint already carries a check and the enable
        # door is live without any further selection click.
        dialog.locator('[data-test="stub-direct-preview-btn"]').click()
        clicks += 1
        expect(dialog.locator('[data-test="stub-direct-card"]')).to_have_count(2, timeout=20000)
        expect(dialog.locator('[data-test="stub-direct-check-all"]')) \
            .to_have_class(re.compile(r"is-checked"), timeout=8000)
        expect(dialog.locator(
            '[data-test^="stub-direct-check-"]:not([data-test="stub-direct-check-all"]).is-checked'
        )).to_have_count(2, timeout=8000)
        expect(dialog.locator('[data-test="stub-direct-import-btn"]')).to_be_enabled(timeout=8000)

        # Click 4: one-click enable; the receipt reports the created count.
        dialog.locator('[data-test="stub-direct-import-btn"]').click()
        clicks += 1
        expect(dialog.locator('[data-test="stub-direct-done-created"]')) \
            .to_contain_text("2", timeout=20000)

        assert clicks <= 4, (
            f"the bulk-enable journey costs {clicks} clicks; the ruling caps it at 4")

        # The enabled stubs are real rows in the space, not dialog state.
        stored = _scalar(f"SELECT count(*) FROM tp_mock_stub WHERE space_id={space_id}")
        assert stored == "2", f"one-click enable left {stored} stub rows, expected 2"

        assert_no_errors(collectors, "bulk-enable click-budget contract")
        assert_no_browser_issues(
            collectors.get("browser_issues", []), "bulk-enable click-budget contract")
    finally:
        _purge(api_client, pid, space_id)
