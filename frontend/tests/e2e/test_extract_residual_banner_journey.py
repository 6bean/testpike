

import re

from conftest import assert_no_errors
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect

from test_f2_extraction import (doc_page, open_extract_dialog, sql,
                                wait_extraction_ready)

DOC_ID = 9085
PROJECT_ID = 201037
CHUNK_ID = 148477
DOC_ROW = "强中台_PRD_PR001-01"
LEDGER_PENDING_SQL = (
    "SELECT s->>'title' FROM tp_doc_extract_ledger l, "
    "jsonb_array_elements(l.steps_json::jsonb) s "
    f"WHERE l.document_id={DOC_ID} AND s->>'state' <> 'done' "
    "AND l.id = (SELECT MAX(id) FROM tp_doc_extract_ledger "
    f"WHERE document_id={DOC_ID})"
)


def _pending_chunk_ids():
    titles = sql(LEDGER_PENDING_SQL)
    return {int(m.group(1)) for m in re.finditer(r"块#(\d+)", titles)}


def _evidence_for_chunk():
    rows = sql(
        "SELECT coalesce(s->>'evidence','') FROM tp_doc_extract_ledger l, "
        "jsonb_array_elements(l.steps_json::jsonb) s "
        f"WHERE l.document_id={DOC_ID} AND s->>'title' = '块#{CHUNK_ID}' "
        "AND l.id = (SELECT MAX(id) FROM tp_doc_extract_ledger "
        f"WHERE document_id={DOC_ID})"
    )
    return rows


def test_residual_banner_three_elements_live_journey(page, collectors):
    """banner -> chip #148477 -> reason card + neighbour context (walk is a
    real path) -> one-click directed supplement -> honest terminal state."""
    # ── precondition: shipped anchors are where the ruling file says ──
    assert int(sql(f"SELECT project_id FROM tp_document WHERE id={DOC_ID}")) \
        == PROJECT_ID, "shipped anchor moved: document 9085 no longer in 201037"
    pending_before = _pending_chunk_ids()
    assert CHUNK_ID in pending_before, (
        f"ledger pending set {sorted(pending_before)} no longer contains "
        f"{CHUNK_ID}: a previous run rotated it -- re-pend the step or move "
        "the anchor here deliberately, never let the journey pass silently")

    # ── 1. open the preview through the shipped project's row ──
    doc_page(page, PROJECT_ID)
    page.locator('[data-test="doc-filter-keyword"]').fill(DOC_ROW)
    page.locator('[data-test="doc-search-btn"]').click()
    dialog = open_extract_dialog(page, DOC_ROW)

    # ── 2. banner: what happened + where the fix lives, chip carries the
    #        ledger's own reason on hover (element one of the three) ──
    banner = dialog.locator('[data-test="extract-gap-banner"]')
    expect(banner).to_be_visible(timeout=15000)
    expect(banner).to_contain_text("未产生需求点")
    expect(banner).to_contain_text("「补提此块」入口")
    chip = dialog.locator(f'[data-test="extract-gap-anchor-{CHUNK_ID}"]')
    expect(chip).to_be_visible()
    assert "残账章节" in (chip.get_attribute("title") or ""), \
        "chip hover lost the ledger's own reason sentence (R63 element one)"

    # ── 3. reason card: note verbatim + the size point (element two) ──
    chip.click()
    source = page.locator('[data-test="extract-source-dialog"]')
    expect(source).to_be_visible(timeout=10000)
    card = source.locator('[data-test="extract-source-reason-card"]')
    expect(card).to_be_visible()
    card_text = card.inner_text()
    assert "残账章节" in card_text and "未提取任何条目" in card_text, \
        f"reason card does not echo the ledger note verbatim: {card_text[:200]}"
    assert "本块正文仅 13 字" in card_text, \
        f"size point missing from the reason card: {card_text[:200]}"
    expect(source.locator('[data-test="extract-source-position"]')) \
        .to_contain_text("本块正文 13 字")

    # ── 4. context area: the sliver is shown with its real neighbourhood,
    #       and walking to a neighbour is a path that WORKS (element three
    #       precondition: no dead links) ──
    context = source.locator('[data-test="extract-source-context"]')
    expect(context).to_be_visible(timeout=10000)
    # Shipped shape: the context area renders EXPANDED by default so the
    # neighbour rows are reachable without an extra click (R63 real path).
    # The old blind header click toggled an already-open panel closed -- the
    # rows stayed attached and "visible" during the closing animation, then
    # the neighbour button click hung on a hidden element. Assert the open
    # state instead of toggling it.
    expect(source.locator('.el-collapse-item__header').first) \
        .to_have_class(re.compile(r"is-active"))
    expect(source.locator('[data-test="extract-source-neighbor"]')
           .first).to_be_visible(timeout=10000)
    expect(source.locator('[data-test="extract-source-current-mark"]')) \
        .to_contain_text("本块")
    position_before = source.locator(
        '[data-test="extract-source-position"]').inner_text()
    # A neighbour row near the scroll bottom can sit under the dialog
    # footer: land it in view before clicking (driver hygiene, no sleep --
    # nothing animates now that the panel is never toggled).
    nb_btn = source.locator('[data-test^="extract-source-neighbor-"]').first
    nb_btn.scroll_into_view_if_needed()
    nb_btn.click()
    position_after = source.locator(
        '[data-test="extract-source-position"]').inner_text()
    assert position_after != position_before, \
        "neighbour walk did not actually switch the viewed chunk (dead link)"
    assert source.locator('[data-test="extract-source-reason-card"]').count() == 0, \
        "an anchored (non-pending) row must not fake a residual reason card"

    # ── 5. one-click directed supplement on #148477: reopen the chip, fire
    #       the round, watch the honest terminal state arrive by itself ──
    source.locator('[data-test="extract-source-close-btn"]').click()
    chip.click()
    expect(card).to_be_visible(timeout=10000)
    source.locator('[data-test="extract-supplement-btn"]').click()
    expect(page.get_by_text("已发起定向补提").first).to_be_visible(timeout=15000)
    expect(source).to_be_hidden(timeout=10000)
    # A directed supplement re-runs the full pipeline for the chunk's
    # group (observed live: >150s with two model calls still streaming
    # while the old budget expired), so this leg waits with a widened
    # budget instead of the fresh-extraction default.
    wait_extraction_ready(dialog, timeout_s=480)

    # ── 6. two explicit branches, both anchored on the ledger (not guessed) ──
    pending_after = _pending_chunk_ids()
    if CHUNK_ID not in pending_after:
        assert dialog.locator(
            f'[data-test="extract-gap-anchor-{CHUNK_ID}"]').count() == 0, \
            "ledger says done but the chip still claims the gap (stale banner)"
        if not pending_after:
            expect(banner).to_be_hidden(timeout=15000)
    else:
        assert dialog.locator(
            f'[data-test="extract-gap-anchor-{CHUNK_ID}"]').count() == 1, \
            "ledger still pending but the banner silently dropped the chip"
        assert "复跑一轮仍无条目" in _evidence_for_chunk(), (
            "kept-pending branch must append the honest rerun evidence, got: "
            f"{_evidence_for_chunk()[:200]}")

    # ── 7. reload persistence: the banner re-reads from the server, no local
    #       cache may contradict the ledger ──
    page.locator('[data-test="extract-close-btn"]').click()
    doc_page(page, PROJECT_ID)
    page.locator('[data-test="doc-filter-keyword"]').fill(DOC_ROW)
    page.locator('[data-test="doc-search-btn"]').click()
    dialog2 = open_extract_dialog(page, DOC_ROW)
    pending_reload = _pending_chunk_ids()
    assert pending_reload == pending_after, \
        "ledger drifted between the two reads (replay semantics broken)"
    if CHUNK_ID in pending_reload:
        expect(dialog2.locator(
            f'[data-test="extract-gap-anchor-{CHUNK_ID}"]')).to_be_visible()
    else:
        assert dialog2.locator(
            f'[data-test="extract-gap-anchor-{CHUNK_ID}"]').count() == 0

    assert_no_errors(collectors, "residual banner three-elements live journey")
    assert_no_browser_issues(collectors["browser_issues"],
                             "residual banner three-elements live journey")
