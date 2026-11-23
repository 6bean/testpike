"""-③ case-gap handoff E2E at worst-case scale (browser + real backend, no mocks).

Spectrum: a throwaway project carrying 32 requirements with zero covering cases
-> the AiGenerateView gap chip reports the server-computed count -> one click
hands the rows into the batch entry (mode flips, every row filled) -> a second
chip click refreshes the count after one more requirement is added.
All render assertions are hard (rule 5d: no count>0 conditional wrappers).
The throwaway project is purged afterwards; factory demo projects untouched.
"""
import pathlib
import re

import pytest
from playwright.sync_api import expect

from conftest import (
    BASE_URL,
    assert_no_errors,
    assert_no_browser_issues,
    slot_psql,
)

# One browser session, 33 sequential requirement POSTs + a 32-row batch render:
# stay inside the per-file default (300s) with margin, no module-wide waits.
pytestmark = pytest.mark.timeout(480)

SHOT_DIR = pathlib.Path(__file__).parent / "artifacts" / "ai-gen-gap"
SHOT_DIR.mkdir(parents=True, exist_ok=True)

REQ_COUNT = 32


def _mk_project_with_gap(api, uniq, count):
    """Create a throwaway project and `count` uncovered requirements via real API."""
    r = api.post("/projects", json={"name": f"GapE2E_{uniq}", "code": f"GE{uniq[:6]}"})
    assert r.status_code == 200, f"project create http={r.status_code} {r.text[:200]}"
    body = r.json()
    assert body.get("success") is True, f"project create body={body}"
    pid = body["data"]
    for i in range(count):
        rr = api.post(f"/projects/{pid}/requirements", json={
            "title": f"缺口需求点-{i}-{uniq}",
            "acceptanceCriteria": "给定前置条件时系统按验收标准响应",
            "chapter": None,
        })
        assert rr.status_code == 200 and rr.json().get("success") is True, \
            f"requirement create http={rr.status_code} {rr.text[:200]}"
    return pid


def _open_ai_gen_for_project(page, pid):
    """Deep-link lands the project id (URL priority of the header context)."""
    page.goto(f"{BASE_URL}/#/ai/generate?projectId={pid}", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="ai-gen-form"]')).to_be_visible(timeout=15000)


def _purge_throwaway(api, pid):
    """Physically remove a throwaway fixture project.

    The project is non-empty by design, and the product purge door refuses a
    force-less delete (measured 11-08: three GapE2E projects survived every
    run because the old call ignored the refusal). The assert keeps any future
    leak loud instead of silent.
    """
    r = api.delete(f"/projects/{pid}/purge?force=true")
    assert r.status_code == 200, f"purge failed http={r.status_code} {r.text[:200]}"


def test_gap_chip_reports_count_and_one_click_fills_batch(logged_in, api_client, collectors, uniq):
    page = logged_in
    pid = _mk_project_with_gap(api_client, uniq, REQ_COUNT)
    try:
        _open_ai_gen_for_project(page, pid)

        # 1. chip visible with the server-computed count at worst-case scale
        chip = page.locator('[data-test="ai-gen-gap-chip"]')
        expect(chip).to_be_visible(timeout=15000)
        expect(chip).to_contain_text(str(REQ_COUNT), timeout=15000)

        # 2. one click hands the rows into the batch entry
        page.locator('[data-test="ai-gen-gap-fill"]').click()
        expect(page.locator('[data-test="ai-gen-batch-form"]')).to_be_visible()
        rows = page.locator('[data-test^="ai-gen-batch-input-"]')
        expect(rows).to_have_count(REQ_COUNT)
        # newest-first contract: row 0 carries the latest requirement, the last
        # row the first one created (server ORDER BY id DESC — pinned here so a
        # sort regression turns red instead of silently reordering the batch).
        # to_have_value reads the live value property (to_contain_text sees the
        # empty textContent of a <textarea> — measured twice this window).
        first = page.locator('[data-test="ai-gen-batch-input-0"]')
        expect(first).to_have_value(
            re.compile(rf"^缺口需求点-{REQ_COUNT - 1}-{uniq}\n验收标准："), timeout=10000)
        last = page.locator(f'[data-test="ai-gen-batch-input-{REQ_COUNT - 1}"]')
        expect(last).to_have_value(f"缺口需求点-0-{uniq}\n验收标准：给定前置条件时系统按验收标准响应")
        # count footer reflects the filled rows (hard assertion, not toast-only)
        expect(page.locator('[data-test="ai-gen-batch-count"]')).to_contain_text(str(REQ_COUNT))

        # 3. evidence frame for the vision round (full page, filled state)
        page.screenshot(path=str(SHOT_DIR / "filled-batch.png"), full_page=True)

        # 4. adding one more requirement refreshes the chip on the next visit
        rr = api_client.post(f"/projects/{pid}/requirements", json={
            "title": f"缺口需求点-33-{uniq}",
            "acceptanceCriteria": None,
            "chapter": None,
        })
        assert rr.status_code == 200 and rr.json().get("success") is True
        _open_ai_gen_for_project(page, pid)
        chip2 = page.locator('[data-test="ai-gen-gap-chip"]')
        expect(chip2).to_contain_text(str(REQ_COUNT + 1), timeout=15000)

        assert_no_browser_issues(collectors["browser_issues"], "case-gap handoff")
        assert_no_errors(collectors, "case-gap handoff")
    finally:
        _purge_throwaway(api_client, pid)


def test_no_chip_when_project_fully_covered(logged_in, api_client, collectors, uniq):
    """A project whose single requirement has a live case shows no chip at all
    (hard two-branch posture: absent is asserted, not silently skipped)."""
    page = logged_in
    pid = _mk_project_with_gap(api_client, uniq, 1)
    try:
        # cover the requirement through the real case API (no generation run)
        reqs = api_client.get(f"/projects/{pid}/requirements")
        body = reqs.json().get("data") or {}
        recs = body.get("list") or []
        assert len(recs) == 1, f"requirement prep failed: {body}"
        cr = api_client.post("/cases", json={
            "title": f"覆盖用例-{uniq}",
            "projectId": pid,
        })
        assert cr.status_code == 200 and cr.json().get("success") is True, \
            f"case create http={cr.status_code} {cr.text[:200]}"
        case_id = cr.json()["data"] if not isinstance(cr.json()["data"], dict) \
            else cr.json()["data"].get("id") or cr.json()["data"].get("caseId")
        assert case_id, f"case create returned no id: {cr.text[:200]}"
        lr = api_client.post(f"/requirements/{recs[0]['id']}/cases", json={"caseIds": [case_id]})
        assert lr.status_code == 200 and lr.json().get("success") is True, \
            f"link case to requirement http={lr.status_code} {lr.text[:200]}"

        _open_ai_gen_for_project(page, pid)
        chip = page.locator('[data-test="ai-gen-gap-chip"]')
        expect(chip).to_have_count(0, timeout=15000)

        assert_no_browser_issues(collectors["browser_issues"], "case-gap no-chip")
        assert_no_errors(collectors, "case-gap no-chip")
    finally:
        _purge_throwaway(api_client, pid)


def test_link_tag_and_requirement_id_survive_browser_submit(logged_in, api_client, collectors, uniq):
    """ residual spectrum (browser submit, not the API-level gate twin).

 A gap-filled batch carries the per-row link chip, the submitted rows
 persist their requirement ids through the real admin -> core chain (read
 back from the slot database), and covering one requirement with a real
 case decrements the server-computed gap chip. Generation success itself
 depends on the LLM channel and is NOT asserted here (honest scope: the
 linkage contract lives at submit/persist time, the channel availability is
 reported separately).
 """
    page = logged_in
    psql = slot_psql()
    n = 3
    pid = _mk_project_with_gap(api_client, uniq, n)
    try:
        _open_ai_gen_for_project(page, pid)
        body = api_client.get(f"/projects/{pid}/requirements").json().get("data") or {}
        recs = body.get("list") or []
        assert len(recs) == n, f"requirement prep failed: {body}"
        ids_desc = sorted((int(r["id"]) for r in recs), reverse=True)

        # 1. every filled row shows its own link chip with the true id (newest-first)
        page.locator('[data-test="ai-gen-gap-fill"]').click()
        expect(page.locator('[data-test="ai-gen-batch-form"]')).to_be_visible()
        for idx, rid in enumerate(ids_desc):
            tag = page.locator(f'[data-test="ai-gen-batch-link-{idx}"]')
            expect(tag).to_be_visible(timeout=10000)
            expect(tag).to_have_text(f"已关联需求点 #{rid}")

        # 2. submit through the browser (estimate confirm dialog -> create)
        page.locator('[data-test="ai-gen-batch-submit"]').click()
        page.get_by_role("button", name="确认生成").click()
        expect(page.get_by_text("批量生成任务已提交", exact=False)).to_be_visible(timeout=20000)

        # 3. read back: the newest batch of this project persisted all ids
        batch_id = int(psql(
            f"SELECT id FROM tp_ai_batch WHERE project_id={pid} ORDER BY id DESC LIMIT 1"))
        assert batch_id, "batch row not found after browser submit"
        stored = [int(v) for v in psql(
            f"SELECT requirement_id FROM tp_ai_task WHERE batch_id={batch_id} "
            "AND requirement_id IS NOT NULL ORDER BY requirement_id DESC").splitlines() if v]
        assert stored == ids_desc, \
            f"browser submit lost linkage: persisted {stored}, expected {ids_desc}"

        # 4. coverage decrements the gap chip (same link row the review-approve
        # path writes; the LLM generation run itself is not asserted)
        cr = api_client.post("/cases", json={"title": f"联动覆盖用例-{uniq}", "projectId": pid})
        assert cr.status_code == 200 and cr.json().get("success") is True, \
            f"case create http={cr.status_code} {cr.text[:200]}"
        data = cr.json()["data"]
        case_id = data if not isinstance(data, dict) else (data.get("id") or data.get("caseId"))
        assert case_id, f"case create returned no id: {cr.text[:200]}"
        lr = api_client.post(f"/requirements/{ids_desc[0]}/cases", json={"caseIds": [case_id]})
        assert lr.status_code == 200 and lr.json().get("success") is True, \
            f"link case http={lr.status_code} {lr.text[:200]}"
        _open_ai_gen_for_project(page, pid)
        expect(page.locator('[data-test="ai-gen-gap-chip"]')).to_contain_text(str(n - 1), timeout=15000)

        assert_no_browser_issues(collectors["browser_issues"], "linkage submit")
        assert_no_errors(collectors, "linkage submit")
    finally:
        _purge_throwaway(api_client, pid)
