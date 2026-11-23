""" — dock-entry unread badge spectrum (16 §97).

Real chain browser -> admin 8081 -> core 8083, zero mocks. The counting rule
under test (adjudication §two.3, D7 settled):

1) a turn SEALED while the dock is closed counts one — the fold happens at the
 seal point, never frame by frame, so the number that appears on the bubble
 is the sealed completions gathered behind a collapsed dock;
2) the ledger is the user's local anchor and rides a reload: refreshing the
 page with the dock still closed keeps the honest number on screen;
3) entering the session clears that session's anchor, and the clear is
 observed where it is observable. The bubble is UNMOUNTED while the dock is
 open (fabVisible mutual exclusion), so a count(0) right after reopening
 would be a false green — the reopen-clear assertion is taken one collapse
 later, reading the same in-memory ledger the bubble renders from; a reload
 then proves the cleared anchor rode localStorage, not a render artifact.

The dock body is v-show resident : collapsing it right
after Enter does NOT interrupt the stream — the server keeps answering and
the panel folds at seal while hidden, which is exactly the scenario this
spectrum drives. Headless=new plus the console/network/Issues zero-tolerance
sweep ride the shared conftest collectors.
"""
import pytest
from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    purge_project,
    seed_project,
)
from playwright.sync_api import expect

pytestmark = [pytest.mark.timeout(420)]

FAB = '[data-test="ai-chat-fab"]'
BADGE = '[data-test="chat-fab-badge"]'
DOCK = '[data-test="ai-chat-dock"]'
INPUT = '[data-test="chat-dock-input"]'


def probe_unread_ledger(page, stage):
    """Pin one evidence line per stage: raw tp_chat_unread_* localStorage.

    Splits root cause without guessing — an entry still present while the
    dock is OPENED proves the clear branch never ran (in-memory race); an
    entry reappearing only after a reload proves the storage write-back is
    missing.
    """
    raw = page.evaluate(
        "() => Object.fromEntries(Object.keys(localStorage)"
        ".filter(k => k.startsWith('tp_chat_unread_'))"
        ".map(k => [k, localStorage.getItem(k)]))"
    )
    print(f"[UNREAD-LEDGER] {stage}: {raw}", flush=True)
    return raw


def test_closed_dock_seal_counts_reload_persists_reopen_clears(
        page, collectors, api_client, uniq):
    """Ask behind the open dock, collapse it, the sealed answer shows one;
    reload keeps it; reopening the dock clears the session anchor (observed
    after collapsing again, since the bubble is unmounted while the dock is
    open) and a later reload proves the clear rode the local ledger."""
    pid = seed_project(api_client, uniq, "UNREAD")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/cases?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator(FAB)).to_be_visible(timeout=15000)
        # Quiet state, observed while the bubble is mounted: a fresh browser
        # context has an empty ledger, so no number may show before anything
        # was ever asked (third state of the three-state badge).
        expect(page.locator(BADGE)).to_have_count(0)

        page.locator(FAB).click()
        expect(page.locator(DOCK)).to_be_visible(timeout=10000)

        page.locator(INPUT).fill("只回答两个字：收到")
        page.locator(INPUT).press("Enter")

        # Collapse the dock with a click-outside (un-pinned default). The
        # stream keeps running hidden (v-show resident, ) — the fold can
        # only happen while chatDockState.visible is false, which is the whole
        # point of this spectrum.
        page.mouse.click(300, 500)
        expect(page.locator(DOCK)).not_to_be_visible(timeout=10000)

        # Seal point: exactly one sealed completion lands on the bubble.
        # Hard upper bound 1 — draft cards are not expected for this prompt,
        # and a per-frame counter would blow past this assertion instantly.
        expect(page.locator(BADGE)).to_have_text("1", timeout=180000)
        probe_unread_ledger(page, "after-seal")

        # Persistence half: reload with the dock still closed -> the number
        # comes back from the user's local ledger, not from memory.
        page.reload(wait_until="domcontentloaded")
        expect(page.locator(FAB)).to_be_visible(timeout=15000)
        expect(page.locator(BADGE)).to_have_text("1", timeout=10000)
        probe_unread_ledger(page, "after-reload-closed")

        # Entering the session zeroes that session's anchor. The bubble is
        # unmounted while the dock is open, so the observable half of this
        # step is the collapse that follows: the reopened dock folds the
        # session anchor in memory, and once the bubble renders again it must
        # show nothing.
        page.locator(FAB).click()
        expect(page.locator(DOCK)).to_be_visible(timeout=10000)
        # Evidence while the dock is OPENED and the thread restored: an entry
        # still on disk here proves the restore branch never ran the clear
        # (the exact stage the first run turned red at). The assistant row
        # from the history door is the deterministic restore-complete signal.
        expect(page.locator(
            '[data-test="chat-msg"][data-role="assistant"]')).to_have_count(
                1, timeout=15000)
        probe_unread_ledger(page, "while-dock-opened")
        page.mouse.click(300, 500)
        expect(page.locator(DOCK)).not_to_be_visible(timeout=10000)
        expect(page.locator(BADGE)).to_have_count(0, timeout=10000)
        probe_unread_ledger(page, "after-reopen-collapse")

        # Reload half: the cleared anchor rode localStorage (no ghost count
        # resurrected from a stale ledger).
        page.reload(wait_until="domcontentloaded")
        expect(page.locator(FAB)).to_be_visible(timeout=15000)
        expect(page.locator(BADGE)).to_have_count(0, timeout=10000)

        assert_no_errors(collectors, "unread badge seal-count spectrum")
    finally:
        purge_project(api_client, pid)
