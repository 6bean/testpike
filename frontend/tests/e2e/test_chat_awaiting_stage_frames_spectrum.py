""" chat awaiting-state spectra: placeholder on send, stage frames under a
slow provider, and one-click resend after a real mid-stream break.

Reproduces the user report shape (case-detail deep link asking a question that
references {{DATA:...}} pool variables) against the provider-position stub in
its two verification doors (tools/mock-llm-chatstream.py):

1) slow-first-frame spectrum — the stub holds the whole answer behind a 20s
 silent wait (the measured grounding window of the report). The dock must
 show the awaiting placeholder within 500ms of send (asserted on in-page
 timestamps, not a network round-trip), must never render the empty-body
 placeholder word while the turn is unfinished, and the dwell sentence must
 escalate to the stage band. The server's own state frames (variable
 parsing -> retrieval -> model call) must land in the process list IN ORDER,
 journaled before the provider speaks supplies them exactly for
 this window);
2) stream-break + resend spectrum — the stub tears the TCP stream mid-answer
 on the first call only. The failed line must name the break class in
 readable words and offer the resend door; clicking it replays the ORIGINAL
 question as a NEW turn (the ledger keeps both records: two user rows),
 and the second call seals a real answer (turn+1).

Iron rules: real browser (--headless=new, zero-tolerance monitors), real
routing chain (project-bound llm config, no client mocks), hard assertions
only (rule 5d). Stub doors are per-instance env, so an existing sibling
spectrum's stream stays byte-identical.
"""
import json
import re
import time

import pytest
from conftest import (
    BASE_URL,
    _set_sys_config_value,
    assert_no_errors,
    core_base_url,
    inject_token,
    launch_provider_stub,
    purge_project,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = [pytest.mark.timeout(900), pytest.mark.xdist_group("ai-chat-exec-switch")]

EMPTY_BODY_WORD = "（正文为空）"
# The report's reproduction anchor question shape (case detail + pool variable).
ANCHOR_QUESTION = "基于当前这个用例，{{DATA:MAIN_BRAND_CONFIG_ADMIN}}我该如何配置？"


def _stub_hits_file(tag: str) -> str:
    return f"/tmp/testpike-t682-{tag}-hits.jsonl"


def _wire_stub(project_id: int, uniq: str, tag: str, port: int, env_extra: dict):
    """Bind the scratch project to a dedicated stub instance (routing chain
    identical to wire_chatstream_stub — this spectrum needs per-instance
    doors, so the port/env are parameters while the wiring stays the single
    /internal/llm-configs + tp_project.llm_config_id shape)."""
    import httpx
    import os
    hits = _stub_hits_file(tag)
    try:
        os.remove(hits)
    except FileNotFoundError:
        pass
    cfg = httpx.post(f"{core_base_url()}/internal/llm-configs",
                     headers={"X-Operator-Id": "1"}, timeout=10,
                     json={"name": f"对话占位态桩-{uniq}", "provider": "openai",
                           "modelName": "chatstream-stub", "apiKey": "e2e-st...cret",
                           "baseUrl": f"http://127.0.0.1:{port}/v1",
                           "status": 1, "isDefault": 0, "modelType": 1,
                           "description": "对话占位态与重发验证桩"}).json()
    assert cfg.get("code") == "0", f"llm config row creation failed: {cfg}"
    sql_query(f"UPDATE tp_project SET llm_config_id={int(cfg['data']['id'])} WHERE id={project_id}")
    return launch_provider_stub("mock-llm-chatstream.py", port,
                                {"MOCK_LLM_HITS": hits, **env_extra})


def _drop_llm_rows(project_id: int):
    rows = sql_query(f"SELECT llm_config_id FROM tp_project WHERE id={project_id}")
    if rows and str(rows).strip():
        cfg_id = int(str(rows).splitlines()[0].strip())
        if cfg_id:
            sql_query(f"DELETE FROM tp_llm_config WHERE id={cfg_id}")


def _open_dock_on_project(page, project_id: int):
    inject_token(page)
    page.goto(f"{BASE_URL}/#/ai/chat?projectId={project_id}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=15000)


def _send(page, text: str):
    # In-page timestamps survive the Playwright round-trip: sentAt is set by a
    # synchronous evaluate right before the key press; shownAt is stamped by a
    # MutationObserver armed at the same instant, so the 500ms budget measures
    # DOM appearance against the send, exactly as the acceptance line reads.
    page.evaluate(
        """() => {
            window.__t682 = { sentAt: performance.now() };
            const root = document.querySelector('[data-test="chat-dock-body"]');
            const ob = new MutationObserver(() => {
                if (!window.__t682.shownAt &&
                    document.querySelector('[data-test="chat-awaiting"]')) {
                    window.__t682.shownAt = performance.now();
                }
            });
            ob.observe(root, { childList: true, subtree: true });
        }"""
    )
    page.locator('[data-test="chat-dock-input"]').fill(text)
    page.locator('[data-test="chat-dock-input"]').press("Enter")


def _user_rows(project_id: int):
    return int(sql_query(
        f"SELECT count(*) FROM tp_ai_chat_message m JOIN tp_ai_chat_session s ON s.id=m.session_id "
        f"WHERE s.project_id={project_id} AND m.role='user'").strip())


def test_slow_first_frame_shows_placeholder_and_stage_frames(page, collectors, api_client, uniq):
    """Report anchors ①②③: placeholder ≤500ms, no empty-body word mid-turn,
    stage sentences escalate with the server's parsing/retrieval/model-call
    frames in order."""
    pid = _seed(api_client, uniq, "AWAIT1")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    stub = None
    try:
        stub = _wire_stub(pid, uniq, "slow", 18195,
                          {"MOCK_LLM_FIRST_FRAME_DELAY_S": "20"})
        _open_dock_on_project(page, pid)
        _send(page, ANCHOR_QUESTION)

        awaiting = page.locator('[data-test="chat-awaiting"]')
        expect(awaiting).to_be_visible(timeout=5000)
        elapsed_ms = page.evaluate("() => window.__t682.shownAt - window.__t682.sentAt")
        assert elapsed_ms <= 500, (
            f"awaiting placeholder appeared after {elapsed_ms:.0f}ms (>500ms budget)")

        # Sampling points across the 20s silent window: the empty-body word
        # must be absent while the turn is unfinished (render-word fence).
        for wait_ms in (300, 3000, 9000):
            page.wait_for_timeout(wait_ms)
            assert page.locator(f'text={EMPTY_BODY_WORD}').count() == 0, (
                f"empty-body word rendered mid-turn at ~{wait_ms}ms sampling point")

        # Stage band: the dwell sentence names the stage once the wait passes
        # the escalation line, and while the provider is still silent it must
        # carry the server's own newest state-frame sentence.
        stage = page.locator('[data-test="chat-awaiting-stage"]')
        expect(stage).to_be_visible(timeout=20000)
        dwell = stage.inner_text()
        assert "仍在处理" in dwell and "阶段" in dwell, f"dwell sentence shape: {dwell}"

        # The turn then seals (the 20s window elapses; answer streams).
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=180000)
        answer = page.locator('[data-test="chat-msg-md"]').last.inner_text()
        assert answer.strip() and EMPTY_BODY_WORD not in answer, f"sealed answer: {answer[:80]}"

        # Server state frames, in order, expanded in the process list.
        toggle = page.locator('[data-test="chat-process-toggle"]').last
        if "▸" in toggle.inner_text():
            toggle.click()
        kinds = page.locator('[data-test="chat-msg"]').last.locator(
            '.chat-process-item[data-kind="state"]')
        texts = [kinds.nth(i).inner_text() for i in range(kinds.count())]
        joined = "\n".join(texts)
        assert "变量解析中" in joined and "MAIN_BRAND_CONFIG_ADMIN" in joined, (
            f"variable-parsing frame missing from process list: {texts}")
        assert "检索完成" in joined, f"retrieval frame missing: {texts}"
        assert "模型调用中" in joined, f"model-call frame missing: {texts}"
        i_parse, i_retr, i_call = (joined.index("变量解析中"), joined.index("检索完成"),
                                   joined.index("模型调用中"))
        assert i_parse < i_retr < i_call, (
            f"stage frames out of order (parse={i_parse}, retr={i_retr}, call={i_call})")
        assert_no_errors(collectors, "chat awaiting slow-first-frame spectrum")
    finally:
        if stub is not None:
            stub.terminate()
            stub.wait(timeout=10)
        _drop_llm_rows(pid)
        purge_project(api_client, pid)


def test_stream_break_line_resends_as_new_turn(page, collectors, api_client, uniq):
    """Report anchor ④: a real mid-stream tear produces the break-class
    failure line with the resend door; the resend replays the original
    question as a NEW turn (two user rows, turn+1) and seals an answer."""
    pid = _seed(api_client, uniq, "AWAIT2")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    stub = None
    try:
        stub = _wire_stub(pid, uniq, "break", 18196,
                          {"MOCK_LLM_FIRST_FRAME_DELAY_S": "1",
                           "MOCK_LLM_BREAK_FIRST_CALLS": "1"})
        _open_dock_on_project(page, pid)
        _send(page, ANCHOR_QUESTION)

        err_line = page.locator('[data-test="chat-msg-error"]')
        expect(err_line).to_be_visible(timeout=120000)
        err_text = err_line.inner_text()
        assert re.search("中断|重发|再问", err_text), (
            f"failure line must name the break class and the next step: {err_text}")

        resend = page.locator('[data-test="chat-msg-resend"]')
        expect(resend).to_be_visible(timeout=5000)
        before = _user_rows(pid)
        assert before >= 1

        resend.click()
        # Second call streams normally: the dock seals the new turn.
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=180000)
        sealed = page.locator('[data-test="chat-msg"][data-role="assistant"]').last
        expect(sealed).to_contain_text("收到", timeout=30000)

        after = _user_rows(pid)
        assert after == before + 1, (
            f"resend must book a NEW turn (user rows {before} -> {after}, no double-count)")
        assert EMPTY_BODY_WORD not in sealed.inner_text(), "sealed line shows empty-body word"
        assert_no_errors(collectors, "chat stream-break resend spectrum")
    finally:
        if stub is not None:
            stub.terminate()
            stub.wait(timeout=10)
        _drop_llm_rows(pid)
        purge_project(api_client, pid)


def _seed(api_client, uniq, tag):
    """Scratch project through the real admin door (shared conftest helper)."""
    from conftest import seed_project
    return seed_project(api_client, uniq, tag)
