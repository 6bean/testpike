"""Chat dock full-journey spectrum (BE batch closing package, .

One live browser session walks the whole dock promise the batch made, in the
order a user actually feels it — real chain (browser -> admin -> core), zero
mocks on the wire, one scratch pair of projects:

 1. pin: the fab opens the dock and one press parks the resident form
 (aria-pressed flips to true) so every later navigation keeps the panel
 open — the pin leg re-proves the dock-entry state door inside this
 journey, not as an isolated case;
 2. the ask-from waterline: on two DIFFERENT pages one question each is
 sent, and the composer hint above the input names the header project
 plus the CURRENT page label taken live from the tab title — the page
 token must change between the two stops while the project token stays,
 which is exactly the per-question anchoring the batch promises;
 3. the cross-project boundary: moving the header onto a second project
 does NOT silently clear the live thread (the assistant rows stay two
 — the defect this band was built to fix), the band names both sides,
 and the one-click door starts a clean conversation on the segment the
 user is standing on (thread zero, hint re-named);
 4. paste-to-answer honesty: a screenshot pasted into the fresh segment
 rides the SHARED upload door (the key is minted under that segment), the
 outbound /stream carries the server-issued pair as attachments, and
 the ledger books a real image-read call log row for that project —
 both halves (payload + provider booking) asserted, never one alone;
 5. the unread badge closes at zero: collapsing the dock mid-run does not
 interrupt the stream (v-show resident), the seal lands one count on
 the bubble, re-entering the session clears the anchor, and a reload
 proves the cleared anchor rode the local ledger rather than a render
 artifact.

Zero-tolerance collectors (4xx/5xx, console, Issues panel) gate the whole
journey; the paste payload helpers are IMPORTED from the paste spectrum,
never copied (single-source rule). The scratch projects and their call-log
rows are purged in the tail so the demo projects stay untouched.
"""
import re

import pytest
from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    pick_project_in_header,
    purge_project,
    seed_project,
    sql_query,
)
from playwright.sync_api import expect
from test_chat_paste_image_table_spectrum import PNG_B64, _paste, _send_and_capture_stream

# Shares the serialized ai-chat worker group: this journey reloads the page
# mid-case and rides the same config plane as the paste family.
pytestmark = [pytest.mark.timeout(420), pytest.mark.xdist_group("ai-chat-exec-switch")]

FAB = '[data-test="ai-chat-fab"]'
BADGE = '[data-test="chat-fab-badge"]'
DOCK = '[data-test="ai-chat-dock"]'
PIN = '[data-test="chat-dock-pin"]'
CLOSE = '[data-test="chat-dock-close"]'
INPUT = '[data-test="chat-dock-input"]'
HINT = '[data-test="chat-context-hint"]'
BOUNDARY = '[data-test="chat-project-boundary"]'
BOUNDARY_NEW = '[data-test="chat-project-boundary-new"]'
IMAGE_READY = '[data-test="chat-image-item-ready"]'
STOP = '[data-test="chat-dock-stop"]'
MSG_ASSISTANT = '[data-test="chat-msg"][data-role="assistant"]'

QUIET_PROMPT = "只回答两个字：收到"


def _tab_page_label(page):
    """Page name the tab title carries — the SAME resolved menu label the
    waterline hint renders, read live instead of hardcoded per page."""
    title = page.title()
    return title.split(" · testPike")[0].strip()


def _wait_sealed(page, count):
    """One turn sealed: the run door closed and exactly ``count`` assistant
    rows stand in the thread (hard count, never an if-wrap)."""
    expect(page.locator(STOP)).to_have_count(0, timeout=180000)
    expect(page.locator(MSG_ASSISTANT)).to_have_count(count, timeout=10000)


def _assert_hint_names(page, project_name):
    """The waterline line above the composer names the header project AND
    the page the user is standing on, taken from the live tab title."""
    expect(page.locator(HINT)).to_contain_text(project_name, timeout=10000)
    hint = page.locator(HINT).inner_text()
    label = _tab_page_label(page)
    assert re.search(rf"页面\s*{re.escape(label)}", hint), (
        f"waterline must name the current page {label!r}, hint reads: {hint!r}")
    return hint


def test_dock_full_journey_pin_pages_boundary_paste_badge(
        page, collectors, api_client, uniq):
    """Pin -> two pages with the waterline -> boundary one-click fresh start
    -> pasted picture booked end to end -> badge counts the hidden seal and
    closes at zero."""
    name_a = f"对话页JOURA{uniq}"
    name_b = f"对话页JOURB{uniq}"
    pid_a = seed_project(api_client, uniq, "JOURA", display_name=name_a)
    pid_b = seed_project(api_client, uniq, "JOURB", display_name=name_b)
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/cases?projectId={pid_a}", wait_until="domcontentloaded")
        expect(page.locator(FAB)).to_be_visible(timeout=15000)

        # 1) pin the dock: one press parks the resident form.
        page.locator(FAB).click()
        expect(page.locator(DOCK)).to_be_visible(timeout=10000)
        expect(page.locator(PIN)).to_have_attribute("aria-pressed", "false", timeout=5000)
        page.locator(PIN).click()
        expect(page.locator(PIN)).to_have_attribute("aria-pressed", "true", timeout=5000)

        # 2a) first stop: the cases page. Waterline names project A + this
        # page; one question sealed.
        hint_first = _assert_hint_names(page, name_a)
        page.locator(INPUT).fill(QUIET_PROMPT)
        page.locator(INPUT).press("Enter")
        _wait_sealed(page, 1)

        # 2b) second stop: navigate on while pinned — the dock survives the
        # page switch, the project token holds, the page token moves. The
        # pinned dock never re-mounts, so its visibility proves nothing about
        # the route; wait for the tags page root to render (the same committed
        # navigation repaints the tab title in the router afterEach) before
        # reading the waterline, or both reads catch the stale cases page.
        page.goto(f"{BASE_URL}/#/tags", wait_until="domcontentloaded")
        expect(page.locator(DOCK)).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="tag-list-page"]')).to_be_visible(timeout=15000)
        hint_second = _assert_hint_names(page, name_a)
        assert hint_second != hint_first, (
            "waterline must follow the page, both stops read the same line: "
            f"{hint_first!r}")
        page.locator(INPUT).fill(QUIET_PROMPT)
        page.locator(INPUT).press("Enter")
        _wait_sealed(page, 2)

        # 3) cross-project boundary: the header moves to project B while the
        # thread still owns A — nothing is cleared silently, the band names
        # both sides, one click starts clean on B.
        pick_project_in_header(page, name_b)
        expect(page.locator(BOUNDARY)).to_be_visible(timeout=10000)
        expect(page.locator(MSG_ASSISTANT)).to_have_count(2, timeout=5000)
        band = page.locator(BOUNDARY).inner_text()
        assert name_a in band and name_b in band, (
            f"boundary band must name owner and current slice, reads: {band!r}")
        page.locator(BOUNDARY_NEW).click()
        expect(page.locator(BOUNDARY)).to_have_count(0, timeout=10000)
        expect(page.locator(MSG_ASSISTANT)).to_have_count(0, timeout=10000)
        _assert_hint_names(page, name_b)

        # 4) pasted picture in the fresh segment: shared upload door, server
        # pair on /stream, booked image-read row.
        with page.expect_response(
                lambda r: r.status == 200 and "/ai/chat/images" in r.url) as up_resp:
            _paste(page, b64=PNG_B64)
        view = up_resp.value.json()["data"]
        assert view["objectKey"].split("/chat-image/")[0].rsplit("/", 1)[-1] == str(pid_b), (
            f"key must be minted under the fresh slice: {view['objectKey']}")
        expect(page.locator(IMAGE_READY)).to_have_count(1, timeout=15000)
        page.locator(INPUT).fill(QUIET_PROMPT)
        body = _send_and_capture_stream(page)
        att = body.get("attachments")
        assert isinstance(att, list) and len(att) == 1 \
            and att[0]["objectKey"] == view["objectKey"] \
            and att[0]["fileName"] == view["fileName"], (
                f"pasted picture must ride the stream as the server pair, got {att!r}")

        # 5) collapse mid-run through the close door (pinned form collapses
        # only this way); the seal lands one count on the bubble behind the
        # closed dock, and re-entering the session closes the count at zero.
        page.locator(CLOSE).click()
        expect(page.locator(DOCK)).not_to_be_visible(timeout=10000)
        expect(page.locator(BADGE)).to_have_text("1", timeout=180000)
        page.locator(FAB).click()
        expect(page.locator(DOCK)).to_be_visible(timeout=10000)
        expect(page.locator(MSG_ASSISTANT)).to_have_count(1, timeout=15000)
        page.locator(CLOSE).click()
        expect(page.locator(DOCK)).not_to_be_visible(timeout=10000)
        expect(page.locator(BADGE)).to_have_count(0, timeout=10000)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator(FAB)).to_be_visible(timeout=15000)
        expect(page.locator(BADGE)).to_have_count(0, timeout=10000)
        # The pinned form rode the reload too: the resident shape is back.
        page.locator(FAB).click()
        expect(page.locator(PIN)).to_have_attribute("aria-pressed", "true", timeout=5000)

        assert_no_errors(collectors, "chat dock full journey")
    finally:
        for pid in (pid_a, pid_b):
            sql_query(f"DELETE FROM tp_ai_call_log WHERE project_id = {pid}")
            purge_project(api_client, pid)
