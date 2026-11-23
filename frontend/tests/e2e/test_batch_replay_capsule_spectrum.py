"""Replay-capsule dock spectrum — TC-AO-22 replay half.

Proves the time-travel Q&A entry in a REAL browser on the REAL chain
(admin 8081 -> core 8083 -> MQ -> ai-service -> LiteLLM, no mocks):

 batch detail executed row -> "replay ask" capsule -> the dock opens with the
 replay question prefilled (task id named, NEVER auto-sent) -> Enter sends
 exactly the prefilled text -> the session create door stamps the capsule's
 executionId on the row's whitelist context, and the POST /stream body
 carries the SAME task id the row's execution history reports (pointer
 reconciles against the event flow, nothing else) ->
 the replay step-timeline block really rides the model prompt (proved through
 a unique action name that exists ONLY in the posted stepsTimeline) ->
 the turn seals with zero error frames in the server ledger ->
 the three zero-tolerance channels (4xx/5xx, console, Issues panel) stay clean.

Data posture (same as test_batch_insight_card.py): batch created through the
admin API, tasks seeded as already-claimed rows so the live executor never
touches them, results posted through the real core result door with a genuine
two-step timeline (step 1 ok, step 2 fail carrying the unique marker).

Cleanup: scratch project purged through the shared physical-cascade sweep.
"""
import json
import re

import httpx
import pytest
from playwright.sync_api import expect

from conftest import (
    BASE_URL,
    _set_sys_config_value,
    assert_no_errors,
    inject_token,
    pick_project_in_header,
    purge_project,
    seed_project,
    sql_query,
)

pytestmark = [pytest.mark.timeout(600), pytest.mark.xdist_group("ai-chat-exec-switch")]

CORE = "http://localhost:8083"

DOCK_INPUT = "[data-test=\"chat-dock-input\"]"


def _mk_case(api_client, pid, uniq, n):
    r = api_client.post("/cases", json={
        "projectId": pid, "title": f"回放用例{n}_{uniq}", "precondition": "无",
        "expectedResult": "符合预期", "priority": 1, "confidence": 2,
        "endType": "web", "status": 3,
        "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
    assert r.json().get("code") == "0", f"seed case failed: {r.text[:200]}"
    d = r.json()["data"]
    return int(d["id"] if isinstance(d, dict) else d)


def _mk_batch_terminal(api_client, pid, name, case_ids):
    """Batch via the real API, running(4) then terminal(6) as data prep — the
    live executor never sees it because tasks are seeded already-claimed."""
    r = api_client.post("/batches", json={"projectId": pid, "name": name, "caseIds": case_ids})
    assert r.json().get("code") == "0", f"seed batch failed: {r.text[:200]}"
    bid = int(r.json()["data"]["id"])
    sql_query(f"UPDATE tp_exec_batch SET status = 4, started_at = now() WHERE id = {bid}")
    return bid


def _seed_task(pid, cid, bid, token):
    out = sql_query("INSERT INTO tp_exec_task (project_id, case_id, batch_id, status, claim_token)"
                    f" VALUES ({pid}, {cid}, {bid}, 2, '{token}') RETURNING id")
    return int(out.splitlines()[0])


def _finalize_batch(bid):
    sql_query(f"UPDATE tp_exec_batch SET status = 6, finished_at = now() WHERE id = {bid}")


def _post_fail_with_timeline(task_id, token, marker):
    """Real core result door, WEB_DRIVER row shape (executor/driver/webdriver
    .py StepResult.to_dict: status + ISO startAt/endAt with a colonless +0800
    offset). ONLY step 2's action name carries the marker — it is physically
    absent from the case row, so an answer repeating it can only have come
    from the replay timeline block."""
    timeline = [
        {"stepNo": 1, "action": "打开登录页", "status": "ok",
         "startAt": "2026-10-31T08:00:01+0800", "endAt": "2026-10-31T08:00:03+0800",
         "detail": None, "screenshotKey": None},
        {"stepNo": 2, "action": f"点击提交按钮{marker}", "status": "fail",
         "startAt": "2026-10-31T08:00:04+0800", "endAt": "2026-10-31T08:00:12+0800",
         "detail": f"元素未找到 {marker}", "screenshotKey": None},
    ]
    body = {"taskId": task_id, "claimToken": token, "result": "fail",
            "stepsTimeline": timeline, "screenshots": [], "durationMs": 11000,
            "log": "e2e-replay-capsule", "errorType": 2,
            "errorDetail": f"e2e replay failure #{marker} at step 2"}
    r = httpx.post(f"{CORE}/internal/executors/tasks/result", timeout=20, json=body)
    assert r.status_code == 200, f"result door failed: {r.text[:200]}"


def _wait_round_sealed(page):
    expect(page.locator('[data-test="chat-dock-stop"]')).to_be_visible(timeout=30000)
    expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=240000)


def test_replay_capsule_prefills_pins_and_answers_from_the_timeline(page, collectors, api_client, uniq):
    """TC-AO-22 replay half: capsule -> prefill (task id) -> wire pointer ->
    timeline block reaches the model -> sealed round with no error frames."""
    marker = f"RP{uniq[:6]}"
    pid = seed_project(api_client, uniq, "RP")
    project_name = f"对话页RP{uniq}"
    token = uniq[:6].lower()
    case_id = _mk_case(api_client, pid, uniq, 1)
    bid = _mk_batch_terminal(api_client, pid, f"回放批{uniq}", [case_id])
    task_id = _seed_task(pid, case_id, bid, token)
    _post_fail_with_timeline(task_id, token, marker)
    _finalize_batch(bid)
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    stream_seen = []
    session_seen = []

    def _capture(req):
        # Secondary witnesses; the authoritative wait is page.expect_request.
        # (Playwright's sync API only dispatches event handlers while the
        # main thread sits inside a Playwright call — the previous pure-Python
        # sleep loop starved the dispatch and reported a stream request that
        # HAD fired as missing: a false red, field-proven twice.)
        if req.method != "POST":
            return
        if req.url.endswith("/stream") and "/ai/chat/sessions/" in req.url:
            stream_seen.append(req)
        elif req.url.rstrip("/").endswith("/ai/chat/sessions"):
            session_seen.append(req)

    try:
        inject_token(page)
        page.on("request", _capture)

        page.goto(f"{BASE_URL}/#/batches/{bid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-case-list"]')).to_be_visible(timeout=15000)

        # R30: the header is the only project segment — the dock refuses sends
        # by name until it lands on this batch's project.
        pick_project_in_header(page, project_name)
        expect(page.locator('[data-test="batch-case-list"]')).to_be_visible(timeout=15000)

        # The capsule sits on the executed row (terminal batch, not a draft).
        btn = page.locator(f'[data-test="batch-replay-{case_id}"]')
        expect(btn).to_be_visible(timeout=10000)
        btn.click()

        # Dock opens with the replay question prefilled, naming THIS task id —
        # and nothing is sent until the human presses Enter.
        expect(page.locator('[data-test="ai-chat-dock"]')).to_be_visible(timeout=5000)
        inp = page.locator(DOCK_INPUT)
        expect(inp).to_have_value(f"请回放执行任务 #{task_id}：按步骤时间线说明它停在哪一步、为什么失败或偏慢。",
                                  timeout=5000)
        assert page.locator('[data-test="chat-dock-stop"]').count() == 0, \
            "the capsule must prefill only — a pre-send stream request is a violation"

        # Send exactly the prefilled text with a deterministic instruction:
        # the answer may only repeat the marker, which lives in the timeline.
        question = inp.input_value() + (
            " 回答要求：输出失败步骤的步号与该步耗时毫秒数，并重复该步动作名结尾"
            "连续的中英文字母与数字代号，格式为「代号=<代号>;步骤=<数字>;耗时=<数字>」，"
            "禁止任何其他内容。")
        inp.fill(question)
        # Playwright-native wait: expect_request pumps the event loop while
        # the key lands (a pure-Python poll of the captured events starves
        # the dispatch and turns a fired request into a false red).
        with page.expect_request(
                lambda r: r.method == "POST" and r.url.endswith("/stream")
                and "/ai/chat/sessions/" in r.url, timeout=30000) as info:
            inp.press("Enter")
        stream_req = info.value
        assert stream_req.post_data is not None, "the stream request carries no body"
        body = json.loads(stream_req.post_data)
        # The capsule pointer rides the SESSION row's whitelist context (the
        # contract chatContextBuilder names: badge/replay doors stamp
        # executionId on create, later turns never re-stamp it). The stream
        # body carries the question; the create door carries the pointer.
        assert session_seen, "Enter sent but no session door was opened"
        wire_ctx = json.loads(json.loads(session_seen[-1].post_data)["contextJson"])
        assert wire_ctx.get("executionId") == task_id, \
            f"session pointer must equal the capsule task id {task_id}, got {wire_ctx.get('executionId')}"
        assert str(task_id) in (body.get("prompt") or ""), \
            "the prefilled prompt sent must name the pinned task verbatim"

        _wait_round_sealed(page)
        answer = page.locator('[data-test="chat-msg"][data-role="assistant"]').last.inner_text()
        # The timeline block is prompt-only: the step number and the 8000ms
        # duration exist NOWHERE the user message repeats — an answer that
        # reports them can only have read the replay timeline, and reports the
        # frame-locating pair the acceptance names (which step, how slow).
        cited = re.search(r"步骤\s*=\s*(\d+)[^\d]*耗时\s*=\s*(\d+)", answer)
        assert cited is not None, (
            f"the answer never cites the step/duration pair from the timeline "
            f"block (marker {marker!r} only lives in the posted stepsTimeline): {answer[:400]}")
        assert int(cited.group(1)) == 2, f"failed step must locate as frame 2, got {cited.group(1)}"
        assert int(cited.group(2)) == 8000, \
            f"step 2 duration must read 8000ms from the ISO stamps, got {cited.group(2)}"
        assert marker in answer, f"the answer must repeat the timeline marker: {answer[:300]}"

        # Server ledger reconciliation: this turn sealed with ZERO error frames
        # (a refused/degraded pointer would have landed an error frame).
        frames = sql_query(
            "SELECT f.event_type FROM tp_ai_chat_frame f "
            "JOIN tp_ai_chat_message m ON m.id = f.message_id "
            "JOIN tp_ai_chat_session s ON s.id = m.session_id "
            f"WHERE s.project_id = {pid} AND f.event_type = 'error'")
        assert frames == "", f"replay turn landed error frames in the ledger: {frames}"
        status = sql_query(
            "SELECT m.status FROM tp_ai_chat_message m "
            "JOIN tp_ai_chat_session s ON s.id = m.session_id "
            f"WHERE s.project_id = {pid} AND m.role = 'assistant' "
            "ORDER BY m.id DESC LIMIT 1")
        assert status.strip() == "done", \
            f"assistant turn must be sealed (done), ledger says {status!r}"

        assert_no_errors(collectors, "P5 replay capsule: prefill -> pointer -> timeline-backed answer")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        purge_project(api_client, pid)
