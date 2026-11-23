
import time
from datetime import datetime

import pytest
from chat_spectrum_support import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    park_data_moment_state,
    purge_project,
    seed_project,
    send_unambiguous,
    sql_query,
    wait_assistant_sealed,
    wait_streaming_message,
)
from playwright.sync_api import expect

# Shares the single-worker group of the ai-chat family (live streamed turns
# on the shared LLM loop).
pytestmark = [pytest.mark.timeout(420), pytest.mark.xdist_group("ai-chat-exec-switch")]

# A stamp with a half-hour offset so a timezone-coincidence cannot fake the
# pass: the expected label text is computed from this instant through the
# machine's own local timezone — the exact conversion the browser applies.
MOMENT_ISO = "2026-11-05T23:41:00+05:30"
EXPECTED_TEXT = datetime.fromisoformat(MOMENT_ISO).astimezone().strftime("%Y-%m-%d %H:%M")


def _open_dock(page, api_client, uniq, tag):
    pid = seed_project(api_client, uniq, tag)
    inject_token(page)
    page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
    return pid


def _last_assistant(page):
    return page.locator('[data-test="chat-msg"][data-role="assistant"]').last


def test_chat_data_moment_stamp_renders_as_of_label(page, collectors, api_client, uniq):
    """Park a retrieval state frame carrying the dataMoment meta -> the live
    answer block shows the formatted as-of mini-label."""
    pid = _open_dock(page, api_client, uniq, "DMMOMENT")
    try:
        send_unambiguous(page, f"探针自检（编号{uniq}）：请用一两句话说明你能提供哪些测试协助，"
                               "不要向我提问，也不要调用任何工具")
        sid, msg_id = wait_streaming_message(90, pid)
        park_data_moment_state(sid, msg_id, MOMENT_ISO)

        label = _last_assistant(page).locator('[data-test="chat-data-moment"]')
        expect(label).to_be_visible(timeout=60000)
        expect(label).to_contain_text(EXPECTED_TEXT)
        assert_no_errors(collectors, "parked dataMoment folds the as-of mini-label")

        wait_assistant_sealed(240, sid)
        assert_no_errors(collectors, "data-moment spectrum tail (sealed turn)")
    finally:
        purge_project(api_client, pid)


def test_chat_data_moment_meta_absent_never_renders(page, collectors, api_client, uniq):
    """A state frame without meta (untimed pool posture) renders no clock:
    the answer shows nothing instead of an invented timestamp."""
    pid = _open_dock(page, api_client, uniq, "DMNO-META")
    try:
        send_unambiguous(page, f"探针自检（编号{uniq}）：请用一两句话说明接口测试支持范围，"
                               "不要向我提问，也不要调用任何工具")
        sid, msg_id = wait_streaming_message(90, pid)
        park_data_moment_state(sid, msg_id, None)

        # The frame itself must reach the block before the absence is
        # meaningful: poll the ledger, then give the stream one settle beat.
        deadline = time.time() + 60
        landed = 0
        while time.time() < deadline:
            landed = int(sql_query("SELECT count(*) FROM tp_ai_chat_frame WHERE message_id="
                                   f"{msg_id} AND event_type='state'"))
            if landed >= 1:
                break
            time.sleep(0.5)
        assert landed >= 1, "the meta-less state frame never journalized"
        page.wait_for_timeout(1500)
        expect(_last_assistant(page).locator('[data-test="chat-data-moment"]')) \
            .to_have_count(0, timeout=10000)
        assert_no_errors(collectors, "meta-less state frame keeps the honest no-clock shape")

        wait_assistant_sealed(240, sid)
        assert_no_errors(collectors, "data-moment no-meta spectrum tail (sealed turn)")
    finally:
        purge_project(api_client, pid)


def test_chat_data_moment_never_bleeds_into_the_next_turn(page, collectors, api_client, uniq):
    """The stamp belongs to the turn whose retrieval folded it: a fresher
    streamed block shows no data-moment label."""
    pid = _open_dock(page, api_client, uniq, "DMBLEED")
    try:
        send_unambiguous(page, f"探针自检（编号{uniq}）：请用一两句话说明用例评审协助方式，"
                               "不要向我提问，也不要调用任何工具")
        sid, msg_id = wait_streaming_message(90, pid)
        park_data_moment_state(sid, msg_id, MOMENT_ISO)

        first = _last_assistant(page)
        expect(first.locator('[data-test="chat-data-moment"]')).to_be_visible(timeout=60000)
        wait_assistant_sealed(240, sid)
        assert_no_errors(collectors, "turn 1 owns the stamped label and seals")

        # Turn 2 streams in the SAME session without any parked stamp.
        send_unambiguous(page, "第二个话题：请再用一句话概括你的执行反馈能力，不要提问")
        _, msg2 = wait_streaming_message(90, pid)
        second = _last_assistant(page)
        expect(second.locator('[data-test="chat-data-moment"]')).to_have_count(0)
        wait_assistant_sealed(240, sid)
        assert_no_errors(collectors, "the next streamed turn never carries the old stamp")
        assert msg2 != msg_id
    finally:
        purge_project(api_client, pid)
