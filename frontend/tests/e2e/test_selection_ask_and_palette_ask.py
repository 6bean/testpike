"""Selection-ask (P1) and palette-ask (P6) dock spectra — TC-AO-19 + TC-AO-22 half.

Proves the two interaction entries of the AO batch in REAL browsers on the
REAL chain (admin 8081 -> core 8083 -> LiteLLM, no mocks):

TC-AO-19 (checked cases ride the dock):
  list page -> check exactly 3 seeded cases -> the floating "Ask AI" door
  parks them into the shared dock as reference chips (count = 3, kinds =
  case) -> the user edits the question and sends -> the POST /stream body
  carries refObjects with EXACTLY the checked ids (the whitelist rides the
  chips, nothing else) -> the chips are consumed on send (composer clean)
  -> the streamed answer names each referenced object (echo instruction).

TC-AO-22 half-spectrum (⌘K turns into one chat round; the camera/replay
  halves belong to the multimodal task and are out of this file):
  Ctrl+K opens the palette -> typing a question pins the "Ask AI" row ->
  clicking it closes the palette and prefills the dock composer WITHOUT
  auto-sending (no stream request fired) -> Enter on the composer element
  sends exactly the prefilled text -> one sealed round answers back.

Iron rules: exec gate hot-enabled and restored to the shipped default (on);
scratch projects purged through the shared physical-cascade sweep; every
phase ends with the 4xx/5xx + console + Issues-panel zero-tolerance check.
"""
import json
import pytest
from conftest import (
    BASE_URL,
    _set_sys_config_value,
    assert_no_errors,
    inject_token,
    purge_project,
    seed_project,
)
from playwright.sync_api import expect

pytestmark = [pytest.mark.timeout(600), pytest.mark.xdist_group("ai-chat-exec-switch")]


def _seed_case(api_client, pid, title):
    """Create one real case through the admin door and return its id."""
    r = api_client.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "已登录",
        "expectedResult": "被逐条点名", "priority": 2, "confidence": 3,
        "endType": "web", "status": 1,
        "steps": [{"stepNo": 1, "description": "打开列表", "isAssert": 0}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"seed case failed: {r.text[:200]}"
    data = r.json()["data"]
    return data["id"] if isinstance(data, dict) else data


def _wait_round_sealed(page):
    """Streaming ends when the stop affordance disappears (sealed turn)."""
    expect(page.locator('[data-test="chat-dock-stop"]')).to_be_visible(timeout=30000)
    expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=240000)


def test_checked_cases_ask_the_dock_with_the_exact_set(page, collectors, api_client, uniq):
    """TC-AO-19: 3 checked cases -> chips -> stream body carries ONLY them."""
    pid = seed_project(api_client, uniq, "ASKSEL")
    token = uniq[:6].lower()
    titles = [f"引用对象{token}甲{suffix}" for suffix in ("一", "二", "三")]
    ids = [_seed_case(api_client, pid, t) for t in titles]
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/cases?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-table"]')).to_be_visible(timeout=15000)

        # Check exactly the three seeded rows via their selection checkboxes.
        rows = page.locator('[data-test="case-table"] .el-table__body tr.el-table__row')
        for t in titles:
            rows.filter(has_text=t).first.locator(".el-checkbox").click()

        # The floating door surfaces on a non-empty selection.
        expect(page.locator('[data-test="case-ask-ai"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="case-ask-ai"]').click()

        # The set parked into the shared dock as exactly three case chips.
        expect(page.locator('[data-test="ai-chat-dock"]')).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="chat-chip-case"]')).to_have_count(3, timeout=5000)
        assert_no_errors(collectors, "P1 selection ask: chips parked")

        inp = page.locator('[data-test="chat-dock-input"]')
        inp.click()
        inp.fill("请逐行原样复述我引用的每个对象的标题（共三条），每行一条，禁止输出其他内容。")
        with page.expect_request(
                lambda r: r.method == "POST" and r.url.endswith("/stream")) as req_info:
            inp.press("Enter")
        body = json.loads(req_info.value.post_data)
        refs = body.get("refObjects")
        assert isinstance(refs, list) and len(refs) == 3, \
            f"the whitelist must ride exactly the checked set, got {refs!r}"
        assert sorted(int(r["id"]) for r in refs) == sorted(ids), \
            "refObjects must carry exactly the checked case ids — nothing more, nothing less"
        assert all(r["kind"] == "case" for r in refs)

        # Chips are consumed by the send: the composer starts clean again.
        expect(page.locator('[data-test="chat-chips"]')).to_have_count(0, timeout=5000)

        _wait_round_sealed(page)
        answer = page.locator('[data-test="chat-msg"][data-role="assistant"]').last.inner_text()
        for t in titles:
            assert t in answer, f"the answer must name the referenced object {t!r}, got: {answer[:300]}"
        assert_no_errors(collectors, "P1 selection ask: sealed round names every object")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        purge_project(api_client, pid)


def test_palette_question_prefills_the_dock_and_closes_one_round(page, collectors, api_client, uniq):
    """TC-AO-22 (⌘K half): palette -> prefill without sending -> one round."""
    pid = seed_project(api_client, uniq, "ASKK")
    token = uniq[:6].lower()
    question = f"用一句话回答：只输出代号{token}，禁止其他内容"
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/cases?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-page"]')).to_be_visible(timeout=15000)

        page.keyboard.press("Control+k")
        expect(page.locator('[data-test="global-search-dialog"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="global-search-input"]').fill(question)

        # The pinned ask row appears the moment the input is non-blank.
        ask_row = page.locator('[data-test="global-search-ask-ai"]')
        expect(ask_row).to_be_visible(timeout=5000)
        ask_row.click()

        # Palette gone, dock open, composer prefilled — and NOTHING sent yet.
        expect(page.locator('[data-test="global-search-dialog"]')).not_to_be_visible()
        expect(page.locator('[data-test="ai-chat-dock"]')).to_be_visible(timeout=5000)
        inp = page.locator('[data-test="chat-dock-input"]')
        expect(inp).to_have_value(question, timeout=5000)
        assert page.locator('[data-test="chat-dock-stop"]').count() == 0, \
            "the palette ask must prefill, never auto-send"
        assert_no_errors(collectors, "P6 palette ask: composer prefilled")

        with page.expect_request(
                lambda r: r.method == "POST" and r.url.endswith("/stream")) as req_info:
            inp.press("Enter")
        body = json.loads(req_info.value.post_data)
        assert body.get("prompt") == question, \
            "the sent prompt must equal the prefilled palette question"

        _wait_round_sealed(page)
        answer_turn = page.locator('[data-test="chat-msg"][data-role="assistant"]').last
        expect(answer_turn).to_contain_text(token, timeout=15000)
        assert_no_errors(collectors, "P6 palette ask: one closed round")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        purge_project(api_client, pid)
