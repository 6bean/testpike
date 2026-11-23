"""Process-event tail drawer on the batch detail face (R62 B-face browser half).

What this pins for the operator: the moment a queued batch is opened on its
detail page the shared cursor window attaches on its own — no click, no
manual refresh. Kickoff/progress/terminal frames replay from the real
ledger (admin 8681 -> core 8683 -> tp_ai_task_event, kind batch-exec) with
the production level set (step + error), the
window grows live while its height stays constant, scrolling up pauses the
follow with a jump-back control, and the sealed terminal frame collapses the
window by itself. Server-side frame production is proven by the core ledger
unit (BatchExecTailLedgerTest); this spectrum owns the browser half, same
posture as the extraction/steps-gen tail spectra (single-source helpers,
iron law 5d: every assertion unconditional).

The batch stays QUEUED for the whole ride: no executor runs on this chain's
slot, so nothing claims the generated task and the run cannot race the
fixture. Growth is seeded by SQL against the real batch id (same technique
as the steps-gen tail spectrum) so replay is deterministic without burning a
live run; the browser still rides the real doors end to end.
"""

import json
import re

from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors
from issues_monitor import assert_no_browser_issues
from test_f2_extraction import sql  # shared lane-aware psql single source

TAIL_ROWS_WORST = 120  # worst-scale replay; client ledger cap defaults to 400
BANNED_REFRESH_PHRASES = ["手动刷新", "刷新后重试", "刷新以查看", "刷新查看最新",
                          "稍后重开", "请以刷新", "请点击刷新"]


def _mk_dataset(api, uniq):
    """Real doors only: project + web env + one approved case with a real
    script row (skipped cases produce no task and an empty batch refuses to
    start). Returns (pid, bid, case_id)."""
    r = api.post("/projects", json={"name": f"批尾项目{uniq}", "code": f"BT{uniq[:6]}"})
    assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    r = api.post(f"/projects/{pid}/envs", json={
        "name": f"批尾环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8899", "endTypes": "web",
        "versionSnapshot": f"build-{uniq[:6]}"})
    assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
    env_id = r.json()["data"]
    r = api.post("/cases", json={
        "projectId": pid, "title": f"批尾用例{uniq[:6]}", "precondition": "无",
        "expectedResult": "符合预期", "priority": 1, "confidence": 2,
        "endType": "web", "status": 3, "phase": 2,
        "steps": [{"stepNo": 1, "description": "打开演示页", "isAssert": 1}]})
    assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    case_id = r.json()["data"]["id"]
    action = {"steps": [
        {"action": "goto", "url": "http://localhost:8899/demo_page.html"},
        {"action": "expect", "text": "欢迎"}]}
    sql("INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by) "
        f"VALUES ({case_id}, 'v2', '{json.dumps(action, ensure_ascii=False)}', 1, 1)")
    r = api.post("/batches", json={
        "projectId": pid, "name": f"批尾执行批{uniq[:6]}", "envId": env_id,
        "caseIds": [case_id]})
    assert r.json().get("code") == "0", f"建批失败: {r.text[:300]}"
    return pid, r.json()["data"]["id"], case_id


def _seed(batch_id, rows):
    """rows: list of (level, summary) appended in order (cursor = id).

    job_kind/job_ref/run_token follow the production writer exactly
    (BatchInternalController reads since(KIND_BATCH_EXEC, batchId, batchId,
    ...)); any other key is filtered out by the read door and the drawer
    would never grow. One multi-VALUES statement keeps insertion order and
    costs a single psql round trip.
    """
    values = ",".join(
        f"('batch-exec', '{batch_id}', '{batch_id}', '{level}', '{summary}', NULL)"
        for level, summary in rows)
    sql("INSERT INTO tp_ai_task_event (job_kind, job_ref, run_token, level, summary, detail) "
        f"VALUES {values}")


def test_batch_exec_tail_drawer(logged_in, api_client, collectors, uniq):
    """logged_in is the single-source browser door (real API login + token
    injection, page left on the app origin)."""
    page = logged_in
    pid = None
    bid = None
    try:
        pid, bid, _case = _mk_dataset(api_client, uniq)

        # --- real start door: QUEUED + the kickoff frame land server-side ---
        r = api_client.post(f"/batches/{bid}/start")
        body = r.json()
        assert body.get("code") == "0", f"启动失败: {r.text[:300]}"
        assert body["data"]["status"] == 2, f"启动未进入排队: {body['data']}"

        # --- attach: open detail and the window binds without any click ---
        page.goto(f"{BASE_URL}/#/batches/{bid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=15000)
        drawer = page.locator('[data-test="batch-detail-tail"]')
        expect(drawer).to_be_visible(timeout=15000)
        badge = page.locator('[data-test="batch-detail-tail-badge"]')
        expect(badge).to_contain_text("过程进行中", timeout=15000)
        expect(page.locator('[data-test="task-tail-row-0"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="task-tail-row-0"]')).to_contain_text(
            "批次执行已发起", timeout=5000)

        # --- constant-height contract (R62②: content growth never stretches
        #     the window): measure before the worst-scale replay ---
        h_before = drawer.bounding_box()["height"]

        rows = []
        for i in range(1, TAIL_ROWS_WORST + 1):
            # Levels follow the production writer (BatchFinalizeSupport emits
            # step frames plus error frames for failed cases only); a level
            # the drawer does not know would sit filtered out of the list.
            if i == 7:
                rows.append(("error", f"用例执行失败｜任务 {i}"))
            elif i % 3 == 0:
                rows.append(("step", f"用例执行通过｜任务 {i}｜耗时 120ms"))
            elif i % 5 == 0:
                rows.append(("step", f"用例已派发｜任务 {i}"))
            else:
                rows.append(("step", f"执行进度帧 {i}"))
        _seed(bid, rows)

        # --- live growth: the last replayed row reaches the tail window ---
        last = page.locator(f'[data-test="task-tail-row-{TAIL_ROWS_WORST}"]')
        expect(last).to_be_visible(timeout=20000)
        expect(badge).to_contain_text(f"· {TAIL_ROWS_WORST + 1}", timeout=10000)

        # height unchanged after 120 more frames; DOM rows stay windowed
        # (virtualised: never the full 121-row render)
        h_after = drawer.bounding_box()["height"]
        assert abs(h_after - h_before) <= 2, (
            f"tail window stretched with content: {h_before} -> {h_after}")
        dom_rows = page.locator('[data-test^="task-tail-row-"]').count()
        assert dom_rows <= 60, f"tail DOM is not windowed: {dom_rows} rows rendered"

        # --- red anchor: one click lands on the error frame ---
        anchor = page.locator('[data-test="task-tail-error-anchor"]')
        expect(anchor).to_be_visible(timeout=5000)
        anchor.click()
        expect(page.locator('[data-test="task-tail-row-7"]')).to_be_visible(timeout=8000)

        # --- scroll up pauses the follow; jump-back resumes it ---
        page.locator('[data-test="task-tail-list"]').evaluate("el => { el.scrollTop = 0 }")
        page.wait_for_timeout(400)
        expect(page.locator('[data-test="task-tail-paused"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="task-tail-jump-latest"]').click()
        expect(page.locator('[data-test="task-tail-paused"]')).to_have_count(0, timeout=5000)
        expect(last).to_be_visible(timeout=8000)

        # --- terminal seal: the real door closes the window, zero user touch
        #     (the follow is silent: no click between seal and close).
        #     The production writers land a seal frame only PAIRED with a
        #     terminal batch row: finalize advances the status then appends
        #     the frame (and writes the report snapshot inside the same
        #     guarded flow); the cancel door lands status 8 + the cancelled
        #     seal frame in one transaction. Hand-crafting that pair left a
        #     DONE-without-snapshot row — a state no door produces — and the
        #     view's silent post-seal re-read then legitimately fetched the
        #     report and hit the designed 404 door. The cancel door from
        #     QUEUED is the faithful terminal path for this claim-queue-free
        #     spectrum: status 8 keeps report/insight calls out of the face,
        #     and the tail collapses on the writer's own seal frame.
        r = api_client.post(f"/batches/{bid}/cancel")
        body = r.json()
        assert body.get("code") == "0", f"取消失败: {r.text[:300]}"
        expect(drawer).to_be_hidden(timeout=20000)
        expect(badge).to_be_hidden(timeout=10000)

        # --- R62④: no refresh-command wording anywhere on the face ---
        body_text = page.inner_text("body")
        for phrase in BANNED_REFRESH_PHRASES:
            assert phrase not in body_text, f"refresh-command wording on screen: {phrase}"
        assert not re.search(r"刷新.{0,6}(查看|重试)", body_text), \
            "refresh-instruction sentence pattern leaked to the batch face"

        assert_no_errors(collectors)
        assert_no_browser_issues(collectors["browser_issues"])
    finally:
        if pid is not None:
            if "bid" in dir():
                try:
                    sql(f"DELETE FROM tp_ai_task_event WHERE job_kind = 'batch-exec' "
                        f"AND job_ref = '{bid}'")
                except Exception:
                    pass
            try:
                sql(f"UPDATE tp_exec_batch SET status = 1 WHERE project_id = {pid}")
            except Exception:
                pass
            api_client.delete(f"/projects/{pid}")
            api_client.delete(f"/projects/{pid}/purge?force=true")
            if "bid" in dir():
                left = sql("SELECT count(*) FROM tp_ai_task_event "
                           f"WHERE job_kind = 'batch-exec' AND job_ref = '{bid}'")
                assert left == "0", f"ledger residue after sweep: {left}"


# ``dir()`` guard note: bid exists only after the dataset step; the sweep is
# best-effort by design — a started batch that never reached the DB door has
# nothing to clean, and the zero-residue assert below only runs when bid set.
