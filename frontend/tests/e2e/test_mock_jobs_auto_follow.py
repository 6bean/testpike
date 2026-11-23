# -*- coding: utf-8 -*-
"""R62 evidence window for the mock drafting-jobs auto-follow surface.

Real chain, zero mocks on the business path: one live job is filed through
the dialog and rides admin -> MQ -> ai-service -> the live LLM provider.
The drawer must (a) show the following badge while anything is pending,
(b) walk that job from queued to its honest terminal WITHOUT a single
manual reload click, (c) self-stop the badge when nothing is pending any
more. The worst-scale door seeds 30 terminal (cancelled) job rows as
fixture data — terminal rows are never consumed by the worker, so the LLM
queue stays honest — and asserts the drawer geometry never grows while
rows accumulate (R62 fixed-height sliding-window law) and that the beat
picks the new rows up with zero interaction.
Shared doors (spaces/gates/status truth) are imported from the AI
drafting E2E module, never copied (R40 single source).
"""
import re
import time
import uuid

import pytest
from playwright.sync_api import expect

pytestmark = [pytest.mark.timeout(600), pytest.mark.xdist_group("mock-plane-live")]

from conftest import assert_no_errors, sql_query
from test_t365b_mock_ai_drafting import (
    MASTER_KEY, PROMPT, SUB_KEY, _configs, _job_status, _mk_project,
    _mk_space, _set_gate, _wb)

BADGE = '[data-test="mock-ai-jobs-following"]'
DRAWER = '[data-test="mock-ai-jobs-drawer"]'
CARD = '[data-test="mock-ai-job-card"]'
# the badge line must carry no "refresh yourself" imperative wording (R62 ④)
FORBIDDEN_WORD = "刷新"
TERMINAL_LABEL = {"preview": "待勾选确认", "consumed": "已消费",
                  "failed": "失败", "cancelled": "已取消"}


def _seed_cancelled_rows(space_id, count=30):
    """Worst-scale fixture rows in a terminal status (never LLM-consumed)."""
    sql_query(
        "INSERT INTO tp_mock_ai_job (space_id, job_type, input_json, status) "
        f"SELECT {space_id}, 'nl-stub', '{{\"prompt\": \"worst-scale fixture row\"}}'::jsonb, "
        "'cancelled' FROM generate_series(1, " + str(count) + ")")


def _drawer_box(page):
    return page.locator(DRAWER).first.bounding_box()


def test_mock_jobs_auto_follow_badge_progression_and_fixed_geometry(logged_in, api_client, collectors):
    """Badge on while pending -> auto progression -> self-stop -> fixed-scale drawer."""
    page = logged_in
    uniq = uuid.uuid4().hex[:8]
    pid = _mk_project(api_client, f"AutoFollow {uniq}", f"af{uniq}")
    space_id = _mk_space(api_client, pid, f"af-space-{uniq}", f"af{uniq}")
    job_id = None
    try:
        cfgs = _configs(api_client)
        assert MASTER_KEY in cfgs and SUB_KEY in cfgs, "ai.mock gate keys missing"
        _set_gate(api_client, cfgs[MASTER_KEY], "1")
        _set_gate(api_client, cfgs[SUB_KEY], "1")

        page.goto(_wb(space_id, pid), wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-docs-page"]')).to_be_visible(timeout=15000)

        # ---- file one real job through the dialog: drawer opens itself ----
        page.locator('[data-test="mock-docs-ai-draft"]').click()
        dialog = page.locator('[data-test="mock-ai-dialog"]')
        expect(dialog).to_be_visible(timeout=5000)
        dialog.locator('[data-test="mock-ai-f-prompt"]').fill(PROMPT)
        page.locator('[data-test="mock-ai-file"]').click()
        drawer = page.locator(DRAWER)
        expect(drawer).to_be_visible(timeout=8000)
        first_card = page.locator(CARD).first
        expect(first_card).to_contain_text("排队中", timeout=8000)
        # once worst-scale rows are seeded they sort on top (id DESC), so the
        # live job is tracked through its pinned #id line, never by position
        # (the digit lookahead keeps #3 from matching a #31 card)

        # ---- door A: the following badge is on, no imperative refresh wording ----
        badge = page.locator(BADGE)
        expect(badge).to_be_visible(timeout=5000)
        badge_text = (badge.text_content() or "").strip()
        assert FORBIDDEN_WORD not in badge_text, \
            f"badge falls back to a refresh instruction (R62 ④): {badge_text}"
        job_id = int(sql_query(
            f"SELECT id FROM tp_mock_ai_job WHERE space_id = {space_id} "
            "AND status <> 'cancelled' ORDER BY id DESC LIMIT 1"))
        live_card = page.locator(CARD).filter(has_text=re.compile(rf"#{job_id}(?!\d)"))

        # baseline geometry of the drawer while only one card is inside
        box_thin = _drawer_box(page)

        # ---- door B: worst scale + zero interaction: seeded terminal rows must
        # appear by the auto-follow beat alone (no click, no reload) ----
        _seed_cancelled_rows(space_id)
        expect(page.locator(CARD)).to_have_count(31, timeout=20000)
        box_wide = _drawer_box(page)
        for dim in ("width", "height"):
            assert abs(box_thin[dim] - box_wide[dim]) <= 1, \
                f"drawer {dim} grew with content: {box_thin[dim]} -> {box_wide[dim]} (R62 ②)"
        assert badge.count() == 1, "badge vanished while the job was still pending"

        # ---- door C: the live job walks to its honest terminal, screen-side,
        # purely by the beat; the DB stays the cross-check truth ----
        deadline = time.time() + 240
        status = ""
        while time.time() < deadline:
            status = _job_status(job_id)
            if status in TERMINAL_LABEL:
                break
            time.sleep(3)
        assert status in TERMINAL_LABEL, f"job {job_id} never reached a terminal state"
        expect(live_card).to_contain_text(TERMINAL_LABEL[status], timeout=20000)

        # ---- door D: badge self-stops on an all-terminal list (no clicks) ----
        # cancel any non-fixture pending rows so the space is fully idle
        expect(page.locator(BADGE)).to_have_count(0, timeout=20000)

        # ---- door E: re-entry keeps truth and stays idle ----
        page.keyboard.press("Escape")
        expect(drawer).to_be_hidden(timeout=5000)
        page.locator('[data-test="mock-docs-ai-jobs"]').click()
        expect(drawer).to_be_visible(timeout=5000)
        expect(page.locator(CARD)).to_have_count(31, timeout=8000)
        expect(page.locator(BADGE)).to_have_count(0, timeout=5000)

        assert_no_errors(collectors, "mock jobs auto-follow R62 window")
    finally:
        try:
            cfgs = _configs(api_client)
            for key in (MASTER_KEY, SUB_KEY):
                if key in cfgs and cfgs[key]["configValue"] != "0":
                    _set_gate(api_client, cfgs[key], "0")
        except Exception as exc:
            print(f"!! gate restore failed (manual check): {exc}")
        try:
            sql_query(f"DELETE FROM tp_mock_ai_job WHERE space_id = {space_id}; "
                      f"DELETE FROM tp_mock_stub WHERE space_id = {space_id}; "
                      f"DELETE FROM tp_mock_scenario WHERE space_id = {space_id}; "
                      f"DELETE FROM tp_mock_space WHERE id = {space_id}; "
                      f"DELETE FROM tp_user_project WHERE project_id = {pid}; "
                      f"DELETE FROM tp_project WHERE id = {pid};")
        except Exception as exc:
            print(f"!! fixture sweep failed (manual check): {exc}")
